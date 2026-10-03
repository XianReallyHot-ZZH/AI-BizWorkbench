package workbench.evals.l16;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import workbench.bootstrap.Args;
import workbench.delivery.DynamicSpecFreeze;
import workbench.delivery.TaskStore;
import workbench.environment.EnvironmentCheck;
import workbench.evals.EvalHarness;
import workbench.release.ReleaseIndex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * L16 统一检查入口（讲义 §2 C1–C8；l07–l15 Checks 族形，七件默认门全 blocking）。
 * transfer 终讲三面：发布证据索引组装与缺项、冷启动自检、现场抽取时序 + P3 缺货登记
 * 受控改进（动态起始红 + 三态证据 + 客户 eval 前后照跑）。
 *
 * <p>argv 面（l08–l15 同形）：{@code --target} 必填（客户树根，须含 flowerp/）；
 * {@code --case}（可多，选跑子集）；{@code --python}（缺省 .venv/bin/python）、
 * {@code --no-report}、{@code --report-path}。
 *
 * <pre>
 * 登记项 → 合同映射（spec Implementation Decisions 定形）：
 * l16_release_index_assembly   C3：五要素引用 + sha256/bytes + 越界拒绝词面 + 缺项类目 + requires_human_review 恒定
 * l16_cold_start_checks        C4：六面只读自检（D3 清单合同化）+ scope installation
 * l16_live_draw_sequence       C1：抽取时序机器可证（红证据 < 候选池 commit < 抽取记录）+ P3 具名在场
 * l16_dynamic_start_evidence   C6：动态起始红三断言（既有合同绿〔客户 eval〕+ 新用例真实红〔vendors 原样登记面缺席〕+ 边界拒绝在场）
 * l16_three_state_evidence     C2/C7：拷贝树改进后三态（正常登记可查 / 无效登记被拒 / 拒绝后数据不变）+ 客户 eval 照跑 + vendors 恒空
 * l16_bound_eval_reverify      C5：绑定 eval delivery_evidence_and_review_controls = L13 DeliveryChecks 照跑（rc 0 + 十件全绿）
 * l16_frozen_fingerprints      C8：指纹六件（客户锚面 + 上游两件 + 本讲两源件 + 抽取记录原件）
 * </pre>
 */
public final class L16Checks {

    /** 冻结指纹件（L15 口径：只冻本讲后不再变动的面）。相对路径：flowerp/ = --target 客户树；其余相对仓库根。 */
    private static final String[] FROZEN_FILES = {
            "flowerp/service.py",
            "vendors/CodexFDE/workbench/release_index.py",
            "vendors/CodexFDE/workbench/environment_check.py",
            "src/main/java/workbench/release/ReleaseIndex.java",
            "src/main/java/workbench/environment/EnvironmentCheck.java",
            "lesson-16-submission/16-transfer/01-draw/抽取记录-P3.md",
    };

    /** 指纹期望值（实现收口时实采冻结，2026-10-03，按 FROZEN_FILES 文件序）。 */
    private static final String[] FROZEN_SHA256 = {
            "6c372dcd105c2c476bbbb76b97abaf6edfbbac233cc7cfb7567a73052325385a",
            "8941993d473051650fa95e44f53c8b88740cdf6d4244766fe74d296cd69c7c13",
            "8c371c2e6fb53b759a9a6e2895362773d3465fbfdd1545ff4e9bd0ac649d4d41",
            "94ab50ad1d3becfdd3e726f44fd9418a13bd3ed6bcd46842c0ec57e9152f4df7",
            "16a057d98edb962d8a73297e4e48cc0d18e2477c49dd217d5eeb6d199ffbecd6",
            "be473e603ec03b121a64cf3d0777e63dc8ae8c99b1eba834db874f48ce717646",
    };

    private static final String DEFAULT_PYTHON = ".venv/bin/python";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private L16Checks() {}

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
            System.out.println(workbench.bootstrap.PyJson.dumps(outcome.report()));
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

    /** 七件默认门（C 表映射见讲义 §2 与 spec——全部 blocking）。 */
    static List<EvalHarness.Entry> entries(Path target, Path interpreter) {
        return List.of(
                new EvalHarness.Entry("l16_release_index_assembly", "blocking",
                        L16Checks::releaseIndexAssembly),
                new EvalHarness.Entry("l16_cold_start_checks", "blocking",
                        L16Checks::coldStartChecks),
                new EvalHarness.Entry("l16_live_draw_sequence", "blocking",
                        L16Checks::liveDrawSequence),
                new EvalHarness.Entry("l16_dynamic_start_evidence", "blocking",
                        () -> dynamicStartEvidence(target, interpreter)),
                new EvalHarness.Entry("l16_three_state_evidence", "blocking",
                        () -> threeStateEvidence(target, interpreter)),
                new EvalHarness.Entry("l16_bound_eval_reverify", "blocking",
                        () -> boundEvalReverify(target)),
                new EvalHarness.Entry("l16_frozen_fingerprints", "blocking",
                        () -> frozenChecks(target)));
    }

    // ---- 场景 1：发布索引组装面（C3——合同测试同面，门级复验）--------------------------------

    private static String releaseIndexAssembly() {
        try {
            Path runtime = Files.createTempDirectory("l16-release-index-");
            TaskStore store = new TaskStore(runtime.resolve("workbench.db"));
            String taskId = "TASK-L16GATE001";
            Path spec = runtime.resolve("spec.md");
            Files.writeString(spec, "# P3 缺货登记 Spec（门级夹具）\n", StandardCharsets.UTF_8);
            Map<String, Object> summary = Map.of("decision", "pass", "blocking_failed", 0);
            Map<String, Object> report = Map.of("schema_version", "1.0", "summary", summary,
                    "results", List.of(Map.of("name", "l16_backorder_after_insufficient_stock",
                            "level", "blocking", "passed", true)));
            Path postReport = runtime.resolve("post-report.json");
            Files.writeString(postReport, MAPPER.writeValueAsString(report), StandardCharsets.UTF_8);
            store.create("P3 缺货登记现场交付", "REQUIREMENT:LIVE-DRAW",
                    List.of("REQUIREMENT:LIVE-DRAW"), spec.toString(), "l16-gate", "manual",
                    taskId, "verify", List.of(), 300, "sub-" + taskId);
            store.appendEvent(taskId, "本次需求 Spec 已冻结", REVIEWER,
                    Map.of("sha256", sha256Bytes(Files.readAllBytes(spec))));
            store.appendEvent(taskId, "受控执行阶段完成", "Claude",
                    Map.of("changed_files", List.of("flowerp/service.py"),
                            "diff", "--- a/flowerp/service.py\n+++ b/flowerp/service.py",
                            "out_of_scope_files", List.of()));
            store.appendEvent(taskId, "课程红绿差分判定已完成", REVIEWER, Map.of("accepted", true));
            Map<String, Object> result = Map.of(
                    "runner", Map.of("report_path", postReport.toString()), "summary", summary);
            setTaskResult(runtime.resolve("workbench.db"), taskId, result);
            setTaskReview(runtime.resolve("workbench.db"), taskId, REVIEWER, "approve");

            Map<String, Object> outcome = ReleaseIndex.create(runtime, taskId, null, null, null);
            List<String> gaps = (List<String>) outcome.get("evidence_gaps");
            expect("requires_human_review".equals(outcome.get("index_status")),
                    "index_status 应恒 requires_human_review（发布索引不代行人审）");
            // 门级反面拍：spec/pre_eval/post_eval 引用缺席即列缺，不报错；剩余 risks 旗标未给 → gap
            expect(gaps.stream().anyMatch(gap -> gap.startsWith("pre_eval"))
                            && gaps.stream().anyMatch(gap -> gap.startsWith("post_eval"))
                            && gaps.contains("remaining_risks"),
                    "缺席证据应如实列缺：" + gaps);
            // 越界拒绝词面（上游 :24-25 直承）
            boolean rejected = false;
            try {
                ReleaseIndex.create(runtime, taskId, "/tmp/outside-l16.md", null, null);
            } catch (IllegalArgumentException error) {
                rejected = error.getMessage().contains("必须是本运行目录内的证据文件");
            }
            expect(rejected, "运行目录外证据应拒绝（词面直承）");
            return "发布索引：缺席如实列缺 + 越界拒绝 + requires_human_review 恒定（不代行人审）";
        } catch (IOException error) {
            throw new IllegalStateException("发布索引场景失败：" + error.getMessage(), error);
        }
    }

    // ---- 场景 2：冷启动自检（C4）--------------------------------------------------------------

    private static String coldStartChecks() {
        Map<String, Object> report = EnvironmentCheck.checkEnvironment();
        expect(Boolean.TRUE.equals(report.get("ok")), "净树安装自检六面应全绿");
        expect("installation".equals(report.get("scope")), "自检 scope 应为 installation");
        expect(Boolean.FALSE.equals(report.get("product_checked")),
                "默认不查客户环境（客户面由冷启动实验承载）");
        List<?> checks = (List<?>) report.get("checks");
        Set<String> expected = Set.of("java", "compiled_classes", "dependency_classpath",
                "golden_resources", "web_panel_assets", "wb_wrapper");
        for (Object item : checks) {
            Map<?, ?> check = (Map<?, ?>) item;
            expect(check.get("name") != null && check.get("ok") instanceof Boolean
                    && check.get("detail") != null, "检查项形状 {name, ok, detail}：" + check);
        }
        Set<String> names = new java.util.HashSet<>();
        checks.forEach(item -> names.add(String.valueOf(((Map<?, ?>) item).get("name"))));
        expect(names.containsAll(expected), "六面检查名应在场：" + names);
        return "冷启动自检：六面只读在场（java/编译产物/依赖清单/golden/面板三件/wb 包装），只查不修";
    }

    // ---- 场景 3：现场抽取时序（C1——仓库原件机器可证）----------------------------------------

    private static String liveDrawSequence() {
        Path root = repoRoot();
        // ①起始红证据：record 132（returncode 1，observed_at）
        Path redMeta = redEvidenceMeta(root);
        JsonNode meta;
        try {
            meta = MAPPER.readTree(Files.readAllBytes(redMeta));
        } catch (IOException error) {
            throw new IllegalStateException("起始红 meta 不可读：" + redMeta, error);
        }
        expect("red".equals(meta.path("phase").asText()) && meta.path("returncode").asInt() == 1,
                "起始红证据应为 phase=red 且 rc=1");
        Instant redAt = Instant.parse(meta.path("observed_at").asText());
        // ②候选池盘点件（commit 时间）
        Path pool = root.resolve("lesson-16-submission/16-transfer/00-pool/候选池盘点.md");
        expect(Files.isRegularFile(pool), "候选池盘点件应在场：" + pool);
        Instant poolAt = commitTime(root, pool);
        // ③抽取记录（具名 + 时间）
        Path draw = root.resolve("lesson-16-submission/16-transfer/01-draw/抽取记录-P3.md");
        expect(Files.isRegularFile(draw), "抽取记录应在场：" + draw);
        String drawText;
        try {
            drawText = Files.readString(draw, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("抽取记录不可读", error);
        }
        expect(drawText.contains("「抽 P3」"), "抽取原话应逐字在场");
        Instant drawAt = Instant.parse(extractTimestamp(drawText, "抽取时间"));
        // 时序链：红证据 < 候选池 commit < 抽取（讲义 D2 ①——抽取事件晚于红证据与盘点件）
        expect(redAt.isBefore(poolAt), "时序：起始红（" + redAt + "）应早于候选池 commit（" + poolAt + "）");
        expect(poolAt.isBefore(drawAt), "时序：候选池 commit（" + poolAt + "）应早于抽取（" + drawAt + "）");
        expect(drawText.contains("InsufficientStock"), "抽取记录应含前置事实（拒绝面在场）");
        return "现场抽取时序机器可证：红证据 < 候选池 < 抽取（P3 具名原话在场，晚于实现前一切面）";
    }

    // ---- 场景 4：动态起始红三断言（C6——vendors 原样树）-------------------------------------

    private static String dynamicStartEvidence(Path target, Path interpreter) {
        Path copy = copyCustomerTree(target);
        // 既有合同绿：客户 eval 照跑（vendors 原样）
        int evalBefore = runCustomerEval(interpreter, copy);
        expect(evalBefore == 0, "既有合同（客户 eval）在原样树上应绿");
        // 新用例真实红：登记面在基线缺席（capability absent = 起始红形式化）
        JsonNode probe = runBackorderProbe(interpreter, copy);
        expect("absent".equals(probe.path("capability").asText()),
                "新增用例应在未实现树上真实失败（register_backorder 缺席）");
        expect(probe.path("reserve_rejected").asBoolean(false),
                "边界拒绝（InsufficientStock）应在场照旧——边界 1 不因新功能松动");
        // 复查轮 T-2（spec 故事 30/C6）：DynamicSpecFreeze 三机制真链路——L15 建成的
        // dynamic_eval_required 机制在本讲真用（冻结关联 / 重名拒绝 / startEvidence 三态）
        Map<String, Object> frozen = DynamicSpecFreeze.freeze(16, P3_SPEC_TEXT,
                List.of(DYNAMIC_CASE), List.of("flowerp/", "workbench/", "eval/", "web/", "tests/"),
                List.of("需求在答辩现场抽取且仓库基线中尚未实现。",
                        "正常、失败和失败后不变状态均有新证据。",
                        "发布索引能追溯需求、Diff、Eval、人审与剩余风险。"), true);
        expect(String.valueOf(frozen.get("sha256")).length() == 64, "冻结应产出 sha256 指纹");
        expect(String.valueOf(frozen.get("acceptance")).contains(DYNAMIC_CASE),
                "冻结产物验收段应词边界关联动态用例");
        // 重名拒绝：与静态合同 Eval 重名即拒（负面拍），不重名即过（正面拍）
        DynamicSpecFreeze.dynamicNameCheck(List.of(DYNAMIC_CASE),
                List.of("delivery_evidence_and_review_controls"));
        boolean duplicateRejected = false;
        try {
            DynamicSpecFreeze.dynamicNameCheck(
                    List.of("delivery_evidence_and_review_controls"),
                    List.of("delivery_evidence_and_review_controls"));
        } catch (IllegalArgumentException error) {
            duplicateRejected = error.getMessage().contains("不能重复静态合同 Eval");
        }
        expect(duplicateRejected, "动态用例与静态合同重名应拒绝（词面直承）");
        // startEvidence 三态：起始红报告（新用例失败 + 静态全过）→ accepted；全绿报告 → 拒
        Map<String, Object> redReport = Map.of("results",
                List.of(Map.of("name", DYNAMIC_CASE, "passed", false)));
        Map<String, Object> startEvidence = DynamicSpecFreeze.startEvidence(redReport,
                List.of("delivery_evidence_and_review_controls"), List.of(DYNAMIC_CASE));
        expect(Boolean.TRUE.equals(startEvidence.get("accepted")),
                "起始红报告应被接受（既有合同绿 + 新用例真实红）");
        Map<String, Object> greenReport = Map.of("results",
                List.of(Map.of("name", DYNAMIC_CASE, "passed", true)));
        Map<String, Object> greenEvidence = DynamicSpecFreeze.startEvidence(greenReport,
                List.of("delivery_evidence_and_review_controls"), List.of(DYNAMIC_CASE));
        expect(!Boolean.TRUE.equals(greenEvidence.get("accepted")),
                "全绿报告不构成起始证据（新用例未真实失败）");
        return "动态起始红：既有合同绿 + 新用例真实红（登记面缺席）+ 拒绝照旧"
                + "；DynamicSpecFreeze 真链路（冻结 sha + 词边界关联 + 重名拒绝 + startEvidence 三态）";
    }

    /** 现场需求六段 Spec（DynamicSpecFreeze 冻结关联机检的输入——六段结构与词边界关联的真文本）。 */
    private static final String DYNAMIC_CASE = "l16_backorder_after_insufficient_stock";

    private static final String P3_SPEC_TEXT = """
            ## 来源

            答辩现场抽取（REQUIREMENT:LIVE-DRAW，用户具名「抽 P3」）：创建单因缺货被拒后，未满足需求无处登记、口头流失。

            ## 目标

            缺货被拒后登记未满足需求（商品/数量/日期/备注/来源单引用），供补货参考——不建负库存，拒绝照旧。

            ## 非目标

            不改拒绝链；不做登记与采购补货的自动联动；不建负库存或预售占位。

            ## 约束

            可用库存不能为负；拒绝照旧（InsufficientStock 原语义）；登记是拒绝的后件留痕；永不写回 vendors。

            ## 验收用例

            新增动态用例 l16_backorder_after_insufficient_stock 先真实失败再转绿：正常态拒绝后登记可查；失败态数量非正、未知商品与重复登记被拒；失败后登记面与库存面数据不变；按商品与日期只读查询；客户 eval 前后照跑双绿。

            ## 完成定义

            正常、失败和失败后不变状态均有新证据；发布索引能追溯需求、Diff、Eval、人审与剩余风险。
            """;

    // ---- 场景 5：三态证据（C2/C7——拷贝树改进后）---------------------------------------------

    private static String threeStateEvidence(Path target, Path interpreter) {
        Path copy = copyCustomerTree(target);
        applyP3Patch(copy);
        JsonNode probe = runBackorderProbe(interpreter, copy);
        expect("present".equals(probe.path("capability").asText()), "改进后登记面应在场");
        expect(probe.path("reserve_rejected").asBoolean(false),
                "正常态前置：缺货拒绝照旧（拒绝先于登记）");
        expect(probe.path("normal_ok").asBoolean(false), "正常态：拒绝后登记可查（字段齐）");
        expect(probe.path("date_filter_ok").asBoolean(false), "查询面：按商品与日期过滤生效");
        expect(probe.path("failed_qty").asBoolean(false) && probe.path("failed_sku").asBoolean(false)
                        && probe.path("failed_dup").asBoolean(false),
                "失败态：数量非正、未知商品与重复登记均被拒");
        expect(probe.path("unchanged_after_failure").asBoolean(false),
                "失败后不变态：拒绝后登记面与库存面数据不变");
        int evalAfter = runCustomerEval(interpreter, copy);
        expect(evalAfter == 0, "客户 eval 改进后照跑应绿（不破坏客户合同）");
        expectSubmoduleClean(target, "vendors/flowERP");
        expectSubmoduleClean(repoRoot().resolve("vendors/CodexFDE"), "vendors/CodexFDE");
        return "P3 三态齐备：正常登记可查 / 无效登记被拒 / 拒绝后数据不变；客户 eval 前后照跑绿 + vendors 恒空";
    }

    // ---- 场景 6：绑定 eval 复验（C5——L13 门真实照跑）----------------------------------------

    private static String boundEvalReverify(Path target) {
        try {
            // 子进程 classpath 内联（main 源件不引 test 支撑——Cli 同形两行）
            String classpath = repoRoot().resolve("target/classes") + ":"
                    + Files.readString(repoRoot().resolve("target/child-classpath.txt")).strip();
            List<String> argv = new ArrayList<>(List.of("java", "-cp", classpath,
                    "workbench.evals.l13.DeliveryChecks", "--target", target.toString(),
                    "--no-report"));
            Process process = new ProcessBuilder(argv).directory(repoRoot().toFile()).start();
            String stdout = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (!process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("L13 门照跑超时");
            }
            expect(process.exitValue() == 0, "L13 DeliveryChecks 照跑应 rc 0："
                    + stderr.strip() + stdout.strip());
            JsonNode report = MAPPER.readTree(stdout);
            expect("pass".equals(report.path("summary").path("decision").asText()),
                    "L13 门照跑 summary 应 pass");
            expect(report.path("summary").path("total").asInt(-1) == 10,
                    "L13 门照跑应为十件（delivery_evidence_and_review_controls 复验面）");
            return "绑定 eval 复验：L13 DeliveryChecks 真实照跑十件全绿（断言面属主不变）";
        } catch (IOException error) {
            throw new IllegalStateException("L13 门照跑启动失败", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("L13 门照跑被中断", error);
        }
    }

    // ---- 场景 7：冻结指纹（C8）---------------------------------------------------------------

    private static String frozenChecks(Path target) {
        for (int i = 0; i < FROZEN_FILES.length; i++) {
            Path file = FROZEN_FILES[i].startsWith("flowerp/")
                    ? target.resolve(FROZEN_FILES[i]) : repoRoot().resolve(FROZEN_FILES[i]);
            String actual;
            try {
                actual = sha256Bytes(Files.readAllBytes(file));
            } catch (IOException error) {
                throw new IllegalStateException("指纹件不可读（漂移或缺席）：" + file, error);
            }
            if (!FROZEN_SHA256[i].equals(actual)) {
                throw new IllegalStateException("指纹漂移：" + FROZEN_FILES[i]
                        + " 期望 " + FROZEN_SHA256[i] + " 实算 " + actual);
            }
        }
        return "冻结指纹六件一致（客户锚面 + 上游两件 + 本讲两源件 + 抽取记录原件）";
    }

    // ---- P3 改进 patch（拷贝树 service.py 尾锚插入，只写拷贝树永不写回 vendors）----------------

    /** 插入锚：service.py 末行（receive_purchase 最终 return——全文件唯一）。 */
    private static final String P3_ANCHOR =
            "        return {\"purchase\": self.purchase(request_id), \"stock\": result}";

    private static final String P3_INSERTION = """

            def register_backorder(self, sku: str, quantity: int, note: str = "", reference: str = "") -> dict:
                sku = (sku or "").strip().upper()
                if quantity <= 0:
                    raise ValueError("登记数量必须为正")
                with self.store.connect() as conn:
                    if not conn.execute("SELECT 1 FROM products WHERE sku=?", (sku,)).fetchone():
                        raise NotFound(f"商品不存在：{sku}")
                    conn.execute(
                        "CREATE TABLE IF NOT EXISTS backorder_requests("
                        "id TEXT PRIMARY KEY, sku TEXT NOT NULL, quantity INTEGER NOT NULL, "
                        "note TEXT NOT NULL DEFAULT '', reference TEXT NOT NULL DEFAULT '', "
                        "created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)"
                    )
                    ref_key = reference.strip()
                    if ref_key:
                        dup = conn.execute(
                            "SELECT id FROM backorder_requests WHERE sku=? AND reference=?",
                            (sku, ref_key),
                        ).fetchone()
                        if dup:
                            raise ValueError(f"该订单已登记缺货：{dup['id']}")
                    bid = f"BO-{uuid.uuid4().hex[:8].upper()}"
                    conn.execute(
                        "INSERT INTO backorder_requests(id,sku,quantity,note,reference) VALUES(?,?,?,?,?)",
                        (bid, sku, quantity, note.strip(), reference.strip()),
                    )
                    row = conn.execute(
                        "SELECT * FROM backorder_requests WHERE id=?", (bid,)
                    ).fetchone()
                return dict(row)

            def list_backorders(self, sku: str = "", date_from: str = "", date_to: str = "") -> list[dict]:
                key = (sku or "").strip().upper()
                conditions, params = [], []
                if key:
                    conditions.append("sku=?")
                    params.append(key)
                if date_from.strip():
                    conditions.append("created_at>=?")
                    params.append(date_from.strip() + " 00:00:00")
                if date_to.strip():
                    conditions.append("created_at<=?")
                    params.append(date_to.strip() + " 23:59:59")
                where = (" WHERE " + " AND ".join(conditions)) if conditions else ""
                with self.store.connect() as conn:
                    if not conn.execute(
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='backorder_requests'"
                    ).fetchone():
                        return []
                    rows = conn.execute(
                        "SELECT * FROM backorder_requests" + where + " ORDER BY created_at DESC, rowid DESC",
                        tuple(params),
                    ).fetchall()
                return [dict(row) for row in rows]
        """;

    static void applyP3Patch(Path copy) {
        try {
            Path service = copy.resolve("flowerp/service.py");
            String text = Files.readString(service, StandardCharsets.UTF_8);
            int anchor = text.indexOf(P3_ANCHOR);
            if (anchor < 0 || text.indexOf(P3_ANCHOR, anchor + 1) >= 0) {
                throw new IllegalStateException("P3 插入锚不唯一或缺席（客户树漂移？）");
            }
            Files.writeString(service, text.replace(P3_ANCHOR, P3_ANCHOR + P3_INSERTION),
                    StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("P3 patch 应用失败", error);
        }
    }

    // ---- P3 探针（子进程 python 驱动拷贝树客户实现；断言与预期留 Java）------------------------

    private static final String BACKORDER_PROBE = """
            import json, sys, tempfile
            from pathlib import Path
            sys.path.insert(0, sys.argv[1])
            from flowerp.service import ERPService
            from flowerp.store import ERPStore
            from flowerp.models import OrderLine, InsufficientStock, NotFound

            def out(obj):
                print(json.dumps(obj, ensure_ascii=False))

            with tempfile.TemporaryDirectory() as tmp:
                store = ERPStore(Path(tmp) / "flowerp.db")
                service = ERPService(store)
                service.add_product("SKU-L16-BO", "缺货登记探针产品", 100)
                service.receive_stock("SKU-L16-BO", 10, "opening")
                order = service.create_order("现场客户", [OrderLine("SKU-L16-BO", 50, 100)])
                reserve_rejected = False
                try:
                    service.reserve_order(order["id"])
                except InsufficientStock:
                    reserve_rejected = True
                if not hasattr(service, "register_backorder"):
                    out({"capability": "absent", "reserve_rejected": reserve_rejected})
                else:
                    bo = service.register_backorder("SKU-L16-BO", 50, "客户催单", order["id"])
                    listed = service.list_backorders("SKU-L16-BO")
                    normal_ok = (
                        len(listed) == 1
                        and bo["sku"] == "SKU-L16-BO"
                        and bo["quantity"] == 50
                        and bo["reference"] == order["id"]
                        and listed[0]["id"] == bo["id"]
                    )
                    from datetime import date as _date
                    today = _date.today().isoformat()
                    date_filter_ok = (
                        len(service.list_backorders("SKU-L16-BO", date_from=today, date_to=today)) == 1
                        and len(service.list_backorders("SKU-L16-BO", date_from="2999-01-01")) == 0
                    )

                    def snap():
                        backorders = service.list_backorders()
                        with store.connect() as conn:
                            stock = [dict(r) for r in conn.execute(
                                "SELECT * FROM stock ORDER BY sku").fetchall()]
                            events = [dict(r) for r in conn.execute(
                                "SELECT * FROM inventory_events ORDER BY rowid").fetchall()]
                        return {"backorders": backorders, "stock": stock, "events": events}

                    before = snap()
                    failed_qty = False
                    try:
                        service.register_backorder("SKU-L16-BO", 0, "零数量")
                    except ValueError:
                        failed_qty = True
                    failed_sku = False
                    try:
                        service.register_backorder("SKU-MISSING-9", 5, "未知商品")
                    except NotFound:
                        failed_sku = True
                    failed_dup = False
                    try:
                        service.register_backorder("SKU-L16-BO", 50, "重复催单", order["id"])
                    except ValueError:
                        failed_dup = True
                    after = snap()
                    out({
                        "capability": "present",
                        "reserve_rejected": reserve_rejected,
                        "normal_ok": normal_ok,
                        "date_filter_ok": date_filter_ok,
                        "failed_qty": failed_qty,
                        "failed_sku": failed_sku,
                        "failed_dup": failed_dup,
                        "unchanged_after_failure": before == after,
                    })
            """;

    static JsonNode runBackorderProbe(Path interpreter, Path copy) {
        return runPythonJson(interpreter, copy, BACKORDER_PROBE, List.of(copy.toString()));
    }

    // ---- 辅助（l15 Checks 族同形）-------------------------------------------------------------

    private static final String REVIEWER = "XianReallyHot-ZZH";

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("L16 检查失败：" + message);
        }
    }

    private static Path repoRoot() {
        return Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
    }

    private static Path redEvidenceMeta(Path root) {
        try (Stream<Path> paths = Files.list(root.resolve("lesson-16-submission/03-failure"))) {
            List<Path> metas = paths.filter(Files::isDirectory)
                    .map(dir -> dir.resolve("meta.json"))
                    .filter(Files::isRegularFile)
                    .sorted()  // 目录名 = UTC 时间戳前缀——按名排序取最大即最新（Files.list 序未定义）
                    .toList();
            expect(metas.size() >= 1, "起始红 capture 目录应在场");
            return metas.get(metas.size() - 1);
        } catch (IOException error) {
            throw new IllegalStateException("起始红目录不可读", error);
        }
    }

    private static Instant commitTime(Path root, Path file) {
        try {
            Process process = new ProcessBuilder(List.of("git", "log", "-1",
                    "--format=%cI", "--", root.relativize(file).toString()))
                    .directory(root.toFile()).start();
            String stdout = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).strip();
            process.waitFor();
            expect(!stdout.isEmpty(), "候选池盘点件应有 commit 记录（git log 空）");
            return OffsetDateTime.parse(stdout, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (IOException error) {
            throw new IllegalStateException("git log 启动失败", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git log 被中断", error);
        }
    }

    private static String extractTimestamp(String text, String label) {
        Pattern pattern = Pattern.compile(label + "[^\\d]*(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z)");
        Matcher matcher = pattern.matcher(text);
        expect(matcher.find(), "抽取记录应含 " + label + " 时间戳");
        return matcher.group(1);
    }

    private static void setTaskResult(Path db, String taskId, Map<String, Object> result) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE tasks SET result_json=? WHERE id=?")) {
            ps.setString(1, MAPPER.writeValueAsString(result));
            ps.setString(2, taskId);
            ps.executeUpdate();
        } catch (Exception error) {
            throw new IllegalStateException("夹具 result 直插失败", error);
        }
    }

    private static void setTaskReview(Path db, String taskId, String reviewer, String decision) {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE tasks SET status='completed', reviewed_by=?, review_decision=? WHERE id=?")) {
            ps.setString(1, reviewer);
            ps.setString(2, decision);
            ps.setString(3, taskId);
            ps.executeUpdate();
        } catch (Exception error) {
            throw new IllegalStateException("夹具 review 直插失败", error);
        }
    }

    private static void expectSubmoduleClean(Path repo, String name) {
        try {
            Process git = new ProcessBuilder("git", "-C", repo.toString(),
                    "status", "--short").start();
            String dirty = new String(git.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).strip();
            git.waitFor();
            expect(dirty.isEmpty(), name + " 应保持只读恒空：" + dirty);
        } catch (IOException error) {
            throw new IllegalStateException("git status 启动失败", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git status 被中断", error);
        }
    }

    private static Path copyCustomerTree(Path target) {
        try {
            Path copy = Files.createTempDirectory("l16-erp-backorder-");
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
                                throw new UncheckedIOException(error);
                            }
                        });
            }
            return copy;
        } catch (IOException error) {
            throw new IllegalStateException("客户树拷贝失败", error);
        }
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

    private static String sha256Bytes(byte[] content) {
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
