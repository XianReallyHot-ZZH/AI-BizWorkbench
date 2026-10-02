package workbench.schedule;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 声明检查器纯核（L11；上游 {@code vendors/CodexFDE/agent/schedule.py} 对应物，逐句
 * 对照——agent 家族第三件，{@code repair/}、{@code loop/} 先例）。<b>无 CLI 面</b>：
 * 上游 schedule.py 即纯库件（loop.py / graph.py 均有 main，唯它没有），调用面全在
 * eval 登记项（{@code write_sets_reject_conflict}）与合同测试（讲义 D1 如实不造）。
 *
 * <p>语义（上游钉死，教学点全承接）：
 * <ul>
 * <li>独立性 = 三个交集都空：{@code W甲∩W乙}（不互相覆盖）、{@code W甲∩R乙}、
 *     {@code W乙∩R甲}（互不改变对方依据）；<b>读读共享允许</b>。
 * <li>路径规范化：反斜杠转斜杠、大小写不敏感保守折叠（课堂 Windows/macOS 跨平台，
 *     不同大小写路径不算独立）、尾斜杠剥除、拒绝绝对路径 / {@code ..} 跳转 /
 *     通配符 {@code :*?[]}——声明只接受明确的仓库相对文件或目录。
 * <li>目录覆盖：写 {@code flowerp/} 即与读 {@code flowerp/service.py} 冲突
 *     （前缀包含 + 根 {@code .} 全匹配）。
 * <li>共享资源：resource_set 交集报 {@code resource:} 前缀冲突（数据库/端口/报告
 *     路径等文件外共享）。
 * <li>共享项取更深路径（parts 更多者——上游 {@code max(key=len(parts))} 同形）。
 * <li>input_version 不统一拒绝——两份结论不得各基于不同共同输入。
 * <li><b>边界</b>（上游明示，教学反例 undeclared-read）：只查申报路径，不扫描真实
 *     工具行为、不锁文件、不沙箱——「声明通过 ≠ 执行安全」由调用方判断面承载。
 * </ul>
 *
 * <p>冲突拒绝词面（上游逐字）：「子任务名称必须非空且唯一」「读写集必须是明确的仓库
 * 相对文件或目录」「共享资源名称必须是非空且不含空白的标识」「并行子任务必须使用同
 * 一个固定输入版本」「共享写集或读写依赖不得并行：a×b→files」。
 */
public final class Schedule {

    /** 子任务声明（上游 {@code Subtask} dataclass frozen 对应物；list 不可变约定）。 */
    public record Subtask(String name, List<String> writeSet, List<String> readSet,
                          List<String> resourceSet, String inputVersion) {

        public Subtask {
            writeSet = List.copyOf(writeSet);
            readSet = List.copyOf(readSet);
            resourceSet = List.copyOf(resourceSet);
            inputVersion = inputVersion == null ? "" : inputVersion;
        }

        public Subtask(String name, List<String> writeSet) {
            this(name, writeSet, List.of(), List.of(), "");
        }

        public Subtask(String name, List<String> writeSet, List<String> readSet) {
            this(name, writeSet, readSet, List.of(), "");
        }
    }

    /** 一对冲突（上游 {@code (left, right, [files...])} 三元组对应物）。 */
    public record Conflict(String left, String right, List<String> shared) {}

    private Schedule() {}

    /**
     * 路径规范化（上游 {@code _scope} 逐句）：反斜杠→斜杠、拒绝越界与通配符、
     * {@code .} 段折叠、尾斜杠剥除、大小写保守折叠（Locale.ROOT——教学场景 ASCII
     * 为主，与 Python casefold 在此面等价）。
     */
    static String scope(String value) {
        String replaced = value.replace("\\", "/");
        if (replaced.isBlank() || replaced.startsWith("/")
                || hasWildcard(replaced) || hasParentHop(replaced)) {
            throw new ScheduleViolation("读写集必须是明确的仓库相对文件或目录");
        }
        List<String> parts = new ArrayList<>();
        for (String part : replaced.split("/")) {
            // PurePosixPath 语义：空段与 "." 段折叠掉，".." 保留（已在上面拒绝）
            if (!part.isEmpty() && !".".equals(part)) {
                parts.add(part);
            }
        }
        return String.join("/", parts).toLowerCase(Locale.ROOT);
    }

    private static boolean hasWildcard(String value) {
        return value.indexOf(':') >= 0 || value.indexOf('*') >= 0
                || value.indexOf('?') >= 0 || value.indexOf('[') >= 0 || value.indexOf(']') >= 0;
    }

    private static boolean hasParentHop(String value) {
        for (String part : value.split("/")) {
            if ("..".equals(part)) {
                return true;
            }
        }
        return false;
    }

    /** 重叠判定（上游 {@code _overlap} 逐句）：相等 / 根全匹配 / 前缀包含。 */
    static boolean overlap(String left, String right) {
        return left.equals(right) || ".".equals(left) || ".".equals(right)
                || left.startsWith(right + "/") || right.startsWith(left + "/");
    }

    /** 资源名规范化（上游 {@code _resource} 逐句）：非空、无空白、小写折叠。 */
    static String resource(String value) {
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new ScheduleViolation("共享资源名称必须是非空且不含空白的标识");
        }
        return normalized;
    }

    /**
     * 全部冲突对（上游 {@code conflict_pairs} 逐句）：名称非空唯一校验 → 写×写与
     * 写×读双向逐对比较（读×读不算冲突）→ resource_set 交集单列。顺序按输入序配对、
     * 每对内共享文件字典序（上游 sorted 同义）。
     */
    public static List<Conflict> conflictPairs(List<Subtask> tasks) {
        List<Subtask> items = List.copyOf(tasks);
        List<String> names = items.stream().map(task -> task.name().strip()).toList();
        if (names.stream().anyMatch(String::isEmpty) || new HashSet<>(names).size() != names.size()) {
            throw new ScheduleViolation("子任务名称必须非空且唯一");
        }
        Map<String, List<String>[]> scopes = new LinkedHashMap<>();
        Map<String, List<String>> resources = new LinkedHashMap<>();
        for (Subtask task : items) {
            scopes.put(task.name(), new List[]{
                    task.writeSet().stream().map(Schedule::scope).toList(),
                    task.readSet().stream().map(Schedule::scope).toList()});
            resources.put(task.name(), task.resourceSet().stream().map(Schedule::resource).toList());
        }
        List<Conflict> conflicts = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            for (int right = index + 1; right < items.size(); right++) {
                Subtask left = items.get(index);
                Subtask other = items.get(right);
                List<String> leftWrites = scopes.get(left.name())[0];
                List<String> leftReads = scopes.get(left.name())[1];
                List<String> rightWrites = scopes.get(other.name())[0];
                List<String> rightReads = scopes.get(other.name())[1];
                Set<String> shared = new LinkedHashSet<>();
                // 写×（写+读）双向（上游 ((left_writes, right_writes+right_reads),
                // (right_writes, left_reads)) 同形）
                for (List<String> writes : List.of(leftWrites)) {
                    for (String a : writes) {
                        for (String b : concat(rightWrites, rightReads)) {
                            if (overlap(a, b)) {
                                shared.add(deeper(a, b));
                            }
                        }
                    }
                }
                for (String a : rightWrites) {
                    for (String b : leftReads) {
                        if (overlap(a, b)) {
                            shared.add(deeper(a, b));
                        }
                    }
                }
                if (!shared.isEmpty()) {
                    conflicts.add(new Conflict(left.name(), other.name(),
                            shared.stream().sorted().toList()));
                }
                List<String> sharedResources = resources.get(left.name()).stream()
                        .filter(resources.get(other.name())::contains).distinct().sorted().toList();
                if (!sharedResources.isEmpty()) {
                    conflicts.add(new Conflict(left.name(), other.name(),
                            sharedResources.stream().map(value -> "resource:" + value).toList()));
                }
            }
        }
        return List.copyOf(conflicts);
    }

    private static List<String> concat(List<String> left, List<String> right) {
        List<String> all = new ArrayList<>(left);
        all.addAll(right);
        return all;
    }

    /** 更深路径（上游 {@code max((a,b), key=len(parts))}：parts 多者，并列取首个）。 */
    private static String deeper(String a, String b) {
        return partsCount(a) >= partsCount(b) ? a : b;
    }

    private static int partsCount(String scope) {
        return scope.isEmpty() ? 0 : scope.split("/").length;
    }

    /**
     * 并行安全断言（上游 {@code assert_parallel_safe} 逐句）：input_version 统一 →
     * 冲突拒绝（词面上游逐字）→ 结果 dict（{@code parallel/tasks/conflicts/
     * input_version}）。通过 ≠ 执行安全（undeclared-read 边界由调用方承载）。
     */
    public static Map<String, Object> assertParallelSafe(List<Subtask> tasks) {
        List<Subtask> items = List.copyOf(tasks);
        Set<String> versions = new LinkedHashSet<>();
        for (Subtask task : items) {
            String version = task.inputVersion().strip();
            if (!version.isEmpty()) {
                versions.add(version);
            }
        }
        if (versions.size() > 1) {
            throw new ScheduleViolation("并行子任务必须使用同一个固定输入版本");
        }
        List<Conflict> conflicts = conflictPairs(items);
        if (!conflicts.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Conflict conflict : conflicts) {
                parts.add(conflict.left() + "×" + conflict.right() + "→"
                        + String.join(",", conflict.shared()));
            }
            throw new ScheduleViolation("共享写集或读写依赖不得并行：" + String.join("; ", parts));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("parallel", true);
        result.put("tasks", items.stream().map(Subtask::name).toList());
        result.put("conflicts", List.of());
        result.put("input_version", versions.isEmpty() ? "" : versions.iterator().next());
        return result;
    }

    /** 声明违规（上游 {@code ValueError} 对应物；词面上游逐字）。 */
    public static final class ScheduleViolation extends RuntimeException {
        public ScheduleViolation(String message) {
            super(message);
        }
    }
}
