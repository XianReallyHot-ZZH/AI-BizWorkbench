package workbench.evals.l08;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import workbench.bootstrap.Args;
import workbench.bootstrap.PyJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 证据信封件（L08 合同 workbenchIncrement「远程复验与证据信封」；上游
 * {@code workbench/ci_evidence.py} 的 Java 对应物，讲义 §1.4 宿主映射）。把统一
 * Harness 报告绑定到 CI 运行身份：报告字节 SHA-256 + 环境传入的提交、Run、工作流、
 * 运行系统与 Java 运行时版本。<b>信封是关联证据的一层，不是业务裁判，也不是合并
 * 授权</b>（上游口径：来源、内容完整、业务正确、人工接受是四种判断，不能互相替代）。
 *
 * <p>词面对齐上游：报告缺失「Harness 报告不存在：…」与缺身份「Evidence Envelope
 * 缺少 GITHUB_SHA 或 GITHUB_RUN_ID」逐字；畸形 JSON 上游裸 traceback rc 1，Java
 * 收敛为 stderr 消息、rc 同码（语言绑定偏差如实，JD6 同口径）。身份字段
 * {@code python} → {@code java}（载体如实：上游记的是 harness 解释器版本，Java 线
 * harness 是 Java；无冻结包袱不硬造同名字段——讲义 D3）。空报告（{@code {}}）仍生成
 * 信封、决策与 suite 如实为空（信封不做报告语义校验——上游教学点）。
 *
 * <p>失败不动旧输出文件（stale-output 边界：文件存在不等于本次生成）；成功才写
 * （创建父目录；indent=2 落盘，stdout 单行紧凑 JSON）。
 */
public final class CiEvidence {

    /** 信封构建失败（词面对齐上游异常面；CLI rc 1 收场）。 */
    static final class EvidenceException extends RuntimeException {
        EvidenceException(String message) {
            super(message);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CiEvidence() {}

    /** REGISTRY 缝（Main 静态注册段；{@code ./bin/wb ci-evidence --report … --output …}）。 */
    public static int execute(String[] argv) {
        Args args;
        try {
            args = new Args(argv, Set.of("--report", "--output"), Set.of());
            if (!args.has("--report") || !args.has("--output")) {
                throw new Args.UsageException("the following arguments are required: --report, --output");
            }
        } catch (Args.UsageException error) {
            System.err.println(error.getMessage());
            return 2;
        }
        try {
            Map<String, Object> envelope = buildEnvelope(
                    Path.of(args.require("--report")).toAbsolutePath().normalize(),
                    System.getenv());
            Path target = Path.of(args.require("--output")).toAbsolutePath().normalize();
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, PyJson.dumps(envelope) + "\n", StandardCharsets.UTF_8);
            System.out.println(PyJson.dumpsCompact(envelope));
            return 0;
        } catch (EvidenceException error) {
            System.err.println(error.getMessage());
            return 1;
        } catch (IOException error) {
            System.err.println("信封输出写入失败：" + error.getMessage());
            return 1;
        }
    }

    /**
     * 构建信封（八字段）。报告缺失/畸形/缺身份 → {@link EvidenceException}（CLI 面
     * rc 1 + stderr；登记项 {@code ci_evidence_envelope_is_honest} 进程内复用本面）。
     */
    static Map<String, Object> buildEnvelope(Path reportPath, Map<String, String> env) {
        if (!Files.isRegularFile(reportPath)) {
            throw new EvidenceException("Harness 报告不存在：" + reportPath);
        }
        byte[] raw;
        try {
            raw = Files.readAllBytes(reportPath);
        } catch (IOException error) {
            throw new EvidenceException("Harness 报告读取失败：" + reportPath);
        }
        JsonNode report;
        try {
            report = MAPPER.readTree(raw);
        } catch (IOException error) {
            throw new EvidenceException("报告不是合法 JSON：" + error.getMessage());
        }
        String sha = env.getOrDefault("GITHUB_SHA", "").strip();
        String runId = env.getOrDefault("GITHUB_RUN_ID", "").strip();
        if (sha.isEmpty() || runId.isEmpty()) {
            throw new EvidenceException("Evidence Envelope 缺少 GITHUB_SHA 或 GITHUB_RUN_ID");
        }
        JsonNode decision = report.path("summary").path("decision");
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("commit_sha", sha);
        envelope.put("run_id", runId);
        envelope.put("workflow", env.getOrDefault("GITHUB_WORKFLOW", "").strip());
        envelope.put("runner_os", env.getOrDefault("RUNNER_OS", "").strip());
        envelope.put("java", Runtime.version().toString());
        envelope.put("suite", report.path("suite").asText(""));
        envelope.put("report_sha256", sha256(raw));
        envelope.put("report_decision",
                decision.isMissingNode() || decision.isNull() ? null : decision.asText());
        return envelope;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
