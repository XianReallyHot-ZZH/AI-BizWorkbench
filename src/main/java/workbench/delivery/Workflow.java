package workbench.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import workbench.spec.SpecParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 交付工作流四段链（上游 {@code workbench/workflow.py} 对应物，L13 讲义 D2/D5）：
 * prepare（queued → spec_ready）→ start（→ executing，允许/禁止动作清单）→
 * execute（verify 模式 = verification_only，不产生业务数据副作用）→ evaluate
 * （→ evaluating → 绿 {@code review}/红 {@code rework}，报告落盘 + SHA-256）。
 *
 * <p>不变量（上游 docstring 逐字承接）：「Run the deterministic delivery stages
 * and stop at review or rework … A green Harness report never becomes completed
 * without a separate named review」——绿报告永不自动 completed。
 */
public final class Workflow {

    /** 受控执行失败（上游 ControlledExecutionError：证据随异常保留）。 */
    public static final class ControlledExecutionError extends RuntimeException {
        public final Map<String, Object> evidence;

        public ControlledExecutionError(String message, Map<String, Object> evidence) {
            super(message);
            this.evidence = evidence;
        }
    }

    /** 执行器缝（上游 execution_runner 参数）：输入任务视图，返回结构化证据。 */
    public interface ExecutionRunner extends Function<Map<String, Object>, Map<String, Object>> {}

    /** suite 缝（上游 suite_runner 参数，L13 讲义 D5 新缝①）：返回 schema 1.0 报告。 */
    public interface SuiteRunner {
        Map<String, Object> run();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Workflow() {}

    /** 第一段：Spec 校验（queued → spec_ready；.md + 工作区/受控运行目录内）。 */
    public static Map<String, Object> prepareTask(TaskStore store, String taskId, String actor) {
        Map<String, Object> task = store.get(taskId);
        if (!"queued".equals(task.get("status"))) {
            throw new IllegalArgumentException("只有 queued 任务可以准备 Spec");
        }
        Path specPath = Path.of(String.valueOf(task.getOrDefault("spec_path", "FDE_SPEC.md")))
                .toAbsolutePath().normalize();
        Path workspace = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path runtime = store.path().getParent();
        boolean allowed = specPath.equals(workspace) || specPath.startsWith(workspace)
                || specPath.equals(runtime) || specPath.startsWith(runtime);
        if (!specPath.toString().toLowerCase().endsWith(".md") || !allowed) {
            throw new IllegalArgumentException("Spec 必须是工作区或受控运行目录内的 Markdown 文件");
        }
        Map<String, String> spec;
        try {
            spec = SpecParser.load(specPath).asMap();
        } catch (IOException error) {
            throw new UncheckedIOException("Spec 读取失败：" + specPath, error);
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("spec_path", specPath.toString());
        return store.transition(taskId, "spec_ready", "结构化 Spec 已校验", actor, evidence,
                Map.of("spec", spec));
    }

    /** 第二段：受控执行开跑（spec_ready/rework → executing；动作清单显式化）。 */
    public static Map<String, Object> startTask(TaskStore store, String taskId, String actor) {
        Map<String, Object> task = store.get(taskId);
        if (!"spec_ready".equals(task.get("status")) && !"rework".equals(task.get("status"))) {
            throw new IllegalArgumentException("只有 spec_ready 或 rework 任务可以开始执行");
        }
        boolean codeWrites = "codex".equals(task.get("execution_mode"));
        List<String> allowedActions = new ArrayList<>(List.of(
                "read_spec", "read_workspace", "run_blocking_eval", "run_workspace_shell"));
        if (codeWrites) {
            allowedActions.add("write_code_in_task_scope");
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("requirement_id", task.getOrDefault("requirement_id", ""));
        evidence.put("business_refs", task.getOrDefault("business_refs", List.of()));
        evidence.put("execution_mode", task.getOrDefault("execution_mode", "verify"));
        evidence.put("write_scope", task.getOrDefault("write_scope", List.of()));
        evidence.put("execution_timeout_seconds", task.getOrDefault("execution_timeout_seconds", 900));
        evidence.put("allowed_actions", allowedActions);
        evidence.put("forbidden_actions", List.of(
                "write_runtime_database", "approve_business_document", "skip_eval"));
        return store.transition(taskId, "executing", "开始受控执行", actor, evidence, Map.of());
    }

    /** 第三段：受控动作（默认 verification_only——不产生业务数据副作用）。 */
    public static Map<String, Object> executeTask(TaskStore store, String taskId, String actor,
            ExecutionRunner executionRunner) {
        Map<String, Object> task = store.get(taskId);
        if (!"executing".equals(task.get("status"))) {
            throw new IllegalArgumentException("只有 executing 任务可以执行受控动作");
        }
        Map<String, Object> evidence = executionRunner == null ? verificationOnlyEvidence() : null;
        if (executionRunner != null) {
            Map<String, Object> produced = executionRunner.apply(task);
            if (produced == null) {
                throw new IllegalArgumentException("执行器必须返回结构化证据");
            }
            evidence = produced;
        }
        Map<String, Object> updated = store.appendEvent(taskId, "受控执行阶段完成", actor, evidence);
        if (Boolean.FALSE.equals(evidence.get("success"))) {
            throw new ControlledExecutionError(
                    String.valueOf(evidence.getOrDefault("message", "受控执行失败")), evidence);
        }
        return updated;
    }

    private static Map<String, Object> verificationOnlyEvidence() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("mode", "verification_only");
        evidence.put("changed_files", List.of());
        evidence.put("message", "未配置代码写入执行器；本轮只验证当前候选，不产生业务数据副作用");
        return evidence;
    }

    /**
     * 第四段：统一阻断级 Eval（executing → evaluating → 绿 review / 红 rework）。
     * 报告落 {@code reports/{taskId}-harness-blocking.json} + SHA-256 入 evidence 与报告。
     */
    public static Map<String, Object> evaluateTask(TaskStore store, String taskId, String actor,
            SuiteRunner suiteRunner) {
        Map<String, Object> task = store.get(taskId);
        if (!"executing".equals(task.get("status"))) {
            throw new IllegalArgumentException("只有 executing 任务可以进入评测");
        }
        store.transition(taskId, "evaluating", "运行统一阻断级 Eval", actor, null, Map.of());
        Map<String, Object> report = suiteRunner.run();
        Path reportDir = store.path().getParent().resolve("reports");
        try {
            Files.createDirectories(reportDir);
        } catch (IOException error) {
            throw new UncheckedIOException("报告目录创建失败：" + reportDir, error);
        }
        Path reportPath = reportDir.resolve(taskId + "-harness-blocking.json");
        String reportRef = "reports/" + reportPath.getFileName();
        report.put("report_path", reportRef);
        byte[] payload;
        try {
            payload = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
            Files.write(reportPath, payload);
        } catch (IOException error) {
            throw new UncheckedIOException("Eval 报告写盘失败：" + reportPath, error);
        }
        report.put("report_sha256", sha256(payload));
        Map<String, Object> summary = report.get("summary") instanceof Map<?, ?> map
                ? cast(map) : Map.of();
        Object decision = summary.get("decision");
        Object blockingFailed = summary.get("blocking_failed");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("decision", decision);
        evidence.put("blocking_failed", blockingFailed);
        evidence.put("report_path", reportRef);
        evidence.put("report_sha256", report.get("report_sha256"));
        boolean green = blockingFailed != null && ((Number) blockingFailed).intValue() == 0
                && "pass".equals(decision);
        if (green) {
            return store.transition(taskId, "review", "阻断级 Eval 已通过，等待具名人工审核",
                    actor, evidence, Map.of("result", report));
        }
        return store.transition(taskId, "rework", "阻断项未通过，退回最小修复",
                actor, evidence, Map.of("result", report));
    }

    /** 编排四段并停在人审前（异常处置上游同形：执行失败 → rework 留证据；其余 → failed 保留原因）。 */
    public static Map<String, Object> runTask(TaskStore store, String taskId, String actor,
            SuiteRunner suiteRunner, ExecutionRunner executionRunner) {
        try {
            Map<String, Object> task = store.get(taskId);
            if ("queued".equals(task.get("status"))) {
                prepareTask(store, taskId, actor);
            }
            startTask(store, taskId, actor);
            executeTask(store, taskId, actor, executionRunner);
            return evaluateTask(store, taskId, actor, suiteRunner);
        } catch (ControlledExecutionError error) {
            Map<String, Object> task = store.get(taskId);
            if ("executing".equals(task.get("status"))) {
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("error_type", error.getClass().getSimpleName());
                evidence.put("execution", error.evidence);
                return store.transition(taskId, "rework", "受控执行失败，保留证据并等待有界重试",
                        actor, evidence, Map.of("error", error.getMessage()));
            }
            throw error;
        } catch (RuntimeException error) {
            Map<String, Object> task = store.get(taskId);
            String status = String.valueOf(task.get("status"));
            if (!List.of("completed", "failed", "rework").contains(status)) {
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("error_type", error.getClass().getSimpleName());
                return store.transition(taskId, "failed", "工作流异常，保留原因",
                        actor, evidence, Map.of("error", String.valueOf(error.getMessage())));
            }
            throw error;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new TaskStore.StoreFailure("SHA-256 不可用", error);
        }
    }
}
