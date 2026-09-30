package workbench.evals.l07;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import workbench.testsupport.Cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L07 工具面合同测试：讲义 docs/lessons/L07-本地护栏Hook.md §2 C1..C8 → 用例映射
 * （本仓库无 Python 先例，映射源 = 讲义 §1.4 宿主映射 + 上游参考处理器
 * vendors/CodexFDE/.codex/hooks/quality_gate.py 与 hook_protocol_lab 六模式，只读对照）。
 *
 * <p>映射表（验收项 → 测试名 → 实际操作 → 比较什么）。本类用例全部为目标能力缺失红点组
 * （quality-gate 未注册 → rc 2 invalid choice；OrderChecks main 缺席 → rc 1 class not
 * found；hook_staging/ 投影缺失——三面起始红，讲义 §3 步骤 2；子进程缝无编译绑定，
 * L04/L05/L06 同法）。真树业务红绿（缺陷注入/修复）、手工协议实录与真实 Stop 事件链
 * 不在 mvn 面内，属候选期证据账（讲义 §3 步骤 4–7）；六类协议在本类以替身 harness
 * 命令承载（上游 hook_protocol_lab 替身同形——替身只证明适配层，不冒充真实事件）：
 *
 * <pre>
 * 红点组（目标能力缺失，commit 1 红）：
 * C4 block      → gateTranslatesHarnessFailureToBlock   → 替身 rc 1 + 尾行 5000
 *                 → gate rc 0 + decision block + reason 含「未通过」与尾行
 *                 （处理器 rc 0 = 协议交付成功，与业务失败两层退出码分离——讲义 §1.4）
 * C4 pass       → gatePassesThroughAndArchivesEventOnGreenHarness → 替身 rc 0
 *                 → gate rc 0 + 无 decision + systemMessage 含「已通过」
 *                 + events-dir 落盘 event.json（hook_event_name=Stop）与
 *                 response.json（与 stdout 同文）——真实事件三件证据的落盘面（C5）
 * C4 reentry    → gateSkipsReentryWithoutRerunningHarness → stop_hook_active=true
 *                 → 跳过响应（重入跳过）且替身未被调（marker 缺席——跳过≠复验）
 * C4 malformed  → gateBlocksOnMalformedEventJson         → 坏 JSON stdin
 *                 → block「未完成验证」
 * C4 timeout    → gateBlocksOnHarnessTimeout             → 替身 sleep 30 +
 *                 --harness-timeout-ms 1500 → block 未完成验证（内部超时可真实终止）
 * C4 cmd-error  → gateBlocksOnMissingHarnessCommand      → 替身路径不存在 → block 未完成验证
 * C4 事件校验   → gateRejectsNonStopEventName            → PreToolUse 事件名 → block 未完成验证
 * C4 cwd 校验   → gateRejectsCwdOutsideProject           → 事件 cwd 在仓库外 → block 未完成验证
 * C4 空输入     → gateNoStdinBlocksAsUnverified          → stdin 空（EOF）→ block 未完成验证
 * C4 注册面     → 上列各用例首断言 exitCode()==0（未注册时 rc 2 invalid choice 即红）
 * 工具面        → orderChecksRequiresTarget             → 缺 --target → rc 2（argparse 同形）
 * 工具面        → orderChecksRejectsTargetWithoutFlowerp → --target 普通目录 → rc 2
 *                 + stderr 词面「--target 下没有 flowerp/」（l06 驱动同款词面）
 * C4 投影       → hookStagingProjectionIsComplete        → hook_staging/ 三件在场：
 *                 settings.hooks.json（Stop 唯一条、type=command、timeout=120、
 *                 command 含 bin/wb 与 quality-gate）+ target.json（target 非空）
 *                 + README.md（声明片段 sha256 与实算一致——待审投影完整性）
 * </pre>
 *
 * <p>gate 参数面（正式安装命令不带，README 披露）：--harness-command（缺省 = 标准统一
 * 入口命令 java…OrderChecks --no-report）、--harness-timeout-ms（缺省 100000，外层
 * settings timeout 120s 留 20s 余量）、--events-dir（缺省 .runtime/hooks/）。
 */
class L07LocalGuardrailContractTest {

    private static final String GATE = "quality-gate";
    private static final String CHECKS_MAIN = "workbench.evals.l07.OrderChecks";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temp;

    // ---- 事件与替身助手（事件 JSON 全 ASCII 字面；替身 = /bin/sh 脚本，上游替身实验同形）----

    private String event(String name, String cwd, boolean stopHookActive) {
        return "{\"hook_event_name\":\"" + name + "\",\"session_id\":\"l07-contract\","
                + "\"transcript_path\":\"/tmp/l07-transcript.jsonl\",\"cwd\":\"" + cwd + "\","
                + "\"stop_hook_active\":" + stopHookActive + ",\"last_assistant_message\":\"OK\"}";
    }

    private String stopEvent(String cwd, boolean stopHookActive) {
        return event("Stop", cwd, stopHookActive);
    }

    private Path stub(String body) throws IOException {
        Path script = temp.resolve("stub-" + System.nanoTime() + ".sh");
        Files.writeString(script, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
        if (!script.toFile().setExecutable(true)) {
            throw new IllegalStateException("替身脚本不可执行：" + script);
        }
        return script;
    }

    private Cli.Result gate(String stdinEvent, String harnessCommand, String timeoutMs,
            String eventsDir) {
        return Cli.runWithInput(stdinEvent, GATE,
                "--harness-command", harnessCommand,
                "--harness-timeout-ms", timeoutMs,
                "--events-dir", eventsDir);
    }

    private String sha256(Path file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        return HexFormat.of().formatHex(digest);
    }

    // ---- 六类协议（C4；替身 harness，上游 hook_protocol_lab 六模式映射）----

    @Test
    void gateTranslatesHarnessFailureToBlock() throws Exception {
        Path red = stub("printf 'l07_draft_amount expected=12000 actual=5000\\n'; exit 1");
        Cli.Result result = gate(stopEvent(Cli.repoRoot().toString(), false),
                red.toString(), "100000", temp.resolve("ev-block").toString());
        assertThat(result.exitCode()).as("处理器以 rc 0 交付协议（两层退出码分离）").isZero();
        JsonNode response = Cli.json(result);
        assertThat(response.path("decision").asText()).isEqualTo("block");
        assertThat(response.path("reason").asText())
                .contains("未通过").contains("5000");
    }

    @Test
    void gateNamesFailedEntriesWhenReportParses() throws Exception {
        // schema 1.0 报告面（真实统一入口的输出形状）：reason 点名失败登记项而非报告尾十行
        // （首采抓获 ObjectNode 直 dumps 的崩溃——报告路径此前无合同，test-first 补）
        String report = "{\"schema_version\":\"1.0\",\"summary\":{\"total\":2,\"passed\":1,"
                + "\"blocking_failed\":1,\"observing_failed\":0,\"decision\":\"block\"},"
                + "\"results\":[{\"name\":\"l07_draft_amount\",\"level\":\"blocking\",\"passed\":false,"
                + "\"duration_ms\":5,\"evidence\":\"AssertionError: expected=12000 actual=5000\","
                + "\"error\":{\"type\":\"AssertionError\",\"message\":\"expected=12000 actual=5000\"}},"
                + "{\"name\":\"l07_rejected_order\",\"level\":\"blocking\",\"passed\":true,"
                + "\"duration_ms\":4,\"evidence\":\"ok\",\"error\":null}]}";
        Path jsonRed = stub("printf '%s' '" + report + "'; exit 1");
        Cli.Result result = gate(stopEvent(Cli.repoRoot().toString(), false),
                jsonRed.toString(), "100000", temp.resolve("ev-report").toString());
        assertThat(result.exitCode()).as("报告面同样以 rc 0 交付协议").isZero();
        JsonNode response = Cli.json(result);
        assertThat(response.path("decision").asText()).isEqualTo("block");
        assertThat(response.path("reason").asText())
                .contains("l07_draft_amount（blocking）").contains("5000").contains("blocking_failed");
    }

    @Test
    void gatePassesThroughAndArchivesEventOnGreenHarness() throws Exception {
        Path green = stub("printf 'all blocking checks passed\\n'");
        Path eventsDir = temp.resolve("ev-pass");
        Cli.Result result = gate(stopEvent(Cli.repoRoot().toString(), false),
                green.toString(), "100000", eventsDir.toString());
        assertThat(result.exitCode()).isZero();
        JsonNode response = Cli.json(result);
        assertThat(response.path("decision").asText("")).as("通过面无阻断决定").isEmpty();
        assertThat(response.path("systemMessage").asText()).contains("已通过");
        // 事件与响应落盘（C5 三件证据的落盘面）：恰好一个事件目录，event/response 各一
        try (var dirs = Files.list(eventsDir)) {
            var runDirs = dirs.filter(Files::isDirectory).toList();
            assertThat(runDirs).as("一次运行一个事件目录（x 模式不覆盖）").hasSize(1);
            JsonNode archived = MAPPER.readTree(Files.readString(runDirs.get(0).resolve("event.json")));
            assertThat(archived.path("hook_event_name").asText()).isEqualTo("Stop");
            JsonNode archivedResponse = MAPPER.readTree(
                    Files.readString(runDirs.get(0).resolve("response.json")));
            assertThat(archivedResponse.path("systemMessage").asText())
                    .isEqualTo(response.path("systemMessage").asText());
        }
    }

    @Test
    void gateSkipsReentryWithoutRerunningHarness() throws Exception {
        Path marker = temp.resolve("marker-reentry");
        Path probe = stub("printf ran > '" + marker + "'");
        Cli.Result result = gate(stopEvent(Cli.repoRoot().toString(), true),
                probe.toString(), "100000", temp.resolve("ev-reentry").toString());
        assertThat(result.exitCode()).isZero();
        JsonNode response = Cli.json(result);
        assertThat(response.path("decision").asText("")).as("重入跳过不产生阻断决定").isEmpty();
        assertThat(response.path("systemMessage").asText()).contains("重入跳过");
        assertThat(marker).as("跳过分支不再次调用统一入口（跳过≠复验）").doesNotExist();
    }

    @Test
    void gateBlocksOnMalformedEventJson() throws Exception {
        Path green = stub("printf 'should not run\\n'");
        Cli.Result result = gate("{not-json",
                green.toString(), "100000", temp.resolve("ev-malformed").toString());
        assertThat(result.exitCode()).isZero();
        JsonNode response = Cli.json(result);
        assertThat(response.path("decision").asText()).isEqualTo("block");
        assertThat(response.path("reason").asText()).contains("未完成验证");
    }

    @Test
    void gateBlocksOnHarnessTimeout() throws Exception {
        Path sleeper = stub("sleep 30");
        Cli.Result result = gate(stopEvent(Cli.repoRoot().toString(), false),
                sleeper.toString(), "1500", temp.resolve("ev-timeout").toString());
        assertThat(result.exitCode()).isZero();
        JsonNode response = Cli.json(result);
        assertThat(response.path("decision").asText()).isEqualTo("block");
        assertThat(response.path("reason").asText()).contains("未完成验证");
    }

    @Test
    void gateBlocksOnMissingHarnessCommand() throws Exception {
        String missing = temp.resolve("no-such-harness").toString();
        Cli.Result result = gate(stopEvent(Cli.repoRoot().toString(), false),
                missing, "100000", temp.resolve("ev-cmderr").toString());
        assertThat(result.exitCode()).isZero();
        JsonNode response = Cli.json(result);
        assertThat(response.path("decision").asText()).isEqualTo("block");
        assertThat(response.path("reason").asText()).contains("未完成验证");
    }

    // ---- 事件校验面（C4：stdin 契约——事件名与 cwd）----

    @Test
    void gateRejectsNonStopEventName() throws Exception {
        Path green = stub("printf 'should not run\\n'");
        Cli.Result result = gate(event("PreToolUse", Cli.repoRoot().toString(), false),
                green.toString(), "100000", temp.resolve("ev-name").toString());
        assertThat(result.exitCode()).isZero();
        JsonNode response = Cli.json(result);
        assertThat(response.path("decision").asText()).isEqualTo("block");
        assertThat(response.path("reason").asText()).contains("未完成验证");
    }

    @Test
    void gateRejectsCwdOutsideProject() throws Exception {
        Path green = stub("printf 'should not run\\n'");
        Cli.Result result = gate(stopEvent(temp.toString(), false),
                green.toString(), "100000", temp.resolve("ev-cwd").toString());
        assertThat(result.exitCode()).isZero();
        JsonNode response = Cli.json(result);
        assertThat(response.path("decision").asText()).isEqualTo("block");
        assertThat(response.path("reason").asText()).contains("未完成验证");
    }

    @Test
    void gateNoStdinBlocksAsUnverified() throws Exception {
        Path green = stub("printf 'should not run\\n'");
        Cli.Result result = gate("",
                green.toString(), "100000", temp.resolve("ev-nostdin").toString());
        assertThat(result.exitCode()).isZero();
        JsonNode response = Cli.json(result);
        assertThat(response.path("decision").asText()).isEqualTo("block");
        assertThat(response.path("reason").asText()).contains("未完成验证");
    }

    // ---- OrderChecks 统一入口工具面（argv 校验，l06 驱动同形）----

    @Test
    void orderChecksRequiresTarget() {
        Cli.Result result = Cli.runMain(CHECKS_MAIN);
        assertThat(result.exitCode()).as("缺 --target 是用法错误 rc 2").isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("--target");
    }

    @Test
    void orderChecksRejectsTargetWithoutFlowerp() throws Exception {
        Path plain = temp.resolve("plain-target");
        Files.createDirectories(plain);
        Cli.Result result = Cli.runMain(CHECKS_MAIN, "--target", plain.toString());
        assertThat(result.exitCode()).as("--target 非客户树拒绝 rc 2").isEqualTo(2);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("--target 下没有 flowerp/");
    }

    // ---- hook_staging 待审投影（C4：配置与处理器分离受审）----

    @Test
    void hookStagingProjectionIsComplete() throws Exception {
        Path staging = Cli.repoRoot().resolve("hook_staging");
        Path fragment = staging.resolve("settings.hooks.json");
        assertThat(fragment).as("待审安装片段在场").exists();
        JsonNode stop = MAPPER.readTree(Files.readString(fragment)).path("hooks").path("Stop");
        assertThat(stop.isArray()).isTrue();
        assertThat(stop.size()).as("Stop 恰一个 matcher 组").isEqualTo(1);
        JsonNode hook = stop.get(0).path("hooks").get(0);
        assertThat(hook.path("type").asText()).isEqualTo("command");
        assertThat(hook.path("timeout").asInt()).as("外层 120s，对内层 100s 留余量").isEqualTo(120);
        assertThat(hook.path("command").asText())
                .contains("bin/wb").contains("quality-gate");

        Path target = staging.resolve("target.json");
        assertThat(target).as("检查目标 pin 在场").exists();
        assertThat(MAPPER.readTree(Files.readString(target)).path("target").asText(""))
                .as("pin 的目标树路径非空").isNotEmpty();

        Path readme = staging.resolve("README.md");
        assertThat(readme).as("安装/回滚/重指流程与指纹清单在场").exists();
        assertThat(Files.readString(readme))
                .as("README 声明的安装片段指纹与实算一致（待审投影完整性）")
                .contains(sha256(fragment));
    }
}
