package workbench.cli;

import workbench.bootstrap.BootstrapCommands;

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
    }

    public static void main(String[] args) {
        if (args.length == 0 || REGISTRY.find(args[0]) == null) {
            usage(args);
        }
        CommandRegistry.CliCommand command = REGISTRY.find(args[0]);
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        try {
            System.exit(command.execute(rest));
        } catch (Args.UsageException error) {
            System.err.println("error: " + error.getMessage());
            System.err.println("usage: python -m workbench.cli <command> [--options]");
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
