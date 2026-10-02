package workbench.evals.l11;

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
 * L11 采购面统一入口与注入装置合同测试：讲义 docs/lessons/L11-独立写集并行调度.md
 * §2 C1/C2/C4/C8 工具面 → 用例映射（agent 家族第三件，无 Python 先例；映射源 = 上游
 * purchase_request_lab 七模式 + integration_lab 注入锚点 + InjectCancelDefect /
 * InjectShipDefect 先例结构，只读对照）。
 *
 * <p>接缝（spec docs/specs/L11-*.md 已具名确认）：独立 main 子进程缝
 * （PurchaseChecks / InjectPurchaseDefect）——两个新高缝之一。插入行与 marker 行是
 * <b>独立预期源</b>（测试与实现各持一份逐字副本；本讲上游逐字源在场：
 * vendors/CodexFDE/docs/courses/L11/examples/integration_lab.py:87-88，讲前已验）。
 * 与 L10 替换式不同——本讲注入是<b>插入式</b>（injection 行插在 marker 行前，
 * marker 保留、行数 +1；上游 {@code original[pos:stop].replace(marker, injection+marker)}
 * 同形），恢复 = 同一装置删注入行（--restore，diff 互逆可对账）。
 *
 * <p>红点组（commit 1，讲义 §3 步骤 2）：PurchaseChecks / InjectPurchaseDefect main
 * 缺席 → rc 1 class not found，全部目标行为断言失败即红——schedule 核（经
 * {@code write_sets_reject_conflict} 登记项调用）与探针（经登记项场景调用）的缺席面
 * 由本 main 缝承载（schedule 无 CLI：上游 agent/schedule.py 即无 main，讲义 D1）。
 * 实现转绿后本类零改动；十模式 + 三交集直调库核机检与实现同 commit 2（直调面
 * 编译依赖实现类，起始红由本 main 缝先行——L06-java「编译不绑实现类」教训承袭）。
 * 登记项内容（七模式断言、客户绑定 eval 照跑、冻结指纹）经候选期
 * {@code PurchaseChecks --target} 真跑验证，本类只锁 argv 与注入行为合同（L08–L10
 * 同款口径：mvn 面不冒充真实运行）。
 *
 * <pre>
 * 用例 → 合同映射：
 * purchaseChecksRequiresTarget              C1/C2 argv 面：缺 --target → rc 2
 * purchaseChecksRejectsTargetWithoutFlowerp C2 argv 面：target 无 flowerp/ → rc 2
 * purchaseChecksRejectsUnknownCase          C2 argv 面：未知名拒绝且不触发探针
 * injectPurchaseDefectRequiresSource        C8 argv 面：缺参 → rc 2
 * injectPurchaseDefectInsertsBeforeMarker   C8 注入：marker 行前插注入行（上游逐字），
 *                                           marker 保留、行数 +1、位置可机检、
 *                                           .git/__pycache__ 不拷
 * injectPurchaseDefectRejectsAmbiguousAnchor C8 护栏：propose 段内 marker 两次 → 拒绝
 * injectPurchaseDefectRejectsAlreadyInjected C8 护栏：已含注入行 → 拒绝（幂等）
 * injectPurchaseDefectRejectsExistingDest   C8 护栏：dest 已存在 → 拒绝（旧证据不可抹）
 * injectPurchaseDefectRestoreRemovesInjection C8 恢复：注入行删除、marker 回原位、行数复原
 * injectPurchaseDefectRestoreRejectsCleanTree C8 护栏：净树 --restore → 拒绝（幂等）
 * </pre>
 */
class L11PurchaseChecksContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 独立预期源（逐字）：上游 integration_lab.py:87-88 的 marker/injection 两行
     * （8 空格缩进 = 客户 service.py propose_purchase 方法体层级，讲前已验段内恰一次）。
     */
    static final String MARKER_LINE = "        return self.purchase(pid)";
    static final String INJECTED_LINE = "        self.receive_stock(sku, quantity, \"teaching-premature-\" + pid)";

    /** 假客户树最小 service.py 片段（含逐字 marker 行；propose 段 → purchase 段边界同客户）。 */
    private static void writeFakeServicePy(Path source) throws IOException {
        Path flowerp = Files.createDirectories(source.resolve("flowerp"));
        Files.writeString(flowerp.resolve("service.py"), """
                class ERPService:
                    def propose_purchase(
                        self, sku: str, quantity: int, reason: str, request_id: str | None = None,
                    ) -> dict:
                        if quantity <= 0 or not reason.strip():
                            raise ValueError("采购数量和原因不能为空")
                        pid = request_id or f"PR-{uuid.uuid4().hex[:8].upper()}"
                        return self.purchase(pid)

                    def purchase(self, request_id: str) -> dict:
                        return None
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
    void purchaseChecksRequiresTarget() {
        Cli.Result result = Cli.runMain("workbench.evals.l11.PurchaseChecks");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("--target");
    }

    @Test
    void purchaseChecksRejectsTargetWithoutFlowerp(@TempDir Path tmp) throws IOException {
        Path empty = Files.createDirectories(tmp.resolve("empty"));
        Cli.Result result = Cli.runMain("workbench.evals.l11.PurchaseChecks",
                "--target", empty.toString(), "--python", "/usr/bin/true");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("flowerp");
    }

    @Test
    void purchaseChecksRejectsUnknownCase(@TempDir Path tmp) throws IOException {
        // --case 选跑面：未知名拒绝且不触发探针（L09 T-7 回归锚同款）
        Path target = Files.createDirectories(tmp.resolve("target"));
        Files.createDirectories(target.resolve("flowerp"));
        Cli.Result result = Cli.runMain("workbench.evals.l11.PurchaseChecks",
                "--target", target.toString(), "--case", "l11_nonexistent");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("l11_nonexistent");
    }

    @Test
    void injectPurchaseDefectRequiresSource() {
        Cli.Result result = Cli.runMain("workbench.evals.l11.InjectPurchaseDefect");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.stderr() + result.stdout()).contains("--source");
    }

    @Test
    void injectPurchaseDefectInsertsBeforeMarker(@TempDir Path tmp) throws IOException {
        Path source = tmp.resolve("source");
        writeFakeServicePy(source);
        Path dest = tmp.resolve("dest");
        Path sourcePy = source.resolve("flowerp").resolve("service.py");
        int markerLine = lineNumberContaining(sourcePy, MARKER_LINE);
        int sourceLineCount = Files.readAllLines(sourcePy, StandardCharsets.UTF_8).size();

        Cli.Result result = Cli.runMain("workbench.evals.l11.InjectPurchaseDefect",
                "--source", source.toString(), "--dest", dest.toString());

        assertThat(result.exitCode()).isZero();
        Path destPy = dest.resolve("flowerp").resolve("service.py");
        String injected = Files.readString(destPy, StandardCharsets.UTF_8);
        // 插入非替换：注入行在场（上游逐字）、marker 行仍在、行数 +1
        assertThat(injected).contains(INJECTED_LINE);
        assertThat(injected).contains(MARKER_LINE);
        assertThat(Files.readAllLines(destPy, StandardCharsets.UTF_8)).hasSize(sourceLineCount + 1);
        // 注入位置可机检：注入行占 marker 原行号（插在其前）、marker 后移一行
        assertThat(lineNumberContaining(destPy, INJECTED_LINE)).isEqualTo(markerLine);
        assertThat(lineNumberContaining(destPy, MARKER_LINE)).isEqualTo(markerLine + 1);
        // stdout 报告的行号与独立计算一致
        assertThat(result.stdout()).isNotBlank();
        int reported = MAPPER.readTree(result.stdout()).path("injected_line").asInt(-1);
        assertThat(reported).isEqualTo(markerLine);
        // 拷贝排除：.git 文件与 __pycache__ 不入 dest
        assertThat(dest.resolve(".git")).doesNotExist();
        assertThat(dest.resolve("flowerp").resolve("__pycache__")).doesNotExist();
    }

    @Test
    void injectPurchaseDefectRejectsAmbiguousAnchor(@TempDir Path tmp) throws IOException {
        Path source = tmp.resolve("source");
        writeFakeServicePy(source);
        Path servicePy = source.resolve("flowerp").resolve("service.py");
        // propose 段内 marker 行出现第二次：段内唯一性校验拒绝
        Files.writeString(servicePy, Files.readString(servicePy, StandardCharsets.UTF_8)
                        .replace(MARKER_LINE + "\n\n    def purchase(", MARKER_LINE + "\n" + MARKER_LINE + "\n\n    def purchase("),
                StandardCharsets.UTF_8);

        Cli.Result result = Cli.runMain("workbench.evals.l11.InjectPurchaseDefect",
                "--source", source.toString(), "--dest", tmp.resolve("dest").toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("锚点");
        assertThat(tmp.resolve("dest")).doesNotExist();
    }

    @Test
    void injectPurchaseDefectRejectsAlreadyInjected(@TempDir Path tmp) throws IOException {
        Path source = tmp.resolve("source");
        writeFakeServicePy(source);
        // 源树已含注入行：幂等护栏拒绝重复注入
        Path servicePy = source.resolve("flowerp").resolve("service.py");
        Files.writeString(servicePy, Files.readString(servicePy, StandardCharsets.UTF_8)
                + INJECTED_LINE + "\n", StandardCharsets.UTF_8);

        Cli.Result result = Cli.runMain("workbench.evals.l11.InjectPurchaseDefect",
                "--source", source.toString(), "--dest", tmp.resolve("dest").toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("已注入");
        assertThat(tmp.resolve("dest")).doesNotExist();
    }

    @Test
    void injectPurchaseDefectRejectsExistingDest(@TempDir Path tmp) throws IOException {
        Path source = tmp.resolve("source");
        writeFakeServicePy(source);
        Path dest = tmp.resolve("dest");
        Files.createDirectories(dest);
        Files.writeString(dest.resolve("旧证据"), "keep", StandardCharsets.UTF_8);

        Cli.Result result = Cli.runMain("workbench.evals.l11.InjectPurchaseDefect",
                "--source", source.toString(), "--dest", dest.toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("dest");
        assertThat(dest.resolve("旧证据")).hasContent("keep");
    }

    @Test
    void injectPurchaseDefectRestoreRemovesInjection(@TempDir Path tmp) throws IOException {
        Path source = tmp.resolve("source");
        writeFakeServicePy(source);
        Path dest = tmp.resolve("dest");
        Cli.runMain("workbench.evals.l11.InjectPurchaseDefect",
                "--source", source.toString(), "--dest", dest.toString());
        Path sourcePy = source.resolve("flowerp").resolve("service.py");
        Path destPy = dest.resolve("flowerp").resolve("service.py");
        int sourceLineCount = Files.readAllLines(sourcePy, StandardCharsets.UTF_8).size();

        Cli.Result result = Cli.runMain("workbench.evals.l11.InjectPurchaseDefect",
                "--restore", "--dest", dest.toString());

        // 恢复 = 删注入行：marker 回原位、行数复原、内容与源树逐字节一致（diff 互逆）
        assertThat(result.exitCode()).isZero();
        String restored = Files.readString(destPy, StandardCharsets.UTF_8);
        assertThat(restored).doesNotContain(INJECTED_LINE);
        assertThat(restored).isEqualTo(Files.readString(sourcePy, StandardCharsets.UTF_8));
        assertThat(Files.readAllLines(destPy, StandardCharsets.UTF_8)).hasSize(sourceLineCount);
        assertThat(result.stdout()).isNotBlank();
        assertThat(MAPPER.readTree(result.stdout()).path("restored").asBoolean()).isTrue();
    }

    @Test
    void injectPurchaseDefectRestoreRejectsCleanTree(@TempDir Path tmp) throws IOException {
        Path source = tmp.resolve("source");
        writeFakeServicePy(source);
        Path dest = tmp.resolve("dest");
        Cli.runMain("workbench.evals.l11.InjectPurchaseDefect",
                "--source", source.toString(), "--dest", dest.toString());
        // 先恢复一次成功，再对净树恢复：幂等护栏拒绝
        Cli.runMain("workbench.evals.l11.InjectPurchaseDefect",
                "--restore", "--dest", dest.toString());
        Path destPy = dest.resolve("flowerp").resolve("service.py");
        String clean = Files.readString(destPy, StandardCharsets.UTF_8);

        Cli.Result result = Cli.runMain("workbench.evals.l11.InjectPurchaseDefect",
                "--restore", "--dest", dest.toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("注入");
        assertThat(Files.readString(destPy, StandardCharsets.UTF_8)).isEqualTo(clean);
    }
}
