package workbench.delivery;

import workbench.bootstrap.Args;
import workbench.bootstrap.JsonOut;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 结构化反馈三子命令 CLI（L15，上游 {@code workbench/feedback.py} main 同形——
 * add/review/summary；spec 偏差 +1 件落证据账：合同验收 1「原始反馈先审核」的
 * 可观测面与治理链造数需要 CLI 入口，L13 已建 Feedback 类零改动复用）。
 *
 * <p>形态适配：上游 {@code --db} → {@code --runtime-dir}（与 evolution / learn
 * 命令族一致）；输出 {@code {"ok":true,"feedback":…}} / {@code {"ok":true,"summary":…}}；
 * 错误词面直达 {@code JsonOut.fail} rc 1（上游 ValueError 词面同形——L13 Feedback
 * 类已按上游同形实现，本件只做参数壳与输出包装）。
 */
public final class FeedbackCli {

    private FeedbackCli() {}

    public static int execute(String[] argv) {
        if (argv.length == 0) {
            return usage("feedback <add|review|summary> …");
        }
        String command = argv[0];
        Set<String> flags = Set.of("--runtime-dir", "--task", "--source", "--conclusion",
                "--next", "--id", "--reviewer", "--decision", "--note");
        String[] rest = java.util.Arrays.copyOfRange(argv, 1, argv.length);
        Args args = new Args(rest, flags, Set.of(), List.of());
        if (!args.has("--runtime-dir")) {
            return usage("--runtime-dir 必填");
        }
        Feedback feedback = new Feedback(
                Path.of(args.require("--runtime-dir")).resolve("workbench.db"));
        try {
            switch (command) {
                case "add" -> JsonOut.emit(JsonOut.ordered("ok", true, "feedback",
                        feedback.addFeedback(args.require("--task"), args.require("--source"),
                                args.require("--conclusion"), args.require("--next"),
                                null, "")));
                case "review" -> JsonOut.emit(JsonOut.ordered("ok", true, "feedback",
                        feedback.reviewFeedback(args.require("--id"), args.require("--reviewer"),
                                args.require("--decision"), args.optional("--note", ""))));
                case "summary" -> JsonOut.emit(JsonOut.ordered("ok", true, "summary",
                        feedback.summary()));
                default -> {
                    return usage("未知子命令：" + command);
                }
            }
            return 0;
        } catch (Feedback.FeedbackNotFound error) {
            return JsonOut.fail("反馈不存在：" + error.getMessage());
        } catch (IllegalArgumentException | IllegalStateException error) {
            return JsonOut.fail(error.getMessage());
        }
    }

    private static int usage(String message) {
        JsonOut.fail(message);
        return 2;
    }
}
