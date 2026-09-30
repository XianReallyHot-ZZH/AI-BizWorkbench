package workbench.cli;

import workbench.bootstrap.Args;
import workbench.bootstrap.BootstrapCommands;
import workbench.bootstrap.Command;
import workbench.bootstrap.JsonOut;
import workbench.evals.l07.QualityGate;
import workbench.execution.ExecutionCommands;
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
