package workbench.execution;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 写集归一化与越界判定（镜像 execution.py 的 _normalize_scope/_in_scope，L04 讲义 JD1）。
 *
 * <p>空条目忽略；绝对路径与越出候选（含 ``..``）的条目不构成有效写集（静默剔除，
 * 全剔后 code 模式落写集校验拒绝）；只剥 ``./`` 前缀本身，不动 ``.hidden`` 这类
 * 合法名字（Python 复查轮 S6a）；保序去重。
 */
public final class WriteScope {

    private WriteScope() {}

    public static List<String> normalize(List<String> entries) {
        Set<String> scope = new LinkedHashSet<>();
        for (String entry : entries) {
            Path path = Path.of(entry.strip());
            if (path.getNameCount() == 0) {
                continue; // 空条目忽略
            }
            if (path.isAbsolute() || namePartsContain(path, "..")) {
                continue;
            }
            String posix = path.toString(); // POSIX 载体：分隔符本就是 /
            while (posix.startsWith("./")) {
                posix = posix.substring(2);
            }
            scope.add(posix.isEmpty() ? "." : posix);
        }
        return new ArrayList<>(scope);
    }

    public static boolean inScope(String path, List<String> scope) {
        for (String item : scope) {
            String prefix = item.endsWith("/") ? item : item + "/";
            if (path.equals(item) || path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean namePartsContain(Path path, String part) {
        for (int i = 0; i < path.getNameCount(); i++) {
            if (path.getName(i).toString().equals(part)) {
                return true;
            }
        }
        return false;
    }
}
