package workbench.spec;

import workbench.bootstrap.Args;
import workbench.bootstrap.JsonOut;
import workbench.bootstrap.Ledger;
import workbench.cli.CommandRegistry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * spec 命令 CLI 适配器（镜像 workbench/spec.py 的 _cmd_spec/_register_spec/register_commands）。
 *
 * <p>经 REGISTRY 注册缝挂接（Main 静态块只加注册调用，缝位置不变，C9）：成功输出
 * ``{"ok": true, "flowerp_connected": …, "spec": {六字段}}`` + 退出码 0；结构错误在
 * handler 层接住（P5 追认移植：不走顶层异常边界，避免合同拒绝被误标"内部错误"），
 * 输出 ``{"ok": false, "flowerp_connected": …, "error": "<解析器原词面>"}`` + 退出码 1。
 * FLOWERP_CONNECTED 取 Ledger 单一来源（Python 复查轮 S1 教训移植为起始纪律）。
 * spec_path 为位置参数（argparse 同形，JD2）；输入文件 UTF-8 只读不修改（C8）。
 */
public final class SpecCommands {

    private SpecCommands() {}

    /** 接入注册表缝（与 BootstrapCommands.register 同形）。 */
    public static void register(CommandRegistry registry) {
        registry.register("spec", SpecCommands::spec);
    }

    private static int spec(String[] argv) {
        // 用法层错误（缺 spec_path/未知选项/多余位置参数）走 UsageException → 入口 stderr + rc 2（argparse 同形）
        Args args = new Args(argv, Set.of(), Set.of(), List.of("spec_path"));
        try {
            SpecParser.ParsedSpec parsed = SpecParser.load(Path.of(args.positional(0)));
            JsonOut.emit(JsonOut.ordered("ok", true, "flowerp_connected", Ledger.FLOWERP_CONNECTED,
                    "spec", parsed.asMap()));
            return 0;
        } catch (SpecParser.SpecParseException | IOException error) {
            JsonOut.emit(JsonOut.ordered("ok", false, "flowerp_connected", Ledger.FLOWERP_CONNECTED,
                    "error", String.valueOf(error.getMessage())));
            return 1;
        }
    }
}
