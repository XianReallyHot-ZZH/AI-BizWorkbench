package workbench.evals.l09;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 客户实现驱动缝（L09 取消释放预占面；l07 {@code OrderProbe} / l08 {@code AtomicProbe}
 * 同款先例）。子进程 python 一次跑完一个模式、回报原始观察值 JSON；
 * <b>判断与预期计算一律留在调用方</b>（独立预期：normal 模式 stock [A(10,2), B(8,1)]、
 * releases [A(0,-4), B(0,-3)] 由 Java 字面断言，不抄子进程返回——上游
 * {@code cancellation_lab.py}「独立期望和四表比较」同义）。
 *
 * <p>入门面六模式（映射源 {@code vendors/CodexFDE/docs/courses/L09/examples/
 * cancellation_lab.py}，只读对照；绑定 eval 用例体走同一入门服务——客户
 * {@code eval/cases.py:78}）：normal（TARGET A4/B3 + OTHER A2/B1，取消 TARGET 完整
 * 释放）、draft（草稿取消无释放事件）、repeat（二次取消被拒且状态不变）、
 * shipped（已发货拒绝）、write-error（临时库标注触发器令第二条释放事件写入失败——
 * 回滚区分器）、leak（数据面教学注入：直接 UPDATE 状态跳过释放——仅教学对照，
 * 不进默认登记项，讲义 D2）。四表快照（stock/sales_orders/sales_order_lines/
 * inventory_events）before/after 原始回报，相等性由 Java 断言。
 *
 * <p>正式面 formal 模式（讲义 D4：上游「未验证正式 SalesService 页面」已知局限在
 * 本仓库闭合）：正式服务族双单取消——只释放 TARGET、OTHER 保留、重复取消拒绝；
 * 净树绿（回归护栏，不是修复链红点——入门注入不影响正式面，恰好证明隔离性）。
 * 子进程超时 300s（l06–l08 同值）。
 */
final class CancelProbe {

    /** 工具以 rc 1 收场的失败（l07/l08 同形；词面入 stderr）。 */
    static final class ToolFailure extends RuntimeException {
        ToolFailure(String message) {
            super(message);
        }

        ToolFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final long TIMEOUT_SECONDS = 300;

    /**
     * 入门面片段：argv = [db, 客户树, mode]；stdout 出单行 JSON 原始观察值
     * （mode/status/other 前后/stock/releases/error/second_error/once/before/after）。
     * 不判对错——判断在调用方。CJK 经 -X utf8 子进程传递（L04/L05 先例）。
     */
    private static final String ENTRY_SNIPPET = """
            import json, sqlite3, sys
            from pathlib import Path
            sys.path.insert(0, sys.argv[2])
            from flowerp.models import InvalidTransition, OrderLine
            from flowerp.service import ERPService
            from flowerp.store import ERPStore

            mode = sys.argv[3]
            TABLES = ("stock", "sales_orders", "sales_order_lines", "inventory_events")

            def out(obj):
                print(json.dumps(obj, ensure_ascii=False))

            def snapshot():
                return {t: store.rows("SELECT * FROM %s ORDER BY rowid" % t) for t in TABLES}

            store = ERPStore(Path(sys.argv[1]))
            service = ERPService(store)
            for sku, qty in (("A", 10), ("B", 8)):
                service.add_product(sku, sku, 100)
                service.receive_stock(sku, qty, "l09-open-" + sku)
            service.create_order("L09 目标客户", [OrderLine("A", 4, 100), OrderLine("B", 3, 100)], "TARGET")
            service.create_order("L09 其他客户", [OrderLine("A", 2, 100), OrderLine("B", 1, 100)], "OTHER")
            service.reserve_order("OTHER")
            if mode != "draft":
                service.reserve_order("TARGET")
            before = snapshot()
            other_before = service.order("OTHER")
            error = None
            if mode == "shipped":
                service.ship_order("TARGET")
                before = snapshot()
            if mode == "write-error":
                with store.connect() as conn:
                    conn.execute("CREATE TRIGGER l09_fail_release BEFORE INSERT ON inventory_events "
                                 "WHEN NEW.event_type='release' AND NEW.sku='B' "
                                 "BEGIN SELECT RAISE(ABORT, 'L09 teaching second release failure'); END")
            if mode == "leak":
                # 数据面教学注入：只改状态跳过释放，仅在临时库（cancellation_lab leak 同形）
                with store.connect() as conn:
                    conn.execute("UPDATE sales_orders SET status='cancelled' WHERE id='TARGET'")
            else:
                try:
                    service.cancel_order("TARGET")
                except (InvalidTransition, sqlite3.IntegrityError) as exc:
                    error = {"type": type(exc).__name__, "message": str(exc)}
            after = snapshot()
            stock = [(r["sku"], r["on_hand"], r["reserved"])
                     for r in store.rows("SELECT * FROM stock ORDER BY sku")]
            releases = [(r["sku"], r["quantity"], r["reserved_delta"]) for r in
                        store.rows("SELECT * FROM inventory_events WHERE event_type='release' "
                                   "AND reference='TARGET' ORDER BY sku")]
            once = None
            second_error = None
            if mode == "repeat" and error is None:
                once = snapshot()
                try:
                    service.cancel_order("TARGET")
                except InvalidTransition as exc:
                    second_error = {"type": type(exc).__name__, "message": str(exc)}
                after = snapshot()
            out({
                "mode": mode,
                "status": service.order("TARGET")["status"],
                "other_before": other_before,
                "other_after": service.order("OTHER"),
                "stock": stock,
                "releases": releases,
                "error": error,
                "second_error": second_error,
                "once": once,
                "before": before,
                "after": after,
            })
            """;

    /**
     * 正式面片段（SalesService.cancel，讲义 D4）：双单 TARGET A4/B3 + OTHER A2/B1，
     * 取消 TARGET 后 reserved/available 前后对照 + 重复取消拒绝，原始回报不判对错。
     */
    private static final String FORMAL_SNIPPET = """
            import json, sys
            from pathlib import Path
            sys.path.insert(0, sys.argv[2])
            from flowerp.store import ERPStore
            from flowerp.identity import IdentityService, SYSTEM_PRINCIPAL
            from flowerp import MasterDataService
            from flowerp.inventory import InventoryService
            from flowerp.sales import SalesService
            from flowerp.models import InvalidTransition

            def out(obj):
                print(json.dumps(obj, ensure_ascii=False))

            store = ERPStore(Path(sys.argv[1]))
            IdentityService(store).ensure_local_defaults()
            master = MasterDataService(store)
            inventory = InventoryService(store)
            sales = SalesService(store)
            actor = SYSTEM_PRINCIPAL
            products = [master.create_product(actor, "L09F-" + sku, sku, 1000, 500) for sku in ("A", "B")]
            customer = master.create_customer(actor, "L09F-C", "L09 formal customer", credit_limit_cents=100000)
            for i, product in enumerate(products):
                inventory.receive(actor, product["id"], "LOC-MAIN-STOCK", (10, 8)[i], "l09f-opening-%d" % i)

            def order_for(qa, qb):
                return sales.create_order(actor, customer["id"], [
                    {"product_id": products[0]["id"], "quantity": qa},
                    {"product_id": products[1]["id"], "quantity": qb}])

            def bal():
                return [[p["id"]] + [inventory.balance(actor, p["id"], "LOC-MAIN-STOCK")[k]
                                     for k in ("on_hand", "reserved", "available")] for p in products]

            target, other = order_for(4, 3), order_for(2, 1)
            for order in (target, other):
                sales.confirm(actor, order["id"])
                sales.reserve(actor, order["id"])
            before = bal()
            sales.cancel(actor, target["id"], "L09 formal cancel TARGET")
            after = bal()
            second_error = None
            try:
                sales.cancel(actor, target["id"], "L09 formal repeat")
            except InvalidTransition as exc:
                second_error = {"type": type(exc).__name__, "message": str(exc)}
            after_repeat = bal()
            out({
                "mode": "formal",
                "status": sales.order(actor, target["id"])["status"],
                "other_status": sales.order(actor, other["id"])["status"],
                "before": before,
                "after": after,
                "after_repeat": after_repeat,
                "second_error": second_error,
            })
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CancelProbe() {}

    /** 相对解释器路径按进程 cwd 绝对化（l06–l08 同款；不做 PATH 查找——CI 须传绝对路径）。 */
    static Path resolveInterpreter(String python) {
        return Path.of(python).toAbsolutePath().normalize();
    }

    /** 跑一次取消模式（入门六模式 + formal），解析 stdout 单行 JSON 为观察值。 */
    static JsonNode cancel(Path interpreter, Path target, Path db, String mode) {
        String snippet = "formal".equals(mode) ? FORMAL_SNIPPET : ENTRY_SNIPPET;
        List<String> argv = new ArrayList<>(List.of(
                interpreter.toString(), "-X", "utf8", "-c", snippet,
                db.toString(), target.toString(), mode));
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

    /** 临时库（自建库、天然干净数据开始；close 递归清理——l06–l08 TempDb 同形）。 */
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
                        throw new java.io.UncheckedIOException(error);
                    }
                });
            } catch (IOException error) {
                throw new java.io.UncheckedIOException(error);
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
}
