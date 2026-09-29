package workbench.golden;

import org.junit.jupiter.api.Test;
import workbench.testsupport.GoldenReplay;

import java.util.List;

/**
 * golden 字节对照（ADR-0006 验收门增量；方案 = L04 讲义附录 A3）。
 *
 * <p>按 {@code golden/l04/manifest.json} 重放 47 场景，锁定受控执行三命令的行为语义
 * （启动拒绝 11 面 / code 正常执行全量记录形 / 复核门 9 面 / code 异常四态 + eval 超时
 * 可分辨 / verify 面 / task-show 与 status 投影 + 篡改检出）——本套重放的正是 L04 目标
 * 能力（V0），与 l01/l02（既有账本能力、commit 1 即绿）同 l03 型：<b>commit 1 红是本讲
 * 预期红点</b>（移植注记 A1/A2），实现后转绿即字节级对照达成。
 *
 * <p>掩码按 manifest normalization 声明数据驱动（l04 扩展：observed_at/reviewed_at
 * 系机器生成 → &lt;TS&gt;；workspace 含机器绝对路径 → &lt;WORKSPACE&gt;；共享件扩展
 * 行为零变化证据见 evidence/L04-java.md §G J1/J2）；setup 块物化 7 个种子候选仓 +
 * nogit/nohead 夹具（与 Python 生成器同一规格）。golden 由冻结 Python 工作台采出
 * （evidence/L04-java.md §G，双跑指纹 5b7ebbcdb44ca977f7bf451da53862c30f4cffb494fecf9316aceb5efd293048），
 * 生成后永不手改。s47 含 tamper_before（status 摘要复核锁定）。
 */
class GoldenL04ReplayTest {

    @Test
    void replayAllScenariosByteIdentically() throws Exception {
        GoldenReplay.replay("/golden/l04/manifest.json", List.of(".runtime/golden-l04"));
    }
}
