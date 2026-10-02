package workbench.evals.l11;

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
 * 客户实现驱动缝（L11 采购申请 propose 面；l07 OrderProbe / l08 AtomicProbe / l09
 * CancelProbe / l10 ShipProbe 同款先例）。子进程 python 一次跑完一个模式、回报原始
 * 观察值 JSON；<b>判断与预期计算一律留在调用方</b>（独立预期：normal 拍五字段 +
 * 10/2/8 由 Java 字面断言，不抄子进程返回——上游 {@code purchase_request_lab.py}
 * 「先手算后运行」同义）。
 *
 * <p>七模式（映射源 {@code vendors/CodexFDE/docs/courses/L11/examples/
 * purchase_request_lab.py}，只读对照）：夹具 = A 在库 10 + OTHER 预占 2 + 既有申请
 * PR-OTHER（五表快照含之）；normal（propose("a", 7, "  低于补货点  ", "PR-TARGET")
 * ——小写 SKU 测规范化、带空白理由测去空白）；zero / negative / blank-reason /
 * unknown-sku / duplicate-id（五拒绝：夹具先建 PR-TARGET 再重复提交——IntegrityError
 * ≠ 幂等成功，上游钉死）；premature-stock（申请后人为 receive_stock——教学缺陷
 * 反证面：「字段检查绿、库存检查红」的同版不同覆盖演示，<b>不进默认门</b>——
 * 必红项，讲义 D2）。五表快照（purchase_requests / stock / inventory_events /
 * sales_orders / sales_order_lines）before/after 原始回报，相等性与字段断言由
 * Java 完成。三量走 {@code service.product()} 计算列（available 不在 stock 表——
 * L10 教训④ 承袭）。
 *
 * <p>子进程超时 300s（l06–l10 同值）。
 */
final class PurchaseProbe {

    /** 工具以 rc 1 收场的失败（l07–l10 同形；词面入 stderr）。 */
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
     * 采购面片段：argv = [db, 客户树, mode]；stdout 出单行 JSON 原始观察值
     * （mode/result/error/repurchase/product 前后三量/before/after）。异常一律回报
     * type+message 不吞（expected_error 面由调用方断言——与上游 except 形态等义，
     * 探针不判对错）。CJK 经 -X utf8 子进程传递（L04/L05 先例）。
     */
    private static final String SNIPPET = """
            import json, sys
            from pathlib import Path
            sys.path.insert(0, sys.argv[2])
            from flowerp.models import NotFound, OrderLine
            from flowerp.service import ERPService
            from flowerp.store import ERPStore

            mode = sys.argv[3]
            TABLES = ("purchase_requests", "stock", "inventory_events", "sales_orders", "sales_order_lines")

            def out(obj):
                print(json.dumps(obj, ensure_ascii=False))

            def snapshot():
                return {t: store.rows("SELECT * FROM %s ORDER BY rowid" % t) for t in TABLES}

            def quantities(product):
                return {"on_hand": product["on_hand"], "reserved": product["reserved"],
                        "available": product["available"]}

            store = ERPStore(Path(sys.argv[1]))
            service = ERPService(store)
            service.add_product("A", "L11 商品 A", 100)
            service.receive_stock("A", 10, "l11-open-A")
            service.create_order("L11 其他客户", [OrderLine("A", 2, 100)], "OTHER")
            service.reserve_order("OTHER")
            # PR-OTHER 固定夹具：SQL 直插（integration_lab 同形）——不经 propose_purchase。
            # 净树上与 propose 建行等价；注入树上避免夹具触发教学注入行（上游 17/2/15 口径：
            # 受控实验首跑曾按 purchase_request_lab 形态经 propose 建夹具，红点 20/2/18
            # 即夹具分歧实录，报告留盘不删——lesson-11-submission 02-complete-v1）。
            with store.connect() as conn:
                conn.execute("INSERT INTO purchase_requests(id,sku,quantity,status,reason) VALUES(?,?,?,?,?)",
                             ("PR-OTHER", "A", 3, "proposed", "另一项需求"))
            if mode == "duplicate-id":
                service.propose_purchase("A", 7, "低于补货点", "PR-TARGET")
            product_before = quantities(service.product("A"))
            before = snapshot()
            quantity = {"zero": 0, "negative": -1}.get(mode, 7)
            reason = "   " if mode == "blank-reason" else "  低于补货点  "
            sku = "UNKNOWN" if mode == "unknown-sku" else "a"
            error = None
            result = None
            try:
                result = service.propose_purchase(sku, quantity, reason, "PR-TARGET")
            except Exception as exc:
                error = {"type": type(exc).__name__, "message": str(exc)}
            if mode == "premature-stock" and result is not None:
                # 教学缺陷（purchase_request_lab premature-stock 同形）：申请后提前入库。
                # 字段仍正确、库存变 17/2/15——「字段绿库存红」由调用方断言。
                service.receive_stock("A", 7, "teaching-premature-receipt")
            after = snapshot()
            repurchase = None
            product_after = None
            if result is not None:
                repurchase = service.purchase("PR-TARGET")
                product_after = quantities(service.product("A"))
            out({
                "mode": mode,
                "result": result,
                "error": error,
                "repurchase": repurchase,
                "product_before": product_before,
                "product_after": product_after,
                "before": before,
                "after": after,
            })
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PurchaseProbe() {}

    /** 相对解释器路径按进程 cwd 绝对化（l06–l10 同款；不做 PATH 查找——CI 须传绝对路径）。 */
    static Path resolveInterpreter(String python) {
        return Path.of(python).toAbsolutePath().normalize();
    }

    /** 跑一次采购模式（七模式），解析 stdout 单行 JSON 为观察值。 */
    static JsonNode purchase(Path interpreter, Path target, Path db, String mode) {
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

    /** 临时库（自建库、天然干净数据开始；close 递归清理——l06–l10 TempDb 同形）。 */
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
