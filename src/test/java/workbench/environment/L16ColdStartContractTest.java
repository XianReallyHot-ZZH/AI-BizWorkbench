package workbench.environment;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import workbench.testsupport.Cli;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L16 合同测试（起始红面二）：冷启动自检——讲义 docs/lessons/L16-冷启动与发布证据索引.md
 * §2 C4（workbench_increment[0]「冷启动」的安装面）。对照源 = 上游
 * {@code vendors/CodexFDE/workbench/environment_check.py}（55 行，只读）：
 * {@code scope:'installation'} 只查不修、逐项 {@code {name, ok, detail}}、
 * 顶层 {@code ok = all(checks)}、默认不查客户环境（{@code product_checked:false}，
 * --product 时才在客户自己的解释器内探针——本仓客户探针面经子进程 python，spec 阶段定形）。
 *
 * <p>接缝（讲义 D3/D4）：REGISTRY 注册缝——{@code environment-check}（上游 CLI 同名直承，
 * 无必填参数）。检查面六项 = D3 推荐清单的合同化（上游 python/venv/模块归属/web 件四面的
 * 本仓形态映射）：java（JDK 版本）/ compiled_classes（编译产物）/ dependency_classpath
 * （依赖清单落盘）/ golden_resources（golden 六套基准）/ web_panel_assets（面板三件）/
 * wb_wrapper（bin/wb 可执行）。
 *
 * <p>红点形态（commit 1）：REGISTRY 未注册 → {@code invalid choice} rc 2 → exitCode 断言红。
 * 实现转绿后本类零改动（红基线纪律）。
 *
 * <pre>
 * 用例 → 合同映射：
 * environmentCheckGreenReadOnlyFace   C4：净树全绿 rc 0 + scope installation + 逐项形状 + product_checked false
 * environmentCheckCoversColdStartFaces C4：六面检查名在场（D3 清单合同化）
 * environmentCheckUsageFace           C4：argv 面——未知旗标 rc 2
 * </pre>
 */
class L16ColdStartContractTest {

    /** D3 推荐清单合同化的六面检查名（spec 阶段可勘误，本类即合同）。 */
    private static final List<String> COLD_START_CHECKS = List.of(
            "java", "compiled_classes", "dependency_classpath", "golden_resources",
            "web_panel_assets", "wb_wrapper");

    @Test
    void environmentCheckGreenReadOnlyFace() {
        Cli.Result result = Cli.run("environment-check");

        assertThat(result.exitCode()).as("净树安装自检应 rc 0：" + result.stdout() + result.stderr())
                .isZero();
        JsonNode report = Cli.json(result);
        assertThat(report.path("ok").asBoolean()).as("mvn test 生命周期内六面应在场").isTrue();
        assertThat(report.path("scope").asText()).isEqualTo("installation");
        assertThat(report.path("product_checked").asBoolean(true))
                .as("默认不查客户环境").isFalse();
        JsonNode checks = report.path("checks");
        assertThat(checks.isArray()).isTrue();
        assertThat(checks.size()).isGreaterThanOrEqualTo(COLD_START_CHECKS.size());
        for (JsonNode check : checks) {
            assertThat(check.path("name").asText()).isNotEmpty();
            assertThat(check.path("ok").isBoolean()).isTrue();
            assertThat(check.path("detail").asText()).isNotEmpty();
        }
    }

    @Test
    void environmentCheckCoversColdStartFaces() {
        Cli.Result result = Cli.run("environment-check");

        assertThat(result.exitCode()).isZero();
        JsonNode checks = Cli.json(result).path("checks");
        for (String name : COLD_START_CHECKS) {
            boolean present = false;
            for (JsonNode check : checks) {
                present = present || name.equals(check.path("name").asText());
            }
            assertThat(present).as("冷启动自检应含检查项 " + name).isTrue();
        }
    }

    @Test
    void environmentCheckUsageFace() {
        Cli.Result result = Cli.run("environment-check", "--bogus-flag");

        assertThat(result.exitCode()).as("未知旗标应 rc 2").isEqualTo(2);
        assertThat(result.stdout() + result.stderr()).contains("--bogus-flag");
    }
}
