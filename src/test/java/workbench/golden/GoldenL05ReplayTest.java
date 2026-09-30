package workbench.golden;

import org.junit.jupiter.api.Test;
import workbench.testsupport.Cli;
import workbench.testsupport.GoldenReplay;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * golden 字节对照（ADR-0006 验收门增量；方案 = L05 讲义附录 A3）。
 *
 * <p>按 {@code golden/l05/manifest.json} 重放 7 场景，锁定 L05 两工具（缺陷基线构造器 /
 * 收货场景驱动）的 stdout 契约——本套重放的正是本讲目标能力（Java 侧 eval 工具面），
 * <b>commit 1 红是本讲预期红点</b>（工具 main 缺失：物化步即败），实现后转绿即
 * 字节级对照达成。s02（rc 1 + 空 stdout）与"主类缺失"偶然同形，可能不红——红点由
 * 其余场景承载（附录 A3 修正注③）。
 *
 * <p>盲区树（ws/blind）先于 {@link GoldenReplay#replay} 物化：生成侧对应物 = 生成器
 * 内联手术（同一 JD4 补丁规格），此处执行被测 Java 构造器 {@code --defect blind}，
 * 树的盲区性质由 s06 驱动 fail 输出反向锁定；因此 replay() 传**空 scratch 清单**——
 * 首步清场不得抹掉盲区树（附录 A3 修正注②），清场由本测试先行完成。
 *
 * <p>掩码按 manifest normalization 声明数据驱动（本套零新掩码：工具 stdout 无机器
 * 时间戳/绝对路径，baseline 字段固定相对路径规避）；setup 块物化 ws/clean 与
 * ws/no-flowerp（与 Python 生成器同一规格）；场景 main 字段驱动 Java 工具入口
 * （共享件 runMain 扩展，行为零变化证据见 evidence/L05-java.md §G J1/J3）。
 * golden 由冻结 Python evals/l05 工具采出（evidence/L05-java.md §G，双跑指纹
 * 3d9b623c654bff89b88d45a716768a506812db46b176e9edf2c8c77977c8e576），
 * 生成后永不手改。
 */
class GoldenL05ReplayTest {

    @Test
    void replayAllScenariosByteIdentically() throws Exception {
        GoldenReplay.deleteRecursively(Cli.repoRoot().resolve(".runtime/golden-l05"));
        Cli.Result blind = Cli.runMain("workbench.evals.l05.BuildDefectBaseline",
                "--defect", "blind", "--baseline", ".runtime/golden-l05/ws/blind");
        assertThat(blind.exitCode()).as("盲区树物化（Java 构造器 blind 模式，s06 前置）").isZero();
        GoldenReplay.replay("/golden/l05/manifest.json", List.of());
    }
}
