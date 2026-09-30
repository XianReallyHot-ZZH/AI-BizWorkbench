package workbench.evals.l05;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 客户实现驱动缝（L05 特有结构翻译点，讲义附录 A1）：冻结 Python 工具经
 * {@code sys.path.insert} + in-process {@code from flowerp import …} 驱动客户实现，
 * Java 无法同形——本件以「<b>子进程 python 跑客户原语、回报原始观察值 JSON</b>」承载；
 * <b>预期计算与断言一律留在调用方</b>（独立计算语义：28 由 Java 用 20+8 算出，不抄子进程返回）。
 *
 * <p>接口 = {@link #probe} 一个方法加 {@link #resolveInterpreter}/{@link #tempDb} 两个
 * 生命周期助手；两个工具 main 都是调用方（真缝）。实现藏解释器绝对化（跨 cwd 纪律）、
 * 进程启动、UTF-8 与 JSON 解析、临时库清理。工具失败 = 自身 rc 非零，不是执行记录
 * 落账语义——故不复用 {@code execution/ProcessRunner}（讲义附录 A6.2 JD1）。
 */
final class FlowerpProbe {

    /** 工具以 rc 1 收场的失败（词面入 stderr；对照 Python SystemExit，errno/traceback 形状按 JD6 如实偏差）。 */
    public static final class ToolFailure extends RuntimeException {
        public ToolFailure(String message) {
            super(message);
        }

        public ToolFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 客户原语片段：经 argv 收 db/target/op，stdout 出单行 JSON 观察。<b>不判对错</b>——
     * 判断在 Java 侧；本片段只报告原始观察值（receive 的 ValueError → rejected:true 等）。
     * 与冻结驱动同款查询面（events/snapshot 的列与排序逐字同文）。
     */
    private static final String SNIPPET = """
            import json, sys
            from pathlib import Path
            sys.path.insert(0, sys.argv[2])
            from flowerp import ERPService, ERPStore
            svc = ERPService(ERPStore(Path(sys.argv[1])))
            op = sys.argv[3]

            def out(obj):
                print(json.dumps(obj, ensure_ascii=False))

            if op == "setup":
                svc.add_product("SKU-A", "验收商品", 1000, 2)
                out({"ok": True})
            elif op == "receive":
                key, qty = sys.argv[4], int(sys.argv[5])
                try:
                    r = svc.receive_stock("SKU-A", qty, key)
                    out({"rejected": False, "on_hand": r["on_hand"],
                         "idempotent_replay": bool(r["idempotent_replay"])})
                except ValueError:
                    out({"rejected": True})
            elif op == "product":
                out({"on_hand": svc.product("SKU-A")["on_hand"]})
            elif op == "events":
                rows = svc.store.rows(
                    "SELECT event_key,quantity,event_type FROM inventory_events WHERE event_key=?",
                    (sys.argv[4],))
                out({"count": len(rows),
                     "quantity": rows[0]["quantity"] if rows else None,
                     "event_type": rows[0]["event_type"] if rows else None})
            elif op == "events_total":
                rows = svc.store.rows(
                    "SELECT event_key FROM inventory_events WHERE sku=?", ("SKU-A",))
                out({"count": len(rows)})
            elif op == "snapshot":
                events = svc.store.rows(
                    "SELECT event_key,sku,quantity,reserved_delta,event_type,reference "
                    "FROM inventory_events WHERE sku=? ORDER BY rowid", ("SKU-A",))
                stock = svc.store.rows(
                    "SELECT on_hand,reserved FROM stock WHERE sku=?", ("SKU-A",))
                out({"events": [list(r.values()) for r in events],
                     "stock": [list(r.values()) for r in stock]})
            else:
                raise SystemExit("unknown op: " + op)
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FlowerpProbe() {}

    /** 相对解释器路径按进程 cwd 绝对化（eval/执行器命令跨 cwd 一律绝对——L04 起始纪律移植）。 */
    static Path resolveInterpreter(String python) {
        return Path.of(python).toAbsolutePath().normalize();
    }

    /** 临时库（自建库、天然干净数据开始；close 递归清理——Python TemporaryDirectory 同形）。 */
    static final class TempDb implements AutoCloseable {
        private final Path dir;
        final Path db;

        private TempDb(Path dir) {
            this.dir = dir;
            this.db = dir.resolve("scenario.db");
        }

        @Override
        public void close() {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                });
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }

    static TempDb tempDb(String prefix) {
        try {
            return new TempDb(Files.createTempDirectory(prefix));
        } catch (IOException error) {
            throw new ToolFailure("临时目录创建失败（" + prefix + "）", error);
        }
    }

    /**
     * 跑一个客户原语操作，解析 stdout 单行 JSON 为观察值。子进程失败或输出不可解析 →
     * ToolFailure（调用方 rc 1 收场；Python 侧对应 traceback 崩溃，形状语言绑定按
     * JD6 如实偏差、不入 golden）。
     */
    static JsonNode probe(Path interpreter, Path target, Path db, String... opArgs) {
        List<String> argv = new ArrayList<>(List.of(
                interpreter.toString(), "-X", "utf8", "-c", SNIPPET,
                db.toString(), target.toString()));
        argv.addAll(List.of(opArgs));
        Process process;
        try {
            process = new ProcessBuilder(argv).start();
        } catch (IOException error) {
            throw new ToolFailure("python 启动失败（" + interpreter + "）", error);
        }
        String stdout;
        String stderr;
        int code;
        try {
            stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            code = process.waitFor();
        } catch (IOException error) {
            throw new ToolFailure("python 输出读取失败（" + interpreter + "）", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("python 等待被中断", error);
        }
        if (code != 0) {
            throw new ToolFailure("python 子进程失败 rc=" + code + "：" + stderr.strip());
        }
        try {
            return MAPPER.readTree(stdout);
        } catch (IOException error) {
            throw new ToolFailure("python 输出不可解析：" + stdout.strip()
                    + "；stderr：" + stderr.strip(), error);
        }
    }
}
