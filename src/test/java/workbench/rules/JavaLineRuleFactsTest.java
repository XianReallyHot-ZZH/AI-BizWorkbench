package workbench.rules;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L02 重走红点组：CLAUDE.md 的 Java 重走线规则要素在场（移植注记附录 A2 注）。
 *
 * <p>本讲"目标能力缺失"的具体形态：规则文件对 Java 线失真——常用命令无 Java 门、
 * 架构大图无 src/、无双轨冻结纪律（现查 2026-09-29，见移植注记 A1）。三组断言
 * 语义组式（组内任一命中即绿、不锁文风），commit 2 按 A6 已确认候选表增量写入后转绿。
 *
 * <p>验收项 → 测试名 → 实际操作 → 比较什么
 * <ul>
 *   <li>C1/C4 → javaVerificationCommandsPresent → 读 CLAUDE.md → Java 验证入口词面在场 + 引用物实存</li>
 *   <li>C2/C4 → javaStructureFactsPresent → 读 CLAUDE.md → Java 线结构事实语义组在场</li>
 *   <li>C3/C12 → dualTrackFreezeDisciplinePresent → 读 CLAUDE.md → 双轨冻结纪律语义组在场</li>
 * </ul>
 *
 * <p>已知混合形态：CourseContracts 词面已在铁律 4（ADR-0006 载体句）——双载体子组
 * 起始红时即绿（就位即绿，同 L02WorkbenchRulesTest 口径）；其余子组缺席构成本讲红点。
 */
class JavaLineRuleFactsTest {

    @Test
    void javaVerificationCommandsPresent() {
        // J1：Java 验证命令在场（词面 + 引用物，双向——C4 精神：不照抄不存在的命令）
        String text = RuleText.rulesText();
        assertThat(text).as("mvn test 全量门词面在场").contains("mvn test");
        assertThat(text).as("./bin/wb CLI 包装入口词面在场").contains("./bin/wb");
        assertThat(RuleText.REPO.resolve("pom.xml")).as("规则引用的 pom.xml 存在").isRegularFile();
        assertThat(RuleText.REPO.resolve("bin/wb")).as("规则引用的 bin/wb 存在").isRegularFile();
        assertThat(RuleText.REPO.resolve("src/main/java")).as("规则引用的 src/main/java 存在").isDirectory();
        assertThat(RuleText.REPO.resolve("src/test/java")).as("规则引用的 src/test/java 存在").isDirectory();
    }

    @Test
    void javaStructureFactsPresent() {
        // J2：Java 线结构事实语义组（分层载体/包装入口/golden 资源位；双载体子组已就位——铁律 4）
        assertThat(RuleText.missingGroups(RuleText.rulesText(),
                new String[] {"src/main", "src/main/java"},
                new String[] {"CourseContracts", "coursecontracts", "双载体"},
                new String[] {"bin/wb",},
                new String[] {"golden/l01", "golden/l02", "对照基准"}))
                .as("Java 结构事实语义组缺席组数").isEmpty();
    }

    @Test
    void dualTrackFreezeDisciplinePresent() {
        // J3：双轨冻结纪律语义组（冻结面 / Python 账本零写入 / 重走线指针）
        assertThat(RuleText.missingGroups(RuleText.rulesText(),
                new String[] {"冻结不触碰", "冻结面", "零触碰", "冻结实现面"},
                new String[] {".runtime/course/L01-workbench", "Python 时代账本", "Python 账本"},
                new String[] {"重走线",}))
                .as("双轨冻结纪律语义组缺席组数").isEmpty();
    }
}
