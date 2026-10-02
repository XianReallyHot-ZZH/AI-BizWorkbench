package workbench.delivery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 交付反馈沉淀与审核（上游 {@code workbench/feedback.py} 对应物，L13 讲义 D6）：
 * 同库 feedback 表（schema 含 status、reviewed_* 演化列与 dedupe_key、evidence_json，
 * 新库初建即终形）；观察自动、接受与提升永远具名（上游 docstring 语义）；
 * 签名去重（同一失败重放不重复沉淀）；已审核不可重复改变结论。
 */
public final class Feedback {

    /** 反馈不存在（上游 KeyError → HTTP 404 同族）。 */
    public static final class FeedbackNotFound extends RuntimeException {
        public FeedbackNotFound(String feedbackId) {
            super(feedbackId);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path dbPath;

    public Feedback(Path path) {
        this.dbPath = path.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.dbPath.getParent());
        } catch (IOException error) {
            throw new UncheckedIOException("反馈库目录创建失败：" + this.dbPath.getParent(), error);
        }
        try (Connection conn = open()) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS feedback(
                        id TEXT PRIMARY KEY, task_id TEXT NOT NULL, source TEXT NOT NULL,
                        conclusion TEXT NOT NULL, next_step TEXT NOT NULL,
                        reviewed INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        status TEXT NOT NULL DEFAULT 'pending_review',
                        reviewed_by TEXT, reviewed_at TEXT, review_note TEXT,
                        dedupe_key TEXT, evidence_json TEXT)""");
                st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_feedback_dedupe "
                        + "ON feedback(dedupe_key) WHERE dedupe_key IS NOT NULL");
            }
            conn.commit();
        } catch (SQLException error) {
            throw new TaskStore.StoreFailure("反馈库初始化失败：" + dbPath, error);
        }
    }

    /**
     * 人工反馈登记（上游 add_feedback 同形）：四要素词面校验 + 幂等键（&gt;200 拒绝）；
     * 有幂等键 → INSERT OR IGNORE 后按键回读（重放返回既有件）。
     */
    public Map<String, Object> addFeedback(String taskId, String source, String conclusion,
            String nextStep, Map<String, Object> evidence, String dedupeKey) {
        if (isBlank(taskId) || isBlank(source) || isBlank(conclusion) || isBlank(nextStep)) {
            throw new IllegalArgumentException("反馈必须包含 task_id、来源、结论和下一步");
        }
        String key = dedupeKey == null ? "" : dedupeKey.strip();
        String normalizedKey = key.isEmpty() ? null : key;
        if (normalizedKey != null && normalizedKey.length() > 200) {
            throw new IllegalArgumentException("反馈幂等键不能超过 200 个字符");
        }
        String id = "FB-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String evidenceJson = evidence == null ? null : dumps(evidence);
        try (Connection conn = open()) {
            if (normalizedKey != null) {
                try (PreparedStatement insert = conn.prepareStatement("""
                        INSERT OR IGNORE INTO feedback(id,task_id,source,conclusion,next_step,
                          dedupe_key,evidence_json) VALUES(?,?,?,?,?,?,?)""")) {
                    bindInsert(insert, id, taskId, source, conclusion, nextStep, normalizedKey, evidenceJson);
                    insert.executeUpdate();
                }
                try (PreparedStatement query = conn.prepareStatement(
                        "SELECT * FROM feedback WHERE dedupe_key=?")) {
                    query.setString(1, normalizedKey);
                    try (ResultSet row = query.executeQuery()) {
                        if (row.next()) {
                            Map<String, Object> existing = decode(row);
                            conn.commit();
                            return existing;
                        }
                    }
                }
            }
            try (PreparedStatement insert = conn.prepareStatement("""
                    INSERT INTO feedback(id,task_id,source,conclusion,next_step,evidence_json)
                    VALUES(?,?,?,?,?,?)""")) {
                bindInsert(insert, id, taskId, source, conclusion, nextStep, null, evidenceJson);
                insert.executeUpdate();
            }
            conn.commit();
        } catch (SQLException error) {
            throw new TaskStore.StoreFailure("反馈登记失败：" + id, error);
        }
        return get(id);
    }

    /**
     * 自动失败沉淀（上游 observe_task_failure 同形）：仅 rework/failed/dead_letter；
     * 阻断失败名集合排序 + 签名去重（task-failure:{task}:{sig20}）；source
     * = automation:{status}；下一步恒为「由具名人员复核失败是否稳定；接受后才能
     * 建立 Evolution 候选」——观察自动、接受具名。
     */
    public Map<String, Object> observeTaskFailure(Map<String, Object> task) {
        String status = String.valueOf(task.get("status") == null ? "" : task.get("status")).strip();
        if (!"rework".equals(status) && !"failed".equals(status) && !"dead_letter".equals(status)) {
            throw new IllegalArgumentException("只有 rework、failed 或 dead_letter 任务可以沉淀失败反馈");
        }
        String taskId = String.valueOf(task.get("id") == null ? "" : task.get("id")).strip();
        if (taskId.isEmpty()) {
            throw new IllegalArgumentException("失败反馈缺少 Task ID");
        }
        Map<String, Object> result = task.get("result") instanceof Map<?, ?> map
                ? cast(map) : Map.of();
        List<Object> resultsList = result.get("results") instanceof List<?> list ? castList(list) : List.of();
        List<String> failedCases = new ArrayList<>(new java.util.TreeSet<>());
        for (Object item : resultsList) {
            if (item instanceof Map<?, ?> row && !Boolean.TRUE.equals(row.get("passed"))
                    && "blocking".equals(row.get("level"))) {
                failedCases.add(String.valueOf(row.get("name") == null ? "unknown" : row.get("name")));
            }
        }
        String error = String.valueOf(task.get("error") == null ? "" : task.get("error")).strip();
        Map<String, Object> signaturePayload = new TreeMap<>();
        signaturePayload.put("status", status);
        signaturePayload.put("blocking_failures", failedCases);
        signaturePayload.put("error", error.length() > 500 ? error.substring(0, 500) : error);
        String signatureHash = sha256(dumps(signaturePayload)).substring(0, 20);
        String conclusion = !failedCases.isEmpty()
                ? "Blocking Eval 失败：" + String.join("、", failedCases)
                : "自动流水线进入 " + status + "：" + (error.isEmpty() ? "未提供错误摘要" : error);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schema_version", "workbench.failure-observation/v1");
        evidence.put("task_status", status);
        evidence.put("requirement_id", task.get("requirement_id") == null ? "" : task.get("requirement_id"));
        evidence.put("business_refs", task.get("business_refs") == null ? List.of() : task.get("business_refs"));
        evidence.put("blocking_failures", failedCases);
        evidence.put("error", error);
        evidence.put("report_path", result.get("report_path"));
        evidence.put("report_sha256", result.get("report_sha256"));
        evidence.put("task_version", task.get("version"));
        return addFeedback(taskId, "automation:" + status, conclusion,
                "由具名人员复核失败是否稳定；接受后才能建立 Evolution 候选",
                evidence, "task-failure:" + taskId + ":" + signatureHash);
    }

    /**
     * 具名反馈审核（上游 review_feedback 同形）：accept/reject 二值；已审核不可
     * 重复改变结论；reviewed=1 + 具名 reviewed_by/reviewed_at/review_note 落账。
     */
    public Map<String, Object> reviewFeedback(String feedbackId, String reviewer, String decision, String note) {
        String identity = reviewer == null ? "" : reviewer.strip();
        String id = feedbackId == null ? "" : feedbackId.strip();
        if (id.isEmpty() || identity.isEmpty()) {
            throw new IllegalArgumentException("审核必须包含反馈编号和审核人");
        }
        String normalized = decision == null ? "" : decision.strip().toLowerCase();
        if (!"accept".equals(normalized) && !"reject".equals(normalized)) {
            throw new IllegalArgumentException("审核决定必须为 accept 或 reject");
        }
        String status = "accept".equals(normalized) ? "accepted" : "rejected";
        try (Connection conn = open()) {
            String current;
            try (PreparedStatement query = conn.prepareStatement(
                    "SELECT status FROM feedback WHERE id=?")) {
                query.setString(1, id);
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) {
                        throw new FeedbackNotFound(id);
                    }
                    current = row.getString("status");
                }
            }
            if (!"pending_review".equals(current)) {
                throw new IllegalArgumentException("反馈已经审核，不能重复改变结论");
            }
            try (PreparedStatement update = conn.prepareStatement("""
                    UPDATE feedback SET status=?, reviewed=1, reviewed_by=?, reviewed_at=?,
                      review_note=? WHERE id=? AND status='pending_review'""")) {
                update.setString(1, status);
                update.setString(2, identity);
                update.setString(3, java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                        .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                update.setString(4, note == null ? "" : note.strip());
                update.setString(5, id);
                update.executeUpdate();
            }
            conn.commit();
        } catch (SQLException error) {
            throw new TaskStore.StoreFailure("反馈审核失败：" + id, error);
        }
        return get(id);
    }

    /** 汇总（上游 summary 同形）：计数 + 明细按 created_at DESC, id DESC。 */
    public Map<String, Object> summary() {
        List<Map<String, Object>> items = new ArrayList<>();
        try (Connection conn = openRead()) {
            try (PreparedStatement query = conn.prepareStatement(
                    "SELECT * FROM feedback ORDER BY created_at DESC, id DESC");
                    ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    items.add(decode(row));
                }
            }
        } catch (SQLException error) {
            throw new TaskStore.StoreFailure("反馈汇总读取失败", error);
        }
        long pending = items.stream().filter(item -> "pending_review".equals(item.get("status"))).count();
        long accepted = items.stream().filter(item -> "accepted".equals(item.get("status"))).count();
        long rejected = items.stream().filter(item -> "rejected".equals(item.get("status"))).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", items.size());
        summary.put("reviewed", items.size() - (int) pending);
        summary.put("pending_review", (int) pending);
        summary.put("accepted", (int) accepted);
        summary.put("rejected", (int) rejected);
        summary.put("items", items);
        return summary;
    }

    public Map<String, Object> get(String feedbackId) {
        try (Connection conn = openRead()) {
            try (PreparedStatement query = conn.prepareStatement(
                    "SELECT * FROM feedback WHERE id=?")) {
                query.setString(1, feedbackId);
                try (ResultSet row = query.executeQuery()) {
                    if (row.next()) {
                        return decode(row);
                    }
                }
            }
        } catch (SQLException error) {
            throw new TaskStore.StoreFailure("反馈读取失败：" + feedbackId, error);
        }
        throw new FeedbackNotFound(feedbackId);
    }

    // ---- 内部 ------------------------------------------------------------------

    private Connection open() throws SQLException {
        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
        config.setBusyTimeout(5000);
        config.setSynchronous(org.sqlite.SQLiteConfig.SynchronousMode.FULL);
        config.setTransactionMode(org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE);
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath, config.toProperties());
        conn.setAutoCommit(false);
        return conn;
    }

    /** 只读连接（TaskStore.openRead 同口径：读不抢写锁）。 */
    private Connection openRead() throws SQLException {
        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
        config.setBusyTimeout(5000);
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath, config.toProperties());
    }

    private static void bindInsert(PreparedStatement insert, String id, String taskId, String source,
            String conclusion, String nextStep, String dedupeKey, String evidenceJson) throws SQLException {
        insert.setString(1, id);
        insert.setString(2, taskId);
        insert.setString(3, source);
        insert.setString(4, conclusion);
        insert.setString(5, nextStep);
        if (dedupeKey == null) {
            insert.setString(6, evidenceJson);
        } else {
            insert.setString(6, dedupeKey);
            insert.setString(7, evidenceJson);
        }
    }

    private static Map<String, Object> decode(ResultSet row) throws SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", row.getString("id"));
        item.put("task_id", row.getString("task_id"));
        item.put("source", row.getString("source"));
        item.put("conclusion", row.getString("conclusion"));
        item.put("next_step", row.getString("next_step"));
        item.put("reviewed", row.getInt("reviewed"));
        item.put("created_at", row.getString("created_at"));
        item.put("status", row.getString("status"));
        item.put("reviewed_by", row.getString("reviewed_by"));
        item.put("reviewed_at", row.getString("reviewed_at"));
        item.put("review_note", row.getString("review_note"));
        item.put("dedupe_key", row.getString("dedupe_key"));
        String raw = row.getString("evidence_json");
        if (raw != null) {
            try {
                item.put("evidence", MAPPER.readValue(raw, new TypeReference<Object>() {}));
            } catch (IOException error) {
                throw new TaskStore.StoreFailure("反馈 evidence 解析失败", error);
            }
        } else {
            item.put("evidence", null);
        }
        return item;
    }

    private static boolean isBlank(String value) {
        return value == null || value.strip().isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(List<?> value) {
        return (List<Object>) value;
    }

    private static String dumps(Object node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (IOException error) {
            throw new TaskStore.StoreFailure("JSON 序列化失败", error);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new TaskStore.StoreFailure("SHA-256 不可用", error);
        }
    }
}
