package workbench.evals.l12;

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
 * 客户实现驱动缝（L12 审批后入库面；l07 OrderProbe / l08 AtomicProbe / l09 CancelProbe /
 * l10 ShipProbe / l11 PurchaseProbe 同款先例）。子进程 python 一次跑完一个模式、回报
 * 原始观察值 JSON；<b>判断与预期计算一律留在调用方</b>（上游
 * {@code purchase_approval_lab.py} 的 assert 面在 Java 侧独立复现，不抄子进程返回）。
 *
 * <p>九模式（映射源 {@code vendors/CodexFDE/docs/courses/L12/examples/
 * purchase_approval_lab.py}，只读对照；夹具逐字段对齐上游：A 商品 + 入库 10
 * {@code "opening"} + OTHER 预占 2 + PR-OTHER（A×3「另一项需求」）+ PR-TARGET（A×7
 * 「补货」）——期初键 {@code opening} 被 key-collision 模式复用，词面不动）：approved /
 * unapproved / blank-reviewer / rejected / replay / different-key / status-write-failure
 * （教学触发器阻断状态翻转——上游 {@code :66-67} 逐字源，<b>内联注入</b>：运行时
 * 数据库行为非源码改动，不建注入装置件、不建常驻拷贝树，讲义 D4）/ recovery-same-key
 * （移除触发器后原键重试）/ key-collision（已消费 {@code opening} 键）。五表阶段快照
 * （before_approval / before_receive / after_status_write_failure / after_receive /
 * final）随 phases 原始回报；三量走 {@code service.product()} 计算列（L10 教训④ 承袭）。
 *
 * <p>子进程超时 300s（l06–l11 同值）。
 */
final class ApprovalProbe {

    /** 工具以 rc 1 收场的失败（l07–l11 同形；词面入 stderr）。 */
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
     * 审批入库面片段：argv = [db, 客户树, mode]；stdout 出单行 JSON 原始观察值
     * （阶段快照 + 模式关键结果 + 异常 type/message 不吞）。异常一律回报不判对错
     * （expected_error 面由调用方断言——与上游 except 形态等义）。CJK 经 -X utf8
     * 子进程传递（L04/L05 先例）。触发器 SQL 与业务调用词面 = 上游逐字。
     */
    private static final String SNIPPET = """
            import json, sys
            from pathlib import Path
            sys.path.insert(0, sys.argv[2])
            from flowerp.service import ERPService
            from flowerp.store import ERPStore
            from flowerp.models import ApprovalRequired, InvalidTransition, OrderLine

            mode = sys.argv[3]
            TABLES = ("purchase_requests", "stock", "inventory_events", "sales_orders", "sales_order_lines")

            def out(obj):
                print(json.dumps(obj, ensure_ascii=False))

            def snapshot():
                return {t: store.rows("SELECT * FROM %s ORDER BY 1" % t) for t in TABLES}

            def quantities(product):
                return {"on_hand": product["on_hand"], "reserved": product["reserved"],
                        "available": product["available"]}

            def record(phase):
                phases.append({"phase": phase, "tables": snapshot()})

            store = ERPStore(Path(sys.argv[1]))
            service = ERPService(store)
            service.add_product("A", "商品 A", 100)
            service.receive_stock("A", 10, "opening")
            service.create_order("其他客户", [OrderLine("A", 2, 100)], "OTHER")
            service.reserve_order("OTHER")
            service.propose_purchase("A", 3, "另一项需求", "PR-OTHER")
            service.propose_purchase("A", 7, "补货", "PR-TARGET")

            phases = []
            payload = {"mode": mode, "boundary": "Real service with temporary database; teaching reviewer and injected trigger; no authenticated approval."}
            record("before_approval")
            payload["before_approval"] = phases[-1]["tables"]
            if mode == "blank-reviewer":
                try:
                    service.approve_purchase("PR-TARGET", "  ")
                except Exception as exc:
                    payload["error"] = {"type": type(exc).__name__, "message": str(exc)}
                payload["after"] = snapshot()
                payload["phases"] = phases
                out(payload)
                raise SystemExit(0)
            if mode == "rejected":
                service.reject_purchase("PR-TARGET", "TEACHING business reviewer")
            elif mode != "unapproved":
                service.approve_purchase("PR-TARGET", "TEACHING business reviewer")
            record("before_receive")
            payload["before_receive"] = phases[-1]["tables"]
            if mode in ("unapproved", "rejected"):
                try:
                    service.receive_purchase("PR-TARGET", "receipt:target")
                except Exception as exc:
                    payload["error"] = {"type": type(exc).__name__, "message": str(exc)}
                payload["after"] = snapshot()
                payload["phases"] = phases
                out(payload)
                raise SystemExit(0)
            if mode in ("status-write-failure", "recovery-same-key"):
                with store.connect() as conn:
                    conn.execute("CREATE TRIGGER fail_target_status BEFORE UPDATE OF status ON purchase_requests WHEN NEW.id='PR-TARGET' AND NEW.status='received' BEGIN SELECT RAISE(ABORT,'TEACHING receipt status failure'); END")
                try:
                    service.receive_purchase("PR-TARGET", "receipt:target")
                except Exception as exc:
                    payload["error1"] = {"type": type(exc).__name__, "message": str(exc)}
                record("after_status_write_failure")
                payload["product_partial"] = quantities(service.product("A"))
                payload["purchase_partial"] = service.purchase("PR-TARGET")
                if mode == "status-write-failure":
                    payload["after"] = snapshot()
                    payload["phases"] = phases
                    out(payload)
                    raise SystemExit(0)
                with store.connect() as conn:
                    conn.execute("DROP TRIGGER fail_target_status")
                payload["receive_result"] = service.receive_purchase("PR-TARGET", "receipt:target")
            key = "opening" if mode == "key-collision" else "receipt:target"
            if mode != "recovery-same-key":
                payload["receive_result"] = service.receive_purchase("PR-TARGET", key)
            record("after_receive")
            if mode == "replay":
                payload["after_first"] = snapshot()
                payload["replay_result"] = service.receive_purchase("PR-TARGET", key)
            if mode == "different-key":
                payload["after_first"] = snapshot()
                try:
                    service.receive_purchase("PR-TARGET", "receipt:another")
                except Exception as exc:
                    payload["error2"] = {"type": type(exc).__name__, "message": str(exc)}
            payload["product"] = quantities(service.product("A"))
            payload["purchase"] = service.purchase("PR-TARGET")
            payload["events"] = store.rows("SELECT * FROM inventory_events WHERE reference='PR-TARGET' AND event_type='receive'")
            payload["pr_other"] = service.purchase("PR-OTHER")
            payload["after"] = snapshot()
            payload["phases"] = phases
            out(payload)
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApprovalProbe() {}

    /** 相对解释器路径按进程 cwd 绝对化（l06–l11 同款；不做 PATH 查找——CI 须传绝对路径）。 */
    static Path resolveInterpreter(String python) {
        return Path.of(python).toAbsolutePath().normalize();
    }

    /** 跑一次审批入库模式（九模式），解析 stdout 单行 JSON 为观察值。 */
    static JsonNode approval(Path interpreter, Path target, Path db, String mode) {
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

    /** 临时库（自建库、天然干净数据开始；close 递归清理——l06–l11 TempDb 同形）。 */
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
