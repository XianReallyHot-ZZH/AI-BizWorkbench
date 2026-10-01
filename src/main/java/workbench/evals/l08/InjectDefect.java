package workbench.evals.l08;

import workbench.bootstrap.Args;
import workbench.bootstrap.PyJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L08 教学缺陷注入装置（讲义 D5：CI 与本地使用同一装置，不另写内联脚本——防两套
 * 口径漂移）。对客户树拷贝做上游 {@code injected-atomic-defect.diff} 逐字手术：
 * {@code flowerp/sales.py} 逐行写入循环的锚点行后插一行提前提交（自带教学标注）。
 * <b>教学注入，不伪称客户事故</b>（上游口径：参考代码已经正确时用明确标注的教学
 * 缺陷建立对照）；只写 {@code --dest} 拷贝树，<b>永不写回 vendors</b>（铁律 1）。
 *
 * <p>护栏三面（缺一即拒，rc 1）：源树已含注入行（幂等——重复注入会使对照口径漂移）；
 * 锚点行出现次数 ≠ 1（唯一性校验——注入位置可机检，L05/L06/L07 锚点纪律同款）；
 * dest 已存在（旧证据不可抹）。拷贝排除 {@code .git}/ {@code __pycache__}/ 与
 * {@code .pyc}。成功 rc 0，stdout 出注入位置 JSON。
 */
public final class InjectDefect {

    /** 上游 injected-atomic-defect.diff 逐字（注入行与其锚点行——独立预期源）。 */
    static final String INJECTED_LINE =
            "                conn.commit()  # L08 teaching defect: commit each line too early";
    static final String ANCHOR_LINE =
            "                conn.execute(\"UPDATE sales_document_lines SET reserved_quantity=reserved_quantity+? WHERE id=?\", (quantity, line[\"id\"]))";

    private InjectDefect() {}

    public static void main(String[] argv) {
        try {
            Args args = new Args(argv, Set.of("--source", "--dest"), Set.of());
            Path source = Path.of(args.require("--source")).toAbsolutePath().normalize();
            Path dest = Path.of(args.require("--dest")).toAbsolutePath().normalize();
            Path salesPy = source.resolve("flowerp").resolve("sales.py");
            if (!Files.isRegularFile(salesPy)) {
                throw new Args.UsageException("--source 下没有 flowerp/sales.py：" + source);
            }
            if (Files.exists(dest)) {
                System.err.println("dest 已存在，拒绝覆盖（旧证据不可抹）：" + dest);
                System.exit(1);
            }
            String content = Files.readString(salesPy, StandardCharsets.UTF_8);
            if (content.contains(INJECTED_LINE)) {
                System.err.println("已注入：源树 sales.py 含 L08 教学注入行，拒绝重复注入");
                System.exit(1);
            }
            int anchors = countOccurrences(content, ANCHOR_LINE);
            if (anchors != 1) {
                System.err.println("锚点不唯一：sales.py 中锚点行出现 " + anchors + " 次（预期恰 1），拒绝注入");
                System.exit(1);
            }
            copyTree(source, dest);
            Path destSales = dest.resolve("flowerp").resolve("sales.py");
            String injected = content.replace(
                    ANCHOR_LINE + "\n", ANCHOR_LINE + "\n" + INJECTED_LINE + "\n");
            Files.writeString(destSales, injected, StandardCharsets.UTF_8);

            List<String> lines = Files.readAllLines(destSales, StandardCharsets.UTF_8);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("injected", true);
            report.put("source", source.toString());
            report.put("dest", dest.toString());
            report.put("anchor_line", lines.indexOf(ANCHOR_LINE) + 1);
            report.put("injected_line", lines.indexOf(INJECTED_LINE) + 1);
            report.put("note", "L08 teaching defect (upstream injected-atomic-defect.diff verbatim); copy only, vendors untouched");
            System.out.println(PyJson.dumpsCompact(report));
        } catch (Args.UsageException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        } catch (IOException error) {
            System.err.println("注入失败：" + error.getMessage());
            System.exit(1);
        }
    }

    private static int countOccurrences(String content, String needle) {
        int count = 0;
        int at = content.indexOf(needle);
        while (at >= 0) {
            count++;
            at = content.indexOf(needle, at + needle.length());
        }
        return count;
    }

    /** 拷贝整树，排除 .git/、__pycache__/ 与 .pyc（客户 submodule 的版本簿记与缓存不入对照树）。 */
    private static void copyTree(Path source, Path dest) throws IOException {
        List<Path> dirs = new ArrayList<>();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if (".git".equals(name) || "__pycache__".equals(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                dirs.add(dest.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.getFileName().toString().endsWith(".pyc")) {
                    Path target = dest.resolve(source.relativize(file).toString());
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        for (Path dir : dirs) {
            Files.createDirectories(dir);
        }
    }
}
