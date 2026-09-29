package workbench.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import workbench.testsupport.Cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L01 合同测试：WORKBENCH_SPEC WB-01..WB-12 → 用例映射（Spec = docs/lessons/L01-工作台自举.md §2）。
 *
 * <p>映射表（验收项 → 测试名 → 实际操作 → 比较什么）——与 Python 载体
 * tests/test_l01_workbench_bootstrap.py 逐条同形（ADR-0006 附录 A2：行为语义逐条不变，
 * 变的只是载体）：
 *
 * <pre>
 * WB-01 → wb01InitPersistsAndCannotBeOverwritten   → 独立运行目录 init、新进程查询、另一 owner 再 init → 身份跨进程可查回；拒绝覆盖
 * WB-02 → wb02ProjectAndTaskWithSnapshots          → 登记项目、创建任务并附 spec/problem 文件 → 任务归属项目，快照与原文件内容一致
 * WB-03 → wb03TaskRequiresRegisteredProject        → 对未登记项目创建任务 → 失败且不产生无所属任务
 * WB-04 → wb04DuplicateTaskIdRejected              → 同编号任务提交不同内容 → 第二次失败，原任务与其记录不变
 * WB-05 → wb05SnapshotsSurviveSourceChanges        → 创建/导入后修改原文件 → 已保存快照保持当时内容
 * WB-06 → wb06MissingChainReported                 → 无证据任务要求完整性 → 报缺链，任务与已有记录保留
 * WB-07 → wb07SameCommandRedDiffGreenChain         → 同一命令红/Diff/绿按时间追加 → evidence_complete 且仍待人审
 * WB-08 → wb08DifferentCommandBreaksChain          → 绿改用不同命令 → 不构成同命令链，三条记录全保留
 * WB-09 → wb09OrderingAndStaleGreenRejected        → 绿早于红；绿后又失败 → 均判不完整
 * WB-10 → wb10QueriesNeverCreateData               → 查缺失任务 / 未初始化目录 → 明确失败，不偷偷建数据
 * WB-11 → wb11RecoveryAfterSupplement              → 补齐缺失记录后重查 → 可恢复，旧失败仍可查
 * WB-12 → wb12IsolatedRuntimeAndNoFlowerp          → 检查数据目录与字段 → 运行库独立，flowerp_connected 为 false
 * C6    → c6EvidenceAddRejectsNaiveTimeAndEmptyOutput → 缺时区时间 / 空输出 → 追加失败，旧记录保留
 * F1    → taskCreateAcceptsTitle                   → --title 等价 --request，正常入库
 * F2    → staleGreenGlobalAcrossCommands           → 绿后跨命令失败同样失权（上游全局锚定）
 * F3    → statusFlagsDigestMismatch                → status 复核存储内容与 SHA-256 摘要
 * F4    → unreadableSpecFileReportsCleanError      → 非 UTF-8 输入报 JSON 错误
 * F5    → foreignDbFileReportsUninitialized        → 空/伪 db 报尚未初始化
 * F6    → infraFailureReturnsJsonError             → 基础设施异常保持 JSON 契约
 * F7    → chainAnyMatchingRedWithDiffBetween       → 红的存在性判定（上游语义）
 * F12   → initNameOptionalAndRequirementIdFallback → 可选 --name；requirement_id 回退
 * F13   → sameOwnerReinitIdempotent                → 同 owner 幂等；跨 owner 消息属实
 * F14   → observedAtZSuffixAccepted                → 'Z' 后缀合法
 * —     → evidenceAddReturnsRecordId               → 返回记录编号（Spec §5）
 * </pre>
 *
 * <p>所有用例经真实 CLI 子进程（java -cp … workbench.cli.Main）驱动，不绕开入口直调内部函数。
 */
class WorkbenchBootstrapContractTest {

    private static final String SPEC_TEXT_V1 = "# 需求 V1\n\n查回任务当时依据的要求。\n";
    private static final String SPEC_TEXT_V2 = "# 需求 V2\n\n内容已被改写，不应影响已建任务。\n";
    private static final String PROBLEM_TEXT = "# 原始问题\n\n隔天继续开发，却找不到依据。\n";
    private static final String OUTPUT_TEXT_V1 = "10:01 运行输出：3 项失败\n";
    private static final String OUTPUT_TEXT_V2 = "输出文件事后被改写，不应影响已导入记录。\n";
    private static final String CHAIN_COMMAND =
            "python -X utf8 -m unittest discover -s tests -v -p 'test_l01_*.py'";

    @TempDir
    private Path tmp;

    private Path rt;

    @BeforeEach
    void setUp() {
        rt = tmp.resolve("rt").resolve("L01-workbench");
    }

    // ---- 组装辅助（镜像 Python 同名 helpers）----

    private Cli.Result initWorkbench() {
        return initWorkbench("tester-A");
    }

    private Cli.Result initWorkbench(String owner) {
        return Cli.run("workbench-init", "--runtime-dir", rt.toString(),
                "--name", "合同测试工作台", "--owner", owner);
    }

    private Cli.Result addProject() {
        return Cli.run("workbench-project-add", "--runtime-dir", rt.toString(),
                "--project-id", "PROJECT-A", "--name", "合同测试项目",
                "--path", ".", "--purpose", "合同验证");
    }

    private Cli.Result createTask() {
        return createTask("T-1", "第一版请求", null, null, "PROJECT-A");
    }

    private Cli.Result createTask(String taskId, String request, Path spec, Path problem,
                                  String projectId) {
        List<String> args = new ArrayList<>(List.of("workbench-task-create",
                "--runtime-dir", rt.toString(), "--project-id", projectId,
                "--task-id", taskId, "--requirement-id", taskId,
                "--request", request, "--actor", "tester-A"));
        if (spec != null) {
            args.addAll(List.of("--spec-file", spec.toString()));
        }
        if (problem != null) {
            args.addAll(List.of("--problem-file", problem.toString()));
        }
        return Cli.run(args.toArray(String[]::new));
    }

    private Cli.Result addEvidence(String taskId, String phase, String command,
                                   Path outputFile, int returncode, String observedAt) {
        return Cli.run("workbench-evidence-add", "--runtime-dir", rt.toString(),
                "--task-id", taskId, "--phase", phase, "--command-text", command,
                "--output-file", outputFile.toString(), "--returncode", String.valueOf(returncode),
                "--observed-at", observedAt);
    }

    private Cli.Result requireStatus(String taskId, String projectId, boolean requireEvidence) {
        List<String> args = new ArrayList<>(List.of("workbench-status", "--runtime-dir", rt.toString(),
                "--require-project", projectId, "--require-task", taskId));
        if (requireEvidence) {
            args.add("--require-red-green-evidence");
        }
        return Cli.run(args.toArray(String[]::new));
    }

    private Cli.Result status() {
        return Cli.run("workbench-status", "--runtime-dir", rt.toString());
    }

    private Path writeOutput(String name, String text) throws Exception {
        Path path = tmp.resolve(name);
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }

    private List<JsonNode> taskEvidence(Cli.Result proc, String taskId) {
        List<JsonNode> evidence = new ArrayList<>();
        JsonNode task = taskById(Cli.json(proc), taskId);
        if (task != null) {
            task.path("evidence").forEach(evidence::add);
        }
        return evidence;
    }

    private JsonNode taskById(JsonNode data, String taskId) {
        for (JsonNode project : data.path("projects")) {
            for (JsonNode task : project.path("tasks")) {
                if (taskId.equals(task.path("task_id").asText())) {
                    return task;
                }
            }
        }
        return null;
    }

    private Set<String> allTaskIds(JsonNode data) {
        Set<String> ids = new HashSet<>();
        for (JsonNode project : data.path("projects")) {
            for (JsonNode task : project.path("tasks")) {
                ids.add(task.path("task_id").asText());
            }
        }
        return ids;
    }

    // ---- WB-01..WB-12 ----

    @Test
    void wb01InitPersistsAndCannotBeOverwritten() {
        Cli.Result proc = initWorkbench();
        assertThat(proc.exitCode()).isEqualTo(0);
        JsonNode first = Cli.json(proc);
        assertThat(first.path("workbench").path("owner").asText()).isEqualTo("tester-A");
        assertThat(first.path("workbench").path("version").asText()).isEqualTo("V0.1");

        // 进程已结束：新进程查询同一运行目录，身份仍在
        Cli.Result again = status();
        assertThat(again.exitCode()).isEqualTo(0);
        assertThat(Cli.json(again).path("workbench").path("owner").asText()).isEqualTo("tester-A");
        assertThat(Cli.json(again).path("workbench").path("name").asText()).isEqualTo("合同测试工作台");

        // 另一 owner 再 init：拒绝，不覆盖
        Cli.Result clash = initWorkbench("tester-B");
        assertThat(clash.exitCode()).isNotEqualTo(0);
        assertThat(Cli.json(status()).path("workbench").path("owner").asText()).isEqualTo("tester-A");
    }

    @Test
    void wb02ProjectAndTaskWithSnapshots() throws Exception {
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        Path spec = tmp.resolve("spec.md");
        Files.writeString(spec, SPEC_TEXT_V1, StandardCharsets.UTF_8);
        Path problem = tmp.resolve("problem.md");
        Files.writeString(problem, PROBLEM_TEXT, StandardCharsets.UTF_8);
        Cli.Result proc = createTask("T-1", "第一版请求", spec, problem, "PROJECT-A");
        assertThat(proc.exitCode()).isEqualTo(0);

        // WB-02 只验收归属与快照，不要求证据链（修订：初版误带 --require-red-green-evidence）
        Cli.Result check = requireStatus("T-1", "PROJECT-A", false);
        assertThat(check.exitCode()).isEqualTo(0);
        JsonNode data = Cli.json(check);
        assertThat(data.path("ok").asBoolean()).isTrue();
        JsonNode task = taskById(data, "T-1");
        assertThat(task).isNotNull();
        assertThat(task.path("requirement_snapshot").asText()).isEqualTo(SPEC_TEXT_V1);
        assertThat(task.path("problem_snapshot").asText()).isEqualTo(PROBLEM_TEXT);
        assertThat(task.path("requirement_summary").asText()).isNotEmpty();
    }

    @Test
    void wb03TaskRequiresRegisteredProject() {
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        Cli.Result proc = createTask("GHOST-T1", "第一版请求", null, null, "GHOST-PROJECT");
        assertThat(proc.exitCode()).isNotEqualTo(0);
        assertThat(allTaskIds(Cli.json(status()))).doesNotContain("GHOST-T1");
    }

    @Test
    void wb04DuplicateTaskIdRejected() {
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        assertThat(createTask().exitCode()).isEqualTo(0);
        Cli.Result clash = createTask("T-1", "换掉第一版的不同内容", null, null, "PROJECT-A");
        assertThat(clash.exitCode()).isNotEqualTo(0);
        JsonNode task = taskById(Cli.json(status()), "T-1");
        assertThat(task).isNotNull();
        assertThat(task.path("request").asText()).isEqualTo("第一版请求");
    }

    @Test
    void wb05SnapshotsSurviveSourceChanges() throws Exception {
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        Path spec = tmp.resolve("spec.md");
        Files.writeString(spec, SPEC_TEXT_V1, StandardCharsets.UTF_8);
        assertThat(createTask("T-1", "第一版请求", spec, null, "PROJECT-A").exitCode()).isEqualTo(0);
        // 原需求文件事后变化
        Files.writeString(spec, SPEC_TEXT_V2, StandardCharsets.UTF_8);

        Path output = writeOutput("out.txt", OUTPUT_TEXT_V1);
        assertThat(addEvidence("T-1", "observation", "echo v1", output, 0,
                "2026-09-25T10:00:00+08:00").exitCode()).isEqualTo(0);
        // 原输出文件事后变化
        Files.writeString(output, OUTPUT_TEXT_V2, StandardCharsets.UTF_8);

        JsonNode task = taskById(Cli.json(status()), "T-1");
        assertThat(task).isNotNull();
        assertThat(task.path("requirement_snapshot").asText()).isEqualTo(SPEC_TEXT_V1);
        List<JsonNode> evidence = taskEvidence(status(), "T-1");
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).path("output_text").asText()).isEqualTo(OUTPUT_TEXT_V1);
    }

    @Test
    void wb06MissingChainReported() {
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        assertThat(createTask().exitCode()).isEqualTo(0);
        Cli.Result proc = requireStatus("T-1", "PROJECT-A", true);
        assertThat(proc.exitCode()).isNotEqualTo(0);
        JsonNode data = Cli.json(proc);
        assertThat(data.path("evidence_complete").asBoolean()).isFalse();
        assertThat(joinErrors(data)).contains("same_command_red_diff_green_missing");
        // 任务与项目仍保留
        Cli.Result plain = status();
        assertThat(Cli.json(plain).path("ok").asBoolean()).isTrue();
        assertThat(allTaskIds(Cli.json(plain))).contains("T-1");
    }

    @Test
    void wb07SameCommandRedDiffGreenChain() throws Exception {
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        assertThat(createTask().exitCode()).isEqualTo(0);
        Path red = writeOutput("red.txt", "red output\n");
        Path diff = writeOutput("diff.txt", "diff output\n");
        Path green = writeOutput("green.txt", "green output\n");
        assertThat(addEvidence("T-1", "red", CHAIN_COMMAND, red, 1,
                "2026-09-25T10:00:01+08:00").exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "diff", CHAIN_COMMAND, diff, 0,
                "2026-09-25T10:00:02+08:00").exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "green", CHAIN_COMMAND, green, 0,
                "2026-09-25T10:00:03+08:00").exitCode()).isEqualTo(0);
        Cli.Result proc = requireStatus("T-1", "PROJECT-A", true);
        assertThat(proc.exitCode()).isEqualTo(0);
        JsonNode data = Cli.json(proc);
        assertThat(data.path("ok").asBoolean()).isTrue();
        assertThat(data.path("evidence_complete").asBoolean()).isTrue();
        assertThat(data.path("acceptance").asText()).isEqualTo("pending_human_review");
        assertThat(data.path("flowerp_connected").asBoolean()).isFalse();
    }

    @Test
    void wb07bDiffRecordMayUseDiffCommand() throws Exception {
        // 上游手册 §5：Diff 记录的 command 本就是 git diff；同命令约束只适用红→绿对
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        assertThat(createTask().exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "red", CHAIN_COMMAND,
                writeOutput("r.txt", "r\n"), 1, "2026-09-25T10:00:01+08:00").exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "diff", "git diff -- workbench tests",
                writeOutput("d.txt", "d\n"), 0, "2026-09-25T10:00:02+08:00").exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "green", CHAIN_COMMAND,
                writeOutput("g.txt", "g\n"), 0, "2026-09-25T10:00:03+08:00").exitCode()).isEqualTo(0);
        Cli.Result proc = requireStatus("T-1", "PROJECT-A", true);
        assertThat(proc.exitCode()).isEqualTo(0);
        JsonNode data = Cli.json(proc);
        assertThat(data.path("evidence_complete").asBoolean()).isTrue();
        assertThat(data.path("acceptance").asText()).isEqualTo("pending_human_review");
    }

    @Test
    void wb08DifferentCommandBreaksChain() throws Exception {
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        assertThat(createTask().exitCode()).isEqualTo(0);
        String otherCommand = CHAIN_COMMAND + " --broken-variant";
        assertThat(addEvidence("T-1", "red", CHAIN_COMMAND,
                writeOutput("r.txt", "r\n"), 1, "2026-09-25T10:00:01+08:00").exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "diff", CHAIN_COMMAND,
                writeOutput("d.txt", "d\n"), 0, "2026-09-25T10:00:02+08:00").exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "green", otherCommand,
                writeOutput("g.txt", "g\n"), 0, "2026-09-25T10:00:03+08:00").exitCode()).isEqualTo(0);
        Cli.Result proc = requireStatus("T-1", "PROJECT-A", true);
        assertThat(proc.exitCode()).isNotEqualTo(0);
        JsonNode data = Cli.json(proc);
        assertThat(data.path("evidence_complete").asBoolean()).isFalse();
        assertThat(joinErrors(data)).contains("same_command_red_diff_green_missing");
        // 三条记录全保留，不因判缺而删除
        assertThat(taskEvidence(status(), "T-1")).hasSize(3);
    }

    @Test
    void wb09OrderingAndStaleGreenRejected() throws Exception {
        // 情形 A：绿灯时间早于红灯
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        assertThat(createTask().exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "red", CHAIN_COMMAND,
                writeOutput("a-r.txt", "r\n"), 1, "2026-09-25T10:00:02+08:00").exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "diff", CHAIN_COMMAND,
                writeOutput("a-d.txt", "d\n"), 0, "2026-09-25T10:00:03+08:00").exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "green", CHAIN_COMMAND,
                writeOutput("a-g.txt", "g\n"), 0, "2026-09-25T10:00:01+08:00").exitCode()).isEqualTo(0);
        assertThat(requireStatus("T-1", "PROJECT-A", true).exitCode()).isNotEqualTo(0);

        // 情形 B：成功后又出现失败，旧绿灯不再说明当前完整
        rt = tmp.resolve("rt-b");
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        assertThat(createTask().exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "red", CHAIN_COMMAND,
                writeOutput("b-r.txt", "r\n"), 1, "2026-09-25T11:00:01+08:00").exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "diff", CHAIN_COMMAND,
                writeOutput("b-d.txt", "d\n"), 0, "2026-09-25T11:00:02+08:00").exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "green", CHAIN_COMMAND,
                writeOutput("b-g.txt", "g\n"), 0, "2026-09-25T11:00:03+08:00").exitCode()).isEqualTo(0);
        assertThat(requireStatus("T-1", "PROJECT-A", true).exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "red", CHAIN_COMMAND,
                writeOutput("b-r2.txt", "r2\n"), 1, "2026-09-25T11:00:04+08:00").exitCode()).isEqualTo(0);
        Cli.Result proc = requireStatus("T-1", "PROJECT-A", true);
        assertThat(proc.exitCode()).isNotEqualTo(0);
        assertThat(Cli.json(proc).path("evidence_complete").asBoolean()).isFalse();
    }

    @Test
    void wb10QueriesNeverCreateData() throws Exception {
        // 未初始化目录：明确失败，且不偷偷建库
        Cli.Result proc = requireStatus("T-1", "PROJECT-A", true);
        assertThat(proc.exitCode()).isNotEqualTo(0);
        assertThat(Cli.json(proc).path("error").asText()).contains("工作台尚未初始化");
        assertThat(rt.resolve("workbench.db")).doesNotExist();

        // 初始化后查不存在的任务：报缺失，不改写其他任务
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        assertThat(createTask().exitCode()).isEqualTo(0);
        Cli.Result missing = requireStatus("CASE-WB-L01-MISSING", "PROJECT-A", true);
        assertThat(missing.exitCode()).isNotEqualTo(0);
        assertThat(Cli.json(missing).path("error").asText()).contains("required_task_missing");
        assertThat(allTaskIds(Cli.json(status()))).containsExactly("T-1");
    }

    @Test
    void wb11RecoveryAfterSupplement() throws Exception {
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        assertThat(createTask().exitCode()).isEqualTo(0);
        Path redOut = writeOutput("r.txt", "red output\n");
        assertThat(addEvidence("T-1", "red", CHAIN_COMMAND, redOut, 1,
                "2026-09-25T10:00:01+08:00").exitCode()).isEqualTo(0);
        assertThat(requireStatus("T-1", "PROJECT-A", true).exitCode()).isNotEqualTo(0);
        // 补齐 Diff 与绿
        assertThat(addEvidence("T-1", "diff", CHAIN_COMMAND,
                writeOutput("d.txt", "d\n"), 0, "2026-09-25T10:00:02+08:00").exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "green", CHAIN_COMMAND,
                writeOutput("g.txt", "g\n"), 0, "2026-09-25T10:00:03+08:00").exitCode()).isEqualTo(0);
        assertThat(requireStatus("T-1", "PROJECT-A", true).exitCode()).isEqualTo(0);
        // 旧失败仍可查，未被删除来恢复
        List<JsonNode> evidence = taskEvidence(status(), "T-1");
        List<JsonNode> reds = evidence.stream().filter(item -> "red".equals(item.path("phase").asText())).toList();
        assertThat(reds).hasSize(1);
        assertThat(reds.get(0).path("returncode").asInt()).isEqualTo(1);
    }

    @Test
    void wb12IsolatedRuntimeAndNoFlowerp() throws Exception {
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        assertThat(createTask().exitCode()).isEqualTo(0);
        JsonNode data = Cli.json(status());
        assertThat(data.path("flowerp_connected").asBoolean()).isFalse();
        assertThat(rt.resolve("workbench.db")).exists();
        // 运行数据只落指定目录，不外溢仓库根
        try (var stream = Files.list(tmp.resolve("rt"))) {
            Set<String> siblings = new HashSet<>();
            stream.forEach(path -> siblings.add(path.getFileName().toString()));
            assertThat(siblings).containsExactly("L01-workbench");
        }
    }

    // ---- C6 记录质量 ----

    @Test
    void c6EvidenceAddRejectsNaiveTimeAndEmptyOutput() throws Exception {
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        assertThat(createTask().exitCode()).isEqualTo(0);
        Cli.Result naive = addEvidence("T-1", "observation", "echo x",
                writeOutput("n.txt", "x\n"), 0, "2026-09-25T10:00:00");  // 缺时区
        assertThat(naive.exitCode()).isNotEqualTo(0);
        Path empty = writeOutput("empty.txt", "");
        Cli.Result missingOut = addEvidence("T-1", "observation", "echo y", empty, 0,
                "2026-09-25T10:00:01+08:00");
        assertThat(missingOut.exitCode()).isNotEqualTo(0);
        // 失败后原任务仍无被污染的记录
        assertThat(taskEvidence(status(), "T-1")).isEmpty();
    }

    // ---- 复查修复轮（code-review @master...lesson-01 发现，2026-09-25）----

    @Test
    void taskCreateAcceptsTitle() {
        // F1：--title 与 --request 等价（上游 create_task 的 title 即任务请求）
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        Cli.Result proc = Cli.run("workbench-task-create", "--runtime-dir", rt.toString(),
                "--project-id", "PROJECT-A", "--task-id", "T-TITLE",
                "--requirement-id", "T-TITLE", "--title", "标题形式的请求", "--actor", "tester-A");
        assertThat(proc.exitCode()).isEqualTo(0);
        JsonNode task = taskById(Cli.json(status()), "T-TITLE");
        assertThat(task).isNotNull();
        assertThat(task.path("request").asText()).isEqualTo("标题形式的请求");
    }

    @Test
    void staleGreenGlobalAcrossCommands() throws Exception {
        // F2：绿后任一 red/diff/green 相失败（跨命令同罪）→ 链失权（上游全局锚定）
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        assertThat(createTask().exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "red", CHAIN_COMMAND,
                writeOutput("s-r.txt", "r\n"), 1, "2026-09-25T10:00:01+08:00").exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "diff", CHAIN_COMMAND,
                writeOutput("s-d.txt", "d\n"), 0, "2026-09-25T10:00:02+08:00").exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "green", CHAIN_COMMAND,
                writeOutput("s-g.txt", "g\n"), 0, "2026-09-25T10:00:03+08:00").exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "red", CHAIN_COMMAND + " --variant-b",
                writeOutput("s-r2.txt", "r2\n"), 1, "2026-09-25T10:00:04+08:00").exitCode()).isEqualTo(0);
        Cli.Result proc = requireStatus("T-1", "PROJECT-A", true);
        assertThat(proc.exitCode()).isNotEqualTo(0);
        assertThat(Cli.json(proc).path("evidence_complete").asBoolean()).isFalse();
    }

    @Test
    void chainAnyMatchingRedWithDiffBetween() throws Exception {
        // F7：上游对红是存在性判定——早期红 + 其后 Diff + 绿也算完整（不应只看最新红）
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        assertThat(createTask().exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "red", CHAIN_COMMAND,
                writeOutput("e-r1.txt", "r1\n"), 1, "2026-09-25T10:00:01+08:00").exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "diff", CHAIN_COMMAND,
                writeOutput("e-d.txt", "d\n"), 0, "2026-09-25T10:00:02+08:00").exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "red", CHAIN_COMMAND,
                writeOutput("e-r2.txt", "r2\n"), 1, "2026-09-25T10:00:03+08:00").exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "green", CHAIN_COMMAND,
                writeOutput("e-g.txt", "g\n"), 0, "2026-09-25T10:00:04+08:00").exitCode()).isEqualTo(0);
        Cli.Result proc = requireStatus("T-1", "PROJECT-A", true);
        assertThat(proc.exitCode()).isEqualTo(0);
        assertThat(Cli.json(proc).path("evidence_complete").asBoolean()).isTrue();
    }

    @Test
    void statusFlagsDigestMismatch() throws Exception {
        // F3：status 复核存储内容与摘要（上游 spec/output_digest_mismatch）
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        Path spec = tmp.resolve("spec.md");
        Files.writeString(spec, SPEC_TEXT_V1, StandardCharsets.UTF_8);
        assertThat(createTask("T-1", "第一版请求", spec, null, "PROJECT-A").exitCode()).isEqualTo(0);
        assertThat(addEvidence("T-1", "observation", "echo z",
                writeOutput("z.txt", "z\n"), 0, "2026-09-25T10:00:00+08:00").exitCode()).isEqualTo(0);
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + rt.resolve("workbench.db"));
             var st = conn.createStatement()) {
            st.executeUpdate("UPDATE evidence SET output_text = '篡改后的内容' WHERE task_id = 'T-1'");
            st.executeUpdate("UPDATE tasks SET requirement_snapshot = '被改写的需求' WHERE task_id = 'T-1'");
        }
        Cli.Result plain = status();
        assertThat(plain.exitCode()).isNotEqualTo(0);
        assertThat(plain.stdout()).contains("output_digest_mismatch");
        assertThat(plain.stdout()).contains("spec_digest_mismatch");
    }

    @Test
    void unreadableSpecFileReportsCleanError() throws Exception {
        // F4：非 UTF-8 文件 → JSON 错误，不裸 traceback
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        Path bad = tmp.resolve("bad-spec.md");
        Files.write(bad, new byte[] {(byte) 0xff, (byte) 0xfe, 0x00, 0x62, 0x69, 0x6e, 0x61, 0x72, 0x79});
        Cli.Result proc = createTask("T-1", "第一版请求", bad, null, "PROJECT-A");
        assertThat(proc.exitCode()).isNotEqualTo(0);
        assertThat(Cli.json(proc).path("error").asText()).contains("需求文件不可读取");
        assertThat(taskEvidence(status(), "T-1")).isEmpty();
    }

    @Test
    void foreignDbFileReportsUninitialized() throws Exception {
        // F5：空文件/非 sqlite 文件占据 workbench.db → 尚未初始化，不裸 traceback
        Files.createDirectories(rt);
        Files.write(rt.resolve("workbench.db"), new byte[0]);
        Cli.Result proc = status();
        assertThat(proc.exitCode()).isNotEqualTo(0);
        assertThat(Cli.json(proc).path("error").asText()).contains("工作台尚未初始化");
        Files.writeString(rt.resolve("workbench.db"), "not a sqlite database at all........",
                StandardCharsets.UTF_8);
        proc = status();
        assertThat(proc.exitCode()).isNotEqualTo(0);
        assertThat(Cli.json(proc).path("error").asText()).contains("工作台尚未初始化");
    }

    @Test
    void infraFailureReturnsJsonError() throws Exception {
        // F6：基础设施异常保持 JSON 错误契约（不裸 traceback）
        Path blocker = tmp.resolve("not-a-dir");
        Files.writeString(blocker, "占用 runtime-dir 路径的普通文件\n", StandardCharsets.UTF_8);
        Cli.Result proc = Cli.run("workbench-init", "--runtime-dir", blocker.toString(),
                "--name", "X", "--owner", "tester-A");
        assertThat(proc.exitCode()).isNotEqualTo(0);
        JsonNode data = Cli.json(proc);
        assertThat(data.path("ok").asBoolean()).isFalse();
        assertThat(data.path("error").asText()).isNotEmpty();
    }

    @Test
    void initNameOptionalAndRequirementIdFallback() {
        // F12：--name 可选（Spec §5）；--requirement-id 缺省回退任务编号（上游同形）
        Cli.Result proc = Cli.run("workbench-init", "--runtime-dir", rt.toString(), "--owner", "tester-A");
        assertThat(proc.exitCode()).isEqualTo(0);
        assertThat(Cli.json(proc).path("workbench").path("name").asText())
                .isEqualTo("个人 AI 研发工作台");
        assertThat(addProject().exitCode()).isEqualTo(0);
        proc = Cli.run("workbench-task-create", "--runtime-dir", rt.toString(),
                "--project-id", "PROJECT-A", "--task-id", "T-FALLBACK",
                "--request", "无显式需求编号", "--actor", "tester-A");
        assertThat(proc.exitCode()).isEqualTo(0);
        JsonNode task = taskById(Cli.json(status()), "T-FALLBACK");
        assertThat(task).isNotNull();
        assertThat(task.path("requirement_id").asText()).isEqualTo("T-FALLBACK");
    }

    @Test
    void sameOwnerReinitIdempotent() {
        // F13：同 owner 重复 init 幂等返回既有身份；跨 owner 拒绝且消息属实
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        Cli.Result again = Cli.run("workbench-init", "--runtime-dir", rt.toString(),
                "--name", "换一个名字也不生效", "--owner", "tester-A");
        assertThat(again.exitCode()).isEqualTo(0);
        assertThat(Cli.json(status()).path("workbench").path("name").asText())
                .isEqualTo("合同测试工作台");  // 保持原身份
        Cli.Result clash = initWorkbench("tester-B");
        assertThat(clash.exitCode()).isNotEqualTo(0);
        assertThat(Cli.json(clash).path("error").asText()).contains("另一个所有者");
    }

    @Test
    void observedAtZSuffixAccepted() throws Exception {
        // F14：RFC 3339 'Z' 后缀是合法时区时间
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        assertThat(createTask().exitCode()).isEqualTo(0);
        Cli.Result proc = addEvidence("T-1", "observation", "echo utc",
                writeOutput("u.txt", "utc\n"), 0, "2026-09-25T10:00:00Z");
        assertThat(proc.exitCode()).isEqualTo(0);
    }

    @Test
    void evidenceAddReturnsRecordId() throws Exception {
        // Spec §5：workbench-evidence-add 返回记录编号与输出摘要
        assertThat(initWorkbench().exitCode()).isEqualTo(0);
        assertThat(addProject().exitCode()).isEqualTo(0);
        assertThat(createTask().exitCode()).isEqualTo(0);
        Cli.Result proc = addEvidence("T-1", "observation", "echo id",
                writeOutput("i.txt", "i\n"), 0, "2026-09-25T10:00:00+08:00");
        assertThat(proc.exitCode()).isEqualTo(0);
        JsonNode record = Cli.json(proc).path("record");
        assertThat(record.path("record_id").isInt()).isTrue();
        assertThat(record.path("output_sha256").asText()).hasSize(64);
    }

    private static String joinErrors(JsonNode data) {
        StringBuilder joined = new StringBuilder();
        for (JsonNode error : data.path("errors")) {
            joined.append(error.asText()).append(' ');
        }
        return joined.toString();
    }
}
