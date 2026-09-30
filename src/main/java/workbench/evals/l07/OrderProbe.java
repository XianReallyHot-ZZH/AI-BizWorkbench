package workbench.evals.l07;

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
 * 客户实现驱动缝（L07 订单面场景；l06 {@code StockProbe} 同款先例，本包自带探针面——
 * 不动 l05/l06 已验收源件）。子进程 python 一次跑完订单场景、回报原始观察值 JSON；
 * <b>判断与预期计算一律留在调用方</b>（独立计算语义：2×3000+3×2000=12000 由 Java 算出，
 * 不抄子进程返回——上游 order_contract_checks.py「independent integer expectations」同义）。
 *
 * <p>探针脚本对客户门面（{@code ERPService.create_order}）只做三件事：合法两行建草稿、
 * 非法三态（数量零/负、第二行商品不存在）试建、迁移输入建单。快照对比两档（上游
 * order_contract_checks 同形）：拒绝场景比 {@code sales_orders}/{@code sales_order_lines}/
 * {@code stock} 三表（拒绝必须零写入）；草稿不预占只比库存表（建单合法写入订单头与明细，
 * 三表对比会把合法建单误判为违规——首采绿腿抓获的探针语义缺陷，失败现场保留见证据账）。
 * 拒绝与不预占是观察到的客户行为，不是探针的断言。子进程超时 300s 对齐 l06
 * {@code SUBPROCESS_TIMEOUT}。
 */
final class OrderProbe {

    /** 工具以 rc 1 收场的失败（l06 ToolFailure 同形；词面入 stderr）。 */
    static final class ToolFailure extends RuntimeException {
        ToolFailure(String message) {
            super(message);
        }

        ToolFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 子进程超时（l06 SUBPROCESS_TIMEOUT 同值）。 */
    private static final long TIMEOUT_SECONDS = 300;

    /**
     * 订单场景片段：argv = [db, 客户树, scenario, q1, p1, q2, p2]；stdout 出单行 JSON
     * 原始观察值（scenario=draft|transfer：status/total/line_totals/三表不变；
     * scenario=reject：三用例的异常类型名 + 三表前后是否相等）。<b>不判对错</b>——
     * 判断在调用方。CJK 字面经 -X utf8 子进程传递（L04/L05 先例背书）。
     */
    private static final String SNIPPET = """
            import json, sys
            from pathlib import Path
            sys.path.insert(0, sys.argv[2])
            from flowerp import ERPService, ERPStore
            from flowerp.models import NotFound, OrderLine

            scenario = sys.argv[3]
            q1, p1, q2, p2 = (int(v) for v in sys.argv[4:8])

            def out(obj):
                print(json.dumps(obj, ensure_ascii=False))

            def tables(store):
                return {
                    "orders": store.rows("SELECT * FROM sales_orders ORDER BY id"),
                    "lines": store.rows("SELECT * FROM sales_order_lines ORDER BY rowid"),
                    "stock": store.rows("SELECT * FROM stock ORDER BY sku"),
                }

            store = ERPStore(Path(sys.argv[1]))
            service = ERPService(store)
            service.add_product("L07-A", "订单商品 A", p1)
            service.add_product("L07-B", "订单商品 B", p2)

            if scenario == "reject":
                cases = [
                    ("L07-ZERO", [OrderLine("L07-A", 0, p1)], "ValueError"),
                    ("L07-NEGATIVE", [OrderLine("L07-A", -1, p1)], "ValueError"),
                    ("L07-MISSING", [OrderLine("L07-A", 1, p1), OrderLine("L07-MISSING", 1, p2)], "NotFound"),
                ]
                results = []
                for oid, lines, expected in cases:
                    before = tables(store)
                    exception = None
                    try:
                        service.create_order("课堂客户", lines, oid)
                    except Exception as exc:
                        exception = type(exc).__name__
                    results.append({"id": oid, "expected": expected, "exception": exception,
                                    "unchanged": tables(store) == before})
                out({"scenario": "reject", "cases": results})
            else:
                customer = "课堂客户" if scenario == "draft" else "迁移客户"
                oid = "L07-AMOUNT" if scenario == "draft" else "L07-TRANSFER"
                # 建单合法写入 orders/lines——草稿不预占只看库存表（上游 draft_amount 同形）
                stock_before = store.rows("SELECT * FROM stock ORDER BY sku")
                order = service.create_order(customer,
                        [OrderLine("L07-A", q1, p1), OrderLine("L07-B", q2, p2)], oid)
                out({
                    "scenario": scenario,
                    "order_id": order["id"],
                    "status": order["status"],
                    "total_cents": order["total_cents"],
                    "line_totals": [row["line_total_cents"] for row in order["lines"]],
                    "stock_unchanged": store.rows("SELECT * FROM stock ORDER BY sku") == stock_before,
                })
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OrderProbe() {}

    /** 相对解释器路径按进程 cwd 绝对化（l06 StockProbe.resolveInterpreter 同款）。 */
    static Path resolveInterpreter(String python) {
        return Path.of(python).toAbsolutePath().normalize();
    }

    /** 临时库（自建库、天然干净数据开始；close 递归清理——l06 TempDb 同形）。 */
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
     * 跑一次订单场景，解析 stdout 单行 JSON 为观察值。子进程失败、超时（300s）或输出
     * 不可解析 → ToolFailure（调用方 rc 1 收场；词面语言绑定按 l06 JD6 口径如实偏差）。
     */
    static JsonNode order(Path interpreter, Path target, Path db, String scenario,
            int q1, int p1, int q2, int p2) {
        List<String> argv = new ArrayList<>(List.of(
                interpreter.toString(), "-X", "utf8", "-c", SNIPPET,
                db.toString(), target.toString(), scenario,
                String.valueOf(q1), String.valueOf(p1), String.valueOf(q2), String.valueOf(p2)));
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
            throw new ToolFailure("python 输出读取失败", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ToolFailure("python 等待被中断");
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
