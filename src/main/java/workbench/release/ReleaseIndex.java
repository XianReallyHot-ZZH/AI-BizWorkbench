package workbench.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import workbench.bootstrap.Args;
import workbench.bootstrap.JsonOut;
import workbench.delivery.TaskStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * L16 发布证据索引（讲义 C3，验收 3 直接承载）：上游
 * {@code vendors/CodexFDE/workbench/release_index.py}（102 行，只读）的逐段对应物——
 * 组装可追溯发布引用，**不认证不接受交付**（schema 词面：Assemble traceable release
 * references without certifying or accepting a delivery）。
 *
 * <p>语义逐段直承：①引用门 = 运行目录内 + 后缀白名单
 * {@code {.json,.md,.txt,.log,.patch,.csv}} + 非空，越界即拒（词面「必须是本运行目录内的
 * 证据文件」）；②事件按 detail 词面取证（四词面——{@code 受控执行阶段完成} L13 Workflow
 * 已在场，其余三面由现场交付链在对应相位补齐，spec 具名裁决非映射表面）；③缺项全类目
 * 如实列出（human_acceptance〔reviewer 缺席/agent: 前缀/decision≠approve〕/
 * verified_implementation/post_eval_not_passing/spec_changed_after_freeze/summary_mismatch/
 * out_of_scope_changes/diff/空文件/缺席文件）；④{@code index_status} 恒
 * {@code requires_human_review} + limitations 三条词面（「本命令不批准任务、不发布版本，
 * 也不证明学生结业」——铁律 2 的发布面：待审核 ≠ 已接受）；⑤Spec 冻结 sha 对照留
 * expected_sha256；⑥diff 摘录随索引写盘（截短局限在 limitations 声明，不冒充完整 Patch）。
 *
 * <p>接缝（spec 具名，零新缝）：REGISTRY 注册缝（{@code release-index}，上游 CLI
 * {@code course-release-index} 紧凑化，argv 同形——task_id 位置参数 + --runtime-dir +
 * 三可选证据旗标）+ delivery TaskStore 公开面（上游 release_index 所读 TaskStore 的
 * 本仓对应物，L13 建成）。
 */
public final class ReleaseIndex {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final Set<String> SUFFIX_WHITELIST = Set.of(
            ".json", ".md", ".txt", ".log", ".patch", ".csv");

    /** 上游事件 detail 词面四件（取证键，非映射表面——现场链路按同词面发事件）。 */
    private static final String DETAIL_PRE_EVAL = "执行前课程 Eval 已完成";
    private static final String DETAIL_EXECUTION = "受控执行阶段完成";
    private static final String DETAIL_DIFFERENTIAL = "课程红绿差分判定已完成";
    private static final String DETAIL_SPEC_FROZEN = "本次需求 Spec 已冻结";

    private ReleaseIndex() {}

    public static int execute(String[] argv) {
        Args args;
        try {
            args = new Args(argv,
                    Set.of("--runtime-dir", "--cold-start-evidence", "--product-evidence", "--risks-file"),
                    Set.of(), List.of("task_id"));
        } catch (Args.UsageException error) {
            System.err.println(error.getMessage());
            return 2;
        }
        try {
            Map<String, Object> summary = create(
                    Path.of(args.optional("--runtime-dir", ".runtime")).toAbsolutePath().normalize(),
                    args.positional(0),
                    args.optional("--cold-start-evidence", null),
                    args.optional("--product-evidence", null),
                    args.optional("--risks-file", null));
            // 上游 CLI 返回体同形：path/task_id/index_status/evidence_gaps
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", summary.get("path"));
            out.put("task_id", summary.get("task_id"));
            out.put("index_status", summary.get("index_status"));
            out.put("evidence_gaps", summary.get("evidence_gaps"));
            System.out.println(MAPPER.writeValueAsString(out));
            return 0;
        } catch (IllegalArgumentException error) {
            // 引用边界拒绝（词面直承）走 JSON 错误契约（rc 1）
            return JsonOut.fail(error.getMessage());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** 组装发布索引（上游 create_release_index 逐段对应）。返回 stdout 摘要与 index 路径。 */
    public static Map<String, Object> create(Path runtimeDir, String taskId,
            String coldStart, String product, String risks) throws IOException {
        TaskStore store = new TaskStore(runtimeDir.resolve("workbench.db"));
        Map<String, Object> task;
        try {
            task = store.get(taskId);
        } catch (TaskStore.TaskNotFound error) {
            throw new IllegalArgumentException("任务不存在：" + taskId);
        }
        Path output = runtimeDir.resolve("release-index").resolve(taskId)
                .resolve(UUID.randomUUID().toString().replace("-", ""));
        Map<String, Object> references = new LinkedHashMap<>();
        // 引用原绝对路径（复核面用——形态适配：上游经 output 相对路径读回，其 output 目录
        // 在首跑复核时点尚未创建，同形语义 = 按引用原路径复核，词面与判定不变）
        Map<String, Path> resolved = new LinkedHashMap<>();
        List<String> gaps = new ArrayList<>();

        Map<String, Object> pre = evidence(task, DETAIL_PRE_EVAL);
        Map<String, Object> execution = evidence(task, DETAIL_EXECUTION);
        Map<String, Object> differential = evidence(task, DETAIL_DIFFERENTIAL);
        Map<String, Object> result = mapOrEmpty(task.get("result"));

        reference(references, resolved, gaps, output, runtimeDir, "spec", text(task.get("spec_path")));
        reference(references, resolved, gaps, output, runtimeDir, "pre_eval",
                nestedRunnerPath(pre, "report_path"));
        reference(references, resolved, gaps, output, runtimeDir, "post_eval",
                nestedRunnerPath(result, "report_path"));
        reference(references, resolved, gaps, output, runtimeDir, "pre_process",
                nestedRunnerPath(pre, "receipt_path"));
        reference(references, resolved, gaps, output, runtimeDir, "post_process",
                nestedRunnerPath(result, "receipt_path"));
        reference(references, resolved, gaps, output, runtimeDir, "cold_start", coldStart);
        reference(references, resolved, gaps, output, runtimeDir, "product", product);
        reference(references, resolved, gaps, output, runtimeDir, "remaining_risks", risks);
        List<String> changedFiles = stringList(execution.get("changed_files"));
        if (changedFiles.isEmpty() || text(execution.get("diff")).isEmpty()) {
            gaps.add("diff");
        }
        String reviewer = text(task.get("reviewed_by"));
        if (!"completed".equals(text(task.get("status"))) || reviewer.isEmpty()
                || reviewer.startsWith("agent:") || !"approve".equals(text(task.get("review_decision")))) {
            gaps.add("human_acceptance");
        }
        if (!Boolean.TRUE.equals(differential.get("accepted"))) {
            gaps.add("verified_implementation");
        }
        List<String> outOfScope = stringList(execution.get("out_of_scope_files"));
        if (!outOfScope.isEmpty()) {
            gaps.add("out_of_scope_changes");
        }
        Map<String, Object> summaryJson = mapOrEmpty(result.get("summary"));
        if (!"pass".equals(text(summaryJson.get("decision")))
                || intValue(summaryJson.get("blocking_failed")) != 0) {
            gaps.add("post_eval_not_passing");
        }
        Map<String, Object> frozen = evidence(task, DETAIL_SPEC_FROZEN);
        String frozenSha = text(frozen.get("sha256"));
        if (!frozenSha.isEmpty() && references.containsKey("spec")) {
            Map<String, Object> specEntry = referenceEntry(references, "spec");
            specEntry.put("expected_sha256", frozenSha);
            if (!frozenSha.equals(specEntry.get("sha256"))) {
                gaps.add("spec_changed_after_freeze");
            }
        }
        // 报告文件与账面复核（上游 :69-78 意图直承）：summary/results 与账面不一致即缺项
        for (String label : List.of("pre_eval", "post_eval")) {
            if (!references.containsKey(label)) {
                continue;
            }
            Map<String, Object> recorded = "pre_eval".equals(label) ? pre : result;
            try {
                JsonNode actual = MAPPER.readTree(Files.readAllBytes(resolved.get(label)));
                if (!actual.path("summary").equals(MAPPER.valueToTree(recorded.get("summary")))) {
                    gaps.add(label + "_summary_mismatch");
                }
                if ("post_eval".equals(label) && !actual.path("results")
                        .equals(MAPPER.valueToTree(result.get("results")))) {
                    gaps.add("post_eval_results_mismatch");
                }
            } catch (IOException | IllegalArgumentException error) {
                gaps.add(label + "_unreadable");
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "workbench.release-index/v1");
        payload.put("task_id", taskId);
        payload.put("created_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        payload.put("index_status", "requires_human_review");
        payload.put("request", task.get("request"));
        payload.put("requirement_id", task.get("requirement_id"));
        payload.put("task_status", task.get("status"));
        payload.put("execution_mode", task.get("execution_mode"));
        payload.put("references", references);
        payload.put("evidence_gaps", gaps);
        payload.put("pre_eval_summary", pre.get("summary"));
        payload.put("post_eval_summary", result.get("summary"));
        payload.put("changed_files", changedFiles);
        payload.put("out_of_scope_files", outOfScope);
        payload.put("differential", differential.isEmpty() ? null : differential);
        payload.put("isolation", evidence(task, "已创建课程隔离 Worktree").isEmpty() ? null
                : evidence(task, "已创建课程隔离 Worktree"));
        Map<String, Object> humanReview = new LinkedHashMap<>();
        humanReview.put("reviewer", reviewer.isEmpty() ? null : reviewer);
        humanReview.put("decision", task.get("review_decision"));
        humanReview.put("note", task.get("review_note"));
        payload.put("human_review", humanReview);
        payload.put("limitations", List.of(
                "文件存在及指纹不证明内容真实或足以验收。",
                "改动摘要可能被执行器截短，不可作为可应用的完整 Patch。",
                "本命令不批准任务、不发布版本，也不证明学生结业。"));

        Files.createDirectories(output.getParent());
        Files.createDirectory(output);
        String diff = text(execution.get("diff"));
        if (!diff.isEmpty()) {
            Path diffPath = output.resolve("diff-excerpt.txt");
            Files.writeString(diffPath, diff, StandardCharsets.UTF_8);
            reference(references, resolved, gaps, output, runtimeDir, "diff_excerpt",
                    diffPath.toString());
        }
        Path indexPath = output.resolve("index.json");
        Files.writeString(indexPath, MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("path", indexPath.toString());
        summary.put("task_id", taskId);
        summary.put("index_status", "requires_human_review");
        summary.put("evidence_gaps", gaps);
        return summary;
    }

    // ---- 引用门（上游 reference() 逐段：缺席/空 → gap；越界/后缀外 → 拒绝）----------------------

    private static void reference(Map<String, Object> references, Map<String, Path> resolved,
            List<String> gaps, Path output, Path runtime, String label, String candidate)
            throws IOException {
        if (candidate == null || candidate.strip().isEmpty()) {
            gaps.add(label);
            return;
        }
        Path path = Path.of(candidate).toAbsolutePath().normalize();
        if (!inside(path, runtime) || !SUFFIX_WHITELIST.contains(suffix(path))) {
            throw new IllegalArgumentException(label + " 必须是本运行目录内的证据文件：" + candidate);
        }
        if (!Files.isRegularFile(path)) {
            gaps.add(label);
            return;
        }
        byte[] content = Files.readAllBytes(path);
        if (content.length == 0 || allWhitespace(content)) {
            gaps.add(label);
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("path", output.relativize(path).toString().replace('\\', '/'));
        entry.put("sha256", sha256(content));
        entry.put("bytes", content.length);
        references.put(label, entry);
        resolved.put(label, path);
    }

    /** 事件取证（上游 evidence()：倒序取最后一个匹配 detail 的事件证据）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> evidence(Map<String, Object> task, String detail) {
        List<Map<String, Object>> events = (List<Map<String, Object>>) task.getOrDefault("events", List.of());
        for (int i = events.size() - 1; i >= 0; i--) {
            if (detail.equals(events.get(i).get("detail"))) {
                Object evidence = events.get(i).get("evidence");
                return evidence instanceof Map ? (Map<String, Object>) evidence : new LinkedHashMap<>();
            }
        }
        return new LinkedHashMap<>();
    }

    private static String nestedRunnerPath(Map<String, Object> evidence, String key) {
        Object runner = evidence.get("runner");
        if (runner instanceof Map<?, ?> map) {
            Object value = map.get(key);
            return value == null ? "" : String.valueOf(value);
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> referenceEntry(Map<String, Object> references, String label) {
        return (Map<String, Object>) references.get(label);
    }

    private static boolean inside(Path path, Path root) {
        return path.startsWith(root);
    }

    private static String suffix(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    /** Python {@code content.strip()} 口径：纯空白字节视同空。 */
    private static boolean allWhitespace(byte[] content) {
        for (byte b : content) {
            if (!Character.isWhitespace(b)) {
                return false;
            }
        }
        return true;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrEmpty(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                out.add(String.valueOf(item));
            }
            return out;
        }
        return new ArrayList<>();
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }
}
