package workbench.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import workbench.testsupport.Cli;
import workbench.testsupport.Server;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L13 任务 API 合同测试：讲义 docs/lessons/L13-任务API与异步交付.md §2 C 表 →
 * 用例映射（operate 阶段首讲 + 检查点自建讲义首例；无 Python 先例；映射源 = 上游
 * 绑定 eval 断言面 {@code eval/cases.py:262-333} + {@code eval/task_api_contract.py}
 * + {@code tests/test_course_task_http.py}，只读对照）。
 *
 * <p>接缝（spec docs/specs/L13-*.md 已具名确认）：本类三面全部经子进程——
 * ①REGISTRY 注册缝（{@code delivery-serve} 常驻服务，经 Server 支撑件起真实端口）
 * ②DeliveryChecks 独立 main 子进程缝（l09–l12 Checks 既有族形的新件）。
 * 工作台面八件登记项的机检在 Checks 内进程内承载（合成 suite 经 suite_runner 缝
 * 注入——上游 fixture 防递归同理，如实标注），本类只锁公开面：注册、argv、默认门。
 *
 * <p>红点组（commit 1，讲义 §3 步骤 2）：{@code delivery-serve} 未注册 → rc 2
 * invalid choice；DeliveryChecks main 缺席 → rc 1 class not found——三面全红。
 * 实现转绿后本类零改动。
 *
 * <pre>
 * 用例 → 合同映射：
 * deliveryServeUsageFace              C1/D2 argv 面：缺 --runtime-dir → rc 2 usage（非 invalid choice）
 * deliveryServeRegisteredAndCapabilities C1 注册面：capabilities 200 + verify-only + 幂等键要求
 * deliveryChecksMainUsageFace         C7 argv 面：缺 --target → rc 2
 * deliveryChecksRejectsUnknownCase    C7 argv 面：未知名拒绝 rc 2
 * deliveryChecksDefaultGateGreen      C1–C8 默认门十件净树全绿（rc 0）
 * deliveryChecksPurchaseCaseGreen     C7 客户名原样照跑单选绿（rc 0）
 * </pre>
 */
class L13DeliveryContractTest {

    /** 净树默认门：客户树 = vendors/flowERP（只读），python 缺省 .venv/bin/python（l12 同口径）。 */
    private static final String VENDOR_TARGET = "vendors/flowERP";

    @Test
    void deliveryServeUsageFace() {
        Cli.Result result = Cli.run("delivery-serve");

        // 已注册缺参 = Args.UsageException rc 2（usage 词面）；未注册 = invalid choice（红面词面）
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout())
                .doesNotContain("invalid choice")
                .contains("delivery-serve");
    }

    @Test
    void deliveryServeRegisteredAndCapabilities(@TempDir Path runtime) {
        try (Server server = Server.start(runtime)) {
            Server.Response response = server.get("/api/v1/delivery/capabilities");
            assertThat(response.statusCode()).as("capabilities 应 200：" + response.body()).isEqualTo(200);
            JsonNode body = server.json(response);
            assertThat(body.path("surface").asText()).isEqualTo("workbench");
            assertThat(body.path("async_submission").asBoolean(false)).isTrue();
            assertThat(body.path("execution_modes").toString()).contains("verify");
            assertThat(body.path("requires_idempotency_key").asBoolean(false)).isTrue();
        }
    }

    @Test
    void deliveryChecksMainUsageFace() {
        Cli.Result result = Cli.runMain("workbench.evals.l13.DeliveryChecks");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("--target");
    }

    @Test
    void deliveryChecksRejectsUnknownCase() {
        Cli.Result result = Cli.runMain("workbench.evals.l13.DeliveryChecks",
                "--target", VENDOR_TARGET, "--no-report", "--case", "l13_nonexistent");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("l13_nonexistent");
    }

    @Test
    void deliveryChecksDefaultGateGreen() {
        Cli.Result result = Cli.runMain("workbench.evals.l13.DeliveryChecks",
                "--target", VENDOR_TARGET, "--no-report");

        assertThat(result.exitCode()).as("默认门应全绿：" + result.stdout()).isZero();
        JsonNode report = Cli.json(result);
        assertThat(report.path("summary").path("decision").asText()).isEqualTo("pass");
        // 默认门十件（C1–C8）——工作台面八件 + 客户 eval 原名照跑 + 冻结指纹
        assertThat(report.path("summary").path("total").asInt(-1)).isEqualTo(10);
        assertThat(report.path("summary").path("blocking_failed").asInt(-1)).isZero();
    }

    @Test
    void deliveryChecksPurchaseCaseGreen() {
        Cli.Result result = Cli.runMain("workbench.evals.l13.DeliveryChecks",
                "--target", VENDOR_TARGET, "--no-report", "--case", "purchase_requires_approval");

        // 客户名原样照跑单选绿（业务权威 ADR-0005；l12 先例同族）
        assertThat(result.exitCode()).as("客户 eval 应绿：" + result.stdout()).isZero();
        JsonNode row = Cli.json(result).path("results").path(0);
        assertThat(row.path("name").asText()).isEqualTo("purchase_requires_approval");
        assertThat(row.path("passed").asBoolean(true)).isTrue();
    }
}
