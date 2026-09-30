package workbench.evals.l06;

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
 * 构造 L06 缺陷基线（Java 重走件，对照冻结 {@code evals/l06/build_defect_baseline.py}）。
 *
 * <p>缺陷（上游 L06 教学缺陷原样）：{@code ERPService.export_inventory} 的 CSV 模板把
 * {@code {row['available']}} 换成 {@code {row['on_hand']}}——查询 [8,3,5]、导出 [8,3,8]，
 * reserved&gt;0 时两出口口径分裂。客户 {@code inventory_export_is_stable} 用 reserved=0
 * 数据，对本缺陷不可见（盲区探针另有实跑采证）。基线仅作检查的靶子，永不合入；锚点不唯一
 * 即拒绝动手（上游形状漂移时构造失败而非错改）。
 *
 * <p>对外契约与冻结件同形：stdout 单行 JSON（defect_live/query_available/csv_available，
 * 键序 = Python 插入序）；拒绝词面逐字同文（基线已存在 / 锚点命中 N 次 / 缺陷未生效），
 * stderr + rc 1；用法错误 rc 2（argparse 同形，词面按 JD6）。结构差异（附录 A1）：自检经
 * 子进程 python 驱动（Python 为 in-process import），经 {@link StockProbe} 一次场景回报。
 * Java 增量参数面（JD1）：{@code --source}（默认 vendors/flowERP，锚点守卫可测缝——冻结件
 * 硬编码无注入缝，l05 修正注① 先例）、{@code --python}（默认 .venv/bin/python，相对 cwd
 * 内部绝对化）；默认基线落 {@code -java} 路径（防跨时代冲撞，如实记录的偏离）。
 */
public final class BuildDefectBaseline {

    /** 只命中 export_inventory 的 CSV 模板 available 字段；与冻结 Python 构造器逐字同一锚点。 */
    private static final String ANCHOR = "{row['available']}";
    private static final String PATCH_TO = "{row['on_hand']}";

    private static final String DEFAULT_BASELINE = ".runtime/course/L06-defect-baseline-java";
    private static final String DEFAULT_SOURCE = "vendors/flowERP";
    private static final String DEFAULT_PYTHON = ".venv/bin/python";

    private BuildDefectBaseline() {}

    public static void main(String[] argv) {
        try {
            Args args = new Args(argv,
                    Set.of("--baseline", "--source", "--python"), Set.of("--skip-self-check"));
            String baselineArg = args.optional("--baseline", DEFAULT_BASELINE);
            Path interpreter = StockProbe.resolveInterpreter(args.optional("--python", DEFAULT_PYTHON));
            build(baselineArg, args.optional("--source", DEFAULT_SOURCE));
            if (!args.flag("--skip-self-check")) {
                selfCheck(interpreter, Path.of(baselineArg).toAbsolutePath().normalize());
            }
        } catch (Args.UsageException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        } catch (StockProbe.ToolFailure error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }

    private static void build(String baselineArg, String sourceArg) {
        Path baseline = Path.of(baselineArg).toAbsolutePath().normalize();
        if (Files.exists(baseline)) {
            throw new StockProbe.ToolFailure("基线已存在，不覆盖：" + baseline + "（重建请先手动删除）");
        }
        Path source = Path.of(sourceArg).toAbsolutePath().normalize();
        Process process;
        try {
            process = new ProcessBuilder("git", "clone", "--quiet", "--no-hardlinks",
                    source.toString(), baseline.toString()).start();
        } catch (IOException error) {
            throw new StockProbe.ToolFailure("git clone 启动失败：" + source, error);
        }
        try {
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            if (code != 0) {
                throw new StockProbe.ToolFailure(
                        "git clone 失败 rc=" + code + "：" + stderr.strip());
            }
        } catch (IOException error) {
            throw new StockProbe.ToolFailure("git clone 输出读取失败：" + source, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new StockProbe.ToolFailure("git clone 等待被中断", error);
        }
        Path serviceFile = baseline.resolve("flowerp").resolve("service.py");
        String sourceText = readUtf8(serviceFile);
        int hits = countMatches(sourceText, ANCHOR);
        if (hits != 1) {
            throw new StockProbe.ToolFailure("锚点命中 " + hits + " 次（应为 1），上游形状变了，停止构造");
        }
        writeUtf8(serviceFile, sourceText.replace(ANCHOR, PATCH_TO));
    }

    private static void selfCheck(Path interpreter, Path baseline) {
        int queryAvailable;
        int csvAvailable;
        try (StockProbe.TempDb temp = StockProbe.tempDb("l06-baseline-check-")) {
            JsonNode observed = StockProbe.scenario(interpreter, baseline, temp.db, 8, 3);
            queryAvailable = observed.path("query").path(2).asInt();
            csvAvailable = observed.path("csv").path(2).asInt();
        }
        boolean defectLive = queryAvailable == 5 && csvAvailable == 8;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("defect_live", defectLive);
        result.put("query_available", queryAvailable);
        result.put("csv_available", csvAvailable);
        System.out.println(PyJson.dumpsCompact(result));
        if (!defectLive) {
            throw new StockProbe.ToolFailure("缺陷未生效：预期查询 5 / CSV 8 分裂，实际见上");
        }
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
            throw new StockProbe.ToolFailure("基线文件读取失败：" + file, error);
        }
    }

    private static void writeUtf8(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new StockProbe.ToolFailure("基线文件写入失败：" + file, error);
        }
    }
}
