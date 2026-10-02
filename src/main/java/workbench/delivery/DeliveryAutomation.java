package workbench.delivery;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 交付自动化流水线（上游 {@code workbench/automation.py} 对应物，L13 讲义 D2/D4）：
 * submit（生成 Task ID + 任务级 Spec + 首事件）→ start（worker 线程池 + 等待队列）→
 * 有界重试（max_attempts，耗尽保留 failure_preserved）→ 失败自动沉淀待审反馈
 * （观察自动、接受具名）→ wait（软超时按 version 续期 + 硬超时）→ recover（重启恢复）。
 *
 * <p>上游 docstring 逐字承接：「Automation deliberately stops at {@code review} or
 * {@code rework}. Human approval is a separate authenticated API action and is
 * never executed by this worker」——自动化停在人审前，人审是分离的具名动作、
 * worker 永不执行。上游 maintenance gate / agent_runner 缝不在本讲断言面
 * （讲义 D1 不采纳清单），如实不建。
 */
public final class DeliveryAutomation {

    /** 等待超时（上游 TimeoutError 词面）。 */
    public static final class WaitTimeout extends RuntimeException {
        public WaitTimeout(String message) {
            super(message);
        }
    }

    private final TaskStore store;
    private final Path runtimeDir;
    private final Workflow.SuiteRunner suiteRunner;
    private final Workflow.ExecutionRunner executionRunner;
    private final int maxWorkers;
    private final int maxAttempts;
    private final Feedback feedback;

    private final Object lock = new Object();
    private final Map<String, Thread> threads = new LinkedHashMap<>();
    private final List<String[]> pending = new ArrayList<>();

    public DeliveryAutomation(TaskStore store, Path runtimeDir, Workflow.SuiteRunner suiteRunner,
            int maxWorkers, Workflow.ExecutionRunner executionRunner, int maxAttempts) {
        if (maxWorkers < 1) {
            throw new IllegalArgumentException("max_workers 必须至少为 1");
        }
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException("max_attempts 必须在 1 到 10 之间");
        }
        this.store = store;
        this.runtimeDir = runtimeDir.toAbsolutePath().normalize();
        this.suiteRunner = suiteRunner;
        this.executionRunner = executionRunner;
        this.maxWorkers = maxWorkers;
        this.maxAttempts = maxAttempts;
        this.feedback = new Feedback(store.path());
    }

    /**
     * 提交即受理（上游 submit 同形）：需求规范化 + 业务引用提取（空则挂需求编号）+
     * Task ID 生成 + 任务级 Spec 生成（失败转 failed「自动生成 Spec 失败」）+
     * 首事件「已从需求生成任务级结构化 Spec」+ 可选自动开跑。
     */
    public Map<String, Object> submit(String request, String requirementId, List<String> businessRefs,
            String actor, String executionMode, List<String> writeScope,
            int executionTimeoutSeconds, boolean autoStart) {
        String requirement = SpecFactory.normalizeRequirementId(requirementId);
        List<String> refs = SpecFactory.normalizeBusinessRefs(request, businessRefs);
        if (refs.isEmpty()) {
            refs = List.of("REQUIREMENT:" + requirement);
        }
        String taskId = "TASK-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 10).toUpperCase();
        Path specPath = runtimeDir.resolve("specs").resolve(taskId + ".md");
        Map<String, Object> task = store.create(request, requirement, refs,
                specPath.toString(), actor, "automatic", taskId, executionMode,
                writeScope, executionTimeoutSeconds, null);
        try {
            SpecFactory.writeDeliverySpec(specPath, request, requirement, refs);
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("spec_path", specPath.toString());
            evidence.put("business_refs", refs);
            store.appendEvent(taskId, "已从需求生成任务级结构化 Spec", "automation", evidence);
        } catch (RuntimeException error) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("error_type", error.getClass().getSimpleName());
            return store.transition(taskId, "failed", "自动生成 Spec 失败", "automation",
                    evidence, Map.of("error", String.valueOf(error.getMessage())));
        }
        if (autoStart) {
            start(taskId, "automation");
        }
        return store.get(taskId);
    }

    /** 便捷重载（上游默认参同形：actor=system / verify / 无写集 / 900s / 自动开跑）。 */
    public Map<String, Object> submit(String request, String requirementId, List<String> businessRefs) {
        return submit(request, requirementId, businessRefs, "system", "verify", null, 900, true);
    }

    /** 开跑（上游 start 同形）：存活去重 + 等待队列 + 状态面校验 + worker 线程。 */
    public Map<String, Object> start(String taskId, String actor) {
        synchronized (lock) {
            Thread existing = threads.get(taskId);
            if (existing != null && existing.isAlive()) {
                return store.get(taskId);
            }
            if (pending.stream().anyMatch(item -> item[0].equals(taskId))) {
                return store.get(taskId);
            }
            Map<String, Object> task = store.get(taskId);
            String status = String.valueOf(task.get("status"));
            if (!List.of("queued", "spec_ready", "rework").contains(status)) {
                throw new IllegalArgumentException("自动流水线只允许 queued、spec_ready 或可安全重放的 rework 任务启动");
            }
            if (threads.size() >= maxWorkers) {
                pending.add(new String[] {taskId, actor});
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("max_workers", maxWorkers);
                store.appendEvent(taskId, "自动流水线等待可用 Worker", actor, evidence);
                return store.get(taskId);
            }
            Thread worker = new Thread(() -> executeOwned(taskId, actor), "delivery-" + taskId);
            worker.setDaemon(true);
            threads.put(taskId, worker);
            worker.start();
        }
        return store.get(taskId);
    }

    // ---- worker ------------------------------------------------------------------

    private void executeOwned(String taskId, String actor) {
        boolean retry = false;
        int attempts = 1;
        try {
            attempts = countStartEvents(taskId) + 1;
            Map<String, Object> attemptEvidence = new LinkedHashMap<>();
            attemptEvidence.put("stages", List.of("spec", "execute", "blocking_eval", "human_review"));
            attemptEvidence.put("human_review_is_automatic", false);
            attemptEvidence.put("attempt", attempts);
            attemptEvidence.put("max_attempts", maxAttempts);
            store.appendEvent(taskId, "自动流水线开始推进", actor, attemptEvidence);
            Map<String, Object> result = Workflow.runTask(store, taskId, actor, suiteRunner, executionRunner);
            String status = String.valueOf(result.get("status"));
            if ("rework".equals(status) && attempts < maxAttempts) {
                retry = true;
                store.appendEvent(taskId, "自动流水线安排有界重试", actor,
                        retryEvidence(attempts, result));
            } else if ("rework".equals(status)) {
                Map<String, Object> evidence = retryEvidence(attempts, result);
                evidence.put("failure_preserved", true);
                store.appendEvent(taskId, "有界重试已耗尽，保留阻断失败并等待人工处理", actor, evidence);
            } else if ("failed".equals(status)) {
                Map<String, Object> evidence = retryEvidence(attempts, result);
                store.transition(taskId, "dead_letter", "自动流水线遇到不可重试的执行失败，转人工处理",
                        actor, evidence, Map.of("error",
                                String.valueOf(result.getOrDefault("error", "自动流水线未能收敛"))));
            }
        } catch (RuntimeException error) {
            // 崩溃的 worker 不得留在进行中的持久状态里：保留异常为证据，复用有界重试策略
            Map<String, Object> task = store.get(taskId);
            String status = String.valueOf(task.get("status"));
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("attempt", attempts);
            evidence.put("max_attempts", maxAttempts);
            evidence.put("error_type", error.getClass().getSimpleName());
            evidence.put("error", String.valueOf(error.getMessage()));
            evidence.put("failure_preserved", true);
            Map<String, Object> current = task;
            if (List.of("executing", "evaluating", "review").contains(status)) {
                current = store.transition(taskId, "rework", "自动流水线异常中断，保留现场并进入有界返工",
                        actor, evidence, Map.of("error", String.valueOf(error.getMessage())));
            } else if (List.of("queued", "spec_ready").contains(status)) {
                current = store.transition(taskId, "failed", "自动流水线在执行前异常中断",
                        actor, evidence, Map.of("error", String.valueOf(error.getMessage())));
            } else {
                store.appendEvent(taskId, "自动流水线捕获未处理异常", actor, evidence);
            }
            String nowStatus = String.valueOf(current.get("status"));
            if ("rework".equals(nowStatus) && attempts < maxAttempts) {
                retry = true;
                Map<String, Object> next = new LinkedHashMap<>(evidence);
                next.put("next_attempt", attempts + 1);
                store.appendEvent(taskId, "自动流水线为异常中断安排有界重试", actor, next);
            } else if ("rework".equals(nowStatus)) {
                store.appendEvent(taskId, "异常重试已耗尽，保留失败并等待人工处理", actor, evidence);
            } else if ("failed".equals(nowStatus)) {
                store.transition(taskId, "dead_letter", "不可安全重试的流水线异常转人工处理",
                        actor, evidence, Map.of("error", String.valueOf(error.getMessage())));
            }
        } finally {
            if (!retry) {
                Map<String, Object> task = store.get(taskId);
                String status = String.valueOf(task.get("status"));
                if (List.of("rework", "failed", "dead_letter").contains(status)) {
                    try {
                        Map<String, Object> observation = feedback.observeTaskFailure(task);
                        Map<String, Object> evidence = new LinkedHashMap<>();
                        evidence.put("feedback_id", observation.get("id"));
                        evidence.put("feedback_status", observation.get("status"));
                        evidence.put("automatic_acceptance", false);
                        store.appendEvent(taskId, "失败已沉淀为待具名审核的反馈候选", "automation", evidence);
                    } catch (RuntimeException error) {
                        Map<String, Object> evidence = new LinkedHashMap<>();
                        evidence.put("error_type", error.getClass().getSimpleName());
                        evidence.put("error", String.valueOf(error.getMessage()));
                        store.appendEvent(taskId, "自动沉淀失败反馈失败，任务终态保持不变", "automation", evidence);
                    }
                }
            }
            String[] next = null;
            synchronized (lock) {
                threads.remove(taskId);
                if (retry) {
                    pending.add(new String[] {taskId, "automation"});
                }
                if (!pending.isEmpty()) {
                    next = pending.remove(0);
                }
            }
            if (next != null) {
                start(next[0], next[1]);
            }
        }
    }

    private Map<String, Object> retryEvidence(int attempts, Map<String, Object> result) {
        Map<String, Object> lastEval = result.get("result") instanceof Map<?, ?> map
                ? nested(map) : null;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("attempt", attempts);
        evidence.put("max_attempts", maxAttempts);
        evidence.put("last_status", result.get("status"));
        evidence.put("last_error", result.get("error"));
        evidence.put("last_eval", lastEval == null ? null
                : lastEval.getOrDefault("summary", Map.of()));
        return evidence;
    }

    private Map<String, Object> nested(Map<?, ?> map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> cast = (Map<String, Object>) map;
        return cast;
    }

    @SuppressWarnings("unchecked")
    private int countStartEvents(String taskId) {
        Map<String, Object> task = store.get(taskId);
        int count = 0;
        for (Object item : (List<Object>) task.get("events")) {
            if (item instanceof Map<?, ?> event
                    && "自动流水线开始推进".equals(event.get("detail"))) {
                count++;
            }
        }
        return count;
    }

    // ---- wait / recover / isActive ------------------------------------------------------------------

    /**
     * 等待到终态（上游 wait 同形）：软超时按 version 变化续期（推进中的多段任务
     * 不会误超时）；硬超时 = max(软, 执行超时+30)；worker 仍活跃时不抛软超时——
     * 存活 worker 可能合法地在 Eval 子进程里耗时，其任务级执行超时才是硬上界。
     */
    public Map<String, Object> wait(String taskId, double timeoutSeconds) {
        long deadline = System.nanoTime() + (long) (timeoutSeconds * 1_000_000_000L);
        Map<String, Object> task = store.get(taskId);
        int executionTimeout = task.get("execution_timeout_seconds") instanceof Number number
                ? number.intValue() : 900;
        long hardDeadline = System.nanoTime()
                + (long) (Math.max(timeoutSeconds, executionTimeout + 30) * 1_000_000_000L);
        Object lastVersion = null;
        while (true) {
            boolean active;
            synchronized (lock) {
                // 同一把锁下读持久状态与 worker 注册表：否则 worker 可能在两次读之间
                // 收尾注销，让旧恢复检查点看起来已是终态
                task = store.get(taskId);
                Thread worker = threads.get(taskId);
                active = (worker != null && worker.isAlive())
                        || pending.stream().anyMatch(item -> item[0].equals(taskId));
            }
            Object version = task.get("version");
            if (lastVersion == null || !version.equals(lastVersion)) {
                lastVersion = version;
                deadline = System.nanoTime() + (long) (timeoutSeconds * 1_000_000_000L);
            }
            String status = String.valueOf(task.get("status"));
            if (List.of("review", "completed", "dead_letter", "rework", "failed").contains(status)
                    && !active) {
                return task;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                if (!active) {
                    throw new WaitTimeout("等待自动流水线超时：" + taskId);
                }
                long hardRemaining = hardDeadline - System.nanoTime();
                if (hardRemaining <= 0) {
                    throw new WaitTimeout("自动流水线超过任务执行硬超时：" + taskId);
                }
                remaining = Math.min(250_000_000L, hardRemaining);
            }
            try {
                Thread.sleep(Math.min(remaining, 50_000_000L) / 1_000_000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待自动流水线被中断：" + taskId, error);
            }
        }
    }

    /** 重启恢复（上游 recover 同形）：安全重放集逐个重启。 */
    public List<String> recover() {
        List<String> recovered = store.recoverAutomaticTasks();
        for (String taskId : recovered) {
            start(taskId, "automation-recovery");
        }
        return recovered;
    }

    public boolean isActive(String taskId) {
        synchronized (lock) {
            Thread worker = threads.get(taskId);
            return (worker != null && worker.isAlive())
                    || pending.stream().anyMatch(item -> item[0].equals(taskId));
        }
    }
}
