package workbench.schedule;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L11 声明检查器直调机检：讲义 docs/lessons/L11-独立写集并行调度.md §2 C3/C5
 * （schedule 库核公开方法直调缝——spec 已具名确认的两个新高缝之一；schedule 无 CLI：
 * 上游 agent/schedule.py 即纯库件，讲义 D1）。映射源 =
 * vendors/CodexFDE/docs/courses/L11/examples/schedule_lab.py 十模式 + 上游
 * {@code agent/schedule.py} 判定面，只读对照。
 *
 * <p>十模式 observed 对照上游 EXPECTED 表（仅 independent / read-only /
 * undeclared-read 为 allowed）；三交集 / 规范化 / 资源 / 版本面逐项机检。
 * <b>undeclared-read 教学点</b>（上游 schedule_lab judgment 同义）：声明层面 allowed
 * 不是安全依据——补报真实读取后即 rejected（「声明通过 ≠ 执行安全」的机检承载）。
 *
 * <p>本类与实现同 commit（直调面编译依赖实现类；起始红由
 * {@code L11PurchaseChecksContractTest} main 缝先行——L06-java「编译不绑实现类」
 * 教训承袭，讲义 §3 步骤 2 头注在案）。
 *
 * <pre>
 * 用例 → 合同映射：
 * tenModesMatchUpstreamExpectedTable       C5 十模式 observed 逐项对照
 * undeclaredReadAllowedIsNotSafe           C3/C9 声明通过 ≠ 执行安全（补报读取即拒）
 * writeWriteReadWriteBothDirections        C3 三交集（写写 + 写读双向；读读共享允许）
 * directoryCoverageReportsDeeperPath       C3 目录覆盖 + 共享项取更深路径
 * pathAliasesCollapseToSameScope           C3 别名（反斜杠/大小写/./前缀）
 * invalidPathsAreRejected                  C3 越界（绝对路径/../通配符/盘符/空白）
 * duplicateAndBlankNamesRejected           C3 名称非空唯一
 * sharedResourcesReportedWithPrefix        C3 resource: 前缀冲突
 * inputVersionMustBeUniform                C3 版本统一 + 结果传递
 * readOnlySharedAllowed                    C3 读读共享 parallel:true
 * </pre>
 */
class L11ScheduleContractTest {

    // ---- 十模式（上游 SCENARIOS/EXPECTED 表翻译） --------------------------------

    @Test
    void tenModesMatchUpstreamExpectedTable() {
        // allowed 三模式（上游 EXPECTED：independent / read-only / undeclared-read）
        assertThat(observed(List.of(
                new Schedule.Subtask("tests", List.of("tests/test_l11_purchase.py"),
                        List.of("flowerp/service.py")),
                new Schedule.Subtask("risk", List.of(),
                        List.of("flowerp/service.py", "flowerp/store.py")))))
                .isEqualTo("allowed");
        assertThat(observed(List.of(
                new Schedule.Subtask("coverage", List.of(), List.of("flowerp/service.py")),
                new Schedule.Subtask("risk", List.of(), List.of("flowerp/service.py")))))
                .isEqualTo("allowed");
        assertThat(observed(List.of(
                new Schedule.Subtask("implementation", List.of("flowerp/service.py")),
                new Schedule.Subtask("tests", List.of("tests/test_l11_purchase.py")))))
                .isEqualTo("allowed");
        // rejected 七模式
        assertThat(observed(List.of(
                new Schedule.Subtask("a", List.of("flowerp/service.py")),
                new Schedule.Subtask("b", List.of("flowerp/service.py")))))
                .isEqualTo("rejected");
        assertThat(observed(List.of(
                new Schedule.Subtask("implementation", List.of("flowerp/service.py")),
                new Schedule.Subtask("tests", List.of("tests/test_l11_purchase.py"),
                        List.of("flowerp/service.py")))))
                .isEqualTo("rejected");
        assertThat(observed(List.of(
                new Schedule.Subtask("implementation", List.of("flowerp/")),
                new Schedule.Subtask("risk", List.of(), List.of("flowerp/service.py")))))
                .isEqualTo("rejected");
        assertThat(observed(List.of(
                new Schedule.Subtask("a", List.of("./flowerp/service.py")),
                new Schedule.Subtask("b", List.of("FLOWERP\\SERVICE.PY")))))
                .isEqualTo("rejected");
        assertThat(observed(List.of(
                new Schedule.Subtask("a", List.of("tests/test_a.py", ".runtime/eval.json")),
                new Schedule.Subtask("b", List.of("tests/test_b.py", ".runtime/eval.json")))))
                .isEqualTo("rejected");
        assertThat(observed(List.of(
                new Schedule.Subtask("a", List.of("../outside.py")))))
                .isEqualTo("rejected");
        assertThat(observed(List.of(
                new Schedule.Subtask("same", List.of()),
                new Schedule.Subtask("same", List.of()))))
                .isEqualTo("rejected");
    }

    /** observed 判定（上游 schedule_lab observe() 同义：安全 → allowed，违规 → rejected）。 */
    private static String observed(List<Schedule.Subtask> tasks) {
        try {
            Schedule.assertParallelSafe(tasks);
            return "allowed";
        } catch (Schedule.ScheduleViolation expected) {
            return "rejected";
        }
    }

    @Test
    void undeclaredReadAllowedIsNotSafe() {
        List<Schedule.Subtask> undeclared = List.of(
                new Schedule.Subtask("implementation", List.of("flowerp/service.py")),
                new Schedule.Subtask("tests", List.of("tests/test_l11_purchase.py")));
        // 声明层面 allowed（漏报读取）——上游 judgment "False independence" 的前置事实
        assertThat(Schedule.conflictPairs(undeclared)).isEmpty();
        // 补报真实读取（上游 SUBMISSION 修订同形）→ 读写依赖拒绝：
        // 「声明通过 ≠ 执行安全」，判断在整合层
        assertThatThrownBy(() -> Schedule.assertParallelSafe(List.of(
                new Schedule.Subtask("implementation", List.of("flowerp/service.py")),
                new Schedule.Subtask("tests", List.of("tests/test_l11_purchase.py"),
                        List.of("flowerp/service.py")))))
                .isInstanceOf(Schedule.ScheduleViolation.class)
                .hasMessageContaining("共享写集或读写依赖不得并行")
                .hasMessageContaining("implementation×tests→flowerp/service.py");
    }

    // ---- 三交集与判定面机检 -------------------------------------------------------

    @Test
    void writeWriteReadWriteBothDirections() {
        // 写写：W甲∩W乙
        assertThatThrownBy(() -> Schedule.assertParallelSafe(List.of(
                new Schedule.Subtask("impl", List.of("flowerp/service.py")),
                new Schedule.Subtask("eval", List.of("flowerp/service.py")))))
                .hasMessageContaining("共享写集");
        // 写读正向：W甲∩R乙（甲改接口，乙按接口写测试）
        assertThatThrownBy(() -> Schedule.assertParallelSafe(List.of(
                new Schedule.Subtask("api-editor", List.of("flowerp/api.py")),
                new Schedule.Subtask("tests", List.of("tests/test_api.py"),
                        List.of("flowerp/api.py")))))
                .hasMessageContaining("读写依赖不得并行");
        // 写读反向：W乙∩R甲（乙改验收规则，甲按旧规则实现）
        assertThatThrownBy(() -> Schedule.assertParallelSafe(List.of(
                new Schedule.Subtask("impl", List.of("src/thing.py"),
                        List.of("docs/spec.md")),
                new Schedule.Subtask("rule-editor", List.of("docs/spec.md")))))
                .hasMessageContaining("读写依赖不得并行");
        // 读读共享允许：R甲∩R乙 非空不构成冲突（independent 模式已证，此处定向复核）
        Map<String, Object> ok = Schedule.assertParallelSafe(List.of(
                new Schedule.Subtask("coverage", List.of("cov-report.md"),
                        List.of("flowerp/service.py")),
                new Schedule.Subtask("risk", List.of("risk-report.md"),
                        List.of("flowerp/service.py"))));
        assertThat(ok.get("parallel")).isEqualTo(true);
    }

    @Test
    void directoryCoverageReportsDeeperPath() {
        // 写目录覆盖读文件 + 共享项取更深路径（上游 max(key=len(parts)) 同形）
        List<Schedule.Conflict> conflicts = Schedule.conflictPairs(List.of(
                new Schedule.Subtask("implementation", List.of("flowerp/")),
                new Schedule.Subtask("risk", List.of(), List.of("flowerp/service.py"))));
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).shared()).containsExactly("flowerp/service.py");
    }

    @Test
    void pathAliasesCollapseToSameScope() {
        // 反斜杠 + 大小写折叠 + ./ 前缀 → 同一 scope（上游 _scope 逐句）
        assertThatThrownBy(() -> Schedule.assertParallelSafe(List.of(
                new Schedule.Subtask("a", List.of("./flowerp/service.py")),
                new Schedule.Subtask("b", List.of("FLOWERP\\SERVICE.PY")))))
                .hasMessageContaining("a×b→flowerp/service.py");
        // 尾斜杠目录 = 无尾斜杠目录
        assertThatThrownBy(() -> Schedule.assertParallelSafe(List.of(
                new Schedule.Subtask("a", List.of("flowerp/")),
                new Schedule.Subtask("b", List.of("FLOWERP")))))
                .hasMessageContaining("a×b→flowerp");
    }

    @Test
    void invalidPathsAreRejected() {
        for (String bad : new String[]{"../outside.py", "/etc/passwd", "a*b.py", "a?b.py",
                "a[b.py", "C:/repo/x.py", "   ", "flowerp/../outside.py"}) {
            assertThatThrownBy(() -> Schedule.assertParallelSafe(
                            List.of(new Schedule.Subtask("a", List.of(bad)))))
                    .as("越界路径应拒绝：%s", bad)
                    .hasMessageContaining("读写集必须是明确的仓库相对文件或目录");
        }
    }

    @Test
    void duplicateAndBlankNamesRejected() {
        assertThatThrownBy(() -> Schedule.assertParallelSafe(List.of(
                new Schedule.Subtask("same", List.of("a.py")),
                new Schedule.Subtask("same", List.of("b.py")))))
                .hasMessageContaining("子任务名称必须非空且唯一");
        assertThatThrownBy(() -> Schedule.assertParallelSafe(List.of(
                new Schedule.Subtask("  ", List.of("a.py")))))
                .hasMessageContaining("子任务名称必须非空且唯一");
    }

    @Test
    void sharedResourcesReportedWithPrefix() {
        List<Schedule.Conflict> conflicts = Schedule.conflictPairs(List.of(
                new Schedule.Subtask("a", List.of("tests/a.py"), List.of(),
                        List.of("SQLITE-DB", "shared-cache"), ""),
                new Schedule.Subtask("b", List.of("tests/b.py"), List.of(),
                        List.of("sqlite-db", "port-8080", "SHARED-CACHE"), "")));
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).shared()).containsExactly("resource:shared-cache", "resource:sqlite-db");
        assertThatThrownBy(() -> Schedule.assertParallelSafe(List.of(
                new Schedule.Subtask("a", List.of(), List.of(), List.of("DB"), ""),
                new Schedule.Subtask("b", List.of(), List.of(), List.of("DB"), ""))))
                .hasMessageContaining("resource:db");
        // 资源名护栏：空白拒绝
        assertThatThrownBy(() -> Schedule.assertParallelSafe(List.of(
                new Schedule.Subtask("a", List.of(), List.of(), List.of("bad name"), ""))))
                .hasMessageContaining("共享资源名称必须是非空且不含空白的标识");
    }

    @Test
    void inputVersionMustBeUniform() {
        assertThatThrownBy(() -> Schedule.assertParallelSafe(List.of(
                new Schedule.Subtask("a", List.of("a.py"), List.of(), List.of(), "v1"),
                new Schedule.Subtask("b", List.of("b.py"), List.of(), List.of(), "v2"))))
                .hasMessageContaining("并行子任务必须使用同一个固定输入版本");
        // 统一版本传递（空版本不参与——上游 strip 后过滤同形）
        Map<String, Object> ok = Schedule.assertParallelSafe(List.of(
                new Schedule.Subtask("a", List.of("a.py"), List.of(), List.of(), "v1"),
                new Schedule.Subtask("b", List.of("b.py"))));
        assertThat(ok.get("input_version")).isEqualTo("v1");
        Map<String, Object> none = Schedule.assertParallelSafe(List.of(
                new Schedule.Subtask("a", List.of("a.py"))));
        assertThat(none.get("input_version")).isEqualTo("");
    }

    @Test
    void readOnlySharedAllowed() {
        Map<String, Object> ok = Schedule.assertParallelSafe(List.of(
                new Schedule.Subtask("spec", List.of(), List.of("FDE_SPEC.md")),
                new Schedule.Subtask("risk", List.of(), List.of("AGENTS.md"))));
        assertThat(ok.get("parallel")).isEqualTo(true);
        assertThat(ok.get("tasks")).isEqualTo(List.of("spec", "risk"));
        assertThat(ok.get("conflicts")).isEqualTo(List.of());
    }
}
