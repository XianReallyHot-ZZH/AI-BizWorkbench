package workbench.evals.l12;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import workbench.testsupport.Cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L12 审批入库面统一入口合同测试：讲义 docs/lessons/L12-显式状态图回退与具名人审.md
 * §2 C2/C3/C5/C6 → 用例映射（agent 家族第四件，无 Python 先例；映射源 = 上游
 * {@code purchase_approval_lab.py} 九模式 + 合同绑定两 eval，只读对照）。
 *
 * <p>接缝（spec docs/specs/L12-*.md 已具名确认）：独立 main 子进程缝
 * （ApprovalChecks）——两个新高缝之一。argv 面只锁合同（L08–L11 同款口径）；
 * 与 L09–L11 不同的一点：净树真实运行面（默认门全绿 + 两必红选跑）在本类经
 * {@code --target vendors/flowERP} 真跑承载——l05/l06 golden 重放先例（本地 mvn
 * 依赖 .venv 与 vendors，CI 只编译不跑 mvn test，CI_GATE_SPEC §门定义）。
 *
 * <p>两必红项语义（讲义 D4/D5）：登记项断言<b>理想不变量</b>（原子性 / 已入库必有
 * +7 流水），客户原样行为违反之 → 预期红留证（R1 两段事务窗口 / R4 已消费键静默吞
 * 的运行实证）——失败不是本仓缺陷，不修绿不删报告；{@code --case} 选跑承载，
 * 不进默认门（L11 premature-stock / L09 leak / L10 refuse-all 先例）。
 *
 * <p>红点组（commit 1，讲义 §3 步骤 2）：ApprovalChecks main 缺席 → rc 1
 * class not found，全部目标行为断言失败即红——九模式探针（经登记项场景调用）的
 * 缺席面由本 main 缝承载。实现转绿后本类零改动。
 *
 * <pre>
 * 用例 → 合同映射：
 * approvalChecksRequiresTarget              C2/C3 argv 面：缺 --target → rc 2
 * approvalChecksRejectsTargetWithoutFlowerp C2/C3 argv 面：target 无 flowerp/ → rc 2
 * approvalChecksRejectsUnknownCase          C2/C3 argv 面：未知名拒绝且不触发探针
 * approvalChecksDefaultGateGreenOnVendorTree C2/C3/C5/C6 默认门十件净树全绿（rc 0）
 * approvalChecksStatusWriteFailureRedByDesign C6 必红选跑：R1 部分提交实证（rc 1）
 * approvalChecksKeyCollisionRedByDesign     C6 必红选跑：R4 已消费键静默吞实证（rc 1）
 * </pre>
 */
class L12ApprovalChecksContractTest {

    /** 净树默认门：客户树 = vendors/flowERP（只读），python 缺省 .venv/bin/python（Cli cwd = 仓库根）。 */
    private static final String VENDOR_TARGET = "vendors/flowERP";

    @Test
    void approvalChecksRequiresTarget() {
        Cli.Result result = Cli.runMain("workbench.evals.l12.ApprovalChecks");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("--target");
    }

    @Test
    void approvalChecksRejectsTargetWithoutFlowerp(@TempDir Path tmp) throws IOException {
        Path empty = Files.createDirectories(tmp.resolve("empty"));
        Cli.Result result = Cli.runMain("workbench.evals.l12.ApprovalChecks",
                "--target", empty.toString(), "--python", "/usr/bin/true");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("flowerp");
    }

    @Test
    void approvalChecksRejectsUnknownCase(@TempDir Path tmp) throws IOException {
        Path target = Files.createDirectories(tmp.resolve("target"));
        Files.createDirectories(target.resolve("flowerp"));
        Cli.Result result = Cli.runMain("workbench.evals.l12.ApprovalChecks",
                "--target", target.toString(), "--case", "l12_nonexistent");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("l12_nonexistent");
    }

    @Test
    void approvalChecksDefaultGateGreenOnVendorTree() {
        Cli.Result result = Cli.runMain("workbench.evals.l12.ApprovalChecks",
                "--target", VENDOR_TARGET, "--no-report");

        assertThat(result.exitCode()).as("默认门应全绿：" + result.stdout()).isZero();
        JsonNode report = Cli.json(result);
        assertThat(report.path("summary").path("decision").asText()).isEqualTo("pass");
        // 默认门十件（七业务拍 + 两客户 eval 原名 + 冻结指纹）——两必红选跑项不在其中
        assertThat(report.path("summary").path("total").asInt(-1)).isEqualTo(10);
        assertThat(report.path("summary").path("blocking_failed").asInt(-1)).isZero();
        for (String absent : new String[]{"l12_status_write_failure", "l12_key_collision"}) {
            for (JsonNode row : report.path("results")) {
                assertThat(row.path("name").asText()).as("必红项不进默认门").isNotEqualTo(absent);
            }
        }
    }

    @Test
    void approvalChecksStatusWriteFailureRedByDesign() {
        Cli.Result result = Cli.runMain("workbench.evals.l12.ApprovalChecks",
                "--target", VENDOR_TARGET, "--no-report",
                "--case", "l12_status_write_failure");

        // 预期红留证（客户原样行为，非本仓缺陷）：R1 两段事务窗口——部分提交实录
        assertThat(result.exitCode()).isEqualTo(1);
        JsonNode row = Cli.json(result).path("results").path(0);
        assertThat(row.path("name").asText()).isEqualTo("l12_status_write_failure");
        assertThat(row.path("passed").asBoolean(true)).isFalse();
        assertThat(row.path("evidence").asText()).contains("R1").contains("部分提交");
    }

    @Test
    void approvalChecksKeyCollisionRedByDesign() {
        Cli.Result result = Cli.runMain("workbench.evals.l12.ApprovalChecks",
                "--target", VENDOR_TARGET, "--no-report",
                "--case", "l12_key_collision");

        // 预期红留证（客户原样行为，非本仓缺陷）：R4 已消费键静默吞——received 但无 +7
        assertThat(result.exitCode()).isEqualTo(1);
        JsonNode row = Cli.json(result).path("results").path(0);
        assertThat(row.path("name").asText()).isEqualTo("l12_key_collision");
        assertThat(row.path("passed").asBoolean(true)).isFalse();
        assertThat(row.path("evidence").asText()).contains("R4").contains("已消费键");
    }
}
