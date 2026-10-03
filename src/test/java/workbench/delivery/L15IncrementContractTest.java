package workbench.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import workbench.testsupport.Cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L15 增量面合同测试：讲义 docs/lessons/L15-反馈治理与记忆演进.md §2 C6/C7 →
 * 用例映射（对照源 = 上游 {@code workbench/delivery_view.py:281-284,345} evolution
 * 块断言面——**L14 D4 裁掉处的预埋补块**（「L15 按断言面增量补块」兑现）+
 * L15Checks 独立 main（l07–l14 Checks 族形，十件默认门，spec 定形）。
 *
 * <p>接缝（spec 已具名）：零新缝——HttpApi 进程内面（L14 同款 handler 起随机端口，
 * 合成绿 suite 经 suite_runner 缝注入——上游 fixture 防递归同理）+ Checks 独立
 * main 子进程缝。L14 列表八计数 → 九计数（verified_evolutions 恢复上游原状）属
 * 合同演化：L14 合同测试随实现增量更新、diff 留证据账（讲义 D4）。
 *
 * <p>红点组（commit 1）：DeliveryView 现状无 evolution 块 → JSON 断言 total 缺席
 * 即红（可编译，不需类缺席）；L15Checks main 缺席 → 子进程 rc 1 ×3 即红。
 * 实现转绿后本类零改动（红基线纪律）。
 *
 * <pre>
 * 用例 → 合同映射：
 * viewEvolutionBlockFace     C6：完整投影 evolution 块 {total, verified, items} +
 *                             列表第九计数 verified_evolutions（上游 :281-284/:345）
 * l15ChecksMainUsageFace     C7/C9 argv 面：缺 --target → rc 2
 * l15ChecksRejectsUnknownCase C7/C9 argv 面：未知名拒绝 rc 2
 * l15ChecksDefaultGateGreen  C4–C9 默认门十件净树全绿（rc 0 + decision pass + total 10）
 * </pre>
 */
class L15IncrementContractTest {

    /** 净树默认门：客户树 = vendors/flowERP（只读），python 缺省 .venv/bin/python（l13/l14 同口径）。 */
    private static final String VENDOR_TARGET = "vendors/flowERP";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test
    void viewEvolutionBlockFace(@TempDir Path runtime) throws Exception {
        HttpApi api = new HttpApi(runtime, () -> {
            Map<String, Object> summary = Map.of("decision", "pass", "blocking_failed", 0,
                    "blocking_passed", 1);
            Map<String, Object> row = Map.of("name", "purchase_requires_approval", "level",
                    "blocking", "passed", true);
            Map<String, Object> report = new LinkedHashMap<>();
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
            // 提交（lesson 15 合同面）→ 202 → review（合成绿 suite——机检缝，上游 fixture 同理）
            Map<String, Object> body = Map.of("lesson", 15, "request", "反馈治理增量复验",
                    "actor", "l15-student", "business_refs", List.of("REQUIREMENT:COURSE-L15"));
            HttpResponse<String> accepted = post(port, "/api/v1/delivery/requests", body,
                    "l15-increment-red");
            assertThat(accepted.statusCode()).as("提交应 202：" + accepted.body()).isEqualTo(202);
            String taskId = MAPPER.readTree(accepted.body()).path("task_id").asText();
            Map<String, Object> done = api.automation().wait(taskId, 30);
            assertThat(done.get("status")).isEqualTo("review");

            // C6 完整投影 evolution 块（红点——现状无块即红；空库块 = total 0 + verified 0）
            HttpResponse<String> detail = get(port, "/api/v1/delivery/views/" + taskId);
            assertThat(detail.statusCode()).isEqualTo(200);
            JsonNode view = MAPPER.readTree(detail.body());
            assertThat(view.path("schema").asText()).isEqualTo("workbench.delivery-view/v1");
            assertThat(view.path("evolution").path("total").asInt(-1))
                    .as("完整投影应含 evolution 块（L14 D4 预埋补块）").isZero();
            assertThat(view.path("evolution").path("verified").asInt(-1)).isZero();
            assertThat(view.path("evolution").path("items").isArray()).isTrue();

            // C6 列表第九计数 verified_evolutions（上游 :345——八计数→九计数恢复原状）
            HttpResponse<String> listing = get(port, "/api/v1/delivery/views");
            assertThat(listing.statusCode()).isEqualTo(200);
            JsonNode summaryNode = MAPPER.readTree(listing.body()).path("summary");
            assertThat(summaryNode.path("verified_evolutions").asInt(-1))
                    .as("列表汇总应含第九计数 verified_evolutions").isZero();
            assertThat(summaryNode.path("pending_feedback").asInt(-1)).isZero();
            assertThat(summaryNode.path("total").asInt(-1)).isOne();
        } finally {
            api.stop();
        }
    }

    @Test
    void l15ChecksMainUsageFace() {
        Cli.Result result = Cli.runMain("workbench.evals.l15.L15Checks");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("--target");
    }

    @Test
    void l15ChecksRejectsUnknownCase() {
        Cli.Result result = Cli.runMain("workbench.evals.l15.L15Checks",
                "--target", VENDOR_TARGET, "--no-report", "--case", "l15_nonexistent");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("l15_nonexistent");
    }

    @Test
    void l15ChecksDefaultGateGreen() {
        Cli.Result result = Cli.runMain("workbench.evals.l15.L15Checks",
                "--target", VENDOR_TARGET, "--no-report");

        assertThat(result.exitCode()).as("默认门应全绿：" + result.stdout()).isZero();
        JsonNode report = Cli.json(result);
        assertThat(report.path("summary").path("decision").asText()).isEqualTo("pass");
        // 默认门十件（spec Implementation Decisions 定形）——治理四件 + 记忆三件
        // + 投影补块 + 动态面 + ERP 升级前后 + 冻结指纹
        assertThat(report.path("summary").path("total").asInt(-1)).isEqualTo(10);
        assertThat(report.path("summary").path("blocking_failed").asInt(-1)).isZero();
    }

    // ---- 便捷 -----------------------------------------------------------------------------------------------------

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(int port, String path, Map<String, Object> body,
            String idempotencyKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
