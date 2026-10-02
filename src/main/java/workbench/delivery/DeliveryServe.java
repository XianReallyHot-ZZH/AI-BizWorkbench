package workbench.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import workbench.bootstrap.Args;
import workbench.bootstrap.PyJson;
import workbench.execution.ProcessRunner;
import workbench.execution.Shlex;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REGISTRY {@code delivery-serve}：交付任务 API 常驻服务（上游
 * {@code workbench/workbench_server.py} {@code serve()} 对应物，L13 讲义 D2——
 * repair-map/loop-run/graph-run 之后的第五个工作台命令，同一注册缝）。
 *
 * <p>CLI 面：{@code --runtime-dir}（必填；缺省即拒绝——服务永不落隐式目录）、
 * {@code --host}（缺省 127.0.0.1）、{@code --port}（缺省 0 = 随机分配）、
 * {@code --suite-command}（suite 缝的进程面：stdout 末行 = schema 1.0 报告、
 * rc 0 绿 / 1 红——LoopRunner/GraphRunner 同款；缺省 = 客户绑定 eval
 * {@code purchase_requires_approval} 原名照跑——「补货需求」的业务权威，
 * 子进程 cwd = vendors/flowERP，ADR-0005）。启动后 stdout 打监听行 JSON
 * （{"ok":true,"port":N,…}），随后常驻直到被停止（Ctrl-C / 进程销毁）。
 */
public final class DeliveryServe {

    /** 检查子进程超时（GraphRunner SUITE_TIMEOUT_SECONDS 同值先例）。 */
    private static final int SUITE_TIMEOUT_SECONDS = 300;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeliveryServe() {}

    public static int execute(String[] argv) {
        Args args = new Args(argv,
                java.util.Set.of("--runtime-dir", "--host", "--port", "--suite-command"),
                java.util.Set.of(), java.util.List.of(), java.util.Set.of());
        String runtimeDir = args.optional("--runtime-dir", "");
        if (runtimeDir.isBlank()) {
            throw new Args.UsageException(
                    "delivery-serve: the following arguments are required: --runtime-dir");
        }
        String host = args.optional("--host", "127.0.0.1");
        int port = parsePort(args.optional("--port", "0"));
        String suiteCommand = args.optional("--suite-command", "");
        Workflow.SuiteRunner suite = suiteCommand.isBlank()
                ? defaultSuiteRunner()
                : suiteCommandRunner(suiteCommand, Path.of("").toAbsolutePath());
        HttpApi api = new HttpApi(Path.of(runtimeDir), suite);
        int bound;
        try {
            bound = api.start(host, port);
        } catch (IOException error) {
            throw new java.io.UncheckedIOException("delivery-serve 监听失败：" + host + ":" + port, error);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(api::stop));
        Map<String, Object> listening = new LinkedHashMap<>();
        listening.put("ok", true);
        listening.put("listening", host + ":" + bound);
        listening.put("port", bound);
        listening.put("runtime_dir", Path.of(runtimeDir).toAbsolutePath().normalize().toString());
        listening.put("suite", suiteCommand.isBlank() ? "default:purchase_requires_approval" : suiteCommand);
        System.out.println(PyJson.dumpsCompact(listening));
        System.out.flush();
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }
    }

    private static int parsePort(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || parsed > 65535) {
                throw new Args.UsageException("--port 必须在 0..65535：" + value);
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new Args.UsageException("--port 必须是整数：" + value);
        }
    }

    /**
     * suite 缝的进程面（LoopRunner/GraphRunner --suite-command 同款）：Shlex 拆分、
     * 超时 / 退出码越界（预期 0|1）→ 异常、stdout 末行解析为报告对象
     * （客户 harness 输出形态——SalesChecks.customerCase tail 同口径）。
     */
    static Workflow.SuiteRunner suiteCommandRunner(String suiteCommand, java.nio.file.Path cwd) {
        return () -> {
            java.util.List<String> command;
            try {
                command = Shlex.split(suiteCommand);
            } catch (Shlex.Unparseable error) {
                throw new IllegalStateException("--suite-command 无法解析：" + suiteCommand, error);
            }
            ProcessRunner.Outcome check = ProcessRunner.run(command,
                    cwd, SUITE_TIMEOUT_SECONDS, null);
            if (check.timedOut()) {
                throw new IllegalStateException("检查子进程超时（" + SUITE_TIMEOUT_SECONDS + "s）");
            }
            if (check.returncode() == null || (check.returncode() != 0 && check.returncode() != 1)) {
                throw new IllegalStateException(
                        "检查退出码越界（预期 0|1，实际 " + check.returncode() + "）");
            }
            String stdout = check.stdoutText().strip();
            String tail = stdout.isEmpty() ? "" : stdout.substring(stdout.lastIndexOf('\n') + 1);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> report = MAPPER.readValue(tail, Map.class);
                return report;
            } catch (IOException error) {
                throw new IllegalStateException("检查子进程 stdout 末行不是 JSON 报告：" + tail, error);
            }
        };
    }

    /** 缺省 suite：客户 purchase_requires_approval 原名照跑（cwd = vendors/flowERP）。 */
    static Workflow.SuiteRunner defaultSuiteRunner() {
        Path repoRoot = repoRoot();
        String command = repoRoot.resolve(".venv/bin/python") + " -X utf8 -m eval.harness"
                + " --case purchase_requires_approval --no-report";
        return suiteCommandRunner(command, repoRoot.resolve("vendors").resolve("flowERP"));
    }

    /** l06–l12 Checks.repoRoot 同形：锚定本类装载位置（target/classes → 仓库根）。 */
    static Path repoRoot() {
        try {
            return Path.of(DeliveryServe.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().getParent().getParent();
        } catch (URISyntaxException error) {
            throw new IllegalStateException("仓库根定位失败（class 装载位置）", error);
        }
    }
}
