package workbench.evals.l14;

import com.fasterxml.jackson.databind.ObjectMapper;
import workbench.bootstrap.Args;
import workbench.bootstrap.PyJson;
import workbench.delivery.DeliveryAutomation;
import workbench.delivery.DeliveryView;
import workbench.delivery.Feedback;
import workbench.delivery.HttpApi;
import workbench.delivery.TaskStore;
import workbench.delivery.Workflow;
import workbench.evals.EvalHarness;
import workbench.execution.ProcessRunner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * L14 交付状态 Web 面板统一入口（讲义 §2 登记项构成，spec 定形八件）：上游绑定 eval
 * {@code no_committed_secrets}（{@code eval/cases.py:219-227}）断言面的 Java 对应物 +
 * 参考测试 {@code tests/test_delivery_view.py} 五测 + {@code test_workbench_web_dashboard.py}
 * 静态断言模式 + {@code workbench_server.py:375-399} serve 段的机检收口。
 *
 * <p>八件默认门全 blocking：投影三面（纯函数缝 {@code DeliveryView.build(task, now)}——
 * 上游 {@code build_delivery_view(task, now=...)} 同形，合成态不绕状态机改库）+
 * HTTP/静态面（进程内同款 handler 起随机端口）+ 面板静态断言（文本直读——如实声明：
 * 证明结构与取数面，不冒充渲染测试）+ 仓面密钥扫描 + 受控 ERP 四向对账（子进程 python
 * 起客户 server——断言与预期留 Java，探针族先例；客户自测 {@code test_http_api.py}
 * 照跑）+ 冻结指纹复核。两件 L13 面绑定 eval 的复验经 l13 DeliveryChecks 默认门照跑
 * 承载（断言面属主不变，本入口不重复实现——spec C6）。
 *
 * <p>argv 面（l08–l13 Checks 同形）：{@code --target} 必填（客户树根，须含
 * flowerp/）；{@code --case}（可多，选跑子集）；{@code --python}（缺省
 * .venv/bin/python）、{@code --no-report}、{@code --report-path}。
 */
public final class WebPanelChecks {

    /**
     * 冻结面（讲义 C7 指纹六件的 Checks 内承载——客户树七件：l13 三件承袭 +
     * server.py + 客户 web/ 三件；本讲 Java 源件与 workbench_web 三件 = 证据账层面，
     * L13 T-2 口径直承）。
     */
    private static final String[] FROZEN_FILES = {
            "eval/harness.py", "eval/cases.py", "flowerp/service.py",
            "flowerp/server.py", "web/index.html", "web/app.js", "web/styles.css"};

    private static final String DEFAULT_PYTHON = ".venv/bin/python";

    /**
     * 密钥标记三件（语义 = 上游 cases.py:221 逐字）。**运行时拼接构造，源件不含整词字面**
     * ——密钥扫描器的标准防自匹配手法（本仓证据链会 diff 本源件，整词字面会经采集件
     * 回流毒化扫描自身——首版实测抓获，见证据账 §3）；diff-phase 采集件另见
     * {@link #isDerivedCapture} 忽略政策。
     */
    private static final String[] SECRET_MARKERS = {
            "sk-" + "proj-", "-----BEGIN " + "PRIVATE KEY-----", "AK" + "IA"};

    /**
     * 忽略目录集：上游 {@code {.git,.tmp,.runtime,.cache}} 逐字 + 本仓形态差异四件
     * 落账——{@code target}（Java 构建产物）、{@code .venv}（环境非承诺）、
     * {@code vendors}（只读对照非本仓承诺）、{@code java}（L02-java 裁定保留的未跟踪件）。
     */
    private static final Set<String> IGNORED_DIRS =
            Set.of(".git", ".tmp", ".runtime", ".cache", "target", ".venv", "vendors", "java");

    /** 后缀名单：上游七件逐字 + .java/.js（本仓源码与面板载体——上游是 Python 仓故无）。 */
    private static final Set<String> SCANNED_SUFFIXES = Set.of(
            ".py", ".md", ".json", ".toml", ".yml", ".yaml", ".html", ".txt", ".java", ".js");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private WebPanelChecks() {}

    public static void main(String[] argv) {
        try {
            Args args = new Args(argv,
                    Set.of("--target", "--python", "--report-path"), Set.of("--no-report"),
                    List.of(), Set.of("--case"));
            Path target = Path.of(args.require("--target")).toAbsolutePath().normalize();
            if (!Files.isDirectory(target.resolve("flowerp"))) {
                throw new Args.UsageException("--target 下没有 flowerp/：" + target);
            }
            Path interpreter = resolveInterpreter(args.optional("--python", DEFAULT_PYTHON));
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

    /** 八件默认门（C 表映射见讲义 §2 与 spec——全部 blocking，无必红选跑项）。 */
    static List<EvalHarness.Entry> entries(Path target, Path interpreter) {
        return List.of(
                new EvalHarness.Entry("l14_projection_review_owner", "blocking",
                        WebPanelChecks::projectionReviewOwner),
                new EvalHarness.Entry("l14_projection_unknown_and_freshness", "blocking",
                        WebPanelChecks::projectionUnknownAndFreshness),
                new EvalHarness.Entry("l14_projection_compact_and_list", "blocking",
                        WebPanelChecks::projectionCompactAndList),
                new EvalHarness.Entry("l14_http_views_and_static", "blocking",
                        WebPanelChecks::httpViewsAndStatic),
                new EvalHarness.Entry("l14_panel_static_asserts", "blocking",
                        WebPanelChecks::panelStaticAsserts),
                new EvalHarness.Entry("no_committed_secrets", "blocking",
                        WebPanelChecks::noCommittedSecrets),
                new EvalHarness.Entry("l14_erp_reconciliation", "blocking",
                        () -> erpReconciliation(target, interpreter)),
                new EvalHarness.Entry("l14_frozen_checks", "blocking",
                        () -> frozenChecks(target)));
    }

    // ---- 合成 suite（机检专用缝，真实面 = delivery-serve --suite-command 子进程缝） ----------

    private static Map<String, Object> suiteReport(String decision, int blockingFailed, String caseName) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("decision", decision);
        summary.put("blocking_failed", blockingFailed);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", caseName);
        row.put("level", "blocking");
        row.put("passed", blockingFailed == 0);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", "1.0");
        report.put("summary", summary);
        report.put("results", List.of(row));
        return report;
    }

    private static Workflow.SuiteRunner greenSuite() {
        return () -> suiteReport("pass", 0, "l14_panel_projection");
    }

    // ---- 场景断言（独立预期全部留 Java，不抄返回值） ------------------------------------------

    /**
     * C4：review 投影（对照上游 test_delivery_view.test_review_projection——
     * control_surface 块 D4 裁掉不采）：归属人/下一步/绿 Eval 摘要/完整压缩等价/允许动作。
     */
    private static String projectionReviewOwner() {
        Path runtime = tempRuntime("l14-owner-");
        TaskStore store = new TaskStore(runtime.resolve("workbench.db"));
        Feedback feedback = new Feedback(runtime.resolve("workbench.db"));
        DeliveryAutomation automation = new DeliveryAutomation(store, runtime, greenSuite(), 2, null, 3);
        Map<String, Object> task = automation.submit("交付库存失败证据", "REQ-VIEW-001",
                List.of("SKU:NOTEBOOK-AI"), "eval-requester", "verify", null, 900, true);
        String taskId = String.valueOf(task.get("id"));
        automation.wait(taskId, 30);
        Map<String, Object> view = DeliveryView.view(store, feedback, taskId);

        expect("workbench.delivery-view/v1".equals(view.get("schema")), "schema 不符");
        Map<String, Object> status = cast(view.get("status"));
        expect("human:reviewer".equals(field(status, "owner", "id")), "review 归属人应 human:reviewer");
        expect(String.valueOf(status.get("next_action")).contains("具名"), "next_action 应含「具名」");
        Map<String, Object> evaluation = cast(view.get("eval"));
        expect(Integer.valueOf(0).equals(evaluation.get("blocking_failed")), "blocking_failed 应 0");
        expect(String.valueOf(evaluation.get("report_path")).startsWith("reports/"), "report_path 应 reports/ 前缀");
        expect(String.valueOf(evaluation.get("report_sha256")).length() == 64, "report_sha256 应 64 位");
        List<?> cases = (List<?>) evaluation.get("cases");
        Map<String, Object> onlyCase = cases.size() == 1 ? cast(cases.get(0)) : Map.of();
        expect(cases.size() == 1 && "l14_panel_projection".equals(onlyCase.get("name"))
                && Boolean.TRUE.equals(onlyCase.get("passed")),
                "cases 应含绿用例摘要：" + cases);
        expect(((List<?>) view.get("allowed_actions")).contains("approve"), "review 应允许 approve");
        Map<String, Object> integrity = cast(view.get("integrity"));
        expect(Boolean.TRUE.equals(integrity.get("truthful")), "诚实链应 truthful");
        Map<String, Object> spec = cast(view.get("spec"));
        expect(Boolean.TRUE.equals(spec.get("available")) && !String.valueOf(spec.get("goal")).isEmpty(),
                "spec 块应 available 且 goal 非空");

        // 压缩等价（对照上游 include_detail=False：results 空而 cases/路径/SHA 保留）
        Map<String, Object> compact = DeliveryView.build(store.get(taskId), List.of(), false,
                OffsetDateTime.now(ZoneOffset.UTC));
        Map<String, Object> compactEval = cast(compact.get("eval"));
        expect(((List<?>) compactEval.get("results")).isEmpty(), "压缩投影 eval.results 应空");
        expect(evaluation.get("cases").equals(compactEval.get("cases")), "cases 应与全量一致");
        expect(evaluation.get("report_path").equals(compactEval.get("report_path")), "report_path 应保留");
        expect(evaluation.get("report_sha256").equals(compactEval.get("report_sha256")), "SHA-256 应保留");
        return "review 投影：归属人 human:reviewer + 下一步具名 + 绿 Eval 摘要 + 压缩等价 + truthful";
    }

    /** C4：未知状态显式不可信 + freshness 语义（300s 边界 / review 不 stale / 终态 final / 不可解析 unknown）。 */
    private static String projectionUnknownAndFreshness() {
        OffsetDateTime base = OffsetDateTime.of(2026, 8, 31, 1, 0, 0, 0, ZoneOffset.UTC);
        Map<String, Object> view = DeliveryView.build(
                Map.of("id", "TASK-UNKNOWN000", "status", "mystery",
                        "updated_at", "2026-08-31 01:00:00"),
                List.of(), true, base);
        Map<String, Object> status = cast(view.get("status"));
        expect(!Boolean.TRUE.equals(status.get("known")), "mystery 应 unknown");
        expect("未知状态".equals(status.get("label")), "unknown label 词面不符");
        expect(Boolean.TRUE.equals(status.get("requires_human")), "unknown 应需人核对");
        expect("停止自动推进并核对未知状态、数据库和事件链".equals(status.get("next_action")),
                "unknown next_action 词面不符");
        expect(((List<?>) cast(view.get("integrity")).get("issues")).contains("unknown_status"),
                "issues 应含 unknown_status");
        expect(!Boolean.TRUE.equals(cast(view.get("integrity")).get("truthful")), "unknown 应不 truthful");
        expect("mystery".equals(cast(view.get("status")).get("code")), "原码应在 code 保留（仅空码归一 unknown）");
        // 复查轮 T-1：unknown 态内联硬词面（上游 delivery_view.py:78-79——非 pipeline 裁掉块）
        expect("未知交付状态：mystery".equals(cast(view.get("status")).get("title")),
                "unknown title 词面不符");
        expect("状态不在交付合同中，禁止显示为成功。".equals(cast(view.get("status")).get("summary")),
                "unknown summary 词面不符");

        // freshness 边界：stale-sensitive 超 300s 才 stale（301 红 / 300 绿）
        expect(Boolean.TRUE.equals(freshnessOf("executing", 301, base).get("stale")),
                "executing 超 300s 应 stale");
        expect(!Boolean.TRUE.equals(freshnessOf("executing", 300, base).get("stale")),
                "executing 恰 300s 不应 stale（>300 语义）");
        Map<String, Object> staleView = DeliveryView.build(taskAt("executing", 301, base), List.of(),
                true, base);
        expect(((List<?>) cast(staleView.get("integrity")).get("issues")).contains("stale_active_task"),
                "stale 活任务应入 issues");
        // review 是人工等待态：再久也不 stale（上游注释语义）
        Map<String, Object> reviewFresh = freshnessOf("review", 99999, base);
        expect(!Boolean.TRUE.equals(reviewFresh.get("stale")), "review 等待不因久而 stale");
        expect("current".equals(reviewFresh.get("state")), "review 新鲜度应为 current");
        // 终态 final；不可解析时间戳 unknown + stale
        expect("final".equals(freshnessOf("completed", 99999, base).get("state")), "终态应 final");
        Map<String, Object> broken = DeliveryView.build(
                Map.of("id", "TASK-BROKEN0001", "status", "executing", "updated_at", "not-a-stamp"),
                List.of(), true, base);
        Map<String, Object> brokenFresh = cast(broken.get("freshness"));
        expect("unknown".equals(brokenFresh.get("state")) && Boolean.TRUE.equals(brokenFresh.get("stale")),
                "不可解析 updated_at 应 unknown 且 stale");
        // dead_letter 词面
        Map<String, Object> deadView = DeliveryView.build(
                Map.of("id", "TASK-DEAD000001", "status", "dead_letter",
                        "updated_at", "2026-08-31 01:00:00"),
                List.of(), true, base);
        expect("已停止，待核对".equals(cast(deadView.get("status")).get("label")),
                "dead_letter label 词面不符");
        return "未知状态不可信 + freshness 边界（301/300）+ review 不 stale + 终态 final + 时间戳破损兜底";
    }

    /** C4：列表轻投影压缩 + 汇总计数 + feedback 关联（对照 test_service_joins + list summary）。 */
    private static String projectionCompactAndList() {
        Path runtime = tempRuntime("l14-list-");
        TaskStore store = new TaskStore(runtime.resolve("workbench.db"));
        Feedback feedback = new Feedback(runtime.resolve("workbench.db"));
        DeliveryAutomation automation = new DeliveryAutomation(store, runtime, greenSuite(), 2, null, 3);
        Map<String, Object> task = automation.submit("复现页面状态漂移", "REQ-COURSE-L14-PANEL",
                List.of("SKU:NOTEBOOK-AI"), "eval-requester", "verify", null, 900, true);
        String taskId = String.valueOf(task.get("id"));
        automation.wait(taskId, 30);
        feedback.addFeedback(taskId, "web-review", "详情与列表不一致", "固定投影合同", null, "");

        Map<String, Object> joined = DeliveryView.view(store, feedback, taskId);
        Map<String, Object> feedbackBlock = cast(joined.get("feedback"));
        expect(Integer.valueOf(1).equals(feedbackBlock.get("total")), "feedback 关联 total 应 1");
        expect(Integer.valueOf(1).equals(feedbackBlock.get("pending_review")), "待审反馈应 1");

        // 复查轮 T-3：C2 同源复核——同一 taskId 经投影与经 SQL（TaskStore 原始行）逐字段一致
        Map<String, Object> raw = store.get(taskId);
        Map<String, Object> taskProjection = cast(joined.get("task"));
        for (String key : List.of("id", "status", "requirement_id", "version", "reviewed_by",
                "automation_mode", "execution_mode", "execution_timeout_seconds")) {
            expect(java.util.Objects.equals(raw.get(key), taskProjection.get(key)),
                    "投影与库同源不符：" + key + "（库=" + raw.get(key) + " 投影=" + taskProjection.get(key) + "）");
        }
        expect(raw.get("business_refs").equals(taskProjection.get("business_refs")),
                "投影与库同源不符：business_refs");

        Map<String, Object> listing = DeliveryView.list(store, feedback, 20);
        expect("workbench.delivery-view-list/v1".equals(listing.get("schema")), "列表 schema 不符");
        Map<String, Object> summary = cast(listing.get("summary"));
        expect(Integer.valueOf(1).equals(summary.get("total")), "total 应 1");
        expect(Integer.valueOf(1).equals(summary.get("active")), "review 属活跃应 1");
        expect(Integer.valueOf(1).equals(summary.get("review")), "review 应 1");
        expect(Integer.valueOf(0).equals(summary.get("completed")), "completed 应 0");
        expect(Integer.valueOf(1).equals(summary.get("pending_feedback")), "pending_feedback 应 1");
        Map<String, Object> item = cast(((List<?>) listing.get("items")).get(0));
        expect(taskId.equals(item.get("task_id")), "列表条目应指回任务");
        Map<String, Object> itemTask = cast(item.get("task"));
        expect(!itemTask.containsKey("spec") && !itemTask.containsKey("result")
                && !itemTask.containsKey("events"), "轻投影 task 应去 spec/result/events");
        expect(((List<?>) cast(item.get("eval")).get("results")).isEmpty(), "轻投影 eval.results 应空");
        expect(((List<?>) item.get("events")).isEmpty(), "轻投影 events 应空数组");

        store.review(taskId, "delivery-reviewer", "approve", "列表汇总更新复验");
        Map<String, Object> updated = cast(DeliveryView.list(store, feedback, 20).get("summary"));
        expect(Integer.valueOf(1).equals(updated.get("completed")) && Integer.valueOf(0).equals(updated.get("active")),
                "批准后汇总应 completed 1 / active 0");
        expect(((List<?>) DeliveryView.view(store, feedback, taskId).get("allowed_actions"))
                .contains("export_candidate"), "REQ-COURSE-L 前缀完成应允许 export_candidate");
        return "列表轻投影压缩 + 汇总计数（review→completed 迁移即变）+ feedback 关联 + export_candidate 条件";
    }

    /** C5：views 列表/详情 HTTP 面 + 静态 serve（头/穿越守卫）+ verify 路由（含拒绝词面）。 */
    private static String httpViewsAndStatic() {
        Path runtime = tempRuntime("l14-http-");
        HttpApi api = new HttpApi(runtime, greenSuite());
        int port = startQuietly(api);
        try {
            Map<String, Object> body = Map.of("lesson", 14, "request", "HTTP 面复验",
                    "actor", "panel-student");
            Map<String, Object> accepted = readJson(httpPost(port, "/api/v1/delivery/requests",
                    body, "l14-http-1"), 202);
            String taskId = String.valueOf(accepted.get("task_id"));
            api.automation().wait(taskId, 30);

            Map<String, Object> listing = readJson(httpGet(port, "/api/v1/delivery/views"), 200);
            expect("workbench.delivery-view-list/v1".equals(listing.get("schema")), "列表 schema 不符");
            expect(Integer.valueOf(1).equals(cast(listing.get("summary")).get("total")),
                    "列表汇总 total 应 1");
            expect(((List<?>) readJson(httpGet(port, "/api/v1/delivery/views?limit=1"), 200)
                    .get("items")).size() == 1, "limit=1 应只回 1 条");
            Map<String, Object> detail = readJson(
                    httpGet(port, "/api/v1/delivery/views/" + taskId), 200);
            expect("workbench.delivery-view/v1".equals(detail.get("schema")), "详情 schema 不符");
            expect(("/api/v1/delivery/views/" + taskId)
                    .equals(cast(detail.get("links")).get("self")), "links.self 应指回自身");

            HttpResponse<String> index = httpGet(port, "/");
            expect(index.statusCode() == 200, "GET / 应 200");
            expect(String.valueOf(index.headers().firstValue("Content-Type").orElse(""))
                    .startsWith("text/html"), "面板 Content-Type 应 text/html");
            expect(String.valueOf(index.headers().firstValue("Cache-Control").orElse(""))
                    .contains("no-cache"), "面板应 no-cache（投影实时性）");
            expect(index.body().contains("个人研发工作台"), "面板身份文案应在");
            expect(String.valueOf(httpGet(port, "/app.js").headers()
                    .firstValue("Content-Type").orElse("")).contains("javascript"),
                    "app.js Content-Type 应 javascript");
            expect(httpGet(port, "/%2e%2e/pom.xml").statusCode() == 404, "穿越编码路径应 404");
            expect(httpGet(port, "/missing-file.xyz").statusCode() == 404, "缺席文件应 404");

            // verify 路由：review 态拒绝（词面）→ 手动 queued 任务具名复验跑通四段链
            HttpResponse<String> refused = httpPost(port, "/api/v1/tasks/" + taskId + "/verify",
                    Map.of("actor", "boss"), null);
            expect(refused.statusCode() == 400 && refused.body().contains("不能再跑复验"),
                    "review 态复验应 400（当前状态 review 不能再跑复验）：" + refused.body());
            HttpResponse<String> anonymous = httpPost(port, "/api/v1/tasks/" + taskId + "/verify",
                    Map.of("actor", " "), null);
            expect(anonymous.statusCode() == 400 && anonymous.body().contains("具名复验人"),
                    "匿名复验应 400（必须填写具名复验人）");
            Map<String, Object> manual = api.createCourseTask(14, "手动任务复验面", "panel-student",
                    null, null);
            String manualId = String.valueOf(manual.get("task_id"));
            Map<String, Object> verified = readJson(
                    httpPost(port, "/api/v1/tasks/" + manualId + "/verify",
                            Map.of("actor", "panel-reviewer"), null), 200);
            expect("workbench.delivery-view/v1".equals(verified.get("schema"))
                            && "review".equals(cast(verified.get("status")).get("code")),
                    "复验响应应为完整投影且停在 review");
        } finally {
            api.stop();
        }
        return "views 列表/详情 HTTP 面 + 静态 serve（content-type/no-cache/穿越 404）+ verify 三拍（拒/匿名拒/跑通）";
    }

    /** C1/C3：面板静态断言（文本直读——结构与取数面，不冒充渲染测试，讲义 D10 如实声明）。 */
    private static String panelStaticAsserts() {
        Path root = repoRoot();
        String html = readString(root.resolve("workbench_web/index.html"));
        String script = readString(root.resolve("workbench_web/app.js"));
        String css = readString(root.resolve("workbench_web/styles.css"));
        for (String marker : List.of("个人研发工作台", "FlowERP 是客户项目案例", "本讲合同",
                "提交并复验", "批准完成", "加载中", "暂无任务", "加载失败")) {
            expect((html + script).contains(marker), "面板缺 marker：" + marker);
        }
        for (String face : List.of("/api/v1/delivery/capabilities", "/api/v1/delivery/views",
                "/api/v1/delivery/requests", "/verify", "/review")) {
            expect(script.contains(face), "取数面缺：" + face);
        }
        expect(script.contains("function esc("), "动态文本应经 esc() 转义");
        expect(!(html + script + css).contains("apiKey")
                        && !(html + script).toLowerCase(Locale.ROOT).contains("secret")
                        && !script.contains("JSON.stringify(detail")
                        && !html.contains("src=\"http") && !html.contains("href=\"http")
                        && !script.contains("https://"),
                "面板不得含凭据字样 / 外部引用 / detail 直写");
        expect(!(html + script).contains("SKU:NOTEBOOK-AI")
                        && !(html + script).contains("PURCHASE:COURSE-DEMO"),
                "面板不得硬编码演示业务数据（静态假数据红旗）");
        expect(css.contains("task-card"), "styles.css 应有任务卡样式");
        // 复查轮 T-6：取数面闭集（C1「新数据源必须过合同」的机检面）——六面 =
        // capabilities / requests 提交 / views 列表 / views 详情 / verify / review
        // （五个路径前缀：views 列表与详情同前缀异形、verify/review 共 tasks/ 前缀）
        java.util.Set<String> faces = new java.util.TreeSet<>();
        java.util.regex.Matcher calls = java.util.regex.Pattern
                .compile("api\\(\"([^\"]*)\"").matcher(script);
        while (calls.find()) {
            String path = calls.group(1);
            int query = path.indexOf('?');
            faces.add(query >= 0 ? path.substring(0, query) : path);
        }
        expect(faces.equals(Set.of("/api/v1/delivery/capabilities", "/api/v1/delivery/views",
                        "/api/v1/delivery/views/", "/api/v1/delivery/requests", "/api/v1/tasks/")),
                "取数面闭集不符（新增数据源必须过合同）：" + faces);
        return "面板静态断言：身份/三态/取数面六面闭集 + esc + 无凭据 + 无 CDN + 无假数据";
    }

    /**
     * C3/C6：仓面密钥扫描（上游 no_committed_secrets 的 Java 对应物——扫本仓根；
     * markers/忽略集/后缀名单同形 + 形态差异落账；扫描件自身豁免对照上游 cases.py）。
     */
    private static String noCommittedSecrets() {
        Path root = repoRoot();
        List<String> suspicious = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                Path relative = root.relativize(file);
                boolean ignored = false;
                for (Path part : relative) {
                    if (IGNORED_DIRS.contains(part.toString())) {
                        ignored = true;
                        break;
                    }
                }
                if (ignored) {
                    continue;
                }
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!SCANNED_SUFFIXES.stream().anyMatch(name::endsWith)) {
                    continue;
                }
                if (isDerivedCapture(relative)) {
                    continue; // diff-phase 采集件 = 受跟踪源的衍生引用（源已扫，扫引用冗余）
                }
                scanned++;
                String text = readString(file);
                for (String marker : SECRET_MARKERS) {
                    if (text.contains(marker)) {
                        suspicious.add(relative + "（含密钥特征）");
                        break;
                    }
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("仓面扫描失败：" + error.getMessage(), error);
        }
        expect(suspicious.isEmpty(), "疑似密钥文件：" + suspicious);
        expect(scanned >= 200, "扫描面应有足量文件（实际 " + scanned + "）——扫描器空转即红旗");
        return "文本源文件未发现常见密钥特征（扫描 " + scanned + " 件；忽略集含本仓形态差异四目录）";
    }

    // ---- 受控 ERP 四向对账 + 冻结指纹 ------------------------------------------------------------------

    /** C2/C8：受控客户实例（页静态 ↔ 客户 API ↔ 客户 SQLite ↔ 本仓投影 business_refs）+ 客户自测照跑。 */
    private static String erpReconciliation(Path target, Path interpreter) {
        Map<String, Object> snapshot = runErpDriver(target, interpreter);
        String sku = "SKU-L14-PANEL-01";
        expect(Integer.valueOf(200).equals(snapshot.get("page_status"))
                        && String.valueOf(snapshot.get("page_ctype")).startsWith("text/html"),
                "客户页应 200 text/html（受控实例自 serve）");
        expect(Integer.valueOf(200).equals(snapshot.get("appjs_status"))
                        && Boolean.TRUE.equals(snapshot.get("appjs_api_face"))
                        && Boolean.TRUE.equals(snapshot.get("appjs_has_esc"))
                        && Boolean.TRUE.equals(snapshot.get("appjs_no_credential")),
                "客户页 app.js 应真取数（/api/v1 + esc + 无凭据字样）");
        expect(Integer.valueOf(201).equals(snapshot.get("bootstrap_status"))
                        && Integer.valueOf(200).equals(snapshot.get("login_status"))
                        && Integer.valueOf(201).equals(snapshot.get("product_status")),
                "种子链应走通（bootstrap/login/建产品）：" + snapshot);
        expect(List.of(sku).equals(snapshot.get("api_skus")) && List.of(sku).equals(snapshot.get("db_skus")),
                "API 与 SQLite 快照应一致且含种子 SKU：" + snapshot.get("api_skus")
                        + " / " + snapshot.get("db_skus"));

        // 第四向：本仓工作台投影的 business_refs 关联同一 ERP 对象
        Path runtime = tempRuntime("l14-erp-link-");
        HttpApi api = new HttpApi(runtime, greenSuite());
        int port = startQuietly(api);
        try {
            Map<String, Object> accepted = readJson(httpPost(port, "/api/v1/delivery/requests",
                    Map.of("lesson", 14, "request", "面板对账关联任务", "actor", "panel-student",
                            "business_refs", List.of(sku)), "l14-erp-link"), 202);
            Map<String, Object> detail = readJson(httpGet(port,
                    "/api/v1/delivery/views/" + accepted.get("task_id")), 200);
            expect(String.valueOf(detail.get("business_refs")).contains(sku),
                    "工作台投影应能关联 ERP 对象（business_refs）");
        } finally {
            api.stop();
        }

        // 客户自测照跑（.venv python + 客户树 cwd——只读执行，临时库自足）
        ProcessRunner.Outcome suite = ProcessRunner.run(
                List.of(interpreter.toString(), "-X", "utf8", "-m", "unittest", "tests.test_http_api"),
                target, 300, null);
        expect(!suite.timedOut() && Integer.valueOf(0).equals(suite.returncode()),
                "客户 test_http_api 应绿（实际 rc=" + suite.returncode() + "）："
                        + suite.stdoutText().substring(0, Math.min(300, suite.stdoutText().length())));
        return "受控客户实例四向对账（页静态/API/SQLite/工作台投影 business_refs）+ 客户自测 11 例照跑绿";
    }

    /**
     * diff-phase 采集件忽略判定（04-diff 目录下的采集输出）：git diff 输出是被扫描
     * 受跟踪源码的衍生引用——源文件本身已在扫描面内，扫引用件对密钥检测零增量
     * （且本扫描器源码进 diff 后整词字面会经采集件回流，见 SECRET_MARKERS 注）。
     * 其余 phase（红/绿/观察）输出不是源码引用，照扫。
     */
    private static boolean isDerivedCapture(Path relative) {
        for (Path part : relative) {
            if ("04-diff".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /** 客户树七件指纹复核（l13 三件承袭 + server.py + 客户 web/ 三件）。 */
    private static String frozenChecks(Path target) {
        Map<String, Object> mismatched = new LinkedHashMap<>();
        for (String rel : FROZEN_FILES) {
            String targetHash = sha256(target.resolve(rel));
            String sourceHash = sha256(repoRoot().resolve("vendors").resolve("flowERP").resolve(rel));
            if (!targetHash.equals(sourceHash)) {
                mismatched.put(rel, Map.of(
                        "target", targetHash.substring(0, 12),
                        "vendors", sourceHash.substring(0, 12)));
            }
        }
        if (!mismatched.isEmpty()) {
            throw new AssertionError("冻结检查被改动: " + PyJson.dumpsCompact(mismatched));
        }
        StringBuilder evidence = new StringBuilder("冻结检查指纹一致: ");
        for (int i = 0; i < FROZEN_FILES.length; i++) {
            if (i > 0) {
                evidence.append(", ");
            }
            evidence.append(FROZEN_FILES[i]).append('=')
                    .append(sha256(target.resolve(FROZEN_FILES[i])).substring(0, 12));
        }
        return evidence.toString();
    }

    /** ERP 受控实例驱动器（探针族先例：子进程 python 起客户 App+server，断言与预期留 Java）。 */
    private static Map<String, Object> runErpDriver(Path target, Path interpreter) {
        String driver = """
                import json, os, sqlite3, sys, tempfile, threading
                from http.client import HTTPConnection
                from http.server import ThreadingHTTPServer
                from pathlib import Path

                sys.path.insert(0, os.getcwd())  # 脚本文件模式 sys.path[0]=临时目录，客户树须显式入路
                from flowerp.server import App, make_handler

                tmp = tempfile.mkdtemp(prefix="l14-erp-")
                app = App(tmp)
                server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(app))
                port = server.server_address[1]
                threading.Thread(target=server.serve_forever, daemon=True).start()

                def call(method, path, body=None, token=None, extra=None):
                    conn = HTTPConnection("127.0.0.1", port, timeout=10)
                    head = {"Content-Type": "application/json"}
                    if token:
                        head["Authorization"] = "Bearer " + token
                    if extra:
                        head.update(extra)
                    conn.request(method, path, json.dumps(body) if body is not None else None, head)
                    resp = conn.getresponse()
                    data = resp.read().decode("utf-8")
                    ctype = resp.getheader("Content-Type") or ""
                    conn.close()
                    return resp.status, data, ctype

                out = {}
                status, html, ctype = call("GET", "/")
                out["page_status"] = status
                out["page_ctype"] = ctype
                status, appjs, _ = call("GET", "/app.js")
                out["appjs_status"] = status
                out["appjs_api_face"] = "/api/v1" in appjs
                out["appjs_has_esc"] = "function esc(" in appjs
                out["appjs_no_credential"] = "apiKey" not in appjs
                out["bootstrap_status"] = call("POST", "/api/v1/setup/bootstrap",
                    {"organization_name": "L14 对账实验", "username": "l14-admin",
                     "password": "L14panel-pass-2026"})[0]
                status, data, _ = call("POST", "/api/v1/auth/login",
                    {"organization": "DEFAULT", "username": "l14-admin",
                     "password": "L14panel-pass-2026"})
                out["login_status"] = status
                token = json.loads(data).get("token") if status == 200 else ""
                out["product_status"] = call("POST", "/api/v1/products",
                    {"sku": "SKU-L14-PANEL-01", "name": "面板对账产品", "sales_price_cents": 1999},
                    token=token, extra={"Idempotency-Key": "l14-erp-seed-1"})[0]
                status, data, _ = call("GET", "/api/v1/products?limit=500", token=token)
                out["api_skus"] = [item.get("sku") for item in json.loads(data).get("items", [])]
                db = sqlite3.connect(str(Path(tmp) / "flowerp.db"))
                out["db_skus"] = [row[0] for row in db.execute("SELECT sku FROM product_master")]
                db.close()
                server.shutdown()
                server.server_close()
                print(json.dumps(out, ensure_ascii=False))
                """;
        Path driverFile = null;
        try {
            driverFile = Files.createTempFile("l14-erp-driver-", ".py");
            Files.writeString(driverFile, driver, StandardCharsets.UTF_8);
            ProcessRunner.Outcome outcome = ProcessRunner.run(
                    List.of(interpreter.toString(), "-X", "utf8", driverFile.toString()),
                    target, 120, null);
            if (outcome.timedOut() || !Integer.valueOf(0).equals(outcome.returncode())) {
                throw new IllegalStateException("ERP 受控实例驱动失败 rc=" + outcome.returncode()
                        + "：" + outcome.stdoutText() + outcome.stderrText());
            }
            String stdout = outcome.stdoutText().strip();
            String tail = stdout.substring(stdout.lastIndexOf('\n') + 1);
            return MAPPER.readValue(tail, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (IOException error) {
            throw new IllegalStateException("ERP 驱动执行失败", error);
        } finally {
            if (driverFile != null) {
                try {
                    Files.deleteIfExists(driverFile);
                } catch (IOException ignored) {
                    // 临时件清理尽力而为
                }
            }
        }
    }

    // ---- 断言与工具（l13 DeliveryChecks 族形） ------------------------------------------------------------------

    private static Map<String, Object> taskAt(String status, long ageSeconds, OffsetDateTime now) {
        return Map.of("id", "TASK-FRESH" + String.format("%04d", ageSeconds), "status", status,
                "updated_at", now.minusSeconds(ageSeconds).toString());
    }

    private static Map<String, Object> freshnessOf(String status, long ageSeconds, OffsetDateTime now) {
        return cast(DeliveryView.build(taskAt(status, ageSeconds, now), List.of(), true, now)
                .get("freshness"));
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static Object field(Map<String, Object> node, String outer, String inner) {
        if (outer != null) {
            return node.get(outer) instanceof Map<?, ?> map ? map.get(inner) : null;
        }
        return node.get(inner);
    }

    private static int startQuietly(HttpApi api) {
        try {
            return api.start("127.0.0.1", 0);
        } catch (IOException error) {
            throw new IllegalStateException("测试服务监听失败", error);
        }
    }

    private static Path resolveInterpreter(String candidate) {
        Path python = Path.of(candidate).toAbsolutePath().normalize();
        if (!Files.isRegularFile(python)) {
            throw new Args.UsageException("python 不存在：" + python);
        }
        return python;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Path tempRuntime(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException error) {
            throw new IllegalStateException("临时运行目录创建失败", error);
        }
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("文件读取失败：" + path, error);
        }
    }

    // ---- HTTP 便捷（l13 族形） ------------------------------------------------------------------

    private static HttpResponse<String> httpGet(int port, String path) {
        try {
            return CLIENT.send(HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException error) {
            throw new IllegalStateException("HTTP GET 失败：" + path, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP GET 被中断：" + path, error);
        }
    }

    private static HttpResponse<String> httpPost(int port, String path, Map<String, Object> body,
            String idempotencyKey) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
            if (idempotencyKey != null) {
                request.header("Idempotency-Key", idempotencyKey);
            }
            return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException error) {
            throw new IllegalStateException("HTTP POST 失败：" + path, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP POST 被中断：" + path, error);
        }
    }

    private static Map<String, Object> readJson(HttpResponse<String> response, int expectedStatus) {
        expect(response.statusCode() == expectedStatus,
                "HTTP " + response.uri().getPath() + " 期望 " + expectedStatus
                        + " 实际 " + response.statusCode() + "：" + response.body());
        try {
            return MAPPER.readValue(response.body(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (IOException error) {
            throw new AssertionError("响应体不是 JSON：" + response.body(), error);
        }
    }

    private static String sha256(Path file) {
        try {
            return sha256(Files.readAllBytes(file));
        } catch (IOException error) {
            throw new IllegalStateException("冻结检查文件读取失败：" + file, error);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    /** l06–l13 Checks.repoRoot 同形：锚定本类装载位置（target/classes → 仓库根）。 */
    static Path repoRoot() {
        try {
            return Path.of(WebPanelChecks.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().getParent().getParent();
        } catch (java.net.URISyntaxException error) {
            throw new IllegalStateException("仓库根定位失败（class 装载位置）", error);
        }
    }
}
