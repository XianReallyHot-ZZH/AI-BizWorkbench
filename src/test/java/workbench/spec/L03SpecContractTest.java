package workbench.spec;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import workbench.testsupport.Cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L03 合同测试：讲义 §2 C1..C13 → 用例映射（Spec = docs/lessons/L03-可验收Spec.md §2 + 附录 A2）。
 *
 * <p>映射表（验收项 → 测试名 → 实际操作 → 比较什么）——红点组 = Java spec 能力缺失
 * （移植注记 A2 C1：目标组 commit 1 红）；护栏组 = 冻结工件在场断言（就位即绿，映射表
 * 声明——Python L02 ReferencedCommandsAreReal 先例）：
 *
 * <pre>
 * 红点组（目标能力缺失，commit 1 红）：
 * C9     → specCommandParsesValidContract       → 六段夹具经 spec 子命令 → rc 0 + ok:true + 六字段
 * C7/C11 → rejectsMissingSection                → 缺"约束"夹具 → rc 1 + 缺章词面（eval spec_contract_rejects_ambiguity 承载，D1）
 * C7     → rejectsEmptySection                  → 空正文夹具 → rc 1 + 空章词面
 * C7     → rejectsDuplicateSection              → 重复"## 目标"夹具 → rc 1 + 重复词面
 * C7     → rejectsWrongOrder                    → 来源/目标互换夹具 → rc 1 + 顺序词面（应为/实际，→ 连接）
 * C7     → rejectsUnknownHeading                → "## 备注"夹具 → rc 1 + 未知词面
 * C7     → u2028SeparatedHeadingsRejectedLikeFrozenParser → U+2028 连接夹具 → rc 1 + 冻结 Python 实测词面（复查轮 S-1；标题行界仅 \n）
 * C7     → rejectsUnclosedFence                 → ``` 未闭合夹具 → rc 1 + 围栏未闭合词面（不吞后续正式章节）
 * C7     → acceptsHeadingInsideFence            → 围栏内含"## 目标"夹具 → rc 0 且围栏正文在约束字段保留原文（检查点 0002 教学节）
 * C3     → preservesSixFieldsVerbatim           → 六段夹具 → 六字段与各节正文逐字一致
 * C8     → rejectionLeavesFileUnchanged         → 拒绝路径 tmp 文件 SHA-256 前后一致
 * C8     → successLeavesFileUnchanged           → 成功路径 tmp 文件 SHA-256 前后一致
 * C8     → missingPathReportsJsonContractShape  → 不存在路径 → rc 1 + JSON ok:false（errno 词面偏差按 JD6 如实记录，只断形状）
 * 护栏组（就位即绿，映射表声明）：
 * C2/C4  → frozenRequirementArtifactsInPlace    → 六工件在场；decisions.md 八问结构；v1 未确认状态句；确认版 A6 细则 + A8（歧义消除可溯源）
 * C3     → confirmedSpecBusinessScopePresent    → 确认版八条业务口径词面在场（文档零重写护栏，不重裁定）
 * C5     → frozenTemplateNoInventoryResidue     → 冻结模板六标题在场 + 库存词面零命中（护栏半边；双载体见 SpecTemplateDualCarrierTest）
 * C6     → procurementDraftReusesStructure      → 采购稿"待确认"在场 + 库存词面零命中（内容面护栏；结构重验走 golden t05）
 * </pre>
 *
 * <p>所有工作台行为用例经真实 CLI 子进程（java -cp … workbench.cli.Main）驱动，
 * 不绕开入口直调内部函数（测试口径）；golden l03 字节对照另见 GoldenL03ReplayTest。
 * 六类拒绝词面与冻结解析器逐字同形（vendor parse_spec()），检查顺序未知→重复→缺章→
 * 乱序→空章、围栏未闭合先行（移植注记 A6.1）。
 */
class L03SpecContractTest {

    private static final Path SUBMISSION = Cli.repoRoot().resolve("lesson-03-submission");

    // ---- 夹具（六段合成；变异只引入目标差异）----

    private static String section(String title, String body) {
        return "## " + title + "\n" + body + "\n";
    }

    private static String sixSections(String source, String goal, String nonGoals,
                                      String constraints, String acceptance, String done) {
        return section("来源", source) + section("目标", goal) + section("非目标", nonGoals)
                + section("约束", constraints) + section("验收用例", acceptance)
                + section("完成定义", done);
    }

    private static final String SIX_SECTIONS = sixSections(
            "合同测试夹具来源段。", "合同测试夹具目标段。", "合同测试夹具非目标段。",
            "合同测试夹具约束段。", "合同测试夹具验收用例段。", "合同测试夹具完成定义段。");

    /** 缺"约束"整节（缺章检查按 REQUIRED_SECTIONS 顺序指认）。 */
    private static final String MISSING_SECTION = section("来源", "合同测试夹具来源段。")
            + section("目标", "合同测试夹具目标段。")
            + section("非目标", "合同测试夹具非目标段。")
            + section("验收用例", "合同测试夹具验收用例段。")
            + section("完成定义", "合同测试夹具完成定义段。");

    /** "来源"正文为空（保留空正文意图，不新增标题——evidence/L03.md §3 夹具教训）。 */
    private static final String EMPTY_SECTION = sixSections(
            "", "合同测试夹具目标段。", "合同测试夹具非目标段。",
            "合同测试夹具约束段。", "合同测试夹具验收用例段。", "合同测试夹具完成定义段。");

    /** 六段齐全 + 末尾重复"## 目标"（重复检查先于顺序检查）。 */
    private static final String DUPLICATE_SECTION =
            SIX_SECTIONS + section("目标", "重复出现的目标段，应触发重复检查。");

    /** 来源与目标互换（顺序词面给出应为/实际）。 */
    private static final String WRONG_ORDER = section("目标", "合同测试夹具目标段。")
            + section("来源", "合同测试夹具来源段。")
            + section("非目标", "合同测试夹具非目标段。")
            + section("约束", "合同测试夹具约束段。")
            + section("验收用例", "合同测试夹具验收用例段。")
            + section("完成定义", "合同测试夹具完成定义段。");

    /** 六段 + "## 备注"（未知检查最先触发）。 */
    private static final String UNKNOWN_HEADING =
            SIX_SECTIONS + section("备注", "未知章节，应触发未知检查。");

    /** 约束节含未闭合围栏：后续正式章节必须报错，不被吞掉。 */
    private static final String UNCLOSED_FENCE = sixSections(
            "合同测试夹具来源段。", "合同测试夹具目标段。", "合同测试夹具非目标段。",
            "只做结构检查。示例缺少结束标记：\n\n```text\n## 目标\n围栏一直开到文件结尾。\n",
            "合同测试夹具验收用例段。", "合同测试夹具完成定义段。");

    /** 约束节含闭合围栏，围栏内有"## 目标"标题行：不误认，正文保留。 */
    private static final String HEADING_IN_FENCE = sixSections(
            "围栏屏蔽夹具来源段。", "围栏屏蔽夹具目标段。", "围栏屏蔽夹具非目标段。",
            "只做结构检查。示例正文里含标题样式，但它不是章节：\n\n"
                    + "```text\n## 目标\n围栏内示例正文。\n```\n",
            "围栏屏蔽夹具验收用例段。", "围栏屏蔽夹具完成定义段。");

    @TempDir
    private Path tmp;

    // ---- 红点组：spec 命令与解析行为（目标能力缺失，commit 1 红）----

    @Test
    void specCommandParsesValidContract() {
        JsonNode payload = okPayload(Cli.run("spec", writeFixture(SIX_SECTIONS)));
        assertThat(payload.path("ok").asBoolean()).isTrue();
        assertThat(payload.has("spec")).as("成功输出含六字段 spec 对象").isTrue();
        assertThat(payload.path("spec").path("source").asText())
                .isEqualTo("合同测试夹具来源段。");
    }

    @Test
    void rejectsMissingSection() {
        assertThat(rejectionMessage(Cli.run("spec", writeFixture(MISSING_SECTION))))
                .isEqualTo("Spec 缺少必要章节：约束");
    }

    @Test
    void rejectsEmptySection() {
        assertThat(rejectionMessage(Cli.run("spec", writeFixture(EMPTY_SECTION))))
                .isEqualTo("Spec 章节内容不能为空：来源");
    }

    @Test
    void rejectsDuplicateSection() {
        assertThat(rejectionMessage(Cli.run("spec", writeFixture(DUPLICATE_SECTION))))
                .isEqualTo("Spec 包含重复章节：目标");
    }

    @Test
    void rejectsWrongOrder() {
        assertThat(rejectionMessage(Cli.run("spec", writeFixture(WRONG_ORDER))))
                .isEqualTo("Spec 章节顺序错误；应为：来源 → 目标 → 非目标 → 约束 → 验收用例 → 完成定义"
                        + "；实际为：目标 → 来源 → 非目标 → 约束 → 验收用例 → 完成定义");
    }

    @Test
    void rejectsUnknownHeading() {
        assertThat(rejectionMessage(Cli.run("spec", writeFixture(UNKNOWN_HEADING))))
                .isEqualTo("Spec 包含未知章节：备注");
    }

    /** U+2028（Unicode 行分隔符）连接的标题与正文：Python re.MULTILINE 只认 \n 行界，
     * 整段成为未知标题——期望词面为冻结 Python CLI 对同一夹具的实测输出（复查轮 S-1）。 */
    @Test
    void u2028SeparatedHeadingsRejectedLikeFrozenParser() {
        String sep = "\u2028";
        String fixture = "## 来源" + sep + "来源正文。\n"
                + "## 目标" + sep + "目标正文。\n"
                + "## 非目标" + sep + "非目标正文。\n"
                + "## 约束" + sep + "约束正文。\n"
                + "## 验收用例" + sep + "验收正文。\n"
                + "## 完成定义" + sep + "完成正文。\n";
        assertThat(rejectionMessage(Cli.run("spec", writeFixture(fixture))))
                .isEqualTo("Spec 包含未知章节：来源" + sep + "来源正文。, 目标" + sep + "目标正文。, "
                        + "非目标" + sep + "非目标正文。, 约束" + sep + "约束正文。, "
                        + "验收用例" + sep + "验收正文。, 完成定义" + sep + "完成正文。");
    }

    @Test
    void rejectsUnclosedFence() {
        assertThat(rejectionMessage(Cli.run("spec", writeFixture(UNCLOSED_FENCE))))
                .isEqualTo("Spec 代码围栏未闭合，请补齐示例的结束标记");
    }

    @Test
    void acceptsHeadingInsideFence() {
        JsonNode payload = okPayload(Cli.run("spec", writeFixture(HEADING_IN_FENCE)));
        JsonNode spec = payload.path("spec");
        // 围栏内 "## 目标" 不被误认：六字段来自正式章节
        assertThat(spec.path("source").asText()).isEqualTo("围栏屏蔽夹具来源段。");
        assertThat(spec.path("goal").asText()).isEqualTo("围栏屏蔽夹具目标段。");
        // 围栏正文在约束字段保留原文（检查点 0002 教学节）
        assertThat(spec.path("constraints").asText())
                .contains("## 目标")
                .contains("围栏内示例正文。");
    }

    @Test
    void preservesSixFieldsVerbatim() {
        JsonNode spec = okPayload(Cli.run("spec", writeFixture(SIX_SECTIONS))).path("spec");
        assertThat(spec.path("source").asText()).isEqualTo("合同测试夹具来源段。");
        assertThat(spec.path("goal").asText()).isEqualTo("合同测试夹具目标段。");
        assertThat(spec.path("non_goals").asText()).isEqualTo("合同测试夹具非目标段。");
        assertThat(spec.path("constraints").asText()).isEqualTo("合同测试夹具约束段。");
        assertThat(spec.path("acceptance").asText()).isEqualTo("合同测试夹具验收用例段。");
        assertThat(spec.path("done").asText()).isEqualTo("合同测试夹具完成定义段。");
    }

    @Test
    void rejectionLeavesFileUnchanged() throws Exception {
        Path file = tmp.resolve("rejected.md");
        Files.writeString(file, MISSING_SECTION, StandardCharsets.UTF_8);
        String before = sha256(file);
        rejectionMessage(Cli.run("spec", file.toString()));
        assertThat(sha256(file)).as("拒绝路径不得改写原文件（C8）").isEqualTo(before);
    }

    @Test
    void successLeavesFileUnchanged() throws Exception {
        Path file = tmp.resolve("accepted.md");
        Files.writeString(file, SIX_SECTIONS, StandardCharsets.UTF_8);
        String before = sha256(file);
        okPayload(Cli.run("spec", file.toString()));
        assertThat(sha256(file)).as("成功路径不得改写原文件（C8）").isEqualTo(before);
    }

    @Test
    void missingPathReportsJsonContractShape() {
        Cli.Result result = Cli.run("spec", tmp.resolve("no-such-spec.md").toString());
        assertThat(result.exitCode()).as("缺失路径应拒绝（JSON 契约，rc 1）").isEqualTo(1);
        JsonNode payload = Cli.json(result);
        assertThat(payload.path("ok").asBoolean()).isFalse();
        assertThat(payload.path("error").asText()).isNotBlank();
        // errno 词面系语言绑定（移植注记 JD6）：只断 JSON 形状，不伪装 Python 同形
    }

    // ---- 护栏组：冻结工件在场（就位即绿，映射表声明）----

    @Test
    void frozenRequirementArtifactsInPlace() throws Exception {
        for (String name : new String[] {"decisions.md", "FDE_SPEC-v1.md", "FDE_SPEC.md",
                "procurement-draft.md", "missing-section.md", "wrong-formula.md"}) {
            assertThat(SUBMISSION.resolve(name)).as("冻结工件 " + name + " 在场").exists();
        }
        String decisions = read(SUBMISSION.resolve("decisions.md"));
        assertThat(decisions).as("decisions.md 八问结构（C2）").contains("Q1", "Q8");
        String v1 = read(SUBMISSION.resolve("FDE_SPEC-v1.md"));
        assertThat(v1).as("v1 保留未确认状态句（C4 歧义可溯源）")
                .contains("尚未经反例审查与最终确认");
        assertThat(v1).as("v1 无 A8 负可用量细则（修订可溯源）").doesNotContain("A8");
        assertThat(read(SUBMISSION.resolve("FDE_SPEC.md")))
                .as("确认版歧义消除后细则在场（C4）")
                .contains("完全相同排序键不承诺额外次序")
                .contains("照实输出为 -3");
    }

    @Test
    void confirmedSpecBusinessScopePresent() throws Exception {
        assertThat(read(SUBMISSION.resolve("FDE_SPEC.md")))
                .contains("sku,name,site,location,lot_id,on_hand,reserved,available")  // 八列列序
                .contains("available = on_hand - reserved")                            // 可用量公式
                .contains("不跨仓库、仓位或批次合并")                                     // 多仓不合并
                .contains("仍生成八列表头")                                              // 空结果出表头
                .contains("UTF-8（无 BOM）")                                            // CSV 编码保真
                .contains("升序排列")                                                   // 稳定排序
                .contains("非零退出码")                                                 // 写出失败
                .contains("照实输出为 -3");                                             // 负可用量
    }

    @Test
    void frozenTemplateNoInventoryResidue() throws Exception {
        // C5 断言读 Java 面拷贝（移植注记 A2 C5 字面）；双载体机检已锁其与 Python 原件逐字等价
        String template = read(Cli.repoRoot()
                .resolve("src/main/resources/templates/SPEC_TEMPLATE.md"));
        for (String heading : new String[] {"## 来源", "## 目标", "## 非目标", "## 约束",
                "## 验收用例", "## 完成定义"}) {
            assertThat(template).as("模板六标题在场（C5）").contains(heading);
        }
        for (String residue : INVENTORY_WORDS) {
            assertThat(template).as("模板无库存残留（C5）").doesNotContain(residue);
        }
    }

    @Test
    void procurementDraftReusesStructureWithoutCopying() throws Exception {
        String draft = read(SUBMISSION.resolve("procurement-draft.md"));
        assertThat(draft).as("采购稿未确认项如实标注（C6）").contains("待确认");
        for (String residue : INVENTORY_WORDS) {
            assertThat(draft).as("采购稿无库存口径照搬（C6）").doesNotContain(residue);
        }
    }

    // ---- helpers ----

    private static final String[] INVENTORY_WORDS = {
            "available", "on_hand", "reserved", "仓位", "批次", "在库", "预占"};

    private static String read(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** 写夹具进临时目录，返回仓库外绝对路径——spec 子命令按路径只读。 */
    private String writeFixture(String content) {
        Path file = tmp.resolve("fixture.md");
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        return file.toString();
    }

    private static JsonNode okPayload(Cli.Result result) {
        assertThat(result.exitCode()).as("解析通过应退出码 0").isEqualTo(0);
        return Cli.json(result);
    }

    private static String rejectionMessage(Cli.Result result) {
        assertThat(result.exitCode()).as("结构拒绝应退出码 1").isEqualTo(1);
        JsonNode payload = Cli.json(result);
        assertThat(payload.path("ok").asBoolean()).isFalse();
        return payload.path("error").asText();
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
