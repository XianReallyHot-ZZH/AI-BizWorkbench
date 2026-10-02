package workbench.evals.l12;

import com.fasterxml.jackson.databind.JsonNode;
import workbench.bootstrap.Args;
import workbench.bootstrap.PyJson;
import workbench.evals.EvalHarness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L12 统一入口收口（新讲机制——本讲无 Python 先例，映射源 = 讲义 §2 登记项构成 +
 * 上游 {@code purchase_approval_lab.py} 九模式与合同绑定两 eval）。本地与显式复验
 * 调用同一本入口——「同一 Eval 身份」（{@link EvalHarness} schema 1.0，L06 已对齐
 * 客户）。
 *
 * <p>默认门十登记项（全 blocking，讲义 D2）：
 * <ul>
 * <li>{@code l12_approval_approved} 正常入库：received + approved_by 具名 + 17/2/15 +
 *     恰一条 +7 流水（receipt:target）+ OTHER/PR-OTHER 保留（合同 acceptance[1]；
 *     预期由 Java 字面算出，不抄探针返回；三量走 product() 计算列）
 * <li>{@code l12_approval_unapproved} / {@code _rejected} / {@code _blank_reviewer}
 *     三拒绝拍：预期异常类型 + 前后<b>五表快照相等</b>（合同 acceptance[0]
 *     「未审批采购入库被阻断且库存不变」——部分写入风险面）
 * <li>{@code l12_approval_replay}（同键重试五表不变 + idempotent_replay）/
 *     {@code l12_approval_different_key}（已入库换键 InvalidTransition 且五表不变）
 *     ——幂等键的两面
 * <li>{@code l12_approval_recovery_same_key} 同键恢复：触发器故障后部分提交实录
 *     （on_hand=17、单据 approved）→ 移除触发器原键重试补齐 received、不重复加库存；
 *     <b>恢复成功不证明第一次操作原子性成立</b>（上游明示口径）
 * <li>{@code purchase_requires_approval} + {@code receiving_is_idempotent} 客户绑定
 *     eval 子进程照跑（登记项名 = 客户名原样，业务权威——ADR-0005；断言面差距 =
 *     receive_stock 键维度幂等 vs 单据维度防重，如实入账证据账 C7）
 * <li>{@code l12_frozen_checks} 客户 eval 双树 + 采购面源件（service.py）指纹复核
 *     （讲义 C8；l07–l10 frozenChecks 族形一致——Java 源件指纹走证据账层面，
 *     L10/L11 勘误先例直承）
 * </ul>
 *
 * <p>两必红选跑项（blocking，<b>不进默认门</b>——讲义 D4/D5）：{@code
 * l12_status_write_failure} 断言<b>理想不变量</b>（故障后五表不变）——客户原样行为
 * 违反之（R1 两段事务窗口：库存已 +7、单据仍 approved），预期红留证；{@code
 * l12_key_collision} 断言 received ⇒ 必有 +7 流水——客户已消费键静默吞违反之
 * （R4：received 但库存 10、无流水，失败显示成成功），预期红留证。<b>失败不是本仓
 * 缺陷</b>（客户真理原样，上游明示「本次未修复产品代码」）——不修绿不删报告；
 * {@code --case} 选跑承载（L11 premature-stock / L09 leak / L10 refuse-all 先例）。
 *
 * <p>argv 面（l08–l11 Checks 同形）：{@code --target} 必填（客户树根，须含
 * flowerp/）；{@code --case}（可多，选跑子集——含两必红选跑项）；{@code --python}
 * （缺省 .venv/bin/python，按进程 cwd 绝对化不查 PATH）、{@code --no-report}、
 * {@code --report-path}。stdout 打印全量报告；退出码 = blocking 失败决定。
 */
public final class ApprovalChecks {

    /** 冻结面（讲义 C8）：客户 eval 双树 + 采购面源件（触发器实验的只读对照件）。 */
    private static final String[] FROZEN_FILES = {
            "eval/harness.py", "eval/cases.py", "flowerp/service.py"};
    private static final String DEFAULT_PYTHON = ".venv/bin/python";

    private ApprovalChecks() {}

    public static void main(String[] argv) {
        try {
            Args args = new Args(argv,
                    Set.of("--target", "--python", "--report-path"), Set.of("--no-report"),
                    List.of(), Set.of("--case"));
            Path target = Path.of(args.require("--target")).toAbsolutePath().normalize();
            if (!Files.isDirectory(target.resolve("flowerp"))) {
                throw new Args.UsageException("--target 下没有 flowerp/：" + target);
            }
            Path interpreter = ApprovalProbe.resolveInterpreter(args.optional("--python", DEFAULT_PYTHON));
            Path reportPath = (args.flag("--no-report") || !args.has("--report-path"))
                    ? null : Path.of(args.require("--report-path"));
            List<String> selected = args.repeatingList("--case");
            List<EvalHarness.Entry> all = entries(target, interpreter);
            List<EvalHarness.Entry> run = selected.isEmpty() ? defaultEntries(all) : all.stream()
                    .filter(entry -> selected.contains(entry.name())).toList();
            if (!selected.isEmpty() && run.size() != selected.size()) {
                List<String> known = run.stream().map(EvalHarness.Entry::name).toList();
                List<String> unknown = selected.stream().filter(name -> !known.contains(name)).toList();
                if (!unknown.isEmpty()) {
                    throw new Args.UsageException("未知登记项 --case：" + unknown);
                }
                List<String> duplicated = selected.stream().distinct()
                        .filter(name -> selected.stream().filter(name::equals).count() > 1).toList();
                throw new Args.UsageException("重复登记项 --case：" + duplicated);
            }
            EvalHarness.Outcome outcome = EvalHarness.run(run, "all", null, reportPath);
            System.out.println(PyJson.dumps(outcome.report()));
            if (outcome.exitCode() != 0) {
                System.exit(outcome.exitCode());
            }
        } catch (Args.UsageException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        } catch (ApprovalProbe.ToolFailure error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }

    /** 默认门 = 十件（两必红选跑项排除——预期红项不进默认门）。 */
    private static List<EvalHarness.Entry> defaultEntries(List<EvalHarness.Entry> all) {
        return all.stream()
                .filter(entry -> !"l12_status_write_failure".equals(entry.name())
                        && !"l12_key_collision".equals(entry.name())).toList();
    }

    static List<EvalHarness.Entry> entries(Path target, Path interpreter) {
        return List.of(
                new EvalHarness.Entry("l12_approval_approved", "blocking",
                        () -> scenario(interpreter, target, "approved")),
                new EvalHarness.Entry("l12_approval_unapproved", "blocking",
                        () -> scenario(interpreter, target, "unapproved")),
                new EvalHarness.Entry("l12_approval_rejected", "blocking",
                        () -> scenario(interpreter, target, "rejected")),
                new EvalHarness.Entry("l12_approval_blank_reviewer", "blocking",
                        () -> scenario(interpreter, target, "blank-reviewer")),
                new EvalHarness.Entry("l12_approval_replay", "blocking",
                        () -> scenario(interpreter, target, "replay")),
                new EvalHarness.Entry("l12_approval_different_key", "blocking",
                        () -> scenario(interpreter, target, "different-key")),
                new EvalHarness.Entry("l12_approval_recovery_same_key", "blocking",
                        () -> scenario(interpreter, target, "recovery-same-key")),
                new EvalHarness.Entry("purchase_requires_approval", "blocking",
                        () -> customerCase(interpreter, target, "purchase_requires_approval")),
                new EvalHarness.Entry("receiving_is_idempotent", "blocking",
                        () -> customerCase(interpreter, target, "receiving_is_idempotent")),
                new EvalHarness.Entry("l12_status_write_failure", "blocking",
                        () -> scenario(interpreter, target, "status-write-failure")),
                new EvalHarness.Entry("l12_key_collision", "blocking",
                        () -> scenario(interpreter, target, "key-collision")),
                new EvalHarness.Entry("l12_frozen_checks", "blocking",
                        () -> frozenChecks(target)));
    }

    // ---- 场景断言（独立预期，全部留 Java） ------------------------------------------

    /**
     * 审批入库场景：探针回报原始观察值，独立预期与全部判断在本方法完成（不抄子进程
     * 返回）。两必红模式断言<b>理想不变量</b>，客户原样行为违反之 → 预期红留证
     * （R1/R4 运行实证——讲义 D5：客户原样不写成复刻缺陷）。
     */
    private static String scenario(Path interpreter, Path target, String mode) {
        try (ApprovalProbe.TempDb tempDb = ApprovalProbe.tempDb("l12-approval-")) {
            JsonNode observed = ApprovalProbe.approval(interpreter, target, tempDb.db, mode);
            switch (mode) {
                case "approved" -> {
                    JsonNode purchase = observed.path("receive_result").path("purchase");
                    assertFieldEquals("purchase.status", purchase.path("status").asText(), "received");
                    assertFieldEquals("approved_by", purchase.path("approved_by").asText(),
                            "TEACHING business reviewer");
                    assertQuantities(observed.path("product"), 17, 2, 15);
                    assertSingleReceipt(observed, "receipt:target", 7);
                    assertOthersPreserved(observed);
                    return "审批后入库：received、approved_by 具名；17/2/15；恰一条 +7 流水"
                            + "（receipt:target）；OTHER 与 PR-OTHER 保留——教学 reviewer 输入不构成身份认证";
                }
                case "unapproved", "rejected" -> {
                    assertRejectedWith(observed, "ApprovalRequired", mode);
                    return mode.equals("unapproved")
                            ? "未审批请求入库被拒：ApprovalRequired，五表不变（未经具名审批不得改变库存）"
                            : "已拒绝采购请求入库被拒：ApprovalRequired，五表不变";
                }
                case "blank-reviewer" -> {
                    JsonNode error = observed.path("error");
                    if (!error.isObject() || !"ValueError".equals(error.path("type").asText())) {
                        throw new AssertionError("mode=blank-reviewer expected ValueError 实际：" + error);
                    }
                    if (!observed.path("before_approval").equals(observed.path("after"))) {
                        throw new AssertionError("mode=blank-reviewer 批准被拒后五表状态发生变化");
                    }
                    return "空白审批人拒绝：ValueError，五表不变（具名不是装饰）";
                }
                case "replay" -> {
                    if (!observed.path("after_first").equals(observed.path("after"))) {
                        throw new AssertionError("mode=replay 同键重试改变了五表状态");
                    }
                    if (!observed.path("replay_result").path("stock")
                            .path("idempotent_replay").asBoolean(false)) {
                        throw new AssertionError("mode=replay 重试未返回 idempotent_replay=true："
                                + observed.path("replay_result").path("stock"));
                    }
                    // 上游公共尾段（复查轮 T-c2 补齐）：终态三拍同收 received + 三量 + 保留面
                    assertFieldEquals("purchase.status",
                            observed.path("purchase").path("status").asText(), "received");
                    assertQuantities(observed.path("product"), 17, 2, 15);
                    assertSingleReceipt(observed, "receipt:target", 7);
                    assertOthersPreserved(observed);
                    return "同键重放：五表不变、idempotent_replay=true——一次幂等入库";
                }
                case "different-key" -> {
                    JsonNode error2 = observed.path("error2");
                    if (!error2.isObject() || !"InvalidTransition".equals(error2.path("type").asText())) {
                        throw new AssertionError("mode=different-key expected InvalidTransition 实际：" + error2);
                    }
                    if (!observed.path("after_first").equals(observed.path("after"))) {
                        throw new AssertionError("mode=different-key 换键请求改变了五表状态");
                    }
                    assertFieldEquals("purchase.status",
                            observed.path("purchase").path("status").asText(), "received");
                    assertQuantities(observed.path("product"), 17, 2, 15);
                    assertSingleReceipt(observed, "receipt:target", 7);
                    assertOthersPreserved(observed);
                    return "已入库后换键必拒：InvalidTransition，五表不变";
                }
                case "recovery-same-key" -> {
                    JsonNode error1 = observed.path("error1");
                    if (!error1.isObject() || !"IntegrityError".equals(error1.path("type").asText())) {
                        throw new AssertionError("mode=recovery-same-key expected IntegrityError 实际：" + error1);
                    }
                    // 部分提交实录（教学事实，不是缺陷断言）：库存已 +7、单据仍 approved
                    if (observed.path("product_partial").path("on_hand").asInt(-1) != 17
                            || !"approved".equals(observed.path("purchase_partial")
                            .path("status").asText())) {
                        throw new AssertionError("部分提交实录形态与上游不符：on_hand="
                                + observed.path("product_partial").path("on_hand").asInt()
                                + " status=" + observed.path("purchase_partial").path("status").asText());
                    }
                    assertFieldEquals("恢复后 purchase.status",
                            observed.path("receive_result").path("purchase").path("status").asText(),
                            "received");
                    assertQuantities(observed.path("product"), 17, 2, 15);
                    assertSingleReceipt(observed, "receipt:target", 7);
                    assertOthersPreserved(observed);
                    return "同键恢复：故障后部分提交实录（on_hand=17、单据 approved）→ 移除触发器"
                            + "原键重试补齐 received、不重复加库存；恢复成功不证明第一次操作原子性成立";
                }
                case "status-write-failure" -> {
                    // 必红项（预期红留证）：断言理想不变量——客户原样行为（R1 两段事务
                    // 窗口）违反之。若客户行为某天变为原子，本登记项转绿——按当期客户
                    // 真理重新对账（对照账 C7），不修绿不删报告。
                    JsonNode error1 = observed.path("error1");
                    if (!error1.isObject() || !"IntegrityError".equals(error1.path("type").asText())) {
                        throw new AssertionError("mode=status-write-failure expected IntegrityError 实际："
                                + error1);
                    }
                    if (!observed.path("before_receive").equals(observed.path("after"))) {
                        throw new AssertionError("理想不变量失败（客户原样行为，R1 两段事务窗口实证）："
                                + "注入状态写入故障后部分提交——on_hand="
                                + observed.path("product_partial").path("on_hand").asInt()
                                + "（应 10）、单据 status="
                                + observed.path("purchase_partial").path("status").asText()
                                + "、+7 流水已落——原子性不成立，失败如实保留"
                                + "（同键恢复路径见 l12_approval_recovery_same_key）");
                    }
                    return "原子性成立：故障后五表不变";
                }
                case "key-collision" -> {
                    // 必红项（预期红留证）：断言 received ⇒ 必有 +7 流水——客户原样行为
                    // （R4 已消费键静默吞）违反之。
                    JsonNode purchase = observed.path("receive_result").path("purchase");
                    if (!"received".equals(purchase.path("status").asText())) {
                        throw new AssertionError("mode=key-collision 单据未 received，实验形态与上游不符："
                                + purchase.path("status").asText());
                    }
                    JsonNode events = observed.path("events");
                    if (events.size() != 1
                            || observed.path("product").path("on_hand").asInt(-1) != 17) {
                        throw new AssertionError("理想不变量失败（客户原样行为，R4 已消费键静默吞实证）："
                                + "单据 received 但 on_hand="
                                + observed.path("product").path("on_hand").asInt()
                                + "（应 17）、PR-TARGET 收货事件 " + events.size()
                                + " 条（应 1）——失败显示成成功（业务边界 5 活教材），如实保留");
                    }
                    return "键冲突下收货与库存严格对应";
                }
                default -> throw new AssertionError("未知模式：" + mode);
            }
        }
    }

    /** 拒绝拍共通：预期异常类型 + before_receive 前后五表快照相等（部分写入面）。 */
    private static void assertRejectedWith(JsonNode observed, String expectedType, String mode) {
        JsonNode error = observed.path("error");
        if (!error.isObject() || !expectedType.equals(error.path("type").asText())) {
            throw new AssertionError("mode=" + mode + " expected " + expectedType + " 实际：" + error);
        }
        if (!observed.path("before_receive").equals(observed.path("after"))) {
            throw new AssertionError("mode=" + mode + " 拒绝后五表状态发生变化（不应有半成品）");
        }
    }

    private static void assertFieldEquals(String field, String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected " + field + "=" + expected + " actual=" + actual);
        }
    }

    /** 三量断言（product() 计算列：available = on_hand - reserved 不在 stock 表）。 */
    private static void assertQuantities(JsonNode product, int onHand, int reserved, int available) {
        if (product.path("on_hand").asInt(-1) != onHand
                || product.path("reserved").asInt(-1) != reserved
                || product.path("available").asInt(-1) != available) {
            throw new AssertionError("expected (" + onHand + "," + reserved + "," + available
                    + ") actual=(" + product.path("on_hand").asInt(-1) + ","
                    + product.path("reserved").asInt(-1) + "," + product.path("available").asInt(-1) + ")");
        }
    }

    /** PR-TARGET 收货事件恰一条：键与数量逐一对应（数量断言独立算，不抄流水行）。 */
    private static void assertSingleReceipt(JsonNode observed, String eventKey, int quantity) {
        JsonNode events = observed.path("events");
        if (events.size() != 1) {
            throw new AssertionError("expected 恰一条 PR-TARGET 收货事件 实际 " + events.size());
        }
        assertFieldEquals("events[0].event_key", events.path(0).path("event_key").asText(), eventKey);
        if (events.path(0).path("quantity").asInt(-1) != quantity) {
            throw new AssertionError("expected quantity=" + quantity + " actual="
                    + events.path(0).path("quantity").asInt(-1));
        }
    }

    /** 既有业务保护：sales 两表 before/after 全等 + PR-OTHER 行全等（17/2/15 中预占 2 恒在）。 */
    private static void assertOthersPreserved(JsonNode observed) {
        JsonNode before = observed.path("before_approval");
        JsonNode after = observed.path("after");
        for (String table : new String[]{"sales_orders", "sales_order_lines"}) {
            if (!before.path(table).equals(after.path(table))) {
                throw new AssertionError("入库改变了 " + table + "（OTHER 保护失效）");
            }
        }
        if (!rowById(before.path("purchase_requests"), "PR-OTHER")
                .equals(observed.path("pr_other"))) {
            throw new AssertionError("PR-OTHER 保护失效：" + observed.path("pr_other"));
        }
    }

    private static JsonNode rowById(JsonNode rows, String id) {
        for (JsonNode row : rows) {
            if (id.equals(row.path("id").asText())) {
                return row;
            }
        }
        throw new AssertionError("找不到行 id=" + id);
    }

    // ---- 客户 eval 与冻结指纹（l08–l11 同形） ------------------------------------

    /** 客户 blocking eval 子进程照跑（l08 SalesChecks.customerCase 可见性开放复用）。 */
    private static String customerCase(Path interpreter, Path target, String caseName) {
        return workbench.evals.l08.SalesChecks.customerCase(interpreter, target, caseName);
    }

    /** 客户 eval 双树 + 采购面源件指纹复核（l07–l11 frozenChecks 同形）。 */
    private static String frozenChecks(Path target) {
        Map<String, Object> mismatched = new LinkedHashMap<>();
        for (String rel : FROZEN_FILES) {
            String targetHash = sha256(target.resolve(rel));
            String sourceHash = sha256(repoRoot().resolve("vendors").resolve("flowERP").resolve(rel));
            if (!targetHash.equals(sourceHash)) {
                mismatched.put(rel, Map.of(
                        "target", targetHash.substring(0, 12),
                        "vendors", sourceHash.substring(0, 12)));
            }
        }
        if (!mismatched.isEmpty()) {
            throw new AssertionError("冻结检查被改动: " + PyJson.dumpsCompact(mismatched));
        }
        StringBuilder evidence = new StringBuilder("冻结检查指纹一致: ");
        for (int i = 0; i < FROZEN_FILES.length; i++) {
            if (i > 0) {
                evidence.append(", ");
            }
            evidence.append(FROZEN_FILES[i]).append('=')
                    .append(sha256(target.resolve(FROZEN_FILES[i])).substring(0, 12));
        }
        return evidence.toString();
    }

    private static String sha256(Path file) {
        try {
            return sha256(java.nio.file.Files.readAllBytes(file));
        } catch (java.io.IOException error) {
            throw new ApprovalProbe.ToolFailure("冻结检查文件读取失败：" + file, error);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new ApprovalProbe.ToolFailure("SHA-256 不可用", error);
        }
    }

    /** l06–l11 Checks.repoRoot 同形：锚定本类装载位置（target/classes → 仓库根），不随 cwd 漂移。 */
    private static Path repoRoot() {
        try {
            return Path.of(ApprovalChecks.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().getParent().getParent();
        } catch (java.net.URISyntaxException error) {
            throw new ApprovalProbe.ToolFailure("仓库根定位失败（class 装载位置）", error);
        }
    }
}
