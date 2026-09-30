package workbench.execution;

import workbench.bootstrap.Args;
import workbench.bootstrap.JsonOut;
import workbench.bootstrap.Ledger;
import workbench.bootstrap.PyJson;
import workbench.cli.CommandRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workbench V0 受控执行三命令（镜像 workbench/execution.py 的 _cmd_task_run /
 * _cmd_task_review / _cmd_task_show / register_commands，L04 合同，附录 A2）。
 *
 * <p>把 L03 能读的合同变成能组织的一次执行：绑定候选工作区、限制写集、调用执行器、
 * 收回 invocation / change_manifest / diff / 双流输出（逐项 SHA-256）、运行最小
 * Eval、停在 review 等人审。执行记录与上游 07-real-codex-executor.json 同形
 * （ADR-0002：执行器合同形状不变，适配器为语言无关子进程）。校验类拒绝（缺写集/
 * 坏候选/review 态重跑/自批/前置未接受）不写任何记录；执行类失败（失败/超时/越界/
 * eval 失败）如实落账，旧记录保留不覆盖。存储只经 Ledger 单一入口（读侧）与同一
 * 连接（写侧直插，Python execution.py 同层位），不建第二套账本；写集检查的局限如实
 * 声明：事前约束靠候选工作区绑定 + argv 注入提示词，事后以候选 git 状态实测比对——
 * 提示词约定不等于事前全部防住（讲义 D4）。
 */
public final class ExecutionCommands {

    private ExecutionCommands() {}

    /** REGISTRY 缝第二批注册（与 BootstrapCommands.register 同形，cli.py L04 注册段镜像）。 */
    public static void register(CommandRegistry registry) {
        registry.register("workbench-task-run", ExecutionCommands::taskRun);
        registry.register("workbench-task-review", ExecutionCommands::taskReview);
        registry.register("workbench-task-show", ExecutionCommands::taskShow);
    }

    // ---- workbench-task-run -----------------------------------------------------

    private static int taskRun(String[] argv) throws Exception {
        Args args = new Args(argv,
                Set.of("--runtime-dir", "--workspace", "--mode", "--executor-command",
                        "--executor-prompt-file", "--eval-command", "--execution-timeout",
                        "--actor"),
                Set.of(),
                List.of("task_id"),
                Set.of("--write-scope"));
        String mode = args.optional("--mode", "verify");
        if (!mode.equals("verify") && !mode.equals("code")) {
            throw new Args.UsageException("argument --mode: invalid choice: '" + mode
                    + "' (choose from 'verify', 'code')");
        }
        try (Ledger ledger = Ledger.open(Path.of(args.require("--runtime-dir")), false)) {
            if (ledger == null || ledger.workbenchRow() == null) {
                return JsonOut.fail(Ledger.UNINITIALIZED);
            }
            String taskId = args.positional(0);
            if (!ledger.taskExists(taskId)) {
                return JsonOut.fail(Ledger.MISSING_TASK + ": 任务不存在：" + taskId + "，记录未追加");
            }
            Path workspace = Path.of(args.require("--workspace"));
            String invalid = WorkspaceInspector.workspaceError(workspace);
            if (invalid != null) {
                return JsonOut.fail(invalid);
            }
            List<String> scope = WriteScope.normalize(args.repeatingList("--write-scope"));
            // shell 形命令串经 shlex 解析为 argv（Python 复查轮 §3-15：单串 + shlex 是冻结终态口径）
            List<String> evalArgv;
            try {
                evalArgv = Shlex.split(args.require("--eval-command"));
            } catch (Shlex.Unparseable error) {
                return JsonOut.fail(
                        "eval_command_unparseable: --eval-command 不是合法的 shell 形命令串，执行未启动");
            }
            List<String> executorCommand = null;
            if (args.has("--executor-command")) {
                try {
                    executorCommand = Shlex.split(args.require("--executor-command"));
                } catch (Shlex.Unparseable error) {
                    return JsonOut.fail(
                            "executor_command_unparseable: --executor-command 不是合法的 shell 形命令串，执行未启动");
                }
            }
            Integer executionTimeout = args.has("--execution-timeout")
                    ? args.requireInt("--execution-timeout") : null;
            String promptText = "";
            if (mode.equals("code")) {
                if (scope.isEmpty()) {
                    return JsonOut.fail("write_scope_required: code 模式必须声明允许写集，拒绝启动");
                }
                if (executorCommand == null) {
                    return JsonOut.fail("executor_command_required: code 模式必须给出执行器命令，拒绝启动");
                }
            } else if (executorCommand != null) {
                return JsonOut.fail("verify_rejects_executor_command: 仅复验模式不调用执行器进程，拒绝执行器命令");
            }
            if (executionTimeout == null) {
                // 建设合同"工作目录、写集、超时三者缺一拒绝启动"；预算是执行器合同的
                // 组成部分（ADR-0002），verify 模式同样须显式声明（Eval 也受它约束）
                return JsonOut.fail("execution_timeout_required: 必须显式声明 --execution-timeout，执行未启动");
            }
            if (!args.optional("--executor-prompt-file", "").isEmpty()) {
                String promptFile = args.require("--executor-prompt-file");
                try {
                    promptText = Files.readString(Path.of(promptFile));
                } catch (IOException error) {
                    return JsonOut.fail("executor_prompt_unreadable: 提示词文件不可读取："
                            + promptFile + "，执行未启动");
                }
            }
            String actor = args.require("--actor");
            if (actor.strip().isEmpty()) {
                return JsonOut.fail("required_actor_missing: 执行发起人必须具名，执行未启动");
            }
            String state = ledger.taskState(taskId);
            if (state.equals("review")) {
                return JsonOut.fail("task_in_review: 任务处于人工复核态，拒绝再次运行：" + taskId);
            }

            Path realWorkspace = workspace.toRealPath();
            Map<String, Object> record = JsonOut.ordered(
                    "task_id", taskId, "mode", mode,
                    "workspace", realWorkspace.toString(),
                    "write_scope", scope,
                    "executor_command", executorCommand,
                    "executor_prompt", promptText,
                    "eval_command", evalArgv);
            if (mode.equals("verify")) {
                ProcessRunner.Outcome eval = ProcessRunner.run(evalArgv, realWorkspace,
                        executionTimeout, null);
                Integer evalRc = eval.returncode();
                record.put("returncode", null);
                record.put("timed_out", false);
                record.put("stdout_text", "");
                record.put("stderr_text", "");
                record.put("changed_files", List.of());
                record.put("out_of_scope_files", List.of());
                record.put("change_manifest", List.of());
                record.put("diff_text", "");
                record.put("eval_returncode", evalRc);
                record.put("eval_output_text", eval.stdoutText() + eval.stderrText());
                record.put("eval_timed_out", eval.timedOut());
                record.put("status", evalRc != null && evalRc == 0 ? "verify_completed" : "eval_failed");
            } else {
                ProcessRunner.Outcome outcome = ProcessRunner.run(executorCommand, realWorkspace,
                        executionTimeout, promptText);
                WorkspaceInspector.Changes changes =
                        WorkspaceInspector.collectChanges(realWorkspace, scope);
                record.put("returncode", outcome.returncode());
                record.put("timed_out", outcome.timedOut());
                record.put("stdout_text", outcome.stdoutText());
                record.put("stderr_text", outcome.stderrText());
                record.put("changed_files", changes.changedFiles());
                record.put("out_of_scope_files", changes.outOfScopeFiles());
                record.put("change_manifest", changes.manifest());
                record.put("diff_text", changes.diffText());
                if (outcome.timedOut()) {
                    record.put("status", "timeout");       // 超时：Eval 前停止，输出与 Diff 已留证
                } else if (outcome.returncode() == null || outcome.returncode() != 0) {
                    record.put("status", "failed");        // 执行器失败（含启动失败）：不冒充成功
                } else if (!changes.outOfScopeFiles().isEmpty()) {
                    record.put("status", "out_of_scope");  // 越界：Eval 前停止，不自动回滚
                } else {
                    ProcessRunner.Outcome eval = ProcessRunner.run(evalArgv, realWorkspace,
                            executionTimeout, null);
                    Integer evalRc = eval.returncode();
                    record.put("eval_returncode", evalRc);
                    record.put("eval_output_text", eval.stdoutText() + eval.stderrText());
                    record.put("eval_timed_out", eval.timedOut());
                    record.put("status", evalRc != null && evalRc == 0 ? "completed" : "eval_failed");
                }
            }
            record.put("stdout_sha256", Ledger.sha256((String) record.get("stdout_text")));
            record.put("stderr_sha256", Ledger.sha256((String) record.get("stderr_text")));
            record.put("diff_sha256", Ledger.sha256((String) record.get("diff_text")));
            record.put("eval_output_sha256",
                    Ledger.sha256((String) record.getOrDefault("eval_output_text", "")));
            long executionId = insertExecution(ledger, record, actor.strip());
            boolean ok = Ledger.EXECUTION_COMPLETE_STATUSES.contains(
                    String.valueOf(record.get("status")));
            Map<String, Object> payload = new LinkedHashMap<>(record);
            payload.put("execution_id", executionId);
            payload.put("actor", actor.strip());
            payload.put("observed_at", Ledger.now());
            JsonOut.emit(JsonOut.ordered("ok", ok, "flowerp_connected", Ledger.FLOWERP_CONNECTED,
                    "execution", payload, "task_state", ledger.taskState(taskId)));
            return ok ? 0 : 1;
        }
    }

    /** 执行记录入库（镜像 execution.py 的 _insert_execution：JSON 列紧凑形，摘要对存储文本）。 */
    private static long insertExecution(Ledger ledger, Map<String, Object> record,
                                        String actor) throws SQLException {
        String writeScopeJson = PyJson.dumpsCompact(record.get("write_scope"));
        String executorCommandJson = record.get("executor_command") == null
                ? null : PyJson.dumpsCompact(record.get("executor_command"));
        String changedJson = PyJson.dumpsCompact(record.get("changed_files"));
        String outOfScopeJson = PyJson.dumpsCompact(record.get("out_of_scope_files"));
        String manifestJson = PyJson.dumpsCompact(record.get("change_manifest"));
        String evalCommandJson = PyJson.dumpsCompact(record.get("eval_command"));
        String evalOutputText = (String) record.getOrDefault("eval_output_text", "");
        try (PreparedStatement ps = ledger.connection().prepareStatement(
                "INSERT INTO executions (task_id, mode, workspace, write_scope, executor_command,"
                        + " executor_prompt, returncode, timed_out, stdout_text, stderr_text,"
                        + " stdout_sha256, stderr_sha256, changed_files, out_of_scope_files,"
                        + " change_manifest, change_manifest_sha256, diff_text, diff_sha256,"
                        + " eval_command, eval_returncode, eval_output_text, eval_output_sha256,"
                        + " eval_timed_out, status, actor, observed_at, recorded_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, (String) record.get("task_id"));
            ps.setString(2, (String) record.get("mode"));
            ps.setString(3, (String) record.get("workspace"));
            ps.setString(4, writeScopeJson);
            ps.setString(5, executorCommandJson);
            ps.setString(6, (String) record.get("executor_prompt"));
            ps.setObject(7, record.get("returncode"));
            ps.setInt(8, (Boolean) record.get("timed_out") ? 1 : 0);
            ps.setString(9, (String) record.get("stdout_text"));
            ps.setString(10, (String) record.get("stderr_text"));
            ps.setString(11, (String) record.get("stdout_sha256"));
            ps.setString(12, (String) record.get("stderr_sha256"));
            ps.setString(13, changedJson);
            ps.setString(14, outOfScopeJson);
            ps.setString(15, manifestJson);
            ps.setString(16, Ledger.sha256(manifestJson));
            ps.setString(17, (String) record.get("diff_text"));
            ps.setString(18, (String) record.get("diff_sha256"));
            ps.setString(19, evalCommandJson);
            ps.setObject(20, record.get("eval_returncode"));
            ps.setString(21, evalOutputText);
            ps.setString(22, (String) record.get("eval_output_sha256"));
            ps.setInt(23, Boolean.TRUE.equals(record.get("eval_timed_out")) ? 1 : 0);
            ps.setString(24, (String) record.get("status"));
            ps.setString(25, actor);
            ps.setString(26, Ledger.now());
            ps.setString(27, Ledger.now());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    // ---- workbench-task-review --------------------------------------------------

    private static int taskReview(String[] argv) throws Exception {
        Args args = new Args(argv,
                Set.of("--runtime-dir", "--reviewer", "--decision", "--note"),
                Set.of(), List.of("task_id"));
        String decision = args.require("--decision");
        if (!decision.equals("approve") && !decision.equals("reject")) {
            throw new Args.UsageException("argument --decision: invalid choice: '" + decision
                    + "' (choose from 'approve', 'reject')");
        }
        try (Ledger ledger = Ledger.open(Path.of(args.require("--runtime-dir")), false)) {
            if (ledger == null || ledger.workbenchRow() == null) {
                return JsonOut.fail(Ledger.UNINITIALIZED);
            }
            String taskId = args.positional(0);
            if (!ledger.taskExists(taskId)) {
                return JsonOut.fail(Ledger.MISSING_TASK + ": 任务不存在：" + taskId + "，记录未追加");
            }
            String reviewer = args.require("--reviewer").strip();
            if (reviewer.isEmpty()) {
                return JsonOut.fail("required_reviewer_missing: 复核人必须具名，记录未追加");
            }
            Map<String, Object> latest = ledger.latestExecution(taskId);
            if (latest == null) {
                return JsonOut.fail("no_execution_to_review: 任务尚无执行记录，不能复核：" + taskId);
            }
            if (reviewer.equals(String.valueOf(latest.get("actor")))) {
                return JsonOut.fail("self_review_rejected: 复核人与最近一次执行者是同一人（" + reviewer
                        + "），执行者不能批准自己，记录未追加");
            }
            if (!ledger.taskState(taskId).equals("review")) {
                return JsonOut.fail("task_not_in_review: 任务不处于人工复核态，不能复核：" + taskId);
            }
            long executionId = ((Number) latest.get("execution_id")).longValue();
            long reviewId;
            try (PreparedStatement ps = ledger.connection().prepareStatement(
                    "INSERT INTO reviews (task_id, execution_id, reviewer, decision, note,"
                            + " reviewed_at) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, taskId);
                ps.setLong(2, executionId);
                ps.setString(3, reviewer);
                ps.setString(4, decision);
                ps.setString(5, args.optional("--note", ""));
                ps.setString(6, Ledger.now());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    reviewId = keys.getLong(1);
                }
            }
            JsonOut.emit(JsonOut.ordered("ok", true, "flowerp_connected", Ledger.FLOWERP_CONNECTED,
                    "review", JsonOut.ordered("review_id", reviewId, "task_id", taskId,
                            "execution_id", executionId, "reviewer", reviewer,
                            "decision", decision, "note", args.optional("--note", "")),
                    "task_state", ledger.taskState(taskId)));
            return 0;
        }
    }

    // ---- workbench-task-show ----------------------------------------------------

    private static int taskShow(String[] argv) throws Exception {
        Args args = new Args(argv, Set.of("--runtime-dir"), Set.of(), List.of("task_id"));
        try (Ledger ledger = Ledger.open(Path.of(args.require("--runtime-dir")), false)) {
            if (ledger == null || ledger.workbenchRow() == null) {
                return JsonOut.fail(Ledger.UNINITIALIZED);
            }
            String taskId = args.positional(0);
            Map<String, Object> task = ledger.taskRow(taskId);
            if (task == null) {
                return JsonOut.fail(Ledger.MISSING_TASK + ": 任务不存在：" + taskId);
            }
            Object prerequisite = task.get("prerequisite_task_id");
            JsonOut.emit(JsonOut.ordered("ok", true, "flowerp_connected", Ledger.FLOWERP_CONNECTED,
                    "task", JsonOut.ordered(
                            "task_id", task.get("task_id"),
                            "request", task.get("request"),
                            "requirement_id", task.get("requirement_id"),
                            "actor", task.get("actor"),
                            "prerequisite_task_id", prerequisite == null ? "" : prerequisite,
                            "state", ledger.taskState(taskId),
                            "executions", ledger.executionsFor(taskId),
                            "reviews", ledger.reviewsFor(taskId))));
            return 0;
        }
    }
}
