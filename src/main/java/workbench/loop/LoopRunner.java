package workbench.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import workbench.bootstrap.Args;
import workbench.bootstrap.PyJson;
import workbench.evals.ReportContract;
import workbench.execution.ProcessRunner;
import workbench.execution.Shlex;
import workbench.repair.RepairMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 有停止条件的修复 Loop（L10 合同 workbenchIncrement「带预算与停止条件的修复 Loop」；
 * 上游 {@code vendors/CodexFDE/agent/loop.py} {@code run_loop} 的 Java 对应物，只读
 * 对照——讲义 §1.4 宿主映射：agent 家族第二件落 {@code workbench/loop/} 新子包，
 * {@code repair/} 先例同族）。
 *
 * <p><b>计数约定</b>（讲义 §1.1，上游辅导资料重写版钉死）：一轮 = 一次检查决策轮——
 * 检查当前候选；若有阻断失败且仍允许继续，才生成 Repair Task 并调用执行器；修改后的
 * 候选在下一轮开头接受检查。因此最多三轮 = 最多两次修改机会，末轮修改可能落在最后一次
 * 检查之后（待复验）。
 *
 * <p><b>决策序</b>（固定，逐句对齐上游并在最前插入有效性步）：报告有效性 → 达标即停
 * （{@code converged}）→ 无进展（相邻失败签名相同 → {@code stopped_no_progress}，
 * 签名 = 阻断失败名排序，保守近似——同名原因变化不识别、A/B/A 振荡需到轮限）→
 * 时间预算（轮前检查 → {@code stopped_time_budget}）→ Token 预算（已用量达限 →
 * {@code stopped_token_budget}，软边界：超支累计实际值不回退、剩余归零、只停后续调用）
 * → 修复任务（{@link RepairMapper} 严格版，不重蹈上游 legacy 六洞）→ 执行器 →
 * 用量核算（缺正用量 → {@code stopped_token_usage_unavailable}，不当零算）→ 下一轮；
 * 轮尽 → {@code stopped_max_rounds}。
 *
 * <p><b>上游四缺口全补</b>（讲义 D4 已裁决，与参考实现差异逐条如实记录）：①执行器
 * 异常/超时/非零退出 → 结构化 {@code stopped_executor_error}（上游 TimeoutExpired
 * 裸抛），保存已启动事实、最后报告、待复验标记；②报告有效性先行——{@link ReportContract}
 * 校验 + 结果非空，空报告拒绝（上游空 results 误判 converged 的接口反例不可复现）；
 * ③{@code pending_verification} 显式标记 + {@code remaining_failures_source} 来源轮次
 * + 当前候选与最后已验证候选指纹分开（上游无此状态，末轮缺口）；④检查子进程独立超时
 * 300s（l06–l08 探针同值先例，独立于轮预算 {@code --timeout}——上游时间预算只传执行器）。
 *
 * <p><b>诚实性分层</b>（铁律 2/3 业务面）：{@code stopped_*} ≠ 业务失败 ≠ 负责人接受
 * 三层分列；patch 执行器的合成用量如实标注（教学替身不冒充真实模型修复，上游
 * candidate_loop_lab 同款口径）；循环外审计另存不计入轮次。
 *
 * <p>CLI 面（REGISTRY {@code loop-run}，quality-gate / ci-evidence / repair-map 先例）：
 * {@code --max-rounds}（默认 3，1..10）{@code --token-budget}（默认 30000，&gt;0）
 * {@code --timeout} 秒（默认 900，&gt;0）——校验词面对齐上游 ValueError；
 * {@code --suite-command}（检查命令，Shlex 拆分后子进程跑，cwd = 候选；stdout = schema 1.0
 * 报告，退出码 0 绿 1 红）{@code --executor dry-run|patch|claude}（默认 dry-run 只生成
 * 任务零执行）{@code --patch-command}（patch 必填：预设补丁命令，stdout 末行 JSON 的
 * usage.total_tokens 为用量——合成值如实入账）{@code --runtime-dir}（每次运行新目录，
 * 已存在即拒——旧证据不可抹）{@code --candidate}（候选目录）{@code --allowed-file}（可多，
 * 人确认写集）{@code --case}（可多，必需用例不降级）{@code --objective}（缺省上游词面）
 * {@code --python}（缺省 .venv/bin/python 绝对化）{@code --source-task}
 * {@code --source-version} {@code --suite}（缺省 all）。退出码：converged 0，其余停止 2
 * （对齐上游 main）；用法错误 2；运行护栏（runtime-dir 已存在）1。
 *
 * <p>claude 执行器 = ADR-0002 无头面接线（命令构造 + ProcessRunner 执行 + usage 尽力
 * 解析），<b>本讲未真实调用</b>——真实用量字段面待首次真实使用校准（讲义 D6）。
 */
public final class LoopRunner {

    /** 检查子进程独立超时（D4-④）：固定 300s（l06–l08 探针同值先例），独立于轮预算。 */
    private static final int SUITE_TIMEOUT_SECONDS = 300;

    /** 执行器失败（D4-①）：携带结构化停止载荷，不裸抛——接手者可查已启动事实与最后报告。 */
    private static final class ExecutorFailure extends RuntimeException {
        final Map<String, Object> payload;

        ExecutorFailure(Map<String, Object> payload) {
            super(String.valueOf(payload.get("cause")));
            this.payload = payload;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LoopRunner() {}

    /** REGISTRY 命令面（Main 静态注册：{@code REGISTRY.register("loop-run", LoopRunner::execute)}）。 */
    public static int execute(String[] argv) {
        Path runtimeDir;
        Map<String, Object> result;
        try {
            Args args = new Args(argv,
                    Set.of("--max-rounds", "--token-budget", "--timeout", "--suite-command",
                            "--executor", "--patch-command", "--runtime-dir", "--candidate",
                            "--objective", "--python", "--source-task", "--source-version", "--suite"),
                    Set.of(), List.of(), Set.of("--allowed-file", "--case"));
            int maxRounds = parseInt(args.optional("--max-rounds", "3"), "max_rounds");
            if (maxRounds < 1 || maxRounds > 10) {
                throw new Args.UsageException("max_rounds 必须在 1..10");
            }
            long tokenBudget = parseLong(args.optional("--token-budget", "30000"), "token_budget");
            if (tokenBudget < 1) {
                throw new Args.UsageException("token_budget 必须大于 0");
            }
            int timeoutSeconds = parseInt(args.optional("--timeout", "900"), "timeout");
            if (timeoutSeconds < 1) {
                throw new Args.UsageException("timeout 必须大于 0");
            }
            String executorMode = args.optional("--executor", "dry-run");
            if (!Set.of("dry-run", "patch", "claude").contains(executorMode)) {
                throw new Args.UsageException("--executor 须为 dry-run|patch|claude：" + executorMode);
            }
            if ("patch".equals(executorMode) && !args.has("--patch-command")) {
                throw new Args.UsageException("--executor patch 需要 --patch-command（预设补丁命令，教学替身）");
            }
            String suiteCommand = args.require("--suite-command");
            runtimeDir = Path.of(args.require("--runtime-dir")).toAbsolutePath().normalize();
            if (Files.exists(runtimeDir)) {
                System.err.println("runtime-dir 已存在，拒绝覆盖（旧证据不可抹）：" + runtimeDir);
                return 1;
            }
            Path candidate = Path.of(args.require("--candidate")).toAbsolutePath().normalize();
            if (!Files.isDirectory(candidate)) {
                throw new Args.UsageException("--candidate 须为已存在目录：" + candidate);
            }
            Path python = Path.of(args.optional("--python", ".venv/bin/python"))
                    .toAbsolutePath().normalize();
            if (!Files.isRegularFile(python)) {
                throw new Args.UsageException("--python 须为已存在解释器文件：" + python);
            }
            List<String> allowedFiles = args.repeatingList("--allowed-file");
            if (allowedFiles.isEmpty()) {
                throw new Args.UsageException("至少一个 --allowed-file（人确认的允许写集，不来自失败名）");
            }
            List<String> cases = args.repeatingList("--case");
            if (cases.isEmpty()) {
                throw new Args.UsageException("至少一个 --case（必需用例，不降级）");
            }
            result = runLoop(maxRounds, tokenBudget, timeoutSeconds, executorMode,
                    suiteCommand, args.optional("--patch-command", ""), runtimeDir, candidate,
                    python, allowedFiles, cases,
                    args.optional("--objective", "仅修复阻断级 Eval，保持现有公共接口和业务不变量"),
                    args.require("--source-task"), args.require("--source-version"),
                    args.optional("--suite", "all"));
            Files.writeString(runtimeDir.resolve("loop-result.json"), PyJson.dumps(result),
                    StandardCharsets.UTF_8);
            System.out.println(PyJson.dumpsCompact(result));
            return "converged".equals(result.get("status")) ? 0 : 2;
        } catch (Args.UsageException error) {
            System.err.println("error: " + error.getMessage());
            return 2;
        } catch (Exception error) {
            System.err.println("loop-run 失败：" + error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : "：" + error.getMessage()));
            return 1;
        }
    }

    // ---- 决策序（一轮 = 一次检查决策轮） -----------------------------------------------

    private static Map<String, Object> runLoop(int maxRounds, long tokenBudget, int timeoutSeconds,
            String executorMode, String suiteCommand, String patchCommand, Path runtimeDir,
            Path candidate, Path python, List<String> allowedFiles, List<String> cases,
            String objective, String sourceTask, String sourceVersion, String expectedSuite)
            throws Exception {

        Files.createDirectories(runtimeDir);
        List<String> suiteArgv = Shlex.split(suiteCommand);
        List<String> patchArgv = "patch".equals(executorMode) ? Shlex.split(patchCommand) : null;
        boolean executorEnabled = !"dry-run".equals(executorMode);

        long startedAt = System.nanoTime();
        List<Map<String, Object>> history = new ArrayList<>();
        List<String> previous = null;
        long tokensUsed = 0;
        int lastExecutionRound = 0;
        Map<String, Object> lastVerifiedFiles = null;

        for (int round = 1; round <= maxRounds; round++) {
            // ① 检查：子进程统一入口，独立超时（D4-④），stdout = schema 1.0 报告
            ProcessRunner.Outcome check = ProcessRunner.run(suiteArgv, candidate,
                    SUITE_TIMEOUT_SECONDS, null);
            Path reportDir = runtimeDir.resolve("check-%02d".formatted(round));
            Files.createDirectories(reportDir);
            Path reportPath = reportDir.resolve("report.json");
            // 报告字节同源：落盘、解析、映射哈希用同一 strip 后文本——report_sha256 可对账
            String reportText = check.stdoutText().strip();
            Files.writeString(reportPath, reportText, StandardCharsets.UTF_8);
            saveJson(runtimeDir.resolve("check-%02d.process.json".formatted(round)), Map.of(
                    "command", suiteArgv, "cwd", candidate.toString(),
                    "returncode", check.returncode() == null ? null : check.returncode(),
                    "timed_out", check.timedOut(),
                    "stdout", tail(check.stdoutText()), "stderr", tail(check.stderrText())));

            // ② 报告有效性先行（D4-②）：超时 / 退出码域外 / 协议无效 / 结果为空 → 拒绝。
            // 检查已发生的事实先进 History（本轮 entry 在有效性判定前入账——逐轮可重建）
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("round", round);
            entry.put("report", "check-%02d/report.json".formatted(round));
            history.add(entry);
            int observedExit;
            JsonNode report;
            List<String> failures;
            Map<String, Object> filesAtCheck = fingerprints(candidate, allowedFiles);
            try {
                if (check.timedOut()) {
                    throw new IllegalStateException("检查子进程超时（" + SUITE_TIMEOUT_SECONDS + "s）");
                }
                if (check.returncode() == null || (check.returncode() != 0 && check.returncode() != 1)) {
                    throw new IllegalStateException("检查退出码越界（预期 0|1，实际 "
                            + check.returncode() + "）");
                }
                observedExit = check.returncode();
                report = MAPPER.readTree(reportText);
                ReportContract.validateReport(report, cases, observedExit, expectedSuite);
                failures = blockingFailureNames(report);
            } catch (Exception invalid) {
                entry.put("decision", "invalid");
                entry.put("report_status", "无效——" + (invalid.getMessage() == null
                        ? invalid.getClass().getSimpleName() : invalid.getMessage()));
                return finish("stopped_invalid_report", round, history,
                        previous == null ? List.of() : previous, tokenBudget, tokensUsed,
                        candidate, allowedFiles, lastVerifiedFiles, lastExecutionRound,
                        Map.of("cause", invalid.getMessage() == null
                                ? invalid.getClass().getSimpleName() : invalid.getMessage(),
                                "report", "check-%02d/report.json".formatted(round),
                                "note", "报告无效——先处理检查为什么没完成，尚无信息判断业务对错"));
            }
            entry.put("failures", failures);
            entry.put("decision", report.path("summary").path("decision").asText());
            entry.put("candidate_files_at_check", filesAtCheck);
            entry.put("tokens_used_before", tokensUsed);
            entry.put("tokens_remaining_before", Math.max(0, tokenBudget - tokensUsed));
            lastVerifiedFiles = filesAtCheck;

            // ③ 达标即停：不把轮数用满，多余修改只扩大未经需求支持的变化
            if (failures.isEmpty()) {
                return finish("converged", round, history, List.of(), tokenBudget, tokensUsed,
                        candidate, allowedFiles, lastVerifiedFiles, lastExecutionRound, null);
            }
            // ④ 无进展：相邻失败签名相同（保守近似——同名原因变化不识别，交回调查）
            if (failures.equals(previous)) {
                return finish("stopped_no_progress", round, history, failures, tokenBudget,
                        tokensUsed, candidate, allowedFiles, lastVerifiedFiles,
                        lastExecutionRound, null);
            }
            // ⑤ 时间预算：轮前检查，不再启动新动作（三种预算不可互换）
            long elapsedSeconds = elapsedSeconds(startedAt);
            if (elapsedSeconds >= timeoutSeconds) {
                return finish("stopped_time_budget", round, history, failures, tokenBudget,
                        tokensUsed, candidate, allowedFiles, lastVerifiedFiles,
                        lastExecutionRound, null);
            }
            // ⑥ Token 预算（轮前）：已用量达限即停（软边界——已发生消耗不回退）
            if (executorEnabled && tokensUsed >= tokenBudget) {
                return finish("stopped_token_budget", round, history, failures, tokenBudget,
                        tokensUsed, candidate, allowedFiles, lastVerifiedFiles,
                        lastExecutionRound, null);
            }

            // ⑦ 修复任务：L09 严格映射器（三态；用例名不是文件权限，human_review 恒 pending）
            Map<String, Object> mapped;
            try {
                mapped = RepairMapper.map(reportText.getBytes(StandardCharsets.UTF_8),
                        new RepairMapper.Context(sourceTask, sourceVersion, candidate, objective,
                                allowedFiles, cases, observedExit, python, reportPath, expectedSuite,
                                "workbench.evals.l10.ShipChecks"));
            } catch (Exception invalid) {
                return finish("stopped_invalid_report", round, history, failures, tokenBudget,
                        tokensUsed, candidate, allowedFiles, lastVerifiedFiles, lastExecutionRound,
                        Map.of("cause", invalid.getMessage() == null
                                ? invalid.getClass().getSimpleName() : invalid.getMessage(),
                                "report", "check-%02d/report.json".formatted(round),
                                "note", "映射器拒绝本报告——上下文或协议不一致，先核对再继续"));
            }
            if (!"repair_required".equals(mapped.get("status"))) {
                return finish("stopped_invalid_report", round, history, failures, tokenBudget,
                        tokensUsed, candidate, allowedFiles, lastVerifiedFiles, lastExecutionRound,
                        Map.of("cause", "映射器与本控制器失败提取不一致（"
                                + mapped.get("status") + "）", "note", "双层判定的口径分歧应交回调查"));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> task = (Map<String, Object>) mapped.get("task");
            Path taskPath = runtimeDir.resolve("repair-round-%d.json".formatted(round));
            Files.writeString(taskPath, PyJson.dumps(task), StandardCharsets.UTF_8);
            entry.put("repair_task", taskPath.getFileName().toString());

            // ⑧ dry-run：只生成任务零执行（默认——一次意外调用不改动任何候选）
            if (!executorEnabled) {
                entry.put("executor", "dry-run: 仅生成修复任务；--executor patch|claude 才授权修改");
                previous = failures;
                continue;
            }

            // ⑨ 执行器：剩余时间传递（时间预算管历时；异常结构化停止——D4-①）
            int remainingSeconds = (int) Math.max(1, timeoutSeconds - elapsedSeconds(startedAt));
            Map<String, Object> execution;
            try {
                execution = switch (executorMode) {
                    case "patch" -> runPatch(patchArgv, candidate, remainingSeconds);
                    case "claude" -> runClaude(task, candidate, remainingSeconds);
                    default -> throw new IllegalStateException(executorMode);
                };
            } catch (ExecutorFailure failure) {
                lastExecutionRound = round;
                Map<String, Object> payload = new LinkedHashMap<>(failure.payload);
                payload.put("round", round);
                payload.put("started", true);
                payload.put("last_report", "check-%02d/report.json".formatted(round));
                payload.put("note", "执行器异常——动作已启动，可能已留部分修改；停止后续自动动作，"
                        + "保留任务/输出/Diff/最后报告，确认真实进程已结束再决定恢复或复验");
                // 已启动事实进 History（接手者从 History 即可看到本轮执行尝试与中止原因）
                entry.put("executor", payload);
                saveJson(runtimeDir.resolve("execution-%d.json".formatted(round)), payload);
                return finish("stopped_executor_error", round, history, failures, tokenBudget,
                        tokensUsed, candidate, allowedFiles, lastVerifiedFiles, lastExecutionRound,
                        payload);
            }
            entry.put("executor", execution);
            saveJson(runtimeDir.resolve("execution-%d.json".formatted(round)), execution);
            lastExecutionRound = round;

            // ⑩ 用量核算：缺正用量停止核算（不当零算）；超支软边界
            long measured = ((Number) execution.getOrDefault("usage_total_tokens", 0L)).longValue();
            if (measured <= 0) {
                entry.put("budget_decision", "停止：执行器未返回可核验 token 用量（未知 ≠ 免费）");
                return finish("stopped_token_usage_unavailable", round, history, failures,
                        tokenBudget, tokensUsed, candidate, allowedFiles, lastVerifiedFiles,
                        lastExecutionRound, null);
            }
            tokensUsed += measured;
            entry.put("tokens_used_after", tokensUsed);
            entry.put("tokens_remaining_after", Math.max(0, tokenBudget - tokensUsed));
            if (tokensUsed >= tokenBudget) {
                entry.put("budget_decision", "停止：累计用量达到或超过预算，不再启动下一轮"
                        + "（超支已发生，不回退、不宣称从未超预算）");
                return finish("stopped_token_budget", round, history, failures, tokenBudget,
                        tokensUsed, candidate, allowedFiles, lastVerifiedFiles,
                        lastExecutionRound, null);
            }
            previous = failures;
        }

        // ⑪ 轮尽：remaining 来自最后有效报告；末轮若已执行 → 待复验（D4-③）
        List<String> remaining = history.isEmpty()
                ? List.of() : new ArrayList<>((List<String>) history.get(history.size() - 1).get("failures"));
        return finish("stopped_max_rounds", history.size(), history, remaining, tokenBudget,
                tokensUsed, candidate, allowedFiles, lastVerifiedFiles, lastExecutionRound, null);
    }

    // ---- 执行器 ----------------------------------------------------------------------

    /** patch 教学执行器：预设补丁子进程（替身，合成用量如实标注；不冒充真实模型修复）。 */
    private static Map<String, Object> runPatch(List<String> argv, Path cwd, int timeoutSeconds) {
        ProcessRunner.Outcome outcome = ProcessRunner.run(argv, cwd, timeoutSeconds, null);
        if (outcome.timedOut()) {
            throw new ExecutorFailure(Map.of("cause",
                    "timeout: 执行器在剩余 " + timeoutSeconds + "s 内未完成（子进程已中止，"
                            + "可能已留部分修改）", "command", argv));
        }
        if (outcome.returncode() == null || outcome.returncode() != 0) {
            throw new ExecutorFailure(Map.of("cause",
                    "exit_code: " + outcome.returncode(), "command", argv,
                    "stderr", tail(outcome.stderrText())));
        }
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("command", argv);
        execution.put("cwd", cwd.toString());
        execution.put("returncode", outcome.returncode());
        execution.put("stdout", tail(outcome.stdoutText()));
        execution.put("stderr", tail(outcome.stderrText()));
        execution.put("usage_total_tokens", extractTotalTokens(outcome.stdoutText()));
        execution.put("source", "predetermined teaching patch subprocess");
        execution.put("usage_is_synthetic", true);
        execution.put("real_model", false);
        return execution;
    }

    /**
     * claude 执行器（ADR-0002 无头面接线）：任务 JSON 构造最小修复提示词，经
     * {@link ProcessRunner} 跑 {@code claude -p --output-format json}。本讲未真实调用
     * （讲义 D6：接线完成即可，真实用量字段面待首次真实使用校准——stdout JSON 的
     * usage 对象尽力解析，不可核验即由用量核算层诚实停止）。
     */
    private static Map<String, Object> runClaude(Map<String, Object> task, Path cwd, int timeoutSeconds) {
        List<String> argv = List.of("claude", "-p", claudePrompt(task), "--output-format", "json");
        ProcessRunner.Outcome outcome = ProcessRunner.run(argv, cwd, timeoutSeconds, null);
        if (outcome.timedOut()) {
            throw new ExecutorFailure(Map.of("cause",
                    "timeout: claude 执行器在剩余 " + timeoutSeconds + "s 内未完成", "command", argv));
        }
        if (outcome.returncode() == null || outcome.returncode() != 0) {
            throw new ExecutorFailure(Map.of("cause",
                    "exit_code: " + outcome.returncode(), "command", argv,
                    "stderr", tail(outcome.stderrText())));
        }
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("command", List.of("claude", "-p", "<task prompt>",
                "--output-format", "json"));
        execution.put("cwd", cwd.toString());
        execution.put("returncode", outcome.returncode());
        execution.put("stdout", tail(outcome.stdoutText()));
        execution.put("stderr", tail(outcome.stderrText()));
        execution.put("usage_total_tokens", extractTotalTokens(outcome.stdoutText()));
        execution.put("source", "claude headless (wired this lesson; not invoked in L10 evidence)");
        return execution;
    }

    /** 上游 _run_codex 提示词词面（最小修复纪律：先复现，再修改，不得删检查）。 */
    private static String claudePrompt(Map<String, Object> task) {
        return "按 Repair Task JSON 执行最小修复。objective=" + task.get("objective")
                + "；scope=" + task.get("scope") + "；reproduce=" + task.get("reproduce")
                + "；acceptance=" + task.get("acceptance")
                + "。先复现，再修改，再运行 acceptance。不得删除 Eval、降低等级或改写失败证据。"
                + "最后列出修改文件、验证命令和剩余风险。";
    }

    /** stdout 末行 JSON 的 usage.total_tokens（缺则 input+output；再缺 = 0 → 上层诚实停止）。 */
    private static long extractTotalTokens(String stdout) {
        String[] lines = stdout.strip().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            try {
                JsonNode parsed = MAPPER.readTree(lines[i]);
                JsonNode usage = parsed.path("usage");
                if (usage.isObject()) {
                    long total = usage.path("total_tokens").asLong(0);
                    if (total <= 0) {
                        total = usage.path("input_tokens").asLong(0) + usage.path("output_tokens").asLong(0);
                    }
                    return total;
                }
            } catch (Exception ignored) {
                // 非末行 JSON——继续向前找（失败面交由 usage_total_tokens=0 诚实停止）
            }
        }
        return 0;
    }

    // ---- finish / History ------------------------------------------------------------

    private static Map<String, Object> finish(String status, int rounds,
            List<Map<String, Object>> history, List<String> remaining, long tokenBudget,
            long tokensUsed, Path candidate, List<String> allowedFiles,
            Map<String, Object> lastVerifiedFiles, int lastExecutionRound,
            Map<String, Object> extra) {
        // D4-③：最后一次动作是执行而非检查收尾 → 已有修改未经后置检查 = 待复验
        boolean pending = lastExecutionRound > 0 && lastExecutionRound == rounds
                && !"converged".equals(status);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("rounds", rounds);
        result.put("history", history);
        result.put("remaining_failures", remaining);
        result.put("remaining_failures_source", rounds == 0 ? "-"
                : pending ? "round:%d(修改前报告)".formatted(rounds)
                : "round:%d(本轮报告)".formatted(rounds));
        result.put("pending_verification", pending);
        result.put("after_loop_audit_due", pending);
        // 两候选分开：当前（结束时现采）vs 最后已验证（最后一次有效检查时）
        result.put("current_candidate", candidate.toString());
        result.put("current_candidate_files", fingerprints(candidate, allowedFiles));
        result.put("last_verified_candidate_files", lastVerifiedFiles == null
                ? Map.of() : lastVerifiedFiles);
        result.put("token_budget", tokenBudget);
        result.put("tokens_used", tokensUsed);
        result.put("tokens_remaining", Math.max(0, tokenBudget - tokensUsed));
        if (extra != null) {
            result.put("executor_error", extra);
        }
        result.put("note", "stopped_* 是控制器结论（停止规则执行），不是业务通过，也不是负责人接受——"
                + "三层分开；接手者先看 last_verified_candidate_files 与 current_candidate_files "
                + "是否一致，不一致先复验当前候选");
        return result;
    }

    /** 阻断失败签名：失败名排序（上游 _signature 同形——保守近似，只看名不看原因）。 */
    private static List<String> blockingFailureNames(JsonNode report) {
        List<String> names = new ArrayList<>();
        for (JsonNode item : report.path("results")) {
            if (!item.path("passed").asBoolean(true)
                    && "blocking".equals(item.path("level").asText())) {
                names.add(item.path("name").asText());
            }
        }
        Collections.sort(names);
        return names;
    }

    /** 允许写集文件指纹（缺失记 absent）：候选身份的观察面——检查时与结束时分开采样。 */
    private static Map<String, Object> fingerprints(Path candidate, List<String> allowedFiles) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String rel : allowedFiles) {
            Path file = candidate.resolve(rel);
            out.put(rel, Files.isRegularFile(file) ? sha256Prefix(file) : "absent");
        }
        return out;
    }

    private static String sha256Prefix(Path file) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
                    .substring(0, 12);
        } catch (Exception error) {
            return "unreadable";
        }
    }

    private static long elapsedSeconds(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000_000L;
    }

    private static String tail(String text) {
        return text.length() <= 1000 ? text : text.substring(text.length() - 1000);
    }

    private static void saveJson(Path file, Map<String, Object> value) throws Exception {
        Files.writeString(file, PyJson.dumps(value), StandardCharsets.UTF_8);
    }

    private static int parseInt(String raw, String label) {
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException error) {
            throw new Args.UsageException(label + " 须为整数：" + raw);
        }
    }

    private static long parseLong(String raw, String label) {
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException error) {
            throw new Args.UsageException(label + " 须为整数：" + raw);
        }
    }
}
