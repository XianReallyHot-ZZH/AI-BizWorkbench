package workbench.golden;

import org.junit.jupiter.api.Test;
import workbench.testsupport.GoldenReplay;

import java.util.List;

/**
 * golden 字节对照（ADR-0006 验收门增量；方案 = L06 讲义附录 A3）。
 *
 * <p>按 {@code golden/l06/manifest.json} 重放 9 场景，锁定 L06 三工具（CSV 缺陷基线
 * 构造器 / 口径交叉检查驱动 / 五登记项收口 Checks）的 stdout 契约——本套首次含
 * <b>统一运行器全量报告</b>（s07 绿 / s08 可信红 / s09 缺 env 全项失败报告：checks 的
 * stdout 即报告全文；报告文件不入 golden，文件面/x 模式由合同测试锁）。<b>commit 1 红
 * 是本讲预期红点</b>（工具 main 缺失：s01/s03/s04/s07 退出码错位即败，s09 期望报告 vs
 * 空 stdout 亦真红；唯 s02（rc 1 + 空 stdout）与「主类缺失」偶然同形可能不红——
 * l05 修正注③ 同款口径，红点由其余场景与 L06EvalContractTest 承载）。
 *
 * <p>与 l05 两点差异（附录 A3/JD3/JD4）：① 掩码集首例扩展 2 键（{@code generated_at}→
 * {@code <TS>}、{@code duration_ms}→{@code <MS>}，按 manifest normalization 声明数据驱动，
 * s07/s08 报告机器时间字段）；② s07/s08 经场景 env 字段注入 {@code L06_EVAL_TARGET}
 * （Checks 读环境变量是冻结 Python 语义，argv 面不含 --target；共享件 env 字段/
 * runMainWithEnv 扩展行为零变化证据见 evidence/L06-java.md §G J1/J2）。缺陷树 ws/b1 由
 * s01 场景自身构造（生成侧 = 冻结 Python 构造器，重放侧 = Java 构造器——双侧树等价的
 * 第一重锁；无 l05 盲区树式预物化），清场经 scratch 清单即 {@code replay()}。
 *
 * <p>golden 由冻结 Python evals 统一运行器面采出（evidence/L06-java.md §G，双跑指纹
 * f0e0ab777e946e8bfa7d5bae2388377e0c9c649d2d3b3a3e671e8b885060f5a8），
 * 生成后永不手改。
 */
class GoldenL06ReplayTest {

    @Test
    void replayAllScenariosByteIdentically() throws Exception {
        GoldenReplay.replay("/golden/l06/manifest.json", List.of(".runtime/golden-l06"));
    }
}
