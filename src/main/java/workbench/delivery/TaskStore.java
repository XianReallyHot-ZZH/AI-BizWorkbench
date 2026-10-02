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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 交付任务存储（上游 {@code workbench/task_store.py} 对应物，L13 讲义 D2）：
 * 三表（tasks/task_events/task_submissions）同形 schema + 九态合法迁移表 +
 * 提交键幂等（fingerprint 比对）+ 并发迁移守卫（原状态条件更新 + version 乐观锁）。
 *
 * <p>词面逐句继承上游中文原文（错误消息、事件 detail、状态名）；与 L01 信用账本
 * 不混库（本件是交付任务面，独立运行库 {@code .runtime/course/L13-delivery/}）。
 * 连接面：每操作短连接 + 事务即 BEGIN IMMEDIATE（对应上游 {@code BEGIN IMMEDIATE}
 * 串行写语义）+ busy_timeout（并发实例等待锁而非立刻失败）。
 *
 * <p>上游 {@code main} 后期增量（spec_text 冻结面、frozen_contract、learning 绑定、
 * WB-L04-BOOTSTRAP 特例）不在本讲断言面（讲义 D1 不采纳清单），如实不建。
 */
public final class TaskStore {

    /** 九态合法迁移表（上游 VALID_TRANSITIONS 逐字同形）。 */
    public static final Map<String, Set<String>> VALID_TRANSITIONS = Map.of(
            "queued", Set.of("spec_ready", "failed", "dead_letter"),
            "spec_ready", Set.of("executing", "failed", "dead_letter"),
            "executing", Set.of("evaluating", "rework", "failed", "dead_letter"),
            "evaluating", Set.of("review", "rework", "failed", "dead_letter"),
            "review", Set.of("completed", "rework", "failed", "dead_letter"),
            "rework", Set.of("executing", "failed", "dead_letter"),
            "completed", Set.of(),
            "failed", Set.of("dead_letter"),
            "dead_letter", Set.of());

    /** 提交键已用于不同需求（上游 TaskSubmissionConflict → HTTP 409）。 */
    public static final class TaskSubmissionConflict extends RuntimeException {
        public TaskSubmissionConflict(String message) {
            super(message);
        }
    }

    /** 任务不存在（上游 KeyError(task_id) → HTTP 404）。 */
    public static final class TaskNotFound extends RuntimeException {
        public TaskNotFound(String taskId) {
            super(taskId);
        }
    }

    /** 存储基础设施失败（不裸栈，保持可读消息面）。 */
    public static final class StoreFailure extends RuntimeException {
        public StoreFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TASK_ID_PATTERN = Pattern.compile("TASK-[A-Z0-9]{10}");

    private final Path dbPath;

    public TaskStore(Path path) {
        this.dbPath = path.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.dbPath.getParent());
        } catch (IOException error) {
            throw new UncheckedIOException("任务库目录创建失败：" + this.dbPath.getParent(), error);
        }
        try (Connection conn = open()) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS tasks(
                          id TEXT PRIMARY KEY, request TEXT NOT NULL, status TEXT NOT NULL,
                          spec_json TEXT, result_json TEXT, error TEXT,
                          requirement_id TEXT NOT NULL DEFAULT '',
                          business_refs_json TEXT NOT NULL DEFAULT '[]',
                          spec_path TEXT NOT NULL DEFAULT 'FDE_SPEC.md',
                          reviewed_by TEXT, review_decision TEXT, review_note TEXT, reviewed_at TEXT,
                          automation_mode TEXT NOT NULL DEFAULT 'manual',
                          execution_mode TEXT NOT NULL DEFAULT 'verify',
                          write_scope_json TEXT NOT NULL DEFAULT '[]',
                          execution_timeout_seconds INTEGER NOT NULL DEFAULT 900,
                          version INTEGER NOT NULL DEFAULT 1,
                          created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )""");
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS task_events(
                          id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL,
                          from_status TEXT, to_status TEXT NOT NULL, detail TEXT,
                          actor TEXT NOT NULL DEFAULT 'system', evidence_json TEXT,
                          created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )""");
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS task_submissions(
                          submission_key TEXT PRIMARY KEY, fingerprint TEXT NOT NULL,
                          task_id TEXT NOT NULL REFERENCES tasks(id)
                        )""");
            }
            conn.commit();
        } catch (SQLException error) {
            throw new StoreFailure("任务库初始化失败：" + dbPath, error);
        }
    }

    public Path path() {
        return dbPath;
    }

    /**
     * 每操作短连接：setAutoCommit(false) + IMMEDIATE 事务模式（驱动在首语句即
     * BEGIN IMMEDIATE——对应上游 {@code BEGIN IMMEDIATE} 串行写语义）+
     * busy_timeout=5000（并发实例等待锁而非立刻失败，上游 sqlite3.connect 默认口径）。
     */
    private Connection open() throws SQLException {
        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
        config.setBusyTimeout(5000);
        config.setSynchronous(org.sqlite.SQLiteConfig.SynchronousMode.FULL);
        config.setTransactionMode(org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE);
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath, config.toProperties());
        conn.setAutoCommit(false);
        return conn;
    }

    /**
     * 只读连接：autocommit、不显式开事务（对应上游 get/list 在 Python sqlite3
     * 默认 DEFERRED 下的只读形态——读不抢写锁；wait 轮询高频读与写线程并存的
     * 关键面）。busy_timeout 兜底 journal 切换瞬间的读锁等待。
     */
    private Connection openRead() throws SQLException {
        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
        config.setBusyTimeout(5000);
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath, config.toProperties());
        return conn;
    }

    // ---- create ------------------------------------------------------------------

    /**
     * 建任务（上游 create 同形）：全词面校验 + 提交键幂等（同键同指纹返回既有任务，
     * 异指纹 TaskSubmissionConflict「请使用新键」——幂等键永不失效）+ 首事件「任务已接收」。
     */
    public Map<String, Object> create(String request, String requirementId, List<String> businessRefs,
            String specPath, String actor, String automationMode, String taskId, String executionMode,
            List<String> writeScope, int executionTimeoutSeconds, String submissionKey) {
        if (request == null || request.strip().isEmpty()) {
            throw new IllegalArgumentException("任务需求不能为空");
        }
        List<String> refs = new ArrayList<>();
        for (Object value : businessRefs == null ? List.of() : businessRefs) {
            String item = String.valueOf(value).strip();
            if (!item.isEmpty()) {
                refs.add(item);
            }
        }
        if (new LinkedHashSet<>(refs).size() != refs.size()) {
            throw new IllegalArgumentException("业务对象引用不能重复");
        }
        if (specPath == null || specPath.strip().isEmpty()) {
            throw new IllegalArgumentException("Spec 路径不能为空");
        }
        if (!"manual".equals(automationMode) && !"automatic".equals(automationMode)) {
            throw new IllegalArgumentException("automation_mode 必须是 manual 或 automatic");
        }
        if (!"verify".equals(executionMode) && !"codex".equals(executionMode)) {
            throw new IllegalArgumentException("execution_mode 必须是 verify 或 codex");
        }
        List<String> scopes = writeScope == null ? List.of() : writeScope;
        if ("codex".equals(executionMode) && scopes.isEmpty()) {
            throw new IllegalArgumentException("Codex 代码执行至少需要一个明确写入范围");
        }
        if (executionTimeoutSeconds < 30 || executionTimeoutSeconds > 3600) {
            throw new IllegalArgumentException("execution_timeout_seconds 必须在 30..3600");
        }
        String id = taskId == null || taskId.isEmpty()
                ? "TASK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase()
                : taskId;
        if (!TASK_ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("任务编号格式无效");
        }
        String normalizedKey = null;
        if (submissionKey != null) {
            normalizedKey = submissionKey.strip();
            if (normalizedKey.isEmpty() || normalizedKey.length() > 200) {
                throw new IllegalArgumentException("提交键不能为空且不能超过200字符");
            }
        }
        Map<String, Object> fingerprintPayload = new TreeMap<>();
        fingerprintPayload.put("request", request.strip());
        fingerprintPayload.put("requirement", requirementId == null ? "" : requirementId.strip());
        fingerprintPayload.put("refs", refs);
        fingerprintPayload.put("actor", actor == null ? "" : actor.strip());
        fingerprintPayload.put("mode", executionMode);
        fingerprintPayload.put("scope", scopes);
        fingerprintPayload.put("timeout", executionTimeoutSeconds);
        fingerprintPayload.put("automation", automationMode);
        String fingerprint = sha256(dumps(fingerprintPayload));
        try (Connection conn = open()) {
            if (normalizedKey != null) {
                String existingId = null;
                String existingFingerprint = null;
                try (PreparedStatement query = conn.prepareStatement(
                        "SELECT task_id, fingerprint FROM task_submissions WHERE submission_key=?")) {
                    query.setString(1, normalizedKey);
                    try (ResultSet row = query.executeQuery()) {
                        if (row.next()) {
                            existingId = row.getString("task_id");
                            existingFingerprint = row.getString("fingerprint");
                        }
                    }
                }
                if (existingId != null) {
                    if (!fingerprint.equals(existingFingerprint)) {
                        throw new TaskSubmissionConflict("提交键已用于不同需求；请使用新键");
                    }
                    conn.commit();
                    return get(existingId);
                }
            }
            try (PreparedStatement insert = conn.prepareStatement("""
                    INSERT INTO tasks(id,request,status,requirement_id,business_refs_json,spec_path,
                      automation_mode,execution_mode,write_scope_json,execution_timeout_seconds)
                    VALUES(?,?,?,?,?,?,?,?,?,?)""")) {
                insert.setString(1, id);
                insert.setString(2, request.strip());
                insert.setString(3, "queued");
                insert.setString(4, requirementId == null ? "" : requirementId.strip());
                insert.setString(5, dumps(refs));
                insert.setString(6, specPath.strip());
                insert.setString(7, automationMode);
                insert.setString(8, executionMode);
                insert.setString(9, dumps(scopes));
                insert.setInt(10, executionTimeoutSeconds);
                insert.executeUpdate();
            }
            Map<String, Object> received = new LinkedHashMap<>();
            received.put("requirement_id", requirementId == null ? "" : requirementId.strip());
            received.put("business_refs", refs);
            received.put("spec_path", specPath.strip());
            received.put("automation_mode", automationMode);
            received.put("execution_mode", executionMode);
            received.put("write_scope", scopes);
            received.put("execution_timeout_seconds", executionTimeoutSeconds);
            insertEvent(conn, id, null, "queued", "任务已接收",
                    actor == null || actor.strip().isEmpty() ? "system" : actor.strip(), received);
            if (normalizedKey != null) {
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO task_submissions VALUES(?,?,?)")) {
                    insert.setString(1, normalizedKey);
                    insert.setString(2, fingerprint);
                    insert.setString(3, id);
                    insert.executeUpdate();
                }
            }
            conn.commit();
        } catch (SQLException error) {
            throw new StoreFailure("任务创建失败：" + id, error);
        }
        return get(id);
    }

    // ---- get / list ------------------------------------------------------------------

    /** 单任务视图（含 events 全序，evidence 反序列化；不存在抛 TaskNotFound）。 */
    public Map<String, Object> get(String taskId) {
        try (Connection conn = openRead()) {
            return readTask(conn, taskId);
        } catch (SQLException error) {
            throw new StoreFailure("任务读取失败：" + taskId + "：" + error.getMessage(), error);
        }
    }

    /** 任务列表（默认 30，上游同形；每条含 events）。 */
    public List<Map<String, Object>> list(int limit) {
        try (Connection conn = openRead()) {
            List<Map<String, Object>> items = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            try (PreparedStatement query = conn.prepareStatement(
                    "SELECT id FROM tasks ORDER BY created_at DESC, rowid DESC LIMIT ?")) {
                query.setInt(1, limit);
                try (ResultSet row = query.executeQuery()) {
                    while (row.next()) {
                        ids.add(row.getString("id"));
                    }
                }
            }
            for (String id : ids) {
                items.add(readTask(conn, id));
            }
            return items;
        } catch (SQLException error) {
            throw new StoreFailure("任务列表读取失败", error);
        }
    }

    // ---- appendEvent / transition / review ------------------------------------------------------------------

    /** 追加事件并返回任务视图（append-only，无状态迁移）。 */
    public Map<String, Object> appendEvent(String taskId, String detail, String actor, Object evidence) {
        try (Connection conn = open()) {
            Map<String, Object> task = readTask(conn, taskId);
            insertEvent(conn, taskId, (String) task.get("status"), (String) task.get("status"),
                    detail, actor == null || actor.strip().isEmpty() ? "system" : actor.strip(), evidence);
            conn.commit();
        } catch (SQLException error) {
            throw new StoreFailure("事件追加失败：" + taskId, error);
        }
        return get(taskId);
    }

    /**
     * 状态迁移（上游 transition 同形）：合法边校验「非法任务状态迁移：x -&gt; y」；
     * completed 双守卫（具名 approve「完成交付必须由具名审核人明确 approve」+
     * 报告绿「阻断级 Eval 未通过，不能批准完成」）；并发守卫
     * 「任务状态已变化，拒绝并发迁移」（原状态条件更新 rowcount）；version+1 乐观锁。
     *
     * @param extras 可选载荷键：spec / result / error / reviewer / review_decision / review_note
     */
    public Map<String, Object> transition(String taskId, String toStatus, String detail, String actor,
            Object evidence, Map<String, Object> extras) {
        Map<String, Object> extra = extras == null ? Map.of() : extras;
        try (Connection conn = open()) {
            String current;
            String resultJson;
            try (PreparedStatement query = conn.prepareStatement(
                    "SELECT status, result_json FROM tasks WHERE id=?")) {
                query.setString(1, taskId);
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) {
                        throw new TaskNotFound(taskId);
                    }
                    current = row.getString("status");
                    resultJson = row.getString("result_json");
                }
            }
            if (!VALID_TRANSITIONS.getOrDefault(current, Set.of()).contains(toStatus)) {
                throw new IllegalArgumentException("非法任务状态迁移：" + current + " -> " + toStatus);
            }
            String reviewer = text(extra.get("reviewer"));
            String decision = text(extra.get("review_decision"));
            String reviewNote = text(extra.get("review_note"));
            if ("completed".equals(toStatus)) {
                if (reviewer.isEmpty() || !"approve".equals(decision)) {
                    throw new IllegalArgumentException("完成交付必须由具名审核人明确 approve");
                }
                Map<String, Object> result = null;
                if (resultJson != null && parse(resultJson) instanceof Map<?, ?> parsedResult) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> castResult = (Map<String, Object>) parsedResult;
                    result = castResult;
                }
                Map<String, Object> summary;
                if (result != null && result.get("summary") instanceof Map<?, ?> summaryMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> castSummary = (Map<String, Object>) summaryMap;
                    summary = castSummary;
                } else {
                    summary = Map.of();
                }
                Object decisionField = summary.get("decision");
                Object blockingFailed = summary.get("blocking_failed");
                if (!"pass".equals(decisionField)
                        || blockingFailed == null || ((Number) blockingFailed).intValue() != 0) {
                    throw new IllegalArgumentException("阻断级 Eval 未通过，不能批准完成");
                }
            }
            String specJson = extra.containsKey("spec") ? dumps(extra.get("spec")) : null;
            String newResultJson = extra.containsKey("result") ? dumps(extra.get("result")) : null;
            String error = text(extra.get("error"));
            try (PreparedStatement update = conn.prepareStatement("""
                    UPDATE tasks SET status=?,spec_json=COALESCE(?,spec_json),
                      result_json=COALESCE(?,result_json),error=COALESCE(?,error),
                      reviewed_by=COALESCE(?,reviewed_by),review_decision=COALESCE(?,review_decision),
                      review_note=COALESCE(?,review_note),
                      reviewed_at=CASE WHEN ? IS NOT NULL THEN CURRENT_TIMESTAMP ELSE reviewed_at END,
                      version=version+1,updated_at=CURRENT_TIMESTAMP
                    WHERE id=? AND status=?""")) {
                update.setString(1, toStatus);
                update.setString(2, specJson);
                update.setString(3, newResultJson);
                update.setString(4, error.isEmpty() ? null : error);
                update.setString(5, reviewer.isEmpty() ? null : reviewer);
                update.setString(6, decision.isEmpty() ? null : decision);
                update.setString(7, reviewNote.isEmpty() ? null : reviewNote);
                update.setString(8, reviewer.isEmpty() ? null : reviewer);
                update.setString(9, taskId);
                update.setString(10, current);
                if (update.executeUpdate() != 1) {
                    throw new IllegalArgumentException("任务状态已变化，拒绝并发迁移：" + current + " -> " + toStatus);
                }
            }
            insertEvent(conn, taskId, current, toStatus, detail,
                    actor == null || actor.strip().isEmpty() ? "system" : actor.strip(), evidence);
            conn.commit();
        } catch (SQLException error) {
            throw new StoreFailure("状态迁移失败：" + taskId + " -> " + toStatus, error);
        }
        return get(taskId);
    }

    /**
     * 老板终审（上游 review 同形，断言面子集）：Agent 不能代老板终审；
     * 决定只认 approve/reject；理由必填；approve → completed / reject → rework，
     * detail「老板终审：{decision}；{note}」+ evidence opc_final。
     */
    public Map<String, Object> review(String taskId, String reviewer, String decision, String note) {
        String identity = reviewer == null ? "" : reviewer.strip();
        if (identity.isEmpty()) {
            throw new IllegalArgumentException("老板身份不能为空");
        }
        if (identity.startsWith("agent:")) {
            throw new IllegalArgumentException("Agent 员工不能代替老板终审；请用老板身份提交验收");
        }
        String normalized = decision == null ? "" : decision.strip().toLowerCase();
        String opinion = note == null ? "" : note.strip();
        if (!"approve".equals(normalized) && !"reject".equals(normalized)) {
            throw new IllegalArgumentException("审核决定必须是 approve 或 reject");
        }
        if (opinion.isEmpty()) {
            throw new IllegalArgumentException("审核理由不能为空");
        }
        String target = "approve".equals(normalized) ? "completed" : "rework";
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("reviewer", identity);
        evidence.put("decision", normalized);
        evidence.put("note", opinion);
        evidence.put("opc_final", true);
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("reviewer", identity);
        extras.put("review_decision", normalized);
        extras.put("review_note", opinion);
        return transition(taskId, target, "老板终审：" + normalized + "；" + opinion,
                identity, evidence, extras);
    }

    // ---- 内部 ------------------------------------------------------------------

    /**
     * 重启恢复面（上游 recover_automatic_tasks 同形）：中断的 automatic 任务——
     * codex 转死信「代码任务中断，禁止自动重复写入」（进程崩溃丢失执行器活所有权），
     * verify 转安全重放（executing/evaluating → rework）；再收 queued/spec_ready
     * 与重放集，按创建序返回待恢复 id。
     */
    public List<String> recoverAutomaticTasks() {
        List<String> replayable = new ArrayList<>();
        try (Connection conn = open()) {
            List<String[]> interrupted = new ArrayList<>();
            try (Statement st = conn.createStatement();
                    ResultSet row = st.executeQuery(
                            "SELECT id,status,execution_mode FROM tasks WHERE automation_mode='automatic' "
                                    + "AND status IN ('executing','evaluating')")) {
                while (row.next()) {
                    interrupted.add(new String[] {
                            row.getString("id"), row.getString("status"), row.getString("execution_mode")});
                }
            }
            for (String[] task : interrupted) {
                String id = task[0];
                String status = task[1];
                if ("codex".equals(task[2])) {
                    try (PreparedStatement update = conn.prepareStatement(
                            "UPDATE tasks SET status='dead_letter',error=?,version=version+1,"
                                    + "updated_at=CURRENT_TIMESTAMP WHERE id=?")) {
                        update.setString(1, "代码执行中断，需人工核对残留进程、Diff与证据后另行授权");
                        update.setString(2, id);
                        update.executeUpdate();
                    }
                    insertEvent(conn, id, status, "dead_letter", "代码任务中断，禁止自动重复写入",
                            "automation-recovery", Map.of("safe_replay", false, "human_review_required", true));
                    continue;
                }
                replayable.add(id);
                try (PreparedStatement update = conn.prepareStatement(
                        "UPDATE tasks SET status='rework',error=?,version=version+1,"
                                + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND status=?")) {
                    update.setString(1, "服务重启时任务停在 " + status + "，已转入安全重放");
                    update.setString(2, id);
                    update.setString(3, status);
                    update.executeUpdate();
                }
                insertEvent(conn, id, status, "rework", "检测到中断执行，进入安全重放",
                        "automation-recovery", Map.of("safe_replay", true));
            }
            List<String> resumable = new ArrayList<>();
            StringBuilder sql = new StringBuilder(
                    "SELECT id FROM tasks WHERE automation_mode='automatic' "
                            + "AND (status IN ('queued','spec_ready')");
            if (!replayable.isEmpty()) {
                sql.append(" OR id IN (");
                sql.append(String.join(",", java.util.Collections.nCopies(replayable.size(), "?")));
                sql.append(")");
            }
            sql.append(") ORDER BY created_at,id");
            try (PreparedStatement query = conn.prepareStatement(sql.toString())) {
                for (int index = 0; index < replayable.size(); index++) {
                    query.setString(index + 1, replayable.get(index));
                }
                try (ResultSet row = query.executeQuery()) {
                    while (row.next()) {
                        resumable.add(row.getString("id"));
                    }
                }
            }
            conn.commit();
            return resumable;
        } catch (SQLException error) {
            throw new StoreFailure("重启恢复查询失败", error);
        }
    }

    private Map<String, Object> readTask(Connection conn, String taskId) throws SQLException {
        Map<String, Object> task;
        try (PreparedStatement query = conn.prepareStatement("SELECT * FROM tasks WHERE id=?")) {
            query.setString(1, taskId);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) {
                    throw new TaskNotFound(taskId);
                }
                task = new LinkedHashMap<>();
                task.put("id", row.getString("id"));
                task.put("request", row.getString("request"));
                task.put("status", row.getString("status"));
                task.put("spec", parseOrNull(row.getString("spec_json")));
                task.put("result", parseOrNull(row.getString("result_json")));
                task.put("error", row.getString("error"));
                task.put("requirement_id", row.getString("requirement_id"));
                task.put("business_refs", parseOrList(row.getString("business_refs_json")));
                task.put("spec_path", row.getString("spec_path"));
                task.put("reviewed_by", row.getString("reviewed_by"));
                task.put("review_decision", row.getString("review_decision"));
                task.put("review_note", row.getString("review_note"));
                task.put("reviewed_at", row.getString("reviewed_at"));
                task.put("automation_mode", row.getString("automation_mode"));
                task.put("execution_mode", row.getString("execution_mode"));
                task.put("write_scope", parseOrList(row.getString("write_scope_json")));
                task.put("execution_timeout_seconds", row.getInt("execution_timeout_seconds"));
                task.put("version", row.getInt("version"));
                task.put("created_at", row.getString("created_at"));
                task.put("updated_at", row.getString("updated_at"));
            }
        }
        List<Map<String, Object>> events = new ArrayList<>();
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT * FROM task_events WHERE task_id=? ORDER BY id")) {
            query.setString(1, taskId);
            try (ResultSet row = query.executeQuery()) {
                while (row.next()) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("id", row.getInt("id"));
                    event.put("task_id", row.getString("task_id"));
                    event.put("from_status", row.getString("from_status"));
                    event.put("to_status", row.getString("to_status"));
                    event.put("detail", row.getString("detail"));
                    event.put("actor", row.getString("actor"));
                    event.put("evidence", parseOrNull(row.getString("evidence_json")));
                    event.put("created_at", row.getString("created_at"));
                    events.add(event);
                }
            }
        }
        task.put("events", events);
        return task;
    }

    private void insertEvent(Connection conn, String taskId, String fromStatus, String toStatus,
            String detail, String actor, Object evidence) throws SQLException {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO task_events(task_id,from_status,to_status,detail,actor,evidence_json) "
                        + "VALUES(?,?,?,?,?,?)")) {
            insert.setString(1, taskId);
            insert.setString(2, fromStatus);
            insert.setString(3, toStatus);
            insert.setString(4, detail);
            insert.setString(5, actor);
            insert.setString(6, evidence == null ? null : dumps(evidence));
            insert.executeUpdate();
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    private static String dumps(Object node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (IOException error) {
            throw new StoreFailure("JSON 序列化失败", error);
        }
    }

    private static Object parseOrNull(String raw) {
        return raw == null ? null : parse(raw);
    }

    private static List<Object> parseOrList(String raw) {
        if (raw == null) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(raw, new TypeReference<List<Object>>() {});
        } catch (IOException error) {
            throw new StoreFailure("JSON 列解析失败：" + raw, error);
        }
    }

    private static Object parse(String raw) {
        try {
            return MAPPER.readValue(raw, new TypeReference<Object>() {});
        } catch (IOException error) {
            throw new StoreFailure("JSON 解析失败：" + raw, error);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new StoreFailure("SHA-256 不可用", error);
        }
    }
}
