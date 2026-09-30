package workbench.evals.l07;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import workbench.bootstrap.Args;
import workbench.bootstrap.PyJson;
import workbench.execution.Shlex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 本地护栏处理器（L07 合同 workbenchIncrement「提交前本地护栏」；上游参考处理器
 * {@code vendors/CodexFDE/.codex/hooks/quality_gate.py} 的 ADR-0002 映射，协议 = Claude
 * Code Stop hook，官方文档现查 + claude 2.1.283 实证——讲义 §1.4 映射表）。
 *
 * <p>读 stdin 事件 JSON → 校验（事件名 Stop、cwd 在本仓库内）→ 重入短路
 * （{@code stop_hook_active}——跳过没有产生新的通过证据）→ 子进程调统一入口
 * {@link OrderChecks}（缺省命令由 {@code hook_staging/target.json} pin 目标树组装；
 * 内部超时缺省 100s，外层 settings timeout 120s 留 20s 余量，双层超时可真实终止）→
 * 翻译为 Stop 协议响应：通过 → {@code systemMessage}（放行停止）；阻断级检查失败 →
 * {@code decision:"block"} + reason（输出尾十行）；处理器自身任何故障 → block
 * 「未完成验证」（失败不能显示成成功——铁律 3）。处理器恒以 rc 0 交付协议（协议交付
 * 成功 ≠ 业务通过，两层退出码分离）；stdout 只出协议 JSON，诊断走 stderr。
 *
 * <p>事件留痕：每次调用在 events-dir（缺省 {@code .runtime/hooks/}）建
 * {@code <UTC 毫秒>-<session>/} 独占目录，落 {@code event.json}（stdin 原文）、
 * {@code response.json} 与 harness 输出两件——真实事件三件证据的落盘面（C5）；
 * 归档失败只记 stderr，不吞协议。
 *
 * <p>argv 面（正式安装命令不带，hook_staging/README 披露）：{@code --harness-command}
 * （shell 形命令串，替身注入缝——六类协议合同测试用）、{@code --harness-timeout-ms}
 * （缺省 100000）、{@code --events-dir}（缺省 .runtime/hooks）。
 */
public final class QualityGate {

    private static final long DEFAULT_HARNESS_TIMEOUT_MS = 100_000L;
    private static final String DEFAULT_EVENTS_DIR = ".runtime/hooks";
    private static final String DEFAULT_CHECKS_MAIN = "workbench.evals.l07.OrderChecks";

    /** 处理器未完成验证的统一载体（翻译为 block 响应，不裸抛——协议必须交付）。 */
    private static final class GateFailure extends RuntimeException {
        GateFailure(String message) {
            super(message);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private QualityGate() {}

    /** REGISTRY 缝（Main 静态注册段，L07 注册调用——讲义 D2：工作台增量即工作台命令）。 */
    public static int execute(String[] argv) {
        Args args = new Args(argv,
                Set.of("--harness-command", "--harness-timeout-ms", "--events-dir"), Set.of());
        long timeoutMs = longOption(args, "--harness-timeout-ms", DEFAULT_HARNESS_TIMEOUT_MS);
        Path eventsDir = repoRoot().resolve(args.optional("--events-dir", DEFAULT_EVENTS_DIR));
        String rawEvent = readStdin();

        JsonNode event = null;
        String problem = null;
        try {
            event = parseEvent(rawEvent);
        } catch (GateFailure error) {
            problem = error.getMessage();
        }
        Path runDir = event == null ? null : newRunDir(eventsDir, event);

        Map<String, Object> response;
        if (problem != null) {
            response = unverified(problem);
        } else {
            try {
                validateEvent(event);
                response = respond(event, args, timeoutMs, runDir);
            } catch (GateFailure error) {
                response = unverified(error.getMessage());
            }
        }
        if (runDir != null) {
            archive(runDir.resolve("event.json"), rawEvent);
            archive(runDir.resolve("response.json"), PyJson.dumpsCompact(response));
        }
        System.out.println(PyJson.dumpsCompact(response));
        return 0;
    }

    // ---- 事件契约（stdin → 校验）----

    private static JsonNode parseEvent(String rawEvent) {
        try {
            JsonNode node = MAPPER.readTree(rawEvent);
            if (!node.isObject()) {
                throw new GateFailure("事件不是 JSON 对象");
            }
            return node;
        } catch (IOException error) {
            throw new GateFailure("事件 JSON 不可解析：" + rawEvent.strip());
        }
    }

    private static void validateEvent(JsonNode event) {
        String name = event.path("hook_event_name").asText("");
        if (!"Stop".equals(name)) {
            throw new GateFailure("事件不是 Stop 事件：" + (name.isEmpty() ? "(缺 hook_event_name)" : name));
        }
        String cwd = event.path("cwd").asText("");
        if (cwd.isBlank()) {
            throw new GateFailure("事件缺少 cwd");
        }
        Path resolved = Path.of(cwd).toAbsolutePath().normalize();
        Path root = repoRoot();
        if (!resolved.equals(root) && !resolved.startsWith(root)) {
            throw new GateFailure("事件 cwd 不在本项目内：" + resolved + "（项目根 " + root + "）");
        }
    }

    // ---- 响应翻译（上游参考处理器同形）----

    private static Map<String, Object> respond(JsonNode event, Args args, long timeoutMs, Path runDir) {
        if (event.path("stop_hook_active").asBoolean(false)) {
            return ordered("systemMessage",
                    "重入跳过；本次未复验。结束前仍须显式运行阻断级检查。");
        }
        List<String> harness = harnessCommand(args);
        Path stdoutFile;
        Path stderrFile;
        try {
            stdoutFile = runDir != null
                    ? runDir.resolve("harness-stdout.txt")
                    : Files.createTempFile("gate-harness-stdout-", ".txt");
            stderrFile = runDir != null
                    ? runDir.resolve("harness-stderr.txt")
                    : Files.createTempFile("gate-harness-stderr-", ".txt");
        } catch (IOException error) {
            throw new GateFailure("harness 输出文件不可创建：" + error.getMessage());
        }
        Process process;
        try {
            process = new ProcessBuilder(harness).directory(repoRoot().toFile())
                    .redirectOutput(stdoutFile.toFile())
                    .redirectError(stderrFile.toFile())
                    .start();
        } catch (IOException error) {
            throw new GateFailure("统一入口启动失败：" + String.join(" ", harness)
                    + "：" + error.getMessage());
        }
        boolean finished;
        try {
            finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new GateFailure("统一入口等待被中断");
        }
        if (!finished) {
            process.destroyForcibly();
            throw new GateFailure("统一入口执行超时（" + timeoutMs + "ms）");
        }
        int code = process.exitValue();
        if (code == 0) {
            return ordered("systemMessage", "当前阻断级检查已通过；不代表 FlowERP 业务验收。");
        }
        String tail = lastLines(readAll(stdoutFile) + "\n" + readAll(stderrFile), 10);
        return ordered("decision", "block",
                "reason", "阻断级检查未通过。修复后显式复验：\n" + tail);
    }

    /** 替身注入优先；缺省命令 = 同 classpath 起新 JVM 跑统一入口，目标树由 pin 解析。 */
    private static List<String> harnessCommand(Args args) {
        if (args.has("--harness-command")) {
            try {
                return Shlex.split(args.require("--harness-command"));
            } catch (Shlex.Unparseable error) {
                throw new GateFailure("--harness-command 不是合法的 shell 形命令串：" + error.getMessage());
            }
        }
        Path pin = repoRoot().resolve("hook_staging").resolve("target.json");
        if (!Files.isRegularFile(pin)) {
            throw new GateFailure("目标 pin 缺失：" + pin);
        }
        String target;
        try {
            target = MAPPER.readTree(Files.readString(pin, StandardCharsets.UTF_8))
                    .path("target").asText("");
        } catch (IOException error) {
            throw new GateFailure("目标 pin 不可读：" + pin);
        }
        if (target.isBlank()) {
            throw new GateFailure("目标 pin 未声明 target：" + pin);
        }
        Path targetAbs = Path.of(target).isAbsolute()
                ? Path.of(target).toAbsolutePath().normalize()
                : repoRoot().resolve(target).toAbsolutePath().normalize();
        return List.of("java",
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-cp", System.getProperty("java.class.path"), DEFAULT_CHECKS_MAIN,
                "--no-report", "--target", targetAbs.toString());
    }

    private static Map<String, Object> unverified(String detail) {
        return ordered("decision", "block",
                "reason", "Hook 未完成验证，请修复环境或配置后显式复验：" + detail);
    }

    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    // ---- 事件留痕（归档失败只记 stderr，不吞协议）----

    private static Path newRunDir(Path eventsDir, JsonNode event) {
        String session = event.path("session_id").asText("no-session").replaceAll("[^A-Za-z0-9-]", "");
        if (session.isBlank()) {
            session = "no-session";
        }
        try {
            Files.createDirectories(eventsDir);
            return Files.createDirectory(eventsDir.resolve(System.currentTimeMillis() + "-" + session));
        } catch (IOException error) {
            System.err.println("事件归档失败（协议继续）：" + error.getMessage());
            return null;
        }
    }

    private static void archive(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException error) {
            System.err.println("事件归档失败（协议继续）：" + file + "：" + error.getMessage());
        }
    }

    // ---- 助手 ----

    private static String readStdin() {
        try {
            return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new GateFailure("stdin 读取失败：" + error.getMessage());
        }
    }

    private static String readAll(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException error) {
            return "";
        }
    }

    /** Python {@code "\n".join(text.splitlines()[-10:])} 同形：输出尾十行。 */
    private static String lastLines(String text, int limit) {
        String[] lines = text.strip().split("\n");
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, lines.length - limit); i < lines.length; i++) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(lines[i]);
        }
        return out.toString();
    }

    private static long longOption(Args args, String name, long defaultValue) {
        String raw = args.optional(name, String.valueOf(defaultValue));
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException error) {
            throw new Args.UsageException("argument " + name + ": invalid long value: '" + raw + "'");
        }
    }

    /** l06 Checks.repoRoot 同形：锚定本类装载位置（target/classes → 仓库根），不随 cwd 漂移。 */
    private static Path repoRoot() {
        try {
            return Path.of(QualityGate.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().getParent().getParent();
        } catch (java.net.URISyntaxException error) {
            throw new GateFailure("仓库根定位失败（class 装载位置）");
        }
    }
}
