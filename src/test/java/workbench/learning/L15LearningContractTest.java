package workbench.learning;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import workbench.testsupport.Cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L15 记忆系统合同测试：讲义 docs/lessons/L15-反馈治理与记忆演进.md §2 C2/C3 →
 * 用例映射（对照源 = **S01 冻结面** {@code python-carrier-final:workbench/learning.py}
 * 770 行七命令五表——错误码/词面/状态机/召回语义逐字直承（ADR-0006：冻结面是本仓
 * 建成件的对照基准）；增量 = 上游现版 evolution 连接缝（source 接 evolution_id +
 * 失败轨迹门槛 + feedback_id 级联，spec D2）。
 *
 * <p>形态适配（spec D3 单库四账 = delivery 库，分歧落证据账）：来源准入「已验收」=
 * delivery tasks 的 completed + review_decision=approve + reviewed_by（上游现版
 * source() 判定词面）；S01 绑 L01 主账的 tasks.project_id / executions / reviews
 * 快照引用不采纳（delivery tasks 无 project 列——任务面项目校验省略，治理面
 * govern 与召回面 recall 的项目隔离保留）；learn-run 证据表 = task_events
 * （S01 evidence/executions 的 L15 等价物，模块自取现算 sha256 不收自报）。
 *
 * <p>接缝（spec 已具名）：CLI 公开接口缝——workbench-learn-* 七件（S01 CLI 合同
 * 直承，参数名逐字）经 {@code workbench.cli.Main} 真实子进程驱动；造数 = SQL 直插
 * tasks / task_events 行 + evolution / feedback 命令真链（类 1 已测面）。
 *
 * <p>红点组（commit 1）：workbench-learn-* 命令缺席 → 全部用例起始红；
 * 实现转绿后本类零改动（红基线纪律）。
 *
 * <pre>
 * 用例 → 合同映射（S01 §5 验收门 12 场景 + L15 增量）：
 * learnCreateSourceGateFace      C2/S01-3：来源准入（未验收拒）+ 合格提炼（快照 sha256）
 * learnEvolutionSourceFace       C1/S01-3+L15 增量：evolution 来源 + 失败轨迹门槛 +
 *                                 feedback 级联自动走同一道审核闸门
 * learnGovernFace                C2/S01-2/8：human() 门拒 AI 名义、自审拒、跳过审批拒、
 *                                 非法迁移拒（S01 场景 3/8/9）
 * learnVersionSupersedeFace      C2/S01-6/18：版本链 superseded 原子、旧快照不改写
 * learnRecallFace                C3/S01-1/2/7：项目隔离、仅 active、同源排除、excludes、
 *                                 冲突分组、预算、检索不到如实为空（场景 1/2/7）
 * learnBindRunFinishFace         C2/S01-5/11：BIND 双唯一、决定集逐项对应、五相位、
 *                                 outcome 具名验收（场景 3/11）
 * learnShowDriftFace             C2/S01-5/10：读时重算、漂移报错不静默（场景 5/10）
 * </pre>
 */
class L15LearningContractTest {

    private static final String ERP_REF = "SKU:L15-LRN-01";

    // ---- 来源准入 -----------------------------------------------------------------------

    @Test
    void learnCreateSourceGateFace(@TempDir Path runtime) {
        String reviewing = seedTask(runtime, "TASK-L15-LRN-A", "review", null, null, null);

        // 未具名验收任务 → 拒（错误码沿 S01，词面按 delivery 形态）
        Cli.Result rejected = Cli.run("workbench-learn-create",
                "--runtime-dir", runtime.toString(), "--project-id", "PROJ-L15",
                "--task-id", reviewing, "--family", "FAM-L15-GATE",
                "--title", "验收门", "--content", "内容", "--actor", "extractor");
        assertThat(rejected.exitCode()).as(rejected.stdout()).isOne();
        assertThat(rejected.stdout() + rejected.stderr())
                .contains("source_task_not_accepted").contains("来源任务未具名验收");

        // 任务不存在 → required_task_missing（S01 词面）
        assertThat(Cli.run("workbench-learn-create",
                        "--runtime-dir", runtime.toString(), "--project-id", "PROJ-L15",
                        "--task-id", "TASK-MISSING-00", "--family", "F", "--title", "t",
                        "--content", "c", "--actor", "extractor").stdout())
                .contains("任务不存在：TASK-MISSING-00");

        // 合格：completed + approve + reviewed_by → candidate + 来源快照 sha256
        String accepted = seedTask(runtime, "TASK-L15-LRN-B", "completed",
                "delivery-reviewer", "approve", "断言面板四拍通过");
        Cli.Result created = Cli.run("workbench-learn-create",
                "--runtime-dir", runtime.toString(), "--project-id", "PROJ-L15",
                "--task-id", accepted, "--family", "FAM-L15-GATE",
                "--title", "面板经验", "--content", "四拍先提交后批准",
                "--applies", "panel", "--applies", "review",
                "--boundary", "仅交付面板", "--actor", "extractor");
        assertThat(created.exitCode()).as(created.stdout()).isZero();
        JsonNode asset = Cli.json(created).path("asset");
        assertThat(asset.path("state").asText()).isEqualTo("candidate");
        assertThat(asset.path("family").asText()).isEqualTo("FAM-L15-GATE");
        assertThat(asset.path("version").asInt(-1)).isOne();
        assertThat(asset.path("source").path("task_id").asText()).isEqualTo(accepted);
        assertThat(asset.path("source").path("sha256").asText()).hasSize(64);
    }

    @Test
    void learnEvolutionSourceFace(@TempDir Path runtime) {
        // 失败任务（rework + 失败轨迹事件）+ 已接受反馈 → evolution（approved）
        String failed = seedTask(runtime, "TASK-L15-LRN-FAIL", "rework", null, null,
                "blocking eval 失败：purchase_requires_approval");
        String evolution = approvedEvolutionFor(runtime, failed);

        // evolution 来源 + 失败轨迹在场 → 准入通过
        Cli.Result created = Cli.run("workbench-learn-create",
                "--runtime-dir", runtime.toString(), "--project-id", "PROJ-L15",
                "--task-id", failed, "--evolution-id", evolution,
                "--family", "FAM-L15-EVO", "--title", "失败经验",
                "--content", "审批入库前先核库存约束", "--actor", "extractor");
        assertThat(created.exitCode()).as(created.stdout()).isZero();
        assertThat(Cli.json(created).path("asset").path("source").path("task_id").asText())
                .isEqualTo(failed);

        // 无失败轨迹（error 空 + 无失败事件）→ 上游词面逐字
        String bare = seedTask(runtime, "TASK-L15-LRN-BARE", "rework", null, null, null);
        String bareEvolution = approvedEvolutionFor(runtime, bare);
        assertThat(Cli.run("workbench-learn-create",
                        "--runtime-dir", runtime.toString(), "--project-id", "PROJ-L15",
                        "--task-id", bare, "--evolution-id", bareEvolution,
                        "--family", "FAM-L15-EVO", "--title", "t", "--content", "c",
                        "--actor", "extractor").stdout())
                .contains("失败经验缺少实际失败轨迹");

        // 已拒绝 evolution → 上游词面逐字
        String rejectedTask = seedTask(runtime, "TASK-L15-LRN-REJ", "rework", null, null,
                "blocking eval 失败");
        String rejected = evolutionFor(runtime, rejectedTask);
        assertThat(Cli.run("evolution", "review", "--runtime-dir", runtime.toString(),
                "--id", rejected, "--reviewer", "teacher", "--decision", "reject",
                "--note", "不立项").exitCode()).isZero();
        assertThat(Cli.run("workbench-learn-create",
                        "--runtime-dir", runtime.toString(), "--project-id", "PROJ-L15",
                        "--task-id", rejectedTask, "--evolution-id", rejected,
                        "--family", "FAM-L15-EVO", "--title", "t", "--content", "c",
                        "--actor", "extractor").stdout())
                .contains("Evolution 来源不一致或已拒绝");

        // 未验收任务且无 evolution → 上游词面逐字
        assertThat(Cli.run("workbench-learn-create",
                        "--runtime-dir", runtime.toString(), "--project-id", "PROJ-L15",
                        "--task-id", bare, "--family", "FAM-L15-EVO", "--title", "t",
                        "--content", "c", "--actor", "extractor").stdout())
                .contains("来源需要已验收任务，或已接受失败反馈对应的 Evolution");

        // --feedback-id 级联：已接受反馈自动建 evolution（同一道闸门）——workbench_control
        String cascaded = seedTask(runtime, "TASK-L15-LRN-CAS", "rework", null, null,
                "blocking eval 失败");
        Cli.Result viaFeedback = Cli.run("workbench-learn-create",
                "--runtime-dir", runtime.toString(), "--project-id", "PROJ-L15",
                "--task-id", cascaded, "--feedback-id",
                acceptedFeedbackFor(runtime, cascaded),
                "--family", "FAM-L15-CAS", "--title", "级联签名", "--content", "c",
                "--actor", "extractor");
        assertThat(viaFeedback.exitCode()).as(viaFeedback.stdout()).isZero();
        Cli.Result summary = Cli.run("evolution", "summary", "--runtime-dir", runtime.toString());
        JsonNode items = Cli.json(summary).path("summary").path("items");
        boolean hasCascaded = false;
        for (JsonNode item : items) {
            if ("workbench_control".equals(item.path("classification").asText())
                    && "级联签名".equals(item.path("failure_signature").asText())) {
                hasCascaded = true;
            }
        }
        assertThat(hasCascaded).as("级联应自动建立 workbench_control 进化候选：" + summary.stdout())
                .isTrue();
    }

    // ---- 治理与版本链 -------------------------------------------------------------------

    @Test
    void learnGovernFace(@TempDir Path runtime) {
        String asset = publishedAsset(runtime, "FAM-L15-GOV",
                acceptedTaskForLearning(runtime), "治理经验", "extractor");

        // human() 门：AI 名义被拒（S01 词面——HUMAN_FORBIDDEN 含 claude 增补）
        assertThat(Cli.run("workbench-learn-govern", "--runtime-dir", runtime.toString(),
                        "--project-id", "PROJ-L15", "--asset-id", asset,
                        "--decision", "revoke", "--actor", "claude", "--note", "n").stdout())
                .contains("human_actor_required").contains("操作人必须具名人工");
        assertThat(Cli.run("workbench-learn-govern", "--runtime-dir", runtime.toString(),
                        "--project-id", "PROJ-L15", "--asset-id", asset,
                        "--decision", "revoke", "--actor", "agent:worker", "--note", "n")
                .stdout()).contains("操作人必须具名人工");
        // 提炼者不得自审（S01 词面）
        assertThat(Cli.run("workbench-learn-govern", "--runtime-dir", runtime.toString(),
                        "--project-id", "PROJ-L15", "--asset-id", asset,
                        "--decision", "revoke", "--actor", "extractor", "--note", "n").stdout())
                .contains("self_govern_rejected").contains("提炼者不得自审");
        // 跨项目治理被拒（S01 词面）
        assertThat(Cli.run("workbench-learn-govern", "--runtime-dir", runtime.toString(),
                        "--project-id", "PROJ-OTHER", "--asset-id", asset,
                        "--decision", "revoke", "--actor", "governor", "--note", "n").stdout())
                .contains("project_mismatch");

        // revoke active → revoked；再 revoke → 非法迁移（S01 词面）
        assertThat(Cli.run("workbench-learn-govern", "--runtime-dir", runtime.toString(),
                "--project-id", "PROJ-L15", "--asset-id", asset,
                "--decision", "revoke", "--actor", "governor", "--note", "退役").exitCode())
                .isZero();
        assertThat(Cli.run("workbench-learn-govern", "--runtime-dir", runtime.toString(),
                        "--project-id", "PROJ-L15", "--asset-id", asset,
                        "--decision", "revoke", "--actor", "governor", "--note", "再撤").stdout())
                .contains("invalid_transition").contains("该版本已停用");

        // 跳过审批的 publish 被拒 + approve 后 publish 通过（新资产）
        String fresh = candidateAsset(runtime, "FAM-L15-GOV2",
                acceptedTaskForLearning(runtime), "fresh");
        assertThat(Cli.run("workbench-learn-govern", "--runtime-dir", runtime.toString(),
                        "--project-id", "PROJ-L15", "--asset-id", fresh,
                        "--decision", "publish", "--actor", "governor", "--note", "n").stdout())
                .contains("approve_required").contains("发布前必须先经具名审核");
        assertThat(Cli.run("workbench-learn-govern", "--runtime-dir", runtime.toString(),
                "--project-id", "PROJ-L15", "--asset-id", fresh,
                "--decision", "approve", "--actor", "governor", "--note", "审过").exitCode())
                .isZero();
        // 已 approve 再 approve → 非法迁移（S01 词面）
        assertThat(Cli.run("workbench-learn-govern", "--runtime-dir", runtime.toString(),
                        "--project-id", "PROJ-L15", "--asset-id", fresh,
                        "--decision", "approve", "--actor", "governor2", "--note", "n").stdout())
                .contains("approve 仅限未审核的 candidate");
        Cli.Result published = Cli.run("workbench-learn-govern", "--runtime-dir",
                runtime.toString(), "--project-id", "PROJ-L15", "--asset-id", fresh,
                "--decision", "publish", "--actor", "governor", "--note", "发布");
        assertThat(published.exitCode()).as(published.stdout()).isZero();
        assertThat(Cli.json(published).path("asset").path("state").asText()).isEqualTo("active");
    }

    @Test
    void learnVersionSupersedeFace(@TempDir Path runtime) {
        // 本用例自持已验收来源任务（夹具独立性——不依赖其它用例的库状态）
        acceptedTaskForLearning(runtime);
        // v1 发布（active）→ v2（同 family + supersedes 声明）发布 → v1 原子 superseded
        String v1 = versionedAsset(runtime, "FAM-L15-VER", "v1 内容", null);
        String v2 = versionedAsset(runtime, "FAM-L15-VER", "v2 内容", v1);
        assertThat(Cli.run("workbench-learn-govern", "--runtime-dir", runtime.toString(),
                "--project-id", "PROJ-L15", "--asset-id", v1, "--decision", "approve",
                "--actor", "governor", "--note", "v1").exitCode()).isZero();
        assertThat(Cli.run("workbench-learn-govern", "--runtime-dir", runtime.toString(),
                "--project-id", "PROJ-L15", "--asset-id", v1, "--decision", "publish",
                "--actor", "governor", "--note", "v1").exitCode()).isZero();
        assertThat(Cli.run("workbench-learn-govern", "--runtime-dir", runtime.toString(),
                "--project-id", "PROJ-L15", "--asset-id", v2, "--decision", "approve",
                "--actor", "governor", "--note", "v2").exitCode()).isZero();
        assertThat(Cli.run("workbench-learn-govern", "--runtime-dir", runtime.toString(),
                "--project-id", "PROJ-L15", "--asset-id", v2, "--decision", "publish",
                "--actor", "governor", "--note", "v2").exitCode()).isZero();

        // v2 active、v1 superseded 且历史事件保留（replacement 指向 v2——不改写旧快照）
        Cli.Result v1Show = Cli.run("workbench-learn-show", "--runtime-dir", runtime.toString(),
                "--asset-id", v1);
        assertThat(v1Show.exitCode()).as(v1Show.stdout()).isZero();
        JsonNode v1Body = Cli.json(v1Show).path("asset");
        assertThat(v1Body.path("state").asText()).isEqualTo("superseded");
        boolean supersededEvent = false;
        for (JsonNode event : v1Body.path("events")) {
            if ("superseded".equals(event.path("action").asText())
                    && event.path("evidence").path("replacement").asText().equals(v2)) {
                supersededEvent = true;
            }
        }
        assertThat(supersededEvent).as("v1 应留 superseded 事件（replacement=" + v2 + "）")
                .isTrue();

        // supersedes 指向不同 family → 拒（S01 词面）
        String other = versionedAsset(runtime, "FAM-L15-OTHER", "o", null);
        assertThat(Cli.run("workbench-learn-create",
                        "--runtime-dir", runtime.toString(), "--project-id", "PROJ-L15",
                        "--task-id", acceptedTaskForLearning(runtime),
                        "--family", "FAM-L15-VER", "--title", "t", "--content", "c",
                        "--supersedes", other, "--actor", "extractor").stdout())
                .contains("supersedes_target_invalid");
    }

    // ---- 召回（C3 核心：默认上下文治理） --------------------------------------------------

    @Test
    void learnRecallFace(@TempDir Path runtime) {
        String sourceA = acceptedTaskForLearning(runtime);
        String consumer = seedTask(runtime, "TASK-L15-RECALL-USE", "queued", null, null, null);
        // A 项目：active 命中项 + revoked 项；B 项目：active 项（项目隔离对照组）
        String hit = activeAsset(runtime, "PROJ-L15", "FAM-L15-RC-A", sourceA,
                "A 项目面板经验", "召回前先看投影 freshness", "panel");
        String revoked = activeAsset(runtime, "PROJ-L15", "FAM-L15-RC-R", sourceA,
                "退役经验", "旧口径", "panel");
        assertThat(Cli.run("workbench-learn-govern", "--runtime-dir", runtime.toString(),
                "--project-id", "PROJ-L15", "--asset-id", revoked, "--decision", "revoke",
                "--actor", "governor", "--note", "退役").exitCode()).isZero();
        String other = activeAsset(runtime, "PROJ-B", "FAM-L15-RC-B", sourceA,
                "B 项目经验", "跨项目内容", "panel");
        Cli.Result foreign = Cli.run("workbench-learn-recall", "--runtime-dir",
                runtime.toString(), "--project-id", "PROJ-B", "--task-id", consumer,
                "--keyword", "panel");
        // B 项目只召回 B 资产（项目隔离——撤回与跨项目不进默认上下文）
        assertThat(foreign.exitCode()).as(foreign.stdout()).isZero();
        assertThat(Cli.json(foreign).path("recall").path("matches").size()).isOne();
        assertThat(Cli.json(foreign).path("recall").path("matches").path(0)
                .path("asset_id").asText()).isEqualTo(other);

        // A 项目召回：命中 + revoked 不注入；同源排除（sourceA 自身召回）
        Cli.Result selfRecall = Cli.run("workbench-learn-recall", "--runtime-dir",
                runtime.toString(), "--project-id", "PROJ-L15", "--task-id", sourceA,
                "--keyword", "panel");
        assertThat(selfRecall.exitCode()).as(selfRecall.stdout()).isZero();
        JsonNode selfBody = Cli.json(selfRecall).path("recall");
        boolean sawRevoked = false;
        for (JsonNode match : selfBody.path("matches")) {
            assertThat(match.path("asset_id").asText())
                    .as("撤回记忆不得进入默认上下文").isNotEqualTo(revoked);
            if (match.path("asset_id").asText().equals(revoked)) {
                sawRevoked = true;
            }
        }
        assertThat(sawRevoked).isFalse();
        boolean selfExcluded = false;
        for (JsonNode excluded : selfBody.path("excluded")) {
            if (excluded.path("asset_id").asText().equals(hit)
                    && excluded.path("reason").asText().contains("self_source")) {
                selfExcluded = true;
            }
        }
        assertThat(selfExcluded).as("同源事项不得作为独立复用（S01 词面 self_source）").isTrue();

        // 非同源消费者召回：命中 + 可解释排序理由（applies:panel）
        Cli.Result consumerRecall = Cli.run("workbench-learn-recall", "--runtime-dir",
                runtime.toString(), "--project-id", "PROJ-L15", "--task-id", consumer,
                "--keyword", "panel");
        JsonNode body = Cli.json(consumerRecall).path("recall");
        boolean foundHit = false;
        for (JsonNode match : body.path("matches")) {
            if (match.path("asset_id").asText().equals(hit)) {
                foundHit = true;
                assertThat(match.path("reason").asText()).isEqualTo("applies:panel");
            }
        }
        assertThat(foundHit).as("命中项应进 matches 且理由可解释").isTrue();
        assertThat(body.path("budget").path("limit").asInt(-1)).isEqualTo(12000);
        // 召回包整体落库（RECALL-*，show 可查——检索结果可追溯）
        String recallId = body.path("recall_id").asText();
        assertThat(recallId).startsWith("RECALL-");
        Cli.Result shown = Cli.run("workbench-learn-show", "--runtime-dir", runtime.toString(),
                "--recall-id", recallId);
        assertThat(shown.exitCode()).as(shown.stdout()).isZero();
        assertThat(Cli.json(shown).path("recall").path("recall_id").asText()).isEqualTo(recallId);

        // 检索不到如实为空（关键词无人命中——不编造召回）
        Cli.Result empty = Cli.run("workbench-learn-recall", "--runtime-dir",
                runtime.toString(), "--project-id", "PROJ-L15", "--task-id", consumer,
                "--keyword", "无人命中的关键词");
        assertThat(empty.exitCode()).as(empty.stdout()).isZero();
        assertThat(Cli.json(empty).path("recall").path("matches").size()).isZero();

        // 预算超限进 excluded（S01 词面 budget_exceeded）
        Cli.Result tight = Cli.run("workbench-learn-recall", "--runtime-dir",
                runtime.toString(), "--project-id", "PROJ-L15", "--task-id", consumer,
                "--keyword", "panel", "--budget", "3");
        assertThat(tight.exitCode()).as(tight.stdout()).isZero();
        assertThat(Cli.json(tight).path("recall").path("excluded").toString())
                .contains("budget_exceeded");

        // 任务不存在 → S01 词面
        assertThat(Cli.run("workbench-learn-recall", "--runtime-dir", runtime.toString(),
                        "--project-id", "PROJ-L15", "--task-id", "TASK-MISSING-11",
                        "--keyword", "k").stdout()).contains("任务不存在：TASK-MISSING-11");
    }

    // ---- 采用与复验 ---------------------------------------------------------------------

    @Test
    void learnBindRunFinishFace(@TempDir Path runtime) {
        String source = acceptedTaskForLearning(runtime);
        String consumer = seedTask(runtime, "TASK-L15-BIND-USE", "queued", null, null, null);
        String asset = activeAsset(runtime, "PROJ-L15", "FAM-L15-BIND", source, "绑定经验",
                "采用前复核 freshness", "bind");
        String recallId = recallFor(runtime, consumer, "bind");
        String decisions = "[{\"asset_id\":\"" + asset + "\",\"adopt\":true,\"reason\":\"复用\"}]";

        // 决定集与 matches 不对应 → S01 词面
        assertThat(Cli.run("workbench-learn-bind", "--runtime-dir", runtime.toString(),
                        "--recall-id", recallId, "--task-id", consumer,
                        "--plan-id", "PLAN-BAD", "--actor", "adopter",
                        "--decisions", "[{\"asset_id\":\"ASSET-MISSING\",\"adopt\":true,"
                                + "\"reason\":\"r\"}]").stdout())
                .contains("decision_mismatch")
                .contains("采用决定必须与本轮召回 matches 逐项对应");

        // 合格绑定 → BIND-*（plan/task 双唯一）
        Cli.Result bound = Cli.run("workbench-learn-bind", "--runtime-dir", runtime.toString(),
                "--recall-id", recallId, "--task-id", consumer, "--plan-id", "PLAN-L15-01",
                "--actor", "adopter", "--decisions", decisions);
        assertThat(bound.exitCode()).as(bound.stdout()).isZero();
        String bindingId = Cli.json(bound).path("binding").path("binding_id").asText();
        assertThat(bindingId).startsWith("BIND-");
        assertThat(Cli.json(bound).path("binding").path("assets").path(0)
                .path("asset_id").asText()).isEqualTo(asset);

        // 双唯一：同 task 二次绑定 → S01 词面
        String otherRecall = recallFor(runtime, consumer, "bind");
        assertThat(Cli.run("workbench-learn-bind", "--runtime-dir", runtime.toString(),
                        "--recall-id", otherRecall, "--task-id", consumer,
                        "--plan-id", "PLAN-L15-02", "--actor", "adopter",
                        "--decisions", decisions).stdout())
                .contains("binding_exists").contains("一次交付最多绑定一条");
        // 绑定任务与召回包事项不一致 → S01 词面（recall_task_mismatch 先于 plan 唯一检查）
        assertThat(Cli.run("workbench-learn-bind", "--runtime-dir", runtime.toString(),
                        "--recall-id", otherRecall, "--task-id", source,
                        "--plan-id", "PLAN-L15-01", "--actor", "adopter",
                        "--decisions", decisions).stdout())
                .contains("recall_task_mismatch").contains("与召回包事项");

        // 复验链：三相位落账 + 每相位一次 + 证据任务不符拒 + 记录不存在拒
        int evidenceId = seedEvent(runtime, consumer, "复验执行证据");
        assertThat(Cli.run("workbench-learn-run", "--runtime-dir", runtime.toString(),
                        "--binding-id", bindingId, "--phase", "precheck",
                        "--result", "passed", "--evidence-table", "events",
                        "--evidence-record-id", "99999", "--summary", "s").stdout())
                .contains("evidence_record_missing");
        int foreignEvidence = seedEvent(runtime, source, "别任务证据");
        assertThat(Cli.run("workbench-learn-run", "--runtime-dir", runtime.toString(),
                        "--binding-id", bindingId, "--phase", "precheck",
                        "--result", "passed", "--evidence-table", "events",
                        "--evidence-record-id", String.valueOf(foreignEvidence),
                        "--summary", "s").stdout())
                .contains("evidence_task_mismatch");
        for (String phase : new String[]{"precheck", "implement", "eval"}) {
            assertThat(Cli.run("workbench-learn-run", "--runtime-dir", runtime.toString(),
                    "--binding-id", bindingId, "--phase", phase, "--result", "passed",
                    "--evidence-table", "events",
                    "--evidence-record-id", String.valueOf(evidenceId),
                    "--summary", "相位 " + phase).exitCode()).isZero();
        }
        assertThat(Cli.run("workbench-learn-run", "--runtime-dir", runtime.toString(),
                        "--binding-id", bindingId, "--phase", "eval", "--result", "passed",
                        "--evidence-table", "events",
                        "--evidence-record-id", String.valueOf(evidenceId), "--summary", "s")
                .stdout()).contains("phase_already_recorded").contains("相位已记录，只记一次");

        // finish：缺 reviewer 的 passed → S01 词面逐字（复用验证缺少具名验收）
        assertThat(Cli.run("workbench-learn-finish", "--runtime-dir", runtime.toString(),
                        "--binding-id", bindingId, "--outcome", "passed",
                        "--reviewer", " ", "--note", "n").stdout())
                .contains("reuse_acceptance_missing").contains("复用验证缺少具名验收");
        // AI 名义 reviewer → human 门
        assertThat(Cli.run("workbench-learn-finish", "--runtime-dir", runtime.toString(),
                        "--binding-id", bindingId, "--outcome", "passed",
                        "--reviewer", "claude", "--note", "n").stdout())
                .contains("操作人必须具名人工");
        // 合格 finish → review/outcome 落账
        Cli.Result finished = Cli.run("workbench-learn-finish", "--runtime-dir",
                runtime.toString(), "--binding-id", bindingId, "--outcome", "passed",
                "--reviewer", "XianReallyHot-ZZH", "--note", "复验通过");
        assertThat(finished.exitCode()).as(finished.stdout()).isZero();
        assertThat(Cli.json(finished).path("finish").path("outcome").asText())
                .isEqualTo("passed");
        assertThat(Cli.json(finished).path("finish").path("reviewer").asText())
                .isEqualTo("XianReallyHot-ZZH");

        // 未通过相位不得标 passed（S01 词面）——新绑定走 failed 相位
        String secondConsumer = seedTask(runtime, "TASK-L15-BIND-USE2", "queued", null,
                null, null);
        String secondRecall = recallFor(runtime, secondConsumer, "bind");
        String secondBinding = bindFor(runtime, secondRecall, secondConsumer, "PLAN-L15-03",
                asset);
        int secondEvidence = seedEvent(runtime, secondConsumer, "第二链证据");
        assertThat(Cli.run("workbench-learn-run", "--runtime-dir", runtime.toString(),
                "--binding-id", secondBinding, "--phase", "precheck", "--result", "failed",
                "--evidence-table", "events",
                "--evidence-record-id", String.valueOf(secondEvidence), "--summary", "s")
                .exitCode()).isZero();
        for (String phase : new String[]{"implement", "eval"}) {
            assertThat(Cli.run("workbench-learn-run", "--runtime-dir", runtime.toString(),
                    "--binding-id", secondBinding, "--phase", phase, "--result", "passed",
                    "--evidence-table", "events",
                    "--evidence-record-id", String.valueOf(secondEvidence),
                    "--summary", "s").exitCode()).isZero();
        }
        assertThat(Cli.run("workbench-learn-finish", "--runtime-dir", runtime.toString(),
                        "--binding-id", secondBinding, "--outcome", "passed",
                        "--reviewer", "XianReallyHot-ZZH", "--note", "n").stdout())
                .contains("phase_not_passed").contains("存在未通过相位，不得标记通过");

        // 缺相位 → S01 词面（missing_phase 无条件——outcome=failed 同样要求三相位齐全）——第三链
        String thirdConsumer = seedTask(runtime, "TASK-L15-BIND-USE3", "queued", null,
                null, null);
        String thirdRecall = recallFor(runtime, thirdConsumer, "bind");
        String thirdBinding = bindFor(runtime, thirdRecall, thirdConsumer, "PLAN-L15-04",
                asset);
        int thirdEvidence = seedEvent(runtime, thirdConsumer, "第三链证据");
        assertThat(Cli.run("workbench-learn-run", "--runtime-dir", runtime.toString(),
                "--binding-id", thirdBinding, "--phase", "precheck", "--result", "failed",
                "--evidence-table", "events",
                "--evidence-record-id", String.valueOf(thirdEvidence), "--summary", "s")
                .exitCode()).isZero();
        assertThat(Cli.run("workbench-learn-finish", "--runtime-dir", runtime.toString(),
                        "--binding-id", thirdBinding, "--outcome", "failed",
                        "--reviewer", "XianReallyHot-ZZH", "--note", "复用失败如实落账")
                .stdout()).contains("missing_phase").contains("复验链缺相位");
        for (String phase : new String[]{"implement", "eval"}) {
            assertThat(Cli.run("workbench-learn-run", "--runtime-dir", runtime.toString(),
                    "--binding-id", thirdBinding, "--phase", phase, "--result", "failed",
                    "--evidence-table", "events",
                    "--evidence-record-id", String.valueOf(thirdEvidence), "--summary", "s")
                    .exitCode()).isZero();
        }
        // 三相位齐全后 failed 如实落账（复用失败不因历史成功标记通过——S01 场景 4）
        assertThat(Cli.run("workbench-learn-finish", "--runtime-dir", runtime.toString(),
                        "--binding-id", thirdBinding, "--outcome", "failed",
                        "--reviewer", "XianReallyHot-ZZH", "--note", "复用失败如实落账")
                .exitCode()).as("outcome=failed 且相位齐全应通过").isZero();
    }

    // ---- 读时重算（漂移阻断） --------------------------------------------------------------

    @Test
    void learnShowDriftFace(@TempDir Path runtime) {
        String asset = candidateAsset(runtime, "FAM-L15-SHOW",
                acceptedTaskForLearning(runtime), "展示");
        // 恰给一个目标（S01 词面）
        assertThat(Cli.run("workbench-learn-show", "--runtime-dir", runtime.toString())
                .stdout()).contains("show_target_required").contains("恰给一个");

        Cli.Result shown = Cli.run("workbench-learn-show", "--runtime-dir", runtime.toString(),
                "--asset-id", asset);
        assertThat(shown.exitCode()).as(shown.stdout()).isZero();
        assertThat(Cli.json(shown).path("asset").path("asset_id").asText()).isEqualTo(asset);
        assertThat(Cli.json(shown).path("asset").path("events").path(0).path("action").asText())
                .isEqualTo("candidate");

        // 内容漂移：SQL 改 payload 不更新 sha256 → 读时重算报错不静默（S01 词面）
        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + runtime.resolve("workbench.db"));
             PreparedStatement st = conn.prepareStatement(
                     "UPDATE learning_assets SET payload = '{\"tampered\":true}' "
                             + "WHERE asset_id = ?")) {
            st.setString(1, asset);
            st.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("漂移注入失败", error);
        }
        Cli.Result drifted = Cli.run("workbench-learn-show", "--runtime-dir", runtime.toString(),
                "--asset-id", asset);
        assertThat(drifted.exitCode()).as("漂移应被读时重算抓获").isOne();
        assertThat(drifted.stdout())
                .contains("memory_content_checksum_failed").contains("快照被改动");

        // 资产不存在 → S01 词面
        assertThat(Cli.run("workbench-learn-show", "--runtime-dir", runtime.toString(),
                        "--asset-id", "ASSET-MISSING").stdout())
                .contains("asset_not_found").contains("记忆条目不存在");
    }

    // ---- 造数 ---------------------------------------------------------------------------

    /** 先跑最轻 evolution 命令触发建库（红点：缺席即库缺席断言红），再直插 tasks 行。 */
    private static String seedTask(Path runtime, String taskId, String status,
            String reviewedBy, String reviewDecision, String error) {
        Cli.run("evolution", "summary", "--runtime-dir", runtime.toString());
        assertThat(runtime.resolve("workbench.db"))
                .as("evolution 命令应已建库（命令缺席即起始红）").isRegularFile();
        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + runtime.resolve("workbench.db"));
             PreparedStatement st = conn.prepareStatement(
                     "INSERT INTO tasks(id, request, status, requirement_id, business_refs_json, "
                             + "reviewed_by, review_decision, reviewed_at, result_json, error, "
                             + "created_at, updated_at) VALUES (?, 'L15 记忆夹具', ?, "
                             + "'REQ-L15-TEST', '[\"" + ERP_REF + "\"]', ?, ?, ?, "
                             + "NULL, ?, datetime('now'), datetime('now'))")) {
            st.setString(1, taskId);
            st.setString(2, status);
            st.setString(3, reviewedBy);
            st.setString(4, reviewDecision);
            st.setString(5, reviewedBy == null ? null : "2026-10-02T00:00:00+00:00");
            st.setString(6, error);
            st.executeUpdate();
        } catch (SQLException error2) {
            throw new IllegalStateException("tasks 夹具插入失败：" + taskId, error2);
        }
        return taskId;
    }

    private static int seedEvent(Path runtime, String taskId, String detail) {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + runtime.resolve("workbench.db"));
             PreparedStatement st = conn.prepareStatement(
                     "INSERT INTO task_events(task_id, from_status, to_status, detail, actor) "
                             + "VALUES (?, NULL, 'queued', ?, 'l15-test')")) {
            st.setString(1, taskId);
            st.setString(2, detail);
            st.executeUpdate();
            try (java.sql.Statement query = conn.createStatement();
                 java.sql.ResultSet rs = query.executeQuery(
                         "SELECT last_insert_rowid()")) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("事件夹具插入失败：" + taskId, error);
        }
    }

    private static String acceptedTaskForLearning(Path runtime) {
        return seedTask(runtime, "TASK-L15-ACC-" + System.nanoTime() % 100000, "completed",
                "delivery-reviewer", "approve", null);
    }

    private static String candidateAsset(Path runtime, String family, String sourceTask,
            String title) {
        Cli.Result created = Cli.run("workbench-learn-create", "--runtime-dir",
                runtime.toString(), "--project-id", "PROJ-L15", "--task-id", sourceTask,
                "--family", family, "--title", title, "--content", "内容 " + title,
                "--applies", "panel", "--actor", "extractor");
        assertThat(created.exitCode()).as(created.stdout()).isZero();
        return Cli.json(created).path("asset").path("asset_id").asText();
    }

    private static String publishedAsset(Path runtime, String family, String sourceTask,
            String title, String actor) {
        String asset = candidateAsset(runtime, family, sourceTask, title);
        assertThat(Cli.run("workbench-learn-govern", "--runtime-dir", runtime.toString(),
                "--project-id", "PROJ-L15", "--asset-id", asset, "--decision", "approve",
                "--actor", "governor", "--note", "批").exitCode()).isZero();
        assertThat(Cli.run("workbench-learn-govern", "--runtime-dir", runtime.toString(),
                "--project-id", "PROJ-L15", "--asset-id", asset, "--decision", "publish",
                "--actor", "governor", "--note", "发").exitCode()).isZero();
        return asset;
    }

    /** 同 family 版本链：sourceTask 须同一已验收任务（版本号自增）。 */
    private static String versionedAsset(Path runtime, String family, String content,
            String supersedes) {
        String sourceTask = null;
        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + runtime.resolve("workbench.db"));
             java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT id FROM tasks WHERE status='completed' LIMIT 1")) {
            rs.next();
            sourceTask = rs.getString(1);
        } catch (SQLException error) {
            throw new IllegalStateException("来源任务查询失败", error);
        }
        Cli.Result created = Cli.run("workbench-learn-create", "--runtime-dir",
                runtime.toString(), "--project-id", "PROJ-L15", "--task-id", sourceTask,
                "--family", family, "--title", family + " 版本", "--content", content,
                "--actor", "extractor", "--supersedes", supersedes == null ? "" : supersedes);
        assertThat(created.exitCode()).as(created.stdout()).isZero();
        return Cli.json(created).path("asset").path("asset_id").asText();
    }

    private static String activeAsset(Path runtime, String project, String family,
            String sourceTask, String title, String content, String applies) {
        Cli.Result created = Cli.run("workbench-learn-create", "--runtime-dir",
                runtime.toString(), "--project-id", project, "--task-id", sourceTask,
                "--family", family, "--title", title, "--content", content,
                "--applies", applies, "--actor", "extractor");
        assertThat(created.exitCode()).as(created.stdout()).isZero();
        String asset = Cli.json(created).path("asset").path("asset_id").asText();
        assertThat(Cli.run("workbench-learn-govern", "--runtime-dir", runtime.toString(),
                "--project-id", project, "--asset-id", asset, "--decision", "approve",
                "--actor", "governor", "--note", "批").exitCode()).isZero();
        assertThat(Cli.run("workbench-learn-govern", "--runtime-dir", runtime.toString(),
                "--project-id", project, "--asset-id", asset, "--decision", "publish",
                "--actor", "governor", "--note", "发").exitCode()).isZero();
        return asset;
    }

    private static String recallFor(Path runtime, String taskId, String keyword) {
        Cli.Result recalled = Cli.run("workbench-learn-recall", "--runtime-dir",
                runtime.toString(), "--project-id", "PROJ-L15", "--task-id", taskId,
                "--keyword", keyword);
        assertThat(recalled.exitCode()).as(recalled.stdout()).isZero();
        return Cli.json(recalled).path("recall").path("recall_id").asText();
    }

    private static String bindFor(Path runtime, String recallId, String taskId, String planId,
            String assetId) {
        Cli.Result bound = Cli.run("workbench-learn-bind", "--runtime-dir", runtime.toString(),
                "--recall-id", recallId, "--task-id", taskId, "--plan-id", planId,
                "--actor", "adopter",
                "--decisions", "[{\"asset_id\":\"" + assetId + "\",\"adopt\":true,"
                        + "\"reason\":\"复用\"}]");
        assertThat(bound.exitCode()).as(bound.stdout()).isZero();
        return Cli.json(bound).path("binding").path("binding_id").asText();
    }

    private static String acceptedFeedbackFor(Path runtime, String taskId) {
        Cli.Result added = Cli.run("feedback", "add", "--runtime-dir", runtime.toString(),
                "--task", taskId, "--source", "ops", "--conclusion", "级联反馈",
                "--next", "复核后立项");
        assertThat(added.exitCode()).as(added.stdout()).isZero();
        String id = Cli.json(added).path("feedback").path("id").asText();
        assertThat(Cli.run("feedback", "review", "--runtime-dir", runtime.toString(),
                "--id", id, "--reviewer", "teacher", "--decision", "accept", "--note", "接受")
                .exitCode()).isZero();
        return id;
    }

    private static String evolutionFor(Path runtime, String taskId) {
        String feedback = acceptedFeedbackFor(runtime, taskId);
        Cli.Result created = Cli.run("evolution", "create", "--runtime-dir", runtime.toString(),
                "--feedback", feedback, "--signature", "l15-learning-" + taskId,
                "--classification", "erp_rule");
        assertThat(created.exitCode()).as(created.stdout()).isZero();
        return Cli.json(created).path("evolution").path("id").asText();
    }

    private static String approvedEvolutionFor(Path runtime, String taskId) {
        String evolution = evolutionFor(runtime, taskId);
        assertThat(Cli.run("evolution", "review", "--runtime-dir", runtime.toString(),
                "--id", evolution, "--reviewer", "teacher", "--decision", "approve",
                "--note", "批").exitCode()).isZero();
        return evolution;
    }
}
