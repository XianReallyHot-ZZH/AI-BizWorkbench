package workbench.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import workbench.testsupport.Cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * golden 重放共享支撑（ADR-0006 验收门；l01/l02 两套 golden 同一重放语义）。
 *
 * <p>数据全来自 manifest（argv/输入内容/预期退出码/tamper/assert_db_absent），测试不自带
 * 文本，杜绝漂移。Java 输出经 {@link Cli#normalize} 独立规范化后与 golden 逐字节一致。
 * golden 由冻结 Python 工作台采出（evidence/LNN-java.md §G），生成后永不手改。
 */
public final class GoldenReplay {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GoldenReplay() {}

    /** 重放一份 golden manifest（清场 → 物化输入 → setup → 逐场景对照；掩码按 manifest 声明驱动）。
     *
     * <p>scratchRelativeDirs 传空清单 = 调用方自行清场（l05 先例：盲区树由重放测试在 replay()
     * 之前物化，不能被首步清场抹掉）。
     */
    public static void replay(String manifestResource, List<String> scratchRelativeDirs) throws Exception {
        // 重放清场（与生成器同口径：一律重采，不做增量）
        for (String dir : scratchRelativeDirs) {
            deleteRecursively(Cli.repoRoot().resolve(dir));
        }

        JsonNode manifest;
        try (InputStream in = GoldenReplay.class.getResourceAsStream(manifestResource)) {
            assertThat(in).as("%s 必须在测试 classpath".formatted(manifestResource)).isNotNull();
            manifest = MAPPER.readTree(in);
        }
        String resourceDir = manifestResource.substring(0, manifestResource.lastIndexOf('/') + 1);

        // 规范化 spec 块与实现一致（防规范漂移：实现独立于该块，此处锁两者不分歧）。
        // 掩码集按 manifest 声明数据驱动（l04 起各套可扩展机器生成字段；l01–l03 三套
        // 声明不变 → 掩码行为逐字节不变）；每个声明值必须是 <占位符> 形状。
        JsonNode maskFieldsNode = manifest.path("normalization").path("mask_fields");
        assertThat(maskFieldsNode.isObject()).as("normalization.mask_fields 必须在 manifest 在场").isTrue();
        Map<String, String> maskFields = new LinkedHashMap<>();
        maskFieldsNode.properties().forEach(entry -> {
            String placeholder = entry.getValue().asText();
            assertThat(placeholder)
                    .as("掩码字段 %s 的占位符", entry.getKey())
                    .matches("<[A-Z_]+>");
            maskFields.put(entry.getKey(), placeholder);
        });

        // 输入固定件：内容来自 manifest，测试不自带文本
        Path workRoot = Cli.repoRoot().resolve(".runtime");
        JsonNode inputs = manifest.path("inputs");
        inputs.properties().forEach(entry -> {
            try {
                Path target = workRoot.resolve(entry.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, entry.getValue().asText(), StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        });

        // setup 块（l04 起候选仓等非文本夹具）：有序步骤，write_file 物化固定内容件，
        // run 执行 git 等 materialize 命令（与 Python 生成器按同一 setup 规格执行）
        for (JsonNode step : manifest.path("setup")) {
            if (step.hasNonNull("write_file")) {
                Path target = workRoot.resolve(step.get("write_file").asText());
                Files.createDirectories(target.getParent());
                Files.writeString(target, step.get("content").asText(), StandardCharsets.UTF_8);
            } else if (step.hasNonNull("run")) {
                List<String> argv = new ArrayList<>();
                step.get("run").forEach(item -> argv.add(item.asText()));
                Process process = new ProcessBuilder(argv).directory(Cli.repoRoot().toFile())
                        .redirectErrorStream(false).start();
                int exit = process.waitFor();
                assertThat(exit).as("setup 步骤退出码：%s", argv).isZero();
            } else {
                throw new IllegalStateException("setup 步骤缺 write_file/run：" + step);
            }
        }

        for (JsonNode scenario : manifest.path("scenarios")) {
            String id = scenario.path("id").asText();
            if (scenario.hasNonNull("tamper_before")) {
                JsonNode tamper = scenario.get("tamper_before");
                try (var conn = DriverManager.getConnection(
                                "jdbc:sqlite:" + Cli.repoRoot().resolve(tamper.get("db").asText()));
                     var st = conn.createStatement()) {
                    st.executeUpdate(tamper.get("sql").asText());
                }
            }
            List<String> argv = new ArrayList<>();
            scenario.path("argv").forEach(item -> argv.add(item.asText()));
            // l05 起工具类场景可声明 main（默认 CLI 入口；l01–l04 四套 manifest 无该字段 → 行为不变）
            String mainClass = scenario.hasNonNull("main")
                    ? scenario.get("main").asText() : "workbench.cli.Main";
            // l06 起场景可声明 env（Checks 等工具读环境变量是冻结 Python 语义，argv 面不含
            // --target）；l01–l05 五套 manifest 无该字段 → 子进程环境不变（行为零变化证据见
            // evidence/L06-java.md §G）
            Cli.Result result;
            if (scenario.hasNonNull("env")) {
                Map<String, String> env = new LinkedHashMap<>();
                scenario.get("env").properties().forEach(
                        entry -> env.put(entry.getKey(), entry.getValue().asText()));
                result = Cli.runMainWithEnv(mainClass, env, argv.toArray(String[]::new));
            } else {
                result = Cli.runMain(mainClass, argv.toArray(String[]::new));
            }
            assertThat(result.exitCode()).as("%s 退出码", id)
                    .isEqualTo(scenario.path("exit_code").asInt());
            assertThat(Cli.normalize(result.stdout(), maskFields))
                    .as("%s 规范化输出逐字节对照", id)
                    .isEqualTo(goldenContent(resourceDir + scenario.path("stdout_file").asText()));
            if (scenario.hasNonNull("assert_db_absent")) {
                Path db = Cli.repoRoot().resolve(scenario.get("assert_db_absent").asText());
                assertThat(db).as("%s 不得偷偷建库（WB-10）", id).doesNotExist();
            }
        }
    }

    private static String goldenContent(String name) throws IOException {
        // name 已含前导 "/"（由 manifestResource 目录段拼出），不再补斜杠——双斜杠在 classpath 查不到
        try (InputStream in = GoldenReplay.class.getResourceAsStream(name)) {
            assertThat(in).as("%s 必须在测试 classpath".formatted(name)).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** public 可见（l05 重放测试在 workbench.golden 跨包自行清场复用；原 private 语义不变。
     * 前置跟进修正：首版放宽为包内不足——跨包不可达，预检编译失败现场见 §G J4）。 */
    public static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            });
        }
    }
}
