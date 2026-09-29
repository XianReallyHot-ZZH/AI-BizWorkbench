package workbench.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 存储 + 任务/记录逻辑（镜像 workbench/bootstrap.py 的存储与逻辑段）。
 *
 * <p>信用内核口径（L01 钉死）：账本只追加、失败不可抹、读命令绝不建库建目录，
 * status 每次重算 SHA-256 复核、链判定三规则（同命令红绿 / Diff 严格居间 /
 * 全局最新须为成功绿，与上游 _chain 语义一致）。
 */
public final class Ledger implements AutoCloseable {

    public static final String WORKBENCH_VERSION = "V0.1";
    public static final String DEFAULT_WORKBENCH_NAME = "个人 AI 研发工作台";
    public static final boolean FLOWERP_CONNECTED = false;  // 冻结边界：L01 不接入 FlowERP
    public static final List<String> PHASES = List.of("red", "diff", "green", "observation");
    public static final String UNINITIALIZED = "工作台尚未初始化，请先运行 workbench-init";
    public static final String MISSING_CHAIN = "same_command_red_diff_green_missing";
    public static final String MISSING_TASK = "required_task_missing";
    public static final List<String> LIMITATIONS = List.of(
            "用户导入的观察记录，未认证命令执行者和时间",
            "有效红灯原因、Diff 写集与 Spec 签署仍须人工核验；完整性不等于验收完成");
    public static final Set<String> EXECUTION_COMPLETE_STATUSES = Set.of("completed", "verify_completed");

    /** 运行库不可用（目录不可创建、文件非 sqlite 账本等基础设施失败）。 */
    public static final class LedgerUnavailable extends RuntimeException {
        public LedgerUnavailable(String message) {
            super(message);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 单一 DDL 来源（与 Python 载体 _SCHEMA 同形：L04 执行/复核 + S01 记忆五表，
     * init 建库共用；旧账迁移在后续重走讲次按需重建）。
     */
    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS workbench (
              id INTEGER PRIMARY KEY CHECK (id = 1),
              workbench_id TEXT NOT NULL,
              name TEXT NOT NULL,
              owner TEXT NOT NULL,
              version TEXT NOT NULL,
              created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS projects (
              project_id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              path TEXT NOT NULL DEFAULT '.',
              purpose TEXT NOT NULL DEFAULT '',
              created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS tasks (
              task_id TEXT PRIMARY KEY,
              project_id TEXT NOT NULL,
              request TEXT NOT NULL,
              requirement_id TEXT NOT NULL,
              actor TEXT NOT NULL DEFAULT '',
              requirement_snapshot TEXT NOT NULL DEFAULT '',
              requirement_summary TEXT NOT NULL DEFAULT '',
              problem_snapshot TEXT,
              problem_summary TEXT,
              prerequisite_task_id TEXT NOT NULL DEFAULT '',
              created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS evidence (
              record_id INTEGER PRIMARY KEY AUTOINCREMENT,
              task_id TEXT NOT NULL,
              phase TEXT NOT NULL,
              command_text TEXT NOT NULL,
              output_text TEXT NOT NULL,
              output_sha256 TEXT NOT NULL,
              returncode INTEGER NOT NULL,
              observed_at TEXT NOT NULL,
              recorded_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS evidence_by_task ON evidence (task_id);
            CREATE TABLE IF NOT EXISTS executions (
              execution_id INTEGER PRIMARY KEY AUTOINCREMENT,
              task_id TEXT NOT NULL,
              mode TEXT NOT NULL,
              workspace TEXT NOT NULL,
              write_scope TEXT NOT NULL DEFAULT '[]',
              executor_command TEXT,
              executor_prompt TEXT,
              returncode INTEGER,
              timed_out INTEGER NOT NULL DEFAULT 0,
              stdout_text TEXT NOT NULL DEFAULT '',
              stderr_text TEXT NOT NULL DEFAULT '',
              stdout_sha256 TEXT NOT NULL DEFAULT '',
              stderr_sha256 TEXT NOT NULL DEFAULT '',
              changed_files TEXT NOT NULL DEFAULT '[]',
              out_of_scope_files TEXT NOT NULL DEFAULT '[]',
              change_manifest TEXT NOT NULL DEFAULT '[]',
              change_manifest_sha256 TEXT NOT NULL DEFAULT '',
              diff_text TEXT NOT NULL DEFAULT '',
              diff_sha256 TEXT NOT NULL DEFAULT '',
              eval_command TEXT NOT NULL DEFAULT '[]',
              eval_returncode INTEGER,
              eval_output_text TEXT NOT NULL DEFAULT '',
              eval_output_sha256 TEXT NOT NULL DEFAULT '',
              eval_timed_out INTEGER NOT NULL DEFAULT 0,
              status TEXT NOT NULL,
              actor TEXT NOT NULL DEFAULT '',
              observed_at TEXT NOT NULL,
              recorded_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS executions_by_task ON executions (task_id);
            CREATE TABLE IF NOT EXISTS reviews (
              review_id INTEGER PRIMARY KEY AUTOINCREMENT,
              task_id TEXT NOT NULL,
              execution_id INTEGER NOT NULL,
              reviewer TEXT NOT NULL,
              decision TEXT NOT NULL,
              note TEXT NOT NULL DEFAULT '',
              reviewed_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS reviews_by_task ON reviews (task_id);
            CREATE TABLE IF NOT EXISTS learning_assets (
              asset_id TEXT PRIMARY KEY,
              family TEXT NOT NULL,
              version INTEGER NOT NULL,
              project_id TEXT NOT NULL,
              kind TEXT NOT NULL DEFAULT 'memory',
              state TEXT NOT NULL DEFAULT 'candidate',
              approved_by TEXT NOT NULL DEFAULT '',
              payload TEXT NOT NULL,
              sha256 TEXT NOT NULL,
              created_at TEXT NOT NULL,
              UNIQUE(family, version)
            );
            CREATE TABLE IF NOT EXISTS learning_events (
              event_id INTEGER PRIMARY KEY AUTOINCREMENT,
              asset_id TEXT NOT NULL,
              actor TEXT NOT NULL,
              action TEXT NOT NULL,
              note TEXT NOT NULL DEFAULT '',
              evidence TEXT NOT NULL DEFAULT '{}',
              at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS learning_events_by_asset ON learning_events (asset_id);
            CREATE TABLE IF NOT EXISTS learning_recalls (
              recall_id TEXT PRIMARY KEY,
              project_id TEXT NOT NULL,
              task_id TEXT NOT NULL,
              payload TEXT NOT NULL,
              sha256 TEXT NOT NULL,
              created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS learning_bindings (
              binding_id TEXT PRIMARY KEY,
              recall_id TEXT NOT NULL,
              plan_id TEXT NOT NULL UNIQUE,
              task_id TEXT NOT NULL UNIQUE,
              payload TEXT NOT NULL,
              sha256 TEXT NOT NULL,
              created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS learning_runs (
              run_id INTEGER PRIMARY KEY AUTOINCREMENT,
              binding_id TEXT NOT NULL,
              phase TEXT NOT NULL,
              payload TEXT NOT NULL DEFAULT '{}',
              at TEXT NOT NULL,
              UNIQUE(binding_id, phase)
            );
            CREATE INDEX IF NOT EXISTS learning_runs_by_binding ON learning_runs (binding_id);
            """;

    private static final List<String> EXECUTION_JSON_COLUMNS = List.of(
            "write_scope", "executor_command", "changed_files", "out_of_scope_files",
            "change_manifest", "eval_command");

    private final Connection connection;

    private Ledger(Connection connection) {
        this.connection = connection;
    }

    // ---- 存储 ----

    /** UTC 观察时刻，Python isoformat 同形（+00:00 后缀；golden 侧掩码）。 */
    public static String now() {
        return LocalDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "+00:00";
    }

    private static void execScript(Connection conn, String script) throws SQLException {
        for (String statement : script.split(";")) {
            String sql = statement.strip();
            if (!sql.isEmpty()) {
                try (Statement st = conn.createStatement()) {
                    st.execute(sql);
                }
            }
        }
    }

    /**
     * create=false 时绝不建库建目录（status 不偷偷初始化），且校验账本可用；
     * 空文件/非账本文件按"尚未初始化"处理（返回 null），不裸崩。
     */
    public static Ledger open(Path runtimeDir, boolean create) {
        Path db = runtimeDir.resolve("workbench.db");
        if (create) {
            try {
                Files.createDirectories(runtimeDir);
                Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
                execScript(conn, SCHEMA);
                return new Ledger(conn);
            } catch (IOException | SQLException error) {
                throw new LedgerUnavailable("运行库不可用：" + db + "（" + error.getMessage() + "）");
            }
        }
        if (!Files.exists(db)) {
            return null;
        }
        try {
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'workbench'")) {
                if (!rs.next()) {
                    conn.close();
                    return null;
                }
                return new Ledger(conn);
            } catch (SQLException error) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                    // 已按"尚未初始化"处理
                }
                return null;
            }
        } catch (SQLException error) {
            return null;
        }
    }

    /** 存储单一入口的同一连接（镜像 Python：execution.py 命令层持同一 conn 直插执行/复核记录，写侧 SQL 不复制进 bootstrap）。 */
    public Connection connection() {
        return connection;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 只读收尾，保持安静
        }
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) {
                out.append("%02x".formatted(b & 0xff));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    /** 镜像 _parse_aware：必须带时区；'Z' 后缀合法；缺时区/无法解析一律 null。 */
    public static OffsetDateTime parseAware(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException error) {
            return null;
        }
    }

    // ---- 行与表 ----

    private static Map<String, Object> rowToMap(ResultSet rs) throws SQLException {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            row.put(meta.getColumnLabel(i), rs.getObject(i));
        }
        return row;
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    public boolean hasTable(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Map<String, Object> workbenchRow() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM workbench WHERE id = 1")) {
            return rs.next() ? rowToMap(rs) : null;
        }
    }

    public boolean projectExists(String projectId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM projects WHERE project_id = ?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean taskExists(String taskId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM tasks WHERE task_id = ?")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 任务行全字段（镜像 _cmd_task_show 的 SELECT *；缺任务返回 null，读侧单一来源）。 */
    public Map<String, Object> taskRow(String taskId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM tasks WHERE task_id = ?")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToMap(rs) : null;
            }
        }
    }

    public void insertWorkbench(String workbenchId, String name, String owner) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO workbench (workbench_id, name, owner, version, created_at) VALUES (?,?,?,?,?)")) {
            ps.setString(1, workbenchId);
            ps.setString(2, name);
            ps.setString(3, owner);
            ps.setString(4, WORKBENCH_VERSION);
            ps.setString(5, now());
            ps.executeUpdate();
        }
    }

    public void insertProject(String projectId, String name, String path, String purpose)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO projects (project_id, name, path, purpose, created_at) VALUES (?,?,?,?,?)")) {
            ps.setString(1, projectId);
            ps.setString(2, name);
            ps.setString(3, path);
            ps.setString(4, purpose);
            ps.setString(5, now());
            ps.executeUpdate();
        }
    }

    public void insertTask(String taskId, String projectId, String request, String requirementId,
                           String actor, String requirementSnapshot, String requirementSummary,
                           String problemSnapshot, String problemSummary, String prerequisite)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO tasks (task_id, project_id, request, requirement_id, actor,"
                        + " requirement_snapshot, requirement_summary, problem_snapshot, problem_summary,"
                        + " prerequisite_task_id, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, taskId);
            ps.setString(2, projectId);
            ps.setString(3, request);
            ps.setString(4, requirementId);
            ps.setString(5, actor);
            ps.setString(6, requirementSnapshot);
            ps.setString(7, requirementSummary);
            ps.setObject(8, problemSnapshot);
            ps.setObject(9, problemSummary);
            ps.setString(10, prerequisite);
            ps.setString(11, now());
            ps.executeUpdate();
        }
    }

    public long insertEvidence(String taskId, String phase, String commandText, String outputText,
                               String outputSha256, int returncode, String observedAt)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO evidence (task_id, phase, command_text, output_text, output_sha256,"
                        + " returncode, observed_at, recorded_at) VALUES (?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, taskId);
            ps.setString(2, phase);
            ps.setString(3, commandText);
            ps.setString(4, outputText);
            ps.setString(5, outputSha256);
            ps.setInt(6, returncode);
            ps.setString(7, observedAt);
            ps.setString(8, now());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    // ---- 查询面 ----

    public List<Map<String, Object>> evidenceFor(String taskId) throws SQLException {
        List<Map<String, Object>> evidence = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM evidence WHERE task_id = ? ORDER BY record_id")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    evidence.add(rowToMap(rs));
                }
            }
        }
        return evidence;
    }

    public List<Map<String, Object>> executionsFor(String taskId) throws SQLException {
        if (!hasTable("executions")) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM executions WHERE task_id = ? ORDER BY execution_id")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(executionPayload(rs));
                }
            }
        }
        return out;
    }

    /** 出账形态（镜像 _execution_payload）：JSON 文本列还原为结构化值，timed_out 归一 bool。 */
    private Map<String, Object> executionPayload(ResultSet rs) throws SQLException {
        Map<String, Object> payload = rowToMap(rs);
        String rawManifest = (String) payload.get("change_manifest");
        for (String column : EXECUTION_JSON_COLUMNS) {
            Object raw = payload.get(column);
            Object value;
            if (raw == null || raw.toString().isEmpty()) {
                value = "executor_command".equals(column) ? null : new ArrayList<>();
            } else {
                try {
                    value = MAPPER.readValue(raw.toString(), Object.class);
                } catch (IOException error) {
                    value = new ArrayList<>();
                }
            }
            payload.put(column, value);
        }
        payload.put("change_manifest_json", rawManifest);
        payload.put("timed_out", intValue(payload.get("timed_out")) != 0);
        payload.put("eval_timed_out", intValue(payload.get("eval_timed_out")) != 0);
        return payload;
    }

    public List<Map<String, Object>> reviewsFor(String taskId) throws SQLException {
        if (!hasTable("reviews")) {
            return List.of();
        }
        List<Map<String, Object>> reviews = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM reviews WHERE task_id = ? ORDER BY review_id")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    reviews.add(rowToMap(rs));
                }
            }
        }
        return reviews;
    }

    /** 最近一次执行（镜像 _latest_execution；L04 执行/复核命令层同源取用，Python import 先例）。 */
    public Map<String, Object> latestExecution(String taskId) throws SQLException {
        if (!hasTable("executions")) {
            return null;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM executions WHERE task_id = ? ORDER BY execution_id DESC LIMIT 1")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToMap(rs) : null;
            }
        }
    }

    /** 最近一次复核（镜像 _latest_review；同 latestExecution 的命令层取用先例）。 */
    public Map<String, Object> latestReview(String taskId) throws SQLException {
        if (!hasTable("reviews")) {
            return null;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM reviews WHERE task_id = ? ORDER BY review_id DESC LIMIT 1")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rowToMap(rs) : null;
            }
        }
    }

    /**
     * 从只追加记录派生任务状态（镜像 _task_state）：无执行 → no_execution；
     * 最近复核恰好覆盖最近执行 → accepted/rejected；最近执行完成态 → review（等人）；
     * 其余执行结局原样即状态。
     */
    public String taskState(String taskId) throws SQLException {
        Map<String, Object> execution = latestExecution(taskId);
        if (execution == null) {
            return "no_execution";
        }
        Map<String, Object> review = latestReview(taskId);
        if (review != null && intValue(review.get("execution_id")) == intValue(execution.get("execution_id"))) {
            return "approve".equals(review.get("decision")) ? "accepted" : "rejected";
        }
        String status = String.valueOf(execution.get("status"));
        return EXECUTION_COMPLETE_STATUSES.contains(status) ? "review" : status;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> loadProjects() throws SQLException {
        List<Map<String, Object>> projects = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM projects ORDER BY created_at, project_id")) {
            while (rs.next()) {
                Map<String, Object> project = rowToMap(rs);
                List<Map<String, Object>> tasks = new ArrayList<>();
                String projectId = (String) project.get("project_id");
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT * FROM tasks WHERE project_id = ? ORDER BY created_at, task_id")) {
                    ps.setString(1, projectId);
                    try (ResultSet trs = ps.executeQuery()) {
                        while (trs.next()) {
                            Map<String, Object> task = rowToMap(trs);
                            // 兼容旧账本：缺 prerequisite_task_id 列（V0 迁移前）按空读
                            task.putIfAbsent("prerequisite_task_id", "");
                            String taskId = (String) task.get("task_id");
                            task.put("evidence", evidenceFor(taskId));
                            task.put("executions", executionsFor(taskId));
                            task.put("reviews", reviewsFor(taskId));
                            tasks.add(task);
                        }
                    }
                }
                project.put("tasks", tasks);
                projects.add(project);
            }
        }
        return projects;
    }

    // ---- 判定逻辑 ----

    /**
     * 同命令红—绿链判定（镜像 _chain_error）：返回 null 表示完整。
     * 完整 = 任一与绿同命令的红(rc≠0)，其间夹一条成功 Diff（时间严格介于红绿之间），
     * 且全局最新的 red/diff/green 记录就是这条成功绿（observation 不参与；
     * 绿后任何相——跨命令亦然——再出新 red/diff/green 即旧绿灯失权）。
     */
    public static String chainError(List<Map<String, Object>> records) {
        record Check(OffsetDateTime moment, Map<String, Object> rec) {}
        List<Check> checks = new ArrayList<>();
        for (Map<String, Object> record : records) {
            OffsetDateTime moment = parseAware((String) record.get("observed_at"));
            if (moment == null) {
                return MISSING_CHAIN;
            }
            if (List.of("red", "diff", "green").contains(record.get("phase"))) {
                checks.add(new Check(moment, record));
            }
        }
        if (checks.isEmpty()) {
            return MISSING_CHAIN;
        }
        Check latest = checks.stream()
                .max(Comparator.comparing(Check::moment)
                        .thenComparing(check -> intValue(check.rec().get("record_id"))))
                .orElseThrow();
        Map<String, Object> green = latest.rec();
        if (!"green".equals(green.get("phase")) || intValue(green.get("returncode")) != 0) {
            return MISSING_CHAIN;
        }
        OffsetDateTime latestTime = latest.moment();
        for (Check check : checks) {
            Map<String, Object> red = check.rec();
            if (!"red".equals(red.get("phase")) || intValue(red.get("returncode")) == 0) {
                continue;
            }
            if (!red.get("command_text").equals(green.get("command_text"))) {
                continue;
            }
            OffsetDateTime redTime = check.moment();
            boolean hasDiffBetween = checks.stream().anyMatch(candidate -> {
                Map<String, Object> rec = candidate.rec();
                return "diff".equals(rec.get("phase"))
                        && intValue(rec.get("returncode")) == 0
                        && redTime.isBefore(candidate.moment())
                        && candidate.moment().isBefore(latestTime);
            });
            if (hasDiffBetween) {
                return null;
            }
        }
        return MISSING_CHAIN;
    }

    /** 复核存储内容与 SHA-256 摘要（镜像 _digest_problems，词面逐字同形）。 */
    @SuppressWarnings("unchecked")
    public static List<String> digestProblems(List<Map<String, Object>> projects) {
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> project : projects) {
            for (Map<String, Object> task : (List<Map<String, Object>>) project.get("tasks")) {
                String snapshot = (String) task.get("requirement_snapshot");
                String summary = (String) task.get("requirement_summary");
                if (snapshot != null && !snapshot.isEmpty() && summary != null && !summary.isEmpty()
                        && !sha256(snapshot).equals(summary)) {
                    problems.add("spec_digest_mismatch: 任务 %s 的需求快照与摘要不一致"
                            .formatted(task.get("task_id")));
                }
                String problemSnapshot = (String) task.get("problem_snapshot");
                String problemSummary = (String) task.get("problem_summary");
                if (problemSnapshot != null && !problemSnapshot.isEmpty()
                        && problemSummary != null && !problemSummary.isEmpty()
                        && !sha256(problemSnapshot).equals(problemSummary)) {
                    problems.add("problem_digest_mismatch: 任务 %s 的问题快照与摘要不一致"
                            .formatted(task.get("task_id")));
                }
                for (Map<String, Object> record : (List<Map<String, Object>>) task.get("evidence")) {
                    if (!sha256((String) record.get("output_text")).equals(record.get("output_sha256"))) {
                        problems.add("output_digest_mismatch: 任务 %s 记录 %s 的输出与摘要不一致"
                                .formatted(task.get("task_id"), record.get("record_id")));
                    }
                }
                for (Map<String, Object> record : (List<Map<String, Object>>) task.get("executions")) {
                    for (String[] keys : new String[][] {
                            {"stdout_text", "stdout_sha256", "stdout"},
                            {"stderr_text", "stderr_sha256", "stderr"},
                            {"change_manifest_json", "change_manifest_sha256", "change_manifest"},
                            {"diff_text", "diff_sha256", "diff"},
                            {"eval_output_text", "eval_output_sha256", "eval 输出"}}) {
                        Object sha = record.get(keys[1]);
                        if (sha != null && !sha.toString().isEmpty()
                                && !sha256((String) record.get(keys[0])).equals(sha.toString())) {
                            problems.add("execution_digest_mismatch: 任务 %s 执行 %s 的 %s 与摘要不一致"
                                    .formatted(task.get("task_id"), record.get("execution_id"), keys[2]));
                        }
                    }
                }
            }
        }
        return problems;
    }
}
