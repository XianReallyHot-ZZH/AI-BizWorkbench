package workbench.cli;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 子命令注册表缝（镜像 Python workbench/cli.py 的 REGISTRY）：键为子命令名，值为命令。
 * 各讲能力经 register 接入本缝（L01：workbench.bootstrap.BootstrapCommands），
 * 入口分发与顶层异常边界只认缝，不认具体命令。
 */
public final class CommandRegistry {

    @FunctionalInterface
    public interface CliCommand {
        /** 执行命令；argv 为子命令名之后的参数。返回进程退出码。 */
        int execute(String[] argv) throws Exception;
    }

    private final Map<String, CliCommand> commands = new LinkedHashMap<>();

    public void register(String name, CliCommand command) {
        commands.put(name, command);
    }

    public CliCommand find(String name) {
        return commands.get(name);
    }

    public boolean isEmpty() {
        return commands.isEmpty();
    }

    public String[] names() {
        return commands.keySet().toArray(String[]::new);
    }
}
