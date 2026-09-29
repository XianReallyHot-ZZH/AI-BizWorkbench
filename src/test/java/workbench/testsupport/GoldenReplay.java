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
import java.util.List;

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

    /** 重放一份 golden manifest（清场 → 物化输入 → 逐场景对照；l01/l02 同形）。 */
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

        // 规范化 spec 块与实现一致（防规范漂移：实现独立于该块，此处锁两者不分歧）
        JsonNode maskFields = manifest.path("normalization").path("mask_fields");
        assertThat(maskFields.path("created_at").asText()).isEqualTo("<TS>");
        assertThat(maskFields.path("recorded_at").asText()).isEqualTo("<TS>");
        assertThat(maskFields.path("workbench_id").asText()).isEqualTo("<WORKBENCH_ID>");

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
            Cli.Result result = Cli.run(argv.toArray(String[]::new));
            assertThat(result.exitCode()).as("%s 退出码", id)
                    .isEqualTo(scenario.path("exit_code").asInt());
            assertThat(Cli.normalize(result.stdout()))
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

    private static void deleteRecursively(Path path) throws IOException {
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
