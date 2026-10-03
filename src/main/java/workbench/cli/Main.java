package workbench.cli;

import workbench.bootstrap.Args;
import workbench.bootstrap.BootstrapCommands;
import workbench.bootstrap.Command;
import workbench.bootstrap.JsonOut;
import workbench.delivery.DeliveryServe;
import workbench.delivery.FeedbackCli;
import workbench.evolution.EvolutionStore;
import workbench.evals.l07.QualityGate;
import workbench.evals.l08.CiEvidence;
import workbench.execution.ExecutionCommands;
import workbench.graph.GraphRunner;
import workbench.learning.LearningCli;
import workbench.loop.LoopRunner;
import workbench.repair.RepairMapper;
import workbench.spec.SpecCommands;

/**
 * Workbench CLI 入口：注册表 + 分发缝 + 顶层异常边界（镜像 Python workbench/cli.py）。
 *
 * <p>L01 五合同命令经 {@code BootstrapCommands.register} 接入本缝，后续讲次同形扩展；
 * 分发与异常边界只认缝，不认具体命令。argparse 同形的用法错误（未知/缺参）走
 * stderr + 退出码 2；基础设施失败保持 JSON 错误契约，不裸栈。
 */
public final class Main {

    static final CommandRegistry REGISTRY = new CommandRegistry();

    static {
        BootstrapCommands.register(REGISTRY);
        // L03：spec 命令经同一条注册缝接入（适配器在 workbench.spec.SpecCommands，
        // 镜像 cli.py 的 L03 注册段：只加注册调用，缝位置不变）。
        SpecCommands.register(REGISTRY);
        // L04：受控执行三命令经同一条缝注册（适配器在 workbench.execution.ExecutionCommands，
        // 镜像 cli.py 的 L04 注册段：复用 bootstrap 存储入口）。
        ExecutionCommands.register(REGISTRY);
        // L07：本地护栏处理器经同一条缝注册（讲义 D2——workbenchIncrement「提交前本地护栏」
        // 即工作台命令；适配器在 workbench.evals.l07.QualityGate，只加注册调用，缝位置不变）。
        REGISTRY.register("quality-gate", QualityGate::execute);
        // L08：证据信封件经同一条缝注册（讲义 D3——workbenchIncrement「远程复验与证据信封」
        // 即工作台命令，L07 D2 先例；适配器在 workbench.evals.l08.CiEvidence，只加注册调用）。
        REGISTRY.register("ci-evidence", CiEvidence::execute);
        // L09：严格修复映射器经同一条缝注册（讲义 D1——workbenchIncrement「报告到 Repair Task
        // 的确定性映射」即工作台命令，L07 D2 / L08 D3 先例；适配器在 workbench.repair.RepairMapper，
        // 只加注册调用。上游 agent/repair.py 的 agent 模块家族对应落点——L10 修复 Loop 前奏）。
        REGISTRY.register("repair-map", RepairMapper::execute);
        // L10：修复 Loop 控制器经同一条缝注册（讲义 D1——workbenchIncrement「带预算与停止条件
        // 的修复 Loop」即工作台命令，L07 D2 / L08 D3 / L09 D1 先例；适配器在 workbench.loop.LoopRunner，
        // 只加注册调用。上游 agent/loop.py 的 agent 家族第二件——上游验收命令
        // python -m agent.loop --max-rounds 3 的本仓对应面）。
        REGISTRY.register("loop-run", LoopRunner::execute);
        // L12：显式状态图控制器经同一条缝注册（讲义 D1——workbenchIncrement「显式状态图、
        // 回退边和具名人审」即工作台命令，L07 D2 / L08 D3 / L09 D1 / L10 D1 先例；适配器在
        // workbench.graph.GraphRunner。上游 agent/ 家族第四件（repair→loop→schedule→graph
        // 四件收齐）——上游验收命令 python -X utf8 -m agent.graph --max-rounds 3 的本仓对应面，
        // 退出码 0 completed / 3 awaiting_human_review（正常等待不是失败）/ 2 其余）。
        REGISTRY.register("graph-run", GraphRunner::execute);
        // L13：交付任务 API 常驻服务经同一条缝注册（讲义 D2——workbenchIncrement
        // 「可追溯 Task API 与异步状态」即工作台命令，L07 D2 起五连先例；适配器在
        // workbench.delivery.DeliveryServe——上游 workbench/workbench_server.py serve()
        // 的对应面。operate 阶段首讲：执行链路封装为 202 + Task ID 的可提交、可查询
        // 资源，全绿停在 review 等具名人审）。
        REGISTRY.register("delivery-serve", DeliveryServe::execute);
        // L15：反馈治理命令族经同一条缝注册（讲义 D5——REGISTRY +8 件 + spec 偏差
        // feedback 一件：记忆系统七件沿 S01 CLI 合同直承〔workbench-learn-*，S01
        // 冻结面 argparse 同名同义〕+ evolution 一件五子命令〔上游 argparse 单入口
        // 同形〕+ feedback 一件三子命令〔上游 feedback.py main 同形，合同验收 1
        // 「原始反馈先审核」的可观测面〕。适配器在 workbench.learning.LearningCli /
        // workbench.evolution.EvolutionStore / workbench.delivery.FeedbackCli）。
        REGISTRY.register("workbench-learn-create", LearningCli::create);
        REGISTRY.register("workbench-learn-govern", LearningCli::govern);
        REGISTRY.register("workbench-learn-recall", LearningCli::recall);
        REGISTRY.register("workbench-learn-bind", LearningCli::bind);
        REGISTRY.register("workbench-learn-run", LearningCli::run);
        REGISTRY.register("workbench-learn-finish", LearningCli::finish);
        REGISTRY.register("workbench-learn-show", LearningCli::show);
        REGISTRY.register("evolution", EvolutionStore::execute);
        REGISTRY.register("feedback", FeedbackCli::execute);
    }

    public static void main(String[] args) {
        if (args.length == 0 || REGISTRY.find(args[0]) == null) {
            usage(args);
        }
        Command command = REGISTRY.find(args[0]);
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        try {
            System.exit(command.execute(rest));
        } catch (Args.UsageException error) {
            System.err.println("error: " + error.getMessage());
            System.err.println("usage: workbench <command> [--options]");
            System.exit(2);
        } catch (Exception error) {
            // 基础设施失败也保持 JSON 错误契约，不裸 traceback
            JsonOut.emit(JsonOut.ordered("ok", false, "flowerp_connected", false,
                    "error", "内部错误：%s: %s".formatted(
                            error.getClass().getSimpleName(), error.getMessage())));
            System.exit(1);
        }
    }

    private static void usage(String[] args) {
        String name = args.length > 0 ? args[0] : "<command>";
        System.err.printf("invalid choice: \"%s\"%n", name);
        System.exit(2);
    }
}
