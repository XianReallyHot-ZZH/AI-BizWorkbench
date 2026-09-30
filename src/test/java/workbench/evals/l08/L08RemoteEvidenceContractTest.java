package workbench.evals.l08;

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
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L08 工具面合同测试：讲义 docs/lessons/L08-远程复验与证据信封.md §2 C1..C8 → 用例映射
 * （第二个无 Python 先例的讲；映射源 = 讲义 §1.4 宿主映射 + 上游
 * vendors/CodexFDE/workbench/ci_evidence.py 与 envelope_boundary_lab 六模式 +
 * injected-atomic-defect.diff，只读对照）。独立预期源 = 上游源码词面与 diff 逐字
 * （注入行 / 锚点行 / 信封错误消息），不抄实现返回值。
 *
 * <p>红点组（目标能力缺失，commit 1 三面红，讲义 §3 步骤 2；子进程缝无编译绑定，
 * L04–L07 同法）：ci-evidence 未注册 → rc 2 invalid choice；SalesChecks/InjectDefect
 * main 缺席 → rc 1 class not found；工作流文件与 CI_GATE_SPEC.md 缺失（文本直读缝）。
 * 真树业务红绿（缺陷注入/修复）、CI 三态真实 Run 不在 mvn 面内，属候选期证据账
 * （讲义 §3 步骤 4–8；mvn 面不冒充真实运行）。八登记项内容经候选期 {@code SalesChecks
 * --target} 真跑验证，本类只锁 argv 与信封行为合同。
 *
 * <pre>
 * 红点组（commit 1 红）：
 * C3/C4 注册面  → 各 ciEvidence* 用例（未注册 rc 2 invalid choice 即红）
 * C4 missing    → ciEvidenceRejectsMissingReport      → rc 1 + stderr「Harness 报告不存在」
 * C4 identity   → ciEvidenceRejectsMissingIdentity    → 隔离空环境（CI 父进程自带
 *                 GITHUB_* 须清除——runMainWithIsolatedEnv）→ rc 1 + stderr
 *                 「Evidence Envelope 缺少 GITHUB_SHA 或 GITHUB_RUN_ID」（上游词面逐字）
 * C4 malformed  → ciEvidenceRejectsMalformedReport    → 坏 JSON → rc 1 + stderr
 *                 （上游裸 traceback rc 1 同码；Java 收敛为 stderr 消息，差异如实）
 * C4 envelope   → ciEvidenceWritesEnvelopeForValidReport → rc 0 + stdout 单行 JSON 八字段
 *                 + output 落盘 + report_sha256 独立重算对账（tautology 反例：测试自算哈希）
 * C4 tampered   → ciEvidenceEnvelopeHashDetectsTampering → 生成后改报告字节 → 信封哈希
 *                 与新文件失配（篡改可检）
 * C4 empty      → ciEvidenceEmptyReportYieldsEmptyDecision → 报告 {} → rc 0、decision
 *                 与 suite 如实为空（信封不做报告语义校验——上游教学点）
 * C4 stale      → ciEvidenceLeavesStaleOutputUntouchedOnFailure → 旧 output 在命令失败后
 *                 原样保留（旧输出不删不覆盖＝不冒充本次证据）
 * C4 argv       → ciEvidenceRequiresBothArgs          → 缺参 → rc 2（用法错误）
 * C2 工具面     → salesChecksRequiresTarget / salesChecksRejectsTargetWithoutFlowerp
 *                 → rc 2 + 词面（l06/l07 同款；main 缺席 rc 1 即红）
 * C2 注入       → injectDefectRequiresSource          → rc 2（main 缺席 rc 1 即红）
 * C2 注入       → injectDefectInjectsSingleTeachingLine → dest 树 sales.py 含上游 diff
 *                 逐字注入行、恰多一行、位置＝锚点行之后；.git/__pycache__ 不入拷贝
 * C2 注入       → injectDefectRejectsAmbiguousAnchor  → 锚点行两次 → 拒绝（唯一性校验）
 * C2 注入       → injectDefectRejectsAlreadyInjected  → 已含注入行再注 → 拒绝（幂等护栏）
 * C3 工作流     → ciWorkflowIsPresentAndFinalState    → .github/workflows/l08-eval.yml：
 *                 submodules recursive / mvn / SalesChecks / ci-evidence /
 *                 if-no-files-found: error 在场，continue-on-error 不在场（终态无吞失败）
 * C3 门规格     → ciGateSpecIsPresent                 → CI_GATE_SPEC.md 在场非空
 * </pre>
 */
class L08RemoteEvidenceContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CHECKS_MAIN = "workbench.evals.l08.SalesChecks";
    private static final String INJECT_MAIN = "workbench.evals.l08.InjectDefect";
    private static final String WORKFLOW_FILE = ".github/workflows/l08-eval.yml";
    private static final String GATE_SPEC_FILE = "CI_GATE_SPEC.md";

    /** 上游 injected-atomic-defect.diff 逐字（注入行与其锚点行——独立预期源，不抄实现）。 */
    private static final String INJECTED_LINE =
            "                conn.commit()  # L08 teaching defect: commit each line too early";
    private static final String ANCHOR_LINE =
            "                conn.execute(\"UPDATE sales_document_lines SET reserved_quantity=reserved_quantity+? WHERE id=?\", (quantity, line[\"id\"]))";

    /** 最小合法统一 Harness 报告（schema 1.0 面；测试手写，不调产线——独立源）。 */
    private static final String VALID_REPORT = "{\"schema_version\":\"1.0\",\"suite\":\"blocking\","
            + "\"requested_cases\":[\"stock_never_negative\"],"
            + "\"summary\":{\"total\":1,\"passed\":1,\"blocking_failed\":0,\"observing_failed\":0,\"decision\":\"pass\"},"
            + "\"results\":[]}";

    @TempDir
    Path temp;

    // ---- ci-evidence（REGISTRY 命令，C3/C4；未注册 rc 2 即红） ----------------------------

    @Test
    void ciEvidenceRequiresBothArgs() {
        Cli.Result result = Cli.run("ci-evidence");
        assertThat(result.exitCode()).as("缺参是用法错误 rc 2").isEqualTo(2);
        assertThat(result.stderr()).contains("--report").contains("--output");
    }

    @Test
    void ciEvidenceRejectsMissingReport() throws Exception {
        Path report = temp.resolve("absent-report.json");
        Cli.Result result = Cli.run("ci-evidence",
                "--report", report.toString(), "--output", temp.resolve("envelope.json").toString());
        assertThat(result.exitCode()).as("报告缺失拒绝 rc 1（上游 FileNotFoundError 同码）").isEqualTo(1);
        assertThat(result.stderr()).contains("Harness 报告不存在");
        assertThat(temp.resolve("envelope.json")).as("失败不写输出文件").doesNotExist();
    }

    @Test
    void ciEvidenceRejectsMissingIdentity() throws Exception {
        Path report = temp.resolve("report.json");
        Files.writeString(report, VALID_REPORT, StandardCharsets.UTF_8);
        // 隔离空环境：CI 父进程自带 GITHUB_* 变量，追加式注入无法构造缺失身份（ Cli 扩展注记）
        Cli.Result result = Cli.runMainWithIsolatedEnv("workbench.cli.Main", Map.of(),
                "ci-evidence", "--report", report.toString(),
                "--output", temp.resolve("envelope.json").toString());
        assertThat(result.exitCode()).as("缺运行身份拒绝 rc 1（上游 SystemExit 同码）").isEqualTo(1);
        assertThat(result.stderr())
                .contains("Evidence Envelope 缺少 GITHUB_SHA 或 GITHUB_RUN_ID");
    }

    @Test
    void ciEvidenceRejectsMalformedReport() throws Exception {
        Path report = temp.resolve("report.json");
        Files.writeString(report, "{bad", StandardCharsets.UTF_8);
        Cli.Result result = Cli.runMainWithIsolatedEnv("workbench.cli.Main",
                Map.of("GITHUB_SHA", "abc123", "GITHUB_RUN_ID", "42"),
                "ci-evidence", "--report", report.toString(),
                "--output", temp.resolve("envelope.json").toString());
        assertThat(result.exitCode()).as("畸形 JSON 拒绝 rc 1（上游裸异常同码，词面收敛如实）").isEqualTo(1);
        assertThat(result.stderr()).isNotEmpty();
        assertThat(temp.resolve("envelope.json")).doesNotExist();
    }

    @Test
    void ciEvidenceWritesEnvelopeForValidReport() throws Exception {
        Path report = temp.resolve("report.json");
        Files.writeString(report, VALID_REPORT, StandardCharsets.UTF_8);
        Path output = temp.resolve("envelope.json");
        Map<String, String> identity = Map.of(
                "GITHUB_SHA", "0123456789abcdef", "GITHUB_RUN_ID", "1234567890",
                "GITHUB_WORKFLOW", "L08 Eval Gate", "RUNNER_OS", "Linux");
        Cli.Result result = Cli.runMainWithIsolatedEnv("workbench.cli.Main", identity,
                "ci-evidence", "--report", report.toString(), "--output", output.toString());
        assertThat(result.exitCode()).as("合法面 rc 0").isZero();
        JsonNode envelope = Cli.json(result);
        java.util.List<String> fields = new java.util.ArrayList<>();
        envelope.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).as("八字段身份面").containsExactlyInAnyOrder(
                "commit_sha", "run_id", "workflow", "runner_os", "java",
                "suite", "report_sha256", "report_decision");
        assertThat(envelope.path("commit_sha").asText()).isEqualTo("0123456789abcdef");
        assertThat(envelope.path("run_id").asText()).isEqualTo("1234567890");
        assertThat(envelope.path("workflow").asText()).isEqualTo("L08 Eval Gate");
        assertThat(envelope.path("runner_os").asText()).isEqualTo("Linux");
        assertThat(envelope.path("java").asText()).as("运行时版本如实记录（载体 Java，不硬造解释器字段）").isNotEmpty();
        assertThat(envelope.path("suite").asText()).isEqualTo("blocking");
        assertThat(envelope.path("report_decision").asText()).isEqualTo("pass");
        // 独立重算对账（不调产线哈希代码——tautology 反例）
        assertThat(envelope.path("report_sha256").asText()).isEqualTo(sha256(Files.readAllBytes(report)));
        assertThat(output).as("输出文件落盘").isRegularFile();
        assertThat(MAPPER.readTree(output.toFile())).as("输出与 stdout 同一 JSON").isEqualTo(envelope);
    }

    @Test
    void ciEvidenceEnvelopeHashDetectsTampering() throws Exception {
        Path report = temp.resolve("report.json");
        Files.writeString(report, VALID_REPORT, StandardCharsets.UTF_8);
        Path output = temp.resolve("envelope.json");
        Cli.Result result = Cli.runMainWithIsolatedEnv("workbench.cli.Main",
                Map.of("GITHUB_SHA", "abc", "GITHUB_RUN_ID", "7"),
                "ci-evidence", "--report", report.toString(), "--output", output.toString());
        assertThat(result.exitCode()).isZero();
        String frozenHash = Cli.json(result).path("report_sha256").asText();
        Files.writeString(report, VALID_REPORT.replace("\"pass\"", "\"block\""), StandardCharsets.UTF_8);
        assertThat(frozenHash).as("报告字节被改后信封哈希失配可检")
                .isNotEqualTo(sha256(Files.readAllBytes(report)));
    }

    @Test
    void ciEvidenceEmptyReportYieldsEmptyDecision() throws Exception {
        Path report = temp.resolve("report.json");
        Files.writeString(report, "{}", StandardCharsets.UTF_8);
        Cli.Result result = Cli.runMainWithIsolatedEnv("workbench.cli.Main",
                Map.of("GITHUB_SHA", "abc", "GITHUB_RUN_ID", "7"),
                "ci-evidence", "--report", report.toString(),
                "--output", temp.resolve("envelope.json").toString());
        assertThat(result.exitCode()).as("空对象仍生成信封 rc 0（上游同形：信封不做报告语义校验）").isZero();
        JsonNode envelope = Cli.json(result);
        assertThat(envelope.hasNonNull("report_decision")).as("决策如实为空").isFalse();
        assertThat(envelope.path("suite").asText()).isEmpty();
    }

    @Test
    void ciEvidenceLeavesStaleOutputUntouchedOnFailure() throws Exception {
        Path output = temp.resolve("envelope.json");
        String stale = "{\"report_decision\":\"pass\",\"run_id\":\"OLD-RUN\"}";
        Files.writeString(output, stale, StandardCharsets.UTF_8);
        Cli.Result result = Cli.run("ci-evidence",
                "--report", temp.resolve("absent.json").toString(), "--output", output.toString());
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(Files.readString(output, StandardCharsets.UTF_8))
                .as("命令失败后旧输出原样保留（文件存在不等于本次生成）").isEqualTo(stale);
    }

    // ---- SalesChecks argv 合同（C2；main 缺席 rc 1 即红） --------------------------------

    @Test
    void salesChecksRequiresTarget() {
        Cli.Result result = Cli.runMain(CHECKS_MAIN);
        assertThat(result.exitCode()).as("缺 --target 是用法错误 rc 2").isEqualTo(2);
        assertThat(result.stderr()).contains("--target");
    }

    @Test
    void salesChecksRejectsTargetWithoutFlowerp() throws Exception {
        Path plain = temp.resolve("plain-target");
        Files.createDirectories(plain);
        Cli.Result result = Cli.runMain(CHECKS_MAIN, "--target", plain.toString());
        assertThat(result.exitCode()).as("--target 非客户树拒绝 rc 2").isEqualTo(2);
        assertThat(result.stderr()).contains("--target 下没有 flowerp/");
    }

    // ---- InjectDefect argv 与注入合同（C2；main 缺席 rc 1 即红） --------------------------

    @Test
    void injectDefectRequiresSource() {
        Cli.Result result = Cli.runMain(INJECT_MAIN);
        assertThat(result.exitCode()).as("缺 --source 是用法错误 rc 2").isEqualTo(2);
        assertThat(result.stderr()).contains("--source");
    }

    @Test
    void injectDefectInjectsSingleTeachingLine() throws Exception {
        Path source = fakeCustomerTree("inject-src", 0);
        Path dest = temp.resolve("inject-dest");
        Cli.Result result = Cli.runMain(INJECT_MAIN,
                "--source", source.toString(), "--dest", dest.toString());
        assertThat(result.exitCode()).as("注入成功 rc 0").isZero();
        Path injected = dest.resolve("flowerp/sales.py");
        assertThat(injected).as("拷贝树内 sales.py 在场").isRegularFile();
        assertThat(dest.resolve(".git")).as(".git 不入拷贝").doesNotExist();
        java.util.List<String> lines = Files.readAllLines(injected, StandardCharsets.UTF_8);
        java.util.List<String> original = Files.readAllLines(
                source.resolve("flowerp/sales.py"), StandardCharsets.UTF_8);
        assertThat(lines.size()).as("恰多一行（上游 diff 单行注入）").isEqualTo(original.size() + 1);
        int anchorAt = lines.indexOf(ANCHOR_LINE);
        int injectedAt = lines.indexOf(INJECTED_LINE);
        assertThat(anchorAt).as("锚点行在场（唯一性由拒绝用例负路径锁定）").isGreaterThanOrEqualTo(0);
        assertThat(injectedAt).as("注入行紧跟锚点行").isEqualTo(anchorAt + 1);
    }

    @Test
    void injectDefectRejectsAmbiguousAnchor() throws Exception {
        Path source = fakeCustomerTree("ambiguous-src", 1);
        Cli.Result result = Cli.runMain(INJECT_MAIN,
                "--source", source.toString(), "--dest", temp.resolve("ambiguous-dest").toString());
        assertThat(result.exitCode()).as("锚点不唯一拒绝（rc 1）").isEqualTo(1);
        assertThat(result.stderr()).contains("锚点");
    }

    @Test
    void injectDefectRejectsAlreadyInjected() throws Exception {
        Path once = temp.resolve("once-src");
        fakeCustomerTreeInto(once, 0);
        assertThat(Cli.runMain(INJECT_MAIN, "--source", once.toString(),
                "--dest", temp.resolve("once-dest").toString()).exitCode()).isZero();
        Path twice = temp.resolve("twice-src");
        fakeCustomerTreeInto(twice, 0);
        Files.writeString(twice.resolve("flowerp/sales.py"),
                Files.readString(twice.resolve("flowerp/sales.py"), StandardCharsets.UTF_8)
                        .replace(ANCHOR_LINE, ANCHOR_LINE + "\n" + INJECTED_LINE),
                StandardCharsets.UTF_8);
        Cli.Result result = Cli.runMain(INJECT_MAIN,
                "--source", twice.toString(), "--dest", temp.resolve("twice-dest").toString());
        assertThat(result.exitCode()).as("已含注入行再注拒绝（幂等护栏）").isEqualTo(1);
        assertThat(result.stderr()).contains("已注入");
    }

    // ---- 文本直读缝（C3；文件缺失即红） ---------------------------------------------------

    @Test
    void ciWorkflowIsPresentAndFinalState() throws IOException {
        Path workflow = Cli.repoRoot().resolve(WORKFLOW_FILE);
        assertThat(workflow).as("工作流文件在场（缺失即起始红第三面）").isRegularFile();
        String text = Files.readString(workflow, StandardCharsets.UTF_8);
        assertThat(text).contains("submodules: recursive");
        assertThat(text).contains("mvn");
        assertThat(text).contains("SalesChecks");
        assertThat(text).contains("ci-evidence");
        assertThat(text).contains("if-no-files-found: error");
        assertThat(text).as("终态无吞失败（continue-on-error 仅限 A 态分支历史）")
                .doesNotContain("continue-on-error");
    }

    @Test
    void ciGateSpecIsPresent() throws IOException {
        Path spec = Cli.repoRoot().resolve(GATE_SPEC_FILE);
        assertThat(spec).as("CI 门规格在场（合同写集映射位）").isRegularFile();
        assertThat(Files.readString(spec, StandardCharsets.UTF_8)).isNotBlank();
    }

    // ---- 断言小面 ------------------------------------------------------------------------

    /** 假客户树：flowerp/sales.py = 客户真件拷贝；extraAnchor 份额外锚点行（负路径构造）。 */
    private Path fakeCustomerTree(String name, int extraAnchor) throws IOException {
        Path root = temp.resolve(name);
        fakeCustomerTreeInto(root, extraAnchor);
        return root;
    }

    private void fakeCustomerTreeInto(Path root, int extraAnchor) throws IOException {
        Files.createDirectories(root.resolve("flowerp"));
        Path sales = root.resolve("flowerp/sales.py");
        Files.copy(Cli.repoRoot().resolve("vendors/flowERP/flowerp/sales.py"), sales);
        for (int i = 0; i < extraAnchor; i++) {
            Files.writeString(sales, "\n" + ANCHOR_LINE,
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
