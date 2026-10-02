package workbench.evals.l11;

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
 * L11 教学缺陷注入装置（讲义 D5；{@code workbench.evals.l09.InjectCancelDefect} /
 * {@code l10.InjectShipDefect} 同款护栏结构——每讲自含纪律）。对客户树拷贝做
 * <b>插入式</b>一行手术：入门服务 {@code flowerp/service.py} {@code propose_purchase}
 * 方法段内的 marker 行（{@code return self.purchase(pid)}，段内恰一次——讲前现查
 * 全文亦唯一，:329）<b>前</b>插入提前入库行（{@code self.receive_stock(sku, quantity,
 * "teaching-premature-" + pid)}）——申请保存后立即收货，上游 {@code integration_lab.py}
 * 的教学缺陷<b>逐字</b>（两行均 :87-88 逐字源）。红点构成 = 过度副作用：字段拍绿、
 * 库存拍 {@code l11_purchase_normal} 红（17/2/15 + 入库流水）——「同版不同覆盖」
 * 教学点（与 L10 过度拒绝形态相反）。<b>教学注入，不伪称客户事故</b>（客户实现在场
 * 且正确，讲义 §1.3）。只写 {@code --dest} 拷贝树，<b>永不写回 vendors</b>（铁律 1）。
 *
 * <p>注入形态上游逐字源在场（与 L09 自拟不同）：上游 examples/integration_lab.py
 * marker/injection 两行逐字即本讲对账件；{@code --restore} 恢复 = 同一装置删注入行
 * （marker 回原位，diff 互逆可对账——上游 {@code service.write_text(original)} 恢复
 * 的装置化对应）。
 *
 * <p>护栏（缺一即拒，rc 1）：注入面——源树已含注入行（幂等）；marker 行在
 * propose 段内出现次数 ≠ 1（唯一性校验——上游 {@code original[pos:stop].count(marker)}
 * 同形，pos/stop = {@code def propose_purchase(} 段到 {@code def purchase(} 段边界）；
 * dest 已存在（旧证据不可抹）。恢复面——注入行在 dest 出现次数 ≠ 1（0 = 净树幂等
 * 拒绝；&gt;1 = 拒绝乱删）。拷贝排除 {@code .git}/（目录与文件双面）/ {@code __pycache__}/
 * 与 {@code .pyc}。成功 rc 0，stdout 出位置 JSON（injected_line = marker 原行号：
 * 插入其前不位移 marker 的「原位置」语义）。
 */
public final class InjectPurchaseDefect {

    /**
     * 独立预期源（逐字；与合同测试各持一份副本）。上游逐字源在场：
     * vendors/CodexFDE/docs/courses/L11/examples/integration_lab.py:87-88。
     */
    static final String MARKER_LINE = "        return self.purchase(pid)";
    static final String INJECTED_LINE = "        self.receive_stock(sku, quantity, \"teaching-premature-\" + pid)";
    private static final String PROPOSE_DEF = "    def propose_purchase(";
    private static final String PURCHASE_DEF = "    def purchase(";

    private InjectPurchaseDefect() {}

    public static void main(String[] argv) {
        try {
            Args args = new Args(argv, Set.of("--source", "--dest"), Set.of("--restore"));
            if (args.flag("--restore")) {
                restore(args);
                return;
            }
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
                System.err.println("已注入：源树 service.py 含 L11 教学注入行，拒绝重复注入");
                System.exit(1);
            }
            int[] section = proposeSection(content);
            int anchors = countOccurrences(content.substring(section[0], section[1]), MARKER_LINE);
            if (anchors != 1) {
                System.err.println("锚点不唯一：propose_purchase 段内 marker 行出现 " + anchors
                        + " 次（预期恰 1），拒绝注入");
                System.exit(1);
            }
            copyTree(source, dest);
            Path destService = dest.resolve("flowerp").resolve("service.py");
            // 上游同形：段内 replace(marker, injection + marker)——插入非替换，marker 保留
            String broken = content.substring(0, section[0])
                    + content.substring(section[0], section[1])
                            .replace(MARKER_LINE, INJECTED_LINE + "\n" + MARKER_LINE)
                    + content.substring(section[1]);
            Files.writeString(destService, broken, StandardCharsets.UTF_8);

            List<String> lines = Files.readAllLines(destService, StandardCharsets.UTF_8);
            int injectedLine = lines.indexOf(INJECTED_LINE) + 1;
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("injected", true);
            report.put("source", source.toString());
            report.put("dest", dest.toString());
            report.put("marker_line", injectedLine + 1);
            report.put("injected_line", injectedLine);
            report.put("verbatim_source",
                    "vendors/CodexFDE/docs/courses/L11/examples/integration_lab.py:87-88 (upstream verbatim)");
            report.put("note", "L11 teaching defect (upstream verbatim premature receive; recovery = "
                    + "--restore removes this line); copy only, vendors untouched");
            System.out.println(PyJson.dumpsCompact(report));
        } catch (Args.UsageException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        } catch (IOException error) {
            System.err.println("注入失败：" + error.getMessage());
            System.exit(1);
        }
    }

    private static void restore(Args args) throws IOException {
        if (args.has("--source")) {
            throw new Args.UsageException("--restore 不接受 --source（恢复只动 dest 树）");
        }
        Path dest = Path.of(args.require("--dest")).toAbsolutePath().normalize();
        Path destService = dest.resolve("flowerp").resolve("service.py");
        if (!Files.isRegularFile(destService)) {
            throw new Args.UsageException("--dest 下没有 flowerp/service.py：" + dest);
        }
        String content = Files.readString(destService, StandardCharsets.UTF_8);
        int count = countOccurrences(content, INJECTED_LINE);
        if (count != 1) {
            System.err.println("恢复护栏：dest service.py 中注入行出现 " + count
                    + " 次（预期恰 1；0 = 净树无注入），拒绝恢复");
            System.exit(1);
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(destService, StandardCharsets.UTF_8));
        int removedLine = lines.indexOf(INJECTED_LINE) + 1;
        lines.remove(INJECTED_LINE);
        Files.writeString(destService, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("restored", true);
        report.put("dest", dest.toString());
        report.put("removed_line", removedLine);
        report.put("note", "reverse of L11 teaching injection; diff 互逆可对账");
        System.out.println(PyJson.dumpsCompact(report));
    }

    /** propose_purchase 方法段边界 [start, stop)——上游 pos/stop 定位同形。 */
    private static int[] proposeSection(String content) {
        int start = content.indexOf(PROPOSE_DEF);
        if (start < 0) {
            System.err.println("锚点缺失：service.py 无 " + PROPOSE_DEF);
            System.exit(1);
        }
        int stop = content.indexOf(PURCHASE_DEF, start);
        if (stop < 0) {
            System.err.println("锚点缺失：service.py propose_purchase 段后无 " + PURCHASE_DEF);
            System.exit(1);
        }
        return new int[]{start, stop};
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

    /** 拷贝整树，排除 .git/（目录与文件）、__pycache__/ 与 .pyc（l08–l10 copyTree 同形）。 */
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
