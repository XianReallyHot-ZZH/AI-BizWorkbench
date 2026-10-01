package workbench.evals.l08;

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
 * 客户实现驱动缝（L08 原子预占面；l07 {@code OrderProbe} 同款先例，本包自带探针面
 * ——不动 l05/l06/l07 已验收源件）。子进程 python 一次跑完一个模式、回报原始观察值
 * JSON；<b>判断与预期计算一律留在调用方</b>（独立预期：pass 模式 reserved [2,3] /
 * available [3,2] 由 Java 字面断言，不抄子进程返回——上游 {@code atomic_reservation_lab.py}
 * 「独立期望和四表比较」同义）。
 *
 * <p>三模式（上游 lab 工人段同形，映射源 {@code vendors/CodexFDE/docs/courses/L08/
 * examples/atomic_reservation_lab.py}，只读对照）：pass（A、B 各 5 件申请 2/3）、
 * shortage（B 仅 2 件——写入前规划期即拒绝，普通缺货不足以证明原子性的教学面）、
 * write-error（两行均足、临时库装标注触发器令第二条预占记录插入失败——写入开始后
 * 回滚的原子性区分器）。四表快照（stock_balance/stock_reservations/
 * sales_document_lines/sales_documents）before/after 原始回报，相等性由 Java 断言。
 * 临时数据库自建，不连客户运行库；write-error 触发器只装在本次临时库。
 * 子进程超时 300s（l06/l07 同值）。
 */
final class AtomicProbe {

    /** 工具以 rc 1 收场的失败（l07 OrderProbe.ToolFailure 同形；词面入 stderr）。 */
    static final class ToolFailure extends RuntimeException {
        ToolFailure(String message) {
            super(message);
        }

        ToolFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 子进程超时（l06/l07 同值）。 */
    private static final long TIMEOUT_SECONDS = 300;

    /**
     * 原子预占片段：argv = [db, 客户树, mode]；stdout 出单行 JSON 原始观察值
     * （mode/status/reserved/available/error/reservation_count/before/after）。
     * <b>不判对错</b>——判断在调用方。CJK 经 -X utf8 子进程传递（L04/L05 先例）。
     */
    private static final String SNIPPET = """
            import json, sqlite3, sys
            from pathlib import Path
            sys.path.insert(0, sys.argv[2])
            from flowerp.store import ERPStore
            from flowerp.identity import IdentityService, SYSTEM_PRINCIPAL
            from flowerp import MasterDataService
            from flowerp.inventory import InventoryService
            from flowerp.sales import SalesService
            from flowerp.models import InsufficientStock

            mode = sys.argv[3]
            TABLES = ("stock_balance", "stock_reservations", "sales_document_lines", "sales_documents")

            def out(obj):
                print(json.dumps(obj, ensure_ascii=False))

            def snapshot():
                return {t: store.rows("SELECT * FROM %s ORDER BY rowid" % t) for t in TABLES}

            store = ERPStore(Path(sys.argv[1]))
            IdentityService(store).ensure_local_defaults()
            master, inventory, sales = MasterDataService(store), InventoryService(store), SalesService(store)
            actor = SYSTEM_PRINCIPAL
            products = [master.create_product(actor, "L08-" + sku, sku, 1000, 500) for sku in ("A", "B")]
            customer = master.create_customer(actor, "L08-C", "L08 customer", credit_limit_cents=100000)
            for i, product in enumerate(products):
                inventory.receive(actor, product["id"], "LOC-MAIN-STOCK",
                                  2 if mode == "shortage" and i == 1 else 5, "l08-opening-%d" % i)
            order = sales.create_order(actor, customer["id"], [
                {"product_id": products[0]["id"], "quantity": 2},
                {"product_id": products[1]["id"], "quantity": 3}])
            sales.confirm(actor, order["id"])
            before = snapshot()
            if mode == "write-error":
                product_id = products[1]["id"].replace("'", "''")
                with store.connect() as conn:
                    conn.execute("CREATE TRIGGER l08_second_write BEFORE INSERT ON stock_reservations "
                                 "WHEN NEW.product_id='%s' BEGIN "
                                 "SELECT RAISE(ABORT, 'L08 injected second-write failure'); END" % product_id)
            error = None
            try:
                sales.reserve(actor, order["id"])
            except (InsufficientStock, sqlite3.IntegrityError) as exc:
                error = {"type": type(exc).__name__, "message": str(exc)}
            after = snapshot()
            balances = [inventory.balance(actor, p["id"], "LOC-MAIN-STOCK") for p in products]
            current = sales.order(actor, order["id"])
            out({
                "mode": mode,
                "status": current["status"],
                "reserved": [b["reserved"] for b in balances],
                "available": [b["available"] for b in balances],
                "error": error,
                "reservation_count": len(after["stock_reservations"]),
                "before": before,
                "after": after,
            })
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AtomicProbe() {}

    /** 相对解释器路径按进程 cwd 绝对化（l06/l07 同款；不做 PATH 查找——CI 须传绝对路径）。 */
    static Path resolveInterpreter(String python) {
        return Path.of(python).toAbsolutePath().normalize();
    }

    /** 临时库（自建库、天然干净数据开始；close 递归清理——l06/l07 TempDb 同形）。 */
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

    /**
     * 跑一次原子预占模式，解析 stdout 单行 JSON 为观察值。子进程失败、超时（300s）或
     * 输出不可解析 → ToolFailure（调用方 rc 1 收场）。
     */
    static JsonNode atomic(Path interpreter, Path target, Path db, String mode) {
        List<String> argv = new ArrayList<>(List.of(
                interpreter.toString(), "-X", "utf8", "-c", SNIPPET,
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
}
