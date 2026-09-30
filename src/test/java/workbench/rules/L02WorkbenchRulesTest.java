package workbench.rules;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L02 合同测试：CLAUDE.md 规则要素在场（文本层；行为层由 N0/N1 会话对照承担）。
 *
 * <p>验收项 → 测试名 → 实际操作 → 比较什么（Java 重走翻译自 tests/test_l02_workbench_rules.py，
 * 语义组式断言、不锁文风；文本在场 ≠ 行为遵守——三层分检）
 * <ul>
 *   <li>C3 → fiveBusinessInvariantsPresent → 读根目录 CLAUDE.md → 五条 FlowERP 业务边界逐条有语义要素</li>
 *   <li>C3 → invariantsCarryApplicability → 读根目录 CLAUDE.md → 边界带适用条件（涉及 FlowERP 业务实现时）</li>
 *   <li>C2 → structureConventionPresent → 读根目录 CLAUDE.md → 调用方向约定与新命令复用语义在场</li>
 *   <li>C5 → definitionOfDonePresent → 读根目录 CLAUDE.md → 完成定义要素（交回物/正反路径/未实现不冒充）</li>
 *   <li>C4 → referencedCommandsAreReal → CLAUDE.md 的 Python 侧验证入口字面量 → 被引用物真实存在</li>
 * </ul>
 *
 * <p>起始红口径（移植注记附录 A2）：本类五用例为**就位即绿的回归护栏**（既有规则文本在 master
 * 已在场，重走零重写）——先例：Python L02 ReferencedCommandsAreReal 起始红时即绿、映射表声明。
 * 本讲红点集中在 {@link JavaLineRuleFactsTest}（CLAUDE.md 缺 Java 重走线规则要素）。
 */
class L02WorkbenchRulesTest {

    @Test
    void fiveBusinessInvariantsPresent() {
        String text = RuleText.rulesText();
        List<List<String[]>> perInvariant = List.of(
                // 库存非负 + 原子预占
                RuleText.missingGroups(text, new String[] {"库存"}, new String[] {"不能为负", "不为负", "永不为负", "非负"},
                        new String[] {"预占"}, new String[] {"原子"}),
                // 同一幂等键只生效一次
                RuleText.missingGroups(text, new String[] {"幂等"}, new String[] {"一次"}),
                // 状态机迁移 + 取消释放预占
                RuleText.missingGroups(text, new String[] {"状态机"}, new String[] {"取消"}, new String[] {"释放"}),
                // 具名审批后才入库
                RuleText.missingGroups(text, new String[] {"审批"}, new String[] {"入库"},
                        new String[] {"具名", "批准", "人工批准"}),
                // 记录可追溯，失败不伪装成成功
                RuleText.missingGroups(text, new String[] {"可追溯"}, new String[] {"失败"},
                        new String[] {"不能显示成成功", "不伪装", "不能伪装", "不得把失败", "失败不能"}));
        for (int i = 0; i < perInvariant.size(); i++) {
            assertThat(perInvariant.get(i)).as("业务边界第 %d 条语义要素缺席组数", i + 1).isEmpty();
        }
    }

    @Test
    void invariantsCarryApplicability() {
        // 适用条件：边界是给"涉及 FlowERP 业务实现"的场景预埋的，不是日常文档改动的负担
        assertThat(Pattern.compile("涉及.{0,8}[Ff]lowERP.{0,12}业务").matcher(RuleText.rulesText()).find())
                .as("边界适用条件（涉及 FlowERP 业务实现时）在场").isTrue();
    }

    @Test
    void structureConventionPresent() {
        assertThat(RuleText.missingGroups(RuleText.rulesText(),
                new String[] {"调用方向", "调用关系", "分层"},
                new String[] {"命令入口", "入口"},
                new String[] {"存储"},
                new String[] {"复用"})).as("结构约定语义要素缺席组数").isEmpty();
    }

    @Test
    void definitionOfDonePresent() {
        String text = RuleText.rulesText();
        assertThat(Pattern.compile("完成定义|Definition of Done").matcher(text).find())
                .as("完成定义标题在场").isTrue();
        assertThat(RuleText.missingGroups(text,
                new String[] {"正常路径"},
                new String[] {"失败路径", "失败用例", "反例"},
                new String[] {"退出码"},
                new String[] {"未解决", "待办", "待确认"},
                new String[] {"不写成已实现", "不能写成已经实现", "不得写成已实现", "未实现不得", "不冒充"}))
                .as("完成定义语义要素缺席组数").isEmpty();
    }

    @Test
    void referencedCommandsAreReal() {
        // C4：规则里的验证入口必须指向仓库真实存在的被引用物（防虚构路径的回归护栏）。
        // Python 载体退役（ADR-0006 尾款，2026-09-30）后半边翻转：规则文本改引 Java 门与
        // 对照 tag；退役面不得在工作树复活（护栏语义由「路径存在」翻转为「路径不复活」）。
        assertThat(RuleText.rulesText()).as("Java 全量门字面量在场").contains("mvn test");
        assertThat(RuleText.rulesText()).as("退役对照 tag 字面量在场").contains("python-carrier-final");
        for (String relative : new String[] {"tests", "workbench", "evals", "pyproject.toml"}) {
            assertThat(RuleText.REPO.resolve(relative)).as("退役后工作树不得复活：%s", relative).doesNotExist();
        }
        assertThat(RuleText.rulesPath()).as("规则文件为仓库根 CLAUDE.md").isRegularFile();
    }
}
