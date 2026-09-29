package workbench.bootstrap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    private final List<String> positionals = new ArrayList<>();

    /**
     * @param argv         子命令名之后的参数
     * @param valueFlags   取值选项名（如 --runtime-dir）
     * @param booleanFlags 开关选项名（如 --require-red-green-evidence）
     */
    public Args(String[] argv, Set<String> valueFlags, Set<String> booleanFlags) {
        this(argv, valueFlags, booleanFlags, List.of());
    }

    /**
     * @param positionalNames 位置参数名（按序；L03 spec 的 spec_path，移植注记 JD2）。
     *     词面沿 argparse 同形：缺失报 ``the following arguments are required: <名>``，
     *     多余报 ``unrecognized arguments: <多余项>``。两参构造器行为零变化（回归保证：
     *     既有命令不收位置参数，非 -- 令牌维持原即时拒绝词面）。
     */
    public Args(String[] argv, Set<String> valueFlags, Set<String> booleanFlags,
                List<String> positionalNames) {
        for (int i = 0; i < argv.length; i++) {
            String token = argv[i];
            if (!token.startsWith("--")) {
                if (positionalNames.isEmpty()) {
                    throw new UsageException("unrecognized argument: " + token);
                }
                positionals.add(token);
                continue;
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
        if (positionals.size() < positionalNames.size()) {
            throw new UsageException("the following arguments are required: "
                    + positionalNames.get(positionals.size()));
        }
        if (positionals.size() > positionalNames.size()) {
            throw new UsageException("unrecognized arguments: "
                    + String.join(" ", positionals.subList(positionalNames.size(), positionals.size())));
        }
    }

    /** 按序取位置参数（0 起）。 */
    public String positional(int index) {
        return positionals.get(index);
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
