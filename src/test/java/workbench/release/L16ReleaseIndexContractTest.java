package workbench.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import workbench.delivery.TaskStore;
import workbench.testsupport.Cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L16 合同测试（起始红面一）：发布证据索引——讲义 docs/lessons/L16-冷启动与发布证据索引.md
 * §2 C3（验收 3「发布索引能追溯需求、Diff、Eval、人审与剩余风险」直接承载）。
 * 对照源 = 上游 {@code vendors/CodexFDE/workbench/release_index.py}（102 行，只读）：
 * references 组装（spec/pre_eval/post_eval/pre_process/post_process/cold_start/product/
 * remaining_risks/diff_excerpt——每引用 sha256+bytes+运行目录内相对路径）、evidence_gaps
 * 全类目、后缀白名单 {@code {.json,.md,.txt,.log,.patch,.csv}}、{@code index_status} 恒
 * {@code requires_human_review}、limitations 三条词面、事件按 detail 词面取证。
 *
 * <p>接缝（讲义 D4/D5）：REGISTRY 注册缝——{@code release-index}（上游 CLI
 * {@code course-release-index} 紧凑化，argv 同形：task_id 位置参数 + --runtime-dir +
 * --cold-start-evidence/--product-evidence/--risks-file）；事件 detail 词面 = 上游四词面
 * 直承（'本次需求 Spec 已冻结'/'执行前课程 Eval 已完成'/'受控执行阶段完成'——L13 Workflow
 * 已在场同词面/'课程红绿差分判定已完成'——现场链路补齐对应事件，spec 阶段确认 D5）。
 * 所读任务库 = L13 delivery TaskStore（上游 TaskStore 对应物，字段面同形——D7 三层运行库）。
 *
 * <p>夹具：TaskStore 公开 API（create/appendEvent）+ SQL 直插终态（completed/approve——
 * L12 ApprovalProbe 夹具同形）+ 磁盘证据件（报告/receipt/spec/三可选证据）。
 * 红点形态（commit 1）：REGISTRY 未注册 → {@code invalid choice} rc 2 → exitCode 断言红。
 * 实现转绿后本类零改动（红基线纪律）。
 *
 * <pre>
 * 用例 → 合同映射：
 * releaseIndexAssemblesTraceableReferencesFace  C3：五要素引用组装 + sha256/bytes + diff-excerpt
 * releaseIndexStaysPendingHumanReviewFace      C3：requires_human_review 恒定 + limitations 三条 + schema/v1
 * releaseIndexHumanAcceptanceGapFaces          C3：reviewer 缺席/agent: 前缀/decision≠approve → gap
 * releaseIndexVerifiedAndPostEvalGapFaces      C3：差分未接受/后置 Eval 未通过 → gap
 * releaseIndexSpecChangedAfterFreezeGapFace    C3：冻结 sha ≠ 现算 sha → gap + expected_sha256
 * releaseIndexEvidenceBoundaryFaces            C3：运行目录外/后缀白名单外拒绝；空文件/缺席 → gap
 * releaseIndexOutOfScopeGapFace                C3：范围外改动 → gap
 * releaseIndexSummaryMismatchGapFace           C3：报告文件 summary 与账面不一致 → gap
 * </pre>
 */
class L16ReleaseIndexContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String REVIEWER = "XianReallyHot-ZZH";

    /** 夹具产物：各证据件绝对路径（均在 runtime 内——引用边界的前提）。 */
    private record Fixture(Path db, Path spec, Path coldStart, Path product, Path risks) {}

    @Test
    void releaseIndexAssemblesTraceableReferencesFace(@TempDir Path runtime) throws Exception {
        Fixture fixture = seed(runtime, "TASK-L16INDEX01");
        Cli.Result result = runReleaseIndex(fixture, "TASK-L16INDEX01");

        assertThat(result.exitCode()).as("release-index 应 rc 0：" + result.stdout() + result.stderr())
                .isZero();
        JsonNode stdout = Cli.json(result);
        assertThat(stdout.path("index_status").asText()).isEqualTo("requires_human_review");
        assertThat(stdout.path("task_id").asText()).isEqualTo("TASK-L16INDEX01");
        assertThat(stdout.path("evidence_gaps")).isEmpty();

        JsonNode index = MAPPER.readTree(Path.of(stdout.path("path").asText()).toFile());
        JsonNode references = index.path("references");
        // C3 五要素：需求（spec）/ Diff（diff_excerpt）/ Eval（pre+post）/ 人审（human_review）
        // / 剩余风险（remaining_risks）+ 可选 cold_start/product 与前后 receipt
        for (String label : List.of("spec", "pre_eval", "post_eval", "pre_process", "post_process",
                "cold_start", "product", "remaining_risks", "diff_excerpt")) {
            assertThat(references.path(label).path("sha256").asText())
                    .as("引用应含 " + label).isNotEmpty();
            assertThat(references.path(label).path("bytes").asInt(-1))
                    .as("引用 " + label + " 应含字节数").isPositive();
        }
        assertThat(references.path("spec").path("sha256").asText())
                .isEqualTo(sha256(Files.readAllBytes(fixture.spec())));
        assertThat(index.path("task_status").asText()).isEqualTo("completed");
        assertThat(index.path("human_review").path("reviewer").asText()).isEqualTo(REVIEWER);
        assertThat(index.path("human_review").path("decision").asText()).isEqualTo("approve");
        assertThat(index.path("changed_files").isArray()).isTrue();
        // diff-excerpt 写盘且内容 = 执行事件里的 diff（上游 :96-99）
        Path diffExcerpt = Path.of(stdout.path("path").asText()).getParent()
                .resolve("diff-excerpt.txt");
        assertThat(diffExcerpt).exists();
        assertThat(Files.readString(diffExcerpt, StandardCharsets.UTF_8)).contains("receiver.py");
    }

    @Test
    void releaseIndexStaysPendingHumanReviewFace(@TempDir Path runtime) throws Exception {
        Fixture fixture = seed(runtime, "TASK-L16INDEX02");
        Cli.Result result = runReleaseIndex(fixture, "TASK-L16INDEX02");

        assertThat(result.exitCode()).isZero();
        JsonNode index = MAPPER.readTree(Path.of(Cli.json(result).path("path").asText()).toFile());
        assertThat(index.path("schema").asText()).isEqualTo("workbench.release-index/v1");
        assertThat(index.path("index_status").asText())
                .as("发布索引不代行人审——状态恒 requires_human_review").isEqualTo("requires_human_review");
        JsonNode limitations = index.path("limitations");
        assertThat(limitations.isArray()).isTrue();
        assertThat(limitations).hasSize(3);
        assertThat(limitations.get(0).asText()).isEqualTo("文件存在及指纹不证明内容真实或足以验收。");
        assertThat(limitations.get(1).asText()).isEqualTo("改动摘要可能被执行器截短，不可作为可应用的完整 Patch。");
        assertThat(limitations.get(2).asText()).isEqualTo("本命令不批准任务、不发布版本，也不证明学生结业。");
        assertThat(index.path("request").asText()).isNotEmpty();
        assertThat(index.path("requirement_id").asText()).isEqualTo("REQUIREMENT:LIVE-DRAW");
    }

    @Test
    void releaseIndexHumanAcceptanceGapFaces(@TempDir Path runtime) throws Exception {
        Fixture fixture = seed(runtime, "TASK-L16GAP0001");
        // 三个红灯面共用一库三任务：reviewer 缺席 / agent: 前缀 / decision≠approve
        seedTask(fixture.db(), "TASK-L16GAP0002", runtime);
        seedTask(fixture.db(), "TASK-L16GAP0003", runtime);
        setReview(fixture.db(), "TASK-L16GAP0001", null, "approve");
        setReview(fixture.db(), "TASK-L16GAP0002", "agent:claude", "approve");
        setReview(fixture.db(), "TASK-L16GAP0003", REVIEWER, "reject");

        for (String taskId : List.of("TASK-L16GAP0001", "TASK-L16GAP0002", "TASK-L16GAP0003")) {
            Cli.Result result = runReleaseIndex(fixture, taskId);
            assertThat(result.exitCode()).as(taskId + " 应 rc 0").isZero();
            assertThat(Cli.json(result).path("evidence_gaps").toString())
                    .as(taskId + " 执行者不得自签通过").contains("human_acceptance");
        }
    }

    @Test
    void releaseIndexVerifiedAndPostEvalGapFaces(@TempDir Path runtime) throws Exception {
        Fixture fixture = seed(runtime, "TASK-L16EVGL001");
        seedTask(fixture.db(), "TASK-L16EVGL002", runtime);
        // 差分未接受 → verified_implementation gap（上游 :57-58）
        appendEvent(fixture.db(), "TASK-L16EVGL001", "课程红绿差分判定已完成",
                Map.of("accepted", false));
        // 后置 Eval 未通过（decision block + blocking_failed>0）→ post_eval_not_passing gap（上游 :61-63）
        Map<String, Object> blocking = Map.of("decision", "block", "blocking_failed", 1);
        setResult(fixture.db(), "TASK-L16EVGL002", Map.of("summary", blocking));

        Cli.Result first = runReleaseIndex(fixture, "TASK-L16EVGL001");
        assertThat(first.exitCode()).isZero();
        assertThat(Cli.json(first).path("evidence_gaps").toString()).contains("verified_implementation");

        Cli.Result second = runReleaseIndex(fixture, "TASK-L16EVGL002");
        assertThat(second.exitCode()).isZero();
        assertThat(Cli.json(second).path("evidence_gaps").toString()).contains("post_eval_not_passing");
    }

    @Test
    void releaseIndexSpecChangedAfterFreezeGapFace(@TempDir Path runtime) throws Exception {
        Fixture fixture = seed(runtime, "TASK-L16FROZ001");
        // 冻结事件记录的 sha ≠ 当前文件现算 sha → spec_changed_after_freeze + expected_sha256 留痕
        appendEvent(fixture.db(), "TASK-L16FROZ001", "本次需求 Spec 已冻结",
                Map.of("sha256", "f".repeat(64)));

        Cli.Result result = runReleaseIndex(fixture, "TASK-L16FROZ001");
        assertThat(result.exitCode()).isZero();
        assertThat(Cli.json(result).path("evidence_gaps").toString())
                .contains("spec_changed_after_freeze");
        JsonNode index = MAPPER.readTree(Path.of(Cli.json(result).path("path").asText()).toFile());
        assertThat(index.path("references").path("spec").path("expected_sha256").asText())
                .isEqualTo("f".repeat(64));
    }

    @Test
    void releaseIndexEvidenceBoundaryFaces(@TempDir Path runtime) throws Exception {
        Fixture fixture = seed(runtime, "TASK-L16BNDY001");
        // 运行目录外的证据文件 → 拒绝（上游 :24-25 词面直承）
        Path outside = Files.createTempFile("l16-outside", ".md");
        Files.writeString(outside, "运行目录外", StandardCharsets.UTF_8);
        Cli.Result outsideResult = Cli.run("release-index", "TASK-L16BNDY001",
                "--runtime-dir", runtime.toString(), "--cold-start-evidence", outside.toString());
        assertThat(outsideResult.exitCode()).as("目录外证据应拒绝").isNotZero();
        assertThat(outsideResult.stdout() + outsideResult.stderr())
                .contains("必须是本运行目录内的证据文件");

        // 白名单外后缀（运行目录内）→ 同词面拒绝
        Path badSuffix = runtime.resolve("cold-start.exe");
        Files.writeString(badSuffix, "后缀不在白名单", StandardCharsets.UTF_8);
        Cli.Result suffixResult = Cli.run("release-index", "TASK-L16BNDY001",
                "--runtime-dir", runtime.toString(), "--cold-start-evidence", badSuffix.toString());
        assertThat(suffixResult.exitCode()).as("白名单外后缀应拒绝").isNotZero();
        assertThat(suffixResult.stdout() + suffixResult.stderr())
                .contains("必须是本运行目录内的证据文件");

        // 空文件 → gap（不报错）；缺席文件 → gap
        Path empty = runtime.resolve("cold-start-empty.md");
        Files.writeString(empty, "", StandardCharsets.UTF_8);
        Cli.Result emptyResult = Cli.run("release-index", "TASK-L16BNDY001",
                "--runtime-dir", runtime.toString(), "--cold-start-evidence", empty.toString());
        assertThat(emptyResult.exitCode()).isZero();
        assertThat(Cli.json(emptyResult).path("evidence_gaps").toString()).contains("cold_start");

        Cli.Result missingResult = Cli.run("release-index", "TASK-L16BNDY001",
                "--runtime-dir", runtime.toString(),
                "--cold-start-evidence", runtime.resolve("not-there.md").toString());
        assertThat(missingResult.exitCode()).isZero();
        assertThat(Cli.json(missingResult).path("evidence_gaps").toString()).contains("cold_start");
    }

    @Test
    void releaseIndexOutOfScopeGapFace(@TempDir Path runtime) throws Exception {
        Fixture fixture = seed(runtime, "TASK-L16OUTS001");
        // 范围外改动 → out_of_scope_changes gap + payload 如实列出（上游 :59-60,87）
        appendEvent(fixture.db(), "TASK-L16OUTS001", "受控执行阶段完成",
                Map.of("changed_files", List.of("receiver.py"), "diff", "--- a/receiver.py",
                        "out_of_scope_files", List.of("secrets.env")));

        Cli.Result result = runReleaseIndex(fixture, "TASK-L16OUTS001");
        assertThat(result.exitCode()).isZero();
        assertThat(Cli.json(result).path("evidence_gaps").toString()).contains("out_of_scope_changes");
        JsonNode index = MAPPER.readTree(Path.of(Cli.json(result).path("path").asText()).toFile());
        assertThat(index.path("out_of_scope_files").get(0).asText()).isEqualTo("secrets.env");
    }

    @Test
    void releaseIndexSummaryMismatchGapFace(@TempDir Path runtime) throws Exception {
        Fixture fixture = seed(runtime, "TASK-L16MISM001");
        // 账面 result.summary 与 post_eval 报告文件实际 summary 不一致 → post_eval_summary_mismatch（上游 :69-78）
        Map<String, Object> differing = new LinkedHashMap<>();
        differing.put("decision", "pass");
        differing.put("blocking_failed", 9);
        setResult(fixture.db(), "TASK-L16MISM001", Map.of("summary", differing));

        Cli.Result result = runReleaseIndex(fixture, "TASK-L16MISM001");
        assertThat(result.exitCode()).isZero();
        assertThat(Cli.json(result).path("evidence_gaps").toString())
                .contains("post_eval_summary_mismatch");
    }

    @Test
    void releaseIndexTaskNotFoundFace(@TempDir Path runtime) throws Exception {
        seed(runtime, "TASK-L16INDEX01");
        // 复查轮 S-3：任务缺席失败路径（DoD 每规则一失败用例）。词面/rc 形态适配落账：
        // 上游 TaskStore.get KeyError → CLI 层 rc 2；本仓走顶层 JSON 错误契约 rc 1
        //（CLAUDE.md 结构约定「基础设施失败保持 JSON 错误契约」优先——处置表 S-3）
        Cli.Result result = Cli.run("release-index", "TASK-MISSING-01",
                "--runtime-dir", runtime.toString());
        assertThat(result.exitCode()).as("任务缺席应 rc 1（JSON 错误契约）").isEqualTo(1);
        assertThat(result.stdout() + result.stderr()).contains("任务不存在");
    }

    // ---- 夹具（TaskStore 公开 API + SQL 直插终态，L12 ApprovalProbe 同形）--------------------------------------

    /** 建库 + 完整绿灯夹具（completed + 具名 approve + 差分通过 + 冻结 sha 吻合 + 报告与账面一致）。 */
    private static Fixture seed(Path runtime, String taskId) throws Exception {
        Path db = runtime.resolve("workbench.db");
        seedTask(db, taskId, runtime);
        return new Fixture(db, runtime.resolve("spec-" + taskId + ".md"),
                runtime.resolve("cold-start-" + taskId + ".md"), runtime.resolve("product-" + taskId + ".md"),
                runtime.resolve("risks-" + taskId + ".md"));
    }

    private static void seedTask(Path db, String taskId, Path runtime) throws Exception {
        Path spec = runtime.resolve("spec-" + taskId + ".md");
        Files.writeString(spec, "# 现场抽取需求 Spec 夹具\n\n六段式占位。\n", StandardCharsets.UTF_8);
        // 前后置 Eval 报告（.json 白名单）与 receipt（.txt 白名单）——summary/results 与账面一致
        Map<String, Object> summary = Map.of("decision", "pass", "blocking_failed", 0,
                "blocking_passed", 1);
        Map<String, Object> row = Map.of("name", "delivery_evidence_and_review_controls",
                "level", "blocking", "passed", true);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", "1.0");
        report.put("summary", summary);
        report.put("results", List.of(row));
        Path preReport = runtime.resolve("pre-report-" + taskId + ".json");
        Path postReport = runtime.resolve("post-report-" + taskId + ".json");
        Files.writeString(preReport, MAPPER.writeValueAsString(report), StandardCharsets.UTF_8);
        Files.writeString(postReport, MAPPER.writeValueAsString(report), StandardCharsets.UTF_8);
        Path preReceipt = runtime.resolve("pre-receipt-" + taskId + ".txt");
        Path postReceipt = runtime.resolve("post-receipt-" + taskId + ".txt");
        Files.writeString(preReceipt, "pre receipt", StandardCharsets.UTF_8);
        Files.writeString(postReceipt, "post receipt", StandardCharsets.UTF_8);

        TaskStore store = new TaskStore(db);
        store.create("L16 发布索引合同夹具：" + taskId, "REQUIREMENT:LIVE-DRAW",
                List.of("REQUIREMENT:LIVE-DRAW"), spec.toString(), "l16-fixture", "manual",
                taskId, "verify", List.of(), 300, "sub-" + taskId);

        appendEvent(db, taskId, "本次需求 Spec 已冻结", Map.of("sha256",
                sha256(Files.readAllBytes(spec))));
        appendEvent(db, taskId, "执行前课程 Eval 已完成", Map.of(
                "runner", Map.of("report_path", preReport.toString(), "receipt_path", preReceipt.toString()),
                "summary", summary));
        appendEvent(db, taskId, "受控执行阶段完成", Map.of(
                "changed_files", List.of("receiver.py"),
                "diff", "--- a/receiver.py\n+++ b/receiver.py\n@@\n+锚点三写合单事务",
                "out_of_scope_files", List.of()));
        appendEvent(db, taskId, "课程红绿差分判定已完成",
                Map.of("accepted", true, "changed_files", List.of("receiver.py")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runner", Map.of("report_path", postReport.toString(),
                "receipt_path", postReceipt.toString()));
        result.put("summary", summary);
        result.put("results", List.of(row));
        setResult(db, taskId, result);
        setReview(db, taskId, REVIEWER, "approve");

        // 可选三证据件（cold_start/product/remaining_risks——由 CLI 旗标引用）
        Files.writeString(runtime.resolve("cold-start-" + taskId + ".md"),
                "冷启动证据：干净克隆全量门绿", StandardCharsets.UTF_8);
        Files.writeString(runtime.resolve("product-" + taskId + ".md"),
                "产品证据：面板可见", StandardCharsets.UTF_8);
        Files.writeString(runtime.resolve("risks-" + taskId + ".md"),
                "剩余风险：账本历史单调增长待瘦身", StandardCharsets.UTF_8);
    }

    /** 事件追加走 TaskStore 公开 API（不绕账面——append-only 语义由实现承载）。 */
    private static void appendEvent(Path db, String taskId, String detail,
            Map<String, Object> evidence) throws Exception {
        new TaskStore(db).appendEvent(taskId, detail, "Claude", evidence);
    }

    /**
     * SQL 直插终态字段：result_json（L12 夹具同形——状态机不走链路，合同面只读这些列）。
     * 合并语义：gap 面只换 {@code summary}，runner 路径等既有键保持（避免连带 gap 掩盖断言面）。
     */
    private static void setResult(Path db, String taskId, Map<String, Object> patch) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            String existing;
            try (PreparedStatement query = conn.prepareStatement(
                    "SELECT result_json FROM tasks WHERE id=?")) {
                query.setString(1, taskId);
                try (java.sql.ResultSet row = query.executeQuery()) {
                    existing = row.next() ? row.getString(1) : null;
                }
            }
            Map<String, Object> merged = existing == null ? new LinkedHashMap<>()
                    : MAPPER.readValue(existing,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            merged.putAll(patch);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tasks SET result_json=? WHERE id=?")) {
                ps.setString(1, MAPPER.writeValueAsString(merged));
                ps.setString(2, taskId);
                ps.executeUpdate();
            }
        }
    }

    private static void setReview(Path db, String taskId, String reviewer, String decision)
            throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tasks SET status='completed', reviewed_by=?, review_decision=? WHERE id=?")) {
                ps.setString(1, reviewer);
                ps.setString(2, decision);
                ps.setString(3, taskId);
                ps.executeUpdate();
            }
        }
    }

    private static Cli.Result runReleaseIndex(Fixture fixture, String taskId) {
        return Cli.run("release-index", taskId, "--runtime-dir",
                fixture.db().getParent().toString(),
                "--cold-start-evidence", fixture.coldStart().toString(),
                "--product-evidence", fixture.product().toString(),
                "--risks-file", fixture.risks().toString());
    }

    private static String sha256(byte[] content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
