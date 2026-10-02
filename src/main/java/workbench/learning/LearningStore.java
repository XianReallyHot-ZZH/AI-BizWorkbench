package workbench.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import workbench.bootstrap.PyJson;
import workbench.evolution.EvolutionStore;

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
 * 记忆系统最小闭环（L15，S01 冻结面 {@code python-carrier-final:workbench/learning.py}
 * 770 行七命令五表的 Java 对应物——Python 冻结面时代建成能力的兑现讲，ADR-0006：
 * 冻结面是本仓建成件的对照基准）。
 *
 * <p><b>七命令</b>（错误码/词面/状态机/召回语义 S01 逐字直承）：learn-create（来源
 * 准入：已验收任务〔completed + approve + reviewed_by〕，或<b>已接受失败反馈对应的
 * Evolution</b>——L15 增量，上游现版 source() 连接缝：source_task_id 一致 + 非
 * rejected/deferred + 失败轨迹在场〔error 或事件含 rework/failed/dead_letter〕；
 * --feedback-id 级联自动建 workbench_control 进化候选——入口有捷径，闸门无捷径）
 * → learn-govern（具名 approve/publish/revoke；{@link #HUMAN_FORBIDDEN} 拒 AI 名义
 * 含 claude 增补——更严；提炼者不得自审；publish 前必须 approve；同 family 旧
 * active 版本同事务原子置 superseded，历史快照不改写）→ learn-recall（项目过滤 +
 * 仅 active + applies 命中/excludes 排除 + 可解释排序〔命中得分与理由〕+ 字符预算
 * Top-k〔超限进 excluded 明示〕+ conflict_key 冲突显式分组「请逐项判断，不自动合并」
 * + 同源事项不得独立复用 + 检索不到如实为空；召回包整体落库 RECALL-*）→
 * learn-bind（决定集与 matches 逐项对应 + 采用资产全文快照封 BIND-*，plan/task
 * 双唯一）→ learn-run/finish（五相位 precheck/implement/eval/review/outcome，每相位
 * 一次；不收自报哈希——只给账本记录引用〔task_events〕模块自取现算；outcome=passed
 * 须具名验收，缺则「复用验证缺少具名验收」）→ learn-show（读时重算——漂移报错
 * 不静默）。
 *
 * <p><b>形态适配</b>（spec D3 单库四账 = delivery 库，与 S01 绑 L01 主账的分歧如实
 * 落账）：「已验收」判定按 delivery tasks 三条件（上游现版 source() 口径）；S01 的
 * tasks.project_id 任务面项目校验不采纳（delivery tasks 无 project 列）——治理面
 * govern 与召回面 recall 的项目隔离保留；来源快照的 executions/reviews 引用改为
 * delivery 形态（review 三字段 + result 摘要）；admission 轻判 + evolution verify
 * 重门分层——报告逐项阻断的权威校验在 EvolutionStore.verify（治理链的重门），
 * 上游 source() 对 accepted 来源的 report(passing=True) 强判不搬运（L15 分层落账）。
 * 记忆链五相位与任务账四相位不混用（信用内核冻结不动——S01 D4 直承）。
 *
 * <p><b>链的形态</b>：记忆链是 sha256 快照链而非红绿链——资产内容、召回包、采用
 * 快照每次读取重算复核（{@link #checkPayloadIntegrity}）；失效不删除只状态迁移，
 * 事件全留痕（candidate/approve/publish/superseded/revoke）。
 */
public final class LearningStore {

    /** 记忆复用链五相位（S01 同形）：前三相位经 learn-run 逐相位落账，review/outcome 由 finish 一次写入。 */
    public static final List<String> RUN_PHASES =
            List.of("precheck", "implement", "eval", "review", "outcome");

    static final List<String> RECORDABLE_RUN_PHASES = List.of("precheck", "implement", "eval");

    /** 默认召回字符预算（S01 DEFAULT_RECALL_BUDGET）。 */
    public static final int DEFAULT_RECALL_BUDGET = 12000;

    /** 具名门槛（S01 HUMAN_FORBIDDEN 词面集逐字 + claude 增补——更严）。 */
    public static final LinkedHashSet<String> HUMAN_FORBIDDEN = new LinkedHashSet<>(List.of(
            "ai", "codex", "system", "claude", "待确认", "待定", "tbd", "pending"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path dbPath;

    public LearningStore(Path path) {
        this.dbPath = path;
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException error) {
            throw new UncheckedIOException("任务库目录创建失败：" + path.getParent(), error);
        }
        new workbench.delivery.TaskStore(path);
        new workbench.delivery.Feedback(path);
        try (Connection conn = open()) {
            try (java.sql.Statement st = conn.createStatement()) {
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS learning_assets(
                          asset_id TEXT PRIMARY KEY,
                          family TEXT NOT NULL,
                          version INTEGER NOT NULL,
                          project_id TEXT NOT NULL,
                          kind TEXT NOT NULL DEFAULT 'memory',
                          state TEXT NOT NULL,
                          approved_by TEXT NOT NULL DEFAULT '',
                          payload TEXT NOT NULL,
                          sha256 TEXT NOT NULL,
                          created_at TEXT NOT NULL
                        )""");
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS learning_events(
                          event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                          asset_id TEXT NOT NULL,
                          actor TEXT NOT NULL,
                          action TEXT NOT NULL,
                          note TEXT NOT NULL DEFAULT '',
                          evidence TEXT NOT NULL DEFAULT '{}',
                          at TEXT NOT NULL
                        )""");
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS learning_recalls(
                          recall_id TEXT PRIMARY KEY,
                          project_id TEXT NOT NULL,
                          task_id TEXT NOT NULL,
                          payload TEXT NOT NULL,
                          sha256 TEXT NOT NULL,
                          created_at TEXT NOT NULL
                        )""");
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS learning_bindings(
                          binding_id TEXT PRIMARY KEY,
                          recall_id TEXT NOT NULL,
                          plan_id TEXT NOT NULL,
                          task_id TEXT NOT NULL,
                          payload TEXT NOT NULL,
                          sha256 TEXT NOT NULL,
                          created_at TEXT NOT NULL
                        )""");
                st.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS learning_runs(
                          binding_id TEXT NOT NULL,
                          phase TEXT NOT NULL,
                          payload TEXT NOT NULL,
                          at TEXT NOT NULL
                        )""");
            }
        } catch (SQLException error) {
            throw new IllegalStateException(
                    "learning 建表失败：" + path + "：" + error.getMessage(), error);
        }
    }

    /** 合同拒绝（词面进 error 字段，rc=1；不写任何记录——S01 LearningError 同形）。 */
    public static final class LearningException extends RuntimeException {
        public final String code;

        public LearningException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }
    }

    // ---- 来源准入与快照（L15 形态：delivery 库 + evolution 连接缝） -----------------------

    /** 来源快照：已验收判定 + evolution 分支 + 失败轨迹门槛；快照整体封存记 sha256。 */
    private Map<String, Object> admissionSnapshot(Connection conn, String taskId,
            String evolutionId) throws SQLException {
        TaskRow task = taskRow(conn, taskId);
        if (task == null) {
            throw new LearningException("required_task_missing", "任务不存在：" + taskId);
        }
        boolean accepted = "completed".equals(task.status())
                && "approve".equals(task.reviewDecision())
                && task.reviewedBy() != null && !task.reviewedBy().isBlank();
        if (accepted) {
            if (evolutionId != null && !evolutionId.isBlank()) {
                Map<String, String> evolution = evolutionBrief(conn, evolutionId);
                if (evolution == null || !taskId.equals(evolution.get("source_task_id"))) {
                    throw new LearningException("evolution_source_mismatch", "Evolution 来源不一致");
                }
            }
        } else {
            if (evolutionId == null || evolutionId.isBlank()) {
                // S01 词面（source_task_not_accepted）+ 上游现版词面（source() :176）两段保留
                throw new LearningException("source_task_not_accepted",
                        "来源任务未具名验收（status=" + task.status() + "）：" + taskId
                                + "；来源需要已验收任务，或已接受失败反馈对应的 Evolution");
            }
            Map<String, String> evolution = evolutionBrief(conn, evolutionId);
            if (evolution == null || !taskId.equals(evolution.get("source_task_id"))
                    || "rejected".equals(evolution.get("status"))
                    || "deferred".equals(evolution.get("status"))) {
                throw new LearningException("evolution_source_mismatch",
                        "Evolution 来源不一致或已拒绝");
            }
            if (!hasFailureTrace(conn, taskId)) {
                // 上游现版词面逐字（source() :186-187）
                throw new LearningException("missing_failure_trace", "失败经验缺少实际失败轨迹");
            }
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("task_id", taskId);
        snapshot.put("request", task.request());
        snapshot.put("requirement_id", task.requirementId());
        snapshot.put("status", task.status());
        snapshot.put("review", new LinkedHashMap<>(Map.of(
                "reviewer", task.reviewedBy() == null ? "" : task.reviewedBy(),
                "decision", task.reviewDecision() == null ? "" : task.reviewDecision(),
                "reviewed_at", task.reviewedAt() == null ? "" : task.reviewedAt())));
        snapshot.put("evolution_id", evolutionId == null ? "" : evolutionId);
        snapshot.put("result", task.resultJson() == null ? "" : task.resultJson());
        snapshot.put("created_at", task.createdAt());
        return snapshot;
    }

    private static boolean hasFailureTrace(Connection conn, String taskId) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT error FROM tasks WHERE id = ?")) {
            st.setString(1, taskId);
            ResultSet row = st.executeQuery();
            if (row.next() && row.getString(1) != null && !row.getString(1).isBlank()) {
                return true;
            }
        }
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT 1 FROM task_events WHERE task_id = ? AND to_status IN "
                        + "('rework','failed','dead_letter') LIMIT 1")) {
            st.setString(1, taskId);
            return st.executeQuery().next();
        }
    }

    /**
     * evolution 概要直查（同连接零新锁——admission 处于 create 事务内，构造
     * EvolutionStore 会连锁 TaskStore 的 IMMEDIATE 建表连接互锁，SQLITE_BUSY 实测）。
     */
    private static Map<String, String> evolutionBrief(Connection conn, String evolutionId)
            throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT source_task_id, status FROM evolutions WHERE id = ?")) {
            st.setString(1, evolutionId);
            ResultSet rs = st.executeQuery();
            if (!rs.next()) {
                return null;
            }
            return Map.of("source_task_id", rs.getString(1), "status", rs.getString(2));
        }
    }

    // ---- create --------------------------------------------------------------------------

    public Map<String, Object> create(Map<String, String> args) {
        String actor = args.getOrDefault("actor", "").strip();
        String family = args.get("family");
        String taskId = args.get("task_id");
        String evolutionId = args.getOrDefault("evolution_id", "");
        String feedbackId = args.getOrDefault("feedback_id", "");
        // feedback 级联（上游 create() :253-259 同形）：缺 evolution 时自动建 workbench_control
        // 候选——EvolutionStore.create 只收具名接受的反馈，闸门与显式路径完全一致。
        if (!feedbackId.isBlank() && evolutionId.isBlank()) {
            evolutionId = cascadeEvolution(dbPath, feedbackId,
                    args.get("title"), actor);
        }
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                Map<String, Object> source = admissionSnapshot(conn, taskId, evolutionId);
                int version;
                try (PreparedStatement st = conn.prepareStatement(
                        "SELECT COALESCE(MAX(version), 0) + 1 FROM learning_assets "
                                + "WHERE family = ?")) {
                    st.setString(1, family);
                    ResultSet rs = st.executeQuery();
                    rs.next();
                    version = rs.getInt(1);
                }
                String supersedes = args.getOrDefault("supersedes", "");
                if (!supersedes.isBlank()) {
                    String targetFamily;
                    try (PreparedStatement st = conn.prepareStatement(
                            "SELECT family FROM learning_assets WHERE asset_id = ?")) {
                        st.setString(1, supersedes);
                        ResultSet rs = st.executeQuery();
                        if (!rs.next()) {
                            throw new LearningException("supersedes_target_invalid",
                                    "被替代对象不存在或不属于同 family：" + supersedes);
                        }
                        targetFamily = rs.getString(1);
                    }
                    if (!family.equals(targetFamily)) {
                        throw new LearningException("supersedes_target_invalid",
                                "被替代对象不存在或不属于同 family：" + supersedes);
                    }
                }
                String assetId = newId("ASSET", "learning_assets", "asset_id");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("id", assetId);
                payload.put("family", family);
                payload.put("version", version);
                payload.put("project_id", args.get("project_id"));
                payload.put("kind", "memory");
                payload.put("state", "candidate");
                payload.put("title", args.get("title"));
                payload.put("content", args.get("content"));
                payload.put("applies", splitFlags(args.get("applies")));
                payload.put("excludes", splitFlags(args.get("excludes")));
                payload.put("boundary", args.getOrDefault("boundary", ""));
                payload.put("conflict_key", args.getOrDefault("conflict_key", ""));
                payload.put("supersedes", supersedes);
                payload.put("source", source);
                payload.put("created_by", actor);
                payload.put("created_at", now());
                String payloadText = canonical(payload);
                String sha = sha256(payloadText);
                try (PreparedStatement st = conn.prepareStatement(
                        "INSERT INTO learning_assets(asset_id, family, version, project_id, "
                                + "kind, state, approved_by, payload, sha256, created_at) "
                                + "VALUES (?, ?, ?, ?, 'memory', 'candidate', '', ?, ?, ?)")) {
                    st.setString(1, assetId);
                    st.setString(2, family);
                    st.setInt(3, version);
                    st.setString(4, args.get("project_id"));
                    st.setString(5, payloadText);
                    st.setString(6, sha);
                    st.setString(7, (String) payload.get("created_at"));
                    st.executeUpdate();
                }
                appendEvent(conn, assetId, actor, "candidate",
                        "从任务 " + taskId + " 提炼",
                        PyJson.dumpsCompact(Map.of("source_sha256", sourceSha(source))));
                conn.commit();
                return Map.of("asset_id", assetId, "family", family,
                        "version", version, "state", "candidate",
                        "supersedes", supersedes,
                        "source", Map.of("task_id", taskId, "sha256", sourceSha(source)));
            } catch (RuntimeException error) {
                conn.rollback();
                throw error;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new IllegalStateException(
                    "learn-create 失败：" + error.getMessage(), error);
        }
    }

    private static String cascadeEvolution(Path dbPath, String feedbackId, String title,
            String actor) {
        EvolutionStore evolutions = new EvolutionStore(dbPath);
        for (Map<String, Object> item : evolutions.list(500)) {
            if (feedbackId.equals(item.get("feedback_id"))) {
                return (String) item.get("id");
            }
        }
        Map<String, Object> created = evolutions.create(feedbackId, title,
                "workbench_control", null, actor);
        return (String) created.get("id");
    }

    // ---- govern --------------------------------------------------------------------------

    public Map<String, Object> govern(Map<String, String> args) {
        String actor = requireHuman(args.getOrDefault("actor", ""));
        String decision = args.get("decision");
        String note = args.getOrDefault("note", "");
        try (Connection conn = open()) {
            conn.setAutoCommit(false);
            try {
                AssetRow row = readAsset(conn, args.get("asset_id"));
                if (!row.projectId().equals(args.get("project_id"))) {
                    // 跨项目治理显式拒绝（S01 :252-253 口径）
                    throw new LearningException("project_mismatch",
                            "记忆条目 " + row.assetId() + " 属于项目 " + row.projectId()
                                    + "，与 --project-id " + args.get("project_id") + " 不符");
                }
                Map<String, Object> payload = checkedPayload(row);
                if (actor.equals(String.valueOf(payload.getOrDefault("created_by", "")).strip())) {
                    throw new LearningException("self_govern_rejected", "提炼者不得自审：" + actor);
                }
                String assetId = row.assetId();
                String state;
                if (!"approve".equals(decision) && !"publish".equals(decision)
                        && !"revoke".equals(decision)) {
                    // argparse choices 同形防线（CLI 层 rc 2 之外，Store 层拒绝非法决定——
                    // 静默 default 兜底会触发破坏性状态迁移，复查轮 S-硬1 修复）
                    throw new LearningException("invalid_decision",
                            "治理决定必须是 approve、publish 或 revoke：当前 " + decision);
                }
                switch (decision) {
                    case "approve" -> {
                        if (!"candidate".equals(row.state()) || !row.approvedBy().isEmpty()) {
                            throw new LearningException("invalid_transition",
                                    "approve 仅限未审核的 candidate：当前 state=" + row.state());
                        }
                        try (PreparedStatement st = conn.prepareStatement(
                                "UPDATE learning_assets SET approved_by = ? WHERE asset_id = ?")) {
                            st.setString(1, actor);
                            st.setString(2, assetId);
                            st.executeUpdate();
                        }
                        appendEvent(conn, assetId, actor, "approve", note, null);
                        state = "candidate";
                    }
                    case "publish" -> {
                        if (!"candidate".equals(row.state())) {
                            throw new LearningException("invalid_transition",
                                    "publish 仅限 candidate：当前 state=" + row.state());
                        }
                        if (row.approvedBy().isEmpty()) {
                            throw new LearningException("approve_required",
                                    "发布前必须先经具名审核（approve）");
                        }
                        // 同 family 旧 active 版本同事务原子置 superseded（历史快照不改写）
                        List<String> olds = new ArrayList<>();
                        try (PreparedStatement st = conn.prepareStatement(
                                "SELECT asset_id FROM learning_assets WHERE family = ? "
                                        + "AND state = 'active' AND asset_id != ?")) {
                            st.setString(1, row.family());
                            st.setString(2, assetId);
                            ResultSet rs = st.executeQuery();
                            while (rs.next()) {
                                olds.add(rs.getString(1));
                            }
                        }
                        for (String old : olds) {
                            try (PreparedStatement st = conn.prepareStatement(
                                    "UPDATE learning_assets SET state = 'superseded' "
                                            + "WHERE asset_id = ?")) {
                                st.setString(1, old);
                                st.executeUpdate();
                            }
                            appendEvent(conn, old, actor, "superseded", "被 " + assetId + " 替代",
                                    PyJson.dumpsCompact(Map.of("replacement", assetId)));
                        }
                        try (PreparedStatement st = conn.prepareStatement(
                                "UPDATE learning_assets SET state = 'active' WHERE asset_id = ?")) {
                            st.setString(1, assetId);
                            st.executeUpdate();
                        }
                        appendEvent(conn, assetId, actor, "publish", note,
                                PyJson.dumpsCompact(Map.of("superseded", olds)));
                        state = "active";
                    }
                    default -> {
                        if (!"candidate".equals(row.state()) && !"active".equals(row.state())) {
                            throw new LearningException("invalid_transition",
                                    "该版本已停用（state=" + row.state() + "），不能重复撤回");
                        }
                        try (PreparedStatement st = conn.prepareStatement(
                                "UPDATE learning_assets SET state = 'revoked' "
                                        + "WHERE asset_id = ?")) {
                            st.setString(1, assetId);
                            st.executeUpdate();
                        }
                        appendEvent(conn, assetId, actor, "revoke", note, null);
                        state = "revoked";
                    }
                }
                conn.commit();
                return Map.of("asset_id", assetId, "family", row.family(),
                        "version", row.version(), "state", state);
            } catch (RuntimeException error) {
                conn.rollback();
                throw error;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("learn-govern 失败", error);
        }
    }

    // ---- recall --------------------------------------------------------------------------

    public Map<String, Object> recall(Map<String, String> args) {
        String taskId = args.get("task_id");
        int budget = args.containsKey("budget") && !args.get("budget").isBlank()
                ? Integer.parseInt(args.get("budget")) : DEFAULT_RECALL_BUDGET;
        List<String> keywords = splitFlags(args.get("keyword"));
        List<String> lowered = keywords.stream().map(String::toLowerCase).toList();
        try (Connection conn = open()) {
            if (taskRow(conn, taskId) == null) {
                throw new LearningException("required_task_missing", "任务不存在：" + taskId);
            }
            List<AssetRow> rows = new ArrayList<>();
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT * FROM learning_assets WHERE project_id = ? AND state = 'active' "
                            + "ORDER BY family, version DESC")) {
                st.setString(1, args.get("project_id"));
                ResultSet rs = st.executeQuery();
                while (rs.next()) {
                    rows.add(new AssetRow(rs));
                }
            }
            List<Map<String, Object>> matches = new ArrayList<>();
            List<Map<String, Object>> excluded = new ArrayList<>();
            record Candidate(int score, String family, int version, AssetRow row,
                    Map<String, Object> payload, String reason, int contentLen) {}
            List<Candidate> candidates = new ArrayList<>();
            for (AssetRow row : rows) {
                Map<String, Object> payload = checkedPayload(row);
                if (taskId.equals(sourceTask(payload))) {
                    excluded.add(Map.of("asset_id", row.assetId(),
                            "reason", "self_source:同源事项不得作为独立复用"));
                    continue;
                }
                List<String> applies = loweredList(payload.get("applies"));
                List<String> excludes = loweredList(payload.get("excludes"));
                List<String> exHits = new ArrayList<>();
                for (String keyword : lowered) {
                    if (excludes.contains(keyword)) {
                        exHits.add(keyword);
                    }
                }
                if (!exHits.isEmpty()) {
                    excluded.add(Map.of("asset_id", row.assetId(),
                            "reason", "excludes:" + String.join(",", exHits)));
                    continue;
                }
                int score;
                String reason;
                if (!lowered.isEmpty() && !applies.isEmpty()) {
                    List<String> hits = new ArrayList<>();
                    for (String keyword : lowered) {
                        if (applies.contains(keyword)) {
                            hits.add(keyword);
                        }
                    }
                    if (hits.isEmpty()) {
                        continue;
                    }
                    score = hits.size();
                    reason = "applies:" + String.join(",", hits);
                } else {
                    score = 0;
                    reason = "applies:*";
                }
                candidates.add(new Candidate(score, row.family(), row.version(), row, payload,
                        reason, String.valueOf(payload.getOrDefault("content", "")).length()));
            }
            candidates.sort((a, b) -> {
                if (a.score != b.score) {
                    return Integer.compare(b.score, a.score);
                }
                int byFamily = a.family.compareTo(b.family);
                if (byFamily != 0) {
                    return byFamily;
                }
                return Integer.compare(b.version, a.version);
            });
            int budgetUsed = 0;
            Map<String, List<String>> conflictGroups = new LinkedHashMap<>();
            for (Candidate candidate : candidates) {
                if (budgetUsed + candidate.contentLen() > budget) {
                    excluded.add(Map.of("asset_id", candidate.row().assetId(),
                            "reason", "budget_exceeded:超出上下文字符预算"));
                    continue;
                }
                budgetUsed += candidate.contentLen();
                matches.add(new LinkedHashMap<>(Map.of(
                        "asset_id", candidate.row().assetId(),
                        "family", candidate.family(),
                        "version", candidate.version(),
                        "title", String.valueOf(candidate.payload().getOrDefault("title", "")),
                        "reason", candidate.reason(),
                        "conflict_key", String.valueOf(
                                candidate.payload().getOrDefault("conflict_key", "")))));
                String conflictKey = String.valueOf(
                        candidate.payload().getOrDefault("conflict_key", ""));
                if (!conflictKey.isEmpty()) {
                    conflictGroups.computeIfAbsent(conflictKey,
                            key -> new ArrayList<>()).add(candidate.row().assetId());
                }
            }
            List<Map<String, Object>> conflicts = new ArrayList<>();
            for (Map.Entry<String, List<String>> group : conflictGroups.entrySet()) {
                if (group.getValue().size() > 1) {
                    conflicts.add(new LinkedHashMap<>(Map.of(
                            "conflict_key", group.getKey(),
                            "asset_ids", group.getValue(),
                            "note", "请逐项判断，不自动合并")));
                }
            }
            String recallId = newId("RECALL", "learning_recalls", "recall_id");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("recall_id", recallId);
            payload.put("project_id", args.get("project_id"));
            payload.put("task_id", taskId);
            payload.put("query", keywords);
            payload.put("matches", matches);
            payload.put("excluded", excluded);
            payload.put("conflicts", conflicts);
            payload.put("budget", new LinkedHashMap<>(Map.of("limit", budget, "used", budgetUsed)));
            payload.put("recalled_at", now());
            String payloadText = canonical(payload);
            try (PreparedStatement st = conn.prepareStatement(
                    "INSERT INTO learning_recalls(recall_id, project_id, task_id, payload, "
                            + "sha256, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                st.setString(1, recallId);
                st.setString(2, args.get("project_id"));
                st.setString(3, taskId);
                st.setString(4, payloadText);
                st.setString(5, sha256(payloadText));
                st.setString(6, (String) payload.get("recalled_at"));
                st.executeUpdate();
            }
            return payload;
        } catch (SQLException error) {
            throw new IllegalStateException("learn-recall 失败", error);
        }
    }

    // ---- bind / run / finish -------------------------------------------------------------

    public Map<String, Object> bind(Map<String, String> args) {
        String actor = requireHuman(args.getOrDefault("actor", ""));
        String recallId = args.get("recall_id");
        String taskId = args.get("task_id");
        String planId = args.get("plan_id");
        try (Connection conn = open()) {
            Map<String, Object> recallPayload = loadRow(conn, "learning_recalls",
                    "recall_id", recallId, "召回包");
            if (!taskId.equals(recallPayload.get("task_id"))) {
                throw new LearningException("recall_task_mismatch",
                        "绑定任务 " + taskId + " 与召回包事项 " + recallPayload.get("task_id")
                                + " 不一致");
            }
            if (taskRow(conn, taskId) == null) {
                throw new LearningException("required_task_missing", "任务不存在：" + taskId);
            }
            JsonNode decisions;
            try {
                decisions = MAPPER.readTree(args.get("decisions"));
            } catch (IOException error) {
                throw new LearningException("decision_mismatch", "决定集不是合法 JSON：" + error);
            }
            List<String> matchIds = new ArrayList<>();
            for (Object match : (List<?>) recallPayload.getOrDefault("matches", List.of())) {
                if (match instanceof Map<?, ?> row) {
                    matchIds.add(String.valueOf(row.get("asset_id")));
                }
            }
            List<String> decisionIds = new ArrayList<>();
            boolean shaped = decisions.isArray();
            if (shaped) {
                for (JsonNode decision : decisions) {
                    if (!decision.isObject() || !decision.path("adopt").isBoolean()
                            || !decision.path("reason").isTextual()) {
                        shaped = false;
                        break;
                    }
                    decisionIds.add(decision.path("asset_id").asText(""));
                }
            }
            if (!shaped || decisionIds.size() != matchIds.size()
                    || !new LinkedHashSet<>(decisionIds).containsAll(matchIds)
                    || !matchIds.containsAll(decisionIds)
                    || new LinkedHashSet<>(decisionIds).size() != decisionIds.size()) {
                throw new LearningException("decision_mismatch",
                        "采用决定必须与本轮召回 matches 逐项对应（adopt 布尔 + 理由，不重不漏）");
            }
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT 1 FROM learning_bindings WHERE task_id = ? OR plan_id = ?")) {
                st.setString(1, taskId);
                st.setString(2, planId);
                if (st.executeQuery().next()) {
                    throw new LearningException("binding_exists",
                            "一次交付最多绑定一条（task 与 plan 双唯一）");
                }
            }
            List<Map<String, Object>> assets = new ArrayList<>();
            for (JsonNode decision : decisions) {
                if (!decision.path("adopt").asBoolean()) {
                    continue;
                }
                AssetRow row = readAsset(conn, decision.path("asset_id").asText());
                Map<String, Object> payload = checkedPayload(row);
                if (!String.valueOf(payload.get("project_id"))
                        .equals(recallPayload.get("project_id"))) {
                    throw new LearningException("project_mismatch",
                            "跨项目采用被拒：" + row.assetId() + " 属于 " + payload.get("project_id"));
                }
                if (!"active".equals(row.state())) {
                    throw new LearningException("asset_not_active",
                            "记忆条目非 active 不得采用：" + row.assetId() + " state=" + row.state());
                }
                assets.add(new LinkedHashMap<>(Map.of(
                        "asset_id", row.assetId(), "family", row.family(),
                        "version", row.version(), "snapshot", payload, "sha256", row.sha256())));
            }
            String bindingId = newId("BIND", "learning_bindings", "binding_id");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", bindingId);
            payload.put("recall_id", recallId);
            payload.put("plan_id", planId);
            payload.put("task_id", taskId);
            payload.put("decisions", MAPPER.convertValue(decisions, Object.class));
            payload.put("assets", assets);
            payload.put("bound_by", actor);
            payload.put("bound_at", now());
            String payloadText = canonical(payload);
            try (PreparedStatement st = conn.prepareStatement(
                    "INSERT INTO learning_bindings(binding_id, recall_id, plan_id, task_id, "
                            + "payload, sha256, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                st.setString(1, bindingId);
                st.setString(2, recallId);
                st.setString(3, planId);
                st.setString(4, taskId);
                st.setString(5, payloadText);
                st.setString(6, sha256(payloadText));
                st.setString(7, (String) payload.get("bound_at"));
                st.executeUpdate();
            }
            List<Map<String, Object>> assetDigest = new ArrayList<>();
            for (Map<String, Object> asset : assets) {
                assetDigest.add(new LinkedHashMap<>(Map.of(
                        "asset_id", asset.get("asset_id"), "family", asset.get("family"),
                        "version", asset.get("version"), "sha256", asset.get("sha256"))));
            }
            return Map.of("binding_id", bindingId, "recall_id", recallId, "plan_id", planId,
                    "task_id", taskId,
                    "decisions", MAPPER.convertValue(decisions, Object.class),
                    "assets", assetDigest);
        } catch (SQLException error) {
            throw new IllegalStateException("learn-bind 失败", error);
        }
    }

    public Map<String, Object> run(Map<String, String> args) {
        String phase = args.get("phase");
        String evidenceTable = args.getOrDefault("evidence_table", "");
        if (!"events".equals(evidenceTable)) {
            // S01 argparse choices 的 L15 单值同形（evidence/executions → delivery 形态 events）
            throw new LearningException("invalid_evidence_table",
                    "证据表必须是 events：当前 " + evidenceTable);
        }
        try (Connection conn = open()) {
            Map<String, Object> payload = loadRow(conn, "learning_bindings",
                    "binding_id", args.get("binding_id"), "采用快照");
            validateBindingAssets(conn, payload);
            int recordId;
            try {
                recordId = Integer.parseInt(args.get("evidence_record_id"));
            } catch (NumberFormatException error) {
                throw new LearningException("evidence_record_missing",
                        "证据记录不存在：" + args.get("evidence_record_id"));
            }
            String evidenceTaskId;
            String evidenceText;
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT task_id, detail, evidence_json FROM task_events WHERE id = ?")) {
                st.setInt(1, recordId);
                ResultSet rs = st.executeQuery();
                if (!rs.next()) {
                    throw new LearningException("evidence_record_missing",
                            "证据记录不存在：" + recordId);
                }
                evidenceTaskId = rs.getString(1);
                evidenceText = rs.getString(2) + "\n" + rs.getString(3);
            }
            if (!evidenceTaskId.equals(payload.get("task_id"))) {
                throw new LearningException("evidence_task_mismatch",
                        "证据记录属于任务 " + evidenceTaskId + "，与绑定任务 "
                                + payload.get("task_id") + " 不符");
            }
            requirePhaseFresh(conn, args.get("binding_id"), phase);
            Map<String, Object> runPayload = new LinkedHashMap<>();
            runPayload.put("result", args.get("result"));
            runPayload.put("summary", args.getOrDefault("summary", ""));
            runPayload.put("evidence", new LinkedHashMap<>(Map.of(
                    "table", "events", "record_id", recordId,
                    "sha256", sha256(evidenceText))));
            String at = now();
            insertRun(conn, args.get("binding_id"), phase, runPayload);
            return Map.of("binding_id", args.get("binding_id"), "phase", phase,
                    "result", args.get("result"), "evidence", runPayload.get("evidence"),
                    "at", at);
        } catch (SQLException error) {
            throw new IllegalStateException("learn-run 失败", error);
        }
    }

    public Map<String, Object> finish(Map<String, String> args) {
        String reviewer = args.getOrDefault("reviewer", "").strip();
        String outcome = args.get("outcome");
        if ("passed".equals(outcome) && reviewer.isEmpty()) {
            throw new LearningException("reuse_acceptance_missing", "复用验证缺少具名验收");
        }
        reviewer = requireHuman(reviewer);
        try (Connection conn = open()) {
            Map<String, Object> payload = loadRow(conn, "learning_bindings",
                    "binding_id", args.get("binding_id"), "采用快照");
            validateBindingAssets(conn, payload);
            for (String phase : RECORDABLE_RUN_PHASES) {
                Map<String, Object> runPayload = runPayload(conn, args.get("binding_id"), phase);
                if (runPayload == null) {
                    throw new LearningException("missing_phase", "复验链缺相位：" + phase);
                }
                if ("passed".equals(outcome)
                        && !"passed".equals(runPayload.get("result"))) {
                    throw new LearningException("phase_not_passed",
                            "存在未通过相位，不得标记通过：" + phase);
                }
            }
            requirePhaseFresh(conn, args.get("binding_id"), "review");
            requirePhaseFresh(conn, args.get("binding_id"), "outcome");
            Map<String, Object> reviewPayload = new LinkedHashMap<>();
            reviewPayload.put("reviewer", reviewer);
            reviewPayload.put("note", args.getOrDefault("note", ""));
            insertRun(conn, args.get("binding_id"), "review", reviewPayload);
            Map<String, Object> outcomePayload = new LinkedHashMap<>();
            outcomePayload.put("outcome", outcome);
            outcomePayload.put("reviewer", reviewer);
            outcomePayload.put("note", args.getOrDefault("note", ""));
            insertRun(conn, args.get("binding_id"), "outcome", outcomePayload);
            return Map.of("binding_id", args.get("binding_id"), "outcome", outcome,
                    "reviewer", reviewer, "phases", RUN_PHASES);
        } catch (SQLException error) {
            throw new IllegalStateException("learn-finish 失败", error);
        }
    }

    // ---- show（读时重算） ------------------------------------------------------------------

    public Map<String, Object> showAsset(String assetId) {
        try (Connection conn = open()) {
            AssetRow row = readAsset(conn, assetId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("asset_id", row.assetId());
            out.put("family", row.family());
            out.put("version", row.version());
            out.put("state", row.state());
            out.put("payload", checkedPayload(row));
            out.put("events", rowEvents(conn, assetId));
            return out;
        } catch (SQLException error) {
            throw new IllegalStateException("learn-show 失败", error);
        }
    }

    public Map<String, Object> showRecall(String recallId) {
        try (Connection conn = open()) {
            return loadRow(conn, "learning_recalls", "recall_id", recallId, "召回包");
        } catch (SQLException error) {
            throw new IllegalStateException("learn-show 失败", error);
        }
    }

    public Map<String, Object> showBinding(String bindingId) {
        try (Connection conn = open()) {
            return loadRow(conn, "learning_bindings", "binding_id", bindingId, "采用快照");
        } catch (SQLException error) {
            throw new IllegalStateException("learn-show 失败", error);
        }
    }

    // ---- evolution verify 的 governed 分支查询面 ------------------------------------------

    /** 候选任务的采用绑定（无绑定返回 null——EvolutionStore.verify governed 分支调用）。 */
    public Map<String, Object> bindingForTask(String taskId) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT payload FROM learning_bindings WHERE task_id = ?")) {
            st.setString(1, taskId);
            ResultSet rs = st.executeQuery();
            if (!rs.next()) {
                return null;
            }
            Map<String, Object> payload = parsePayload(rs.getString(1));
            payload.put("binding_id", payload.get("id"));
            return payload;
        } catch (SQLException error) {
            throw new IllegalStateException("采用绑定查询失败：" + taskId, error);
        }
    }

    /** 绑定的 outcome 相位载荷（无 outcome 返回 null）。 */
    public Map<String, Object> outcomeForBinding(String bindingId) {
        Map<String, Object> runPayload = runPayloadQuiet(bindingId, "outcome");
        return runPayload;
    }

    // ---- 支撑 ---------------------------------------------------------------------------

    static String requireHuman(String actor) {
        String name = actor == null ? "" : actor.strip().toLowerCase();
        if (name.isEmpty() || HUMAN_FORBIDDEN.contains(name) || name.startsWith("agent:")) {
            throw new LearningException("human_actor_required",
                    "操作人必须具名人工：'" + (actor == null ? "" : actor.strip()) + "'");
        }
        return actor.strip();
    }

    record AssetRow(String assetId, String family, int version, String projectId,
                    String state, String approvedBy, String payload, String sha256) {
        AssetRow(ResultSet rs) throws SQLException {
            this(rs.getString("asset_id"), rs.getString("family"), rs.getInt("version"),
                    rs.getString("project_id"), rs.getString("state"),
                    rs.getString("approved_by") == null ? "" : rs.getString("approved_by"),
                    rs.getString("payload"), rs.getString("sha256"));
        }
    }

    record TaskRow(String id, String request, String requirementId, String status,
                   String resultJson, String reviewedBy, String reviewDecision,
                   String reviewedAt, String createdAt) {}

    private TaskRow taskRow(Connection conn, String taskId) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT id, request, requirement_id, status, result_json, reviewed_by, "
                        + "review_decision, reviewed_at, created_at FROM tasks WHERE id = ?")) {
            st.setString(1, taskId);
            ResultSet rs = st.executeQuery();
            if (!rs.next()) {
                return null;
            }
            return new TaskRow(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                    rs.getString(8), rs.getString(9));
        }
    }

    /** 读时重算复核（S01 _check_payload_integrity）：对存储文本算 sha 比对——漂移报错不静默。 */
    private Map<String, Object> checkedPayload(AssetRow row) {
        return checkedPayloadText(row.payload(), row.sha256());
    }

    private Map<String, Object> checkedPayloadText(String payloadText, String sha) {
        if (!sha256(payloadText).equals(sha)) {
            throw new LearningException("memory_content_checksum_failed",
                    "内容校验失败，快照被改动");
        }
        return parse(payloadText);
    }

    private Map<String, Object> parsePayload(String payloadText) {
        return parse(payloadText);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String text) {
        try {
            return MAPPER.readValue(text, Map.class);
        } catch (IOException error) {
            throw new IllegalStateException("载荷不是 JSON 对象", error);
        }
    }

    private record LoadedRow(String id, String payload, String sha256) {}

    private LoadedRow loadRowRaw(Connection conn, String table, String idColumn, String id)
            throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT " + idColumn + ", payload, sha256 FROM " + table
                        + " WHERE " + idColumn + " = ?")) {
            st.setString(1, id);
            ResultSet rs = st.executeQuery();
            if (!rs.next()) {
                return null;
            }
            return new LoadedRow(rs.getString(1), rs.getString(2), rs.getString(3));
        }
    }

    private Map<String, Object> loadRow(Connection conn, String table, String idColumn,
            String id, String what) throws SQLException {
        LoadedRow row = loadRowRaw(conn, table, idColumn, id);
        if (row == null) {
            String code = switch (table) {
                case "learning_recalls" -> "recall_not_found";
                case "learning_bindings" -> "binding_not_found";
                default -> "asset_not_found";
            };
            String noun = switch (table) {
                case "learning_recalls" -> "召回包";
                case "learning_bindings" -> "采用快照";
                default -> "记忆条目";
            };
            throw new LearningException(code, noun + "不存在：" + id);
        }
        if (!sha256(row.payload()).equals(row.sha256())) {
            throw new LearningException("memory_content_checksum_failed",
                    what + "内容校验失败，快照被改动");
        }
        Map<String, Object> payload = parse(row.payload());
        if (!id.equals(payload.get(idColumn))) {
            payload.put(idColumn, id);
        }
        return payload;
    }

    private AssetRow readAsset(Connection conn, String assetId) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT * FROM learning_assets WHERE asset_id = ?")) {
            st.setString(1, assetId);
            ResultSet rs = st.executeQuery();
            if (!rs.next()) {
                throw new LearningException("asset_not_found", "记忆条目不存在：" + assetId);
            }
            return new AssetRow(rs);
        }
    }

    private List<Map<String, Object>> rowEvents(Connection conn, String assetId)
            throws SQLException {
        List<Map<String, Object>> events = new ArrayList<>();
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT * FROM learning_events WHERE asset_id = ? ORDER BY event_id")) {
            st.setString(1, assetId);
            ResultSet rs = st.executeQuery();
            while (rs.next()) {
                events.add(new LinkedHashMap<>(Map.of(
                        "actor", rs.getString("actor"), "action", rs.getString("action"),
                        "note", rs.getString("note"),
                        "evidence", parse(rs.getString("evidence")), "at", rs.getString("at"))));
            }
        }
        return events;
    }

    private void appendEvent(Connection conn, String assetId, String actor, String action,
            String note, String evidenceJson) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "INSERT INTO learning_events(asset_id, actor, action, note, evidence, at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            st.setString(1, assetId);
            st.setString(2, actor);
            st.setString(3, action);
            st.setString(4, note == null ? "" : note);
            st.setString(5, evidenceJson == null ? "{}" : evidenceJson);
            st.setString(6, now());
            st.executeUpdate();
        }
    }

    private void insertRun(Connection conn, String bindingId, String phase,
            Map<String, Object> payload) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "INSERT INTO learning_runs(binding_id, phase, payload, at) VALUES (?, ?, ?, ?)")) {
            st.setString(1, bindingId);
            st.setString(2, phase);
            st.setString(3, canonical(payload));
            st.setString(4, now());
            st.executeUpdate();
        }
    }

    /** 采用版本防漂移（S01 _validate_binding_assets）：绑定封存的资产 sha 与现账逐一比对。 */
    private void validateBindingAssets(Connection conn, Map<String, Object> payload)
            throws SQLException {
        for (Object asset : (List<?>) payload.getOrDefault("assets", List.of())) {
            if (!(asset instanceof Map<?, ?> row)) {
                continue;
            }
            String assetId = String.valueOf(row.get("asset_id"));
            try (PreparedStatement st = conn.prepareStatement(
                    "SELECT sha256 FROM learning_assets WHERE asset_id = ?")) {
                st.setString(1, assetId);
                ResultSet rs = st.executeQuery();
                if (!rs.next() || !rs.getString(1).equals(row.get("sha256"))) {
                    throw new LearningException("binding_version_changed",
                            "采用版本内容已变化：" + assetId);
                }
            }
        }
    }

    private void requirePhaseFresh(Connection conn, String bindingId, String phase)
            throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT 1 FROM learning_runs WHERE binding_id = ? AND phase = ?")) {
            st.setString(1, bindingId);
            st.setString(2, phase);
            if (st.executeQuery().next()) {
                throw new LearningException("phase_already_recorded",
                        "相位已记录，只记一次：" + phase);
            }
        }
    }

    private Map<String, Object> runPayload(Connection conn, String bindingId, String phase)
            throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT payload FROM learning_runs WHERE binding_id = ? AND phase = ?")) {
            st.setString(1, bindingId);
            st.setString(2, phase);
            ResultSet rs = st.executeQuery();
            if (!rs.next()) {
                return null;
            }
            return parse(rs.getString(1));
        }
    }

    private Map<String, Object> runPayloadQuiet(String bindingId, String phase) {
        try (Connection conn = open()) {
            return runPayload(conn, bindingId, phase);
        } catch (SQLException error) {
            throw new IllegalStateException("相位查询失败：" + bindingId, error);
        }
    }

    private static String sourceTask(Map<String, Object> payload) {
        Object source = payload.get("source");
        if (source instanceof Map<?, ?> row) {
            return String.valueOf(row.get("task_id"));
        }
        return "";
    }

    private static String sourceSha(Map<String, Object> source) {
        return sha256(canonical(source));
    }

    private static List<String> loweredList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).strip().toLowerCase());
                }
            }
        }
        return out;
    }

    private static List<String> splitFlags(String joined) {
        List<String> out = new ArrayList<>();
        if (joined == null || joined.isBlank()) {
            return out;
        }
        for (String item : joined.split("\n")) {
            if (!item.isBlank()) {
                out.add(item.strip());
            }
        }
        return out;
    }

    private String newId(String prefix, String table, String column) {
        for (int attempt = 0; attempt < 8; attempt++) {
            String candidate = prefix + "-" + UUID.randomUUID().toString()
                    .replaceAll("-", "").substring(0, 8);
            try (Connection conn = open();
                 PreparedStatement st = conn.prepareStatement(
                         "SELECT 1 FROM " + table + " WHERE " + column + " = ?")) {
                st.setString(1, candidate);
                if (!st.executeQuery().next()) {
                    return candidate;
                }
            } catch (SQLException error) {
                throw new IllegalStateException("标识生成失败", error);
            }
        }
        throw new LearningException("id_generation_failed", "标识生成反复冲突，请重试");
    }

    /** 规范序列化（S01 _dumps sort_keys 语义的 Java 形态：TreeMap 键序 + 紧凑 JSON）。 */
    static String canonical(Object node) {
        return PyJson.dumpsCompact(sortKeys(node));
    }

    private static Object sortKeys(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), sortKeys(entry.getValue()));
            }
            return sorted;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(sortKeys(item));
            }
            return out;
        }
        return node;
    }

    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 计算失败", error);
        }
    }

    static String now() {
        return OffsetDateTime.now(java.time.ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .replaceAll("\\.\\d+", "");
    }

    private Connection open() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (java.sql.Statement st = conn.createStatement()) {
            st.execute("PRAGMA busy_timeout = 5000");
        }
        return conn;
    }
}
