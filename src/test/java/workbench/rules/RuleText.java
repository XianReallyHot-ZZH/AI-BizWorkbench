package workbench.rules;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 规则文本合同测试共享支撑（复查轮提取——原 L02WorkbenchRulesTest 私有 helper，两个测试类同形共用）：
 * 仓库根定位、根 CLAUDE.md 直读（纯文本类测试结构约定明载例外）、语义组缺席检查。
 */
final class RuleText {

    static final Path REPO = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
    private static final Path RULES = REPO.resolve("CLAUDE.md");

    private RuleText() {}

    static Path rulesPath() {
        return RULES;
    }

    static String rulesText() {
        try {
            return Files.readString(RULES);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** 逐组检查：组内任一关键词出现即算该组在场；返回全部缺席的组（语义组式断言，不锁文风）。 */
    @SafeVarargs
    static List<String[]> missingGroups(String text, String[]... groups) {
        List<String[]> missing = new ArrayList<>();
        for (String[] group : groups) {
            boolean present = false;
            for (String word : group) {
                if (text.contains(word)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                missing.add(group);
            }
        }
        return missing;
    }
}
