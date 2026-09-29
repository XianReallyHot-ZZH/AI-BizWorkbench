package workbench.cli;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 手写 argparse 同形参数解析（朴素口径，ADR-0006 附录 A2）：--flag value 与
 * --flag=value 两种形状；同名选项后值覆盖前值（argparse 同形）；未知选项/缺必需项
 * 报用法错误（stderr，退出码 2，不出 JSON——与 argparse 行为同形）。
 */
public final class Args {

    /** 用法错误：入口捕获后打 stderr 并以退出码 2 结束。 */
    public static final class UsageException extends RuntimeException {
        public UsageException(String message) {
            super(message);
        }
    }

    private final Map<String, String> values = new HashMap<>();
    private final Set<String> present = new java.util.HashSet<>();

    /**
     * @param argv         子命令名之后的参数
     * @param valueFlags   取值选项名（如 --runtime-dir）
     * @param booleanFlags 开关选项名（如 --require-red-green-evidence）
     */
    public Args(String[] argv, Set<String> valueFlags, Set<String> booleanFlags) {
        for (int i = 0; i < argv.length; i++) {
            String token = argv[i];
            if (!token.startsWith("--")) {
                throw new UsageException("unrecognized argument: " + token);
            }
            String name = token;
            String value = null;
            int eq = token.indexOf('=');
            if (eq >= 0) {
                name = token.substring(0, eq);
                value = token.substring(eq + 1);
            }
            if (booleanFlags.contains(name)) {
                if (value != null) {
                    throw new UsageException("argument " + name + ": ignored explicit argument");
                }
                present.add(name);
            } else if (valueFlags.contains(name)) {
                if (value == null) {
                    if (i + 1 >= argv.length) {
                        throw new UsageException("argument " + name + ": expected one argument");
                    }
                    value = argv[++i];
                }
                values.put(name, value);
            } else {
                throw new UsageException("unrecognized argument: " + name);
            }
        }
    }

    public String require(String name) {
        String value = values.get(name);
        if (value == null) {
            throw new UsageException("the following arguments are required: " + name);
        }
        return value;
    }

    /** 选项是否出现过（含取值选项）。 */
    public boolean has(String name) {
        return present.contains(name) || values.containsKey(name);
    }

    public String optional(String name, String defaultValue) {
        return values.getOrDefault(name, defaultValue);
    }

    public int requireInt(String name) {
        String value = require(name);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new UsageException("argument " + name + ": invalid int value: '" + value + "'");
        }
    }

    public boolean flag(String name) {
        return present.contains(name);
    }
}
