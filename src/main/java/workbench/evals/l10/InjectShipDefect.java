package workbench.evals.l10;

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
 * L10 教学缺陷注入装置（讲义 D5；{@code workbench.evals.l09.InjectCancelDefect} 同款
 * 护栏结构——每讲自含纪律）。对客户树拷贝做<b>替换式</b>一行手术：入门服务
 * {@code flowerp/service.py} {@code ship_order} 的锚点行（{@code if order["status"] !=
 * OrderStatus.RESERVED:}，全文恰一次——讲前现查）替换为 {@code if True:}——守卫恒真
 * 即拒绝一切发货（含合法 reserved），上游 {@code candidate_loop_lab.py} 的 BAD 教学行
 * <b>逐字</b>（「incorrectly reject every shipment」，含注释措辞）。红点构成 = 过度
 * 拒绝：合法拍 {@code l10_ship_legal} 红，非法三拍与客户 eval 绿（隔离护栏恰与注入
 * 形态互证——与 L09 跳过释放形态相反）。<b>教学注入，不伪称客户事故</b>（客户实现
 * 在场且正确，讲义 §1.3）。只写 {@code --dest} 拷贝树，<b>永不写回 vendors</b>（铁律 1）。
 *
 * <p>注入形态上游逐字源在场（与 L09 自拟不同）：上游 examples/candidate_loop_lab.py
 * GOOD/BAD 两行逐字即本讲对账件；恢复 = 同一棵树反向替换一行（diff 互逆可对账）。
 *
 * <p>护栏三面（缺一即拒，rc 1）：源树已含注入行（幂等——重复注入使对照口径漂移）；
 * 锚点行出现次数 ≠ 1（唯一性校验——注入位置可机检，L05–L09 锚点纪律同款）；
 * dest 已存在（旧证据不可抹）。拷贝排除 {@code .git}/（目录与文件双面——客户
 * submodule 的 gitdir 指针）/ {@code __pycache__}/ 与 {@code .pyc}。成功 rc 0，
 * stdout 出注入位置 JSON（injected_line = 锚点原行号：替换不位移）。
 */
public final class InjectShipDefect {

    /**
     * 独立预期源（逐字；与合同测试各持一份副本）。上游逐字源在场：
     * vendors/CodexFDE/docs/courses/L10/examples/candidate_loop_lab.py:24-25（GOOD/BAD）。
     */
    static final String ANCHOR_LINE = "            if order[\"status\"] != OrderStatus.RESERVED:";
    static final String INJECTED_LINE = "            if True:  # TEACHING: incorrectly reject every shipment";

    private InjectShipDefect() {}

    public static void main(String[] argv) {
        try {
            Args args = new Args(argv, Set.of("--source", "--dest"), Set.of());
            Path source = Path.of(args.require("--source")).toAbsolutePath().normalize();
            Path dest = Path.of(args.require("--dest")).toAbsolutePath().normalize();
            Path servicePy = source.resolve("flowerp").resolve("service.py");
            if (!Files.isRegularFile(servicePy)) {
                throw new Args.UsageException("--source 下没有 flowerp/service.py：" + source);
            }
            if (Files.exists(dest)) {
                System.err.println("dest 已存在，拒绝覆盖（旧证据不可抹）：" + dest);
                System.exit(1);
            }
            String content = Files.readString(servicePy, StandardCharsets.UTF_8);
            if (content.contains(INJECTED_LINE)) {
                System.err.println("已注入：源树 service.py 含 L10 教学注入行，拒绝重复注入");
                System.exit(1);
            }
            int anchors = countOccurrences(content, ANCHOR_LINE);
            if (anchors != 1) {
                System.err.println("锚点不唯一：service.py 中锚点行出现 " + anchors + " 次（预期恰 1），拒绝注入");
                System.exit(1);
            }
            copyTree(source, dest);
            Path destService = dest.resolve("flowerp").resolve("service.py");
            Files.writeString(destService, content.replace(ANCHOR_LINE, INJECTED_LINE),
                    StandardCharsets.UTF_8);

            List<String> lines = Files.readAllLines(destService, StandardCharsets.UTF_8);
            int injectedLine = lines.indexOf(INJECTED_LINE) + 1;  // 替换不位移：锚点原行号 = 注入行行号
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("injected", true);
            report.put("source", source.toString());
            report.put("dest", dest.toString());
            report.put("anchor_line", injectedLine);
            report.put("injected_line", injectedLine);
            report.put("verbatim_source",
                    "vendors/CodexFDE/docs/courses/L10/examples/candidate_loop_lab.py:24-25 (upstream verbatim)");
            report.put("note", "L10 teaching defect (upstream verbatim BAD line; recovery = "
                    + "reverse-replace this line); copy only, vendors untouched");
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

    /** 拷贝整树，排除 .git/（目录与文件）、__pycache__/ 与 .pyc（l08/l09 copyTree 同形）。 */
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
                String name = file.getFileName().toString();
                if (!name.endsWith(".pyc") && !".git".equals(name)) {
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
