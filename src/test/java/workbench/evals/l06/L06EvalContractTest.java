package workbench.evals.l06;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import workbench.testsupport.Cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L06 工具面合同测试：讲义 §2 C1..C7 + 附录 A2 → 用例映射（Spec =
 * docs/lessons/L06-Harness分级判决.md §2 + 附录 A）。
 *
 * <p>映射表（验收项 → 测试名 → 实际操作 → 比较什么）。本类用例全部为目标能力缺失红点组
 * （Java 侧 evals/l06 三工具缺席；工具不进 REGISTRY，经 {@code Cli.runMain}/{@code Cli.runMainWithEnv}
 * 以独立 main 驱动——起始红无编译依赖，L04/L05 同法）；真树行为面与统一运行器报告面
 * （s07 绿报告 / s08 可信红报告全文）由 GoldenL06ReplayTest 字节锁，本类只断言语义 + 拒绝词面：
 *
 * <pre>
 * 红点组（目标能力缺失，commit 1 红）：
 * C1/C7/D2 → b1BuildsDefectBaselineWithSelfCheck → 构造器默认 b1（源 vendors/flowERP 真克隆）
 *            → rc 0 + 自检 JSON（defect_live/query_available 5/csv_available 8）
 *            + 补丁文本在场（CSV available 字段误写 on_hand）+ 原锚点缺席
 * D2        → b1RejectsExistingBaseline       → 预建基线目录 → rc 1 + stdout 空
 *            + stderr 词面「基线已存在，不覆盖」（词面断言使 commit 1 亦红——
 *            对照 golden s02 偶然同形，l05 修正注③ 同款）
 * 锚点守卫  → b1RejectsWhenAnchorNotUnique    → 合成双锚点 git 源经 --source 注入 → rc 1
 *            + stderr 词面「锚点命中 2 次」（l05 修正注① 先例：冻结 Python 侧 clone 源
 *            硬编码无注入缝，golden 不可构造，由本用例 discharge）
 * C1        → driverRejectsInvalidQuantityWindow → 真克隆树 3/3（0 &lt; reserved &lt; opening
 *            不成立）→ rc 1 + input 失败 JSON（step=input）
 * 参数面    → driverRejectsTargetWithoutFlowerp → --target 普通目录 → rc 2 + stdout 空
 *            + stderr 词面「--target 下没有 flowerp/」
 * 参数面    → driverRequiresTarget             → 缺 --target → rc 2 + stdout 空
 * C5        → checksRejectsTargetWithoutFlowerp → env 指向普通目录 → rc 1 + stdout 空
 *            + stderr 词面「L06_EVAL_TARGET 下没有 flowerp/」（env 注入经 runMainWithEnv——
 *            Checks 读环境变量是冻结 Python 语义，附录 A3/JD4）
 * </pre>
 *
 * <p>错误词面与冻结 Python 逐字同形（附录 A2/JD6：stderr 词面不入 golden，由本类与
 * 实跑证据链承载）。统一运行器九项分级语义与报告合同（ReportContract 8 类拒绝）因 Java
 * 编译绑定随实现同落 commit 2（EvalHarnessContractTest，进程内直调——Python 时代复查轮
 * S6 口径承袭；对应面的 commit 1 起始红由 golden s07–s09 工具级承载，Python 时代同位面
 * 为 collection error 红，§R 披露）。客户报告过 ReportContract 的交叉验证（D3 schema 1.0
 * 对齐机检）亦随 commit 2。客户 blocking eval 与证据链重演（红绿链 / 假绿探针 / 冻结指纹 /
 * 盲区对照）不在 mvn 面内，属候选期证据账（附录 A4 步骤 6）。
 */
class L06EvalContractTest {

    private static final String BUILDER_MAIN = "workbench.evals.l06.BuildDefectBaseline";
    private static final String DRIVER_MAIN = "workbench.evals.l06.StockConsistencyCheck";
    private static final String CHECKS_MAIN = "workbench.evals.l06.Checks";

    /** 冻结构造器同款锚点（service.py export_inventory CSV 模板 available 字段）。 */
    private static final String ANCHOR = "{row['available']}";

    @TempDir
    Path temp;

    @Test
    void b1BuildsDefectBaselineWithSelfCheck() throws Exception {
        String baseline = temp.resolve("b1").toString();
        Cli.Result result = Cli.runMain(BUILDER_MAIN, "--baseline", baseline);
        assertThat(result.exitCode()).as("b1 构造 rc").isZero();
        JsonNode node = Cli.json(result);
        assertThat(node.get("defect_live").asBoolean()).isTrue();
        assertThat(node.get("query_available").asInt()).isEqualTo(5);
        assertThat(node.get("csv_available").asInt()).isEqualTo(8);
        String patched = Files.readString(Path.of(baseline, "flowerp", "service.py"));
        assertThat(patched).as("CSV available 字段误写 on_hand（缺陷在场）")
                .contains("{row['on_hand']},{row['reserved']},{row['on_hand']}");
        assertThat(patched).doesNotContain(ANCHOR);
    }

    @Test
    void b1RejectsExistingBaseline() throws Exception {
        Path baseline = temp.resolve("b1-exists");
        Files.createDirectories(baseline);
        Cli.Result result = Cli.runMain(BUILDER_MAIN, "--baseline", baseline.toString());
        assertThat(result.exitCode()).as("基线已存在不覆盖 rc").isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("基线已存在，不覆盖");
    }

    @Test
    void b1RejectsWhenAnchorNotUnique() throws Exception {
        Path mutant = temp.resolve("mutant-source");
        Files.createDirectories(mutant.resolve("flowerp"));
        // 双锚点源：锚点命中 2 次 → 构造器拒绝动手（上游形状漂移守卫）
        Files.writeString(mutant.resolve("flowerp").resolve("service.py"),
                ANCHOR + "\n" + ANCHOR + "\n# synthetic double-anchor source\n");
        git(mutant, "init", "-q");
        git(mutant, "add", ".");
        git(mutant, "-c", "user.name=G", "-c", "user.email=g@example",
                "-c", "commit.gpgsign=false", "commit", "-q", "-m", "seed");
        Cli.Result result = Cli.runMain(BUILDER_MAIN,
                "--source", mutant.toString(), "--baseline", temp.resolve("fresh").toString());
        assertThat(result.exitCode()).as("锚点不唯一拒绝 rc").isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("锚点命中 2 次");
    }

    @Test
    void driverRejectsInvalidQuantityWindow() throws Exception {
        Path clean = temp.resolve("clean");
        git(temp, "clone", "--quiet", "--no-hardlinks",
                Cli.repoRoot().resolve("vendors/flowERP").toString(), "clean");
        Cli.Result result = Cli.runMain(DRIVER_MAIN, "--target", clean.toString(),
                "--opening", "3", "--reserved", "3");
        assertThat(result.exitCode()).as("输入窗口不成立 rc").isEqualTo(1);
        JsonNode node = Cli.json(result);
        assertThat(node.get("step").asText()).isEqualTo("input");
        assertThat(node.get("status").asText()).isEqualTo("fail");
    }

    @Test
    void driverRejectsTargetWithoutFlowerp() throws Exception {
        Path plain = temp.resolve("plain");
        Files.createDirectories(plain);
        Cli.Result result = Cli.runMain(DRIVER_MAIN, "--target", plain.toString());
        assertThat(result.exitCode()).as("--target 非客户树 rc").isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("--target 下没有 flowerp/");
    }

    @Test
    void driverRequiresTarget() throws Exception {
        Cli.Result result = Cli.runMain(DRIVER_MAIN);
        assertThat(result.exitCode()).as("缺 --target rc").isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    void checksRejectsTargetWithoutFlowerp() throws Exception {
        Path plain = temp.resolve("plain");
        Files.createDirectories(plain);
        Cli.Result result = Cli.runMainWithEnv(CHECKS_MAIN,
                Map.of("L06_EVAL_TARGET", plain.toString()), "--no-report");
        assertThat(result.exitCode()).as("env 非客户树 rc").isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("L06_EVAL_TARGET 下没有 flowerp/");
    }

    private static void git(Path dir, String... argv) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(argv));
        Process process = new ProcessBuilder(command).start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git 夹具失败：" + String.join(" ", command));
        }
    }
}
