package workbench.evals.l05;

import com.fasterxml.jackson.databind.JsonNode;
import workbench.bootstrap.Args;
import workbench.bootstrap.PyJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L05 收货场景驱动（Java 重走件，对照冻结 {@code evals/l05/receiving_scenario_check.py}）。
 *
 * <p>预期一律由 --opening/--receipt/--new 算术在 Java 侧独立计算（上游：预期的 28 来自
 * 20＋8，不能抄程序返回值）；客户实现经 {@link FlowerpProbe} 子进程跑原语、只回报原始
 * 观察值。除 on_hand 外同时逐条对照 inventory_events——客户 blocking eval 不读流水，
 * 本驱动是 observing 补充（C6 迁移 / C7 盲区对照），blocking 身份仍是客户用例
 * {@code receiving_is_idempotent}（D4，不另立身份）。
 *
 * <p>默认数据 20/8/8 复现 C1/C2（20→28→28→36，流水 1→2→2→3，0/负数拒绝且状态不变）；
 * {@code --opening 11 --receipt 3 --new 3} 为 C6 迁移（11→14→14→17）。每次运行自建
 * 临时库，天然从干净数据开始。stdout 单行 JSON（pass/fail 两形）与冻结件逐字同形
 * （fail 的 expected/actual 逐 step 同载荷）；rc：0 pass / 1 fail / 2 用法错误。
 */
public final class ReceivingScenarioCheck {

    private static final String REQUIREMENT = "同一笔收货只入账一次：库存与流水都不得重复";
    private static final String DEFAULT_PYTHON = ".venv/bin/python";

    private ReceivingScenarioCheck() {}

    public static void main(String[] argv) {
        try {
            Args args = new Args(argv,
                    Set.of("--target", "--opening", "--receipt", "--new", "--python"), Set.of());
            String targetArg = args.require("--target");
            Path target = Path.of(targetArg).toAbsolutePath().normalize();
            if (!Files.isDirectory(target.resolve("flowerp"))) {
                throw new Args.UsageException("--target 下没有 flowerp/：" + target);
            }
            int opening = intOption(args, "--opening", 20);
            int receipt = intOption(args, "--receipt", 8);
            int newReceipt = intOption(args, "--new", 8);
            Path interpreter = FlowerpProbe.resolveInterpreter(args.optional("--python", DEFAULT_PYTHON));
            int code = run(target, interpreter, opening, receipt, newReceipt);
            if (code != 0) {
                System.exit(code);
            }
        } catch (Args.UsageException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        } catch (FlowerpProbe.ToolFailure error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }

    private static int run(Path target, Path interpreter, int opening, int receipt, int newReceipt) {
        try (FlowerpProbe.TempDb temp = FlowerpProbe.tempDb("l05-scenario-")) {
            Path db = temp.db;
            FlowerpProbe.probe(interpreter, target, db, "setup");

            JsonNode openingReceive = FlowerpProbe.probe(interpreter, target, db,
                    "receive", "opening", String.valueOf(opening));
            if (openingReceive.get("on_hand").asInt() != opening) {
                return fail("opening", obj("on_hand", opening),
                        obj("on_hand", openingReceive.get("on_hand").asInt()));
            }
            JsonNode openingLedger = FlowerpProbe.probe(interpreter, target, db, "events", "opening");
            if (openingLedger.get("count").asInt() != 1
                    || openingLedger.get("quantity").asInt() != opening
                    || !"receive".equals(openingLedger.get("event_type").asText())) {
                return fail("opening-ledger", 1, openingLedger.get("count").asInt());
            }

            int expectedFirst = opening + receipt;
            JsonNode first = FlowerpProbe.probe(interpreter, target, db,
                    "receive", "receipt:A", String.valueOf(receipt));
            if (first.get("on_hand").asInt() != expectedFirst || first.get("idempotent_replay").asBoolean()) {
                return fail("first",
                        obj("on_hand", expectedFirst, "replay", false),
                        obj("on_hand", first.get("on_hand").asInt(),
                            "replay", first.get("idempotent_replay").asBoolean()));
            }
            if (FlowerpProbe.probe(interpreter, target, db, "events", "receipt:A")
                    .get("count").asInt() != 1) {
                return fail("first-ledger", 1, "receipt:A 流水数≠1");
            }

            JsonNode before = FlowerpProbe.probe(interpreter, target, db, "snapshot");
            JsonNode second = FlowerpProbe.probe(interpreter, target, db,
                    "receive", "receipt:A", String.valueOf(receipt));
            if (second.get("on_hand").asInt() != expectedFirst) {
                return fail("replay", obj("on_hand", expectedFirst),
                        obj("on_hand", second.get("on_hand").asInt()));
            }
            if (!second.get("idempotent_replay").asBoolean()) {
                return fail("replay-flag", true, second.get("idempotent_replay").asBoolean());
            }
            JsonNode after = FlowerpProbe.probe(interpreter, target, db, "snapshot");
            if (!before.equals(after)) {
                return fail("replay-ledger", "库存与流水均不变", "重放改变了账面");
            }

            int expectedNew = opening + receipt + newReceipt;
            JsonNode third = FlowerpProbe.probe(interpreter, target, db,
                    "receive", "receipt:B", String.valueOf(newReceipt));
            if (third.get("on_hand").asInt() != expectedNew) {
                return fail("new", obj("on_hand", expectedNew),
                        obj("on_hand", third.get("on_hand").asInt()));
            }
            int totalEvents = FlowerpProbe.probe(interpreter, target, db, "events_total")
                    .get("count").asInt();
            if (totalEvents != 3) {
                return fail("new-ledger", 3, totalEvents);
            }

            for (int invalid : new int[] {0, -1}) {
                JsonNode state = FlowerpProbe.probe(interpreter, target, db, "snapshot");
                JsonNode attempt = FlowerpProbe.probe(interpreter, target, db,
                        "receive", "invalid:" + invalid, String.valueOf(invalid));
                if (!attempt.get("rejected").asBoolean()) {
                    return fail("invalid", "数量 " + invalid + " 被拒绝", "被接受");
                }
                if (!state.equals(FlowerpProbe.probe(interpreter, target, db, "snapshot"))) {
                    return fail("invalid-state", "拒绝后状态不变", "拒绝后改变了账面");
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("case", "receiving_scenario");
        out.put("status", "pass");
        out.put("opening", opening);
        out.put("receipt", receipt);
        out.put("new", newReceipt);
        out.put("on_hand_path", List.of(opening, opening + receipt,
                opening + receipt, opening + receipt + newReceipt));
        out.put("ledger_path", List.of(1, 2, 2, 3));
        out.put("invalid_quantities_rejected", List.of(0, -1));
        System.out.println(PyJson.dumpsCompact(out));
        return 0;
    }

    /** 与冻结驱动同形的失败行：step/status/expected/actual/requirement（键序 = Python 插入序）。 */
    private static int fail(String step, Object expected, Object actual) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("step", step);
        out.put("status", "fail");
        out.put("expected", expected);
        out.put("actual", actual);
        out.put("requirement", REQUIREMENT);
        System.out.println(PyJson.dumpsCompact(out));
        return 1;
    }

    private static Map<String, Object> obj(Object... keyValue) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValue.length; i += 2) {
            map.put((String) keyValue[i], keyValue[i + 1]);
        }
        return map;
    }

    private static int intOption(Args args, String name, int defaultValue) {
        String raw = args.optional(name, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            throw new Args.UsageException("argument " + name + ": invalid int value: '" + raw + "'");
        }
    }
}
