package workbench.delivery;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 交付投影视图（上游 {@code workbench/delivery_view.py} 断言面子集，L14 讲义 D4）：
 * 稳定只读投影——每次调用实时构建，不缓存旧状态（上游 constructibility 点名坑
 * 「投影视图写死旧状态」的代码面否定）。
 *
 * <p>诚实性不变量（上游教学点逐条承接）：未知状态显式不可信（「状态不在交付合同中，
 * 禁止显示为成功」）；review 是深思熟虑的人工等待态，<b>不因等待久而标 stale</b>
 * （stale-sensitive 集不含 review）；completed 无具名批准 / review 无绿 Eval /
 * 越写集写入 → integrity issues 如实列出，truthful 聚合；includeDetail=false 轻投影
 * 压缩（results/spec/events 摘要化，cases/报告路径/SHA-256 保留）。
 *
 * <p>D4 裁掉块如实记录（上游 main 后期增量或跨讲面，不在本讲断言面）：
 * control_surface、evolution、lane（cockpit）、workflow_graph、
 * status 的 pipeline 来源字段（stage_id/label/title/summary——known 态 label 为 null，
 * 仅 dead_letter「已停止，待核对」与 unknown「未知状态」词面保留——上游测试断言面）、
 * delivery_summary.risks 的 injected_process_runner/authorization_policy 依赖项、
 * policy 的 workspace_path/authorization_policy、spec 的 text/sha256（本仓存储面是
 * spec 结构化 Map + spec_path，无 spec_text 列——形态差异如实）。
 */
public final class DeliveryView {

    /** 上游 SCHEMA 逐字。 */
    public static final String SCHEMA = "workbench.delivery-view/v1";

    /** 上游列表 schema 逐字。 */
    public static final String LIST_SCHEMA = "workbench.delivery-view-list/v1";

    /** 活跃态集（上游 ACTIVE_STATUSES 逐字）。 */
    public static final Set<String> ACTIVE_STATUSES =
            Set.of("queued", "spec_ready", "executing", "evaluating", "review", "rework");

    /** 终态集（上游 TERMINAL_STATUSES 逐字）。 */
    public static final Set<String> TERMINAL_STATUSES =
            Set.of("completed", "failed", "dead_letter");

    /**
     * stale-sensitive 集（上游 STALE_SENSITIVE_STATUSES 逐字）——review 不在内：
     * 人工审核等待不是不诚实（上游注释原话：review is a deliberate human wait state
     * and must not become dishonest merely because the reviewer takes longer）。
     */
    public static final Set<String> STALE_SENSITIVE_STATUSES =
            Set.of("queued", "spec_ready", "executing", "evaluating", "rework");

    /** 新鲜度阈值（秒，上游 300 同形）。 */
    public static final int STALE_AFTER_SECONDS = 300;

    /**
     * 九态归属表（上游 _STATUS_CONTROL 逐字翻译）：状态 → (归属人 id, 归属人标签,
     * 下一步动作)。unknown 状态走兜底行（上游 get 缺省三元组逐字）。
     */
    static final Map<String, String[]> STATUS_CONTROL = Map.of(
            "queued", new String[] {"automation", "自动化调度", "生成并校验任务级 Spec"},
            "spec_ready", new String[] {"agent:coder", "实现者", "按写入范围执行或直接进入验证"},
            "executing", new String[] {"agent:coder", "实现者", "完成受控修改并提交真实执行证据"},
            "evaluating", new String[] {"agent:reviewer", "质量验证", "运行统一 blocking Eval 并保存报告"},
            "review", new String[] {"human:reviewer", "具名终审人", "具名核对 Spec、Diff 与 Eval 后接受或打回"},
            "rework", new String[] {"agent:coder", "返工责任人", "根据保留的阻断证据进行有界修复"},
            "completed", new String[] {"human:reviewer", "交付责任人", "采集真实反馈并观察后续业务结果"},
            "failed", new String[] {"human:operator", "人工处置人", "核对不可重试错误并决定恢复或停止"},
            "dead_letter", new String[] {"human:operator", "人工处置人",
                    "先核对中断原因和实际改动，确认后再创建新任务；原记录会保留"});

    private static final String[] UNKNOWN_CONTROL = {"human:operator", "人工处置人",
            "停止自动推进并核对未知状态、数据库和事件链"};

    /** 上游 _STATUS_CONTROL 需要人介入的状态（+unknown）。 */
    private static final Set<String> REQUIRES_HUMAN_BASE = Set.of("review", "failed", "dead_letter");

    private static final DateTimeFormatter SQLITE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DeliveryView() {}

    // ---- 服务面 ---------------------------------------------------------------------------------------------------

    /** 单任务完整投影（detail 全量；feedback 经同库 summary 过滤本任务条目）。 */
    public static Map<String, Object> view(TaskStore tasks, Feedback feedback, String taskId) {
        return build(tasks.get(taskId), feedbackItems(feedback), true, null);
    }

    /** 列表投影（轻投影 + 汇总计数；上游 list(limit) 同形）。 */
    public static Map<String, Object> list(TaskStore tasks, Feedback feedback, int limit) {
        List<Map<String, Object>> items = feedbackItems(feedback);
        List<Map<String, Object>> views = new ArrayList<>();
        for (Map<String, Object> task : tasks.list(limit)) {
            views.add(build(task, items, false, null));
        }
        int active = 0;
        int review = 0;
        int completed = 0;
        int rework = 0;
        int failed = 0;
        int unknown = 0;
        int pendingFeedback = 0;
        for (Map<String, Object> view : views) {
            String code = statusCode(view);
            if (ACTIVE_STATUSES.contains(code)) {
                active++;
            }
            if ("review".equals(code)) {
                review++;
            }
            if ("completed".equals(code)) {
                completed++;
            }
            if ("rework".equals(code)) {
                rework++;
            }
            if ("failed".equals(code) || "dead_letter".equals(code)) {
                failed++;
            }
            if (!statusKnown(view)) {
                unknown++;
            }
            pendingFeedback += nestedInt(view, "feedback", "pending_review");
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", views.size());
        summary.put("active", active);
        summary.put("review", review);
        summary.put("completed", completed);
        summary.put("rework", rework);
        summary.put("failed", failed);
        summary.put("unknown", unknown);
        summary.put("pending_feedback", pendingFeedback);
        // 上游第九计数 verified_evolutions 属 evolution 块（D4 裁掉，L15 按断言面增量补块）
        Map<String, Object> listing = new LinkedHashMap<>();
        listing.put("schema", LIST_SCHEMA);
        listing.put("summary", summary);
        listing.put("items", views);
        return listing;
    }

    // ---- 纯投影（build(task, now)——上游 build_delivery_view(task, now=...) 同形缝） -------------------------------

    /** 完整投影便捷面（now = 当前时刻；无反馈条目）。 */
    public static Map<String, Object> build(Map<String, Object> task, OffsetDateTime now) {
        return build(task, List.of(), true, now);
    }

    /** 投影本体：把存储原始行投成一条诚实的 API/Web 合同（上游 docstring 语义）。 */
    public static Map<String, Object> build(Map<String, Object> task,
            List<Map<String, Object>> feedbackItems, boolean includeDetail, OffsetDateTime now) {
        String taskId = text(task.get("id"));
        List<Map<String, Object>> feedback = new ArrayList<>();
        for (Map<String, Object> item : feedbackItems) {
            if (taskId.equals(item.get("task_id"))) {
                feedback.add(item);
            }
        }
        Map<String, Object> status = statusView(task);
        Map<String, Object> freshness = freshness(task, now);
        Map<String, Object> evalView = evalView(task, includeDetail);
        Map<String, Object> execution = executionView(task, includeDetail);
        Map<String, Object> review = reviewBlock(task);

        List<String> issues = new ArrayList<>();
        if (!Boolean.TRUE.equals(status.get("known"))) {
            issues.add("unknown_status");
        }
        if (Boolean.TRUE.equals(freshness.get("stale"))) {
            issues.add("stale_active_task");
        }
        if ("completed".equals(status.get("code")) && (text(review.get("reviewed_by")).isEmpty()
                || !"approve".equals(review.get("decision")))) {
            issues.add("completed_without_named_approval");
        }
        if (Set.of("review", "completed").contains(status.get("code"))
                && (!"pass".equals(evalView.get("decision"))
                        || ((Number) evalView.getOrDefault("blocking_failed", 0)).intValue() != 0)) {
            issues.add("review_without_green_blocking_eval");
        }
        if (!((List<?>) execution.getOrDefault("out_of_scope_files", List.of())).isEmpty()) {
            issues.add("out_of_scope_writes");
        }

        Map<String, Object> taskProjection = new LinkedHashMap<>(task);
        if (!includeDetail) {
            taskProjection.remove("spec");
            taskProjection.remove("result");
            taskProjection.remove("events");
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("schema", SCHEMA);
        view.put("task_id", taskId);
        view.put("requirement_id", textOrEmpty(task.get("requirement_id")));
        view.put("request", textOrEmpty(task.get("request")));
        view.put("business_refs", listOrEmpty(task.get("business_refs")));
        view.put("task", taskProjection);
        view.put("delivery_summary", deliverySummary(task, execution));
        view.put("status", status);
        view.put("freshness", freshness);
        view.put("policy", policy(task));
        view.put("spec", specBlock(task, includeDetail));
        view.put("execution", execution);
        view.put("eval", evalView);
        view.put("review", review);
        view.put("feedback", feedbackBlock(feedback, includeDetail));
        view.put("events", includeDetail ? eventViews(task) : List.of());
        view.put("allowed_actions", allowedActions(task));
        Map<String, Object> integrity = new LinkedHashMap<>();
        integrity.put("truthful", issues.isEmpty());
        integrity.put("issues", issues);
        view.put("integrity", integrity);
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("self", "/api/v1/delivery/views/" + taskId);
        links.put("task", "/api/v1/tasks/" + taskId);
        links.put("web", "/#delivery");
        view.put("links", links);
        return view;
    }

    // ---- 分块 ---------------------------------------------------------------------------------------------------

    /** 状态视图：九态归属表 + unknown 兜底（「禁止显示为成功」）。 */
    static Map<String, Object> statusView(Map<String, Object> task) {
        String code = text(task.get("status"));
        boolean known = STATUS_CONTROL.containsKey(code);
        String[] control = known ? STATUS_CONTROL.get(code) : UNKNOWN_CONTROL;
        String ownerId = control[0];
        String ownerLabel = control[1];
        if ("completed".equals(code) && !text(task.get("reviewed_by")).isEmpty()) {
            ownerId = text(task.get("reviewed_by"));
            ownerLabel = "具名交付审核人";
        }
        Map<String, Object> owner = new LinkedHashMap<>();
        owner.put("id", ownerId);
        owner.put("label", ownerLabel);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("code", code.isEmpty() ? "unknown" : code);
        status.put("known", known);
        // D4：known 态的 pipeline 来源 label 为 null（上游 label/title/summary/stage_id
        // 来自 delivery_pipeline——不在断言面不建）；dead_letter 与 unknown 词面保留
        status.put("label", "dead_letter".equals(code) ? "已停止，待核对" : known ? null : "未知状态");
        status.put("owner", owner);
        status.put("next_action", control[2]);
        status.put("terminal", TERMINAL_STATUSES.contains(code));
        status.put("requires_human", REQUIRES_HUMAN_BASE.contains(code) || !known);
        return status;
    }

    /** 新鲜度：updated_at 不可解析 → unknown/stale；stale-sensitive 超 300s 才 stale；review 永不因等待 stale。 */
    static Map<String, Object> freshness(Map<String, Object> task, OffsetDateTime now) {
        Object raw = task.get("updated_at");
        OffsetDateTime updated = parseStamp(raw);
        Map<String, Object> freshness = new LinkedHashMap<>();
        freshness.put("updated_at", raw);
        if (updated == null) {
            freshness.put("age_seconds", null);
            freshness.put("state", "unknown");
            freshness.put("stale", true);
            return freshness;
        }
        OffsetDateTime current = now == null ? OffsetDateTime.now(ZoneOffset.UTC) : now;
        long age = Math.max(0, current.toEpochSecond() - updated.toEpochSecond());
        String code = text(task.get("status"));
        boolean stale = STALE_SENSITIVE_STATUSES.contains(code) && age > STALE_AFTER_SECONDS;
        freshness.put("age_seconds", age);
        freshness.put("state", stale ? "stale" : TERMINAL_STATUSES.contains(code) ? "final" : "current");
        freshness.put("stale", stale);
        return freshness;
    }

    /** Eval 摘要：decision/blocking 计数（summary 缺项按 results 兜底计算）/ 报告路径 + SHA-256 / cases。 */
    static Map<String, Object> evalView(Map<String, Object> task, boolean includeDetail) {
        Map<String, Object> result = task.get("result") instanceof Map<?, ?> map ? cast(map) : Map.of();
        Map<String, Object> summary = result.get("summary") instanceof Map<?, ?> map ? cast(map) : Map.of();
        List<Map<String, Object>> results = new ArrayList<>();
        if (result.get("results") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    results.add(cast(map));
                }
            }
        }
        List<Map<String, Object>> blocking = new ArrayList<>();
        for (Map<String, Object> item : results) {
            if ("blocking".equals(item.get("level"))) {
                blocking.add(item);
            }
        }
        Integer passed = summary.get("blocking_passed") instanceof Number number
                ? number.intValue() : countPassed(blocking, true);
        Integer failed = summary.get("blocking_failed") instanceof Number number
                ? number.intValue() : countPassed(blocking, false);
        List<Map<String, Object>> cases = new ArrayList<>();
        for (Map<String, Object> item : results) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", text(item.get("name")));
            row.put("level", text(item.get("level")));
            row.put("passed", Boolean.TRUE.equals(item.get("passed")));
            cases.add(row);
        }
        Map<String, Object> eval = new LinkedHashMap<>();
        eval.put("available", !result.isEmpty());
        eval.put("decision", summary.get("decision"));
        eval.put("blocking_passed", passed == null ? 0 : passed);
        eval.put("blocking_failed", failed == null ? 0 : failed);
        eval.put("report_path", result.get("report_path") == null
                ? textOrEmpty(result.get("blocking_report")) : textOrEmpty(result.get("report_path")));
        eval.put("report_sha256", textOrEmpty(result.get("report_sha256")));
        eval.put("cases", cases);
        eval.put("summary", summary);
        eval.put("results", includeDetail ? results : List.of());
        eval.put("runner", result.get("runner"));
        return eval;
    }

    /** 执行证据视图：最近一条「受控执行阶段完成」事件；轻投影去重型字段。 */
    static Map<String, Object> executionView(Map<String, Object> task, boolean includeDetail) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (task.get("events") instanceof List<?> events) {
            for (int index = events.size() - 1; index >= 0; index--) {
                if (events.get(index) instanceof Map<?, ?> event
                        && "受控执行阶段完成".equals(event.get("detail"))
                        && event.get("evidence") instanceof Map<?, ?> candidate) {
                    evidence = cast(candidate);
                    break;
                }
            }
        }
        Map<String, Object> compact = new LinkedHashMap<>(evidence);
        if (!includeDetail) {
            for (String key : List.of("diff", "stdout_tail", "stderr_tail", "commands", "change_manifest")) {
                compact.remove(key);
            }
        }
        Map<String, Object> execution = new LinkedHashMap<>();
        execution.put("available", !evidence.isEmpty());
        execution.put("mode", evidence.get("mode"));
        execution.put("success", evidence.isEmpty() ? null : evidence.get("success"));
        execution.put("changed_files", stringList(evidence.get("changed_files")));
        execution.put("out_of_scope_files", stringList(evidence.get("out_of_scope_files")));
        execution.put("usage", evidence.get("usage") instanceof Map<?, ?> map ? cast(map) : Map.of());
        execution.put("evidence", compact);
        return execution;
    }

    /** 审核块：required = 当前 review 态 + 具名决定四件。 */
    private static Map<String, Object> reviewBlock(Map<String, Object> task) {
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("required", "review".equals(text(task.get("status"))));
        review.put("reviewed_by", task.get("reviewed_by"));
        review.put("decision", task.get("review_decision"));
        review.put("note", task.get("review_note"));
        review.put("reviewed_at", task.get("reviewed_at"));
        return review;
    }

    /** 反馈块：四计数（detail 时含条目）。 */
    private static Map<String, Object> feedbackBlock(List<Map<String, Object>> feedback,
            boolean includeDetail) {
        int pending = 0;
        int accepted = 0;
        int rejected = 0;
        for (Map<String, Object> item : feedback) {
            if ("pending_review".equals(item.get("status"))) {
                pending++;
            } else if ("accepted".equals(item.get("status"))) {
                accepted++;
            } else if ("rejected".equals(item.get("status"))) {
                rejected++;
            }
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("total", feedback.size());
        block.put("pending_review", pending);
        block.put("accepted", accepted);
        block.put("rejected", rejected);
        block.put("items", includeDetail ? feedback : List.of());
        return block;
    }

    /** 事件投影：全序，每事件带 to_status 的状态视图（下钻面）。 */
    private static List<Map<String, Object>> eventViews(Map<String, Object> task) {
        List<Map<String, Object>> projected = new ArrayList<>();
        if (task.get("events") instanceof List<?> events) {
            for (Object item : events) {
                if (item instanceof Map<?, ?> event) {
                    Map<String, Object> row = new LinkedHashMap<>(cast(event));
                    row.put("status", statusView(Map.of("status", event.get("to_status"))));
                    projected.add(row);
                }
            }
        }
        return projected;
    }

    /** 允许动作（上游 _allowed_actions 同形；export_candidate 条件 REQ-COURSE-L 前缀）。 */
    static List<String> allowedActions(Map<String, Object> task) {
        String status = text(task.get("status"));
        List<String> actions = new ArrayList<>(List.of("view_evidence", "add_feedback"));
        if (Set.of("queued", "spec_ready", "rework").contains(status)) {
            actions.add("run");
        }
        if ("review".equals(status)) {
            actions.add("approve");
            actions.add("reject");
        }
        if ("completed".equals(status) && text(task.get("requirement_id")).startsWith("REQ-COURSE-L")) {
            actions.add("export_candidate");
        }
        return actions;
    }

    /** 策略块（workspace_path/authorization_policy 两键上游有本仓无源——D4 裁掉落账）。 */
    private static Map<String, Object> policy(Map<String, Object> task) {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("automation_mode", text(task.get("automation_mode")).isEmpty()
                ? "manual" : task.get("automation_mode"));
        policy.put("execution_mode", text(task.get("execution_mode")).isEmpty()
                ? "verify" : task.get("execution_mode"));
        policy.put("write_scope", listOrEmpty(task.get("write_scope")));
        policy.put("execution_timeout_seconds", task.get("execution_timeout_seconds"));
        return policy;
    }

    /** Spec 块：available/path/title + goal/acceptance/content（detail）；text/sha256 本仓无源裁掉。 */
    private static Map<String, Object> specBlock(Map<String, Object> task, boolean includeDetail) {
        Map<String, Object> spec = task.get("spec") instanceof Map<?, ?> map ? cast(map) : null;
        String request = textOrEmpty(task.get("request"));
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("available", spec != null && !spec.isEmpty());
        block.put("path", textOrEmpty(task.get("spec_path")));
        block.put("title", request.length() > 120 ? request.substring(0, 120) : request);
        block.put("goal", includeDetail && spec != null ? textOrEmpty(spec.get("goal")) : "");
        block.put("acceptance", includeDetail && spec != null ? textOrEmpty(spec.get("acceptance")) : "");
        block.put("content", includeDetail ? spec : null);
        return block;
    }

    /**
     * 交付摘要：changed_files / validations / result / remaining_risks——risks 可承项
     * 按本仓字段翻译（provenance/authorization_policy 两依赖项 D4 裁掉）。
     */
    private static Map<String, Object> deliverySummary(Map<String, Object> task,
            Map<String, Object> execution) {
        List<Map<String, Object>> validations = new ArrayList<>();
        boolean hasValidationCommand = false;
        if (task.get("events") instanceof List<?> events) {
            for (Object item : events) {
                if (item instanceof Map<?, ?> event && event.get("evidence") instanceof Map<?, ?> evidence
                        && evidence.get("validation") instanceof Map<?, ?> validation) {
                    validations.add(cast(validation));
                    if (text(cast(validation).get("command")).isEmpty() == false) {
                        hasValidationCommand = true;
                    }
                }
            }
        }
        List<String> risks = new ArrayList<>();
        if (!"completed".equals(text(task.get("status")))) {
            risks.add("尚未获得实际审核者接受");
        }
        if (!text(task.get("error")).isEmpty()) {
            risks.add(text(task.get("error")));
        }
        if ("verify".equals(text(task.get("execution_mode")))) {
            risks.add("仅复验已有候选，未调用 Codex 编码");
        }
        if (!hasValidationCommand) {
            risks.add("尚无独立验证命令记录");
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("changed_files", execution.get("changed_files"));
        summary.put("validations", validations);
        summary.put("result", task.get("status"));
        summary.put("remaining_risks", risks);
        return summary;
    }

    // ---- 工具 ---------------------------------------------------------------------------------------------------

    private static List<Map<String, Object>> feedbackItems(Feedback feedback) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (feedback != null && feedback.summary().get("items") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    items.add(cast(map));
                }
            }
        }
        return items;
    }

    /** SQLite CURRENT_TIMESTAMP（"YYYY-MM-DD HH:MM:SS"，UTC）与 ISO 变体（T/Z/偏移）兼容解析；失败返 null。 */
    static OffsetDateTime parseStamp(Object value) {
        String text = text(value);
        if (text.isEmpty()) {
            return null;
        }
        String normalized = text.strip().replace(' ', 'T');
        if (normalized.endsWith("Z") || normalized.endsWith("z")) {
            normalized = normalized.substring(0, normalized.length() - 1) + "+00:00";
        }
        try {
            return OffsetDateTime.parse(normalized);
        } catch (DateTimeParseException error) {
            // 无时区形态（SQLite CURRENT_TIMESTAMP）按 UTC 补
            try {
                return LocalDateTime.parse(normalized).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException nested) {
                return null;
            }
        }
    }

    private static Integer countPassed(List<Map<String, Object>> blocking, boolean passed) {
        int count = 0;
        for (Map<String, Object> item : blocking) {
            if (Boolean.TRUE.equals(item.get("passed")) == passed) {
                count++;
            }
        }
        return count;
    }

    private static List<Object> stringList(Object value) {
        List<Object> items = new ArrayList<>();
        if (value instanceof List<?> list) {
            items.addAll(list);
        }
        return items;
    }

    private static List<Object> listOrEmpty(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static String textOrEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String statusCode(Map<String, Object> view) {
        return view.get("status") instanceof Map<?, ?> status ? text(status.get("code")) : "";
    }

    private static boolean statusKnown(Map<String, Object> view) {
        return view.get("status") instanceof Map<?, ?> status
                && Boolean.TRUE.equals(status.get("known"));
    }

    private static int nestedInt(Map<String, Object> view, String outer, String inner) {
        if (view.get(outer) instanceof Map<?, ?> block && block.get(inner) instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }
}
