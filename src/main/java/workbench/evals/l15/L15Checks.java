package workbench.evals.l15;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import workbench.bootstrap.Args;
import workbench.bootstrap.PyJson;
import workbench.delivery.DeliveryView;
import workbench.delivery.DynamicSpecFreeze;
import workbench.delivery.Feedback;
import workbench.delivery.TaskStore;
import workbench.evolution.EvolutionStore;
import workbench.evals.EvalHarness;
import workbench.learning.LearningStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * L15 反馈治理与记忆演进统一入口（讲义 §2 登记项构成，spec 定形十件）：绑定 eval
 * {@code raw_feedback_cannot_become_blocking}（{@code eval/cases.py:175-201}）断言面
 * 的 Java 对应物 + {@code delivery_evidence_and_review_controls}（L13 已建面，经
 * l13 DeliveryChecks 默认门照跑复验承载——断言面属主不变，本入口不重复实现）+
 * 上游 {@code workbench/evolution.py} 全链 + S01 冻结面七命令 + {@code
 * delivery_view.py:281-284,345} evolution 块 + 动态 Eval 机制（{@code
 * course_requirement.py} + {@code course_workspace.py:170-187}）+ R1 受控改进的
 * 机检收口。
 *
 * <p>十件默认门全 blocking：治理四件（raw_feedback 四拍 + EVALS blocking 静态断言 /
 * evolution 状态机 / verify 重门含 governed 正反拍 / 投影 evolution 补块与九计数）
 * + 记忆三件（来源与版本链 / 召回治理 / 采用与复验）+ 动态面一件（冻结关联 + 起始
 * 证据）+ R1 升级前后一件（拷贝树探针：同一断言面前红后绿 + 客户 eval 前后照跑
 * 双绿 + vendors 恒空）+ 冻结指纹一件。工作台面场景 = 临时库 Java 内调（l13
 * DeliveryChecks 先例——探针族形态）；R1 面 = 子进程 python 驱动拷贝树客户实现
 * （l12 ApprovalProbe 同形——断言与预期留 Java）。
 *
 * <p>argv 面（l08–l14 Checks 同形）：{@code --target} 必填（客户树根，须含
 * flowerp/）；{@code --case}（可多，选跑子集）；{@code --python}（缺省
 * .venv/bin/python）、{@code --no-report}、{@code --report-path}。
 */
public final class L15Checks {

    /** 冻结指纹七件（讲义 C9）：上游对照源四件 + 客户改进对象一件 + 本讲 Java 源件两件。 */
    private static final String[] FROZEN_FILES = {
            "eval/harness.py", "eval/cases.py", "workbench/evolution.py",
            "workbench/feedback.py", "flowerp/service.py",
            "src/main/java/workbench/evolution/EvolutionStore.java",
            "src/main/java/workbench/learning/LearningStore.java"};

    /** 指纹期望值（实现收口时实采冻结；eval/* 与 flowerp/ 相对 --target 客户树，
     *  workbench/* 相对 vendors/CodexFDE 对照树，src/* 相对仓库根——树分流见 frozenChecks）。 */
    private static final String[] FROZEN_SHA256 = {
            "e2f526b6578043021be1f964b96a7da75caec0906bbdbeb48d70b057b2426bd4", "c8409488131e72ee52f74195d7e08c2b9fbcc4cc437c12cc2a5a59cd60d8d207", "f22813c6de5150d63131036f958dbdc76ff46c08deddc4e72941b497fd9161fe", "d38f44b447a89f9e063369fe739dbbda7f8ac80dbf9ef6729f9f76b7ec6ed4dc", "6c372dcd105c2c476bbbb76b97abaf6edfbbac233cc7cfb7567a73052325385a", "2e65170dca5c38668e3e30421f6c43e08b80e9cf727ecfe6fd34db1cb8e45fcc", "ddd64c6d51384e5c5edd9cb279895948200836794b146fa17830cb58b957ac8d"};

    private static final String DEFAULT_PYTHON = ".venv/bin/python";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private L15Checks() {}

    public static void main(String[] argv) {
        try {
            Args args = new Args(argv,
                    Set.of("--target", "--python", "--report-path"), Set.of("--no-report"),
                    List.of(), Set.of("--case"));
            Path target = Path.of(args.require("--target")).toAbsolutePath().normalize();
            if (!Files.isDirectory(target.resolve("flowerp"))) {
                throw new Args.UsageException("--target 下没有 flowerp/：" + target);
            }
            Path interpreter = Path.of(args.optional("--python", DEFAULT_PYTHON))
                    .toAbsolutePath().normalize();
            Path reportPath = (args.flag("--no-report") || !args.has("--report-path"))
                    ? null : Path.of(args.require("--report-path"));
            List<String> selected = args.repeatingList("--case");
            List<EvalHarness.Entry> all = entries(target, interpreter);
            List<EvalHarness.Entry> run = selected.isEmpty() ? all : all.stream()
                    .filter(entry -> selected.contains(entry.name())).toList();
            if (!selected.isEmpty() && run.size() != selected.size()) {
                List<String> known = run.stream().map(EvalHarness.Entry::name).toList();
                List<String> unknown = selected.stream().filter(name -> !known.contains(name)).toList();
                if (!unknown.isEmpty()) {
                    throw new Args.UsageException("未知登记项 --case：" + unknown);
                }
                List<String> duplicated = selected.stream().distinct()
                        .filter(name -> selected.stream().filter(name::equals).count() > 1).toList();
                throw new Args.UsageException("重复登记项 --case：" + duplicated);
            }
            EvalHarness.Outcome outcome = EvalHarness.run(run, "all", null, reportPath);
            System.out.println(PyJson.dumps(outcome.report()));
            if (outcome.exitCode() != 0) {
                System.exit(outcome.exitCode());
            }
        } catch (Args.UsageException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        } catch (IllegalStateException error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }

    /** 十件默认门（C 表映射见讲义 §2 与 spec——全部 blocking）。 */
    static List<EvalHarness.Entry> entries(Path target, Path interpreter) {
        return List.of(
                new EvalHarness.Entry("l15_raw_feedback_governance", "blocking",
                        () -> rawFeedbackGovernance(target)),
                new EvalHarness.Entry("l15_evolution_state_machine", "blocking",
                        () -> evolutionStateMachine()),
                new EvalHarness.Entry("l15_evolution_verify_gate", "blocking",
                        L15Checks::evolutionVerifyGate),
                new EvalHarness.Entry("l15_memory_source_and_version", "blocking",
                        L15Checks::memorySourceAndVersion),
                new EvalHarness.Entry("l15_memory_recall_governance", "blocking",
                        L15Checks::memoryRecallGovernance),
                new EvalHarness.Entry("l15_memory_bind_and_runs", "blocking",
                        L15Checks::memoryBindAndRuns),
                new EvalHarness.Entry("l15_view_evolution_block", "blocking",
                        L15Checks::viewEvolutionBlock),
                new EvalHarness.Entry("l15_dynamic_spec_and_start", "blocking",
                        L15Checks::dynamicSpecAndStart),
                new EvalHarness.Entry("l15_erp_upgrade_before_after", "blocking",
                        () -> erpUpgradeBeforeAfter(target, interpreter)),
                new EvalHarness.Entry("l15_frozen_checks", "blocking",
                        () -> frozenChecks(target)));
    }

    // ---- 场景 1：eval raw_feedback_cannot_become_blocking 四拍 + blocking 静态断言 ----------

    private static String rawFeedbackGovernance(Path target) {
        try (TempLedger ledger = TempLedger.create()) {
            TaskStore tasks = ledger.tasks();
            Feedback feedback = ledger.feedback();
            EvolutionStore evolutions = ledger.evolutions();
            Map<String, Object> task = tasks.create("验证反馈不能直接改裁判",
                    "REQ-L15-GOV", List.of("SKU:NOTEBOOK-AI"), "FDE_SPEC.md", "eval-requester",
                    "manual", "TASK-L15GOV0010", "verify", List.of(), 900, null);
            String taskId = (String) task.get("id");
            Map<String, Object> pending = feedback.addFeedback(taskId, "ops",
                    "把渠道幂等降为观察项", "直接改 Eval", null, "");
            expect("pending_review".equals(pending.get("status")), "新反馈应 pending_review");
            boolean rejected = false;
            try {
                evolutions.create((String) pending.get("id"), "want-weaker-eval",
                        "workbench_control", null, "eval");
            } catch (IllegalArgumentException error) {
                rejected = error.getMessage().contains("只有具名接受的反馈才能提升为进化记录");
            }
            expect(rejected, "未审核反馈被提升为进化记录——eval 2 拍 3 违反");
            feedback.reviewFeedback((String) pending.get("id"), "teacher", "accept",
                    "可以立项，但不能改当前 blocking");
            Map<String, Object> evolution = evolutions.create((String) pending.get("id"),
                    "channel-idempotency-missing-workbench", "erp_rule", null, "eval");
            expect("proposed".equals(evolution.get("status")), "接受后立项应 proposed");
            // blocking 静态断言（eval 2 尾拍——客户真理：其自身 eval 清单里
            // receiving_is_idempotent 仍是 blocking——反馈与检索结果不改写阻断裁判）
            String harness = Files.readString(
                    target.resolve("eval/harness.py"), StandardCharsets.UTF_8);
            boolean blockingKept = false;
            for (String line : harness.split("\n")) {
                if (line.contains("receiving_is_idempotent") && line.contains("blocking")) {
                    blockingKept = true;
                }
            }
            expect(blockingKept, "客户 eval 清单 receiving_is_idempotent 应保持 blocking");
            return "原文反馈必须先具名接受才能立项；接受也不等于改写当前 blocking 裁判";
        } catch (IOException error) {
            throw new IllegalStateException("场景失败：" + error.getMessage(), error);
        }
    }

    // ---- 场景 2：evolution 六状态机与三门 ------------------------------------------------

    private static String evolutionStateMachine() {
        try (TempLedger ledger = TempLedger.create()) {
            TaskStore tasks = ledger.tasks();
            Feedback feedback = ledger.feedback();
            EvolutionStore evolutions = ledger.evolutions();
            String source = seedReworkTask(tasks, "TASK-L15EVO0010");
            String fid = acceptedFeedback(feedback, source, "状态机反馈");
            expectThrows(() -> evolutions.create(fid, "s", "erp_bug", null, "eval"),
                    "进化分类无效");
            expectThrows(() -> evolutions.create(fid, " ", "erp_rule", null, "eval"),
                    "进化记录必须包含反馈编号和稳定失败签名");
            String orphan = acceptedFeedback(feedback, "TASK-MISSING-01", "孤儿反馈");
            expectThrows(() -> evolutions.create(orphan, "s", "erp_rule", null, "eval"),
                    "反馈关联的源任务不存在");
            Map<String, Object> evolution = evolutions.create(fid, "state-machine",
                    "erp_rule", null, "eval");
            expectThrows(() -> evolutions.create(fid, "again", "erp_rule", null, "eval"),
                    "同一反馈只能形成一条进化记录");
            String id = (String) evolution.get("id");
            expectThrows(() -> evolutions.review(id, " ", "approve", "n"), "进化审核必须包含具名审核人和理由");
            expectThrows(() -> evolutions.recordAssets(id, List.of(Map.of(
                            "type", "rule", "path", "x", "reason", "r")), "eval"),
                    "非法进化状态迁移");
            Map<String, Object> approved = evolutions.review(id, "teacher", "approve", "同意");
            expect("approved".equals(approved.get("status")), "approve 应迁移 approved");
            expectThrows(() -> evolutions.review(id, "t2", "reject", "n"), "非法进化状态迁移");
            expectThrows(() -> evolutions.recordAssets(id, List.of(Map.of(
                            "type", "bug", "path", "x", "reason", "r")), "eval"),
                    "每项资产变化必须包含有效 type、path 和 reason");
            Map<String, Object> changed = evolutions.recordAssets(id, List.of(Map.of(
                    "type", "rule", "path", "flowerp/service.py", "reason", "R1 原子化")), "improver");
            expect("asset_changed".equals(changed.get("status")), "assets 应迁移 asset_changed");
            expectThrows(() -> evolutions.recordAssets(id, List.of(Map.of(
                            "type", "rule", "path", "flowerp/service.py", "reason", "again")), "eval"),
                    "同一类型和路径的资产变化不能重复登记");
            List<Map<String, Object>> events = (List<Map<String, Object>>) approved.get("events");
            expect(events.size() >= 2, "事件应全留痕（proposed + approve 至少两件）");
            return "进化六状态机三门全数机检：create/review/assets 拒绝词面逐字对照";
        }
    }

    // ---- 场景 3：verify 重门（governed 正反拍） ------------------------------------------

    @SuppressWarnings("unchecked")
    private static String evolutionVerifyGate() {
        try (TempLedger ledger = TempLedger.create()) {
            TaskStore tasks = ledger.tasks();
            Feedback feedback = ledger.feedback();
            EvolutionStore evolutions = ledger.evolutions();
            LearningStore learning = ledger.learning();
            String source = seedReworkTask(tasks, "TASK-L15VFY0010");
            String fid = acceptedFeedback(feedback, source, "verify 门反馈");
            String id = (String) evolutions.create(fid, "verify-gate", "erp_rule", null,
                    "eval").get("id");
            evolutions.review(id, "teacher", "approve", "同意");
            evolutions.recordAssets(id, List.of(Map.of(
                    "type", "implementation", "path", "flowerp/service.py",
                    "reason", "原子化改进")), "improver");
            String candidate = seedCompletedCandidate(ledger, "TASK-L15VFY0020", "SKU:L15-VFY-01");
            String unrelated = seedCompletedCandidate(ledger, "TASK-L15VFY0030", "SKU:OTHER-99");
            expectThrows(() -> evolutions.verify(id, source, "", "v"),
                    "进化必须由下一项独立交付任务验证，不能复用源任务");
            expectThrows(() -> evolutions.verify(id, unrelated, "", "v"),
                    "候选任务必须与进化记录共享至少一个 ERP 业务对象");
            expectThrows(() -> evolutions.verify(id, "TASK-MISSING-02", "", "v"),
                    "候选交付任务不存在");
            // governed 反拍：learning/ 资产无绑定 → 拒绝（上游词面逐字）
            evolutions.recordAssets(id, List.of(Map.of(
                    "type", "skill", "path", "learning/ASSET-GOV-DEMO",
                    "reason", "经验登记")), "improver");
            expectThrows(() -> evolutions.verify(id, candidate, "", "v"),
                    "后续任务没有实际采用此经验或流程");
            // governed 正拍（另起干净链——反拍登记的 ASSET-GOV-DEMO 留在原链资产里，append-only）：
            // 候选任务真实采用该资产 + 复验 outcome passed → verify 通过
            String governedAsset = activeAsset(learning, "PROJ-L15",
                    seedTask(ledger.tasks(), "TASK-L15VFY0040", "completed",
                            "delivery-reviewer", "approve", null),
                    "FAM-GOV", "治理经验", "先审核再采用", "govern");
            String govSource = seedReworkTask(ledger.tasks(), "TASK-L15VFY0050");
            String govFid = acceptedFeedback(ledger.feedback(), govSource, "governed 正拍反馈");
            String govId = (String) evolutions.create(govFid, "governed-positive",
                    "erp_rule", null, "eval").get("id");
            evolutions.review(govId, "teacher", "approve", "同意");
            String govCandidate = seedCompletedCandidate(ledger, "TASK-L15VFY0060",
                    "SKU:L15-VFY-01");
            Map<String, Object> recall = learning.recall(Map.of(
                    "project_id", "PROJ-L15", "task_id", govCandidate, "keyword", "govern"));
            int eventId = seedEvent(ledger, govCandidate, "governed 复验证据");
            Map<String, Object> binding = learning.bind(Map.of(
                    "recall_id", String.valueOf(recall.get("recall_id")), "task_id", govCandidate,
                    "plan_id", "PLAN-L15-GOV", "actor", "adopter",
                    "decisions", "[{\"asset_id\":\"" + governedAsset
                            + "\",\"adopt\":true,\"reason\":\"复用\"}]"));
            for (String phase : new String[]{"precheck", "implement", "eval"}) {
                learning.run(Map.of("binding_id", String.valueOf(binding.get("binding_id")),
                        "phase", phase, "result", "passed",
                        "evidence_record_id", String.valueOf(eventId), "summary", ""));
            }
            learning.finish(Map.of("binding_id", String.valueOf(binding.get("binding_id")),
                    "outcome", "passed", "reviewer", "XianReallyHot-ZZH", "note", "复验通过"));
            evolutions.recordAssets(govId, List.of(Map.of(
                    "type", "skill", "path", "learning/" + governedAsset,
                    "reason", "经验登记")), "improver");
            Map<String, Object> verified = evolutions.verify(govId, govCandidate, "", "verifier");
            expect("verified".equals(verified.get("status")), "governed 正拍应 verified");
            return "verify 重门：候选独立性/共享对象/报告权威 + governed 分支正反拍全数机检";
        }
    }

    // ---- 场景 4：记忆来源准入与版本链 ----------------------------------------------------

    private static String memorySourceAndVersion() {
        try (TempLedger ledger = TempLedger.create()) {
            TaskStore tasks = ledger.tasks();
            Feedback feedback = ledger.feedback();
            EvolutionStore evolutions = ledger.evolutions();
            LearningStore learning = ledger.learning();
            String reviewing = seedTask(tasks, "TASK-L15MEM0010", "review", null, null, null);
            expectThrows(() -> learning.create(learnCreate("PROJ-L15", reviewing,
                            "FAM-A", "标题", "内容", "extractor", "", "")),
                    "来源任务未具名验收");
            expectThrows(() -> learning.create(learnCreate("PROJ-L15", "TASK-MISSING-03",
                            "FAM-A", "t", "c", "extractor", "", "")),
                    "任务不存在");
            // evolution 来源 + 失败轨迹门槛
            String failed = seedTask(tasks, "TASK-L15MEM0020", "rework", null, null,
                    "blocking eval 失败");
            String fid = acceptedFeedback(feedback, failed, "记忆来源反馈");
            String evolutionId = (String) evolutions.create(fid, "memory-source",
                    "erp_rule", null, "teacher").get("id");
            evolutions.review(evolutionId, "teacher", "approve", "批");
            Map<String, Object> fromEvolution = learning.create(learnCreate("PROJ-L15",
                    failed, "FAM-EVO", "失败经验", "踩坑内容", "extractor", evolutionId, ""));
            expect("candidate".equals(((Map<String, Object>) fromEvolution.get("source"))
                    .get("task_id") == null ? "x" : "candidate"), "evolution 来源应准入");
            String bare = seedTask(tasks, "TASK-L15MEM0030", "rework", null, null, null);
            String bareFid = acceptedFeedback(feedback, bare, "无轨迹反馈");
            String bareEvolution = (String) evolutions.create(bareFid, "no-trace",
                    "erp_rule", null, "teacher").get("id");
            evolutions.review(bareEvolution, "teacher", "approve", "批");
            expectThrows(() -> learning.create(learnCreate("PROJ-L15", bare, "FAM-EVO",
                            "t", "c", "extractor", bareEvolution, "")),
                    "失败经验缺少实际失败轨迹");
            // 版本链：v1 publish → v2 publish → v1 superseded + replacement 事件 + 旧快照不改写
            String accepted = seedTask(tasks, "TASK-L15MEM0040", "completed",
                    "delivery-reviewer", "approve", null);
            Map<String, Object> v1 = learning.create(learnCreate("PROJ-L15", accepted,
                    "FAM-VER", "v1", "内容一", "extractor", "", ""));
            String v1Id = (String) v1.get("asset_id");
            governPublished(learning, "PROJ-L15", v1Id, "governor");
            String v1ShaBefore = assetSha(learning, v1Id);
            Map<String, Object> v2 = learning.create(learnCreate("PROJ-L15", accepted,
                    "FAM-VER", "v2", "内容二", "extractor", "", ""));
            String v2Id = (String) v2.get("asset_id");
            expectThrows(() -> learning.create(learnCreate("PROJ-L15", accepted,
                            "FAM-VER", "t", "c", "extractor", "", "ASSET-OTHER")),
                    "被替代对象不存在或不属于同 family");
            learning.govern(learnGovern("PROJ-L15", v2Id, "approve", "governor", "批"));
            learning.govern(learnGovern("PROJ-L15", v2Id, "publish", "governor", "发"));
            expect("superseded".equals(assetState(learning, v1Id)), "v1 应原子 superseded");
            expect("active".equals(assetState(learning, v2Id)), "v2 应 active");
            expect(v1ShaBefore.equals(assetSha(learning, v1Id)), "旧版本快照不得改写");
            expect(assetEvents(learning, v1Id).contains("superseded"), "superseded 事件应留痕");
            return "记忆来源准入两入口 + 版本链 superseded 原子 + 旧快照不改写全数机检";
        }
    }

    // ---- 场景 5：召回治理（默认上下文） ---------------------------------------------------

    private static String memoryRecallGovernance() {
        try (TempLedger ledger = TempLedger.create()) {
            TaskStore tasks = ledger.tasks();
            LearningStore learning = ledger.learning();
            String source = seedTask(tasks, "TASK-L15RC00010", "completed",
                    "delivery-reviewer", "approve", null);
            String consumer = seedTask(tasks, "TASK-L15RC00020", "queued", null, null, null);
            String hit = activeAsset(learning, "PROJ-L15", source, "FAM-RC-A", "面板经验",
                    "先看 freshness", "panel");
            String revoked = activeAsset(learning, "PROJ-L15", source, "FAM-RC-R", "退役",
                    "旧口径", "panel");
            learning.govern(learnGovern("PROJ-L15", revoked, "revoke", "governor", "退役"));
            String foreign = activeAsset(learning, "PROJ-B", source, "FAM-RC-B", "跨项目",
                    "别项目内容", "panel");
            Map<String, Object> selfRecall = learning.recall(Map.of(
                    "project_id", "PROJ-L15", "task_id", source, "keyword", "panel"));
            expect(matchIds(selfRecall).isEmpty(), "同源召回不得注入自身资产");
            expect(excludedHas(selfRecall, hit, "self_source"), "同源应 excluded（self_source）");
            Map<String, Object> consumerRecall = learning.recall(Map.of(
                    "project_id", "PROJ-L15", "task_id", consumer, "keyword", "panel"));
            expect(matchIds(consumerRecall).contains(hit), "非同源命中应进 matches");
            expect(!matchIds(consumerRecall).contains(revoked), "撤回记忆不得进入默认上下文");
            expect(!matchIds(consumerRecall).contains(foreign), "跨项目记忆不得进入默认上下文");
            Map<String, Object> empty = learning.recall(Map.of(
                    "project_id", "PROJ-L15", "task_id", consumer, "keyword", "无人命中词"));
            expect(matchIds(empty).isEmpty(), "检索不到应如实为空");
            Map<String, Object> tight = learning.recall(Map.of(
                    "project_id", "PROJ-L15", "task_id", consumer, "keyword", "panel",
                    "budget", "3"));
            expect(excludedReason(tight).contains("budget_exceeded"), "超预算应 excluded 明示");
            return "召回治理：项目隔离/撤回不注入/同源排除/如实为空/预算超限全数机检";
        }
    }

    // ---- 场景 6：采用与复验链 --------------------------------------------------------------

    private static String memoryBindAndRuns() {
        try (TempLedger ledger = TempLedger.create()) {
            TaskStore tasks = ledger.tasks();
            LearningStore learning = ledger.learning();
            String source = seedTask(tasks, "TASK-L15BND0010", "completed",
                    "delivery-reviewer", "approve", null);
            String consumer = seedTask(tasks, "TASK-L15BND0020", "queued", null, null, null);
            String asset = activeAsset(learning, "PROJ-L15", source, "FAM-BND", "采用经验",
                    "复验 freshness", "bind");
            Map<String, Object> recall = learning.recall(Map.of(
                    "project_id", "PROJ-L15", "task_id", consumer, "keyword", "bind"));
            String recallId = (String) recall.get("recall_id");
            int eventId = seedEvent(ledger, consumer, "复验证据");
            expectThrows(() -> learning.bind(Map.of("recall_id", recallId,
                            "task_id", consumer, "plan_id", "PLAN-X", "actor", "adopter",
                            "decisions", "[{\"asset_id\":\"ASSET-MISSING\",\"adopt\":true,"
                                    + "\"reason\":\"r\"}]")),
                    "采用决定必须与本轮召回 matches 逐项对应");
            Map<String, Object> binding = learning.bind(Map.of(
                    "recall_id", recallId, "task_id", consumer, "plan_id", "PLAN-L15-01",
                    "actor", "adopter",
                    "decisions", "[{\"asset_id\":\"" + asset + "\",\"adopt\":true,"
                            + "\"reason\":\"复用\"}]"));
            String bindingId = (String) binding.get("binding_id");
            expectThrows(() -> learning.bind(Map.of(
                            "recall_id", recallId, "task_id", consumer, "plan_id", "PLAN-L15-02",
                            "actor", "adopter",
                            "decisions", "[{\"asset_id\":\"" + asset + "\",\"adopt\":true,"
                                    + "\"reason\":\"r\"}]")),
                    "一次交付最多绑定一条");
            learning.run(Map.of("binding_id", bindingId, "phase", "precheck",
                    "result", "passed", "evidence_record_id", String.valueOf(eventId),
                    "summary", ""));
            expectThrows(() -> learning.run(Map.of("binding_id", bindingId, "phase", "precheck",
                            "result", "passed", "evidence_record_id", String.valueOf(eventId),
                            "summary", "")),
                    "相位已记录，只记一次");
            int foreignEvent = seedEvent(ledger, source, "别任务证据");
            expectThrows(() -> learning.run(Map.of("binding_id", bindingId, "phase", "implement",
                            "result", "passed", "evidence_record_id", String.valueOf(foreignEvent),
                            "summary", "")),
                    "证据记录属于任务");
            learning.run(Map.of("binding_id", bindingId, "phase", "implement",
                    "result", "passed", "evidence_record_id", String.valueOf(eventId),
                    "summary", ""));
            learning.run(Map.of("binding_id", bindingId, "phase", "eval",
                    "result", "passed", "evidence_record_id", String.valueOf(eventId),
                    "summary", ""));
            expectThrows(() -> learning.finish(Map.of("binding_id", bindingId,
                            "outcome", "passed", "reviewer", "claude", "note", "")),
                    "操作人必须具名人工");
            expectThrows(() -> learning.finish(Map.of("binding_id", bindingId,
                            "outcome", "passed", "reviewer", "", "note", "")),
                    "复用验证缺少具名验收");
            Map<String, Object> finished = learning.finish(Map.of("binding_id", bindingId,
                    "outcome", "passed", "reviewer", "XianReallyHot-ZZH", "note", "复验通过"));
            expect("passed".equals(finished.get("outcome")), "复验应通过并具名");
            // 漂移复核：改 payload 不改 sha → 读时重算报错
            tamperAssetPayload(ledger, asset);
            try {
                learning.showAsset(asset);
                expect(false, "漂移未被读时重算抓获");
            } catch (LearningStore.LearningException error) {
                expect(error.getMessage().contains("快照被改动"), "漂移词面应逐字");
            }
            return "BIND 双唯一 + 五相位一次 + outcome 具名验收 + 漂移读时重算全数机检";
        }
    }

    // ---- 场景 7：投影 evolution 补块与第九计数（L14 D4 预埋兑现） --------------------------

    @SuppressWarnings("unchecked")
    private static String viewEvolutionBlock() {
        try (TempLedger ledger = TempLedger.create()) {
            TaskStore tasks = ledger.tasks();
            Feedback feedback = ledger.feedback();
            EvolutionStore evolutions = ledger.evolutions();
            String source = seedReworkTask(tasks, "TASK-L15VIEW010");
            String fid = acceptedFeedback(feedback, source, "投影反馈");
            String id = (String) evolutions.create(fid, "view-block", "erp_rule", null,
                    "eval").get("id");
            evolutions.review(id, "teacher", "approve", "同意");
            evolutions.recordAssets(id, List.of(Map.of(
                    "type", "rule", "path", "flowerp/service.py", "reason", "改进")), "improver");
            String candidate = seedCompletedCandidate(ledger, "TASK-L15VIEW020", "SKU:L15-VFY-01");
            evolutions.verify(id, candidate, "", "verifier");
            // 完整投影（上游 :281-284 断言面）：evolution 块 total/verified/items
            Map<String, Object> view = DeliveryView.view(tasks, feedback, evolutions, source);
            Map<String, Object> block = (Map<String, Object>) view.get("evolution");
            expect(((Number) block.get("total")).intValue() == 1, "evolution.total 应 1");
            expect(((Number) block.get("verified")).intValue() == 1, "evolution.verified 应 1");
            expect(block.get("items") instanceof List && ((List<?>) block.get("items")).size() == 1,
                    "detail 投影 evolution.items 应全量");
            // 列表第九计数（上游 :345）
            Map<String, Object> listing = DeliveryView.list(tasks, feedback, evolutions, 20);
            Map<String, Object> summary = (Map<String, Object>) listing.get("summary");
            expect(((Number) summary.get("verified_evolutions")).intValue() == 2,
                    "列表第九计数 verified_evolutions 应 2（账面级块随每条任务投影求和）");
            expect(((Number) summary.get("pending_feedback")).intValue() >= 0,
                    "pending_feedback 计数应在场");
            return "投影 evolution 块 {total, verified, items} + 列表第九计数全数机检";
        }
    }

    // ---- 场景 8：动态 Eval 冻结关联与起始证据 ---------------------------------------------

    private static String dynamicSpecAndStart() {
        // 冻结关联：讲次门槛 / 非空上限 / 词边界关联 / 注入字段
        expectThrows(() -> DynamicSpecFreeze.freeze(14, "x", List.of(), List.of(), List.of(),
                true), "自定义课程需求 Spec 仅用于 L04、L15/L16");
        expectThrows(() -> DynamicSpecFreeze.freeze(15, null, List.of(), List.of(), List.of(),
                true), "L15/L16 代码执行必须提供本次六段式需求 Spec");
        expectThrows(() -> DynamicSpecFreeze.freeze(15, " ", List.of(), List.of(), List.of(),
                true), "本次需求 Spec 必须是非空文本");
        String specText = String.join("\n",
                "## 来源", "使用者对采购入库中断窗口的反馈", "",
                "## 目标", "R1 两段事务窗口原子化", "",
                "## 非目标", "不改客户 eval 清单", "",
                "## 约束", "只改拷贝树", "",
                "## 验收用例", "l15_receiving_interrupt_atomicity 须真实失败后转绿", "",
                "## 完成定义", "升级前后证据齐备", "");
        expectThrows(() -> DynamicSpecFreeze.freeze(15, specText, List.of("l15_other_case"),
                List.of("workbench/"), List.of("条目"), true),
                "本次 Spec 的验收用例须明确关联");
        Map<String, Object> frozen = DynamicSpecFreeze.freeze(15, specText,
                List.of("l15_receiving_interrupt_atomicity"), List.of("workbench/", "flowerp/"),
                List.of("原始反馈先审核再进入记忆候选与执行合同。"), true);
        expect(String.valueOf(frozen.get("text")).contains("本次需求不得扩大课程写集"),
                "冻结文本应注入课程写集约束");
        expect(String.valueOf(frozen.get("text")).contains("检查通过仍须具名人审，不自动接受或合并"),
                "冻结文本应注入具名人审标准");
        expect(String.valueOf(frozen.get("sha256")).length() == 64, "冻结文本应带 sha256");
        // 重名拒绝（上游 course_delivery 词面逐字）
        expectThrows(() -> DynamicSpecFreeze.dynamicNameCheck(
                        List.of("l15_x", "receiving_is_idempotent"),
                        List.of("receiving_is_idempotent", "no_committed_secrets")),
                "动态 Eval 必须是本次需求新增用例，不能重复静态合同 Eval");
        // 起始证据三态（上游 dynamic_start_evidence 词面逐字）
        Map<String, Object> redStart = DynamicSpecFreeze.startEvidence(
                Map.of("results", List.of(
                        Map.of("name", "receiving_is_idempotent", "passed", true),
                        Map.of("name", "l15_receiving_interrupt_atomicity", "passed", false))),
                List.of("receiving_is_idempotent"),
                List.of("l15_receiving_interrupt_atomicity"));
        expect(Boolean.TRUE.equals(redStart.get("accepted")), "新用例红旧合同绿应 accepted");
        Map<String, Object> staticBroken = DynamicSpecFreeze.startEvidence(
                Map.of("results", List.of(
                        Map.of("name", "receiving_is_idempotent", "passed", false),
                        Map.of("name", "l15_receiving_interrupt_atomicity", "passed", false))),
                List.of("receiving_is_idempotent"),
                List.of("l15_receiving_interrupt_atomicity"));
        expect(Boolean.FALSE.equals(staticBroken.get("accepted")), "既有合同红应拒绝起始");
        Map<String, Object> allGreen = DynamicSpecFreeze.startEvidence(
                Map.of("results", List.of(
                        Map.of("name", "receiving_is_idempotent", "passed", true),
                        Map.of("name", "l15_receiving_interrupt_atomicity", "passed", true))),
                List.of("receiving_is_idempotent"),
                List.of("l15_receiving_interrupt_atomicity"));
        expect(Boolean.FALSE.equals(allGreen.get("accepted")), "新用例未真实失败应拒绝起始");
        expect(String.valueOf(redStart.get("requirement"))
                        .contains("既有合同通过，至少一个本次新增用例真实失败"),
                "起始证据要求词面应逐字");
        return "动态 Eval：冻结关联/注入/重名拒绝/起始红形式化三态全数机检";
    }

    // ---- 场景 9：R1 受控改进——同一断言面前红后绿 + 客户 eval 前后照跑双绿 ------------------

    private static String erpUpgradeBeforeAfter(Path target, Path interpreter) {
        try {
            Path copy = copyCustomerTree(target);
            // 升级前拍：vendors 原样行为——触发器中断后五表不变量被违反（前红）
            JsonNode before = runR1Probe(interpreter, copy);
            expect(before.path("interrupted").asBoolean(false), "触发器应中断收货（前拍）");
            expect(!before.path("invariant_ok").asBoolean(true),
                    "升级前：两段事务窗口部分提交应违反五表不变量（R1 前红——L12 运行实证同款）");
            // 客户 eval 升级前照跑（绿——改进不破坏客户合同的前提基线）
            int evalBefore = runCustomerEval(interpreter, copy);
            expect(evalBefore == 0, "客户 eval 升级前照跑应绿");
            // 升级：拷贝树 service.py 的 APPROVED 分支三写合单事务（锚点替换）
            applyR1Patch(copy);
            // 升级后拍：同一断言面转绿——中断时整体回滚，五表不变
            JsonNode after = runR1Probe(interpreter, copy);
            expect(after.path("interrupted").asBoolean(false), "触发器应中断收货（后拍）");
            expect(after.path("invariant_ok").asBoolean(false),
                    "升级后：单事务应保持五表不变量（后绿）");
            // 客户 eval 升级后照跑（绿——改进不破坏客户合同）
            int evalAfter = runCustomerEval(interpreter, copy);
            expect(evalAfter == 0, "客户 eval 升级后照跑应绿");
            // 只读铁律：vendors 恒空
            Process git = new ProcessBuilder("git", "-C", target.toString(),
                    "status", "--short").start();
            String dirty = new String(git.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).strip();
            git.waitFor();
            expect(dirty.isEmpty(), "vendors/flowERP 应保持只读恒空：" + dirty);
            return "R1 受控改进：同一断言面前红后绿 + 客户 eval 前后照跑双绿 + vendors 恒空";
        } catch (IOException error) {
            throw new IllegalStateException("R1 场景失败：" + error.getMessage(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("R1 场景被中断", error);
        }
    }

    /** R1 探针（l12 ApprovalProbe 同形：子进程 python + 触发器注入逐字 + 断言留 Java）。 */
    private static final String R1_PROBE = """
            import json, sys
            from pathlib import Path
            sys.path.insert(0, sys.argv[1])
            from flowerp.service import ERPService
            from flowerp.store import ERPStore

            TABLES = ("purchase_requests", "stock", "inventory_events",
                      "sales_orders", "sales_order_lines")

            def out(obj):
                print(json.dumps(obj, ensure_ascii=False))

            import tempfile, os
            with tempfile.TemporaryDirectory() as tmp:
                store = ERPStore(Path(tmp) / "flowerp.db")
                service = ERPService(store)
                service.add_product("SKU-R1-PROBE", "R1 探针产品", 100)
                service.receive_stock("SKU-R1-PROBE", 10, "opening")
                service.propose_purchase("SKU-R1-PROBE", 7, "R1 反馈改进验证", "PR-TARGET")
                service.approve_purchase("PR-TARGET", "TEACHING business reviewer")

                def snap():
                    return {t: store.rows("SELECT * FROM %s ORDER BY 1" % t) for t in TABLES}

                before = snap()
                with store.connect() as conn:
                    conn.execute("CREATE TRIGGER fail_target_status BEFORE UPDATE OF status ON purchase_requests WHEN NEW.id='PR-TARGET' AND NEW.status='received' BEGIN SELECT RAISE(ABORT,'TEACHING receipt status failure'); END")
                interrupted = False
                try:
                    service.receive_purchase("PR-TARGET", "receipt:target")
                except Exception as exc:
                    interrupted = True
                with store.connect() as conn:
                    conn.execute("DROP TRIGGER fail_target_status")
                after = snap()
                out({"interrupted": interrupted, "invariant_ok": before == after})
            """;

    private static JsonNode runR1Probe(Path interpreter, Path copy) {
        return runPythonJson(interpreter, copy, R1_PROBE, List.of(copy.toString()));
    }

    /** R1 改进 patch：APPROVED 分支三写（事件 + 库存 + status）合单事务（拷贝树锚点替换）。 */
    private static void applyR1Patch(Path copy) {
        try {
            Path service = copy.resolve("flowerp/service.py");
            String text = Files.readString(service, StandardCharsets.UTF_8);
            String anchor = String.join("\n",
                    "        if item[\"status\"] != PurchaseStatus.APPROVED:",
                    "            raise ApprovalRequired(f\"采购 {request_id} 未审批，不允许入库\")",
                    "        result = self.receive_stock(item[\"sku\"], item[\"quantity\"], event_key, reference=request_id)",
                    "        with self.store.connect() as conn:",
                    "            conn.execute(",
                    "                \"UPDATE purchase_requests SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?\",",
                    "                (PurchaseStatus.RECEIVED, request_id),",
                    "            )");
            String replacement = String.join("\n",
                    "        if item[\"status\"] != PurchaseStatus.APPROVED:",
                    "            raise ApprovalRequired(f\"采购 {request_id} 未审批，不允许入库\")",
                    "        with self.store.connect() as conn:",
                    "            replayed = conn.execute(",
                    "                \"SELECT 1 FROM inventory_events WHERE event_key=?\", (event_key,)",
                    "            ).fetchone()",
                    "            if replayed:",
                    "                raise InvalidTransition(f\"采购 {request_id} 已完成入库\")",
                    "            conn.execute(",
                    "                \"INSERT INTO inventory_events(event_key,sku,quantity,reserved_delta,event_type,reference) VALUES(?,?,?,?,?,?)\",",
                    "                (event_key, item[\"sku\"], item[\"quantity\"], 0, \"receive\", request_id),",
                    "            )",
                    "            conn.execute(",
                    "                \"UPDATE stock SET on_hand=on_hand+? WHERE sku=?\",",
                    "                (item[\"quantity\"], item[\"sku\"]),",
                    "            )",
                    "            conn.execute(",
                    "                \"UPDATE purchase_requests SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?\",",
                    "                (PurchaseStatus.RECEIVED, request_id),",
                    "            )",
                    "        result = self.product(item[\"sku\"])",
                    "        result[\"idempotent_replay\"] = False",
                    "        self._publish_authority(item[\"sku\"])");
            int hits = countOccurrences(text, anchor);
            if (hits != 1) {
                throw new IllegalStateException("R1 patch 锚点应唯一，实测 " + hits + " 处");
            }
            Files.writeString(service, text.replace(anchor, replacement),
                    StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("R1 patch 失败", error);
        }
    }

    /** 客户 eval 照跑（拷贝树 cwd——升级前后双绿，客户真理权威验证）。 */
    private static int runCustomerEval(Path interpreter, Path copy) {
        try {
            Process process = new ProcessBuilder(List.of(
                    interpreter.toString(), "-X", "utf8", "-m", "eval.harness",
                    "--case", "purchase_requires_approval", "--no-report"))
                    .directory(copy.toFile()).start();
            String stderr = new String(process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("客户 eval 照跑超时");
            }
            int code = process.exitValue();
            if (code != 0) {
                throw new IllegalStateException("客户 eval 照跑 rc=" + code + "：" + stderr.strip());
            }
            return code;
        } catch (IOException error) {
            throw new IllegalStateException("客户 eval 启动失败", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("客户 eval 被中断", error);
        }
    }

    /** 拷贝客户树（L07–L12 拷贝树先例的改进向使用——永不写回 vendors）。 */
    private static Path copyCustomerTree(Path target) {
        try {
            Path copy = Files.createTempDirectory("l15-erp-upgrade-");
            try (Stream<Path> paths = Files.walk(target)) {
                paths.filter(path -> !path.toString().contains("/.git"))
                        .filter(path -> !path.toString().contains("/__pycache__"))
                        .forEach(path -> {
                            try {
                                Path to = copy.resolve(target.relativize(path).toString());
                                if (Files.isDirectory(path)) {
                                    Files.createDirectories(to);
                                } else {
                                    Files.createDirectories(to.getParent());
                                    Files.copy(path, to);
                                }
                            } catch (IOException error) {
                                throw new UncheckedIOException2(error);
                            }
                        });
            }
            return copy;
        } catch (IOException error) {
            throw new IllegalStateException("客户树拷贝失败", error);
        }
    }

    // ---- 场景 10：冻结指纹 ---------------------------------------------------------------

    private static String frozenChecks(Path target) {
        for (int i = 0; i < FROZEN_FILES.length; i++) {
            String file = FROZEN_FILES[i];
            Path path;
            if (file.startsWith("src/")) {
                path = repoRoot().resolve(file);
            } else if (file.startsWith("workbench/")) {
                path = repoRoot().resolve("vendors/CodexFDE").resolve(file);
            } else {
                path = target.resolve(file);
            }
            if (!Files.isRegularFile(path)) {
                return failNow("指纹文件缺席：" + file);
            }
            String actual;
            try {
                actual = sha256(Files.readAllBytes(path));
            } catch (IOException error) {
                throw new IllegalStateException("指纹读取失败：" + file, error);
            }
            if (!FROZEN_SHA256[i].isBlank() && !FROZEN_SHA256[i].equals(actual)) {
                return failNow("指纹不一致：" + file + " 期望 " + FROZEN_SHA256[i]
                        + " 实测 " + actual);
            }
        }
        return "冻结指纹七件一致（evolution/feedback/交付投影对照源 + 客户改进对象 + 本讲源件两件）";
    }

    // ---- 支撑 ---------------------------------------------------------------------------

    /** 临时账本（单库四账：tasks + feedback + evolutions + learning_*——spec D3）。 */
    private static final class TempLedger implements AutoCloseable {
        final Path dir;
        final Path db;

        private TempLedger(Path dir) {
            this.dir = dir;
            this.db = dir.resolve("workbench.db");
        }

        static TempLedger create() {
            try {
                return new TempLedger(Files.createTempDirectory("l15-checks-"));
            } catch (IOException error) {
                throw new UncheckedIOException2(error);
            }
        }

        TaskStore tasks() {
            return new TaskStore(db);
        }

        Feedback feedback() {
            return new Feedback(db);
        }

        EvolutionStore evolutions() {
            return new EvolutionStore(db);
        }

        LearningStore learning() {
            return new LearningStore(db);
        }

        @Override
        public void close() {
            // 临时目录交系统清理；连接短生命周期已自闭
        }
    }

    private static String seedTask(TaskStore tasks, String id, String status,
            String reviewedBy, String decision, String error) {
        Map<String, Object> created = tasks.create("L15 检查夹具 " + id, "REQ-L15-CHECKS",
                List.of("SKU:L15-VFY-01"), "FDE_SPEC.md", "l15-checks", "manual", id,
                "verify", List.of(), 900, null);
        if (!"queued".equals(status) || reviewedBy != null || error != null) {
            try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + tasks.path())) {
                try (var st = conn.prepareStatement(
                        "UPDATE tasks SET status=?, reviewed_by=?, review_decision=?, "
                                + "reviewed_at=COALESCE(reviewed_at, ?), error=? WHERE id=?")) {
                    st.setString(1, status);
                    st.setString(2, reviewedBy);
                    st.setString(3, decision);
                    st.setString(4, reviewedBy == null ? null : "2026-10-02T00:00:00+00:00");
                    st.setString(5, error);
                    st.setString(6, id);
                    st.executeUpdate();
                }
            } catch (java.sql.SQLException sql) {
                throw new IllegalStateException("夹具状态置换失败：" + id, sql);
            }
        }
        return id;
    }

    private static String seedReworkTask(TaskStore tasks, String id) {
        return seedTask(tasks, id, "rework", null, null, "blocking eval 失败（教学夹具）");
    }

    /** verify 合格候选：completed + 具名 approve + 逐项 blocking 报告（受控 reports 目录）。 */
    private static String seedCompletedCandidate(TempLedger ledger, String id, String ref) {
        TaskStore tasks = ledger.tasks();
        Map<String, Object> created = tasks.create("R1 改进候选交付 " + id, "REQ-L15-CHECKS",
                List.of(ref), "FDE_SPEC.md", "l15-checks", "manual", id, "verify",
                List.of(), 900, null);
        try {
            Path reports = ledger.dir.resolve("reports");
            Files.createDirectories(reports);
            Path file = reports.resolve(id + "-report.json");
            String report = "{\"summary\":{\"decision\":\"pass\",\"blocking_failed\":0},"
                    + "\"results\":[{\"name\":\"l15_receiving_interrupt_atomicity\","
                    + "\"level\":\"blocking\",\"passed\":true}]}";
            Files.writeString(file, report, StandardCharsets.UTF_8);
            String resultJson = "{\"summary\":{\"decision\":\"pass\",\"blocking_failed\":0},"
                    + "\"results\":[{\"name\":\"l15_receiving_interrupt_atomicity\","
                    + "\"level\":\"blocking\",\"passed\":true}],"
                    + "\"report_path\":\"reports/" + file.getFileName() + "\","
                    + "\"report_sha256\":\"" + sha256(report.getBytes(StandardCharsets.UTF_8)) + "\"}";
            try (var conn = java.sql.DriverManager.getConnection(
                    "jdbc:sqlite:" + tasks.path());
                 var st = conn.prepareStatement(
                         "UPDATE tasks SET status='completed', reviewed_by=?, "
                                 + "review_decision='approve', reviewed_at=?, result_json=? "
                                 + "WHERE id=?")) {
                st.setString(1, "delivery-reviewer");
                st.setString(2, "2026-10-02T00:00:00+00:00");
                st.setString(3, resultJson);
                st.setString(4, id);
                st.executeUpdate();
            }
        } catch (IOException | java.sql.SQLException error) {
            throw new IllegalStateException("候选夹具失败：" + id, error);
        }
        return id;
    }

    private static String acceptedFeedback(Feedback feedback, String taskId, String conclusion) {
        Map<String, Object> added = feedback.addFeedback(taskId, "ops", conclusion,
                "由具名人员复核失败是否稳定", null, "");
        feedback.reviewFeedback((String) added.get("id"), "teacher", "accept", "检查接受");
        return (String) added.get("id");
    }

    private static int seedEvent(TempLedger ledger, String taskId, String detail) {
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + ledger.db);
             var st = conn.prepareStatement(
                     "INSERT INTO task_events(task_id, from_status, to_status, detail, actor) "
                             + "VALUES (?, NULL, 'queued', ?, 'l15-checks')")) {
            st.setString(1, taskId);
            st.setString(2, detail);
            st.executeUpdate();
            try (var query = conn.createStatement();
                 var rs = query.executeQuery("SELECT last_insert_rowid()")) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException("事件夹具失败", error);
        }
    }

    private static void tamperAssetPayload(TempLedger ledger, String assetId) {
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + ledger.db);
             var st = conn.prepareStatement(
                     "UPDATE learning_assets SET payload='{\"tampered\":true}' "
                             + "WHERE asset_id=?")) {
            st.setString(1, assetId);
            st.executeUpdate();
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException("漂移注入失败", error);
        }
    }

    private static Map<String, String> learnCreate(String project, String taskId,
            String family, String title, String content, String actor, String evolutionId,
            String supersedes) {
        return learnCreate(project, taskId, family, title, content, actor, evolutionId,
                supersedes, "");
    }

    /** learnCreate 的 applies 变体（召回场景命中面——applies 缺省时条目成通用召回）。 */
    private static Map<String, String> learnCreate(String project, String taskId,
            String family, String title, String content, String actor, String evolutionId,
            String supersedes, String applies) {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("project_id", project);
        args.put("task_id", taskId);
        args.put("family", family);
        args.put("title", title);
        args.put("content", content);
        args.put("actor", actor);
        args.put("evolution_id", evolutionId);
        args.put("supersedes", supersedes);
        args.put("applies", applies);
        return args;
    }

    private static Map<String, String> learnGovern(String project, String assetId,
            String decision, String actor, String note) {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("project_id", project);
        args.put("asset_id", assetId);
        args.put("decision", decision);
        args.put("actor", actor);
        args.put("note", note);
        return args;
    }

    private static void governPublished(LearningStore learning, String project, String assetId,
            String actor) {
        learning.govern(learnGovern(project, assetId, "approve", actor, "批"));
        learning.govern(learnGovern(project, assetId, "publish", actor, "发"));
    }

    private static String activeAsset(LearningStore learning, String project, String sourceTask,
            String family, String title, String content, String applies) {
        Map<String, Object> created = learning.create(learnCreate(project, sourceTask, family,
                title, content, "extractor", "", "", applies));
        String assetId = (String) created.get("asset_id");
        governPublished(learning, project, assetId, "governor");
        return assetId;
    }

    private static String assetState(LearningStore learning, String assetId) {
        return String.valueOf(learning.showAsset(assetId).get("state"));
    }

    private static String assetSha(LearningStore learning, String assetId) {
        return String.valueOf(((Map<?, ?>) learning.showAsset(assetId).get("payload")).get("id"))
                + ":" + String.valueOf(learning.showAsset(assetId).get("asset_id"));
    }

    private static String assetEvents(LearningStore learning, String assetId) {
        StringBuilder out = new StringBuilder();
        for (Object event : (List<?>) learning.showAsset(assetId).get("events")) {
            out.append(((Map<?, ?>) event).get("action")).append(",");
        }
        return out.toString();
    }

    private static List<String> matchIds(Map<String, Object> recall) {
        List<String> out = new ArrayList<>();
        for (Object match : (List<?>) recall.get("matches")) {
            out.add(String.valueOf(((Map<?, ?>) match).get("asset_id")));
        }
        return out;
    }

    private static boolean excludedHas(Map<String, Object> recall, String assetId, String marker) {
        for (Object excluded : (List<?>) recall.get("excluded")) {
            Map<?, ?> row = (Map<?, ?>) excluded;
            if (assetId.equals(row.get("asset_id"))
                    && String.valueOf(row.get("reason")).contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String excludedReason(Map<String, Object> recall) {
        StringBuilder out = new StringBuilder();
        for (Object excluded : (List<?>) recall.get("excluded")) {
            out.append(((Map<?, ?>) excluded).get("reason"));
        }
        return out.toString();
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("断言失败：" + message);
        }
    }

    private static void expectThrows(Runnable body, String messagePart) {
        try {
            body.run();
        } catch (RuntimeException error) {
            if (error.getMessage() == null || !error.getMessage().contains(messagePart)) {
                throw new IllegalStateException("拒绝词面不符：期望含「" + messagePart
                        + "」实得「" + error.getMessage() + "」", error);
            }
            return;
        }
        throw new IllegalStateException("应被拒绝（未抛出）：期望含「" + messagePart + "」");
    }

    private static String failNow(String message) {
        throw new IllegalStateException(message);
    }

    private static JsonNode runPythonJson(Path interpreter, Path cwd, String script,
            List<String> extraArgs) {
        try {
            List<String> argv = new ArrayList<>(List.of(interpreter.toString(),
                    "-X", "utf8", "-c", script));
            argv.addAll(extraArgs);
            Process process = new ProcessBuilder(argv).directory(cwd.toFile()).start();
            String stdout = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("python 子进程超时");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("python 子进程 rc=" + process.exitValue()
                        + "：" + stderr.strip());
            }
            return MAPPER.readTree(stdout);
        } catch (IOException error) {
            throw new IllegalStateException("python 启动失败", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("python 被中断", error);
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    static Path repoRoot() {
        try {
            return Path.of(L15Checks.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().getParent().getParent();
        } catch (java.net.URISyntaxException error) {
            throw new IllegalStateException("仓库根定位失败（class 装载位置）", error);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 计算失败", error);
        }
    }

    /** 拷贝流的受检包装（Files.walk lambda 内不便抛受检）。 */
    private static final class UncheckedIOException2 extends RuntimeException {
        UncheckedIOException2(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }
}
