package workbench.learning;

import workbench.bootstrap.Args;
import workbench.bootstrap.JsonOut;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 记忆系统七命令 CLI（L15，S01 冻结面 argparse 参数逐字直承；REGISTRY 注册缝
 * 第七批——与 bootstrap/spec/execution 同形经 {@code workbench.cli.Main} 入口）。
 *
 * <p>参数形态（S01 {@code _register_learn_*} 同名同义）：learn-create 增量
 * {@code --evolution-id} / {@code --feedback-id} 两参数（上游现版 evolution 连接缝，
 * spec D2）；可重复参数（--applies/--excludes/--keyword）经 Args 的 append 语义
 * （argparse action="append" 同形）按出现次序收集。
 * 错误契约：{@link LearningStore.LearningException} 词面（{@code code: message}）
 * 经 {@code JsonOut.fail} 直达 rc 1——词面即断言面（测试与 Checks 按词面断言）。
 */
public final class LearningCli {

    private static final Set<String> VALUE_FLAGS = Set.of("--runtime-dir", "--project-id",
            "--task-id", "--family", "--title", "--content", "--boundary", "--conflict-key",
            "--supersedes", "--actor", "--asset-id", "--decision", "--note", "--recall-id",
            "--plan-id", "--decisions", "--binding-id", "--phase", "--result",
            "--evidence-table", "--evidence-record-id", "--summary", "--budget", "--outcome",
            "--reviewer", "--evolution-id", "--feedback-id");

    private LearningCli() {}

    private record Prepared(LearningStore store, Map<String, String> flags) {}

    private static Prepared prepare(String[] argv, Set<String> repeating) {
        Args args = new Args(argv, VALUE_FLAGS, Set.of(), List.of(), repeating);
        LearningStore store = new LearningStore(
                Path.of(args.require("--runtime-dir")).resolve("workbench.db"));
        Map<String, String> flags = new LinkedHashMap<>();
        for (String flag : VALUE_FLAGS) {
            if (flag.equals("--runtime-dir")) {
                continue;
            }
            if (args.has(flag)) {
                flags.put(flag.substring(2).replace('-', '_'), args.optional(flag, ""));
            }
        }
        for (String flag : repeating) {
            if (!args.repeatingList(flag).isEmpty()) {
                flags.put(flag.substring(2), String.join("\n", args.repeatingList(flag)));
            }
        }
        return new Prepared(store, flags);
    }

    private static int emit(String key, java.util.function.Function<Prepared, Object> body,
            String[] argv, Set<String> repeating) {
        Prepared prepared = prepare(argv, repeating);
        try {
            JsonOut.emit(JsonOut.ordered("ok", true, key, body.apply(prepared)));
            return 0;
        } catch (LearningStore.LearningException | IllegalArgumentException
                | IllegalStateException error) {
            return JsonOut.fail(error.getMessage());
        }
    }

    // ---- workbench-learn-create：从已验收任务（或已接受反馈的 Evolution）提炼记忆候选 ----

    public static int create(String[] argv) {
        return emit("asset", prepared -> prepared.store().create(prepared.flags()), argv,
                Set.of("--applies", "--excludes"));
    }

    // ---- workbench-learn-govern：记忆条目具名治理决定（approve/publish/revoke） ----------

    public static int govern(String[] argv) {
        Prepared prepared = prepare(argv, Set.of());
        String decision = prepared.flags().get("decision");
        if (!"approve".equals(decision) && !"publish".equals(decision)
                && !"revoke".equals(decision)) {
            // S01 argparse choices 同形：非法决定 stderr + rc 2（Store 层另有防线）
            System.err.println("argument --decision: invalid choice: '" + decision
                    + "' (choose from 'approve', 'publish', 'revoke')");
            return 2;
        }
        return emit("asset", p -> p.store().govern(p.flags()), argv, Set.of());
    }

    // ---- workbench-learn-recall：按项目与关键词召回记忆（预算 Top-k + 冲突分组） ----------

    public static int recall(String[] argv) {
        return emit("recall", prepared -> prepared.store().recall(prepared.flags()), argv,
                Set.of("--keyword"));
    }

    // ---- workbench-learn-bind：逐项采用决定并封存快照（plan/task 双唯一） ------------------

    public static int bind(String[] argv) {
        return emit("binding", prepared -> prepared.store().bind(prepared.flags()), argv, Set.of());
    }

    // ---- workbench-learn-run：记录复验链相位证据（不收自报哈希） ---------------------------

    public static int run(String[] argv) {
        return emit("run", prepared -> prepared.store().run(prepared.flags()), argv, Set.of());
    }

    // ---- workbench-learn-finish：回写复验结果（review + outcome；passed 须具名验收） -------

    public static int finish(String[] argv) {
        return emit("finish", prepared -> prepared.store().finish(prepared.flags()), argv, Set.of());
    }

    // ---- workbench-learn-show：检查记忆资产/召回包/采用快照（读时重算） --------------------

    public static int show(String[] argv) {
        Args args = new Args(argv, VALUE_FLAGS, Set.of(), List.of());
        LearningStore store = new LearningStore(
                Path.of(args.require("--runtime-dir")).resolve("workbench.db"));
        try {
            List<String> targets = new java.util.ArrayList<>();
            if (args.has("--asset-id")) {
                targets.add("asset");
            }
            if (args.has("--recall-id")) {
                targets.add("recall");
            }
            if (args.has("--binding-id")) {
                targets.add("binding");
            }
            if (targets.size() != 1) {
                throw new LearningStore.LearningException("show_target_required",
                        "--asset-id / --recall-id / --binding-id 恰给一个");
            }
            if (args.has("--asset-id")) {
                JsonOut.emit(JsonOut.ordered("ok", true, "asset",
                        store.showAsset(args.optional("--asset-id", ""))));
            } else if (args.has("--recall-id")) {
                JsonOut.emit(JsonOut.ordered("ok", true, "recall",
                        store.showRecall(args.optional("--recall-id", ""))));
            } else {
                JsonOut.emit(JsonOut.ordered("ok", true, "binding",
                        store.showBinding(args.optional("--binding-id", ""))));
            }
            return 0;
        } catch (LearningStore.LearningException | IllegalArgumentException
                | IllegalStateException error) {
            return JsonOut.fail(error.getMessage());
        }
    }
}
