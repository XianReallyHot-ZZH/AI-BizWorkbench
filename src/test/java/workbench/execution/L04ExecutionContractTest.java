package workbench.execution;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import workbench.testsupport.Cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L04 合同测试：讲义 §2 C1..C15 → 用例映射（Spec = docs/lessons/L04-受控执行.md §2 + 附录 A2）。
 *
 * <p>映射表（验收项 → 测试名 → 实际操作 → 比较什么）——红点组 = Java 受控执行能力缺失
 * （移植注记 A2 C1：目标组 commit 1 红，invalid-choice rc 2）；就位组 = 已随 L01-java
 * 终态移植的面（映射表声明，commit 1 即绿——Python L02 ReferencedCommandsAreReal 先例）：
 *
 * <pre>
 * 红点组（目标能力缺失，commit 1 红）：
 * C3     → codeRunRecordsManifestDiffAndStopsAtReview   → code 模式替身执行器写 scope 内文件 + eval 绿 → rc 0 + 记录全形（write_scope/change_manifest 前后 SHA/diff/双流 SHA/returncode/timed_out）+ task_state review
 * C4     → taskRunRequiresWriteScopeInCodeMode          → code 模式缺写集 → rc 1 + 词面 + 不落记录
 * C4     → taskRunRequiresExecutorCommandInCodeMode     → code 模式缺执行器命令 → rc 1 + 词面
 * C4     → taskRunRequiresExplicitTimeout               → 缺 --execution-timeout → rc 1 + 词面（复查轮 §3-6：预算是合同组成部分）
 * C4     → verifyRejectsExecutorCommand                 → verify + 执行器命令 → rc 1 + 词面
 * C4     → workspaceRejectsBeforeProcess（×3）           → 不存在 / 缺 .git / 缺 HEAD 候选 → rc 1 + workspace_invalid 词面（拒于进程前）
 * C4     → outOfScopeStopsBeforeEval                    → 执行器写 scope 内外两文件 → rc 1 + out_of_scope + eval 未运行（marker 缺席）+ 越界文件留证不回滚
 * C5     → verifyModeNeverLaunchesExecutor              → 仅复验 + eval 落 marker → executor_command null + 执行器产物不存在 + marker 在绑定候选 cwd
 * C5/C6  → executorFailurePreservesOutputAndDiff        → 替身执行器 rc 3 带输出 → rc 1 + failed + 双流原文 + Diff 保留
 * C5/C6  → timeoutPreservesPartialOutput                → printf 后 sleep + timeout 1 → rc 1 + timed_out + 部分输出保留
 * C6     → launchErrorPreservedAsFailedRecord           → 执行器二进制不存在 → rc 1 + failed 记录 + stderr 含"启动失败"（errno 词面语言绑定，按 JD6 只断形状）
 * C6     → evalCommandUnparseableRejected               → 不平衡引号 → rc 1 + eval_command_unparseable（不落记录）
 * C7     → evalRunsInBoundWorkspaceCwd                  → eval 相对路径落 marker → marker 在候选内（eval 绑定候选 cwd 实跑）
 * C7     → evalFailureKeepsRecordAndStatus              → eval rc 1 → rc 1 + eval_failed + eval 输出保留
 * C8     → reviewStateRejectsRerun                      → review 态再 run → rc 1 + task_in_review + 原记录与状态不毁
 * C8     → selfReviewRejected                           → 复核人 = 最近执行者 → rc 1 + self_review_rejected
 * C8     → reviewAnchorsToExecutionId                   → approve → accepted；再执行 → 回 review（旧接受失效），reviews 只追加
 * C7     → taskShowSummarizesExecutionsAndReviews       → task-show → 状态 + 执行记录 + 复核记录摘要形
 * C14    → statusDigestCoversExecutions                 → 篡改 change_manifest → status rc 1 + execution_digest_mismatch（S-c1）
 * C6     → rerunAfterFailureKeepsOldRecord              → 失败后再执行 → 两条执行记录并存（旧尝试保留不覆盖）
 * C9     → prerequisiteAcceptedAllowsTaskCreation       → A verify 执行 + 具名接受后 task-create 放行 → prerequisite_task_id 入账（正面半边依赖 V0，红点）
 * 就位组（已随 L01-java 终态移植/文档护栏，commit 1 即绿）：
 * C9     → prerequisiteRejectionFaces                   → 前置不存在/未接受两拒绝词面（--prerequisite-task 已在 BootstrapCommands）
 * C2     → bootstrapContractParsesViaJavaCli            → 冻结 WB-L04-BOOTSTRAP.md 经 Java CLI spec 解析 rc 0（结构半边重验；文档零重写）
 * C13    → pythonEraSubmissionArtifactsInPlace          → Python 五工件 + FDE_SPEC.md 在场（零重写护栏）
 * </pre>
 *
 * <p>所有工作台行为用例经真实 CLI 子进程（java -cp … workbench.cli.Main）驱动，
 * 不绕开入口直调内部函数（测试口径）——起始红因此无编译依赖；替身执行器 = 测试控制
 * 的 sh 脚本进程（D7 移植：证明 V0 机制，不证明真实 claude -p 行为，后者由 B 段实跑留证）。
 * 错误词面与冻结 Python 逐字同形（A2 C4）；golden l04 字节对照另见 GoldenL04ReplayTest。
 */
class L04ExecutionContractTest {

    private static final String EXECUTOR = "G-EXECUTOR";
    private static final String OPERATOR = "G-OPERATOR";
    private static final String REVIEWER = "G-REVIEWER";

    /** 替身执行器：写 scope 内文件 + 固定双流输出（单串 shell 形，经实现 shlex 解析）。 */
    private static final String EXEC_WRITE =
            "sh -c 'printf v1 > delivery.csv; printf created\\n'";
    /** 越界替身：scope 内外各写一件。 */
    private static final String EXEC_SNEAK =
            "sh -c 'printf x > sneaked.txt; printf v1 > delivery.csv; printf both\\n'";
    /** 失败替身：rc 3 带双流输出。 */
    private static final String EXEC_FAIL =
            "sh -c 'echo boom-out; echo boom-err >&2; exit 3'";
    /** 超时替身：先落部分输出再长眠。 */
    private static final String EXEC_TIMEOUT =
            "sh -c 'printf partial-out; printf partial-err >&2; sleep 30'";
    private static final String EXEC_NOTHING = "sh -c 'exit 0'";
    private static final String EVAL_OK = "sh -c 'exit 0'";
    private static final String EVAL_MARKER = "sh -c 'echo eval-ran > eval-marker.txt; exit 0'";
    private static final String EVAL_FAIL = "sh -c 'echo eval-says-no >&2; exit 1'";

    @TempDir
    Path temp;

    // ---- 夹具 ------------------------------------------------------------------

    private record Ledger(Path rt, Path workspace) {}

    private Ledger ledger() throws Exception {
        Path rt = temp.resolve("rt");
        Path ws = temp.resolve("candidate");
        Files.createDirectories(ws);
        Files.writeString(ws.resolve("seed.txt"), "seed-v1\n");
        git(null, "init", "-q", ws.toString());
        git(ws, "add", ".");
        git(ws, "-c", "user.name=G", "-c", "user.email=g@example", "-c", "commit.gpgsign=false",
                "commit", "-q", "-m", "seed");
        assertThat(Cli.run("workbench-init", "--runtime-dir", rt.toString(),
                "--name", "合同测试工作台", "--owner", "T-OWNER").exitCode()).isZero();
        assertThat(Cli.run("workbench-project-add", "--runtime-dir", rt.toString(),
                "--project-id", "T-PROJ", "--name", "合同测试项目", "--path", ".",
                "--purpose", "L04 合同测试").exitCode()).isZero();
        return new Ledger(rt, ws);
    }

    private void createTask(Ledger l, String taskId) {
        assertThat(Cli.run("workbench-task-create", "--runtime-dir", l.rt.toString(),
                "--project-id", "T-PROJ", "--task-id", taskId, "--requirement-id", "REQ-L04",
                "--request", "L04 合同测试任务", "--actor", OPERATOR).exitCode()).isZero();
    }

    private static void git(Path dir, String... argv) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        if (dir != null) {
            command.add("-C");
            command.add(dir.toString());
        }
        command.addAll(List.of(argv));
        Process process = new ProcessBuilder(command).start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("git 夹具失败：" + String.join(" ", command));
        }
    }

    private String[] taskRun(Ledger l, String taskId, String mode, String executor,
                             String evalCommand, int timeout) {
        List<String> argv = new ArrayList<>(List.of("workbench-task-run", taskId,
                "--runtime-dir", l.rt.toString(), "--workspace", l.workspace.toString(),
                "--mode", mode, "--write-scope", "delivery.csv",
                "--eval-command", evalCommand, "--execution-timeout", String.valueOf(timeout),
                "--actor", EXECUTOR));
        if (executor != null) {
            argv.add("--executor-command");
            argv.add(executor);
        }
        return argv.toArray(String[]::new);
    }

    private JsonNode executionOf(Cli.Result result) {
        return Cli.json(result).get("execution");
    }

    // ---- 红点组（目标能力缺失，commit 1 红）--------------------------------------

    @Test
    void codeRunRecordsManifestDiffAndStopsAtReview() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        Cli.Result result = Cli.run(taskRun(l, "T-001", "code", EXEC_WRITE, EVAL_OK, 60));
        assertThat(result.exitCode()).as("执行成功 rc 0").isZero();
        JsonNode payload = Cli.json(result);
        JsonNode execution = payload.get("execution");
        assertThat(execution.get("mode").asText()).isEqualTo("code");
        assertThat(execution.get("write_scope").toString()).contains("delivery.csv");
        JsonNode manifest = execution.get("change_manifest").get(0);
        assertThat(manifest.get("path").asText()).isEqualTo("delivery.csv");
        assertThat(manifest.get("change").asText()).isEqualTo("added");
        assertThat(manifest.get("before_sha256").isNull()).isTrue();
        assertThat(manifest.get("after_sha256").asText()).hasSize(64);
        assertThat(execution.get("diff_text").asText()).contains("diff --git", "delivery.csv");
        assertThat(execution.get("returncode").asInt()).isZero();
        assertThat(execution.get("timed_out").asBoolean()).isFalse();
        assertThat(execution.get("stdout_text").asText()).isEqualTo("created\n");
        assertThat(execution.get("stdout_sha256").asText()).hasSize(64);
        assertThat(execution.get("change_manifest_sha256").asText()).hasSize(64);
        assertThat(execution.get("eval_returncode").asInt()).isZero();
        assertThat(execution.get("status").asText()).isEqualTo("completed");
        assertThat(payload.get("task_state").asText()).isEqualTo("review");
    }

    @Test
    void taskRunRequiresWriteScopeInCodeMode() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        List<String> argv = new ArrayList<>(List.of("workbench-task-run", "T-001",
                "--runtime-dir", l.rt.toString(), "--workspace", l.workspace.toString(),
                "--mode", "code", "--eval-command", EVAL_OK,
                "--execution-timeout", "60", "--actor", EXECUTOR,
                "--executor-command", EXEC_WRITE));
        Cli.Result result = Cli.run(argv.toArray(String[]::new));
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Cli.json(result).get("error").asText())
                .isEqualTo("write_scope_required: code 模式必须声明允许写集，拒绝启动");
        Cli.Result show = Cli.run("workbench-task-show", "T-001", "--runtime-dir", l.rt.toString());
        assertThat(Cli.json(show).get("task").get("executions")).isEmpty();
    }

    @Test
    void taskRunRequiresExecutorCommandInCodeMode() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        Cli.Result result = Cli.run(taskRun(l, "T-001", "code", null, EVAL_OK, 60));
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Cli.json(result).get("error").asText())
                .isEqualTo("executor_command_required: code 模式必须给出执行器命令，拒绝启动");
    }

    @Test
    void taskRunRequiresExplicitTimeout() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        List<String> argv = new ArrayList<>(List.of("workbench-task-run", "T-001",
                "--runtime-dir", l.rt.toString(), "--workspace", l.workspace.toString(),
                "--mode", "code", "--write-scope", "delivery.csv",
                "--eval-command", EVAL_OK, "--actor", EXECUTOR,
                "--executor-command", EXEC_WRITE));
        Cli.Result result = Cli.run(argv.toArray(String[]::new));
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Cli.json(result).get("error").asText())
                .isEqualTo("execution_timeout_required: 必须显式声明 --execution-timeout，执行未启动");
    }

    @Test
    void verifyRejectsExecutorCommand() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        Cli.Result result = Cli.run(taskRun(l, "T-001", "verify", EXEC_NOTHING, EVAL_OK, 60));
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Cli.json(result).get("error").asText())
                .isEqualTo("verify_rejects_executor_command: 仅复验模式不调用执行器进程，拒绝执行器命令");
    }

    @Test
    void workspaceRejectsBeforeProcess() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        Path noGit = temp.resolve("nogit");
        Files.createDirectories(noGit);
        Path noHead = temp.resolve("nohead");
        git(null, "init", "-q", noHead.toString());
        record Case(String workspace, String face) {}
        List<Case> cases = List.of(
                new Case(temp.resolve("ghost").toString(),
                        "workspace_invalid: 工作目录不存在：" + temp.resolve("ghost")),
                new Case(noGit.toString(),
                        "workspace_invalid: 工作目录不是 git 候选（缺 .git）：" + noGit),
                new Case(noHead.toString(),
                        "workspace_invalid: 候选没有起点提交（缺 HEAD）：" + noHead));
        for (Case c : cases) {
            Cli.Result result = Cli.run("workbench-task-run", "T-001",
                    "--runtime-dir", l.rt.toString(), "--workspace", c.workspace,
                    "--mode", "verify", "--eval-command", EVAL_OK,
                    "--execution-timeout", "60", "--actor", EXECUTOR);
            assertThat(result.exitCode()).as("%s 应拒绝", c.workspace).isEqualTo(1);
            assertThat(Cli.json(result).get("error").asText()).isEqualTo(c.face);
        }
    }

    @Test
    void outOfScopeStopsBeforeEval() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        Cli.Result result = Cli.run(taskRun(l, "T-001", "code", EXEC_SNEAK, EVAL_MARKER, 60));
        assertThat(result.exitCode()).isEqualTo(1);
        JsonNode execution = executionOf(result);
        assertThat(execution.get("status").asText()).isEqualTo("out_of_scope");
        assertThat(execution.get("out_of_scope_files").toString()).contains("sneaked.txt");
        assertThat(execution.get("changed_files").toString()).contains("delivery.csv");
        assertThat(execution.has("eval_returncode"))
                .as("越界在 eval 前停止：eval 键缺位").isFalse();
        assertThat(l.workspace.resolve("eval-marker.txt"))
                .as("eval 未运行").doesNotExist();
        assertThat(l.workspace.resolve("sneaked.txt"))
                .as("越界文件留证：不自动回滚").exists();
    }

    @Test
    void verifyModeNeverLaunchesExecutor() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        Cli.Result result = Cli.run(taskRun(l, "T-001", "verify", null, EVAL_MARKER, 60));
        assertThat(result.exitCode()).isZero();
        JsonNode execution = executionOf(result);
        assertThat(execution.get("executor_command").isNull())
                .as("仅复验：executor_command 为 null").isTrue();
        assertThat(execution.get("status").asText()).isEqualTo("verify_completed");
        assertThat(l.workspace.resolve("eval-marker.txt"))
                .as("eval 在绑定候选 cwd 实跑").exists();
        assertThat(l.workspace.resolve("delivery.csv"))
                .as("执行器进程未被调用").doesNotExist();
    }

    @Test
    void executorFailurePreservesOutputAndDiff() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        Cli.Result result = Cli.run(taskRun(l, "T-001", "code", EXEC_FAIL, EVAL_OK, 60));
        assertThat(result.exitCode()).isEqualTo(1);
        JsonNode execution = executionOf(result);
        assertThat(execution.get("status").asText()).isEqualTo("failed");
        assertThat(execution.get("returncode").asInt()).isEqualTo(3);
        assertThat(execution.get("stdout_text").asText()).contains("boom-out");
        assertThat(execution.get("stderr_text").asText()).contains("boom-err");
        assertThat(execution.has("eval_returncode"))
                .as("执行器失败在 eval 前停止").isFalse();
    }

    @Test
    void timeoutPreservesPartialOutput() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        Cli.Result result = Cli.run(taskRun(l, "T-001", "code", EXEC_TIMEOUT, EVAL_OK, 1));
        assertThat(result.exitCode()).isEqualTo(1);
        JsonNode execution = executionOf(result);
        assertThat(execution.get("status").asText()).isEqualTo("timeout");
        assertThat(execution.get("timed_out").asBoolean()).isTrue();
        assertThat(execution.get("stdout_text").asText()).isEqualTo("partial-out");
        assertThat(execution.get("stderr_text").asText()).isEqualTo("partial-err");
    }

    @Test
    void launchErrorPreservedAsFailedRecord() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        Cli.Result result = Cli.run(taskRun(l, "T-001", "code",
                "./no-such-executor", EVAL_OK, 60));
        assertThat(result.exitCode()).isEqualTo(1);
        JsonNode execution = executionOf(result);
        assertThat(execution.get("status").asText())
                .as("启动失败必须落账而非裸崩（B 门实跑教训）").isEqualTo("failed");
        assertThat(execution.get("stderr_text").asText()).contains("启动失败");
    }

    @Test
    void evalCommandUnparseableRejected() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        Cli.Result result = Cli.run(taskRun(l, "T-001", "code", EXEC_WRITE,
                "sh -c 'unbalanced", 60));
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Cli.json(result).get("error").asText())
                .isEqualTo("eval_command_unparseable: --eval-command 不是合法的 shell 形命令串，执行未启动");
    }

    @Test
    void evalRunsInBoundWorkspaceCwd() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        Cli.Result result = Cli.run(taskRun(l, "T-001", "code", EXEC_WRITE, EVAL_MARKER, 60));
        assertThat(result.exitCode()).isZero();
        assertThat(l.workspace.resolve("eval-marker.txt")).exists();
    }

    @Test
    void evalFailureKeepsRecordAndStatus() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        Cli.Result result = Cli.run(taskRun(l, "T-001", "code", EXEC_WRITE, EVAL_FAIL, 60));
        assertThat(result.exitCode()).isEqualTo(1);
        JsonNode execution = executionOf(result);
        assertThat(execution.get("status").asText()).isEqualTo("eval_failed");
        assertThat(execution.get("eval_returncode").asInt()).isEqualTo(1);
        assertThat(execution.get("eval_output_text").asText()).contains("eval-says-no");
        assertThat(execution.get("eval_timed_out").asBoolean()).isFalse();
    }

    @Test
    void reviewStateRejectsRerun() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        assertThat(Cli.run(taskRun(l, "T-001", "code", EXEC_WRITE, EVAL_OK, 60)).exitCode())
                .isZero();
        Cli.Result rerun = Cli.run(taskRun(l, "T-001", "code", EXEC_WRITE, EVAL_OK, 60));
        assertThat(rerun.exitCode()).isEqualTo(1);
        assertThat(Cli.json(rerun).get("error").asText())
                .isEqualTo("task_in_review: 任务处于人工复核态，拒绝再次运行：T-001");
        Cli.Result show = Cli.run("workbench-task-show", "T-001", "--runtime-dir", l.rt.toString());
        JsonNode task = Cli.json(show).get("task");
        assertThat(task.get("executions")).hasSize(1);
        assertThat(task.get("state").asText()).isEqualTo("review");
    }

    @Test
    void selfReviewRejected() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        assertThat(Cli.run(taskRun(l, "T-001", "code", EXEC_WRITE, EVAL_OK, 60)).exitCode())
                .isZero();
        Cli.Result review = Cli.run("workbench-task-review", "T-001",
                "--runtime-dir", l.rt.toString(), "--reviewer", EXECUTOR,
                "--decision", "approve");
        assertThat(review.exitCode()).isEqualTo(1);
        assertThat(Cli.json(review).get("error").asText())
                .isEqualTo("self_review_rejected: 复核人与最近一次执行者是同一人（" + EXECUTOR
                        + "），执行者不能批准自己，记录未追加");
    }

    @Test
    void reviewAnchorsToExecutionId() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        assertThat(Cli.run(taskRun(l, "T-001", "code", EXEC_WRITE, EVAL_OK, 60)).exitCode())
                .isZero();
        Cli.Result approve = Cli.run("workbench-task-review", "T-001",
                "--runtime-dir", l.rt.toString(), "--reviewer", REVIEWER,
                "--decision", "approve", "--note", "合同测试复核");
        assertThat(approve.exitCode()).isZero();
        assertThat(Cli.json(approve).get("task_state").asText()).isEqualTo("accepted");
        // 接受后再执行：旧接受失效回 review（C8 版本锚定），复核记录只追加
        assertThat(Cli.run(taskRun(l, "T-001", "code", EXEC_WRITE, EVAL_OK, 60)).exitCode())
                .isZero();
        Cli.Result show = Cli.run("workbench-task-show", "T-001", "--runtime-dir", l.rt.toString());
        JsonNode task = Cli.json(show).get("task");
        assertThat(task.get("state").asText()).isEqualTo("review");
        assertThat(task.get("executions")).hasSize(2);
        assertThat(task.get("reviews")).hasSize(1);
    }

    @Test
    void taskShowSummarizesExecutionsAndReviews() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        assertThat(Cli.run(taskRun(l, "T-001", "code", EXEC_WRITE, EVAL_OK, 60)).exitCode())
                .isZero();
        assertThat(Cli.run("workbench-task-review", "T-001", "--runtime-dir", l.rt.toString(),
                "--reviewer", REVIEWER, "--decision", "approve").exitCode()).isZero();
        Cli.Result show = Cli.run("workbench-task-show", "T-001", "--runtime-dir", l.rt.toString());
        assertThat(show.exitCode()).isZero();
        JsonNode task = Cli.json(show).get("task");
        assertThat(task.get("task_id").asText()).isEqualTo("T-001");
        assertThat(task.get("state").asText()).isEqualTo("accepted");
        JsonNode execution = task.get("executions").get(0);
        assertThat(execution.get("changed_files").toString()).contains("delivery.csv");
        assertThat(execution.get("eval_command").toString()).contains("sh");
        assertThat(task.get("reviews").get(0).get("reviewer").asText()).isEqualTo(REVIEWER);
        assertThat(task.get("reviews").get(0).get("execution_id").asInt()).isEqualTo(1);
    }

    @Test
    void statusDigestCoversExecutions() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        assertThat(Cli.run(taskRun(l, "T-001", "code", EXEC_WRITE, EVAL_OK, 60)).exitCode())
                .isZero();
        try (var conn = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + l.rt.resolve("workbench.db"));
             var st = conn.createStatement()) {
            st.executeUpdate("UPDATE executions SET change_manifest = '[]'"
                    + " WHERE task_id = 'T-001'");
        }
        Cli.Result status = Cli.run("workbench-status", "--runtime-dir", l.rt.toString());
        assertThat(status.exitCode()).isEqualTo(1);
        assertThat(Cli.json(status).get("errors").toString())
                .contains("execution_digest_mismatch: 任务 T-001 执行 1 的 change_manifest 与摘要不一致");
    }

    @Test
    void rerunAfterFailureKeepsOldRecord() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-001");
        assertThat(Cli.run(taskRun(l, "T-001", "code", EXEC_FAIL, EVAL_OK, 60)).exitCode())
                .isEqualTo(1);
        assertThat(Cli.run(taskRun(l, "T-001", "code", EXEC_WRITE, EVAL_OK, 60)).exitCode())
                .isZero();
        Cli.Result show = Cli.run("workbench-task-show", "T-001", "--runtime-dir", l.rt.toString());
        JsonNode task = Cli.json(show).get("task");
        assertThat(task.get("executions")).hasSize(2);
        assertThat(task.get("executions").get(0).get("status").asText()).isEqualTo("failed");
        assertThat(task.get("executions").get(1).get("status").asText()).isEqualTo("completed");
    }

    // ---- 就位组（已随 L01-java 终态移植/文档护栏，commit 1 即绿）------------------

    @Test
    void prerequisiteRejectionFaces() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-A");
        Cli.Result missing = Cli.run("workbench-task-create", "--runtime-dir", l.rt.toString(),
                "--project-id", "T-PROJ", "--task-id", "T-X", "--requirement-id", "REQ-L04",
                "--request", "前置不存在", "--actor", OPERATOR,
                "--prerequisite-task", "T-NOPE");
        assertThat(missing.exitCode()).isEqualTo(1);
        assertThat(Cli.json(missing).get("error").asText())
                .isEqualTo("prerequisite_task_missing: 前置任务不存在：T-NOPE，任务未创建");
        Cli.Result notAccepted = Cli.run("workbench-task-create", "--runtime-dir", l.rt.toString(),
                "--project-id", "T-PROJ", "--task-id", "T-X", "--requirement-id", "REQ-L04",
                "--request", "前置未接受", "--actor", OPERATOR,
                "--prerequisite-task", "T-A");
        assertThat(notAccepted.exitCode()).isEqualTo(1);
        assertThat(Cli.json(notAccepted).get("error").asText())
                .isEqualTo("prerequisite_not_accepted: 前置任务未具名接受：T-A，任务未创建");
    }

    // ---- 红点组续（C9 正面半边依赖 V0 verify/复核能力，commit 1 红）---------------

    @Test
    void prerequisiteAcceptedAllowsTaskCreation() throws Exception {
        Ledger l = ledger();
        createTask(l, "T-A");
        // A 接受：verify 执行 + 具名复核（V0 能力）
        assertThat(Cli.run(taskRun(l, "T-A", "verify", null, EVAL_OK, 60)).exitCode()).isZero();
        assertThat(Cli.run("workbench-task-review", "T-A", "--runtime-dir", l.rt.toString(),
                "--reviewer", REVIEWER, "--decision", "approve").exitCode()).isZero();
        Cli.Result ok = Cli.run("workbench-task-create", "--runtime-dir", l.rt.toString(),
                "--project-id", "T-PROJ", "--task-id", "T-B", "--requirement-id", "REQ-L04",
                "--request", "前置已接受", "--actor", OPERATOR,
                "--prerequisite-task", "T-A");
        assertThat(ok.exitCode()).isZero();
        assertThat(Cli.json(ok).get("task").get("prerequisite_task_id").asText()).isEqualTo("T-A");
    }

    @Test
    void bootstrapContractParsesViaJavaCli() {
        Cli.Result result = Cli.run("spec", "lesson-04-submission/WB-L04-BOOTSTRAP.md");
        assertThat(result.exitCode()).as("建设合同结构闸门").isZero();
        assertThat(Cli.json(result).get("ok").asBoolean()).isTrue();
    }

    @Test
    void pythonEraSubmissionArtifactsInPlace() {
        for (String name : List.of("WB-L04-BOOTSTRAP.md", "A-evidence.md", "B-evidence.md",
                "handoff.md", "FDE_SPEC.md")) {
            Path artifact = name.equals("FDE_SPEC.md")
                    ? Cli.repoRoot().resolve("lesson-03-submission").resolve(name)
                    : Cli.repoRoot().resolve("lesson-04-submission").resolve(name);
            assertThat(artifact).as("Python 时代工件零重写护栏：%s", name).exists();
        }
    }
}
