package workbench.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import workbench.testsupport.Cli;
import workbench.testsupport.Server;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L14 交付状态 Web 面板合同测试：讲义 docs/lessons/L14-交付状态Web面板.md §2 C 表 →
 * 用例映射（operate 第二讲 + 检查点自建讲义第二例；映射源 = 上游
 * {@code tests/test_delivery_view.py} 五测 + {@code tests/workbench_web_dashboard.py}
 * 静态断言模式 + {@code workbench_server.py:375-399} serve 段 + 绑定 eval
 * {@code eval/cases.py:219-227/479-483} 断言面，只读对照）。
 *
 * <p>接缝（spec docs/specs/L14-交付状态Web面板.md 已具名）：零新高架构缝——
 * ①最高公开缝 = delivery-serve HTTP 面（Server 支撑件起真实子进程 + 进程内同款
 * handler 起随机端口两形态；响应头断言在进程内面——Server.Response 不透出 headers，
 * 如实分工）②WebPanelChecks 独立 main 子进程缝（l09–l13 Checks 族形新件）
 * ③面板静态件 = 文本直读缝（上游 test_workbench_web_dashboard 同形；如实声明：
 * 证明结构与取数面，不冒充渲染测试）。
 *
 * <p>红点组（commit 1，讲义 §3 步骤 2）：{@code workbench_web/} 缺席 → 静态断言红；
 * views 列表路由与静态 serve 缺席 → HTTP 404 红；DeliveryView 完整投影缺席 →
 * 对现存简化投影断言 freshness/status.owner/integrity 即红；WebPanelChecks main
 * 缺席 → rc 1 class not found。实现转绿后本类零改动。
 *
 * <pre>
 * 用例 → 合同映射：
 * panelStaticsFace                    C1/D5 面板静态断言：身份文案 + 取数面五条 + 无凭据 + 无 CDN + 三态 + esc
 * deliveryServeStaticAndViewsFace     C5 静态 serve（/ → index.html）+ 穿越守卫 404 + views 列表（子进程真实服务面）
 * viewsProjectionFullFace             C4/C5 完整投影 + 响应头（content-type/no-cache）+ 动作后投影 + 列表压缩与汇总（进程内）
 * l14ChecksMainUsageFace              C6/C7 argv 面：缺 --target → rc 2
 * l14ChecksRejectsUnknownCase         C6/C7 argv 面：未知名拒绝 rc 2
 * l14ChecksDefaultGateGreen           C1–C8 默认门八件净树全绿（rc 0）
 * </pre>
 */
class L14WebPanelContractTest {

    /** 净树默认门：客户树 = vendors/flowERP（只读），python 缺省 .venv/bin/python（l13 同口径）。 */
    private static final String VENDOR_TARGET = "vendors/flowERP";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    /** 进程内面响应记录（含响应头——Server.Response 不透出 headers，进程内自查）。 */
    private record RawResponse(int status, Map<String, List<String>> headers, String body) {}

    @Test
    void panelStaticsFace() {
        Path root = Cli.repoRoot();
        Path html = root.resolve("workbench_web/index.html");
        Path script = root.resolve("workbench_web/app.js");
        Path css = root.resolve("workbench_web/styles.css");

        // C1 前置：三件在场（红点①——目录缺席即红）
        assertThat(html).as("workbench_web/index.html 应在场").isRegularFile();
        assertThat(script).as("workbench_web/app.js 应在场").isRegularFile();
        assertThat(css).as("workbench_web/styles.css 应在场").isRegularFile();
        String htmlText = read(html);
        String scriptText = read(script);
        String cssText = read(css);

        // D5 身份文案与四区结构（上游 test_workbench_web_dashboard marker 模式同形）
        assertThat(htmlText).contains("个人研发工作台");
        assertThat(htmlText).contains("FlowERP 是客户项目案例");
        assertThat(htmlText).contains("本讲合同");
        assertThat(htmlText).contains("提交并复验");
        assertThat(htmlText).contains("批准完成");
        // 加载/空/失败三态（flowERP web/AGENTS.md 约束承袭）
        assertThat(htmlText + scriptText).contains("加载中");
        assertThat(htmlText + scriptText).contains("暂无任务");
        assertThat(htmlText + scriptText).contains("加载失败");

        // D5 取数面五条且仅此五条（fetch 面名单——页面数据来自 API 而非静态假数据，C1）
        assertThat(scriptText).contains("/api/v1/delivery/capabilities");
        assertThat(scriptText).contains("/api/v1/delivery/views");
        assertThat(scriptText).contains("/api/v1/delivery/requests");
        assertThat(scriptText).contains("/verify");
        assertThat(scriptText).contains("/review");
        // 客户端转义（flowERP AGENTS.md「表格输出统一经过 esc()」精神承袭）
        assertThat(scriptText).contains("function esc(");

        // C3 页面不包含凭据（上游 assertNotIn apiKey/secret 模式同形）+ 不 JSON.stringify 整个 detail
        assertThat(htmlText + scriptText + cssText).doesNotContain("apiKey");
        assertThat((htmlText + scriptText).toLowerCase()).doesNotContain("secret");
        assertThat(scriptText).doesNotContain("JSON.stringify(detail");
        // 无 CDN / 无外部资源引用（离线可跑——训练营与容器冷启动约束）
        assertThat(htmlText).doesNotContain("src=\"http");
        assertThat(htmlText).doesNotContain("href=\"http");
        assertThat(scriptText).doesNotContain("https://");
        // 无硬编码业务数据（静态假数据红旗——演示 fixture 字面量不得出现在面板）
        assertThat(htmlText + scriptText).doesNotContain("SKU:NOTEBOOK-AI");
        assertThat(htmlText + scriptText).doesNotContain("PURCHASE:COURSE-DEMO");
    }

    @Test
    void deliveryServeStaticAndViewsFace(@TempDir Path runtime) {
        try (Server server = Server.start(runtime)) {
            // C5 静态 serve：/ → index.html（红点②——serve 面缺席即 404）
            Server.Response index = server.get("/");
            assertThat(index.statusCode()).as("GET / 应 200：" + index.body()).isEqualTo(200);
            assertThat(index.body()).contains("个人研发工作台");
            Server.Response styles = server.get("/styles.css");
            assertThat(styles.statusCode()).isEqualTo(200);
            assertThat(styles.body()).contains("task-card");

            // C5 路径穿越守卫：resolve 后不在 WEB_ROOT 内一律 404（../ 编码与字面两形态）
            assertThat(server.get("/../pom.xml").statusCode()).isEqualTo(404);
            assertThat(server.get("/%2e%2e/pom.xml").statusCode()).isEqualTo(404);
            assertThat(server.get("/missing-file.xyz").statusCode()).isEqualTo(404);

            // C5 views 列表：默认 20 与自定义 limit（红点②——路由缺席即 404）
            Server.Response listing = server.get("/api/v1/delivery/views");
            assertThat(listing.statusCode()).as("views 列表应 200：" + listing.body()).isEqualTo(200);
            JsonNode body = server.json(listing);
            assertThat(body.path("schema").asText()).isEqualTo("workbench.delivery-view-list/v1");
            assertThat(body.path("summary").path("total").asInt(-1)).isZero();
            assertThat(body.path("items").isArray()).isTrue();
            assertThat(server.get("/api/v1/delivery/views?limit=5").statusCode()).isEqualTo(200);
            // 未知任务详情 404（回归护栏）
            assertThat(server.get("/api/v1/delivery/views/TASK-MISSING00").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void viewsProjectionFullFace(@TempDir Path runtime) {
        HttpApi api = new HttpApi(runtime, () -> {
            Map<String, Object> summary = Map.of("decision", "pass", "blocking_failed", 0,
                    "blocking_passed", 1);
            Map<String, Object> row = Map.of("name", "purchase_requires_approval", "level",
                    "blocking", "passed", true);
            Map<String, Object> report = new java.util.LinkedHashMap<>();
            report.put("schema_version", "1.0");
            report.put("summary", summary);
            report.put("results", List.of(row));
            return report;
        });
        int port;
        try {
            port = api.start("127.0.0.1", 0);
        } catch (IOException error) {
            throw new IllegalStateException("测试服务监听失败", error);
        }
        try {
            // C5 静态 serve 响应头（进程内同款 handler）：content-type 按后缀 + no-cache
            RawResponse index = rawGet(port, "/");
            assertThat(index.status()).as("GET / 应 200：" + index.body()).isEqualTo(200);
            assertThat(firstHeader(index, "Content-Type")).startsWith("text/html");
            assertThat(firstHeader(index, "Cache-Control")).contains("no-cache");
            RawResponse scriptFile = rawGet(port, "/app.js");
            assertThat(scriptFile.status()).isEqualTo(200);
            assertThat(firstHeader(scriptFile, "Content-Type")).contains("javascript");

            // 提交（lesson 14 合同面）→ 202 → review（合成绿 suite——机检缝，上游 fixture 同理）
            Map<String, Object> body = Map.of("lesson", 14, "request", "面板复验补货交付状态",
                    "actor", "panel-student", "business_refs", List.of("REQUIREMENT:COURSE-L14"));
            RawResponse accepted = rawPost(port, "/api/v1/delivery/requests", body,
                    "l14-contract-red");
            assertThat(accepted.status()).as("提交应 202：" + accepted.body()).isEqualTo(202);
            String taskId = json(accepted).path("task_id").asText();
            Map<String, Object> done = api.automation().wait(taskId, 30);
            assertThat(done.get("status")).isEqualTo("review");

            // C4 完整投影（红点③——简化投影缺 freshness/status.owner/integrity 即红）
            RawResponse detail = rawGet(port, "/api/v1/delivery/views/" + taskId);
            assertThat(detail.status()).isEqualTo(200);
            JsonNode view = json(detail);
            assertThat(view.path("schema").asText()).isEqualTo("workbench.delivery-view/v1");
            assertThat(view.path("task_id").asText()).isEqualTo(taskId);
            assertThat(view.path("status").path("code").asText()).isEqualTo("review");
            assertThat(view.path("status").path("owner").path("id").asText())
                    .isEqualTo("human:reviewer");
            assertThat(view.path("status").path("next_action").asText()).contains("具名");
            assertThat(view.path("eval").path("blocking_failed").asInt(-1)).isZero();
            assertThat(view.path("eval").path("report_path").asText()).startsWith("reports/");
            assertThat(view.path("eval").path("report_sha256").asText()).hasSize(64);
            assertThat(view.path("freshness").path("stale").asBoolean(true)).isFalse();
            assertThat(view.path("review").path("required").asBoolean(false)).isTrue();
            assertThat(view.path("review").path("reviewed_by").isMissingNode()
                    || view.path("review").path("reviewed_by").isNull()).isTrue();
            assertThat(view.path("feedback").path("total").asInt(-1)).isZero();
            assertThat(view.path("allowed_actions").toString()).contains("approve");
            assertThat(view.path("integrity").path("truthful").asBoolean(false)).isTrue();
            assertThat(view.path("integrity").path("issues").isArray()).isTrue();
            assertThat(view.path("links").path("self").asText())
                    .isEqualTo("/api/v1/delivery/views/" + taskId);
            assertThat(view.path("links").path("task").asText()).isEqualTo("/api/v1/tasks/" + taskId);
            assertThat(view.path("events").isArray()).isTrue();
            assertThat(view.path("events").size()).isPositive();
            assertThat(view.path("events").path(0).path("status").isObject()).isTrue();

            // C4 动作后完整投影：具名批准 → completed + reviewed_by + integrity 仍诚实
            Map<String, Object> reviewBody = Map.of("reviewer", "delivery-reviewer",
                    "decision", "approve", "note", "面板状态与库一致");
            RawResponse reviewed = rawPost(port, "/api/v1/tasks/" + taskId + "/review",
                    reviewBody, null);
            assertThat(reviewed.status()).isEqualTo(200);
            JsonNode completed = json(reviewed);
            assertThat(completed.path("schema").asText()).isEqualTo("workbench.delivery-view/v1");
            assertThat(completed.path("status").path("code").asText()).isEqualTo("completed");
            assertThat(completed.path("review").path("reviewed_by").asText())
                    .isEqualTo("delivery-reviewer");
            assertThat(completed.path("integrity").path("truthful").asBoolean(false)).isTrue();

            // C4 列表压缩与汇总：轻投影（task 无 spec/result/events；eval.results 空数组留 cases）
            RawResponse listing = rawGet(port, "/api/v1/delivery/views");
            assertThat(listing.status()).isEqualTo(200);
            JsonNode listBody = json(listing);
            assertThat(listBody.path("summary").path("total").asInt(-1)).isEqualTo(1);
            assertThat(listBody.path("summary").path("completed").asInt(-1)).isEqualTo(1);
            JsonNode item = listBody.path("items").path(0);
            assertThat(item.path("task").isObject()).isTrue();
            assertThat(item.path("task").has("spec")).isFalse();
            assertThat(item.path("task").has("result")).isFalse();
            assertThat(item.path("task").has("events")).isFalse();
            assertThat(item.path("eval").path("cases").isArray()).isTrue();
            assertThat(item.path("eval").path("results").isArray()).isTrue();
            assertThat(item.path("eval").path("results").size()).isZero();
            assertThat(item.path("eval").path("report_path").asText()).startsWith("reports/");
            assertThat(item.path("events").isArray()).isTrue();
            assertThat(item.path("events").size()).isZero();
        } finally {
            api.stop();
        }
    }

    @Test
    void l14ChecksMainUsageFace() {
        Cli.Result result = Cli.runMain("workbench.evals.l14.WebPanelChecks");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("--target");
    }

    @Test
    void l14ChecksRejectsUnknownCase() {
        Cli.Result result = Cli.runMain("workbench.evals.l14.WebPanelChecks",
                "--target", VENDOR_TARGET, "--no-report", "--case", "l14_nonexistent");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("l14_nonexistent");
    }

    @Test
    void l14ChecksDefaultGateGreen() {
        Cli.Result result = Cli.runMain("workbench.evals.l14.WebPanelChecks",
                "--target", VENDOR_TARGET, "--no-report");

        assertThat(result.exitCode()).as("默认门应全绿：" + result.stdout()).isZero();
        JsonNode report = Cli.json(result);
        assertThat(report.path("summary").path("decision").asText()).isEqualTo("pass");
        // 默认门八件（C1–C8）——投影三面 + HTTP/静态 + 面板静态断言 + 密钥扫描 + ERP 对账 + 冻结指纹
        assertThat(report.path("summary").path("total").asInt(-1)).isEqualTo(8);
        assertThat(report.path("summary").path("blocking_failed").asInt(-1)).isZero();
    }

    // ---- 便捷 -----------------------------------------------------------------------------------------------------

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException error) {
            throw new IllegalStateException("面板文件读取失败：" + file, error);
        }
    }

    private static JsonNode json(RawResponse response) {
        try {
            return MAPPER.readTree(response.body());
        } catch (IOException error) {
            throw new IllegalStateException("响应体不是 JSON：" + response.body(), error);
        }
    }

    private static String firstHeader(RawResponse response, String name) {
        return String.valueOf(response.headers().getOrDefault(name, List.of()).stream()
                .findFirst().orElse(""));
    }

    /** 进程内 handler 的 GET（同款 handler 起随机端口——spec 最高公开缝的进程内形态）。 */
    private static RawResponse rawGet(int port, String path) {
        try {
            HttpResponse<String> response = CLIENT.send(HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return new RawResponse(response.statusCode(), response.headers().map(), response.body());
        } catch (IOException error) {
            throw new IllegalStateException("HTTP GET 失败：" + path, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP GET 被中断：" + path, error);
        }
    }

    /** 进程内 handler 的 POST（JSON 体 + 可选 Idempotency-Key）。 */
    private static RawResponse rawPost(int port, String path, Map<String, Object> body,
            String idempotencyKey) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
            if (idempotencyKey != null) {
                request.header("Idempotency-Key", idempotencyKey);
            }
            HttpResponse<String> response = CLIENT.send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            return new RawResponse(response.statusCode(), response.headers().map(), response.body());
        } catch (IOException error) {
            throw new IllegalStateException("HTTP POST 失败：" + path, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP POST 被中断：" + path, error);
        }
    }
}
