package workbench.delivery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import workbench.coursecontracts.CourseContracts;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * 交付任务 HTTP API（上游 {@code workbench/workbench_server.py} 的 L13 断言面子集，
 * L13 讲义 D2/D3）：{@code com.sun.net.httpserver}（JDK 内置——依赖白名单零新增）。
 *
 * <p>端点（上游路由词面同形）：GET {@code /api/v1/delivery/capabilities}（verify-only
 * + 幂等键要求）、POST {@code /api/v1/delivery/requests}（202 + Task ID——受理不是
 * 完成；Idempotency-Key 头承载提交键）、GET {@code /api/v1/tasks/{id}}、
 * GET {@code /api/v1/delivery/views}（列表，L14——默认 limit 20）、
 * GET {@code /api/v1/delivery/views/{id}}（完整投影，L14 升级）、
 * POST {@code /api/v1/tasks/{id}/verify}（仅复验入口，L14）、
 * POST {@code /api/v1/tasks/{id}/review}。
 * 错误映射同上游：415 unsupported_media_type / 400 invalid_json·词面 /
 * 404 not_found / 409 conflict（同键异需求「请使用新键」）/ body 上限 8192。
 * 形态差异如实声明：400 的 error 字段 = Java 异常 SimpleName（上游 = Python
 * 异常类名如 "ValueError"）——退出码与 message 词面对齐上游，类名字段按本仓
 * 语言自然形态（L12 D2「上游裸栈 rc 1 形态差异如实入账」同款口径）。
 *
 * <p>L14 增量（讲义 D5/D6）：{@code view()} 从简化投影升级为 {@link DeliveryView}
 * 完整投影（verify/review POST 响应同形升级）；静态面板 serve（WEB_ROOT = 本仓
 * {@code workbench_web/}——resolve 后不在根内一律 404 穿越守卫 + is_file 守卫 +
 * 后缀 Content-Type + {@code no-cache}；API 路由优先于静态回退，上游
 * {@code workbench_server} 同一承载——无新命令）。limit 解析容错回缺省
 * （非法 limit 不 400——L13 {@code /api/v1/tasks} 同款口径）。
 *
 * <p>D1 裁剪如实记录：上游 main 版 capabilities 的 code_execution 簇字段、
 * graphs/preview/artifacts 路由、initiatives/projects/backups 面不在 L13/L14 断言面，
 * 不建。
 */
public final class HttpApi {

    /** 上游 capabilities 断言面子集（execution_modes/requires_idempotency_key 是测试面）。 */
    static Map<String, Object> capabilitiesBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("surface", "workbench");
        body.put("async_submission", true);
        body.put("execution_modes", List.of("verify"));
        body.put("requires_idempotency_key", true);
        return body;
    }

    private static final int MAX_BODY_BYTES = 8192;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path runtime;
    private final TaskStore tasks;
    private final Feedback feedback;
    private final Workflow.SuiteRunner suiteRunner;
    private final DeliveryAutomation automation;

    public HttpApi(Path runtimeDir, Workflow.SuiteRunner suiteRunner) {
        this.runtime = runtimeDir.toAbsolutePath().normalize();
        this.tasks = new TaskStore(this.runtime.resolve("workbench.db"));
        this.feedback = new Feedback(this.runtime.resolve("workbench.db"));
        this.suiteRunner = suiteRunner;
        this.automation = new DeliveryAutomation(this.tasks, this.runtime, suiteRunner,
                2, null, 3);
    }

    public TaskStore tasks() {
        return tasks;
    }

    public DeliveryAutomation automation() {
        return automation;
    }

    /** 起服务（上游 serve() 对应物；port 0 = 随机分配，返回实际端口）。 */
    public int start(String host, int port) throws IOException {
        HttpServer created = HttpServer.create(new InetSocketAddress(host, port), 0);
        created.createContext("/", this::handle);
        created.setExecutor(Executors.newCachedThreadPool());
        created.start();
        this.server = created;
        return created.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpServer server;

    // ---- 应用面（WorkbenchApp 断言面子集） ------------------------------------------------------------------

    /**
     * 创建课程任务（上游 create_course_task 同形）：lesson 4–16 / 词面校验 /
     * 合同业务引用去重合并 / 讲次 Spec 生成 / 幂等重放清理新文件 /
     * 「Web 已创建受控任务；尚未授权 Codex 写入」。
     */
    public Map<String, Object> createCourseTask(int lesson, String request, String actor,
            String submissionKey, Object businessRefs) {
        if (lesson < 4 || lesson > 16) {
            throw new IllegalArgumentException("Web 受控任务只允许选择 L04-L16");
        }
        String need = request == null ? "" : request.strip();
        if (need.isEmpty()) {
            throw new IllegalArgumentException("现场需求不能为空");
        }
        if (need.length() > 1000) {
            throw new IllegalArgumentException("现场需求不能超过 1000 个字符");
        }
        String submitter = actor == null ? "" : actor.strip();
        if (submitter.isEmpty()) {
            throw new IllegalArgumentException("必须填写具名提交人");
        }
        if (submitter.length() > 80) {
            throw new IllegalArgumentException("提交人名称不能超过 80 个字符");
        }
        CourseContracts.LessonContract contract = CourseContracts.lessonContract(lesson);
        List<?> rawRefs = businessRefs == null ? List.of() : null;
        if (rawRefs == null) {
            if (!(businessRefs instanceof List<?> provided) || provided.size() > 20) {
                throw new IllegalArgumentException("业务引用必须是最多 20 个非空编号，每个不超过 200 字符");
            }
            rawRefs = provided;
        }
        for (Object ref : rawRefs) {
            String value = ref instanceof String item ? item.strip() : "";
            if (!(ref instanceof String) || value.isEmpty() || value.length() > 200) {
                throw new IllegalArgumentException("业务引用必须是最多 20 个非空编号，每个不超过 200 字符");
            }
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>(contract.businessRefs());
        for (Object ref : rawRefs) {
            merged.add(String.valueOf(ref).strip());
        }
        List<String> refs = new ArrayList<>(merged);
        Path specPath = runtime.resolve("course").resolve("L%02d".formatted(lesson))
                .resolve(UUID.randomUUID().toString().replace("-", ""))
                .resolve("FDE_SPEC.md");
        SpecFactory.writeLessonSpec(lesson, specPath, List.of());
        Map<String, Object> task = tasks.create(need, contract.requirementId(), refs,
                specPath.toString(), submitter,
                submissionKey != null ? "automatic" : "manual",
                null, "verify", contract.writeScope(), 900, submissionKey);
        if (!specPath.toString().equals(task.get("spec_path"))) {
            // 本次尝试只拥有新生成（未使用）的文件——幂等重放清理
            try {
                Files.deleteIfExists(specPath);
                Files.deleteIfExists(specPath.getParent());
            } catch (IOException error) {
                // 清理失败不破坏重放语义（上游 unlink + rmdir 同为尽力而为）
            }
            return view(String.valueOf(task.get("id")));
        }
        tasks.appendEvent(String.valueOf(task.get("id")),
                "Web 已创建受控课程任务；尚未授权 Codex 写入", submitter,
                Map.of("lesson", lesson, "execution_mode", "verify"));
        return view(String.valueOf(task.get("id")));
    }

    /**
     * 受理异步提交（上游 accept_course_task 同形）：Idempotency-Key 必填 +
     * 幂等命中不重启自动化 + queued 才开跑；返回 202 契约体。
     */
    public Map<String, Object> acceptCourseTask(int lesson, String request, String actor,
            String key, Object businessRefs) {
        if (key == null || key.strip().isEmpty()) {
            throw new IllegalArgumentException("异步提交必须提供 Idempotency-Key");
        }
        Map<String, Object> created = createCourseTask(lesson, request, actor, key.strip(), businessRefs);
        String taskId = String.valueOf(created.get("task_id"));
        if ("queued".equals(tasks.get(taskId).get("status"))) {
            automation.start(taskId, actor);
        }
        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("task_id", taskId);
        accepted.put("accepted", true);
        accepted.put("execution_mode", "verify");
        accepted.put("status_url", "/api/v1/tasks/" + taskId);
        accepted.put("view_url", "/api/v1/delivery/views/" + taskId);
        return accepted;
    }

    /** 具名审核（上游 review_task 的断言面直通——日常研发/红绿差分门不在本讲面）。 */
    public Map<String, Object> reviewTask(String taskId, String reviewer, String decision, String note) {
        tasks.review(taskId, reviewer, decision, note);
        return view(taskId);
    }

    /**
     * 仅复验入口（上游 verify_task 断言面子集，L14 D5——v0 分支不采纳）：具名复验人
     * 必填；codex 拒绝「代码任务须通过课程执行方案处理，不能从仅复验入口运行」；
     * 仅 queued/spec_ready/rework 可跑（「当前状态 {status} 不能再跑复验」）；
     * 同步跑完四段链后返回完整投影。
     */
    public Map<String, Object> verifyTask(String taskId, String actor) {
        String verifier = actor == null ? "" : actor.strip();
        if (verifier.isEmpty()) {
            throw new IllegalArgumentException("必须填写具名复验人");
        }
        if (verifier.length() > 80) {
            throw new IllegalArgumentException("复验人名称不能超过 80 个字符");
        }
        Map<String, Object> task = tasks.get(taskId);
        if ("codex".equals(task.get("execution_mode"))) {
            throw new IllegalArgumentException("代码任务须通过课程执行方案处理，不能从仅复验入口运行");
        }
        String status = String.valueOf(task.get("status") == null ? "" : task.get("status"));
        if (!List.of("queued", "spec_ready", "rework").contains(status)) {
            throw new IllegalArgumentException("当前状态 " + status + " 不能再跑复验");
        }
        Workflow.runTask(tasks, taskId, verifier, suiteRunner, null);
        return view(taskId);
    }

    /** 任务视图（DeliveryView 完整投影——L14 从简化占位升级，spec C4/C5）。 */
    public Map<String, Object> view(String taskId) {
        return DeliveryView.view(tasks, feedback, taskId);
    }

    /** 列表投影（轻投影 + 汇总计数——L14 C4/C5）。 */
    public Map<String, Object> viewsList(int limit) {
        return DeliveryView.list(tasks, feedback, limit);
    }

    // ---- HTTP 路由 ------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod())) {
                handleGet(exchange, path);
            } else if ("POST".equals(exchange.getRequestMethod())) {
                handlePost(exchange, path);
            } else {
                json(exchange, 404, Map.of("error", "not_found"));
            }
        } catch (TaskStore.TaskSubmissionConflict error) {
            json(exchange, 409, Map.of("error", "conflict", "message", String.valueOf(error.getMessage())));
        } catch (TaskStore.TaskNotFound error) {
            json(exchange, 404, Map.of("error", "not_found", "message", String.valueOf(error.getMessage())));
        } catch (Feedback.FeedbackNotFound error) {
            json(exchange, 404, Map.of("error", "not_found", "message", String.valueOf(error.getMessage())));
        } catch (IllegalArgumentException error) {
            json(exchange, 400, Map.of("error", error.getClass().getSimpleName(),
                    "message", String.valueOf(error.getMessage())));
        } catch (RuntimeException error) {
            json(exchange, 500, Map.of("error", error.getClass().getSimpleName(),
                    "message", String.valueOf(error.getMessage())));
        }
    }

    private void handleGet(HttpExchange exchange, String path) throws IOException {
        if ("/api/v1/delivery/capabilities".equals(path)) {
            json(exchange, 200, capabilitiesBody());
            return;
        }
        if ("/api/v1/tasks".equals(path) || "/api/tasks".equals(path)) {
            Map<String, Object> query = exchange.getRequestURI().getRawQuery() == null ? Map.of()
                    : parseQuery(exchange.getRequestURI().getRawQuery());
            Map<String, Object> listing = new LinkedHashMap<>();
            listing.put("items", tasks.list(parseLimit(query, 30)));
            json(exchange, 200, listing);
            return;
        }
        if (path.startsWith("/api/v1/tasks/")) {
            String taskId = lastSegment(path);
            json(exchange, 200, tasks.get(taskId));
            return;
        }
        if ("/api/v1/delivery/views".equals(path)) {
            Map<String, Object> query = exchange.getRequestURI().getRawQuery() == null ? Map.of()
                    : parseQuery(exchange.getRequestURI().getRawQuery());
            json(exchange, 200, viewsList(parseLimit(query, 20)));
            return;
        }
        if (path.startsWith("/api/v1/delivery/views/")) {
            json(exchange, 200, view(lastSegment(path)));
            return;
        }
        serveStatic(exchange, path);
    }

    // ---- 静态面板 serve（上游 WEB_ROOT 段同形，L14 D6） ------------------------------------------------------------------

    /** 面板根 = 仓库根 workbench_web/（锚定本类装载位置——DeliveryServe.repoRoot 同形）。 */
    static Path webRoot() {
        try {
            return Path.of(HttpApi.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().getParent().getParent().resolve("workbench_web").normalize();
        } catch (java.net.URISyntaxException error) {
            throw new IllegalStateException("仓库根定位失败（class 装载位置）", error);
        }
    }

    /** 后缀 Content-Type（对照上游 mimetypes.guess_type 语义：Python 3.13 口径，缺省 text/html）。 */
    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".html")) {
            return "text/html";
        }
        if (name.endsWith(".css")) {
            return "text/css";
        }
        if (name.endsWith(".js")) {
            return "text/javascript";
        }
        if (name.endsWith(".json")) {
            return "application/json";
        }
        return "text/html";
    }

    /**
     * 静态回退：API 路由之外的 GET 按面板文件解析（{@code /} → index.html）。
     * 穿越守卫同上游：resolve 后不在 WEB_ROOT 内一律 404；is_file 守卫 404；
     * 响应带 Content-Type + Content-Length + {@code Cache-Control: no-cache}。
     */
    private void serveStatic(HttpExchange exchange, String path) throws IOException {
        String relative = "/".equals(path) ? "index.html" : path.substring(1);
        Path root = webRoot();
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            json(exchange, 404, Map.of("error", "not_found"));
            return;
        }
        byte[] payload = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", contentType(file));
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    /** limit 解析（两路由同形）：非法或缺席回缺省（tasks 30 / views 20，上游各自默认同形）。 */
    private static int parseLimit(Map<String, Object> query, int defaultValue) {
        if (query.get("limit") instanceof String value && !value.isEmpty()) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException error) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static Map<String, Object> parseQuery(String rawQuery) {
        Map<String, Object> query = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                query.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return query;
    }

    private void handlePost(HttpExchange exchange, String path) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        String mediaType = contentType == null ? "" : contentType.split(";", 2)[0].strip().toLowerCase();
        if (!"application/json".equals(mediaType)) {
            json(exchange, 415, Map.of("error", "unsupported_media_type",
                    "message", "只接受 application/json"));
            return;
        }
        String lengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
        int length = lengthHeader == null ? 0 : Integer.parseInt(lengthHeader);
        if (length <= 0 || length > MAX_BODY_BYTES) {
            json(exchange, 400, Map.of("error", "invalid_body", "message", "请求体为空或过大"));
            return;
        }
        byte[] raw = exchange.getRequestBody().readAllBytes();
        Map<String, Object> body;
        try {
            Object parsed = MAPPER.readValue(raw, new TypeReference<Object>() {});
            if (!(parsed instanceof Map)) {
                throw new IllegalArgumentException("请求体必须是 JSON 对象");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) parsed;
            body = cast;
        } catch (IOException error) {
            json(exchange, 400, Map.of("error", "invalid_json", "message", "请求体不是合法 JSON"));
            return;
        }
        if ("/api/v1/delivery/requests".equals(path)) {
            String mode = String.valueOf(body.getOrDefault("execution_mode", "verify"));
            if (!"verify".equals(mode)) {
                throw new IllegalArgumentException("网页API只允许verify；代码执行须使用明确授权的课程CLI");
            }
            json(exchange, 202, acceptCourseTask(
                    intArgument(body, "lesson"),
                    String.valueOf(body.getOrDefault("request", "")),
                    String.valueOf(body.getOrDefault("actor", "")),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key") == null
                            ? "" : exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    body.get("business_refs")));
            return;
        }
        String[] segments = path.split("/");
        // /api/v1/tasks/{id}/review
        if (segments.length == 6 && "api".equals(segments[1]) && "v1".equals(segments[2])
                && "tasks".equals(segments[3]) && "review".equals(segments[5])) {
            json(exchange, 200, reviewTask(segments[4],
                    String.valueOf(body.getOrDefault("reviewer", "")),
                    String.valueOf(body.getOrDefault("decision", "")),
                    String.valueOf(body.getOrDefault("note", ""))));
            return;
        }
        // /api/v1/tasks/{id}/verify（仅复验入口，L14 D5）
        if (segments.length == 6 && "api".equals(segments[1]) && "v1".equals(segments[2])
                && "tasks".equals(segments[3]) && "verify".equals(segments[5])) {
            json(exchange, 200, verifyTask(segments[4],
                    String.valueOf(body.getOrDefault("actor", ""))));
            return;
        }
        json(exchange, 404, Map.of("error", "not_found"));
    }

    private static int intArgument(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isEmpty()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException error) {
                // 非数字 → 400（上游 int() 抛 ValueError → 400 同面；error 字段 =
                // Java 异常 SimpleName，message 承 int() 词面——形态差异见类 javadoc）
                throw new IllegalArgumentException("int() invalid literal: " + text);
            }
        }
        return 0;
    }

    private static String lastSegment(String path) {
        String[] segments = path.split("/");
        return segments[segments.length - 1];
    }

    private static void json(HttpExchange exchange, int status, Map<String, Object> body)
            throws IOException {
        byte[] payload = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
