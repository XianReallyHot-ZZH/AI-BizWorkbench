package workbench.evals.l10;

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
 * 客户实现驱动缝（L10 合法发货与非法迁移面；l07 OrderProbe / l08 AtomicProbe / l09
 * CancelProbe 同款先例）。子进程 python 一次跑完一个模式、回报原始观察值 JSON；
 * <b>判断与预期计算一律留在调用方</b>（独立预期：legal 模式 (on_hand, reserved,
 * available)=(6,2,4)、ship 流水 (-4,-4) 由 Java 字面断言，不抄子进程返回——上游
 * {@code order_transition_lab.py}「先手算后运行」同义）。
 *
 * <p>五模式（映射源 {@code vendors/CodexFDE/docs/courses/L10/examples/
 * order_transition_lab.py}，只读对照）：legal（A 在库 10、OTHER 预占 2、TARGET 预占
 * 4，发货后 6/2/4、OTHER 不变、一条 ship 流水）；draft-ship / cancelled-ship /
 * double-ship（三种非法状态：InvalidTransition 拒绝且四表不变）；refuse-all（教学
 * 错误修复反证面：合法路径也拒绝——「全部拒绝」挡住非法拍却破坏合法业务，<b>不进
 * 默认登记项</b>，讲义 D3：必红项不进默认门）。四表快照（stock/sales_orders/
 * sales_order_lines/inventory_events）before/after 原始回报，相等性由 Java 断言。
 *
 * <p>子进程超时 300s（l06–l09 同值）。
 */
final class ShipProbe {

    /** 工具以 rc 1 收场的失败（l07–l09 同形；词面入 stderr）。 */
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
     * 发货面片段：argv = [db, 客户树, mode]；stdout 出单行 JSON 原始观察值
     * （mode/status/error/other 前后/stock 三量/ship_events/before/after）。
     * 不判对错——判断在调用方。CJK 经 -X utf8 子进程传递（L04/L05 先例）。
     */
    private static final String SNIPPET = """
            import json, sys
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
            service.add_product("A", "L10 商品 A", 100)
            service.receive_stock("A", 10, "l10-open-A")
            service.create_order("L10 其他客户", [OrderLine("A", 2, 100)], "OTHER")
            service.reserve_order("OTHER")
            other_before = service.order("OTHER")
            service.create_order("L10 目标客户", [OrderLine("A", 4, 100)], "TARGET")
            if mode != "draft-ship":
                service.reserve_order("TARGET")
            if mode == "cancelled-ship":
                service.cancel_order("TARGET")
            if mode == "double-ship":
                service.ship_order("TARGET")
            before = snapshot()
            error = None
            if mode in ("draft-ship", "cancelled-ship", "double-ship"):
                try:
                    service.ship_order("TARGET")
                except InvalidTransition as exc:
                    error = {"type": type(exc).__name__, "message": str(exc)}
                after = snapshot()
                status = service.order("TARGET")["status"]
            elif mode == "refuse-all":
                # 教学错误修复（order_transition_lab refuse-all 同形）：合法路径也拒绝。
                # 此处不调用真实服务——反证面由调用方断言"合法拍应失败"。
                error = {"type": "InvalidTransition",
                         "message": "TEACHING defect: all shipping rejected, including reserved"}
                after = snapshot()
                status = service.order("TARGET")["status"]
            else:
                service.ship_order("TARGET")
                after = snapshot()
                status = service.order("TARGET")["status"]
            # 三量走 service.product() 计算列（上游 order_transition_lab 同款——available
            # 不在 stock 表原始列，由在库-预占推导）
            product = service.product("A")
            stock = [("A", product["on_hand"], product["reserved"], product["available"])]
            ship_events = [(r["sku"], r["quantity"], r["reserved_delta"]) for r in
                           store.rows("SELECT * FROM inventory_events WHERE reference='TARGET' "
                                      "AND event_type='ship' ORDER BY sku")]
            out({
                "mode": mode,
                "status": status,
                "other_before": other_before,
                "other_after": service.order("OTHER"),
                "stock": stock,
                "ship_events": ship_events,
                "error": error,
                "before": before,
                "after": after,
            })
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ShipProbe() {}

    /** 相对解释器路径按进程 cwd 绝对化（l06–l09 同款；不做 PATH 查找——CI 须传绝对路径）。 */
    static Path resolveInterpreter(String python) {
        return Path.of(python).toAbsolutePath().normalize();
    }

    /** 跑一次发货模式（五模式），解析 stdout 单行 JSON 为观察值。 */
    static JsonNode ship(Path interpreter, Path target, Path db, String mode) {
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

    /** 临时库（自建库、天然干净数据开始；close 递归清理——l06–l09 TempDb 同形）。 */
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
