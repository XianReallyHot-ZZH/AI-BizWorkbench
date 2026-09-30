package workbench.evals.l05;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import workbench.testsupport.Cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L05 合同测试：讲义 §2 C1..C7 → 用例映射（Spec = docs/lessons/L05-失败优先Eval.md §2 + 附录 A2）。
 *
 * <p>映射表（验收项 → 测试名 → 实际操作 → 比较什么）。本类用例全部为目标能力缺失红点组
 * （Java 侧 evals/l05 两工具缺席；工具不进 REGISTRY，经 {@code Cli.runMain} 以独立 main
 * 驱动——起始红无编译依赖，L04 同法）；真树行为面（驱动 pass/fail 三树、构造自检字节形）
 * 由 GoldenL05ReplayTest 字节锁，本类只断言语义 + 拒绝词面：
 *
 * <pre>
 * 红点组（目标能力缺失，commit 1 红）：
 * C3/D2  → b1BuildsDefectBaselineWithSelfCheck → 构造器默认 b1（源 vendors/flowERP 真克隆）→ rc 0
 *          + 自检 JSON（defect_live/on_hand 10/events 1）+ baseline 原样回显
 *          + 补丁文本在场（UPDATE stock SET on_hand=on_hand+?）
 * D2     → b1RejectsExistingBaseline            → 预建基线目录 → rc 1 + stdout 空
 *          + stderr 词面「基线已存在，不覆盖」（防覆盖纪律；词面断言使 commit 1 亦红——
 *          对照 golden s02 偶然同形，附录 A3 修正注③）
 * 修正注①→ b1RejectsWhenAnchorNotUnique         → 合成双锚点 git 源经 --source 注入 → rc 1
 *          + stderr 词面「锚点命中 2 次」（锚点守卫：冻结 Python 侧 clone 源硬编码无注入缝，
 *          golden 不可构造，由本用例 discharge）
 * C7/JD4 → blindBuildsRewriteTreeToSpec         → --defect blind 真克隆 → rc 0 + 自检 JSON
 *          （defect blind/on_hand 5/old_key_events 0/rewritten_key_events 1）
 *          + 补丁文本形状（DELETE 与 "-rewritten" 与 INSERT 在场；b1 的 UPDATE stock 缺席）
 *          ——JD4 补丁规格等价性 discharge（生成器内联手术 ↔ Java 构造器 blind 模式）
 * 参数面  → driverRejectsTargetWithoutFlowerp   → --target 普通目录 → rc 2 + stdout 空
 *          + stderr 词面「--target 下没有 flowerp/」
 * 参数面  → driverRequiresTarget                → 缺 --target → rc 2 + stdout 空
 * </pre>
 *
 * <p>错误词面与冻结 Python 逐字同形（附录 A2/JD6：stderr 词面不入 golden，由本类与
 * 实跑证据链承载）。客户 blocking eval（receiving_is_idempotent）与证据链重演（两连红 /
 * 同命令红绿链 / 冻结指纹 / 盲区对照）不在 mvn 面内，属候选期证据账（附录 A4 步骤 6）。
 */
class L05EvalContractTest {

    private static final String BUILDER_MAIN = "workbench.evals.l05.BuildDefectBaseline";
    private static final String DRIVER_MAIN = "workbench.evals.l05.ReceivingScenarioCheck";

    /** 冻结构造器同款锚点（service.py 门面重放分支，12 空格缩进两行）。 */
    private static final String ANCHOR = "            if exists:\n                row = conn.execute(";

    @TempDir
    Path temp;

    @Test
    void b1BuildsDefectBaselineWithSelfCheck() throws Exception {
        String baseline = temp.resolve("b1").toString();
        Cli.Result result = Cli.runMain(BUILDER_MAIN, "--baseline", baseline);
        assertThat(result.exitCode()).as("b1 构造 rc").isZero();
        JsonNode node = Cli.json(result);
        assertThat(node.get("baseline").asText()).as("baseline 原样回显").isEqualTo(baseline);
        assertThat(node.get("defect_live").asBoolean()).isTrue();
        assertThat(node.get("on_hand").asInt()).isEqualTo(10);
        assertThat(node.get("events").asInt()).isEqualTo(1);
        assertThat(Files.readString(Path.of(baseline, "flowerp", "service.py")))
                .contains("UPDATE stock SET on_hand=on_hand+?");
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
    void blindBuildsRewriteTreeToSpec() throws Exception {
        String baseline = temp.resolve("blind").toString();
        Cli.Result result = Cli.runMain(BUILDER_MAIN, "--defect", "blind", "--baseline", baseline);
        assertThat(result.exitCode()).as("盲区树构造 rc").isZero();
        JsonNode node = Cli.json(result);
        assertThat(node.get("baseline").asText()).isEqualTo(baseline);
        assertThat(node.get("defect").asText()).isEqualTo("blind");
        assertThat(node.get("defect_live").asBoolean()).isTrue();
        assertThat(node.get("on_hand").asInt()).as("库存返回正确（盲区不动库存）").isEqualTo(5);
        assertThat(node.get("old_key_events").asInt()).as("旧键被删").isZero();
        assertThat(node.get("rewritten_key_events").asInt()).as("新键在账").isEqualTo(1);
        String patched = Files.readString(Path.of(baseline, "flowerp", "service.py"));
        assertThat(patched).contains("DELETE FROM inventory_events WHERE event_key=?");
        assertThat(patched).contains("\"-rewritten\"");
        assertThat(patched).contains(
                "INSERT INTO inventory_events(event_key,sku,quantity,reserved_delta,event_type,reference)");
        assertThat(patched).as("盲区缺陷不是 b1（不动库存）").doesNotContain("UPDATE stock SET on_hand=on_hand+");
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

    private static void git(Path dir, String... argv) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(argv));
        Process process = new ProcessBuilder(command).start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git 夹具失败：" + String.join(" ", command));
        }
    }
}
