package workbench.golden;

import org.junit.jupiter.api.Test;
import workbench.testsupport.GoldenReplay;

import java.util.List;

/**
 * golden 字节对照（ADR-0006 验收门增量；方案 = L01 讲义附录 A3）。
 *
 * <p>按 {@code golden/l01/manifest.json} 重放 22 场景：同一会话序、同一 argv、同一输入
 * 内容（全部来自 manifest，测试不自带文本，杜绝漂移）。Java 输出经**独立**规范化器
 * （{@code Cli#normalize}，按 manifest 的 normalization 块实现）后与 golden 逐字节一致。
 * golden 由冻结 Python 工作台采出（evidence/L01-java.md §G），生成后永不手改。
 *
 * <p>重放循环已抽 {@link GoldenReplay} 共享支撑（L02-java 重走引入 l02 golden 时重构，
 * 行为零变化：本类仍重放 l01 的 22 场景，清场目录与此前一致）。
 */
class GoldenL01ReplayTest {

    @Test
    void replayAllScenariosByteIdentically() throws Exception {
        GoldenReplay.replay("/golden/l01/manifest.json", List.of(
                ".runtime/golden-l01/rt",
                ".runtime/golden-l01/rt-empty",
                ".runtime/golden-l01/inputs"));
    }
}
