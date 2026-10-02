import workbench.schedule.Schedule;
import java.util.List;

// L11 真实并行前的声明检查（讲义 C7 / D6；上游 SUBMISSION 第 5 项：
// 一次冲突声明被拒绝 + 修订后的安排）。逐字对账件：stdout 两段词面。
// ① 冲突形态（故意提交）：甲(impl)写 flowerp/service.py，乙(risk)读同一文件
//    → 读写依赖拒绝（W甲∩R乙 非空）
var conflictLeft = new Schedule.Subtask("impl", List.of("flowerp/service.py"));
var conflictRight = new Schedule.Subtask("risk", List.of(), List.of("flowerp/service.py"));
try {
    Schedule.assertParallelSafe(List.of(conflictLeft, conflictRight));
    System.out.println("UNEXPECTED-ALLOWED");
} catch (RuntimeException e) {
    System.out.println("declared-check rejected: " + e.getMessage());
}
// ② 修订安排：甲(tests)只写独立测试文件、读固定实现；乙(risk)只读同一实现
//    → 声明通过（读读共享允许；写集互斥、读写无交叉）
var revised = Schedule.assertParallelSafe(List.of(
        new Schedule.Subtask("tests", List.of("tests/test_l11_purchase.py"),
                List.of("flowerp/service.py", "flowerp/store.py", "flowerp/models.py")),
        new Schedule.Subtask("risk", List.of(),
                List.of("flowerp/service.py", "flowerp/store.py"))));
System.out.println("declared-check revised: " + revised);
/exit
