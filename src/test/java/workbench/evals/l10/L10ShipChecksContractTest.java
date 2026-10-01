package workbench.evals.l10;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import workbench.testsupport.Cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L10 发货面统一入口与注入装置合同测试：讲义 docs/lessons/L10-有停止条件的修复Loop.md
 * §2 C1/C2/C8 工具面 → 用例映射（无 Python 先例第二讲；映射源 = 上游
 * order_transition_lab 五模式 + candidate_loop_lab 注入锚点 + InjectCancelDefect 先例
 * 结构，只读对照）。
 *
 * <p>接缝（spec docs/specs/L10-*.md 已具名确认）：独立 main 子进程缝（ShipChecks /
 * InjectShipDefect）——全复用既有缝。锚点行与注入行是<b>独立预期源</b>（测试与实现
 * 各持一份逐字副本；与 L09 自拟形态不同——本讲上游逐字源在场：
 * vendors/CodexFDE/docs/courses/L10/examples/candidate_loop_lab.py:24-25，讲前已验）。
 *
 * <p>红点组（commit 1，讲义 §3 步骤 2）：ShipChecks / InjectShipDefect main 缺席
 * → rc 1 class not found，全部目标行为断言失败即红；实现转绿后本类零改动。
 * 登记项内容（五模式断言、客户绑定 eval 照跑、冻结指纹）经候选期
 * {@code ShipChecks --target} 真跑验证，本类只锁 argv 与注入行为合同（L08/L09 同款
 * 口径：mvn 面不冒充真实运行）。
 *
 * <pre>
 * 用例 → 合同映射：
 * shipChecksRequiresTarget              C1/C2 argv 面：缺 --target → rc 2
 * shipChecksRejectsTargetWithoutFlowerp C2 argv 面：target 无 flowerp/ → rc 2
 * shipChecksRejectsUnknownCase          C2 argv 面：未知名拒绝且不触发探针
 * injectShipDefectRequiresSource        C8 argv 面：缺参 → rc 2
 * injectShipDefectReplacesSingleLine    C8 注入：锚点行 → 注入行（上游逐字），行数不变、
 *                                        位置可机检、.git/__pycache__ 不拷
 * injectShipDefectRejectsAmbiguousAnchor C8 护栏：锚点两次 → 拒绝
 * injectShipDefectRejectsAlreadyInjected C8 护栏：已含注入行 → 拒绝（幂等）
 * injectShipDefectRejectsExistingDest   C8 护栏：dest 已存在 → 拒绝（旧证据不可抹）
 * </pre>
 */
class L10ShipChecksContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 独立预期源（逐字）：上游 candidate_loop_lab.py:24-25 的 GOOD/BAD 锚点行
     * （12 空格缩进 = 客户 service.py ship_order with 块内层级，讲前已验全文恰一次）。
     */
    static final String ANCHOR_LINE = "            if order[\"status\"] != OrderStatus.RESERVED:";
    static final String INJECTED_LINE = "            if True:  # TEACHING: incorrectly reject every shipment";

    /** 假客户树最小 service.py 片段（含逐字锚点行）。 */
    private static void writeFakeServicePy(Path source) throws IOException {
        Path flowerp = Files.createDirectories(source.resolve("flowerp"));
        Files.writeString(flowerp.resolve("service.py"), """
                class ERPService:
                    def ship_order(self, order_id: str) -> dict:
                        with self.store.connect() as conn:
                            order = conn.execute("SELECT status FROM sales_orders WHERE id=?", (order_id,)).fetchone()
                            if order["status"] != OrderStatus.RESERVED:
                                raise InvalidTransition(f"只有 reserved 订单可发货，当前为 {order['status']}")
                            return {}
                """, StandardCharsets.UTF_8);
        // L08 真实现缺陷教训：submodule 的 .git 是文件（gitdir 指针），拷贝必须排除
        Files.writeString(source.resolve(".git"), "gitdir: ../real.git\n", StandardCharsets.UTF_8);
        Path pycache = Files.createDirectories(flowerp.resolve("__pycache__"));
        Files.write(pycache.resolve("service.cpython-312.pyc"), new byte[]{1});
    }

    private static int lineNumberContaining(Path file, String needle) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals(needle)) {
                return i + 1;
            }
        }
        return -1;
    }

    @Test
    void shipChecksRequiresTarget() {
        Cli.Result result = Cli.runMain("workbench.evals.l10.ShipChecks");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("--target");
    }

    @Test
    void shipChecksRejectsTargetWithoutFlowerp(@TempDir Path tmp) throws IOException {
        Path empty = Files.createDirectories(tmp.resolve("empty"));
        Cli.Result result = Cli.runMain("workbench.evals.l10.ShipChecks",
                "--target", empty.toString(), "--python", "/usr/bin/true");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("flowerp");
    }

    @Test
    void shipChecksRejectsUnknownCase(@TempDir Path tmp) throws IOException {
        // --case 选跑面：未知名拒绝且不触发探针（L09 T-7 回归锚同款）
        Path target = Files.createDirectories(tmp.resolve("target"));
        Files.createDirectories(target.resolve("flowerp"));
        Cli.Result result = Cli.runMain("workbench.evals.l10.ShipChecks",
                "--target", target.toString(), "--case", "l10_nonexistent");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("l10_nonexistent");
    }

    @Test
    void injectShipDefectRequiresSource() {
        Cli.Result result = Cli.runMain("workbench.evals.l10.InjectShipDefect");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("--source");
    }

    @Test
    void injectShipDefectReplacesSingleLine(@TempDir Path tmp) throws IOException {
        Path source = tmp.resolve("source");
        writeFakeServicePy(source);
        Path dest = tmp.resolve("dest");
        Path sourcePy = source.resolve("flowerp").resolve("service.py");
        int anchorLine = lineNumberContaining(sourcePy, ANCHOR_LINE);
        int sourceLineCount = Files.readAllLines(sourcePy, StandardCharsets.UTF_8).size();

        Cli.Result result = Cli.runMain("workbench.evals.l10.InjectShipDefect",
                "--source", source.toString(), "--dest", dest.toString());

        assertThat(result.exitCode()).isZero();
        Path destPy = dest.resolve("flowerp").resolve("service.py");
        String injected = Files.readString(destPy, StandardCharsets.UTF_8);
        // 替换非插入：注入行在场（上游逐字）、锚点行退场、行数不变
        assertThat(injected).contains(INJECTED_LINE);
        assertThat(injected).doesNotContain(ANCHOR_LINE);
        assertThat(Files.readAllLines(destPy, StandardCharsets.UTF_8)).hasSize(sourceLineCount);
        // 注入位置可机检：stdout 报告的行号 = 锚点行原行号（独立计算，替换不位移）
        assertThat(result.stdout()).isNotBlank();
        int reported = MAPPER.readTree(result.stdout()).path("injected_line").asInt(-1);
        assertThat(reported).isEqualTo(anchorLine);
        // 拷贝排除：.git 文件与 __pycache__ 不入 dest
        assertThat(dest.resolve(".git")).doesNotExist();
        assertThat(dest.resolve("flowerp").resolve("__pycache__")).doesNotExist();
    }

    @Test
    void injectShipDefectRejectsAmbiguousAnchor(@TempDir Path tmp) throws IOException {
        Path source = tmp.resolve("source");
        writeFakeServicePy(source);
        Path servicePy = source.resolve("flowerp").resolve("service.py");
        // 锚点行出现第二次：唯一性校验拒绝
        Files.writeString(servicePy, Files.readString(servicePy, StandardCharsets.UTF_8)
                + ANCHOR_LINE + "\n", StandardCharsets.UTF_8);

        Cli.Result result = Cli.runMain("workbench.evals.l10.InjectShipDefect",
                "--source", source.toString(), "--dest", tmp.resolve("dest").toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("锚点");
        assertThat(tmp.resolve("dest")).doesNotExist();
    }

    @Test
    void injectShipDefectRejectsAlreadyInjected(@TempDir Path tmp) throws IOException {
        Path source = tmp.resolve("source");
        writeFakeServicePy(source);
        // 源树已含注入行：幂等护栏拒绝重复注入
        Path servicePy = source.resolve("flowerp").resolve("service.py");
        Files.writeString(servicePy, Files.readString(servicePy, StandardCharsets.UTF_8)
                + INJECTED_LINE + "\n", StandardCharsets.UTF_8);

        Cli.Result result = Cli.runMain("workbench.evals.l10.InjectShipDefect",
                "--source", source.toString(), "--dest", tmp.resolve("dest").toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("已注入");
        assertThat(tmp.resolve("dest")).doesNotExist();
    }

    @Test
    void injectShipDefectRejectsExistingDest(@TempDir Path tmp) throws IOException {
        Path source = tmp.resolve("source");
        writeFakeServicePy(source);
        Path dest = tmp.resolve("dest");
        Files.createDirectories(dest);
        Files.writeString(dest.resolve("旧证据"), "keep", StandardCharsets.UTF_8);

        Cli.Result result = Cli.runMain("workbench.evals.l10.InjectShipDefect",
                "--source", source.toString(), "--dest", dest.toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("dest");
        assertThat(dest.resolve("旧证据")).hasContent("keep");
    }
}
