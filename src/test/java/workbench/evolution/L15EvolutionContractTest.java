package workbench.evolution;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import workbench.testsupport.Cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L15 演进记录合同测试：讲义 docs/lessons/L15-反馈治理与记忆演进.md §2 C1/C5 →
 * 用例映射（operate 第三讲 + 检查点自建讲义第三例；对照源 = 上游
 * {@code workbench/evolution.py} 490 行断言面逐句 + 绑定 eval
 * {@code eval/cases.py:175-201} raw_feedback_cannot_become_blocking 四拍，只读对照）。
 *
 * <p>接缝（spec docs/specs/L15-反馈治理与记忆演进.md 已具名）：CLI 公开接口缝——
 * {@code evolution} 一件五子命令（create/review/assets/verify/summary，上游 argparse
 * 单入口同形；--db → --runtime-dir 本仓形态适配）+ {@code feedback} 一件三子命令
 * （add/review/summary，上游 feedback.py main 同形——L15 补建 CLI 面，spec 偏差
 * +1 件落证据账）经 {@code workbench.cli.Main} 真实子进程驱动；造数 = SQL 直插
 * tasks 行（L11 夹具先例）+ feedback 命令真链。
 *
 * <p>红点组（commit 1）：evolution / feedback 命令缺席 → REGISTRY miss rc≠0、
 * 库未建 → 造数断言 {@code workbench.db 应在场} 即红——本类 7 用例全数起始红；
 * 实现转绿后本类零改动（红基线纪律）。
 *
 * <pre>
 * 用例 → 合同映射：
 * evolutionRejectsUnreviewedFeedbackFace   C1/eval 2 拍 3：未审核反馈提升被拒（词面逐字）
 * evolutionPromotesAcceptedFeedbackFace    C1/eval 2 拍 4-5：具名接受后 proposed + 事件留痕
 * evolutionCreateValidationFace            C5 create 门：分类/签名/同反馈唯一/源任务/业务对象
 * evolutionReviewGateFace                  C5 review 门：具名+理由、只 proposed、三决定、终态
 * evolutionAssetsGateFace                  C5 assets 门：七类型、去重、状态前置
 * evolutionVerifyGateFace                  C5 verify 重门：候选≠源/共享对象/具名验收/报告
 *                                          sha256 重算/governed learning/ 资产分支（反拍）
 * evolutionSummaryFace                     C5 summary 计数与列表
 * </pre>
 */
class L15EvolutionContractTest {

    private static final String OPS_FEEDBACK = "把渠道幂等降为观察项";
    private static final String ERP_REF = "SKU:L15-ERP-01";

    // ---- eval 2 断言面四拍（cases.py:175-201 逐字对照） --------------------------------

    @Test
    void evolutionRejectsUnreviewedFeedbackFace(@TempDir Path runtime) {
        String task = seedTask(runtime, "TASK-L15-SRC-01", "[]", "review", null, null);
        String pending = addFeedback(runtime, task, OPS_FEEDBACK);

        // 拍 3：未审核反馈直接提升为进化记录 → 拒绝（词面上游逐字）
        Cli.Result rejected = Cli.run("evolution", "create", "--runtime-dir", runtime.toString(),
                "--feedback", pending, "--signature", "want-weaker-eval",
                "--classification", "workbench_control");
        assertThat(rejected.exitCode()).as("未审核反馈提升应 rc 1：" + rejected.stdout()).isOne();
        assertThat(rejected.stdout() + rejected.stderr())
                .contains("只有具名接受的反馈才能提升为进化记录");
    }

    @Test
    void evolutionPromotesAcceptedFeedbackFace(@TempDir Path runtime) {
        String task = seedTask(runtime, "TASK-L15-SRC-02",
                "[\"" + ERP_REF + "\"]", "completed", "delivery-reviewer", "approve");
        String feedback = addFeedback(runtime, task, "渠道幂等缺少工作台控制");
        Cli.Result reviewed = Cli.run("feedback", "review", "--runtime-dir", runtime.toString(),
                "--id", feedback, "--reviewer", "teacher", "--decision", "accept",
                "--note", "可以立项，但不能改当前 blocking");
        assertThat(reviewed.exitCode()).as(reviewed.stdout()).isZero();
        assertThat(Cli.json(reviewed).path("feedback").path("status").asText())
                .isEqualTo("accepted");

        // 拍 5：接受后可立项，status=proposed + 首事件留痕
        Cli.Result created = Cli.run("evolution", "create", "--runtime-dir", runtime.toString(),
                "--feedback", feedback, "--signature", "channel-idempotency-missing-workbench",
                "--classification", "erp_rule");
        assertThat(created.exitCode()).as(created.stdout()).isZero();
        JsonNode evolution = Cli.json(created).path("evolution");
        assertThat(evolution.path("status").asText()).isEqualTo("proposed");
        assertThat(evolution.path("feedback_id").asText()).isEqualTo(feedback);
        assertThat(evolution.path("source_task_id").asText()).isEqualTo(task);
        assertThat(evolution.path("business_refs").toString()).contains(ERP_REF);
        assertThat(evolution.path("events").isArray()).isTrue();
        assertThat(evolution.path("events").path(0).path("to_status").asText())
                .isEqualTo("proposed");
        assertThat(evolution.path("events").path(0).path("detail").asText())
                .contains("已从具名接受的反馈建立进化候选");
    }

    // ---- create 门 ---------------------------------------------------------------------

    @Test
    void evolutionCreateValidationFace(@TempDir Path runtime) {
        String task = seedTask(runtime, "TASK-L15-SRC-03",
                "[\"" + ERP_REF + "\"]", "completed", "delivery-reviewer", "approve");
        String feedback = acceptedFeedback(runtime, task, "进销存反馈");

        // 分类无效（词面上游逐字）
        assertThat(Cli.run("evolution", "create", "--runtime-dir", runtime.toString(),
                        "--feedback", feedback, "--signature", "s", "--classification", "erp_bug")
                .stdout()).contains("进化分类无效");
        // 反馈编号与稳定失败签名必填
        assertThat(Cli.run("evolution", "create", "--runtime-dir", runtime.toString(),
                        "--feedback", feedback, "--signature", " ", "--classification", "erp_rule")
                .stdout()).contains("进化记录必须包含反馈编号和稳定失败签名");
        // 源任务缺席 → 进化因果链拒绝（反馈指向不存在的任务）
        String orphan = addFeedback(runtime, "TASK-MISSING-99", "孤儿反馈");
        acceptFeedback(runtime, orphan);
        assertThat(Cli.run("evolution", "create", "--runtime-dir", runtime.toString(),
                        "--feedback", orphan, "--signature", "s", "--classification", "erp_rule")
                .stdout()).contains("反馈关联的源任务不存在，不能建立进化因果链");
        // 源任务无业务对象且未显式给 → 至少关联一个 ERP 业务对象
        String bare = seedTask(runtime, "TASK-L15-SRC-04", "[]", "completed",
                "delivery-reviewer", "approve");
        String bareFeedback = acceptedFeedback(runtime, bare, "无对象反馈");
        assertThat(Cli.run("evolution", "create", "--runtime-dir", runtime.toString(),
                        "--feedback", bareFeedback, "--signature", "s",
                        "--classification", "erp_rule")
                .stdout()).contains("进化记录至少关联一个 ERP 业务对象");
        // 同一反馈只能形成一条进化记录（第一条成功，第二条拒绝）
        assertThat(Cli.run("evolution", "create", "--runtime-dir", runtime.toString(),
                        "--feedback", feedback, "--signature", "s2", "--classification", "erp_rule")
                .exitCode()).isZero();
        assertThat(Cli.run("evolution", "create", "--runtime-dir", runtime.toString(),
                        "--feedback", feedback, "--signature", "s3", "--classification", "erp_rule")
                .stdout()).contains("同一反馈只能形成一条进化记录");
    }

    // ---- review / assets 门 ------------------------------------------------------------

    @Test
    void evolutionReviewGateFace(@TempDir Path runtime) {
        String evolution = proposedEvolution(runtime, "TASK-L15-SRC-05", "rev-gate");

        // 具名审核人和理由必填
        assertThat(Cli.run("evolution", "review", "--runtime-dir", runtime.toString(),
                        "--id", evolution, "--reviewer", " ", "--decision", "approve", "--note", "n")
                .stdout()).contains("进化审核必须包含具名审核人和理由");
        assertThat(Cli.run("evolution", "review", "--runtime-dir", runtime.toString(),
                        "--id", evolution, "--reviewer", "r", "--decision", "approve", "--note", " ")
                .stdout()).contains("进化审核必须包含具名审核人和理由");
        // approve → approved + decision_by
        Cli.Result approved = Cli.run("evolution", "review", "--runtime-dir", runtime.toString(),
                "--id", evolution, "--reviewer", "teacher", "--decision", "approve",
                "--note", "同意立项");
        assertThat(approved.exitCode()).as(approved.stdout()).isZero();
        JsonNode row = Cli.json(approved).path("evolution");
        assertThat(row.path("status").asText()).isEqualTo("approved");
        assertThat(row.path("decision_by").asText()).isEqualTo("teacher");
        // 终态不可再迁（上游词面「非法进化状态迁移」）
        assertThat(Cli.run("evolution", "review", "--runtime-dir", runtime.toString(),
                        "--id", evolution, "--reviewer", "t2", "--decision", "reject", "--note", "n")
                .stdout()).contains("非法进化状态迁移");
        // reject / defer 终态（另一条链走 reject）
        String rejected = proposedEvolution(runtime, "TASK-L15-SRC-06", "rev-reject");
        Cli.Result outcome = Cli.run("evolution", "review", "--runtime-dir", runtime.toString(),
                "--id", rejected, "--reviewer", "teacher", "--decision", "defer", "--note", "挂起");
        assertThat(outcome.exitCode()).isZero();
        assertThat(Cli.json(outcome).path("evolution").path("status").asText())
                .isEqualTo("deferred");
    }

    @Test
    void evolutionAssetsGateFace(@TempDir Path runtime) {
        String evolution = proposedEvolution(runtime, "TASK-L15-SRC-07", "assets-gate");
        approveEvolution(runtime, evolution);

        // proposed（未 approve）状态直接登记被拒——另起一条
        String early = proposedEvolution(runtime, "TASK-L15-SRC-08", "assets-early");
        assertThat(Cli.run("evolution", "assets", "--runtime-dir", runtime.toString(),
                        "--id", early, "--actor", "a",
                        "--asset-json", "[{\"type\":\"rule\",\"path\":\"x\",\"reason\":\"r\"}]")
                .stdout()).contains("非法进化状态迁移");

        // 无效类型 / 缺 reason → 词面拒绝
        assertThat(Cli.run("evolution", "assets", "--runtime-dir", runtime.toString(),
                        "--id", evolution, "--actor", "a",
                        "--asset-json", "[{\"type\":\"bug\",\"path\":\"x\",\"reason\":\"r\"}]")
                .stdout()).contains("每项资产变化必须包含有效 type、path 和 reason");
        // 合法登记 → asset_changed
        Cli.Result changed = Cli.run("evolution", "assets", "--runtime-dir", runtime.toString(),
                "--id", evolution, "--actor", "improver",
                "--asset-json", "[{\"type\":\"rule\",\"path\":\"flowerp/service.py\","
                        + "\"reason\":\"两段事务窗口原子化\"}]");
        assertThat(changed.exitCode()).as(changed.stdout()).isZero();
        JsonNode row = Cli.json(changed).path("evolution");
        assertThat(row.path("status").asText()).isEqualTo("asset_changed");
        assertThat(row.path("asset_changes").path(0).path("type").asText()).isEqualTo("rule");
        // 同 type+path 跨批重复登记被拒
        assertThat(Cli.run("evolution", "assets", "--runtime-dir", runtime.toString(),
                        "--id", evolution, "--actor", "a",
                        "--asset-json", "[{\"type\":\"rule\",\"path\":\"flowerp/service.py\","
                                + "\"reason\":\"again\"}]")
                .stdout()).contains("同一类型和路径的资产变化不能重复登记");
    }

    // ---- verify 重门 -------------------------------------------------------------------

    @Test
    void evolutionVerifyGateFace(@TempDir Path runtime) {
        String source = seedTask(runtime, "TASK-L15-VFY-SRC",
                "[\"" + ERP_REF + "\"]", "rework", null, null);
        String evolution = evolutionFromFeedback(runtime, source, "verify-gate");
        approveEvolution(runtime, evolution);
        recordAsset(runtime, evolution, "implementation", "flowerp/service.py", "原子化改进");
        // 候选任务：completed + 具名 approve + 逐项 blocking 报告（受控 reports 目录）
        String candidate = seedCompletedCandidate(runtime, "TASK-L15-VFY-CAND", ERP_REF);
        String unrelated = seedCompletedCandidate(runtime, "TASK-L15-VFY-OTHER", "SKU:OTHER-99");

        // 复用源任务自证被拒（上游词面逐字）
        assertThat(Cli.run("evolution", "verify", "--runtime-dir", runtime.toString(),
                        "--id", evolution, "--task", source, "--actor", "v")
                .stdout()).contains("进化必须由下一项独立交付任务验证，不能复用源任务");
        // 不共享业务对象被拒
        assertThat(Cli.run("evolution", "verify", "--runtime-dir", runtime.toString(),
                        "--id", evolution, "--task", unrelated, "--actor", "v")
                .stdout()).contains("候选任务必须与进化记录共享至少一个 ERP 业务对象");

        // 未完成任务（review 态）被拒：具名交付验收门槛
        String inReview = seedTask(runtime, "TASK-L15-VFY-REVIEW",
                "[\"" + ERP_REF + "\"]", "review", null, null);
        seedReport(runtime, inReview);
        assertThat(Cli.run("evolution", "verify", "--runtime-dir", runtime.toString(),
                        "--id", evolution, "--task", inReview, "--actor", "v")
                .stdout()).contains("候选任务必须完成 Blocking Eval 并经过具名交付验收");

        // governed 反拍：learning/ 资产无绑定 → 拒绝（上游词面逐字）
        recordAsset(runtime, evolution, "skill", "learning/ASSET-DEADBEEF", "经验登记");
        assertThat(Cli.run("evolution", "verify", "--runtime-dir", runtime.toString(),
                        "--id", evolution, "--task", candidate, "--actor", "v")
                .stdout()).contains("后续任务没有实际采用此经验或流程");
    }

    @Test
    void evolutionSummaryFace(@TempDir Path runtime) {
        proposedEvolution(runtime, "TASK-L15-SUM-SRC", "summary-gate");
        Cli.Result summary = Cli.run("evolution", "summary", "--runtime-dir", runtime.toString());
        assertThat(summary.exitCode()).as(summary.stdout()).isZero();
        JsonNode body = Cli.json(summary).path("summary");
        assertThat(body.path("total").asInt(-1)).isEqualTo(1);
        assertThat(body.path("proposed").asInt(-1)).isEqualTo(1);
        assertThat(body.path("verified").asInt(-1)).isZero();
        assertThat(body.path("items").isArray()).isTrue();
        assertThat(body.path("items").path(0).path("status").asText()).isEqualTo("proposed");
    }

    // ---- 造数（SQL 直插夹具——L11 先例；feedback 经 CLI 真链） --------------------------

    /** 先跑最轻 evolution 命令触发建库（红点：命令缺席 → 库缺席 → 断言红），再直插 tasks 行。 */
    private static String seedTask(Path runtime, String taskId, String refsJson, String status,
            String reviewedBy, String reviewDecision) {
        Cli.run("evolution", "summary", "--runtime-dir", runtime.toString());
        Path db = runtime.resolve("workbench.db");
        assertThat(db).as("evolution 命令应已建库（命令缺席即起始红）").isRegularFile();
        String resultJson = "completed".equals(status)
                ? "{\"summary\":{},\"results\":[]}" : null;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement st = conn.prepareStatement(
                     "INSERT INTO tasks(id, request, status, requirement_id, business_refs_json, "
                             + "reviewed_by, review_decision, reviewed_at, result_json, error, "
                             + "created_at, updated_at) "
                             + "VALUES (?, ?, ?, 'REQ-L15-TEST', ?, ?, ?, ?, ?, NULL, "
                             + "datetime('now'), datetime('now'))")) {
            st.setString(1, taskId);
            st.setString(2, "L15 合同测试夹具任务 " + taskId);
            st.setString(3, status);
            st.setString(4, refsJson);
            st.setString(5, reviewedBy);
            st.setString(6, reviewDecision);
            st.setString(7, reviewedBy == null ? null : "2026-10-02T00:00:00+00:00");
            st.setString(8, resultJson);
            st.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("tasks 夹具插入失败：" + taskId, error);
        }
        return taskId;
    }

    /** verify 合格候选：completed + 具名 approve + 逐项 blocking 报告（受控 reports 目录 + sha256）。 */
    private static String seedCompletedCandidate(Path runtime, String taskId, String ref) {
        seedTask(runtime, taskId, "[\"" + ref + "\"]", "completed", "delivery-reviewer", "approve");
        seedReport(runtime, taskId);
        return taskId;
    }

    private static void seedReport(Path runtime, String taskId) {
        try {
            Path reports = runtime.resolve("reports");
            Files.createDirectories(reports);
            Path file = reports.resolve(taskId + "-report.json");
            String report = "{\"summary\":{\"decision\":\"pass\",\"blocking_failed\":0},"
                    + "\"results\":[{\"name\":\"purchase_requires_approval\","
                    + "\"level\":\"blocking\",\"passed\":true}]}";
            Files.writeString(file, report, StandardCharsets.UTF_8);
            String sha = sha256(file);
            try (Connection conn = DriverManager.getConnection(
                    "jdbc:sqlite:" + runtime.resolve("workbench.db"));
                 PreparedStatement st = conn.prepareStatement(
                         "UPDATE tasks SET result_json = ? WHERE id = ?")) {
                st.setString(1, "{\"summary\":{\"decision\":\"pass\",\"blocking_failed\":0},"
                        + "\"results\":[{\"name\":\"purchase_requires_approval\","
                        + "\"level\":\"blocking\",\"passed\":true}],"
                        + "\"report_path\":\"reports/" + file.getFileName() + "\","
                        + "\"report_sha256\":\"" + sha + "\"}");
                st.setString(2, taskId);
                st.executeUpdate();
            }
        } catch (Exception error) {
            throw new IllegalStateException("候选报告夹具失败：" + taskId, error);
        }
    }

    private static String addFeedback(Path runtime, String taskId, String conclusion) {
        Cli.Result added = Cli.run("feedback", "add", "--runtime-dir", runtime.toString(),
                "--task", taskId, "--source", "ops", "--conclusion", conclusion,
                "--next", "由具名人员复核失败是否稳定");
        assertThat(added.exitCode()).as(added.stdout()).isZero();
        return Cli.json(added).path("feedback").path("id").asText();
    }

    private static void acceptFeedback(Path runtime, String feedbackId) {
        Cli.Result reviewed = Cli.run("feedback", "review", "--runtime-dir", runtime.toString(),
                "--id", feedbackId, "--reviewer", "teacher", "--decision", "accept",
                "--note", "测试接受");
        assertThat(reviewed.exitCode()).as(reviewed.stdout()).isZero();
    }

    private static String acceptedFeedback(Path runtime, String taskId, String conclusion) {
        String id = addFeedback(runtime, taskId, conclusion);
        acceptFeedback(runtime, id);
        return id;
    }

    private static String proposedEvolution(Path runtime, String taskId, String signature) {
        return evolutionFromFeedback(runtime, seedTask(runtime, taskId,
                "[\"" + ERP_REF + "\"]", "rework", null, null), signature);
    }

    private static String evolutionFromFeedback(Path runtime, String taskId, String signature) {
        String feedback = acceptedFeedback(runtime, taskId, "进化因果链反馈 " + signature);
        Cli.Result created = Cli.run("evolution", "create", "--runtime-dir", runtime.toString(),
                "--feedback", feedback, "--signature", signature,
                "--classification", "erp_rule");
        assertThat(created.exitCode()).as(created.stdout()).isZero();
        return Cli.json(created).path("evolution").path("id").asText();
    }

    private static void approveEvolution(Path runtime, String evolutionId) {
        Cli.Result approved = Cli.run("evolution", "review", "--runtime-dir", runtime.toString(),
                "--id", evolutionId, "--reviewer", "teacher", "--decision", "approve",
                "--note", "测试批准");
        assertThat(approved.exitCode()).as(approved.stdout()).isZero();
    }

    private static void recordAsset(Path runtime, String evolutionId, String type, String path,
            String reason) {
        Cli.Result recorded = Cli.run("evolution", "assets", "--runtime-dir", runtime.toString(),
                "--id", evolutionId, "--actor", "improver",
                "--asset-json", "[{\"type\":\"" + type + "\",\"path\":\"" + path
                        + "\",\"reason\":\"" + reason + "\"}]");
        assertThat(recorded.exitCode()).as(recorded.stdout()).isZero();
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    }
}
