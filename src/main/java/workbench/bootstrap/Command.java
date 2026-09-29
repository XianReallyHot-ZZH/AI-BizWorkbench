package workbench.bootstrap;

/**
 * 命令函数式接口（镜像 Python bootstrap 中 handler 的 duck-type 形状）：
 * cli 包的注册表与本包命令都认它，保证 cli→bootstrap 单向依赖。
 */
@FunctionalInterface
public interface Command {
    /** 执行命令；argv 为子命令名之后的参数。返回进程退出码。 */
    int execute(String[] argv) throws Exception;
}
