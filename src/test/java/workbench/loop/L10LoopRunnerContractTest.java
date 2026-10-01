package workbench.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import workbench.testsupport.Cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L10 Loop 控制器合同测试：讲义 docs/lessons/L10-有停止条件的修复Loop.md §2 C4/C5/C6 →
 * 用例映射（无 Python 先例第二讲；映射源 = 上游 agent/loop.py 决策序 + loop_control_lab
 * 十二模式的堵洞目标，只读对照）。独立预期源 = 上游十二模式表格（检查数/执行数/状态名
 * 逐项）与 run_loop 源码语义，不抄实现返回值。
 *
 * <p>接缝（spec docs/specs/L10-*.md 已具名确认）：REGISTRY 命令 {@code loop-run} CLI
 * 子进程缝为唯一新高缝——十二模式的合成输入经 {@code --suite-command}/{@code --patch-command}
 * 脚本注入（计数脚本按调用序输出报告；上游 loop_control_lab 用进程内 mock，本仓库以
 * CLI 参数即注入缝承载，更高缝同语义）。全经真实子进程断言，不直调内部函数（仓库
 * 测试口径）。
 *
 * <p>与上游参考实现的四处已裁决差异（讲义 D4 全补，逐条断言）：
 * executor 异常/超时 → 结构化 {@code stopped_executor_error}（上游裸抛）；空报告 →
 * {@code stopped_invalid_report} 拒绝（上游误判 converged 的接口反例）；末轮修改后
 * {@code pending_verification} 显式标记（上游无此状态）；检查子进程独立超时。
 *
 * <p>红点组（commit 1，讲义 §3 步骤 2）：{@code loop-run} 未注册 → rc 2 invalid
 * choice，全部目标行为断言失败即红；实现转绿后本类零改动。
 *
 * <pre>
 * 用例 → 合同映射：
 * loopRunValidatesMaxRounds             C4 参数校验：1..10 之外 rc 2
 * loopRunValidatesBudgetAndTimeout      C4 参数校验：预算/超时 &gt;0
 * loopRunRequiresSuiteCommand           C4 argv 面：缺检查命令 rc 2
 * loopRunRejectsExistingRuntimeDir      C6 运行隔离：已存在目录拒绝（旧证据不可抹）
 * loopRunRequiresPatchCommandForPatch   C4 argv 面：patch 执行器须显式命令
 * loopAlreadyGreen                      C5 already-green：1 检查/0 执行 converged
 * loopConverge                          C5 converge：2/1 converged（修复后下一轮检查通过）
 * loopRepeated                          C5 repeated：2/1 stopped_no_progress（相邻签名同）
 * loopChangedReason                     C5 changed-reason：同名原因变化仍停（签名只看名）
 * loopOscillating                       C5 oscillating：A/B/A 到三轮上限 3/3
 * loopLastRepair                        C5/C6 last-repair：1/1 stopped_max_rounds + 待复验标记
 * loopTokenBudget                       C4 token-budget：150&gt;100 剩 0 停（软边界不回退）
 * loopMissingUsage                      C4 missing-usage：缺正用量停止核算
 * loopTimeBudget                        C4 time-budget：轮前时间检查 1/0
 * loopExecutorErrorOnTimeout            C4/D4 执行器超时结构化停止（不再裸抛）
 * loopExecutorErrorOnExitCode           C4/D4 执行器非零退出结构化停止
 * loopEmptyReport                       C5/D4 空报告拒绝（不再误判 converged）
 * loopDryRun                            C4 dry-run：只生成任务零执行
 * </pre>
 */
class L10LoopRunnerContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger RUN = new AtomicInteger();

    // ---- 合成输入（schema 1.0 客户报告合同形；L09 边界机检同款独立预期源） ----------

    private static String report(String name, boolean passed, String evidence) {
        return "{\"schema_version\": \"1.0\", \"suite\": \"all\", \"generated_at\": \"2026-10-02T00:00:00\", "
                + "\"requested_cases\": [\"" + name + "\"], "
                + "\"results\": [{\"name\": \"" + name + "\", \"level\": \"blocking\", \"passed\": " + passed + ", "
                + "\"evidence\": \"" + evidence + "\", \"duration_ms\": 5}], "
                + "\"summary\": {\"total\": 1, \"passed\": " + (passed ? 1 : 0)
                + ", \"blocking_failed\": " + (passed ? 0 : 1)
                + ", \"observing_failed\": 0, \"decision\": \"" + (passed ? "pass" : "block") + "\"}}";
    }

    private static final String RED_A = report("TEACHING_A", false, "synthetic fixed observation");
    private static final String RED_A_REASON_2 = report("TEACHING_A", false, "synthetic reason 1");
    private static final String RED_B = report("TEACHING_B", false, "synthetic fixed observation");
    private static final String GREEN = report("TEACHING_A", true, "ok");

    /**
     * suite 计数脚本：按调用序输出序列第 N 份报告（序列尽后重复末份），退出码随红绿
     * （block→1 / pass→0——退出码与结论一致是报告合同面）。脚本旁 .cnt 计数文件即
     * 调用历史，多轮可复现。
     */
    private static Path suiteScript(Path dir, String name, String... sequence) throws IOException {
        StringBuilder sh = new StringBuilder("#!/bin/sh\n"
                + "n=$(cat \"$0.cnt\" 2>/dev/null || echo 0)\n"
                + "echo $((n+1)) > \"$0.cnt\"\n"
                + "case \"$n\" in\n");
        for (int i = 0; i < sequence.length - 1; i++) {
            sh.append(String.format("%d) printf '%%s\\n' '%s'; exit %s ;;\n",
                    i, sequence[i], sequence[i].contains("\"decision\": \"block\"") ? "1" : "0"));
        }
        String last = sequence[sequence.length - 1];
        sh.append(String.format("*) printf '%%s\\n' '%s'; exit %s ;;\nesac\n",
                last, last.contains("\"decision\": \"block\"") ? "1" : "0"));
        return executableScript(dir, name, sh.toString());
    }

    /** patch 教学执行器脚本：可 sleep（超时面）/输出单行（usage 面）/自定义退出码。 */
    private static Path patchScript(Path dir, String name, Integer sleepSeconds,
                                    String stdoutLine, int exitCode) throws IOException {
        StringBuilder sh = new StringBuilder("#!/bin/sh\n");
        if (sleepSeconds != null) {
            sh.append("sleep ").append(sleepSeconds).append('\n');
        }
        if (stdoutLine != null) {
            sh.append("printf '%s\\n' '").append(stdoutLine).append("'\n");
        }
        sh.append("exit ").append(exitCode).append('\n');
        return executableScript(dir, name, sh.toString());
    }

    private static Path executableScript(Path dir, String name, String content) throws IOException {
        Path script = dir.resolve(name);
        Files.writeString(script, content, StandardCharsets.UTF_8);
        if (!script.toFile().setExecutable(true)) {
            throw new IOException("脚本不可执行：" + script);
        }
        return script;
    }

    /** 干净候选目录 + 占位 python（RepairMapper 上下文校验面：目录在场、解释器为文件）。 */
    private static Path fakeCandidate(Path tmp) throws IOException {
        Path candidate = Files.createDirectories(tmp.resolve("candidate-" + RUN.incrementAndGet()));
        Files.createDirectories(candidate.resolve("flowerp"));
        Path python = tmp.resolve("python3");
        if (!Files.exists(python)) {
            Files.writeString(python, "");
            python.toFile().setExecutable(true);
        }
        return candidate;
    }

    /** 跑一次 loop-run（每次调用新运行目录——运行隔离由实现保证已存在拒绝，这里预建前不撞）。 */
    private static Cli.Result runLoop(Path tmp, Path candidate, String suiteCommand,
                                      String... extra) throws IOException {
        Path runtimeDir = tmp.resolve("runtime-" + RUN.incrementAndGet());
        List<String> argv = new ArrayList<>(List.of(
                "loop-run",
                "--suite-command", suiteCommand,
                "--runtime-dir", runtimeDir.toString(),
                "--candidate", candidate.toString(),
                "--python", tmp.resolve("python3").toString(),
                "--source-task", "CASE-WB-L10-TEST",
                "--source-version", "l10-test",
                "--allowed-file", "flowerp/service.py",
                "--case", "TEACHING_A"));
        argv.addAll(List.of(extra));
        return Cli.run(argv.toArray(String[]::new));
    }

    private static JsonNode readJson(Path file) {
        try {
            return MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IllegalStateException("loop-result 不可读：" + file, error);
        }
    }

    private static JsonNode runtimeResult(Path tmp) {
        return readJson(latestRuntime(tmp).resolve("loop-result.json"));
    }

    private static Path latestRuntime(Path tmp) {
        try (var stream = Files.list(tmp)) {
            return stream.filter(p -> p.getFileName().toString().startsWith("runtime-"))
                    .max(Path::compareTo)
                    .orElseThrow(() -> new IllegalStateException("没有 runtime 目录"));
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    /** 执行次数 = history 中 executor 条目非 dry-run 且在场的轮数。 */
    private static int executions(JsonNode result) {
        int count = 0;
        for (JsonNode entry : result.path("history")) {
            JsonNode executor = entry.path("executor");
            if (!executor.isMissingNode() && !executor.asText("").startsWith("dry-run")) {
                count++;
            }
        }
        return count;
    }

    // ---- argv / 参数校验面 --------------------------------------------------------------

    @Test
    void loopRunValidatesMaxRounds(@TempDir Path tmp) throws IOException {
        Path candidate = fakeCandidate(tmp);
        Path script = suiteScript(tmp, "suite.sh", GREEN);

        Cli.Result tooSmall = runLoop(tmp, candidate, script.toString(), "--max-rounds", "0");
        assertThat(tooSmall.exitCode()).isEqualTo(2);
        assertThat(tooSmall.stderr() + tooSmall.stdout()).contains("max_rounds");

        Cli.Result tooLarge = runLoop(tmp, candidate, script.toString(), "--max-rounds", "11");
        assertThat(tooLarge.exitCode()).isEqualTo(2);
        assertThat(tooLarge.stderr() + tooLarge.stdout()).contains("max_rounds");
    }

    @Test
    void loopRunValidatesBudgetAndTimeout(@TempDir Path tmp) throws IOException {
        Path candidate = fakeCandidate(tmp);
        Path script = suiteScript(tmp, "suite.sh", GREEN);

        Cli.Result budget = runLoop(tmp, candidate, script.toString(), "--token-budget", "0");
        assertThat(budget.exitCode()).isEqualTo(2);
        assertThat(budget.stderr() + budget.stdout()).contains("token_budget");

        Cli.Result timeout = runLoop(tmp, candidate, script.toString(), "--timeout", "0");
        assertThat(timeout.exitCode()).isEqualTo(2);
        assertThat(timeout.stderr() + timeout.stdout()).contains("timeout");
    }

    @Test
    void loopRunRequiresSuiteCommand(@TempDir Path tmp) throws IOException {
        Path candidate = fakeCandidate(tmp);
        Path runtimeDir = tmp.resolve("runtime-no-suite");
        // 缺 --suite-command（不传，非空串）——用法错误
        Cli.Result result = Cli.run("loop-run",
                "--runtime-dir", runtimeDir.toString(),
                "--candidate", candidate.toString(),
                "--python", tmp.resolve("python3").toString(),
                "--source-task", "CASE-WB-L10-TEST",
                "--source-version", "l10-test",
                "--allowed-file", "flowerp/service.py",
                "--case", "TEACHING_A");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("suite-command");
    }

    @Test
    void loopRunRejectsExistingRuntimeDir(@TempDir Path tmp) throws IOException {
        Path candidate = fakeCandidate(tmp);
        Path script = suiteScript(tmp, "suite.sh", GREEN);
        Path occupied = Files.createDirectories(tmp.resolve("occupied"));
        Files.writeString(occupied.resolve("旧证据"), "keep", StandardCharsets.UTF_8);

        Cli.Result rejected = Cli.run("loop-run",
                "--suite-command", script.toString(),
                "--runtime-dir", occupied.toString(),
                "--candidate", candidate.toString(),
                "--python", tmp.resolve("python3").toString(),
                "--source-task", "CASE-WB-L10-TEST",
                "--source-version", "l10-test",
                "--allowed-file", "flowerp/service.py",
                "--case", "TEACHING_A");

        assertThat(rejected.exitCode()).isEqualTo(1);
        assertThat(rejected.stderr()).contains("runtime");
        assertThat(occupied.resolve("旧证据")).hasContent("keep");
    }

    @Test
    void loopRunRequiresPatchCommandForPatch(@TempDir Path tmp) throws IOException {
        Path candidate = fakeCandidate(tmp);
        Path script = suiteScript(tmp, "suite.sh", RED_A);

        Cli.Result result = runLoop(tmp, candidate, script.toString(),
                "--executor", "patch", "--max-rounds", "1");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("patch-command");
    }

    // ---- 十二控制模式（上游表格逐项对齐） ----------------------------------------------

    @Test
    void loopAlreadyGreen(@TempDir Path tmp) throws IOException {
        Path candidate = fakeCandidate(tmp);
        Path script = suiteScript(tmp, "suite.sh", GREEN);

        Cli.Result result = runLoop(tmp, candidate, script.toString());
        JsonNode loop = runtimeResult(tmp);

        assertThat(result.exitCode()).isZero();
        assertThat(loop.path("status").asText()).isEqualTo("converged");
        assertThat(loop.path("history")).hasSize(1);
        assertThat(executions(loop)).isZero();
        assertThat(loop.path("pending_verification").asBoolean()).isFalse();
    }

    @Test
    void loopConverge(@TempDir Path tmp) throws IOException {
        Path candidate = fakeCandidate(tmp);
        Path suite = suiteScript(tmp, "suite.sh", RED_A, GREEN);
        Path patch = patchScript(tmp, "patch.sh", null,
                "{\"usage\": {\"total_tokens\": 10}, \"source\": \"TEACHING predetermined patch\"}", 0);

        Cli.Result result = runLoop(tmp, candidate, suite.toString(),
                "--executor", "patch", "--patch-command", patch.toString());
        JsonNode loop = runtimeResult(tmp);

        assertThat(result.exitCode()).isZero();
        assertThat(loop.path("status").asText()).isEqualTo("converged");
        assertThat(loop.path("history")).hasSize(2);
        assertThat(executions(loop)).isEqualTo(1);
        assertThat(loop.path("remaining_failures")).isEmpty();
    }

    @Test
    void loopRepeated(@TempDir Path tmp) throws IOException {
        Path candidate = fakeCandidate(tmp);
        Path suite = suiteScript(tmp, "suite.sh", RED_A);
        Path patch = patchScript(tmp, "patch.sh", null,
                "{\"usage\": {\"total_tokens\": 10}, \"source\": \"TEACHING no-change patch\"}", 0);

        Cli.Result result = runLoop(tmp, candidate, suite.toString(),
                "--executor", "patch", "--patch-command", patch.toString());
        JsonNode loop = runtimeResult(tmp);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(loop.path("status").asText()).isEqualTo("stopped_no_progress");
        assertThat(loop.path("history")).hasSize(2);
        assertThat(executions(loop)).isEqualTo(1);
        assertThat(loop.path("remaining_failures").findValuesAsText("name"))
                .isEmpty();
        assertThat(loop.path("remaining_failures")).isEqualTo(
                MAPPER.readTree("[\"TEACHING_A\"]"));
    }

    @Test
    void loopChangedReason(@TempDir Path tmp) throws IOException {
        Path candidate = fakeCandidate(tmp);
        // 同名失败原因变化（evidence 不同）——签名只看名，相邻相同仍停（保守近似）
        Path suite = suiteScript(tmp, "suite.sh", RED_A_REASON_2, RED_A);
        Path patch = patchScript(tmp, "patch.sh", null,
                "{\"usage\": {\"total_tokens\": 10}}", 0);

        Cli.Result result = runLoop(tmp, candidate, suite.toString(),
                "--executor", "patch", "--patch-command", patch.toString());
        JsonNode loop = runtimeResult(tmp);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(loop.path("status").asText()).isEqualTo("stopped_no_progress");
        assertThat(loop.path("history")).hasSize(2);
        assertThat(executions(loop)).isEqualTo(1);
    }

    @Test
    void loopOscillating(@TempDir Path tmp) throws IOException {
        Path candidate = fakeCandidate(tmp);
        // A/B/A 交替：相邻签名都不同 → 无进展不触发，到三轮上限
        Path suite = suiteScript(tmp, "suite.sh", RED_A, RED_B, RED_A);
        Path patch = patchScript(tmp, "patch.sh", null,
                "{\"usage\": {\"total_tokens\": 10}}", 0);

        Cli.Result result = runLoop(tmp, candidate, suite.toString(),
                "--executor", "patch", "--patch-command", patch.toString());
        JsonNode loop = runtimeResult(tmp);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(loop.path("status").asText()).isEqualTo("stopped_max_rounds");
        assertThat(loop.path("history")).hasSize(3);
        assertThat(executions(loop)).isEqualTo(3);
    }

    @Test
    void loopLastRepair(@TempDir Path tmp) throws IOException {
        Path candidate = fakeCandidate(tmp);
        Path suite = suiteScript(tmp, "suite.sh", RED_A);
        Path patch = patchScript(tmp, "patch.sh", null,
                "{\"usage\": {\"total_tokens\": 10}}", 0);

        Cli.Result result = runLoop(tmp, candidate, suite.toString(),
                "--executor", "patch", "--patch-command", patch.toString(),
                "--max-rounds", "1");
        JsonNode loop = runtimeResult(tmp);

        // 末轮修改后无循环内检查：stopped_max_rounds + 待复验显式标记（D4 补齐），
        // remaining_failures 来自修改前报告须标来源
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(loop.path("status").asText()).isEqualTo("stopped_max_rounds");
        assertThat(loop.path("history")).hasSize(1);
        assertThat(executions(loop)).isEqualTo(1);
        assertThat(loop.path("pending_verification").asBoolean()).isTrue();
        assertThat(loop.path("remaining_failures")).isEqualTo(MAPPER.readTree("[\"TEACHING_A\"]"));
        assertThat(loop.path("remaining_failures_source").asText())
                .isEqualTo("round:1(修改前报告)");
    }

    @Test
    void loopTokenBudget(@TempDir Path tmp) throws IOException {
        Path candidate = fakeCandidate(tmp);
        Path suite = suiteScript(tmp, "suite.sh", RED_A);
        Path patch = patchScript(tmp, "patch.sh", null,
                "{\"usage\": {\"total_tokens\": 150}}", 0);

        Cli.Result result = runLoop(tmp, candidate, suite.toString(),
                "--executor", "patch", "--patch-command", patch.toString(),
                "--token-budget", "100");
        JsonNode loop = runtimeResult(tmp);

        // 软边界：累计 150（实际值不回退）、剩余归零、停止下一轮
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(loop.path("status").asText()).isEqualTo("stopped_token_budget");
        assertThat(loop.path("history")).hasSize(1);
        assertThat(executions(loop)).isEqualTo(1);
        assertThat(loop.path("tokens_used").asLong()).isEqualTo(150L);
        assertThat(loop.path("tokens_remaining").asLong()).isZero();
    }

    @Test
    void loopMissingUsage(@TempDir Path tmp) throws IOException {
        Path candidate = fakeCandidate(tmp);
        Path suite = suiteScript(tmp, "suite.sh", RED_A);
        // 执行器正常退出但无 usage 行——缺正用量停止核算，不当零算
        Path patch = patchScript(tmp, "patch.sh", null, "TEACHING patch done (no usage)", 0);

        Cli.Result result = runLoop(tmp, candidate, suite.toString(),
                "--executor", "patch", "--patch-command", patch.toString());
        JsonNode loop = runtimeResult(tmp);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(loop.path("status").asText()).isEqualTo("stopped_token_usage_unavailable");
        assertThat(loop.path("history")).hasSize(1);
        assertThat(executions(loop)).isEqualTo(1);
    }

    @Test
    void loopTimeBudget(@TempDir Path tmp) throws IOException {
        Path candidate = fakeCandidate(tmp);
        // 检查脚本真睡 2 秒 + timeout 1：第一轮检查后轮前时间判定即超（1 检查/0 执行）
        Path suite = patchScript(tmp, "slow-suite.sh", 2, RED_A, 1);

        Cli.Result result = runLoop(tmp, candidate, suite.toString(), "--timeout", "1");
        JsonNode loop = runtimeResult(tmp);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(loop.path("status").asText()).isEqualTo("stopped_time_budget");
        assertThat(loop.path("history")).hasSize(1);
        assertThat(executions(loop)).isZero();
    }

    @Test
    void loopExecutorErrorOnTimeout(@TempDir Path tmp) throws IOException {
        Path candidate = fakeCandidate(tmp);
        Path suite = suiteScript(tmp, "suite.sh", RED_A);
        // patch 睡 5 秒但执行器剩余时间只有约 2 秒：超时被捕获为结构化停止（D4-①，
        // 上游 TimeoutExpired 裸抛缺口的补齐），保存已启动事实与最后报告
        Path patch = patchScript(tmp, "patch.sh", 5,
                "{\"usage\": {\"total_tokens\": 10}}", 0);

        Cli.Result result = runLoop(tmp, candidate, suite.toString(),
                "--executor", "patch", "--patch-command", patch.toString(),
                "--timeout", "2");
        JsonNode loop = runtimeResult(tmp);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(loop.path("status").asText()).isEqualTo("stopped_executor_error");
        assertThat(executions(loop)).isEqualTo(1);
        assertThat(loop.path("executor_error").path("cause").asText()).contains("timeout");
        assertThat(loop.path("pending_verification").asBoolean()).isTrue();
    }

    @Test
    void loopExecutorErrorOnExitCode(@TempDir Path tmp) throws IOException {
        Path candidate = fakeCandidate(tmp);
        Path suite = suiteScript(tmp, "suite.sh", RED_A);
        Path patch = patchScript(tmp, "patch.sh", null, null, 99);

        Cli.Result result = runLoop(tmp, candidate, suite.toString(),
                "--executor", "patch", "--patch-command", patch.toString());
        JsonNode loop = runtimeResult(tmp);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(loop.path("status").asText()).isEqualTo("stopped_executor_error");
        assertThat(loop.path("executor_error").path("cause").asText()).contains("99");
    }

    @Test
    void loopEmptyReport(@TempDir Path tmp) throws IOException {
        Path candidate = fakeCandidate(tmp);
        // 上游接口反例：空 results 在参考实现误判 converged——本仓库报告有效性先行拒绝
        Path suite = suiteScript(tmp, "suite.sh", "{\"results\": [], \"summary\": {\"decision\": \"pass\"}}");

        Cli.Result result = runLoop(tmp, candidate, suite.toString());
        JsonNode loop = runtimeResult(tmp);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(loop.path("status").asText()).isEqualTo("stopped_invalid_report");
        assertThat(loop.path("history")).hasSize(1);
        assertThat(executions(loop)).isZero();
    }

    @Test
    void loopDryRun(@TempDir Path tmp) throws IOException {
        Path candidate = fakeCandidate(tmp);
        Path suite = suiteScript(tmp, "suite.sh", RED_A);

        Cli.Result result = runLoop(tmp, candidate, suite.toString());
        JsonNode loop = runtimeResult(tmp);

        // 默认 dry-run：只生成修复任务零执行（两轮同名失败后停止）
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(loop.path("status").asText()).isEqualTo("stopped_no_progress");
        assertThat(loop.path("history")).hasSize(2);
        assertThat(executions(loop)).isZero();
        assertThat(loop.path("history").get(0).path("executor").asText("")).contains("dry-run");
        // 每轮任务草案在场（RepairMapper 严格版产物，runtime 目录内）
        assertThat(latestRuntime(tmp).resolve("repair-round-1.json")).isRegularFile();
    }
}
