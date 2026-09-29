package workbench.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 合同测试支撑：经真实 CLI 子进程驱动（镜像 Python 测试的 run_cli/payload，
 * 不绕入口直调内部函数——测试口径见 CLAUDE.md）。
 *
 * <p>golden 规范化器**独立实现** manifest.json 的 normalization 块，不共享产线代码：
 * golden 由 Python 产线写出，规范化由测试按 spec 独立复现，任何一侧偏离规范都会被对照抓住。
 */
public final class Cli {

    public record Result(int exitCode, String stdout, String stderr) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Cli() {}

    public static Path repoRoot() {
        return Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
    }

    /** 子进程 classpath：主 classes + 依赖 jar（exec:build-classpath 在测试前落盘）。 */
    private static String childClasspath() {
        Path cpFile = repoRoot().resolve("target/child-classpath.txt");
        if (!Files.isRegularFile(cpFile)) {
            throw new IllegalStateException(
                    "缺少 target/child-classpath.txt：mvn test 生命周期应先经 maven-dependency-plugin:build-classpath 生成");
        }
        try {
            return repoRoot().resolve("target/classes") + ":" + Files.readString(cpFile).strip();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    public static Result run(String... args) {
        try {
            List<String> argv = new ArrayList<>(List.of("java",
                    "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                    "-cp", childClasspath(), "workbench.cli.Main"));
            argv.addAll(List.of(args));
            Process process = new ProcessBuilder(argv).directory(repoRoot().toFile()).start();
            byte[] out = process.getInputStream().readAllBytes();
            byte[] err = process.getErrorStream().readAllBytes();
            int code = process.waitFor();
            return new Result(code,
                    new String(out, StandardCharsets.UTF_8), new String(err, StandardCharsets.UTF_8));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    public static JsonNode json(Result result) {
        try {
            return MAPPER.readTree(result.stdout());
        } catch (IOException error) {
            throw new IllegalStateException("stdout 不是 JSON：" + result.stdout(), error);
        }
    }

    // ---- golden 规范化（独立实现，规则见各 manifest.json normalization 块）----

    /** l01–l03 三套 manifest 的统一掩码集（默认重载保持既有口径，行为零变化）。 */
    private static final Map<String, String> DEFAULT_MASKS = Map.of(
            "created_at", "<TS>",
            "recorded_at", "<TS>",
            "workbench_id", "<WORKBENCH_ID>");

    /** JSON 输出：掩码 + canonical 序列化 + 末尾换行；非 JSON 逐字保留（防御性）。 */
    public static String normalize(String raw) {
        return normalize(raw, DEFAULT_MASKS);
    }

    /**
     * 同上，掩码字段由 manifest normalization 声明驱动（l04 起各套可扩展：机器生成时间戳、
     * 机器绝对路径等不可复现字段；声明即掩码，与 Python 生成器按同一块复现）。
     */
    public static String normalize(String raw, Map<String, String> maskFields) {
        try {
            return canonical(mask(MAPPER.readValue(raw, Object.class), maskFields)) + "\n";
        } catch (IOException notJson) {
            return raw;
        }
    }

    private static Object mask(Object node, Map<String, String> maskFields) {
        if (node instanceof Map<?, ?> map) {
            Map<Object, Object> masked = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String placeholder = maskFields.get(String.valueOf(entry.getKey()));
                masked.put(entry.getKey(),
                        placeholder != null ? placeholder : mask(entry.getValue(), maskFields));
            }
            return masked;
        }
        if (node instanceof List<?> list) {
            return list.stream().map(item -> mask(item, maskFields)).toList();
        }
        return node;
    }

    /** Python json.dumps(sort_keys=True, ensure_ascii=False, indent=2) 的同形字节。 */
    private static String canonical(Object node) {
        return writeSorted(node, 0);
    }

    private static String writeSorted(Object node, int depth) {
        if (node == null) {
            return "null";
        }
        if (node instanceof Boolean bool) {
            return bool.toString();
        }
        if (node instanceof Number number) {
            return number.toString();  // 本次载荷只有整数；无浮点格式化歧义
        }
        if (node instanceof String string) {
            return quote(string);
        }
        if (node instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                return "{}";
            }
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, value) -> sorted.put(String.valueOf(key), value));
            StringBuilder out = new StringBuilder("{\n");
            int index = 0;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                out.append(indent(depth + 1)).append(quote(entry.getKey())).append(": ")
                        .append(writeSorted(entry.getValue(), depth + 1));
                out.append(++index < sorted.size() ? "," : "").append("\n");
            }
            return out.append(indent(depth)).append('}').toString();
        }
        if (node instanceof List<?> list) {
            if (list.isEmpty()) {
                return "[]";
            }
            StringBuilder out = new StringBuilder("[\n");
            for (int i = 0; i < list.size(); i++) {
                out.append(indent(depth + 1)).append(writeSorted(list.get(i), depth + 1));
                out.append(i < list.size() - 1 ? "," : "").append('\n');
            }
            return out.append(indent(depth)).append(']').toString();
        }
        throw new IllegalArgumentException("不可规范化的节点类型：" + node.getClass());
    }

    private static String indent(int depth) {
        return "  ".repeat(depth);
    }

    /** Python ensure_ascii=False 口径：仅转义引号、反斜杠与控制字符，非 ASCII 保留原字。 */
    private static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
