package workbench.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import workbench.bootstrap.Args;
import workbench.bootstrap.PyJson;
import workbench.evals.l12.ApprovalChecks;
import workbench.execution.ProcessRunner;
import workbench.execution.Shlex;
import workbench.repair.RepairMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 显式状态图控制器（L12 合同 workbenchIncrement「显式状态图、回退边和具名人审」；
 * 上游 {@code vendors/CodexFDE/agent/graph.py} {@code run_graph} 的 Java 对应物，只读
 * 对照——讲义 §1.4 宿主映射：agent 家族第四件落 {@code workbench/graph/} 新子包，
 * {@code repair/}/{@code loop/}/{@code schedule/} 先例同族，上游 {@code agent/} 四件收齐）。
 *
 * <p><b>状态机</b>（逐句对齐上游）：develop（轮数 +1，超限 stopped）→ test（统一检查，
 * 阻断失败 → rework 携修复任务，零阻断 → human_review）→ human_review（reject_once
 * 演示打回 / {@code --require-human-review} 或有状态文件 → awaiting_human_review 等待
 * 具名审核人 / 否则本地演示策略自动 completed）；rework 未到限回 develop；异常显式
 * failed 留 error 不静默吞。等待点决定在主循环前处理（approve → completed / reject →
 * develop 回退重查），具名 reviewer + 决定 + 时间落状态；终态（completed/failed/stopped/
 * awaiting_human_review）重读不重执行。
 *
 * <p><b>与上游的已裁决形态差异</b>（讲义 D2，逐条如实记录）：①上游进程内直调
 * {@code run_suite}/{@code build_repair_task} → 本仓 {@code --suite-command} 子进程缝
 * （stdout = schema 1.0 报告，rc 0 绿 / 1 红——LoopRunner 同款）+ 修复任务复用
 * {@link RepairMapper} 严格版（修复上下文参数按需装配：仅阻断失败需要生成修复任务时
 * 校验，缺参 → failed 显式，不静默跳过——上游 {@code build_repair_task} 无上下文）；
 * ②上游 ValueError 裸抛 → 参数校验走 UsageException rc 2（L10 LoopRunner 口径承袭）；
 * ③上游裸栈 rc 1 → malformed-state / save-error 落 Main 顶层异常边界（JSON 错误契约
 * rc 1，退出码面一致、输出形态按本仓 L01 合同）。
 *
 * <p><b>上游缺口如实暴露面</b>（讲义 §1.3 第 6 条，不粉饰——治理层留后续讲次）：
 * {@code move} 只记转移不验边（unchecked-move 直调跳步可复现）；空报告零阻断也进入
 * 等待（消费者校验缺口，empty-report 接口反例）；批准未绑定候选身份（stale-approval）；
 * 传入姓名不认证身份（教学 reviewer 字符串）。
 *
 * <p>CLI 面（REGISTRY {@code graph-run}，repair-map / loop-run 先例）：{@code --max-rounds}
 * （默认 3，1..10）{@code --reject-once}（教学打回演示）{@code --require-human-review}
 * {@code --state-file}（持久化与恢复面；有状态文件即等待，上游 {@code persistence}
 * 同语义）{@code --review-decision approve|reject} + {@code --reviewer}（等待点决定，
 * 决定必须具名）{@code --suite-command}（检查注入缝，必填）；修复上下文（阻断失败时
 * 按需校验）：{@code --source-task} {@code --source-version} {@code --allowed-file}
 * （可多）{@code --case}（可多）{@code --candidate} {@code --python} {@code --objective}
 * （缺省同 loop-run 词面）{@code --suite}（缺省 all）{@code --repair-task-path}（缺省
 * 状态文件同目录 graph-repair-task.json；上游 {@code .runtime/graph-repair-task.json}
 * 同名）。退出码（对齐上游 main）：completed 0、awaiting_human_review 3（<b>正常等待
 * 不是失败</b>）、其余 2；用法错误 2；状态 IO 故障 1（顶层边界）。
 */
public final class GraphRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 检查子进程超时（l06–l10 探针 / LoopRunner 同值先例）。 */
    private static final int SUITE_TIMEOUT_SECONDS = 300;

    /** 循环停止态 = 终态 + 等待（上游 while 条件同集）。 */
    private static final Set<String> STOP_STATES =
            Set.of("completed", "failed", "stopped", "awaiting_human_review");

    private static final String DEFAULT_OBJECTIVE = "仅修复阻断级 Eval，保持现有公共接口和业务不变量";

    private GraphRunner() {}

    /** REGISTRY 命令面（Main 静态注册：{@code REGISTRY.register("graph-run", GraphRunner::execute)}）。 */
    public static int execute(String[] argv) {
        try {
            Args args = new Args(argv,
                    Set.of("--max-rounds", "--state-file", "--review-decision", "--reviewer",
                            "--suite-command", "--repair-task-path", "--python", "--candidate",
                            "--source-task", "--source-version", "--objective", "--suite"),
                    Set.of("--reject-once", "--require-human-review"),
                    List.of(), Set.of("--allowed-file", "--case"));
            int maxRounds = parseInt(args.optional("--max-rounds", "3"), "max_rounds");
            if (maxRounds < 1 || maxRounds > 10) {
                throw new Args.UsageException("max_rounds 必须在 1..10");
            }
            String reviewDecision = args.has("--review-decision") ? args.require("--review-decision") : null;
            if (reviewDecision != null && !Set.of("approve", "reject").contains(reviewDecision)) {
                throw new Args.UsageException("review_decision 必须为 approve 或 reject");
            }
            String reviewer = args.optional("--reviewer", "");
            if (reviewDecision != null && reviewer.isBlank()) {
                throw new Args.UsageException("提交人工审核决定时必须记录审核人");
            }
            String suiteCommand = args.require("--suite-command");
            Path stateFile = args.has("--state-file")
                    ? Path.of(args.require("--state-file")).toAbsolutePath().normalize() : null;
            RepairConfig repair = new RepairConfig(
                    args.optional("--source-task", ""), args.optional("--source-version", ""),
                    args.optional("--objective", DEFAULT_OBJECTIVE),
                    args.repeatingList("--allowed-file"), args.repeatingList("--case"),
                    args.has("--candidate")
                            ? Path.of(args.require("--candidate")).toAbsolutePath().normalize() : null,
                    args.has("--python")
                            ? Path.of(args.require("--python")).toAbsolutePath().normalize() : null,
                    args.has("--repair-task-path")
                            ? Path.of(args.require("--repair-task-path")).toAbsolutePath().normalize() : null,
                    args.optional("--suite", "all"));
            Map<String, Object> result = runGraph(maxRounds, args.flag("--reject-once"),
                    args.flag("--require-human-review"), stateFile, reviewDecision, reviewer,
                    suiteCommand, repair);
            System.out.println(PyJson.dumps(result));
            String status = String.valueOf(result.get("status"));
            return "completed".equals(status) ? 0 : "awaiting_human_review".equals(status) ? 3 : 2;
        } catch (Args.UsageException error) {
            System.err.println("error: " + error.getMessage());
            System.err.println("usage: workbench graph-run [--options]");
            return 2;
        }
    }

    /** 修复上下文（阻断失败时按需装配与校验——上游 build_repair_task 无上下文）。 */
    private record RepairConfig(String sourceTask, String sourceVersion, String objective,
                                List<String> allowedFiles, List<String> cases, Path candidate,
                                Path python, Path repairTaskPath, String suite) {}

    /**
     * 状态机主体（逐句对齐上游 run_graph）：状态加载（损坏 JSON 逃逸到顶层边界）→
     * 等待点决定（主循环前）→ 主循环（检查异常 → failed 显式）→ 保存（父路径被文件
     * 占用等 IO 故障逃逸到顶层边界——不吞不伪装）。
     */
    private static Map<String, Object> runGraph(int maxRounds, boolean rejectOnce, boolean requireHumanReview,
            Path stateFile, String reviewDecision, String reviewer, String suiteCommand,
            RepairConfig repair) {
        DeliveryState state = stateFile != null && Files.isRegularFile(stateFile)
                ? loadState(stateFile) : new DeliveryState();
        if ("awaiting_human_review".equals(state.state) && reviewDecision != null) {
            state.reviewer = reviewer.strip();
            state.reviewDecision = reviewDecision;
            state.reviewedAt = OffsetDateTime.now(ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.SECONDS).toString();
            if ("approve".equals(reviewDecision)) {
                state.move("completed", "审核人 " + state.reviewer + " 批准交付");
            } else {
                state.move("develop", "审核人 " + state.reviewer + " 打回交付");
            }
        }
        try {
            while (!STOP_STATES.contains(state.state)) {
                switch (state.state) {
                    case "develop" -> {
                        state.roundNo += 1;
                        if (state.roundNo > maxRounds) {
                            state.move("stopped", "达到最大轮数，保留剩余失败");
                        } else {
                            state.move("test", "本轮最小变更完成，交给统一 Harness");
                        }
                    }
                    case "test" -> {
                        SuiteResult suite = runSuite(suiteCommand);
                        state.report = suite.report();
                        if (suite.blockingFailed()) {
                            state.repairTask = buildRepairTask(state, repair, suite.reportText(),
                                    suite.exitCode(), stateFile);
                            state.move("rework", "阻断级 Eval 失败");
                        } else {
                            state.move("human_review", "阻断项为零，进入人工决策点");
                        }
                    }
                    case "rework" -> {
                        if (state.roundNo >= maxRounds) {
                            state.move("stopped", "未收敛，禁止宣称成功");
                        } else {
                            state.move("develop", "仅携带失败证据和禁止变更进入下一轮");
                        }
                    }
                    case "human_review" -> {
                        if (rejectOnce) {
                            rejectOnce = false;
                            state.move("develop", "演示：人工打回一次，验证回退路径");
                        } else if (requireHumanReview || stateFile != null) {
                            state.move("awaiting_human_review", "阻断项为零，等待具名审核人批准或打回");
                        } else {
                            state.move("completed", "本地演示审批策略自动通过");
                        }
                    }
                    default -> state.move("failed", "未知状态：" + state.state);
                }
            }
        } catch (Exception exc) {
            state.error = exc.getClass().getSimpleName()
                    + (exc.getMessage() == null ? "" : ": " + exc.getMessage());
            state.move("failed", "异常显式进入失败态");
        }
        if (stateFile != null) {
            // 上游 path.parent.mkdir(parents=True, exist_ok=True)：父路径被文件占用 →
            // 逃逸到顶层异常边界（保存失败不吞——状态文件未写入即未宣称进度已可靠记录；
            // 上游 FileExistsError 裸抛的同义面）
            try {
                Files.createDirectories(stateFile.getParent());
                Files.writeString(stateFile, PyJson.dumps(result(state)), StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw new java.io.UncheckedIOException("状态保存失败（不吞不伪装）：" + stateFile, error);
            }
        }
        return result(state);
    }

    // ---- 检查节点（--suite-command 子进程缝） ------------------------------------------

    private record SuiteResult(Object report, String reportText, boolean blockingFailed, int exitCode) {}

    /**
     * 检查子进程：超时 / 退出码越界（预期 0|1）→ 异常（主循环捕获 → failed 显式）；
     * stdout 解析为报告对象，blocking_failed 缺失 → 异常（不臆断——空结果 + 有效
     * summary 的 empty-report 形态照放行，上游消费者校验缺口如实保留）。
     */
    private static SuiteResult runSuite(String suiteCommand) throws Exception {
        ProcessRunner.Outcome check = ProcessRunner.run(Shlex.split(suiteCommand),
                Path.of("").toAbsolutePath(), SUITE_TIMEOUT_SECONDS, null);
        if (check.timedOut()) {
            throw new IllegalStateException("检查子进程超时（" + SUITE_TIMEOUT_SECONDS + "s）——本轮检查未完成");
        }
        if (check.returncode() == null || (check.returncode() != 0 && check.returncode() != 1)) {
            throw new IllegalStateException("检查退出码越界（预期 0|1，实际 " + check.returncode() + "）");
        }
        String reportText = check.stdoutText().strip();
        Object report = MAPPER.readValue(reportText, Object.class);
        Object blocking = report instanceof Map<?, ?> map
                && map.get("summary") instanceof Map<?, ?> summary
                ? summary.get("blocking_failed") : null;
        if (blocking == null) {
            throw new IllegalStateException(
                    "报告缺 summary.blocking_failed——不臆断不静默（上游 KeyError 同义面）");
        }
        boolean failed = blocking instanceof Number number
                ? number.intValue() > 0 : Boolean.TRUE.equals(blocking);
        return new SuiteResult(report, reportText, failed, check.returncode());
    }

    // ---- 修复任务（RepairMapper 严格版复用，上下文按需装配） --------------------------

    private static Map<String, Object> buildRepairTask(DeliveryState state, RepairConfig cfg,
            String reportText, int exitCode, Path stateFile) throws Exception {
        List<String> missing = new ArrayList<>();
        if (cfg.sourceTask().isBlank()) {
            missing.add("--source-task");
        }
        if (cfg.sourceVersion().isBlank()) {
            missing.add("--source-version");
        }
        if (cfg.allowedFiles().isEmpty()) {
            missing.add("--allowed-file");
        }
        if (cfg.cases().isEmpty()) {
            missing.add("--case");
        }
        if (cfg.candidate() == null) {
            missing.add("--candidate");
        }
        if (cfg.python() == null) {
            missing.add("--python");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("阻断失败需生成修复任务，修复上下文缺参：" + missing
                    + "——缺参显式失败，不静默跳过（上游 build_repair_task 无上下文，"
                    + "本仓 L09 严格映射器按需装配）");
        }
        Path taskPath = cfg.repairTaskPath() != null ? cfg.repairTaskPath()
                : stateFile != null ? stateFile.resolveSibling("graph-repair-task.json")
                : Path.of(".runtime/graph-repair-task.json").toAbsolutePath().normalize();
        Files.createDirectories(taskPath.getParent());
        Path reportPath = taskPath.resolveSibling("graph-suite-report.json");
        Files.writeString(reportPath, reportText, StandardCharsets.UTF_8);
        Map<String, Object> mapped = RepairMapper.map(
                reportText.getBytes(StandardCharsets.UTF_8),
                new RepairMapper.Context(cfg.sourceTask(), cfg.sourceVersion(), cfg.candidate(),
                        cfg.objective(), cfg.allowedFiles(), cfg.cases(), exitCode, cfg.python(),
                        reportPath, cfg.suite(), ApprovalChecks.class.getName()));
        if (!"repair_required".equals(mapped.get("status"))) {
            throw new IllegalStateException("映射器拒绝本报告（" + mapped.get("status")
                    + "）：" + mapped.get("errors"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> task = (Map<String, Object>) mapped.get("task");
        Files.writeString(taskPath, PyJson.dumps(task), StandardCharsets.UTF_8);
        return task;
    }

    // ---- 状态（上游 DeliveryState / _load_state / _result 同形） ----------------------

    /** 上游 DeliveryState 十字段的 Java 对应物；move 只记转移不验边（如实暴露 unchecked-move）。 */
    private static final class DeliveryState {
        String state = "develop";
        int roundNo;
        Object report;
        Map<String, Object> repairTask;
        List<Map<String, Object>> trace = new ArrayList<>();
        String error;
        String reviewer;
        String reviewDecision;
        String reviewedAt;

        void move(String target, String reason) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("from", state);
            entry.put("to", target);
            entry.put("reason", reason);
            entry.put("round", roundNo);
            trace.add(entry);
            state = target;
        }
    }

    @SuppressWarnings("unchecked")
    private static DeliveryState loadState(Path path) {
        Map<String, Object> data;
        try {
            data = MAPPER.readValue(path.toFile(), Map.class);
        } catch (IOException error) {
            // 读取阶段损坏 JSON / IO 故障：逃逸到顶层异常边界（上游 JSONDecodeError
            // 裸抛的同义面——不吞、不伪装成新状态）
            throw new java.io.UncheckedIOException("状态文件读取/解析失败（不吞不伪装）：" + path, error);
        }
        DeliveryState state = new DeliveryState();
        state.state = strOr(data, "status", strOr(data, "state", "develop"));
        state.roundNo = loadRounds(data);
        if (data.get("trace") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map) {
                    state.trace.add((Map<String, Object>) item);
                }
            }
        }
        state.report = data.get("report");
        Object repairTask = data.get("repair_task");
        state.repairTask = repairTask instanceof Map ? (Map<String, Object>) repairTask : null;
        state.error = strOrNull(data.get("error"));
        state.reviewer = strOrNull(data.get("reviewer"));
        state.reviewDecision = strOrNull(data.get("review_decision"));
        state.reviewedAt = strOrNull(data.get("reviewed_at"));
        return state;
    }

    /** 上游 _result 十字段投影（字段名与顺序同形，状态文件与 stdout 同源）。 */
    private static Map<String, Object> result(DeliveryState state) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", state.state);
        out.put("rounds", state.roundNo);
        out.put("trace", state.trace);
        out.put("report_summary",
                state.report instanceof Map<?, ?> map ? map.get("summary") : null);
        out.put("report", state.report);
        out.put("repair_task", state.repairTask);
        out.put("error", state.error);
        out.put("reviewer", state.reviewer);
        out.put("review_decision", state.reviewDecision);
        out.put("reviewed_at", state.reviewedAt);
        return out;
    }

    private static String strOr(Map<String, Object> data, String key, String fallback) {
        Object value = data.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static String strOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int loadRounds(Map<String, Object> data) {
        for (String key : new String[]{"rounds", "round_no"}) {
            Object value = data.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String text) {
                try {
                    return Integer.parseInt(text.strip());
                } catch (NumberFormatException ignored) {
                    // 落到下方逃逸（在场但不可解析——不静默回落）
                }
            }
            throw new IllegalStateException("状态字段 " + key + " 无效（在场但不可解析）：" + value
                    + "——不静默回落（上游 int() TypeError 逃逸同义面）");
        }
        return 0;
    }

    private static int parseInt(String raw, String label) {
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException error) {
            throw new Args.UsageException(label + " 须为整数：" + raw);
        }
    }
}
