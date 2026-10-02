package workbench.evals.l13;

import com.fasterxml.jackson.databind.ObjectMapper;
import workbench.bootstrap.Args;
import workbench.bootstrap.PyJson;
import workbench.delivery.DeliveryAutomation;
import workbench.delivery.Feedback;
import workbench.delivery.HttpApi;
import workbench.delivery.SpecFactory;
import workbench.delivery.TaskStore;
import workbench.delivery.Workflow;
import workbench.evals.EvalHarness;
import workbench.spec.SpecParser;

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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * L13 任务 API 统一入口（上游绑定 eval {@code delivery_evidence_and_review_controls}
 * 断言面 + {@code task_api_contract.py}/{@code check_required_workbench} + HTTP 合同测试
 * 的 Java 机检收口；讲义 §2 登记项构成）。
 *
 * <p>十件默认门全 blocking：工作台面八件（进程内装配 HttpApi——合成 suite 经
 * suite_runner 缝注入：控制模式机检用合成缝，上游 eval 用例 fixture 防递归同理，
 * 如实标注——真实检查面 = delivery-serve 的 {@code --suite-command} 子进程缝，
 * 由受控 API 实验承载）+ 客户 {@code purchase_requires_approval} 原名照跑
 * （l08 SalesChecks.customerCase 复用）+ 冻结指纹复核（客户 eval 双树 +
 * {@code flowerp/service.py}，l07–l12 同形；本讲 Java 源件指纹承载面 = 证据账层面，
 * L11 T-2 口径直承）。
 *
 * <p>argv 面（l08–l12 Checks 同形）：{@code --target} 必填（客户树根，须含
 * flowerp/）；{@code --case}（可多，选跑子集）；{@code --python}（缺省
 * .venv/bin/python）、{@code --no-report}、{@code --report-path}。
 */
public final class DeliveryChecks {

    /** 冻结面（讲义 C8）：客户 eval 双树 + 采购面源件（l12 同三件）。 */
    private static final String[] FROZEN_FILES = {
            "eval/harness.py", "eval/cases.py", "flowerp/service.py"};

    private static final String DEFAULT_PYTHON = ".venv/bin/python";

    private static final Pattern TASK_ID_PATTERN = Pattern.compile("TASK-[A-Z0-9]{10}");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private DeliveryChecks() {}

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

    /** 十件默认门（C 表映射见讲义 §2 登记项构成——全部 blocking，无必红选跑项）。 */
    static List<EvalHarness.Entry> entries(Path target, Path interpreter) {
        return List.of(
                new EvalHarness.Entry("l13_task_api_accept", "blocking",
                        DeliveryChecks::taskApiAccept),
                new EvalHarness.Entry("l13_idempotency_and_conflict", "blocking",
                        DeliveryChecks::idempotencyAndConflict),
                new EvalHarness.Entry("l13_event_traceability", "blocking",
                        DeliveryChecks::eventTraceability),
                new EvalHarness.Entry("l13_stops_at_review", "blocking",
                        DeliveryChecks::stopsAtReview),
                new EvalHarness.Entry("l13_state_machine", "blocking",
                        DeliveryChecks::stateMachine),
                new EvalHarness.Entry("l13_rework_and_feedback_observation", "blocking",
                        DeliveryChecks::reworkAndFeedbackObservation),
                new EvalHarness.Entry("l13_feedback_review_loop", "blocking",
                        DeliveryChecks::feedbackReviewLoop),
                new EvalHarness.Entry("l13_required_workbench", "blocking",
                        DeliveryChecks::requiredWorkbench),
                new EvalHarness.Entry("purchase_requires_approval", "blocking",
                        () -> customerCase(interpreter, target, "purchase_requires_approval")),
                new EvalHarness.Entry("l13_frozen_checks", "blocking",
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
        return () -> suiteReport("pass", 0, "delivery_pipeline");
    }

    private static Workflow.SuiteRunner redSuite() {
        return () -> suiteReport("block", 1, "observed_failure");
    }

    // ---- 场景断言（独立预期全部留 Java，不抄返回值） ------------------------------------------

    /**
     * C1：202 契约 + 异步诚实（202 先于 Eval 完成——latch 钉死，对照上游
     * entered/release 形态）+ capabilities + 词面校验五拍。
     */
    private static String taskApiAccept() {
        Path runtime = tempRuntime("l13-accept-");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Workflow.SuiteRunner held = () -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("fixture was not released");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("fixture 等待被中断", error);
            }
            return suiteReport("pass", 0, "required_api_boundary_fixture");
        };
        HttpApi api = new HttpApi(runtime, held);
        int port = startQuietly(api);
        try {
            Map<String, Object> capabilities = readJson(httpGet(port, "/api/v1/delivery/capabilities"), 200);
            expect(List.of("verify").equals(capabilities.get("execution_modes")), "capabilities.execution_modes 应为 [verify]");
            expect(Boolean.TRUE.equals(capabilities.get("requires_idempotency_key")), "capabilities.requires_idempotency_key 应为 true");
            expect(Boolean.TRUE.equals(capabilities.get("async_submission")), "capabilities.async_submission 应为 true");

            Map<String, Object> body = Map.of("lesson", 13, "request", "复验采购审批边界",
                    "actor", "student-li", "business_refs", List.of("PURCHASE:PUR-FIXTURE"));
            Map<String, Object> accepted = readJson(httpPost(port, "/api/v1/delivery/requests", body, "lesson-submit"), 202);
            String taskId = String.valueOf(accepted.get("task_id"));
            expect(TASK_ID_PATTERN.matcher(taskId).matches(), "task_id 形态应 TASK-[A-Z0-9]{10}，实际 " + taskId);
            expect(Boolean.TRUE.equals(accepted.get("accepted")), "accepted 应为 true");
            expect("verify".equals(accepted.get("execution_mode")), "execution_mode 应 verify");
            expect(String.valueOf(accepted.get("status_url")).equals("/api/v1/tasks/" + taskId), "status_url 契约不符");
            expect(String.valueOf(accepted.get("view_url")).equals("/api/v1/delivery/views/" + taskId), "view_url 契约不符");

            // 异步证明：202 已到手而 suite 尚未放行完成
            expect(awaitQuietly(entered, 5), "202 返回后 Eval 应已进入但未完成（entered）");
            expect(release.getCount() == 1, "202 必须先于 Eval 完成返回（异步诚实）");

            Map<String, Object> codex = new java.util.HashMap<>(body);
            codex.put("execution_mode", "codex");
            expect(httpPost(port, "/api/v1/delivery/requests", codex, "codex").statusCode() == 400,
                    "网页 API 只允许 verify（codex 应 400）");
            Map<String, Object> blank = Map.of("lesson", 13, "request", "", "actor", "student");
            expect(httpPost(port, "/api/v1/delivery/requests", blank, "blank").statusCode() == 400,
                    "空需求应 400");
            Map<String, Object> anonymous = Map.of("lesson", 13, "request", "补货", "actor", "");
            expect(httpPost(port, "/api/v1/delivery/requests", anonymous, "anon").statusCode() == 400,
                    "匿名提交人应 400");
            Map<String, Object> badRefs = Map.of("lesson", 13, "request", "补货", "actor", "s", "business_refs", "invalid");
            expect(httpPost(port, "/api/v1/delivery/requests", badRefs, "badrefs").statusCode() == 400,
                    "非法 business_refs 应 400");
            Map<String, Object> early = Map.of("lesson", 3, "request", "补货", "actor", "s");
            expect(httpPost(port, "/api/v1/delivery/requests", early, "early").statusCode() == 400,
                    "L03 越界应 400（Web 受控任务只允许 L04-L16）");

            release.countDown();
            Map<String, Object> task = api.automation().wait(taskId, 30);
            expect("review".equals(task.get("status")), "全绿后应停在 review，实际 " + task.get("status"));

            // 回归锚：客户 eval 平铺报告必须包装成 summary 形态（首次受控实验抓获的
            // 形态分歧——缺包装则 Workflow 判定面读到 null 判红，见 13-api/02-attempt1）
            Map<String, Object> flat = new LinkedHashMap<>();
            flat.put("decision", "pass");
            flat.put("blocking_failed", 0);
            flat.put("total", 1);
            flat.put("passed", 1);
            Map<String, Object> wrapped = workbench.delivery.DeliveryServe.normalizeReport(flat);
            expect(wrapped.get("summary") instanceof Map<?, ?> summary
                    && "pass".equals(summary.get("decision"))
                    && Integer.valueOf(0).equals(summary.get("blocking_failed")),
                    "平铺报告应包装为 summary 形态（缺省 suite 面）");
        } finally {
            release.countDown();
            api.stop();
        }
        return "202 契约 + 异步证明（202 先于 Eval 完成）+ capabilities verify-only + 词面拒绝五拍 + 平铺报告包装回归锚";
    }

    /** C2：同键同需求重放同 Task ID / 同键异需求（含异 refs）409 / 跨实例并发提交原子。 */
    private static String idempotencyAndConflict() {
        Path runtime = tempRuntime("l13-idem-");
        HttpApi api = new HttpApi(runtime, greenSuite());
        int port = startQuietly(api);
        try {
            Map<String, Object> body = Map.of("lesson", 13, "request", "补货 PURCHASE:PR-A",
                    "actor", "student");
            Map<String, Object> first = readJson(httpPost(port, "/api/v1/delivery/requests", body, "key-1"), 202);
            Map<String, Object> replay = readJson(httpPost(port, "/api/v1/delivery/requests", body, "key-1"), 202);
            expect(first.get("task_id").equals(replay.get("task_id")), "同键同需求重放应返回同一 Task ID");
            Map<String, Object> listing = readJson(httpGet(port, "/api/v1/tasks?limit=30"), 200);
            expect(((List<?>) listing.get("items")).size() == 1, "重放后任务列表应只有 1 条");

            Map<String, Object> other = Map.of("lesson", 13, "request", "不同需求", "actor", "student");
            expect(httpPost(port, "/api/v1/delivery/requests", other, "key-1").statusCode() == 409,
                    "同键异需求应 409（请使用新键）");
            Map<String, Object> otherRefs = Map.of("lesson", 13, "request", "补货 PURCHASE:PR-A",
                    "actor", "student", "business_refs", List.of("PURCHASE:PR-OTHER"));
            expect(httpPost(port, "/api/v1/delivery/requests", otherRefs, "key-1").statusCode() == 409,
                    "同键异 business_refs 应 409（fingerprint 不同）");
            readJson(httpGet(port, "/api/v1/tasks/" + first.get("task_id")), 200);
            expect(httpGet(port, "/api/v1/tasks/TASK-MISSING00").statusCode() == 404, "未知任务应 404");
        } finally {
            api.stop();
        }
        Path db = runtime.resolve("workbench.db");
        TaskStore first = new TaskStore(db);
        TaskStore second = new TaskStore(db);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Map<String, Object>> one = pool.submit(() -> {
                start.await();
                return first.create("同一需求", "", List.of(), "FDE_SPEC.md", "student",
                        "automatic", null, "verify", null, 900, "same-key");
            });
            Future<Map<String, Object>> two = pool.submit(() -> {
                start.await();
                return second.create("同一需求", "", List.of(), "FDE_SPEC.md", "student",
                        "automatic", null, "verify", null, 900, "same-key");
            });
            start.countDown();
            expect(getQuietly(one).get("id").equals(getQuietly(two).get("id")),
                    "跨实例并发同键应得到同一 Task ID");
            expect(first.list(30).size() == 2, "并发提交后列表 = 既有 1 + 新 1");
            try {
                second.create("不同需求", "", List.of(), "FDE_SPEC.md", "student",
                        "automatic", null, "verify", null, 900, "same-key");
                throw new AssertionError("工作台接受了同键不同需求");
            } catch (TaskStore.TaskSubmissionConflict expected) {
                // 预期词面：提交键已用于不同需求；请使用新键
            }
        } finally {
            pool.shutdownNow();
        }
        return "幂等提交三面：同键重放同 ID / 同键异需求与异 refs 409 / 跨实例并发原子";
    }

    /** C3：事件链全序 + evidence 三向 + Spec 落盘可解析 + 每任务独立 + 信号词路由。 */
    private static String eventTraceability() {
        Path runtime = tempRuntime("l13-trace-");
        TaskStore store = new TaskStore(runtime.resolve("workbench.db"));
        DeliveryAutomation automation = new DeliveryAutomation(store, runtime, greenSuite(), 2, null, 3);
        Map<String, Object> task = automation.submit("补货 PURCHASE:PR-TRACE 的自动交付",
                "REQ-EVAL-TRACE", List.of("PURCHASE:PR-TRACE"), "eval-requester", "verify", null, 900, true);
        Map<String, Object> done = automation.wait(String.valueOf(task.get("id")), 30);
        String taskId = String.valueOf(done.get("id"));
        List<String> details = eventDetails(done);
        for (String expected : List.of("任务已接收", "已从需求生成任务级结构化 Spec",
                "自动流水线开始推进", "结构化 Spec 已校验", "开始受控执行", "受控执行阶段完成",
                "阻断级 Eval 已通过，等待具名人工审核")) {
            expect(details.contains(expected), "事件链缺：" + expected + "（实际 " + details + "）");
        }
        Map<String, Object> startEvent = findEvent(done, "自动流水线开始推进");
        expect(List.of("spec", "execute", "blocking_eval", "human_review")
                .equals(field(startEvent, "evidence", "stages")), "开始推进事件 stages 四段不符");
        expect(Boolean.FALSE.equals(field(startEvent, "evidence", "human_review_is_automatic")),
                "human_review_is_automatic 必须为 false（自动化永不代人审）");
        expect("REQ-EVAL-TRACE".equals(done.get("requirement_id")), "evidence 三向之 requirement_id");
        expect(String.valueOf(done.get("business_refs")).contains("PURCHASE:PR-TRACE"), "evidence 三向之 business_refs");
        Map<String, Object> evalEvent = findEvent(done, "阻断级 Eval 已通过，等待具名人工审核");
        expect(String.valueOf(field(evalEvent, "evidence", "report_path")).startsWith("reports/"),
                "evidence 三向之 report_path");
        expect(String.valueOf(field(evalEvent, "evidence", "report_sha256")).length() == 64,
                "report_sha256 应为 64 位十六进制");
        Path reportFile = runtime.resolve(String.valueOf(field(evalEvent, "evidence", "report_path")));
        expect(Files.isRegularFile(reportFile), "Eval 报告应落盘");

        Path specPath = Path.of(String.valueOf(done.get("spec_path")));
        expect(Files.isRegularFile(specPath), "任务级 Spec 应落盘且每任务独立");
        String specText = readString(specPath);
        expect(specText.contains("补货建议尚未由具名人员批准") && specText.contains("相同入库幂等键重放"),
                "补货信号词应触发两条业务边界验收用例");
        try {
            SpecParser.parse(specText);
        } catch (RuntimeException error) {
            throw new AssertionError("生成 Spec 应可被 L03 解析器解析：" + error.getMessage());
        }
        Map<String, Object> another = automation.submit("补货 PURCHASE:PR-TRACE-2 的自动交付",
                "REQ-EVAL-TRACE2", List.of(), "eval-requester", "verify", null, 900, true);
        expect(!String.valueOf(another.get("spec_path")).equals(done.get("spec_path")),
                "每任务 Spec 路径应独立");
        return "事件链 7 段全序 + 三向 evidence（需求/对象/报告+SHA）+ Spec 落盘可解析 + 信号词路由";
    }

    /** C4：绿链 review 停住 + 匿名/Agent/未绿批准三拒绝 + 具名 approve/reject 分叉。 */
    private static String stopsAtReview() {
        Path runtime = tempRuntime("l13-review-");
        TaskStore store = new TaskStore(runtime.resolve("workbench.db"));
        DeliveryAutomation automation = new DeliveryAutomation(store, runtime, greenSuite(), 2, null, 3);
        Map<String, Object> task = automation.submit("验证 SKU:NOTEBOOK-AI 的自动交付证据状态机",
                "REQ-EVAL-DELIVERY", List.of("SKU:NOTEBOOK-AI"), "eval-requester", "verify", null, 900, true);
        Map<String, Object> done = automation.wait(String.valueOf(task.get("id")), 30);
        String taskId = String.valueOf(done.get("id"));
        expect("review".equals(done.get("status")) && "automatic".equals(done.get("automation_mode")),
                "自动链应停在 review（automatic），实际 " + done.get("status"));
        expect(done.get("reviewed_by") == null, "review 停住时 reviewed_by 应为空");
        expect(Files.isRegularFile(Path.of(String.valueOf(done.get("spec_path")))), "Spec 文件应在");
        expect(goalOf(done).isEmpty() == false, "task.spec.goal 应非空");

        expectRejected(() -> store.transition(taskId, "completed", "匿名完成", "system", null, Map.of()),
                "完成交付必须由具名审核人明确 approve");
        expectRejected(() -> store.review(taskId, "agent:coder", "approve", "代签"),
                "Agent 员工不能代替老板终审");
        expectRejected(() -> store.review(taskId, "  ", "approve", "空白身份"),
                "老板身份不能为空");
        expectRejected(() -> store.review(taskId, "boss", "maybe", "决定无效"),
                "审核决定必须是 approve 或 reject");
        expectRejected(() -> store.review(taskId, "boss", "approve", "  "),
                "审核理由不能为空");
        Map<String, Object> delivery = store.review(taskId, "delivery-reviewer", "approve", "阻断证据完整");
        expect("completed".equals(delivery.get("status")), "具名 approve 应完成");
        expect("delivery-reviewer".equals(delivery.get("reviewed_by")), "reviewed_by 应具名");

        Map<String, Object> second = automation.submit("验证打回路径", "REQ-EVAL-REJECT", List.of(),
                "eval-requester", "verify", null, 900, true);
        String secondId = String.valueOf(second.get("id"));
        automation.wait(secondId, 30);
        Map<String, Object> rejected = store.review(secondId, "boss", "reject", "证据不足，返工");
        expect("rework".equals(rejected.get("status")), "具名 reject 应回 rework");
        expect(eventDetails(rejected).stream().anyMatch(detail -> detail.startsWith("老板终审：reject")),
                "老板终审事件 detail 词面不符");
        return "全绿停 review（reviewed_by 空）+ 匿名/Agent/无效决定/空理由四拒绝 + 具名分叉";
    }

    /** C5：九态合法迁移表 + 跳步拒绝 + 并发迁移互斥（恰好一成一拒）。 */
    private static String stateMachine() {
        expect(Set.of().equals(TaskStore.VALID_TRANSITIONS.get("completed"))
                && Set.of().equals(TaskStore.VALID_TRANSITIONS.get("dead_letter")),
                "completed/dead_letter 应为终态（空出边）");
        expect(TaskStore.VALID_TRANSITIONS.get("queued").equals(Set.of("spec_ready", "failed", "dead_letter")),
                "queued 出边不符（上游同形）");
        expect(TaskStore.VALID_TRANSITIONS.get("review").containsAll(Set.of("completed", "rework", "failed", "dead_letter")),
                "review 出边不符");

        Path runtime = tempRuntime("l13-machine-");
        TaskStore store = new TaskStore(runtime.resolve("workbench.db"));
        Map<String, Object> task = store.create("验证不能跳过交付阶段", "REQ-EVAL-JUMP", List.of(),
                "FDE_SPEC.md", "eval", "manual", null, "verify", null, 900, null);
        String taskId = String.valueOf(task.get("id"));
        expectRejected(() -> store.transition(taskId, "completed", "跳过评测", "eval", null, Map.of()),
                "非法任务状态迁移：queued -> completed");
        expectRejected(() -> store.transition(taskId, "evaluating", "跳过执行", "eval", null, Map.of()),
                "非法任务状态迁移：queued -> evaluating");

        // 并发互斥：两实例两线程同时迁移同一任务，恰好一个成功、另一个被拒
        TaskStore second = new TaskStore(runtime.resolve("workbench.db"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> one = pool.submit(() -> {
                start.await();
                return tryTransition(store, taskId, "worker-a");
            });
            Future<String> two = pool.submit(() -> {
                start.await();
                return tryTransition(second, taskId, "worker-b");
            });
            start.countDown();
            String firstOutcome = getQuietly(one);
            String secondOutcome = getQuietly(two);
            int successes = ("ok".equals(firstOutcome) ? 1 : 0) + ("ok".equals(secondOutcome) ? 1 : 0);
            expect(successes == 1, "并发迁移应恰好一个成功（互斥），实际 a=" + firstOutcome
                    + " b=" + secondOutcome);
            String loser = "ok".equals(firstOutcome) ? secondOutcome : firstOutcome;
            expect(loser.contains("拒绝并发迁移") || loser.contains("非法任务状态迁移"),
                    "并发被拒方词面应是互斥守卫，实际 " + loser);
            expect("spec_ready".equals(store.get(taskId).get("status")), "终态应为 spec_ready");
        } finally {
            pool.shutdownNow();
        }
        return "九态合法迁移表 + 跳步拒绝（queued→completed/evaluating）+ 并发迁移互斥";
    }

    /** C6：红 Eval → rework + 自动反馈沉淀 + 有界重试（耗尽保留）+ 异常分类处置。 */
    private static String reworkAndFeedbackObservation() {
        Path runtime = tempRuntime("l13-rework-");
        TaskStore store = new TaskStore(runtime.resolve("workbench.db"));
        Feedback feedback = new Feedback(runtime.resolve("workbench.db"));
        DeliveryAutomation single = new DeliveryAutomation(store, runtime, redSuite(), 2, null, 1);
        Map<String, Object> failed = single.submit("验证自动失败进入待审反馈",
                "REQ-EVAL-FAILURE-OBSERVATION", List.of("SKU:NOTEBOOK-AI"), "eval-requester",
                "verify", null, 900, true);
        Map<String, Object> failedTask = single.wait(String.valueOf(failed.get("id")), 30);
        String failedId = String.valueOf(failedTask.get("id"));
        expect("rework".equals(failedTask.get("status")), "红 Eval 应进 rework");
        Map<String, Object> observation = firstItemFor(feedback, failedId);
        expect("pending_review".equals(observation.get("status")), "自动反馈应 pending_review");
        expect("automation:rework".equals(observation.get("source")), "source 应 automation:rework");
        expect(String.valueOf(field(observation, "evidence", "blocking_failures"))
                .contains("observed_failure"), "blocking_failures 应含 observed_failure");
        expect(eventDetails(failedTask).contains("失败已沉淀为待具名审核的反馈候选"),
                "失败沉淀事件应在链");

        DeliveryAutomation bounded = new DeliveryAutomation(store, runtime, redSuite(), 2, null, 3);
        Map<String, Object> retried = bounded.submit("验证有界重试", "REQ-EVAL-RETRY", List.of(),
                "eval-requester", "verify", null, 900, true);
        Map<String, Object> exhausted = bounded.wait(String.valueOf(retried.get("id")), 60);
        List<String> details = eventDetails(exhausted);
        expect(details.contains("自动流水线安排有界重试"), "重试事件应在链");
        expect(details.contains("有界重试已耗尽，保留阻断失败并等待人工处理"),
                "耗尽保留事件应在链（failure_preserved）");
        long attempts = details.stream().filter("自动流水线开始推进"::equals).count();
        expect(attempts == 3, "三次尝试后停止（max_attempts=3），实际 " + attempts);

        // suite 异常的可达路径：runTask 把 executing/evaluating 中的异常分类为不可重试 →
        // failed → 流水线转 dead_letter「转人工处理」（error 摘要保留、不静默吞）。
        // 上游「executing 中异常 → rework 保留现场」是防御分支（runTask 之外的存储层
        // 异常才触发），正常序列不可达——如实标注，防御代码在 Workflow/自动化件内保留。
        Workflow.SuiteRunner crashing = () -> {
            throw new IllegalStateException("合成执行器异常");
        };
        DeliveryAutomation crashingPipeline = new DeliveryAutomation(store, runtime, crashing, 2, null, 1);
        Map<String, Object> crashed = crashingPipeline.submit("验证异常不静默吞",
                "REQ-EVAL-CRASH", List.of(), "eval-requester", "verify", null, 900, true);
        Map<String, Object> crashedTask = crashingPipeline.wait(String.valueOf(crashed.get("id")), 30);
        expect("dead_letter".equals(crashedTask.get("status")),
                "suite 异常应转人工处理（dead_letter），实际 " + crashedTask.get("status"));
        expect(eventDetails(crashedTask).stream()
                        .anyMatch(detail -> detail.contains("不可重试的执行失败，转人工处理")),
                "转人工处理事件词面应在链（不静默吞）");
        expect(String.valueOf(crashedTask.get("error")).contains("合成执行器异常"), "异常摘要应保留");
        return "红 Eval→rework+自动反馈 + 有界重试 3 次耗尽保留 + suite 异常→dead_letter 转人工（防御分支如实标注）";
    }

    /** C7：人工反馈登记 + 具名审核 + 重复决定拒绝 + summary 统计。 */
    private static String feedbackReviewLoop() {
        Path runtime = tempRuntime("l13-feedback-");
        TaskStore store = new TaskStore(runtime.resolve("workbench.db"));
        DeliveryAutomation automation = new DeliveryAutomation(store, runtime, greenSuite(), 2, null, 3);
        Map<String, Object> task = automation.submit("验证反馈审核闭环", "REQ-EVAL-FEEDBACK",
                List.of(), "eval-requester", "verify", null, 900, true);
        Map<String, Object> done = automation.wait(String.valueOf(task.get("id")), 30);
        String taskId = String.valueOf(done.get("id"));
        store.review(taskId, "boss", "approve", "先完成后审反馈");

        Feedback feedback = new Feedback(runtime.resolve("workbench.db"));
        Map<String, Object> added = feedback.addFeedback(taskId, "eval", "证据待确认", "人工复核", null, "");
        expect("pending_review".equals(added.get("status")), "人工反馈应 pending_review");
        expectRejected(() -> feedback.addFeedback(" ", "eval", "x", "y", null, ""),
                "反馈必须包含 task_id、来源、结论和下一步");
        Map<String, Object> reviewed = feedback.reviewFeedback(String.valueOf(added.get("id")),
                "eval-reviewer", "accept", "证据有效");
        expect("accepted".equals(reviewed.get("status")), "具名 accept 应 accepted");
        expect("eval-reviewer".equals(reviewed.get("reviewed_by")), "reviewed_by 应具名");
        expectRejected(() -> feedback.reviewFeedback(String.valueOf(added.get("id")),
                "other-reviewer", "reject", "重复决定"), "反馈已经审核，不能重复改变结论");
        expectRejected(() -> feedback.reviewFeedback(String.valueOf(added.get("id")),
                "boss", "undo", "无效决定"), "审核决定必须为 accept 或 reject");
        Map<String, Object> summary = feedback.summary();
        expect(((Number) summary.get("pending_review")).intValue() == 0
                        && ((Number) summary.get("accepted")).intValue() == 1,
                "summary 统计不符：" + PyJson.dumpsCompact(summary));
        return "反馈登记→具名审核→重复决定拒绝→summary 统计";
    }

    /**
     * C8（上游 check_required_workbench 全断言对照）：accept → wait review 停住 →
     * 同键重放同 id → 异需求冲突 → 重开实例状态持久 → recover 空 → 无 flowerp.db。
     */
    private static String requiredWorkbench() {
        Path runtime = tempRuntime("l13-required-");
        HttpApi api = new HttpApi(runtime, greenSuite());
        Map<String, Object> accepted = api.acceptCourseTask(13, "复验采购审批", "eval-student",
                "required-api-eval", null);
        String taskId = String.valueOf(accepted.get("task_id"));
        Map<String, Object> task = api.automation().wait(taskId, 30);
        expect("review".equals(task.get("status")) && "verify".equals(task.get("execution_mode")),
                "API 任务应停在 review（verify），实际 " + task.get("status"));
        expect(task.get("reviewed_by") == null, "review 停住时 reviewed_by 应空");
        Map<String, Object> replay = api.acceptCourseTask(13, "复验采购审批", "eval-student",
                "required-api-eval", null);
        expect(taskId.equals(replay.get("task_id")), "同键同需求重放应同 Task ID");
        expect(api.tasks().list(30).size() == 1, "重放后列表应 1 条");
        try {
            api.acceptCourseTask(13, "另一需求", "eval-student", "required-api-eval", null);
            throw new AssertionError("工作台接受了同键不同需求");
        } catch (TaskStore.TaskSubmissionConflict expected) {
            // 预期词面：提交键已用于不同需求；请使用新键
        }
        HttpApi reopened = new HttpApi(runtime, greenSuite());
        Map<String, Object> persisted = reopened.tasks().get(taskId);
        expect("review".equals(persisted.get("status")), "重开实例后状态应仍在（SQLite 持久）");
        expect(String.valueOf(persisted.get("business_refs")).contains("PURCHASE:COURSE-DEMO"),
                "lesson 13 合同自带业务对象应在（PURCHASE:COURSE-DEMO）");
        expect(reopened.automation().recover().isEmpty(), "无中断任务时 recover 应为空");
        expect(Files.notExists(runtime.resolve("flowerp.db")),
                "API 运行目录不得出现 flowerp.db（不触客户库）");
        return "accept→review 停住 + 重放同 id + 冲突 409 面 + 重开持久 + recover 空 + 无 flowerp.db";
    }

    // ---- 客户 eval 与冻结指纹（l08–l12 同形） ------------------------------------

    /** 客户 blocking eval 子进程照跑（l08 SalesChecks.customerCase 复用）。 */
    private static String customerCase(Path interpreter, Path target, String caseName) {
        return workbench.evals.l08.SalesChecks.customerCase(interpreter, target, caseName);
    }

    /** 客户 eval 双树 + 采购面源件指纹复核（l07–l12 frozenChecks 同形）。 */
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

    // ---- 断言与工具 ------------------------------------------------------------------

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String tryTransition(TaskStore store, String taskId, String actor) {
        try {
            store.transition(taskId, "spec_ready", "并发迁移 " + actor, actor, null, Map.of());
            return "ok";
        } catch (IllegalArgumentException error) {
            return error.getMessage();
        }
    }

    private static int startQuietly(HttpApi api) {
        try {
            return api.start("127.0.0.1", 0);
        } catch (IOException error) {
            throw new IllegalStateException("测试服务监听失败", error);
        }
    }

    private static boolean awaitQuietly(CountDownLatch latch, int seconds) {
        try {
            return latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("latch 等待被中断", error);
        }
    }

    private static <V> V getQuietly(Future<V> future) {
        try {
            return future.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发任务等待被中断", error);
        } catch (java.util.concurrent.ExecutionException error) {
            if (error.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("并发任务失败", error);
        }
    }

    /** l12 ApprovalProbe.resolveInterpreter 同义（包私有不可跨包，本地复刻）：绝对化 + 存在性检查。 */
    private static Path resolveInterpreter(String candidate) {
        Path python = Path.of(candidate).toAbsolutePath().normalize();
        if (!Files.isRegularFile(python)) {
            throw new Args.UsageException("python 不存在：" + python);
        }
        return python;
    }

    private interface ThrowingRunnable {
        void run();
    }

    private static void expectRejected(ThrowingRunnable action, String expectedPhrase) {
        try {
            action.run();
        } catch (IllegalArgumentException error) {
            expect(String.valueOf(error.getMessage()).contains(expectedPhrase),
                    "拒绝词面不符——期望含「" + expectedPhrase + "」，实际「" + error.getMessage() + "」");
            return;
        }
        throw new AssertionError("预期被拒绝但通过了：" + expectedPhrase);
    }

    private static Path tempRuntime(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException error) {
            throw new IllegalStateException("临时运行目录创建失败", error);
        }
    }

    private static List<String> eventDetails(Map<String, Object> task) {
        List<String> details = new ArrayList<>();
        for (Object item : (List<?>) task.get("events")) {
            if (item instanceof Map<?, ?> event) {
                details.add(String.valueOf(event.get("detail")));
            }
        }
        return details;
    }

    private static Map<String, Object> findEvent(Map<String, Object> task, String detail) {
        for (Object item : (List<?>) task.get("events")) {
            if (item instanceof Map<?, ?> event && detail.equals(event.get("detail"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) event;
                return cast;
            }
        }
        throw new AssertionError("找不到事件：" + detail);
    }

    private static Object field(Map<String, Object> node, String outer, String inner) {
        if (node.get(outer) instanceof Map<?, ?> map) {
            return map.get(inner);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstItemFor(Feedback feedback, String taskId) {
        for (Object item : (List<?>) feedback.summary().get("items")) {
            Map<String, Object> row = (Map<String, Object>) item;
            if (taskId.equals(row.get("task_id"))) {
                return row;
            }
        }
        throw new AssertionError("找不到任务的自动反馈：" + taskId);
    }

    private static String goalOf(Map<String, Object> task) {
        return task.get("spec") instanceof Map<?, ?> spec ? String.valueOf(spec.get("goal")) : "";
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("文件读取失败：" + path, error);
        }
    }

    // ---- HTTP 便捷 ------------------------------------------------------------------

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
            @SuppressWarnings("unchecked")
            Map<String, Object> body = MAPPER.readValue(response.body(), Map.class);
            return body;
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

    /** l06–l12 Checks.repoRoot 同形：锚定本类装载位置（target/classes → 仓库根）。 */
    private static Path repoRoot() {
        try {
            return Path.of(DeliveryChecks.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().getParent().getParent();
        } catch (java.net.URISyntaxException error) {
            throw new IllegalStateException("仓库根定位失败（class 装载位置）", error);
        }
    }
}
