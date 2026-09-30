package workbench.evals.l05;

import com.fasterxml.jackson.databind.JsonNode;
import workbench.bootstrap.Args;
import workbench.bootstrap.PyJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 构造 L05 缺陷基线（Java 重走件，对照冻结 {@code evals/l05/build_defect_baseline.py}）。
 *
 * <p>缺陷形状（讲义 §5 裁决记录二）：
 * <ul>
 * <li>{@code b1}（默认）——门面 {@code ERPService.receive_stock} 的重放分支重执行库存更新：
 *     重试再次入账，on_hand 5→10、流水不重复。客户 blocking eval
 *     {@code receiving_is_idempotent} 对此两连红（C3）。补丁与冻结 Python 逐字同文；
 * <li>{@code blind}——重放分支删旧 event 行、以 {@code -rewritten} 新键重插同内容、库存与
 *     返回值不变（C7 盲区对照靶子；JD4 规格，与 golden 生成器内联手术逐字同文）。
 * </ul>
 *
 * <p>上游课堂窄缺陷「同键多写流水」因 {@code inventory_events.event_key} 主键不可构造
 * （store.py schema 事实，Python 时代已入证据账）。基线仅作检查的靶子，永不合入。
 *
 * <p>对外契约与冻结件同形：stdout 单行 JSON（b1：baseline/defect_live/on_hand/events，
 * 键序与 Python 插入序一致）；拒绝词面逐字同文（基线已存在 / 锚点命中 N 次 / 缺陷未生效），
 * stderr + rc 1；用法错误 rc 2（argparse 同形，词面按 JD6）。结构差异（附录 A1）：自检经
 * 子进程 python 驱动（Python 为 in-process import），默认解释器 {@code .venv/bin/python}
 * （相对 cwd，内部绝对化）。Java 增量参数面（JD1/修正注①）：{@code --source}（默认
 * vendors/flowERP，锚点守卫的可测缝——冻结件硬编码无注入缝）、{@code --defect}、
 * {@code --python}；默认基线落 {@code -java} 路径（防跨时代冲撞，如实记录的偏离）。
 */
public final class BuildDefectBaseline {

    /** 只命中门面重放分支（service.py receive_stock）；与冻结 Python 构造器逐字同一锚点。 */
    private static final String ANCHOR = "            if exists:\n                row = conn.execute(";

    /** b1 补丁：与冻结构造器 PATCH 逐字同文（重放分支重执行 UPDATE stock）。 */
    private static final String B1_PATCH = "            if exists:\n"
            + "                conn.execute(\"UPDATE stock SET on_hand=on_hand+? WHERE sku=?\", (quantity, sku))\n"
            + "                row = conn.execute(";

    /** 盲区补丁：JD4 规格，与 tools/generate_golden_l05.py 的 BLIND_PATCH 逐字同文（双侧同规格）。 */
    private static final String BLIND_PATCH = "            if exists:\n"
            + "                conn.execute(\"DELETE FROM inventory_events WHERE event_key=?\", (event_key,))\n"
            + "                conn.execute(\n"
            + "                    \"INSERT INTO inventory_events(event_key,sku,quantity,reserved_delta,event_type,reference) VALUES(?,?,?,?,?,?)\",\n"
            + "                    (event_key + \"-rewritten\", sku, quantity, 0, \"receive\", reference),\n"
            + "                )\n"
            + "                row = conn.execute(";

    private static final String DEFAULT_BASELINE = ".runtime/course/L05-defect-baseline-java";
    private static final String DEFAULT_SOURCE = "vendors/flowERP";
    private static final String DEFAULT_PYTHON = ".venv/bin/python";

    private BuildDefectBaseline() {}

    public static void main(String[] argv) {
        try {
            Args args = new Args(argv,
                    Set.of("--baseline", "--defect", "--source", "--python"), Set.of());
            String baselineArg = args.optional("--baseline", DEFAULT_BASELINE);
            String defect = args.optional("--defect", "b1");
            if (!defect.equals("b1") && !defect.equals("blind")) {
                throw new Args.UsageException(
                        "argument --defect: invalid choice: '" + defect + "' (choose from 'b1', 'blind')");
            }
            String sourceArg = args.optional("--source", DEFAULT_SOURCE);
            Path interpreter = FlowerpProbe.resolveInterpreter(args.optional("--python", DEFAULT_PYTHON));

            build(baselineArg, defect, sourceArg);
            selfCheck(interpreter, Path.of(baselineArg).toAbsolutePath().normalize(), defect, baselineArg);
        } catch (Args.UsageException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        } catch (FlowerpProbe.ToolFailure error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }

    private static void build(String baselineArg, String defect, String sourceArg) {
        Path baseline = Path.of(baselineArg).toAbsolutePath().normalize();
        if (Files.exists(baseline)) {
            throw new FlowerpProbe.ToolFailure("基线已存在，不覆盖：" + baseline + "（重建请先手动删除）");
        }
        Path source = Path.of(sourceArg).toAbsolutePath().normalize();
        Process process;
        try {
            process = new ProcessBuilder("git", "clone", "--quiet", "--no-hardlinks",
                    source.toString(), baseline.toString()).start();
        } catch (IOException error) {
            throw new FlowerpProbe.ToolFailure("git clone 启动失败：" + source, error);
        }
        try {
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            if (code != 0) {
                throw new FlowerpProbe.ToolFailure(
                        "git clone 失败 rc=" + code + "：" + stderr.strip());
            }
        } catch (IOException error) {
            throw new FlowerpProbe.ToolFailure("git clone 输出读取失败：" + source, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new FlowerpProbe.ToolFailure("git clone 等待被中断", error);
        }
        Path serviceFile = baseline.resolve("flowerp").resolve("service.py");
        String sourceText = readUtf8(serviceFile);
        int hits = countMatches(sourceText, ANCHOR);
        if (hits != 1) {
            throw new FlowerpProbe.ToolFailure("锚点命中 " + hits + " 次（应为 1），上游形状变了，停止构造");
        }
        String patch = defect.equals("blind") ? BLIND_PATCH : B1_PATCH;
        writeUtf8(serviceFile, sourceText.replace(ANCHOR, patch));
    }

    private static void selfCheck(Path interpreter, Path baseline, String defect, String baselineArg) {
        Map<String, Object> report = new LinkedHashMap<>();
        try (FlowerpProbe.TempDb temp = FlowerpProbe.tempDb("l05-baseline-check-")) {
            FlowerpProbe.probe(interpreter, baseline, temp.db, "setup");
            FlowerpProbe.probe(interpreter, baseline, temp.db, "receive", "receipt:001", "5");
            JsonNode second = FlowerpProbe.probe(interpreter, baseline, temp.db,
                    "receive", "receipt:001", "5");
            int onHand = second.get("on_hand").asInt();
            report.put("on_hand", onHand);
            if (defect.equals("blind")) {
                int oldKey = FlowerpProbe.probe(interpreter, baseline, temp.db,
                        "events", "receipt:001").get("count").asInt();
                int rewritten = FlowerpProbe.probe(interpreter, baseline, temp.db,
                        "events", "receipt:001-rewritten").get("count").asInt();
                report.put("old_key_events", oldKey);
                report.put("rewritten_key_events", rewritten);
                if (onHand != 5 || oldKey != 0 || rewritten != 1) {
                    throw new FlowerpProbe.ToolFailure("缺陷未生效，基线不可用：" + PyJson.dumpsCompact(report));
                }
            } else {
                int events = FlowerpProbe.probe(interpreter, baseline, temp.db,
                        "events", "receipt:001").get("count").asInt();
                report.put("events", events);
                if (onHand != 10 || events != 1) {
                    throw new FlowerpProbe.ToolFailure("缺陷未生效，基线不可用：" + PyJson.dumpsCompact(report));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseline", baselineArg);
        if (defect.equals("blind")) {
            out.put("defect", "blind");
        }
        out.put("defect_live", true);
        out.putAll(report);
        System.out.println(PyJson.dumpsCompact(out));
    }

    private static int countMatches(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String readUtf8(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new FlowerpProbe.ToolFailure("基线文件读取失败：" + file, error);
        }
    }

    private static void writeUtf8(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new FlowerpProbe.ToolFailure("基线文件写入失败：" + file, error);
        }
    }
}
