package workbench.bootstrap;

import workbench.cli.Args;
import workbench.cli.CommandRegistry;
import workbench.cli.JsonOut;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * L01 五命令：任务账 + 命令证据账 + 完整性检查（镜像 workbench/bootstrap.py 的
 * _cmd_* 命令入口段；输出键序、错误词面、退出码逐字同形）。
 *
 * <p>四职责分工（上游辅导资料 §4）：本类只做"命令入口"——接收参数、输出 JSON、
 * 返回退出码，不含业务判断；任务与记录逻辑在 {@link Ledger#chainError}/
 * {@link Ledger#digestProblems}，存储在 {@link Ledger}，测试在合同测试套件。
 */
public final class BootstrapCommands {

    private BootstrapCommands() {}

    /** 接入注册表缝（幂等；镜像 bootstrap.register_commands）。 */
    public static void register(CommandRegistry registry) {
        registry.register("workbench-init", BootstrapCommands::init);
        registry.register("workbench-project-add", BootstrapCommands::projectAdd);
        registry.register("workbench-task-create", BootstrapCommands::taskCreate);
        registry.register("workbench-evidence-add", BootstrapCommands::evidenceAdd);
        registry.register("workbench-status", BootstrapCommands::status);
    }

    // ---- workbench-init（镜像 _cmd_init：同 owner 幂等，异 owner 拒绝）----

    private static int init(String[] argv) throws SQLException {
        Args args = new Args(argv, Set.of("--runtime-dir", "--name", "--owner"), Set.of());
        Path runtimeDir = Path.of(args.require("--runtime-dir"));
        String owner = args.require("--owner");
        try (Ledger ledger = Ledger.open(runtimeDir, true)) {
            Map<String, Object> existing = ledger.workbenchRow();
            if (existing != null) {
                if (!owner.equals(existing.get("owner"))) {
                    return JsonOut.fail("工作台已存在（所有者 %s），不能被另一个所有者覆盖"
                            .formatted(existing.get("owner")));
                }
                JsonOut.emit(JsonOut.ordered("ok", true, "flowerp_connected", Ledger.FLOWERP_CONNECTED,
                        "workbench", workbenchPayload(existing.get("workbench_id"),
                                existing.get("name"), existing.get("owner"), existing.get("version"))));
                return 0;
            }
            String name = args.optional("--name", Ledger.DEFAULT_WORKBENCH_NAME);
            String workbenchId = UUID.randomUUID().toString().replace("-", "");
            ledger.insertWorkbench(workbenchId, name, owner);
            JsonOut.emit(JsonOut.ordered("ok", true, "flowerp_connected", Ledger.FLOWERP_CONNECTED,
                    "workbench", workbenchPayload(workbenchId, name, owner, Ledger.WORKBENCH_VERSION)));
            return 0;
        }
    }

    private static Map<String, Object> workbenchPayload(Object workbenchId, Object name,
                                                        Object owner, Object version) {
        return JsonOut.ordered("workbench_id", workbenchId, "name", name,
                "owner", owner, "version", version);
    }

    // ---- workbench-project-add（镜像 _cmd_project_add）----

    private static int projectAdd(String[] argv) throws SQLException {
        Args args = new Args(argv,
                Set.of("--runtime-dir", "--project-id", "--name", "--path", "--purpose"), Set.of());
        try (Ledger ledger = Ledger.open(Path.of(args.require("--runtime-dir")), false)) {
            if (ledger == null || ledger.workbenchRow() == null) {
                return JsonOut.fail(Ledger.UNINITIALIZED);
            }
            String projectId = args.require("--project-id");
            if (ledger.projectExists(projectId)) {
                return JsonOut.fail("项目编号已存在：%s，不覆盖旧项目".formatted(projectId));
            }
            String name = args.require("--name");
            String path = args.optional("--path", ".");
            String purpose = args.optional("--purpose", "");
            ledger.insertProject(projectId, name, path, purpose);
            JsonOut.emit(JsonOut.ordered("ok", true, "flowerp_connected", Ledger.FLOWERP_CONNECTED,
                    "project", JsonOut.ordered("project_id", projectId, "name", name,
                            "path", path, "purpose", purpose)));
            return 0;
        }
    }

    // ---- workbench-task-create（镜像 _cmd_task_create）----

    private static int taskCreate(String[] argv) throws SQLException {
        Args args = new Args(argv, Set.of("--runtime-dir", "--project-id", "--task-id",
                "--request", "--title", "--requirement-id", "--actor", "--spec-file",
                "--problem-file", "--prerequisite-task"), Set.of());
        // --request / --title 互斥且必有一（argparse mutually exclusive group 同形，
        // 在解析期判，与上游 argparse 时机一致）
        if (args.has("--request") == args.has("--title")) {
            throw new Args.UsageException("one of the arguments --request --title is required");
        }
        try (Ledger ledger = Ledger.open(Path.of(args.require("--runtime-dir")), false)) {
            if (ledger == null || ledger.workbenchRow() == null) {
                return JsonOut.fail(Ledger.UNINITIALIZED);
            }
            String projectId = args.require("--project-id");
            String taskId = args.require("--task-id");
            if (!ledger.projectExists(projectId)) {
                return JsonOut.fail("项目不存在：%s，任务必须有已登记项目".formatted(projectId));
            }
            if (ledger.taskExists(taskId)) {
                return JsonOut.fail("任务编号已存在：%s，原任务与记录保持不变".formatted(taskId));
            }
            String prerequisite = args.optional("--prerequisite-task", "").strip();
            if (!prerequisite.isEmpty()) {
                // L04 前置门：B 单必须绑定已具名接受的 A 单，缺失或未接受都拒绝，不静默链接
                if (!ledger.taskExists(prerequisite)) {
                    return JsonOut.fail("prerequisite_task_missing: 前置任务不存在：%s，任务未创建"
                            .formatted(prerequisite));
                }
                if (!"accepted".equals(ledger.taskState(prerequisite))) {
                    return JsonOut.fail("prerequisite_not_accepted: 前置任务未具名接受：%s，任务未创建"
                            .formatted(prerequisite));
                }
            }
            String request = args.has("--request") ? args.require("--request") : args.require("--title");
            String requirementId = args.optional("--requirement-id", "");
            if (requirementId.isEmpty()) {
                requirementId = taskId;  // 上游同形：缺省回退任务编号
            }
            String requirementSnapshot = "";
            String requirementSummary = "";
            String specFile = args.optional("--spec-file", "");
            if (!specFile.isEmpty()) {
                requirementSnapshot = readSnapshot(specFile);
                if (requirementSnapshot == null) {
                    return JsonOut.fail("需求文件不可读取：%s，任务未创建".formatted(specFile));
                }
                requirementSummary = Ledger.sha256(requirementSnapshot);
            }
            String problemSnapshot = null;
            String problemSummary = null;
            String problemFile = args.optional("--problem-file", "");
            if (!problemFile.isEmpty()) {
                problemSnapshot = readSnapshot(problemFile);
                if (problemSnapshot == null) {
                    return JsonOut.fail("问题文件不可读取：%s，任务未创建".formatted(problemFile));
                }
                problemSummary = Ledger.sha256(problemSnapshot);
            }
            ledger.insertTask(taskId, projectId, request, requirementId,
                    args.optional("--actor", ""), requirementSnapshot, requirementSummary,
                    problemSnapshot, problemSummary, prerequisite);
            JsonOut.emit(JsonOut.ordered("ok", true, "flowerp_connected", Ledger.FLOWERP_CONNECTED,
                    "task", JsonOut.ordered("task_id", taskId, "project_id", projectId,
                            "request", request, "requirement_id", requirementId,
                            "requirement_summary", requirementSummary,
                            "prerequisite_task_id", prerequisite)));
            return 0;
        }
    }

    /** 镜像 _read_snapshot：不可读取（缺失/非 UTF-8）返回 null，不抛裸异常。 */
    private static String readSnapshot(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException | UncheckedIOException error) {
            return null;
        }
    }

    // ---- workbench-evidence-add（镜像 _cmd_evidence_add）----

    private static int evidenceAdd(String[] argv) throws SQLException {
        Args args = new Args(argv, Set.of("--runtime-dir", "--task-id", "--phase", "--command-text",
                "--output-file", "--returncode", "--observed-at"), Set.of());
        // 解析期校验与上游 argparse 同时机（choices / type=int）
        String phase = args.require("--phase");
        if (!Ledger.PHASES.contains(phase)) {
            throw new Args.UsageException(
                    "argument --phase: invalid choice: '%s' (choose from %s)".formatted(phase, Ledger.PHASES));
        }
        int returncode = args.requireInt("--returncode");
        String outputFile = args.require("--output-file");
        try (Ledger ledger = Ledger.open(Path.of(args.require("--runtime-dir")), false)) {
            if (ledger == null || ledger.workbenchRow() == null) {
                return JsonOut.fail(Ledger.UNINITIALIZED);
            }
            String taskId = args.require("--task-id");
            if (!ledger.taskExists(taskId)) {
                return JsonOut.fail("%s: 任务不存在：%s，记录未追加".formatted(Ledger.MISSING_TASK, taskId));
            }
            String observedAt = args.require("--observed-at");
            if (Ledger.parseAware(observedAt) == null) {
                return JsonOut.fail("观察时间缺少时区或无法解析：%s，记录未追加".formatted(observedAt));
            }
            byte[] rawOutput;
            try {
                rawOutput = Files.readAllBytes(Path.of(outputFile));
            } catch (IOException error) {
                return JsonOut.fail("输出不可读取：%s，记录未追加".formatted(outputFile));
            }
            if (rawOutput.length == 0) {
                return JsonOut.fail("输出为空：%s，记录未追加".formatted(outputFile));
            }
            String outputText = new String(rawOutput, StandardCharsets.UTF_8);
            String outputSha256 = Ledger.sha256(outputText);  // 与 status 复核同一口径（对存储文本哈希）
            long recordId = ledger.insertEvidence(taskId, phase, args.require("--command-text"),
                    outputText, outputSha256, returncode, observedAt);
            JsonOut.emit(JsonOut.ordered("ok", true, "flowerp_connected", Ledger.FLOWERP_CONNECTED,
                    "record", JsonOut.ordered("record_id", (int) recordId, "task_id", taskId,
                            "phase", phase, "output_sha256", outputSha256,
                            "returncode", returncode, "observed_at", observedAt)));
            return 0;
        }
    }

    // ---- workbench-status（镜像 _cmd_status）----

    @SuppressWarnings("unchecked")
    private static int status(String[] argv) throws SQLException {
        Args args = new Args(argv, Set.of("--runtime-dir", "--require-project", "--require-task"),
                Set.of("--require-red-green-evidence"));
        try (Ledger ledger = Ledger.open(Path.of(args.require("--runtime-dir")), false)) {
            if (ledger == null || ledger.workbenchRow() == null) {
                return JsonOut.fail(Ledger.UNINITIALIZED);
            }
            String requireProject = args.optional("--require-project", "");
            String requireTask = args.optional("--require-task", "");
            boolean requireRedGreen = args.flag("--require-red-green-evidence");
            Map<String, Object> workbench = ledger.workbenchRow();
            List<Map<String, Object>> projects = ledger.loadProjects();
            List<String> errors = Ledger.digestProblems(projects);

            if (!requireProject.isEmpty() && projects.stream()
                    .noneMatch(project -> requireProject.equals(project.get("project_id")))) {
                return JsonOut.fail("required_project_missing: 项目不存在：" + requireProject,
                        JsonOut.ordered("evidence_complete", false, "errors", errors));
            }
            List<Map<String, Object>> scope = requireProject.isEmpty() ? projects
                    : projects.stream()
                            .filter(project -> requireProject.equals(project.get("project_id")))
                            .toList();
            List<Map<String, Object>> selected = scope.stream()
                    .flatMap(project -> ((List<Map<String, Object>>) project.get("tasks")).stream())
                    .filter(task -> requireTask.isEmpty() || requireTask.equals(task.get("task_id")))
                    .toList();
            if (!requireTask.isEmpty() && selected.isEmpty()) {
                return JsonOut.fail("%s: 任务不存在：%s".formatted(Ledger.MISSING_TASK, requireTask),
                        JsonOut.ordered("evidence_complete", false, "errors", errors));
            }

            boolean complete = !selected.isEmpty() && selected.stream().allMatch(
                    task -> Ledger.chainError((List<Map<String, Object>>) task.get("evidence")) == null);
            if (requireRedGreen) {
                if (requireTask.isEmpty()) {
                    return JsonOut.fail("缺少 --require-task，无法对具体任务检查证据完整性",
                            JsonOut.ordered("evidence_complete", complete && errors.isEmpty(),
                                    "errors", errors));
                }
                if (!complete) {
                    errors.add(Ledger.MISSING_CHAIN + ": 缺少同命令的红—Diff—绿链");
                }
            }

            boolean evidenceComplete = complete && errors.isEmpty();
            JsonOut.emit(JsonOut.ordered(
                    "ok", errors.isEmpty(),
                    "flowerp_connected", Ledger.FLOWERP_CONNECTED,
                    "workbench", workbenchPayload(workbench.get("workbench_id"), workbench.get("name"),
                            workbench.get("owner"), workbench.get("version")),
                    "projects", projects,
                    "errors", errors,
                    "evidence_complete", evidenceComplete,
                    "acceptance", "pending_human_review",
                    "limitations", Ledger.LIMITATIONS));
            return errors.isEmpty() ? 0 : 1;
        }
    }
}
