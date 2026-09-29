package workbench.cli;

import workbench.bootstrap.Command;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 子命令注册表缝（镜像 Python workbench/cli.py 的 REGISTRY）：键为子命令名，值为命令。
 * 各讲能力经 register 接入本缝（L01：workbench.bootstrap.BootstrapCommands），
 * 入口分发与顶层异常边界只认缝，不认具体命令。
 */
public final class CommandRegistry {

    private final Map<String, Command> commands = new LinkedHashMap<>();

    public void register(String name, Command command) {
        commands.put(name, command);
    }

    public Command find(String name) {
        return commands.get(name);
    }

    public boolean isEmpty() {
        return commands.isEmpty();
    }

    public String[] names() {
        return commands.keySet().toArray(String[]::new);
    }
}
