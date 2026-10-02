package workbench.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import workbench.testsupport.Cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L12 Graph 控制器合同测试：讲义 docs/lessons/L12-显式状态图回退与具名人审.md
 * §2 C1 → 用例映射（agent 家族第四件，无 Python 先例；映射源 = 上游
 * {@code agent/graph.py} run_graph 源码语义 + graph_control_lab 十六模式断言表，
 * 只读对照）。独立预期源 = 上游 README 十六模式表（状态名 / 检查调用数 / 轮数 /
 * 新增检查数逐项）与 run_graph 源码语义，不抄实现返回值。
 *
 * <p>接缝（spec docs/specs/L12-*.md 已具名确认）：REGISTRY 命令 {@code graph-run}
 * CLI 子进程缝为两个新高缝之一——十六模式的合成输入经 {@code --suite-command}
 * 注入脚本（计数脚本按调用序输出报告；上游 graph_control_lab 用进程内
 * unittest.mock patch，本仓库以 CLI 参数即注入缝承载——L10 LoopRunner 同款）。
 * 全经真实子进程断言，不直调内部函数（仓库测试口径）。
 *
 * <p>与上游的已裁决形态差异（讲义 D2/D3，逐条如实）：①上游进程内直调
 * {@code run_suite}/{@code build_repair_task} → 本仓 {@code --suite-command} 子进程
 * 缝 + RepairMapper 复用（修复上下文参数按需装配——仅阻断失败需要生成修复任务时
 * 校验，缺参 → failed 显式，不静默）；②上游 ValueError 裸抛 rc 1 → 参数校验走
 * UsageException rc 2（L10 LoopRunner 口径承袭）；③malformed-state / save-error
 * 异常照旧不吞——落 Main 顶层异常边界（JSON 错误契约 rc 1，词面含异常类型；上游
 * 裸栈 rc 1 的退出码面一致、输出形态按本仓 L01 合同）。
 *
 * <p>上游缺口如实暴露面（讲义 D6 讲义 §1.3 第 6 条，不粉饰）：empty-report 零阻断
 * 也进等待（消费者校验缺口）；stale-approval 批准未绑定候选；unchecked-move 由
 * {@code move} 不验边所致——本类以行为断言钉住该缺口（治理层留后续讲次）。
 *
 * <p>红点组（commit 1，讲义 §3 步骤 2）：{@code graph-run} 未注册 → rc 2
 * invalid choice，全部目标行为断言失败即红；实现转绿后本类零改动。
 *
 * <pre>
 * 用例 → 合同映射：
 * graphRunValidatesMaxRounds              C1 参数校验：1..10 之外 rc 2（上游 ValueError 词面）
 * graphRunValidatesReviewDecision         C1 参数校验：决定域 + 决定须带审核人
 * graphRunRejectOnceDemonstratesReturn    C1 --reject-once 演示回退：打回→重查→等待，2 次检查
 * graphRunDemoCompletesWithoutWaiting     C1 demo：无等待策略自动 completed，1 次检查
 * graphRunWaitsThenResumeKeepsWaiting     C1 wait/resume：rc 3 等待；再跑保持等待零新查
 * graphRunApproveCompletesWithoutRecheck  C1 approve：completed、具名留痕、零新查
 * graphRunRejectReturnsAndRechecks        C1 reject：打回回退 develop→重查→再等待（1 新查）
 * graphRunRejectAtLimitStops              C1 reject-at-limit：上限 1 打回 → 轮数变 2 stopped 零新查
 * graphRunBlockingFailureStopsAtLimit     C1 blocking-failure：三轮三查 stopped + 修复任务
 * graphRunEvalExceptionFailsExplicitly    C1 eval-exception：检查崩溃 → failed 显式留 error
 * graphRunEmptyReportStillWaits           C1 empty-report：零阻断也进等待（接口反例如实）
 * graphRunUnknownStateFails               C1 unknown-state：未知状态 failed 零检查
 * graphRunMalformedStateEscapesToBoundary C1 malformed-state：损坏 JSON 不吞 → 顶层边界 rc 1
 * graphRunSaveErrorEscapesToBoundary      C1 save-error：保存失败不吞不伪装 → 顶层边界 rc 1
 * graphRunStaleApprovalStillApproves      C1 stale-approval：候选已变仍可批准（未绑定候选如实暴露）
 * graphRunApprovalWithoutWaitIgnored      C1 approval-without-wait：新跑带决定不直接批准
 * graphRunTerminalRerunUnchanged          C1 terminal-rerun：终态重读结果逐字不变零新查
 * graphRunRepairTaskDefaultsToStateFileSibling 复查轮 T-1：修复任务缺省 = 状态文件同目录（spec story 24）
 * graphRunUncheckedMoveAcceptedFromHandEditedState 复查轮 T-2：unchecked-move 缺口换 CLI 面钉住
 *                                           （手工跳步进 human_review 被信任推进零检查——上游直调
 *                                           move 在 Java 无私有缝，同缺口可观察承载）
 * graphRunInvalidRoundsFieldEscapesToBoundary 复查轮 T-c1：rounds 在场无效不静默回落（rc 1）
 * </pre>
 */
class L12GraphRunnerContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger RUN = new AtomicInteger();

    // ---- 合成检查报告（schema 1.0 单用例，L10 LoopRunner 合同测试同形） ----------------

    private static String report(String name, boolean passed, String evidence) {
        return "{\"schema_version\": \"1.0\", \"suite\": \"all\", \"generated_at\": \"2026-10-02T00:00:00\", "
                + "\"requested_cases\": [\"" + name + "\"], "
                + "\"results\": [{\"name\": \"" + name + "\", \"level\": \"blocking\", \"passed\": " + passed + ", "
                + "\"evidence\": \"" + evidence + "\", \"duration_ms\": 5}], "
                + "\"summary\": {\"total\": 1, \"passed\": " + (passed ? 1 : 0)
                + ", \"blocking_failed\": " + (passed ? 0 : 1)
                + ", \"observing_failed\": 0, \"decision\": \"" + (passed ? "pass" : "block") + "\"}}";
    }

    private static final String GREEN = report("TEACHING_A", true, "synthetic green observation");
    private static final String RED = report("TEACHING_A", false, "synthetic blocking failure");

    /** 上游 empty-report 形态（合成空结果、零阻断数——消费者接口反例的教学口径逐字段）。 */
    private static final String EMPTY_GREEN = "{\"results\": [], \"summary\": {\"total\": 0, "
            + "\"blocking_failed\": 0, \"decision\": \"pass\"}}";

    /** suite 计数脚本：按调用序输出序列第 N 份报告（序列尽后重复末份），退出码随红绿。 */
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

    /** 单次固定输出脚本（崩溃面可自定义退出码）。 */
    private static Path oneShotScript(Path dir, String name, String stdoutLine, int exitCode)
            throws IOException {
        return executableScript(dir, name, "#!/bin/sh\n"
                + (stdoutLine == null ? "" : "printf '%s\\n' '" + stdoutLine + "'\n")
                + "echo x > \"$0.cnt\"\n"
                + "exit " + exitCode + "\n");
    }

    private static Path executableScript(Path dir, String name, String content) throws IOException {
        Path script = dir.resolve(name);
        Files.writeString(script, content, StandardCharsets.UTF_8);
        if (!script.toFile().setExecutable(true)) {
            throw new IOException("脚本不可执行：" + script);
        }
        return script;
    }

    /** 检查调用数：脚本旁 .cnt 计数文件（未调用 = 0）。 */
    private static int suiteCalls(Path script) throws IOException {
        Path cnt = Path.of(script + ".cnt");
        return Files.isRegularFile(cnt) ? Integer.parseInt(Files.readString(cnt).strip()) : 0;
    }

    /** 干净候选目录 + 占位 python（RepairMapper 上下文校验面：目录在场、解释器为文件）。 */
    private static Path fakeCandidate(Path tmp) throws IOException {
        Path candidate = Files.createDirectories(tmp.resolve("candidate-" + RUN.incrementAndGet()));
        Path python = candidate.resolve("python-placeholder");
        Files.writeString(python, "#!/bin/sh\n", StandardCharsets.UTF_8);
        return candidate;
    }

    // ---- 参数校验面 ------------------------------------------------------------------

    @Test
    void graphRunValidatesMaxRounds(@TempDir Path tmp) throws IOException {
        Path script = suiteScript(tmp, "suite.sh", GREEN);
        for (String bad : new String[]{"0", "11"}) {
            Cli.Result result = Cli.run("graph-run",
                    "--suite-command", script.toString(), "--max-rounds", bad);

            assertThat(result.exitCode()).as("max-rounds=%s", bad).isEqualTo(2);
            assertThat(result.stderr() + result.stdout()).contains("max_rounds 必须在 1..10");
        }
    }

    @Test
    void graphRunValidatesReviewDecision(@TempDir Path tmp) throws IOException {
        Path script = suiteScript(tmp, "suite.sh", GREEN);
        Cli.Result bogus = Cli.run("graph-run",
                "--suite-command", script.toString(), "--review-decision", "maybe");

        assertThat(bogus.exitCode()).isEqualTo(2);
        assertThat(bogus.stderr() + bogus.stdout()).contains("review_decision 必须为 approve 或 reject");

        Cli.Result anonymous = Cli.run("graph-run",
                "--suite-command", script.toString(), "--review-decision", "approve");

        assertThat(anonymous.exitCode()).isEqualTo(2);
        assertThat(anonymous.stderr() + anonymous.stdout()).contains("必须记录审核人");
    }

    // ---- 十六控制模式 ----------------------------------------------------------------

    @Test
    void graphRunRejectOnceDemonstratesReturn(@TempDir Path tmp) throws IOException {
        Path script = suiteScript(tmp, "suite.sh", GREEN, GREEN);
        Path state = tmp.resolve("state.json");

        Cli.Result result = Cli.run("graph-run",
                "--suite-command", script.toString(), "--reject-once", "--state-file", state.toString());

        assertThat(result.exitCode()).isEqualTo(3);
        JsonNode json = Cli.json(result);
        assertThat(json.path("status").asText()).isEqualTo("awaiting_human_review");
        // 演示打回后重新检查再等待：2 次检查，轨迹含演示打回与两次 develop→test
        assertThat(suiteCalls(script)).isEqualTo(2);
        assertThat(json.path("trace").toString()).contains("演示：人工打回一次，验证回退路径");
        assertThat(traceMoves(json)).containsSubsequence("develop>test", "test>human_review",
                "human_review>develop", "develop>test", "test>human_review",
                "human_review>awaiting_human_review");
    }

    @Test
    void graphRunDemoCompletesWithoutWaiting(@TempDir Path tmp) throws IOException {
        Path script = suiteScript(tmp, "suite.sh", GREEN);

        Cli.Result result = Cli.run("graph-run", "--suite-command", script.toString());

        assertThat(result.exitCode()).isZero();
        JsonNode json = Cli.json(result);
        assertThat(json.path("status").asText()).isEqualTo("completed");
        assertThat(json.path("trace").toString()).contains("本地演示审批策略自动通过");
        assertThat(json.path("report_summary").path("blocking_failed").asInt(-1)).isZero();
        assertThat(suiteCalls(script)).isEqualTo(1);
        assertThat(json.path("reviewer").isNull()).isTrue();
    }

    @Test
    void graphRunWaitsThenResumeKeepsWaiting(@TempDir Path tmp) throws IOException {
        Path script = suiteScript(tmp, "suite.sh", GREEN);
        Path state = tmp.resolve("state.json");

        Cli.Result first = Cli.run("graph-run",
                "--suite-command", script.toString(), "--state-file", state.toString());

        assertThat(first.exitCode()).isEqualTo(3);
        assertThat(Cli.json(first).path("status").asText()).isEqualTo("awaiting_human_review");

        // resume：再次运行保持等待，不重新检查（上游 additional_eval_calls == 0）
        Cli.Result second = Cli.run("graph-run",
                "--suite-command", script.toString(), "--state-file", state.toString());

        assertThat(second.exitCode()).isEqualTo(3);
        assertThat(Cli.json(second).path("status").asText()).isEqualTo("awaiting_human_review");
        assertThat(suiteCalls(script)).isEqualTo(1);
    }

    @Test
    void graphRunApproveCompletesWithoutRecheck(@TempDir Path tmp) throws IOException {
        Path script = suiteScript(tmp, "suite.sh", GREEN);
        Path state = tmp.resolve("state.json");
        Cli.run("graph-run", "--suite-command", script.toString(), "--state-file", state.toString());

        Cli.Result result = Cli.run("graph-run",
                "--suite-command", script.toString(), "--state-file", state.toString(),
                "--review-decision", "approve", "--reviewer", "TEACHING reviewer");

        assertThat(result.exitCode()).isZero();
        JsonNode json = Cli.json(result);
        assertThat(json.path("status").asText()).isEqualTo("completed");
        assertThat(json.path("reviewer").asText()).isEqualTo("TEACHING reviewer");
        assertThat(json.path("review_decision").asText()).isEqualTo("approve");
        assertThat(json.path("reviewed_at").asText()).isNotBlank();
        assertThat(json.path("trace").toString()).contains("审核人 TEACHING reviewer 批准交付");
        // 批准后不重新运行 Eval（上游 additional_eval_calls == 0）
        assertThat(suiteCalls(script)).isEqualTo(1);
    }

    @Test
    void graphRunRejectReturnsAndRechecks(@TempDir Path tmp) throws IOException {
        Path script = suiteScript(tmp, "suite.sh", GREEN, GREEN);
        Path state = tmp.resolve("state.json");
        Cli.run("graph-run", "--suite-command", script.toString(), "--state-file", state.toString());

        Cli.Result result = Cli.run("graph-run",
                "--suite-command", script.toString(), "--state-file", state.toString(),
                "--review-decision", "reject", "--reviewer", "TEACHING reviewer");

        // 打回 → 回退 develop → 重新检查 → 再等待（上游 expected awaiting + 1 新查）
        assertThat(result.exitCode()).isEqualTo(3);
        JsonNode json = Cli.json(result);
        assertThat(json.path("status").asText()).isEqualTo("awaiting_human_review");
        assertThat(json.path("trace").toString()).contains("审核人 TEACHING reviewer 打回交付");
        assertThat(traceMoves(json)).containsSubsequence("awaiting_human_review>develop", "develop>test",
                "test>human_review", "human_review>awaiting_human_review");
        assertThat(suiteCalls(script)).isEqualTo(2);
    }

    @Test
    void graphRunRejectAtLimitStops(@TempDir Path tmp) throws IOException {
        Path script = suiteScript(tmp, "suite.sh", GREEN);
        Path state = tmp.resolve("state.json");
        Cli.run("graph-run",
                "--suite-command", script.toString(), "--max-rounds", "1", "--state-file", state.toString());

        Cli.Result result = Cli.run("graph-run",
                "--suite-command", script.toString(), "--max-rounds", "1", "--state-file", state.toString(),
                "--review-decision", "reject", "--reviewer", "TEACHING reviewer");

        // 上限 1 时打回：轮数字段变为 2 后 stopped，未再检查（上游 rounds == 2 + 零新查）
        assertThat(result.exitCode()).isEqualTo(2);
        JsonNode json = Cli.json(result);
        assertThat(json.path("status").asText()).isEqualTo("stopped");
        assertThat(json.path("rounds").asInt(-1)).isEqualTo(2);
        assertThat(json.path("trace").toString()).contains("达到最大轮数，保留剩余失败");
        assertThat(suiteCalls(script)).isEqualTo(1);
    }

    @Test
    void graphRunBlockingFailureStopsAtLimit(@TempDir Path tmp) throws IOException {
        Path script = suiteScript(tmp, "suite.sh", RED);
        Path state = tmp.resolve("state.json");
        Path repair = tmp.resolve("graph-repair-task.json");
        Path candidate = fakeCandidate(tmp);

        Cli.Result result = Cli.run("graph-run",
                "--suite-command", script.toString(), "--state-file", state.toString(),
                "--repair-task-path", repair.toString(),
                "--source-task", "CASE-WB-L12-001", "--source-version", "r1",
                "--allowed-file", "flowerp/service.py", "--case", "TEACHING_A",
                "--candidate", candidate.toString(), "--python", candidate.resolve("python-placeholder").toString());

        // 三轮三查后 stopped（round 3 == max 3 在 rework 停止——未收敛，禁止宣称成功）
        assertThat(result.exitCode()).isEqualTo(2);
        JsonNode json = Cli.json(result);
        assertThat(json.path("status").asText()).isEqualTo("stopped");
        assertThat(json.path("rounds").asInt(-1)).isEqualTo(3);
        assertThat(suiteCalls(script)).isEqualTo(3);
        assertThat(json.path("trace").toString()).contains("阻断级 Eval 失败")
                .contains("未收敛，禁止宣称成功");
        // 修复任务经 L09 严格映射器生成并落盘（rework 的携带物）
        assertThat(json.path("repair_task").path("source_task").asText()).isEqualTo("CASE-WB-L12-001");
        assertThat(repair).isRegularFile();
        assertThat(MAPPER.readTree(Files.readString(repair, StandardCharsets.UTF_8))
                .path("source_task").asText()).isEqualTo("CASE-WB-L12-001");
    }

    @Test
    void graphRunEvalExceptionFailsExplicitly(@TempDir Path tmp) throws IOException {
        Path script = oneShotScript(tmp, "suite.sh", null, 3);
        Path state = tmp.resolve("state.json");

        Cli.Result result = Cli.run("graph-run",
                "--suite-command", script.toString(), "--state-file", state.toString());

        // 检查崩溃（退出码越界）：异常显式进入失败态并留 error，不静默吞
        assertThat(result.exitCode()).isEqualTo(2);
        JsonNode json = Cli.json(result);
        assertThat(json.path("status").asText()).isEqualTo("failed");
        assertThat(json.path("error").asText()).contains("检查退出码越界");
        assertThat(json.path("trace").toString()).contains("异常显式进入失败态");
    }

    @Test
    void graphRunEmptyReportStillWaits(@TempDir Path tmp) throws IOException {
        Path script = oneShotScript(tmp, "suite.sh", EMPTY_GREEN, 0);
        Path state = tmp.resolve("state.json");

        Cli.Result result = Cli.run("graph-run",
                "--suite-command", script.toString(), "--state-file", state.toString());

        // 上游接口反例如实：空结果零阻断也进入等待（消费者缺完整校验——不粉饰）
        assertThat(result.exitCode()).isEqualTo(3);
        JsonNode json = Cli.json(result);
        assertThat(json.path("status").asText()).isEqualTo("awaiting_human_review");
        assertThat(json.path("report_summary").path("total").asInt(-1)).isZero();
        assertThat(json.path("error").isNull()).isTrue();
    }

    @Test
    void graphRunUnknownStateFails(@TempDir Path tmp) throws IOException {
        Path script = suiteScript(tmp, "suite.sh", GREEN);
        Path state = tmp.resolve("state.json");
        Files.writeString(state, "{\"status\": \"unrecognized\"}", StandardCharsets.UTF_8);

        Cli.Result result = Cli.run("graph-run",
                "--suite-command", script.toString(), "--state-file", state.toString());

        assertThat(result.exitCode()).isEqualTo(2);
        JsonNode json = Cli.json(result);
        assertThat(json.path("status").asText()).isEqualTo("failed");
        assertThat(json.path("trace").toString()).contains("未知状态：unrecognized");
        assertThat(suiteCalls(script)).isZero();
    }

    @Test
    void graphRunMalformedStateEscapesToBoundary(@TempDir Path tmp) throws IOException {
        Path script = suiteScript(tmp, "suite.sh", GREEN);
        Path state = tmp.resolve("state.json");
        Files.writeString(state, "{broken", StandardCharsets.UTF_8);

        Cli.Result result = Cli.run("graph-run",
                "--suite-command", script.toString(), "--state-file", state.toString());

        // 读取阶段损坏 JSON：不吞不伪装——落 Main 顶层异常边界（JSON 错误契约 rc 1）
        assertThat(result.exitCode()).isEqualTo(1);
        JsonNode error = Cli.json(result);
        assertThat(error.path("ok").asBoolean(true)).isFalse();
        assertThat(error.path("error").asText()).contains("内部错误");
    }

    @Test
    void graphRunSaveErrorEscapesToBoundary(@TempDir Path tmp) throws IOException {
        Path script = suiteScript(tmp, "suite.sh", GREEN);
        // 上游同形：父路径被文件占用，保存阶段失败（运行已到等待态后）
        Path block = tmp.resolve("parent-is-a-file");
        Files.writeString(block, "occupied", StandardCharsets.UTF_8);
        Path state = block.resolve("state.json");

        Cli.Result result = Cli.run("graph-run",
                "--suite-command", script.toString(), "--state-file", state.toString());

        assertThat(result.exitCode()).isEqualTo(1);
        JsonNode error = Cli.json(result);
        assertThat(error.path("ok").asBoolean(true)).isFalse();
        assertThat(error.path("error").asText()).contains("内部错误");
        assertThat(suiteCalls(script)).isEqualTo(1);
    }

    @Test
    void graphRunStaleApprovalStillApproves(@TempDir Path tmp) throws IOException {
        Path script = suiteScript(tmp, "suite.sh", GREEN);
        Path state = tmp.resolve("state.json");
        Cli.run("graph-run", "--suite-command", script.toString(), "--state-file", state.toString());

        Cli.Result result = Cli.run("graph-run",
                "--suite-command", script.toString(), "--state-file", state.toString(),
                "--review-decision", "approve", "--reviewer", "TEACHING reviewer");

        // 如实暴露（上游 stale-approval 同义）：批准未绑定候选身份、未重新检查——
        // 等待期间候选可变而批准仍生效（治理层缺口留后续讲次，不粉饰）
        assertThat(result.exitCode()).isZero();
        assertThat(Cli.json(result).path("status").asText()).isEqualTo("completed");
        assertThat(suiteCalls(script)).isEqualTo(1);
    }

    @Test
    void graphRunApprovalWithoutWaitIgnored(@TempDir Path tmp) throws IOException {
        Path script = suiteScript(tmp, "suite.sh", GREEN);
        Path state = tmp.resolve("state.json");

        // 新运行传入批准：不在等待点则决定被忽略，仍进入等待（上游 reviewer is None）
        Cli.Result result = Cli.run("graph-run",
                "--suite-command", script.toString(), "--state-file", state.toString(),
                "--review-decision", "approve", "--reviewer", "TEACHING reviewer");

        assertThat(result.exitCode()).isEqualTo(3);
        JsonNode json = Cli.json(result);
        assertThat(json.path("status").asText()).isEqualTo("awaiting_human_review");
        assertThat(json.path("reviewer").isNull()).isTrue();
        assertThat(suiteCalls(script)).isEqualTo(1);
    }

    @Test
    void graphRunTerminalRerunUnchanged(@TempDir Path tmp) throws IOException {
        Path script = suiteScript(tmp, "suite.sh", GREEN);
        Path state = tmp.resolve("state.json");
        Cli.run("graph-run", "--suite-command", script.toString(), "--state-file", state.toString());
        Cli.Result approved = Cli.run("graph-run",
                "--suite-command", script.toString(), "--state-file", state.toString(),
                "--review-decision", "approve", "--reviewer", "TEACHING reviewer");
        assertThat(approved.exitCode()).isZero();

        // 已完成状态再次读取保持完成、结果逐字不变、不重新执行（上游 again == result）
        Cli.Result rerun = Cli.run("graph-run",
                "--suite-command", script.toString(), "--state-file", state.toString());

        assertThat(rerun.exitCode()).isZero();
        assertThat(Cli.json(rerun)).isEqualTo(Cli.json(approved));
        assertThat(suiteCalls(script)).isEqualTo(1);
    }

    // ---- 复查轮新增（T-1 / T-2 / T-c1，2026-10-02） --------------------------------

    @Test
    void graphRunRepairTaskDefaultsToStateFileSibling(@TempDir Path tmp) throws IOException {
        Path script = suiteScript(tmp, "suite.sh", RED);
        Path state = tmp.resolve("state.json");
        Path candidate = fakeCandidate(tmp);

        Cli.Result result = Cli.run("graph-run",
                "--suite-command", script.toString(), "--state-file", state.toString(),
                "--source-task", "CASE-WB-L12-001", "--source-version", "r1",
                "--allowed-file", "flowerp/service.py", "--case", "TEACHING_A",
                "--candidate", candidate.toString(), "--python", candidate.resolve("python-placeholder").toString());

        // spec story 24：修复任务缺省落**状态文件同目录** graph-repair-task.json
        // （无状态文件时才是上游词面 .runtime/graph-repair-task.json——javadoc 在案）
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(tmp.resolve("graph-repair-task.json")).isRegularFile();
    }

    @Test
    void graphRunUncheckedMoveAcceptedFromHandEditedState(@TempDir Path tmp) throws IOException {
        Path script = suiteScript(tmp, "suite.sh", GREEN);
        Path state = tmp.resolve("state.json");
        Files.writeString(state, "{\"status\": \"human_review\", \"rounds\": 1}", StandardCharsets.UTF_8);

        Cli.Result result = Cli.run("graph-run",
                "--suite-command", script.toString(), "--state-file", state.toString());

        // unchecked-move 缺口的 CLI 可观察面：上游直调 move 跳步在 Java 侧无私有缝——
        // 手工跳步进 human_review 的状态被信任推进（move 不验边、加载态不校验历史），
        // 零检查即达等待。缺口如实暴露（治理层留后续讲次）。
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(Cli.json(result).path("status").asText()).isEqualTo("awaiting_human_review");
        assertThat(suiteCalls(script)).isZero();
    }

    @Test
    void graphRunInvalidRoundsFieldEscapesToBoundary(@TempDir Path tmp) throws IOException {
        Path script = suiteScript(tmp, "suite.sh", GREEN);
        Path state = tmp.resolve("state.json");
        Files.writeString(state, "{\"status\": \"develop\", \"rounds\": \"not-a-number\"}",
                StandardCharsets.UTF_8);

        Cli.Result result = Cli.run("graph-run",
                "--suite-command", script.toString(), "--state-file", state.toString());

        // 上游 int() TypeError 逃逸同义：字段在场但无效 → 不静默回落（rc 1 顶层边界）
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Cli.json(result).path("error").asText()).contains("内部错误");
    }

    /** 轨迹折叠为 from>to 序列（断言用）。 */
    private static List<String> traceMoves(JsonNode json) {
        return java.util.stream.StreamSupport.stream(json.path("trace").spliterator(), false)
                .map(entry -> entry.path("from").asText() + ">" + entry.path("to").asText())
                .toList();
    }
}
