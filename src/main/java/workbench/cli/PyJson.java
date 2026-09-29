package workbench.cli;

import java.util.List;
import java.util.Map;

/**
 * 与 Python {@code json.dumps(obj, ensure_ascii=False, indent=2)} 同形的 JSON 序列化器。
 *
 * <p>工作台输出契约的载体（golden 对照与错误词面逐字节保真依赖它）：键按插入序输出、
 * 缩进两空格、仅转义引号/反斜杠/控制字符、非 ASCII 保留原字。本仓库载荷只含
 * 字符串/整数/布尔/null/列表/映射，无浮点格式化歧义。
 */
public final class PyJson {

    private PyJson() {}

    public static String dumps(Object node) {
        StringBuilder out = new StringBuilder();
        write(node, 0, out);
        return out.toString();
    }

    private static void write(Object node, int depth, StringBuilder out) {
        if (node == null) {
            out.append("null");
        } else if (node instanceof Boolean bool) {
            out.append(bool);
        } else if (node instanceof Number number) {
            out.append(number);
        } else if (node instanceof String string) {
            quote(string, out);
        } else if (node instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.append(indent(depth + 1));
                quote(String.valueOf(entry.getKey()), out);
                out.append(": ");
                write(entry.getValue(), depth + 1, out);
                out.append(++index < map.size() ? ",\n" : "\n");
            }
            out.append(indent(depth)).append('}');
        } else if (node instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                out.append(indent(depth + 1));
                write(list.get(i), depth + 1, out);
                out.append(i < list.size() - 1 ? ",\n" : "\n");
            }
            out.append(indent(depth)).append(']');
        } else {
            throw new IllegalArgumentException("不可序列化的类型：" + node.getClass());
        }
    }

    private static String indent(int depth) {
        return "  ".repeat(depth);
    }

    private static void quote(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
