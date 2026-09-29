package workbench.bootstrap;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 命令输出与错误契约（镜像 workbench/bootstrap.py 的 _emit/_fail）：所有命令出口恒为
 * JSON；失败返回退出码 1，顶层异常边界也保持同一 JSON 契约不裸栈。
 */
public final class JsonOut {

    private JsonOut() {}

    /** 按键对偶构建保持插入序的载荷（与 Python 字面量键序同形）。 */
    public static LinkedHashMap<String, Object> ordered(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("键值必须成对");
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            out.put((String) keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    public static void emit(Map<String, Object> payload) {
        byte[] bytes = (PyJson.dumps(payload) + "\n").getBytes(StandardCharsets.UTF_8);
        PrintStream stdout = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        stdout.write(bytes, 0, bytes.length);
        stdout.flush();
    }

    /** 镜像 _fail：{"ok": false, "error": …, "flowerp_connected": false, **extra}，退出码 1。 */
    public static int fail(String error, Map<String, Object> extra) {
        LinkedHashMap<String, Object> payload = ordered(
                "ok", false, "error", error, "flowerp_connected", false);
        payload.putAll(extra);
        emit(payload);
        return 1;
    }

    public static int fail(String error) {
        return fail(error, Map.of());
    }
}
