package workbench.evals.l16;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import workbench.testsupport.Cli;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L16Checks 独立 main 的 argv/门面合同测试（l15 l15ChecksMainUsageFace 族形——实现期
 * 增量测试，非 commit 1 起始红基线：本类随 Checks 实现落地，讲义预测红点为两面
 * 〔release-index/environment-check〕，Checks 面属实现面测试，证据账如实区分）。
 *
 * <pre>
 * 用例 → 合同映射：
 * l16ChecksMainUsageFace     缺 --target → rc 2
 * l16ChecksRejectsUnknownCase 未知名拒绝 rc 2
 * l16ChecksDefaultGateGreen  七件默认门净树全绿（rc 0 + decision pass + total 7）
 * </pre>
 */
class L16ChecksContractTest {

    /** 净树默认门：客户树 = vendors/flowERP（只读），python 缺省 .venv/bin/python。 */
    private static final String VENDOR_TARGET = "vendors/flowERP";

    @Test
    void l16ChecksMainUsageFace() {
        Cli.Result result = Cli.runMain("workbench.evals.l16.L16Checks");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("--target");
    }

    @Test
    void l16ChecksRejectsUnknownCase() {
        Cli.Result result = Cli.runMain("workbench.evals.l16.L16Checks",
                "--target", VENDOR_TARGET, "--no-report", "--case", "l16_nonexistent");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("l16_nonexistent");
    }

    @Test
    void l16ChecksDefaultGateGreen() {
        Cli.Result result = Cli.runMain("workbench.evals.l16.L16Checks",
                "--target", VENDOR_TARGET, "--no-report");

        assertThat(result.exitCode()).as("默认门应全绿：" + result.stdout()).isZero();
        JsonNode report = Cli.json(result);
        assertThat(report.path("summary").path("decision").asText()).isEqualTo("pass");
        // 默认门七件（spec Implementation Decisions 定形）——发布索引/冷启动/抽取时序
        // /动态起始红/三态/绑定 eval 复验/冻结指纹
        assertThat(report.path("summary").path("total").asInt(-1)).isEqualTo(7);
        assertThat(report.path("summary").path("blocking_failed").asInt(-1)).isZero();
    }
}
