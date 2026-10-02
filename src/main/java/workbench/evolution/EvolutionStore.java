package workbench.evolution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import workbench.bootstrap.JsonOut;
import workbench.bootstrap.PyJson;
import workbench.delivery.Feedback;
import workbench.delivery.TaskStore;
import workbench.learning.LearningStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 演进记录账（L15，上游 {@code workbench/evolution.py} 490 行逐句对应物——operate
 * 第三讲真新面）："Governed evidence that one observed failure became a verified
 * reusable asset."（上游类 docstring 原文）。
 *
 * <p><b>闸门链</b>（合同 acceptance[0]/eval raw_feedback_cannot_become_blocking
 * 断言面）：create 只收<b>具名接受的反馈</b>（词面「只有具名接受的反馈才能提升为
 * 进化记录」——未审核反馈不得经任何通道变成执行依据）；review 具名+理由、只
 * proposed；assets 登记版本化工程资产（七类型、同 type+path 去重）；verify 重门
 * ——候选任务 ≠ 源任务（「进化必须由下一项独立交付任务验证，不能复用源任务」）、
 * 共享至少一个 ERP 业务对象、候选晚于进化创建、completed + 具名 approve + 逐项
 * blocking + 报告在受控 reports 目录且 sha256 重算一致；<b>governed 分支</b>：
 * learning/ 前缀资产须证明候选任务实际采用（learning_bindings 绑定 + outcome
 * passed + 登记资产 ⊆ 采用集——「缺少全部登记版本的采用与复验通过证据」）。
 *
 * <p><b>状态机</b>：proposed →（approve/reject/defer）→ approved →（assets）
 * → asset_changed →（verify）→ verified；rejected/deferred 终态；非法迁移词面
 * 「非法进化状态迁移：{from} -> {to}」。事件表全留痕（from/to/detail/actor/
 * evidence）——演进过程可回放。
 *
 * <p><b>存储</b>（spec D3 单库四账）：与 tasks / feedback 同库（构造次序 =
 * TaskStore → Feedback → evolutions/evolution_events 两表；上游 {@code
 * EvolutionStore.__init__} 同形 {@code TaskStore(path)} + {@code feedback_connect}）。
 * verify 的 governed 分支构造 {@link LearningStore}（上游 {@code from .learning
 * import LearningStore} 同形）——evolution ↔ learning 互相引用与上游同构。
 *
 * <p><b>CLI</b>（上游 argparse 单入口五子命令同形，--db → --runtime-dir 本仓
 * 形态适配落账）：{@code evolution create|review|assets|verify|summary}，独立
 * main 不进 REGISTRY 之外另有 FeedbackCli 三子命令承载反馈审核可观测面。
 */
public final class EvolutionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** JSON 解析（业务件统一 Jackson；PyJson 只承载序列化）。 */
    private static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text == null ? "null" : text);
        } catch (IOException error) {
            throw new IllegalArgumentException("非法 JSON：" + error.getMessage(), error);
        }
    }

    /** JsonNode → Java 集合（PyJson 输出边界只认 Map/List/标量）。 */
    private static Object toJson(JsonNode node) {
        return MAPPER.convertValue(node, Object.class);
    }

    /** 进化五分类（上游 CLASSIFICATIONS 逐字）。 */
    public static final LinkedHashSet<String> CLASSIFICATIONS = new LinkedHashSet<>(List.of(
            "erp_rule", "erp_workflow", "workbench_control", "workbench_observability",
            "new_requirement"));

    /** 资产七类型（上游 ASSET_TYPES 逐字）。 */
    public static final LinkedHashSet<String> ASSET_TYPES = new LinkedHashSet<>(List.of(
            "rule", "spec", "eval", "skill", "harness", "implementation", "documentation"));

    private final Path dbPath;

    public EvolutionStore(Path path) {
        this.dbPath = path;
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException error) {
            throw new UncheckedIOException("任务库目录创建失败：" + path.getParent(), error);
        }
        new TaskStore(path);
        new Feedback(path);
        try (Connection conn = open()) {
            try (java.sql.Statement st = conn.createStatement()) {
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS evolutions(
                          id TEXT PRIMARY KEY,
                          feedback_id TEXT NOT NULL UNIQUE,
                          source_task_id TEXT NOT NULL,
                          candidate_task_id TEXT,
                          business_refs_json TEXT NOT NULL,
                          failure_signature TEXT NOT NULL,
                          classification TEXT NOT NULL,
                          status TEXT NOT NULL,
                          asset_changes_json TEXT NOT NULL DEFAULT '[]',
                          decision_by TEXT,
                          decision_at TEXT,
                          decision_note TEXT,
                          blocking_report TEXT,
                          human_acceptance TEXT,
                          verified_by TEXT,
                          verified_at TEXT,
                          created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )""");
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS evolution_events(
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          evolution_id TEXT NOT NULL,
                          from_status TEXT,
                          to_status TEXT NOT NULL,
                          detail TEXT NOT NULL,
                          actor TEXT NOT NULL,
                          evidence_json TEXT,
                          created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )""");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_evolutions_status_created "
                        + "ON evolutions(status,created_at DESC)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_evolution_events_record "
                        + "ON evolution_events(evolution_id,id)");
            }
        } catch (SQLException error) {
            throw new IllegalStateException("evolutions 建表失败：" + path, error);
        }
    }

    public Path path() {
        return dbPath;
    }

    // ---- create：反馈审核闸门 -----------------------------------------------------------

    public Map<String, Object> create(String feedbackId, String failureSignature,
            String classification, List<String> businessRefs, String actor) {
        String fid = feedbackId.strip();
        String signature = failureSignature.strip();
        String cls = classification.strip().toLowerCase();
        String who = actor == null || actor.isBlank() ? "system" : actor.strip();
        if (fid.isEmpty() || signature.isEmpty()) {
            throw new IllegalArgumentException("进化记录必须包含反馈编号和稳定失败签名");
        }
        if (signature.length() > 200) {
            throw new IllegalArgumentException("失败签名不能超过 200 个字符");
        }
        if (!CLASSIFICATIONS.contains(cls)) {
            throw new IllegalArgumentException("进化分类无效");
        }
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement st = conn.prepareStatement(
                        "SELECT id, task_id, status FROM feedback WHERE id = ?")) {
                    st.setString(1, fid);
                    ResultSet feedbackRow = st.executeQuery();
                    if (!feedbackRow.next()) {
                        throw new MissingException(fid);
                    }
                    String status = feedbackRow.getString("status");
                    String taskId = feedbackRow.getString("task_id");
                    if (!"accepted".equals(status)) {
                        // 合同 acceptance[0] + eval 2 拍 3——上游词面逐字
                        throw new IllegalArgumentException("只有具名接受的反馈才能提升为进化记录");
                    }
                    List<String> refs = resolveRefs(conn, taskId, businessRefs);
                    String evolutionId = "EVO-" + UUID.randomUUID().toString()
                            .replaceAll("-", "").substring(0, 10).toUpperCase();
                    try (PreparedStatement insert = conn.prepareStatement(
                            "INSERT INTO evolutions(id, feedback_id, source_task_id, "
                                    + "business_refs_json, failure_signature, classification, "
                                    + "status) VALUES (?, ?, ?, ?, ?, ?, 'proposed')")) {
                        insert.setString(1, evolutionId);
                        insert.setString(2, fid);
                        insert.setString(3, taskId);
                        insert.setString(4, PyJson.dumpsCompact(refs));
                        insert.setString(5, signature);
                        insert.setString(6, cls);
                        insert.executeUpdate();
                    } catch (SQLException conflict) {
                        throw new IllegalArgumentException("同一反馈只能形成一条进化记录", conflict);
                    }
                    event(conn, evolutionId, null, "proposed", "已从具名接受的反馈建立进化候选",
                            who, PyJson.dumpsCompact(Map.of(
                                    "feedback_id", fid, "source_task_id", taskId,
                                    "business_refs", refs)));
                    conn.commit();
                }
            } catch (RuntimeException error) {
                conn.rollback();
                throw error;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("evolution create 失败：" + fid, error);
        }
        return get(lastEvolutionId(dbPath, fid));
    }

    private static List<String> resolveRefs(Connection conn, String taskId,
            List<String> businessRefs) throws SQLException {
        List<String> refs = new ArrayList<>();
        if (businessRefs != null && !businessRefs.isEmpty()) {
            for (String value : businessRefs) {
                if (value != null && !value.isBlank()) {
                    refs.add(value.strip());
                }
            }
        } else {
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT business_refs_json FROM tasks WHERE id = ?")) {
                st.setString(1, taskId);
                ResultSet row = st.executeQuery();
                if (!row.next()) {
                    throw new IllegalArgumentException("反馈关联的源任务不存在，不能建立进化因果链");
                }
                JsonNode node = parse(row.getString(1));
                if (node.isArray()) {
                    for (JsonNode item : node) {
                        if (!item.asText().isBlank()) {
                            refs.add(item.asText().strip());
                        }
                    }
                }
            }
        }
        if (refs.isEmpty()) {
            throw new IllegalArgumentException("进化记录至少关联一个 ERP 业务对象");
        }
        if (new LinkedHashSet<>(refs).size() != refs.size()) {
            throw new IllegalArgumentException("进化记录的业务对象引用不能重复");
        }
        return refs;
    }

    // ---- review：具名审核 ---------------------------------------------------------------

    public Map<String, Object> review(String evolutionId, String reviewer, String decision,
            String note) {
        String who = reviewer == null ? "" : reviewer.strip();
        String verdict = decision == null ? "" : decision.strip().toLowerCase();
        String why = note == null ? "" : note.strip();
        if (who.isEmpty() || why.isEmpty()) {
            throw new IllegalArgumentException("进化审核必须包含具名审核人和理由");
        }
        Map<String, String> targets = Map.of("approve", "approved", "reject", "rejected",
                "defer", "deferred");
        if (!targets.containsKey(verdict)) {
            throw new IllegalArgumentException("进化审核决定必须是 approve、reject 或 defer");
        }
        String target = targets.get(verdict);
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                String current = status(conn, evolutionId);
                if (!"proposed".equals(current)) {
                    throw new IllegalArgumentException("非法进化状态迁移：" + current + " -> " + target);
                }
                String decidedAt = now();
                int updated;
                try (PreparedStatement st = conn.prepareStatement(
                        "UPDATE evolutions SET status = ?, decision_by = ?, decision_at = ?, "
                                + "decision_note = ?, updated_at = CURRENT_TIMESTAMP "
                                + "WHERE id = ? AND status = 'proposed'")) {
                    st.setString(1, target);
                    st.setString(2, who);
                    st.setString(3, decidedAt);
                    st.setString(4, why);
                    st.setString(5, evolutionId);
                    updated = st.executeUpdate();
                }
                if (updated != 1) {
                    throw new IllegalArgumentException("进化状态已变化，拒绝并发审核");
                }
                event(conn, evolutionId, current, target, "具名进化审核：" + verdict + "；" + why,
                        who, PyJson.dumpsCompact(Map.of("decision", verdict, "note", why)));
                conn.commit();
            } catch (RuntimeException error) {
                conn.rollback();
                throw error;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("evolution review 失败：" + evolutionId, error);
        }
        return get(evolutionId);
    }

    // ---- assets：版本化工程资产 ---------------------------------------------------------

    public Map<String, Object> recordAssets(String evolutionId, List<Map<String, String>> assets,
            String actor) {
        String who = actor == null ? "" : actor.strip();
        if (who.isEmpty()) {
            throw new IllegalArgumentException("登记资产变更必须具名");
        }
        List<Map<String, String>> validated = validateAssets(assets);
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                String current = status(conn, evolutionId);
                if (!"approved".equals(current) && !"asset_changed".equals(current)) {
                    throw new IllegalArgumentException(
                            "非法进化状态迁移：" + current + " -> asset_changed");
                }
                JsonNode existing = parse(rawAssets(conn, evolutionId));
                List<Map<String, String>> merged = new ArrayList<>();
                LinkedHashSet<String> identities = new LinkedHashSet<>();
                for (JsonNode item : existing) {
                    merged.add(Map.of("type", item.path("type").asText(),
                            "path", item.path("path").asText(),
                            "reason", item.path("reason").asText()));
                    identities.add(item.path("type").asText() + "/" + item.path("path").asText());
                }
                for (Map<String, String> asset : validated) {
                    String identity = asset.get("type") + "/" + asset.get("path");
                    if (identities.contains(identity)) {
                        throw new IllegalArgumentException("同一类型和路径的资产变化不能重复登记");
                    }
                    identities.add(identity);
                    merged.add(asset);
                }
                int updated;
                try (PreparedStatement st = conn.prepareStatement(
                        "UPDATE evolutions SET status = 'asset_changed', asset_changes_json = ?, "
                                + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = ?")) {
                    st.setString(1, PyJson.dumpsCompact(merged));
                    st.setString(2, evolutionId);
                    st.setString(3, current);
                    updated = st.executeUpdate();
                }
                if (updated != 1) {
                    throw new IllegalArgumentException("进化状态已变化，拒绝并发登记资产");
                }
                event(conn, evolutionId, current, "asset_changed",
                        "approved".equals(current) ? "已登记版本化工程资产变化"
                                : "已追加版本化工程资产变化",
                        who, PyJson.dumpsCompact(Map.of("asset_changes", validated)));
                conn.commit();
            } catch (RuntimeException error) {
                conn.rollback();
                throw error;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("evolution assets 失败：" + evolutionId, error);
        }
        return get(evolutionId);
    }

    // ---- verify：独立候选交付验证 -------------------------------------------------------

    public Map<String, Object> verify(String evolutionId, String candidateTaskId,
            String blockingReport, String actor) {
        String candidate = candidateTaskId == null ? "" : candidateTaskId.strip();
        String who = actor == null ? "" : actor.strip();
        if (candidate.isEmpty() || who.isEmpty()) {
            throw new IllegalArgumentException("验证进化必须包含候选任务和验证人");
        }
        // governed 前置检查（事务外只读——上游 :261-276 断言面同形）：learning/ 前缀资产须
        // 证明候选任务实际采用。LearningStore 构造/查询开独立连接，若置于 BEGIN IMMEDIATE
        // 事务内会同进程双连接互锁（SQLITE_BUSY 实测）——检查移前，状态闸门仍在事务内复核。
        List<String> governed = new ArrayList<>();
        try (Connection pre = open()) {
            EvolutionRow evolution = row(pre, evolutionId);
            if (!"asset_changed".equals(evolution.status())) {
                throw new IllegalArgumentException(
                        "非法进化状态迁移：" + evolution.status() + " -> verified");
            }
            if (candidate.equals(evolution.sourceTaskId())) {
                throw new IllegalArgumentException("进化必须由下一项独立交付任务验证，不能复用源任务");
            }
            for (JsonNode asset : parse(evolution.assetChangesJson())) {
                String path = asset.path("path").asText();
                if (path.startsWith("learning/")) {
                    governed.add(path.substring("learning/".length()));
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("evolution verify 预检失败：" + evolutionId, error);
        }
        if (!governed.isEmpty()) {
            verifyGovernedAdoption(dbPath, candidate, governed);
        }
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                EvolutionRow evolution = row(conn, evolutionId);
                if (!"asset_changed".equals(evolution.status())) {
                    throw new IllegalArgumentException(
                            "非法进化状态迁移：" + evolution.status() + " -> verified");
                }
                if (candidate.equals(evolution.sourceTaskId())) {
                    throw new IllegalArgumentException("进化必须由下一项独立交付任务验证，不能复用源任务");
                }
                TaskRow task = taskRow(conn, candidate);
                if (task == null) {
                    throw new IllegalArgumentException("候选交付任务不存在");
                }
                if (!intersects(evolution.businessRefs(), task.businessRefs())) {
                    throw new IllegalArgumentException("候选任务必须与进化记录共享至少一个 ERP 业务对象");
                }
                if (task.createdAt().compareTo(evolution.createdAt()) < 0) {
                    throw new IllegalArgumentException("候选任务必须在进化候选建立后创建");
                }
                JsonNode result = parse(task.resultJson() == null ? "{}" : task.resultJson());
                JsonNode summary = result.path("summary");
                if (!"completed".equals(task.status()) || !"approve".equals(task.reviewDecision())
                        || task.reviewedBy() == null || task.reviewedBy().isBlank()) {
                    throw new IllegalArgumentException("候选任务必须完成 Blocking Eval 并经过具名交付验收");
                }
                if (!"pass".equals(summary.path("decision").asText())
                        || summary.path("blocking_failed").asInt(0) != 0) {
                    throw new IllegalArgumentException("候选任务的阻断级 Eval 未通过");
                }
                List<JsonNode> blockingResults = new ArrayList<>();
                for (JsonNode item : result.path("results")) {
                    if ("blocking".equals(item.path("level").asText())) {
                        blockingResults.add(item);
                    }
                }
                if (blockingResults.isEmpty() || blockingResults.stream()
                        .anyMatch(item -> !item.path("passed").asBoolean(false))) {
                    throw new IllegalArgumentException("候选任务缺少逐项通过的阻断级 Eval 证据");
                }
                String canonicalReport = result.path("report_path").asText("").strip();
                String expectedHash = result.path("report_sha256").asText("").strip().toLowerCase();
                if (canonicalReport.isEmpty() || expectedHash.isEmpty()) {
                    throw new IllegalArgumentException("候选任务缺少不可替换的 Task 级 Blocking 报告快照");
                }
                Path runtimeRoot = dbPath.toAbsolutePath().getParent();
                Path reportRoot = runtimeRoot.resolve("reports").normalize();
                Path reportFile = resolveReport(runtimeRoot, canonicalReport);
                if (!reportFile.getParent().equals(reportRoot) || !Files.isRegularFile(reportFile)) {
                    throw new IllegalArgumentException("候选任务的 Blocking 报告不在受控运行目录或已经丢失");
                }
                String actualHash = sha256(reportFile);
                if (!actualHash.equals(expectedHash)) {
                    throw new IllegalArgumentException("候选任务的 Blocking 报告校验和不一致");
                }
                if (blockingReport != null && !blockingReport.isBlank()) {
                    Path supplied = resolveReport(runtimeRoot, blockingReport.strip());
                    if (!supplied.equals(reportFile)) {
                        throw new IllegalArgumentException("提交的 Blocking 报告与候选任务持久化证据不一致");
                    }
                }
                String verifiedAt = now();
                String acceptance = task.reviewedBy() + "@" + task.reviewedAt();
                int updated;
                try (PreparedStatement st = conn.prepareStatement(
                        "UPDATE evolutions SET status = 'verified', candidate_task_id = ?, "
                                + "blocking_report = ?, human_acceptance = ?, verified_by = ?, "
                                + "verified_at = ?, updated_at = CURRENT_TIMESTAMP "
                                + "WHERE id = ? AND status = 'asset_changed'")) {
                    st.setString(1, candidate);
                    st.setString(2, canonicalReport);
                    st.setString(3, acceptance);
                    st.setString(4, who);
                    st.setString(5, verifiedAt);
                    st.setString(6, evolutionId);
                    updated = st.executeUpdate();
                }
                if (updated != 1) {
                    throw new IllegalArgumentException("进化状态已变化，拒绝并发验证");
                }
                event(conn, evolutionId, "asset_changed", "verified",
                        "下一项交付任务已用阻断证据和具名验收证明资产升级有效", who,
                        PyJson.dumpsCompact(Map.of(
                                "candidate_task_id", candidate,
                                "blocking_report", canonicalReport,
                                "report_sha256", expectedHash,
                                "decision", summary.path("decision").asText(),
                                "blocking_failed", summary.path("blocking_failed").asInt(0),
                                "human_acceptance", acceptance)));
                conn.commit();
            } catch (RuntimeException error) {
                conn.rollback();
                throw error;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("evolution verify 失败：" + evolutionId, error);
        }
        return get(evolutionId);
    }

    /** governed 分支：候选任务对 learning/ 资产的实际采用证据（绑定 + outcome passed + 资产覆盖）。 */
    private static void verifyGovernedAdoption(Path dbPath, String candidate,
            List<String> governed) {
        LearningStore learning = new LearningStore(dbPath);
        Map<String, Object> binding = learning.bindingForTask(candidate);
        if (binding == null) {
            throw new IllegalArgumentException("后续任务没有实际采用此经验或流程");
        }
        Map<String, Object> outcome = learning.outcomeForBinding(
                (String) binding.get("binding_id"));
        List<String> adopted = new ArrayList<>();
        for (Object asset : (List<?>) binding.getOrDefault("assets", List.of())) {
            if (asset instanceof Map<?, ?> row) {
                adopted.add(String.valueOf(row.get("asset_id")));
            }
        }
        if (!new LinkedHashSet<>(adopted).containsAll(governed) || outcome == null
                || !"passed".equals(outcome.get("outcome"))) {
            throw new IllegalArgumentException("缺少全部登记版本的采用与复用通过证据");
        }
    }

    // ---- 读面 ---------------------------------------------------------------------------

    public Map<String, Object> get(String evolutionId) {
        try (Connection conn = open()) {
            Map<String, Object> item;
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT * FROM evolutions WHERE id = ?")) {
                st.setString(1, evolutionId);
                ResultSet rs = st.executeQuery();
                if (!rs.next()) {
                    throw new MissingException(evolutionId);
                }
                item = decode(rs);
            }
            List<Map<String, Object>> events = new ArrayList<>();
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT * FROM evolution_events WHERE evolution_id = ? ORDER BY id")) {
                st.setString(1, evolutionId);
                ResultSet rs = st.executeQuery();
                while (rs.next()) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("id", rs.getInt("id"));
                    event.put("evolution_id", rs.getString("evolution_id"));
                    event.put("from_status", rs.getString("from_status"));
                    event.put("to_status", rs.getString("to_status"));
                    event.put("detail", rs.getString("detail"));
                    event.put("actor", rs.getString("actor"));
                    String evidence = rs.getString("evidence_json");
                    event.put("evidence", evidence == null ? null : toJson(parse(evidence)));
                    event.put("created_at", rs.getString("created_at"));
                    events.add(event);
                }
            }
            item.put("events", events);
            return item;
        } catch (SQLException error) {
            throw new IllegalStateException("evolution get 失败：" + evolutionId, error);
        }
    }

    public List<Map<String, Object>> list(int limit) {
        int bounded = Math.max(1, Math.min(limit, 500));
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT * FROM evolutions ORDER BY created_at DESC, id DESC LIMIT ?")) {
            st.setInt(1, bounded);
            ResultSet rs = st.executeQuery();
            List<Map<String, Object>> items = new ArrayList<>();
            while (rs.next()) {
                items.add(decode(rs));
            }
            return items;
        } catch (SQLException error) {
            throw new IllegalStateException("evolution list 失败", error);
        }
    }

    public Map<String, Object> summary(int limit) {
        Map<String, Object> counts = new LinkedHashMap<>();
        try (Connection conn = open();
             java.sql.Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT status, COUNT(*) AS count FROM evolutions GROUP BY status")) {
            while (rs.next()) {
                counts.put(rs.getString("status"), rs.getInt("count"));
            }
        } catch (SQLException error) {
            throw new IllegalStateException("evolution summary 失败", error);
        }
        int total = counts.values().stream().mapToInt(v -> ((Number) v).intValue()).sum();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        for (String key : List.of("proposed", "approved", "asset_changed", "verified",
                "rejected", "deferred")) {
            summary.put(key, ((Number) counts.getOrDefault(key, 0)).intValue());
        }
        summary.put("items", list(limit));
        return summary;
    }

    // ---- CLI（上游 argparse 单入口五子命令同形） -----------------------------------------

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    /** REGISTRY 注册面（Command 接口 int 契约；独立 main 经 System.exit 同词面退出）。 */
    public static int execute(String[] args) {
        return cli(args);
    }

    private static int cli(String[] args) {
        if (args.length == 0) {
            return usage("evolution <create|review|assets|verify|summary> …");
        }
        String command = args[0];
        Map<String, String> flags = new LinkedHashMap<>();
        List<String> repeatedRefs = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--runtime-dir" -> flags.put("runtime-dir", args[++i]);
                case "--feedback" -> flags.put("feedback", args[++i]);
                case "--signature" -> flags.put("signature", args[++i]);
                case "--classification" -> flags.put("classification", args[++i]);
                case "--business-ref" -> repeatedRefs.add(args[++i]);
                case "--actor" -> flags.put("actor", args[++i]);
                case "--id" -> flags.put("id", args[++i]);
                case "--reviewer" -> flags.put("reviewer", args[++i]);
                case "--decision" -> flags.put("decision", args[++i]);
                case "--note" -> flags.put("note", args[++i]);
                case "--asset-json" -> flags.put("asset-json", args[++i]);
                case "--task" -> flags.put("task", args[++i]);
                case "--report" -> flags.put("report", args[++i]);
                default -> {
                    return usage("未知参数：" + args[i]);
                }
            }
        }
        if (!flags.containsKey("runtime-dir")) {
            return usage("--runtime-dir 必填");
        }
        EvolutionStore store = new EvolutionStore(
                Path.of(flags.get("runtime-dir")).resolve("workbench.db"));
        try {
            switch (command) {
                case "create" -> JsonOut.emit(JsonOut.ordered("ok", true, "evolution",
                        store.create(flags.get("feedback"), flags.get("signature"),
                                flags.get("classification"), repeatedRefs,
                                flags.getOrDefault("actor", "cli-operator"))));
                case "review" -> JsonOut.emit(JsonOut.ordered("ok", true, "evolution",
                        store.review(flags.get("id"), flags.get("reviewer"),
                                flags.get("decision"), flags.getOrDefault("note", ""))));
                case "assets" -> JsonOut.emit(JsonOut.ordered("ok", true, "evolution",
                        store.recordAssets(flags.get("id"), parseAssets(flags.get("asset-json")),
                                flags.getOrDefault("actor", "cli-operator"))));
                case "verify" -> JsonOut.emit(JsonOut.ordered("ok", true, "evolution",
                        store.verify(flags.get("id"), flags.get("task"),
                                flags.getOrDefault("report", ""),
                                flags.getOrDefault("actor", "cli-operator"))));
                case "summary" -> JsonOut.emit(JsonOut.ordered("ok", true, "summary",
                        store.summary(100)));
                default -> {
                    return usage("未知子命令：" + command);
                }
            }
            return 0;
        } catch (IllegalArgumentException | MissingException | IllegalStateException error) {
            return JsonOut.fail(error.getMessage());
        }
    }

    private static int usage(String message) {
        JsonOut.fail(message);
        return 2;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> parseAssets(String json) {
        JsonNode node = parse(json == null ? "[]" : json);
        if (!node.isArray()) {
            throw new IllegalArgumentException("资产变化必须是对象数组");
        }
        List<Map<String, String>> assets = new ArrayList<>();
        for (JsonNode item : node) {
            assets.add(new LinkedHashMap<>(Map.of(
                    "type", item.path("type").asText(""), "path", item.path("path").asText(""),
                    "reason", item.path("reason").asText(""))));
        }
        return assets;
    }

    // ---- 支撑 ---------------------------------------------------------------------------

    private static List<Map<String, String>> validateAssets(List<Map<String, String>> assets) {
        if (assets == null || assets.isEmpty()) {
            throw new IllegalArgumentException("至少登记一项版本化资产变化");
        }
        if (assets.size() > 20) {
            throw new IllegalArgumentException("单次最多登记 20 项资产变化");
        }
        LinkedHashSet<String> identities = new LinkedHashSet<>();
        for (Map<String, String> item : assets) {
            String type = item.getOrDefault("type", "").strip().toLowerCase();
            String path = item.getOrDefault("path", "").strip();
            String reason = item.getOrDefault("reason", "").strip();
            if (!ASSET_TYPES.contains(type) || path.isEmpty() || reason.isEmpty()) {
                throw new IllegalArgumentException("每项资产变化必须包含有效 type、path 和 reason");
            }
            if (path.length() > 500 || reason.length() > 1000) {
                throw new IllegalArgumentException("资产路径或变更原因过长");
            }
            String identity = type + "/" + path;
            if (!identities.add(identity)) {
                throw new IllegalArgumentException("同一批次不能重复登记相同类型和路径的资产");
            }
        }
        return assets;
    }

    record EvolutionRow(String status, String sourceTaskId, String assetChangesJson,
                        String businessRefsJson, String createdAt) {
        List<String> businessRefs() {
            List<String> refs = new ArrayList<>();
            for (JsonNode item : parse(businessRefsJson)) {
                refs.add(item.asText());
            }
            return refs;
        }
    }

    record TaskRow(String status, String resultJson, String reviewedBy, String reviewDecision,
                   String reviewedAt, String businessRefsJson, String createdAt) {
        List<String> businessRefs() {
            List<String> refs = new ArrayList<>();
            for (JsonNode item : parse(businessRefsJson == null ? "[]" : businessRefsJson)) {
                refs.add(item.asText());
            }
            return refs;
        }
    }

    private static EvolutionRow row(Connection conn, String evolutionId) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT * FROM evolutions WHERE id = ?")) {
            st.setString(1, evolutionId);
            ResultSet rs = st.executeQuery();
            if (!rs.next()) {
                throw new MissingException(evolutionId);
            }
            return new EvolutionRow(rs.getString("status"), rs.getString("source_task_id"),
                    rs.getString("asset_changes_json"), rs.getString("business_refs_json"),
                    rs.getString("created_at"));
        }
    }

    private static TaskRow taskRow(Connection conn, String taskId) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT status, result_json, reviewed_by, review_decision, reviewed_at, "
                        + "business_refs_json, created_at FROM tasks WHERE id = ?")) {
            st.setString(1, taskId);
            ResultSet rs = st.executeQuery();
            if (!rs.next()) {
                return null;
            }
            return new TaskRow(rs.getString("status"), rs.getString("result_json"),
                    rs.getString("reviewed_by"), rs.getString("review_decision"),
                    rs.getString("reviewed_at"), rs.getString("business_refs_json"),
                    rs.getString("created_at"));
        }
    }

    private static String status(Connection conn, String evolutionId) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT status FROM evolutions WHERE id = ?")) {
            st.setString(1, evolutionId);
            ResultSet rs = st.executeQuery();
            if (!rs.next()) {
                throw new MissingException(evolutionId);
            }
            return rs.getString("status");
        }
    }

    private static String rawAssets(Connection conn, String evolutionId) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT asset_changes_json FROM evolutions WHERE id = ?")) {
            st.setString(1, evolutionId);
            ResultSet rs = st.executeQuery();
            rs.next();
            return rs.getString(1) == null ? "[]" : rs.getString(1);
        }
    }

    private static void event(Connection conn, String evolutionId, String fromStatus,
            String toStatus, String detail, String actor, String evidenceJson)
            throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "INSERT INTO evolution_events(evolution_id, from_status, to_status, detail, "
                        + "actor, evidence_json) VALUES (?, ?, ?, ?, ?, ?)")) {
            st.setString(1, evolutionId);
            st.setString(2, fromStatus);
            st.setString(3, toStatus);
            st.setString(4, detail);
            st.setString(5, actor);
            st.setString(6, evidenceJson);
            st.executeUpdate();
        }
    }

    private static Map<String, Object> decode(ResultSet rs) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", rs.getString("id"));
        item.put("feedback_id", rs.getString("feedback_id"));
        item.put("source_task_id", rs.getString("source_task_id"));
        item.put("candidate_task_id", rs.getString("candidate_task_id"));
        item.put("business_refs", toJson(parse(rs.getString("business_refs_json"))));
        item.put("failure_signature", rs.getString("failure_signature"));
        item.put("classification", rs.getString("classification"));
        item.put("status", rs.getString("status"));
        item.put("asset_changes", toJson(parse(
                rs.getString("asset_changes_json") == null ? "[]" : rs.getString("asset_changes_json"))));
        item.put("decision_by", rs.getString("decision_by"));
        item.put("decision_at", rs.getString("decision_at"));
        item.put("decision_note", rs.getString("decision_note"));
        item.put("blocking_report", rs.getString("blocking_report"));
        item.put("human_acceptance", rs.getString("human_acceptance"));
        item.put("verified_by", rs.getString("verified_by"));
        item.put("verified_at", rs.getString("verified_at"));
        item.put("created_at", rs.getString("created_at"));
        item.put("updated_at", rs.getString("updated_at"));
        return item;
    }

    private static String lastEvolutionId(Path dbPath, String feedbackId) {
        try (Connection conn = open(dbPath);
             PreparedStatement st = conn.prepareStatement(
                     "SELECT id FROM evolutions WHERE feedback_id = ?")) {
            st.setString(1, feedbackId);
            ResultSet rs = st.executeQuery();
            rs.next();
            return rs.getString(1);
        } catch (SQLException error) {
            throw new IllegalStateException("进化记录回读失败：" + feedbackId, error);
        }
    }

    private static Path resolveReport(Path runtimeRoot, String ref) {
        Path file = Path.of(ref);
        return file.isAbsolute() ? file.normalize() : runtimeRoot.resolve(file).normalize();
    }

    private static boolean intersects(List<String> left, List<String> right) {
        for (String item : left) {
            if (right.contains(item)) {
                return true;
            }
        }
        return false;
    }

    public static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(Files.readAllBytes(file)));
        } catch (Exception error) {
            throw new IllegalStateException("报告哈希计算失败：" + file, error);
        }
    }

    static String now() {
        return OffsetDateTime.now(java.time.ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .replaceAll("\\.\\d+", "");
    }

    /** 反馈不存在（上游 KeyError(feedback_id) / KeyError(evolution_id) 的 CLI 词面化）。 */
    static final class MissingException extends RuntimeException {
        MissingException(String id) {
            super("记录不存在：" + id);
        }
    }

    private Connection open() throws SQLException {
        return open(dbPath);
    }

    private static Connection open(Path path) throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("PRAGMA busy_timeout = 5000");
        }
        return conn;
    }
}
