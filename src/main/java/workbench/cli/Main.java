package workbench.cli;

/**
 * Workbench CLI 入口（镜像 Python workbench/cli.py 的注册表与分发缝）。
 *
 * <p>L01 骨架（commit 1）：注册表为空——任何子命令按 argparse 同形报 invalid choice，
 * 退出码 2，即起始红的预期形态（目标能力缺失，非环境错误）。五个合同命令在实现提交
 * 经 {@code BootstrapCommands.register(REGISTRY)} 接入本缝。
 */
public final class Main {

    public static void main(String[] args) {
        String name = args.length > 0 ? args[0] : "<command>";
        System.err.printf("invalid choice: \"%s\"%n", name);
        System.exit(2);
    }
}
