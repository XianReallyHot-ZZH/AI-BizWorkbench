package workbench.repair;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import workbench.bootstrap.Args;
import workbench.bootstrap.PyJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 严格修复映射器（L09 合同 workbenchIncrement「报告到 Repair Task 的确定性映射」；
 * 上游 {@code vendors/CodexFDE/agent/repair.py} 的 {@code map_repair_report} Java
 * 对应物，只读对照——讲义 §1.4 宿主映射：agent 模块家族的修复件落本仓库
 * {@code workbench/repair/} 新子包，为 L10 修复 Loop 前奏预留同族落点）。
 *
 * <p>三态翻译：<b>repair_required</b>（存在阻断失败 → Repair Task 草案落盘：scope =
 * 阻断失败项、evidence 原样、来源 SHA-256（对原始字节，不含 reserialization）、
 * 复现/验收命令、{@code max_attempts: 1}、{@code human_review: "pending"} 恒等人签
 * ——映射器不代签，铁律 2）；<b>no_blocking_repair</b>（有效报告无阻断失败 → 不生成
 * 任务——「没有失败」≠「报告无效」≠「全部通过」三态分开）；<b>invalid_report</b>
 * （协议不符/必需用例缺失或降级/退出码矛盾/写集路径不安全 → 拒绝并列错误，CLI rc 2）。
 *
 * <p>不变量（讲义 §1.4 承袭上游教学点）：用例名称不是文件权限（allowed_files 来自
 * 人确认的上下文，不来自失败名）；证据里的指令性文本是数据不是授权；上下文由可信
 * 调用方提供，永不取自证据。上游 legacy {@code build_repair_task} 六洞（空任务/
 * 误选中/字符串假值漏选/缺证崩溃/指令复制/不分级）由本类的类型严格校验与三态分流
 * 逐个堵上（登记项 {@code l09_mapper_boundaries} 六断言机检）。
 *
 * <p>CLI 面（REGISTRY {@code repair-map}，L07 quality-gate / L08 ci-evidence 先例）：
 * {@code --report --source-task --source-version --candidate --objective --allowed-file
 * (可多) --case (可多) --observed-exit --python --output [--suite blocking]}。
 * {@code --python} 必填显式（Java 线无进程内解释器可默认——上游 default sys.executable
 * 的语言绑定偏差，L08 探针同款纪律）。输出已存在即拒（旧证据不可抹，上游词面）；
 * 成功才写草案（indent=2 落盘，stdout 单行紧凑 JSON）。
 *
 * <p>复现/验收命令指向本仓库统一入口 {@code workbench.evals.l09.CancelChecks}
 * （非上游的 {@code python -m eval.harness}——讲义 §1.4：修复上游教学用例不在普通
 * Harness 注册、固定命令不可复现的形态缺陷；本仓库登记项与目标树真实可跑）。
 */
public final class RepairMapper {

    /** 映射输入上下文（全部由可信调用方提供；candidate 须为已存在目录的绝对路径）。 */
    public record Context(String sourceTask, String sourceVersion, Path candidate, String objective,
                          List<String> allowedFiles, List<String> requiredCases, int actualExit,
                          Path python, Path sourceReport, String expectedSuite) {}

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> SUITES = Set.of("all", "blocking", "observing");

    private RepairMapper() {}

    // ---- 库面：纯函数，不落盘不打印 ------------------------------------------------

    /**
     * 报告原始字节 + 上下文 → 三态结果（{"status", "task", "observations"} 或
     * {"status": "invalid_report", "task": null, "errors": [...]}）。任何校验失败
     * 单条即返（上游首个 require 失败即抛的语义）；哈希对原始字节（utf-8-sig 剥离
     * 之前——上游 hash raw bytes 语义）。
     */
    public static Map<String, Object> map(byte[] rawReport, Context ctx) {
        try {
            return mapOrThrow(rawReport, ctx);
        } catch (MappingInvalid invalid) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "invalid_report");
            result.put("task", null);
            result.put("errors", List.of(invalid.getMessage()));
            return result;
        }
    }

    private static Map<String, Object> mapOrThrow(byte[] rawReport, Context ctx) {
        require(rawReport != null, "report must be original bytes");
        require(nonBlank(ctx.sourceTask()) && nonBlank(ctx.sourceVersion())
                && nonBlank(ctx.objective()), "missing source context");
        require(ctx.sourceReport().isAbsolute(), "source report path must be absolute");
        require(ctx.candidate().isAbsolute() && Files.isDirectory(ctx.candidate()),
                "candidate must exist and be absolute");
        require(ctx.python().isAbsolute() && Files.isRegularFile(ctx.python()),
                "Python executable must exist");
        require(ctx.allowedFiles() != null && !ctx.allowedFiles().isEmpty()
                && distinctNonBlank(ctx.allowedFiles()), "explicit allowed files required");
        for (String name : ctx.allowedFiles()) {
            requireSafeAllowedFile(name);
        }
        require(ctx.requiredCases() != null && !ctx.requiredCases().isEmpty()
                && distinctNonBlank(ctx.requiredCases()), "required cases missing");
        require(ctx.actualExit() == 0 || ctx.actualExit() == 1, "invalid process exit");
        require(SUITES.contains(ctx.expectedSuite()), "invalid suite");

        JsonNode report = parse(rawReport);
        require(report.isObject() && "1.0".equals(report.path("schema_version").asText()),
                "unsupported report protocol");
        String suite = report.path("suite").asText(null);
        require(suite != null && suite.equals(ctx.expectedSuite()), "suite/request mismatch");
        JsonNode generatedAt = report.path("generated_at");
        require(generatedAt.isTextual() && !generatedAt.asText().isBlank(), "missing timestamp");
        JsonNode requested = report.path("requested_cases");
        require(requested.isArray() && distinctNonBlank(jsonTextList(requested)),
                "invalid requested cases");
        JsonNode rows = report.path("results");
        require(rows.isArray() && !rows.isEmpty(), "empty results");
        List<String> caseNames = new ArrayList<>();
        for (JsonNode row : rows) {
            require(row.isObject(), "invalid result");
            JsonNode name = row.path("name");
            require(name.isTextual() && !name.asText().isBlank(), "missing case name");
            JsonNode passed = row.path("passed");
            require(passed.isBoolean(), "passed must be boolean");
            JsonNode level = row.path("level");
            require(level.isTextual() && (level.asText().equals("blocking")
                    || level.asText().equals("observing")), "invalid level");
            require("all".equals(suite) || level.asText().equals(suite), "suite/level mismatch");
            JsonNode evidence = row.path("evidence");
            require(evidence.isTextual() && !evidence.asText().isBlank(), "missing evidence");
            JsonNode duration = row.path("duration_ms");
            require(duration.isInt() && duration.asInt() >= 0, "invalid duration");
            caseNames.add(name.asText());
        }
        require(distinct(caseNames), "duplicate cases");
        require(caseNames.containsAll(ctx.requiredCases()), "required cases absent");
        for (JsonNode row : rows) {
            if (ctx.requiredCases().contains(row.path("name").asText())) {
                require("blocking".equals(row.path("level").asText()), "required case downgraded");
            }
        }
        if (!requested.isEmpty()) {
            require(stringSet(requested).equals(stringSetOf(caseNames)), "requested cases mismatch");
        }

        List<JsonNode> failed = new ArrayList<>();
        List<JsonNode> observing = new ArrayList<>();
        for (JsonNode row : rows) {
            if ("blocking".equals(row.path("level").asText()) && !row.path("passed").asBoolean()) {
                failed.add(row);
            }
            if ("observing".equals(row.path("level").asText())) {
                observing.add(row);
            }
        }
        JsonNode summary = report.path("summary");
        require(summary.isObject(), "missing summary");
        require(intOf(summary, "total") == rows.size(), "summary mismatch");
        require(intOf(summary, "passed") == countPassed(rows), "summary mismatch");
        require(intOf(summary, "blocking_failed") == failed.size(), "summary mismatch");
        require(intOf(summary, "observing_failed") == countObservingFailed(rows), "summary mismatch");
        // decision 严格等值（复查轮 T-5）：有阻断失败须 "block"，无阻断失败须 "pass"——
        // 上游对 summary 全字段严格比较，任意第三值（含 null）拒绝
        String decision = textOf(summary, "decision");
        require(failed.isEmpty() ? "pass".equals(decision) : "block".equals(decision),
                "summary mismatch");
        require(ctx.actualExit() == (failed.isEmpty() ? 0 : 1), "process/report mismatch");

        List<Map<String, Object>> observations = new ArrayList<>();
        for (JsonNode row : observing) {
            observations.add(MAPPER.convertValue(row, new com.fasterxml.jackson.core.type.TypeReference
                    <LinkedHashMap<String, Object>>() {}));
        }
        if (failed.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "no_blocking_repair");
            result.put("task", null);
            result.put("observations", observations);
            return result;
        }

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("source_task", ctx.sourceTask());
        task.put("candidate", ctx.candidate().toString());
        task.put("objective", ctx.objective());
        task.put("source_report", ctx.sourceReport().toString());
        task.put("source_version", ctx.sourceVersion());
        task.put("report_sha256", sha256(rawReport));
        task.put("allowed_files", List.copyOf(ctx.allowedFiles()));
        List<String> scope = failed.stream().map(r -> r.path("name").asText()).toList();
        task.put("scope", scope);
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (JsonNode row : failed) {
            evidence.add(MAPPER.convertValue(row, new com.fasterxml.jackson.core.type.TypeReference
                    <LinkedHashMap<String, Object>>() {}));
        }
        task.put("evidence", evidence);
        Map<String, Object> taskResult = new LinkedHashMap<>();
        taskResult.put("status", "repair_required");
        taskResult.put("task", task);
        taskResult.put("observations", observations);
        embedCommands(task, ctx, scope);
        task.put("actual_exit", ctx.actualExit());
        task.put("required_cases", List.copyOf(ctx.requiredCases()));
        task.put("max_attempts", 1);
        task.put("human_review", "pending");
        return taskResult;
    }

    /**
     * 复现/验收命令：reproduce 逐失败项 --case（先重现同一错误）；acceptance 全量
     * 登记项（修好后的完整标准）。argv[0]=java、classpath 取本进程（与 bin/wb 同源）。
     */
    private static void embedCommands(Map<String, Object> task, Context ctx, List<String> scope) {
        List<String> base = new ArrayList<>(List.of(
                "java", "-cp", System.getProperty("java.class.path"),
                "workbench.evals.l09.CancelChecks", "--no-report",
                "--target", ctx.candidate().toString(),
                "--python", ctx.python().toString()));
        List<String> reproduce = new ArrayList<>(base);
        for (String name : scope) {
            reproduce.add("--case");
            reproduce.add(name);
        }
        task.put("reproduce", Map.of("argv", reproduce, "cwd", repoRoot().toString()));
        task.put("acceptance", Map.of("argv", List.copyOf(base), "cwd", repoRoot().toString()));
    }

    // ---- CLI 面：REGISTRY repair-map ------------------------------------------------

    /** REGISTRY 缝（Main 静态注册段；{@code ./bin/wb repair-map …}）。 */
    public static int execute(String[] argv) {
        Args args;
        try {
            args = new Args(argv,
                    Set.of("--report", "--source-task", "--source-version", "--candidate",
                            "--objective", "--python", "--output", "--observed-exit", "--suite"),
                    Set.of(), List.of(), Set.of("--allowed-file", "--case"));
            for (String required : new String[]{"--report", "--source-task", "--source-version",
                    "--candidate", "--objective", "--python", "--output", "--observed-exit"}) {
                if (!args.has(required)) {
                    throw new Args.UsageException("the following arguments are required: " + required);
                }
            }
        } catch (Args.UsageException error) {
            System.err.println(error.getMessage());
            return 2;
        }
        try {
            String suite = args.optional("--suite", "blocking");
            if (!SUITES.contains(suite)) {
                System.err.println("--suite 须为 all/blocking/observing：" + suite);
                return 2;
            }
            Path output = Path.of(args.require("--output")).toAbsolutePath().normalize();
            // 拒绝覆盖旧输出（上游：即使本次报告本可不产任务，也先拒绝 stale 输出）
            if (Files.exists(output)) {
                return invalid("输出已存在；请换新路径，保留旧证据：" + output);
            }
            Context ctx = new Context(
                    args.require("--source-task"),
                    args.require("--source-version"),
                    Path.of(args.require("--candidate")).toAbsolutePath().normalize(),
                    args.require("--objective"),
                    args.repeatingList("--allowed-file"),
                    args.repeatingList("--case"),
                    args.requireInt("--observed-exit"),
                    Path.of(args.require("--python")).toAbsolutePath().normalize(),
                    Path.of(args.require("--report")).toAbsolutePath().normalize(),
                    suite);
            byte[] raw;
            try {
                raw = Files.readAllBytes(ctx.sourceReport());
            } catch (IOException error) {
                return invalid("报告读取失败：" + ctx.sourceReport() + "：" + error.getMessage());
            }
            Map<String, Object> result = map(raw, ctx);
            if ("repair_required".equals(result.get("status"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> task = (Map<String, Object>) result.get("task");
                if (output.getParent() != null) {
                    Files.createDirectories(output.getParent());
                }
                Files.writeString(output, PyJson.dumps(task) + "\n", StandardCharsets.UTF_8);
            }
            System.out.println(PyJson.dumpsCompact(result));
            return "invalid_report".equals(result.get("status")) ? 2 : 0;
        } catch (Args.UsageException error) {
            System.err.println(error.getMessage());
            return 2;
        } catch (IOException error) {
            System.err.println("草案输出写入失败：" + error.getMessage());
            return 1;
        }
    }

    private static int invalid(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "invalid_report");
        result.put("task", null);
        result.put("errors", List.of(message));
        System.out.println(PyJson.dumpsCompact(result));
        return 2;
    }

    // ---- 校验小面 ---------------------------------------------------------------

    private static final class MappingInvalid extends RuntimeException {
        MappingInvalid(String message) {
            super(message);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new MappingInvalid(message);
        }
    }

    /** utf-8-sig 容忍 BOM（上游 decode 语义）；重复键拒绝（上游 object_pairs_hook）。 */
    private static JsonNode parse(byte[] raw) {
        byte[] body = raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB
                && (raw[2] & 0xFF) == 0xBF ? java.util.Arrays.copyOfRange(raw, 3, raw.length) : raw;
        try {
            return MAPPER.readTree(body);
        } catch (IOException error) {
            throw new MappingInvalid("report is not valid JSON: " + error.getMessage());
        }
    }

    /** 写集路径安全（上游 unsafe allowed file 语义：相对、POSIX 规范形、无越界/敏感段、有后缀）。 */
    private static void requireSafeAllowedFile(String name) {
        require(!name.startsWith("/") && !name.contains("\\")
                        && !name.matches(".*[:*?\\u0000].*"), "unsafe allowed file: " + name);
        String[] parts = name.split("/", -1);
        for (String part : parts) {
            // "." 段拒绝（复查轮 T-6）：显式相对段（./x.py）非规范形，上游 PurePosixPath 拒
            require(!part.isEmpty() && !part.equals(".") && !part.equals("..") && !part.equals(".git")
                    && !part.equals(".codex") && !part.equals(".runtime")
                    && !part.equals(".tmp") && !part.startsWith(".env"),
                    "unsafe allowed file: " + name);
        }
        require(parts[parts.length - 1].indexOf('.') > 0, "unsafe allowed file: " + name);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean distinct(List<String> values) {
        return values.size() == values.stream().distinct().count();
    }

    private static boolean distinctNonBlank(List<String> values) {
        return values.stream().allMatch(RepairMapper::nonBlank) && distinct(values);
    }

    private static List<String> jsonTextList(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            values.add(item.asText(null));
        }
        return values;
    }

    private static java.util.Set<String> stringSet(JsonNode array) {
        java.util.Set<String> values = new java.util.LinkedHashSet<>();
        for (JsonNode item : array) {
            values.add(item.asText());
        }
        return values;
    }

    private static java.util.Set<String> stringSetOf(List<String> values) {
        return new java.util.LinkedHashSet<>(values);
    }

    private static int countPassed(JsonNode rows) {
        int count = 0;
        for (JsonNode row : rows) {
            if (row.path("passed").asBoolean(false)) {
                count++;
            }
        }
        return count;
    }

    private static int countObservingFailed(JsonNode rows) {
        int count = 0;
        for (JsonNode row : rows) {
            if ("observing".equals(row.path("level").asText())
                    && !row.path("passed").asBoolean(false)) {
                count++;
            }
        }
        return count;
    }

    private static int intOf(JsonNode summary, String field) {
        JsonNode node = summary.path(field);
        require(node.isInt(), "summary mismatch");
        return node.asInt();
    }

    private static String textOf(JsonNode summary, String field) {
        JsonNode node = summary.path(field);
        return node.isTextual() ? node.asText() : null;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    /** l06/l07/l08 Checks.repoRoot 同形：锚定本类装载位置（target/classes → 仓库根）。 */
    private static Path repoRoot() {
        try {
            return Path.of(RepairMapper.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().getParent().getParent();
        } catch (java.net.URISyntaxException error) {
            throw new IllegalStateException("仓库根定位失败（class 装载位置）", error);
        }
    }
}
