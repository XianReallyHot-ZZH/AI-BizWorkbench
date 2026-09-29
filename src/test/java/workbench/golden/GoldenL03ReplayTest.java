package workbench.golden;

import org.junit.jupiter.api.Test;
import workbench.testsupport.GoldenReplay;

import java.util.List;

/**
 * golden 字节对照（ADR-0006 验收门增量；方案 = L03 讲义附录 A3）。
 *
 * <p>按 {@code golden/l03/manifest.json} 重放 11 场景，锁定 spec 命令的结构闸门行为
 * （六类拒绝词面 + 围栏语义 + 三份冻结输入的通过/拒绝/结构通过态）——本套重放的
 * 正是 L03 目标能力，与 l01/l02（既有账本能力、commit 1 即绿）不同：<b>commit 1 红
 * 是本讲预期红点</b>（移植注记 A1/A2），实现后转绿即字节级对照达成。数据驱动同
 * {@link GoldenL01ReplayTest}/{@link GoldenL02ReplayTest}（共享 {@link GoldenReplay}），
 * golden 由冻结 Python 工作台采出（evidence/L03-java.md §G，双跑指纹 f5c7a611…），
 * 生成后永不手改。spec 命令不触账本：无 tamper/assert_db_absent 标记，scratch 仅
 * inputs 目录。
 */
class GoldenL03ReplayTest {

    @Test
    void replayAllScenariosByteIdentically() throws Exception {
        GoldenReplay.replay("/golden/l03/manifest.json", List.of(
                ".runtime/golden-l03/inputs"));
    }
}
