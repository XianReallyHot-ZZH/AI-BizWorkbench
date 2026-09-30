package workbench.evals.l06;

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
import java.util.concurrent.TimeUnit;

/**
 * 客户实现驱动缝（L06 口径场景，讲义附录 A1 结构翻译点③/JD2）：冻结 Python 驱动经
 * {@code sys.path.insert} + in-process {@code from flowerp import …} 驱动客户实现，Java
 * 无法同形——本件以「<b>子进程 python 一次跑完口径场景、回报原始观察值 JSON</b>」承载；
 * <b>判断与预期计算一律留在调用方</b>（独立计算语义：8−3=5 由调用方算出，不抄子进程返回）。
 *
 * <p>单方法缝 = {@link #scenario}（{@code FlowerpProbe} 同款先例；C5 已冻的 l05 三源件不动，
 * l06 包内新件自带探针面），配 {@link #resolveInterpreter}/{@link #tempDb} 两个生命周期
 * 助手与 {@link ToolFailure}。子进程超时 300s 对齐冻结 checks 的 {@code SUBPROCESS_TIMEOUT}
 * （冻结 {@code FlowerpProbe} 无超时——冻结件不动，新缝自带）。场景步序与冻结
 * {@code stock_consistency_check.py} 逐字同形（含 R1 终态：快照拍在超额草稿单创建之后）；
 * 探针脚本 BOM 处理用显式转义（源内纯 ASCII 可见，Unicode 纪律）。
 */
final class StockProbe {

    /** 工具以 rc 1 收场的失败（词面入 stderr；对照 Python SystemExit，errno/traceback 形状按 JD6 如实偏差）。 */
    public static final class ToolFailure extends RuntimeException {
        public ToolFailure(String message) {
            super(message);
        }

        public ToolFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 子进程超时（冻结 checks 的 SUBPROCESS_TIMEOUT 同值）。 */
    private static final long TIMEOUT_SECONDS = 300;

    /**
     * 口径场景片段：经 argv 收 db/target/opening/reserved，stdout 出单行 JSON 原始观察值
     * （query 三元组 / csv 该 SKU 行数与三元组 / 超额预占是否被拒 / 四表快照前后）。
     * <b>不判对错</b>——判断在调用方；本片段只报告原始观察值。步序与冻结驱动逐字同形
     * （add→receive→建单→预占→查询/导出→超额草稿→快照→预占（捕获 InsufficientStock）→快照）。
     */
    private static final String SNIPPET = """
            import csv, io, json, sys
            from pathlib import Path
            sys.path.insert(0, sys.argv[2])
            from flowerp import ERPService, ERPStore
            from flowerp.models import InsufficientStock, OrderLine

            opening, reserved = int(sys.argv[3]), int(sys.argv[4])
            sku = "L06-A"

            def out(obj):
                print(json.dumps(obj, ensure_ascii=False))

            def snapshot(store):
                return {
                    "stock": store.rows("SELECT * FROM stock ORDER BY sku"),
                    "events": store.rows("SELECT * FROM inventory_events ORDER BY rowid"),
                    "orders": store.rows("SELECT * FROM sales_orders ORDER BY id"),
                    "lines": store.rows("SELECT * FROM sales_order_lines ORDER BY rowid"),
                }

            store = ERPStore(Path(sys.argv[1]))
            service = ERPService(store)
            service.add_product(sku, "口径检查", 100)
            service.receive_stock(sku, opening, "opening")
            order = service.create_order("已有订单", [OrderLine(sku, reserved, 100)], "order-A")
            service.reserve_order(order["id"])

            query = service.product(sku)
            rows = list(csv.DictReader(io.StringIO(service.export_inventory().lstrip("\\ufeff"))))
            exported = [row for row in rows if row["sku"] == sku]

            excessive = service.create_order("超额订单", [OrderLine(sku, opening - reserved + 1, 100)], "order-B")
            # 快照拍在草稿单创建之后：建单是合法写入，被拒绝的是预占——拒绝必须零变动（R1 终态）
            before = snapshot(store)
            rejected = False
            try:
                service.reserve_order(excessive["id"])
            except InsufficientStock:
                rejected = True
            after = snapshot(store)

            out({
                "query": [query[k] for k in ("on_hand", "reserved", "available")],
                "csv_rows": len(exported),
                "csv": [int(exported[0][k]) for k in ("on_hand", "reserved", "available")] if len(exported) == 1 else None,
                "rejected": rejected,
                "before": before,
                "after": after,
            })
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StockProbe() {}

    /** 相对解释器路径按进程 cwd 绝对化（跨 cwd 纪律，FlowerpProbe.resolveInterpreter 同款）。 */
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
     * 跑一次完整口径场景，解析 stdout 单行 JSON 为观察值。子进程失败、超时（300s）或输出
     * 不可解析 → ToolFailure（调用方 rc 1 收场；词面语言绑定按 JD6 如实偏差，不入 golden）。
     */
    static JsonNode scenario(Path interpreter, Path target, Path db, int opening, int reserved) {
        List<String> argv = new ArrayList<>(List.of(
                interpreter.toString(), "-X", "utf8", "-c", SNIPPET,
                db.toString(), target.toString(), String.valueOf(opening), String.valueOf(reserved)));
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
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ToolFailure("python 子进程超时（" + TIMEOUT_SECONDS + "s）：" + interpreter);
            }
            code = process.exitValue();
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
