package workbench.golden;

import org.junit.jupiter.api.Test;
import workbench.testsupport.GoldenReplay;

import java.util.List;

/**
 * golden 字节对照（ADR-0006 验收门增量；方案 = L02 讲义附录 A3）。
 *
 * <p>按 {@code golden/l02/manifest.json} 重放 15 场景，锁定 L02 流的工作台增量语义：
 * observation 绿后导入不参与链判定、不破坏链（t09/t10），observation 不满足红绿链要求
 * （t15）——均为 l01 golden 未覆盖的面。数据驱动同 {@link GoldenL01ReplayTest}（共享
 * {@link GoldenReplay}），golden 由冻结 Python 工作台采出（evidence/L02-java.md §G），
 * 生成后永不手改。就位类验收：Java bootstrap 已实现该语义（L01-java 交付），本重放
 * 在 commit 1 即应绿——它锁定的是语义面而非新增能力。
 */
class GoldenL02ReplayTest {

    @Test
    void replayAllScenariosByteIdentically() throws Exception {
        GoldenReplay.replay("/golden/l02/manifest.json", List.of(
                ".runtime/golden-l02/rt",
                ".runtime/golden-l02/inputs"));
    }
}
