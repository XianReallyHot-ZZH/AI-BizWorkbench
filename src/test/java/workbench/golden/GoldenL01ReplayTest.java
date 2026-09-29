package workbench.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
 * golden 字节对照（ADR-0006 验收门增量；方案 = L01 讲义附录 A3）。
 *
 * <p>按 {@code golden/l01/manifest.json} 重放 22 场景：同一会话序、同一 argv、同一输入
 * 内容（全部来自 manifest，测试不自带文本，杜绝漂移）。Java 输出经**独立**规范化器
 * （{@link Cli#normalize}，按 manifest 的 normalization 块实现）后与 golden 逐字节一致。
 * golden 由冻结 Python 工作台采出（evidence/L01-java.md §G），生成后永不手改。
 */
class GoldenL01ReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void cleanScratch() throws IOException {
        // 重放清场（与生成器同口径：一律重采，不做增量）
        deleteRecursively(Cli.repoRoot().resolve(".runtime/golden-l01/rt"));
        deleteRecursively(Cli.repoRoot().resolve(".runtime/golden-l01/rt-empty"));
        deleteRecursively(Cli.repoRoot().resolve(".runtime/golden-l01/inputs"));
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

    private static String goldenResource(String name) throws IOException {
        try (InputStream in = GoldenL01ReplayTest.class.getResourceAsStream("/golden/l01/" + name)) {
            assertThat(in).as("golden/l01/%s 必须在测试 classpath".formatted(name)).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void replayAllScenariosByteIdentically() throws Exception {
        JsonNode manifest;
        try (InputStream in = GoldenL01ReplayTest.class
                .getResourceAsStream("/golden/l01/manifest.json")) {
            assertThat(in).as("golden/l01/manifest.json 必须在测试 classpath").isNotNull();
            manifest = MAPPER.readTree(in);
        }

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
                    .isEqualTo(goldenResource(scenario.path("stdout_file").asText()));
            if (scenario.hasNonNull("assert_db_absent")) {
                Path db = Cli.repoRoot().resolve(scenario.get("assert_db_absent").asText());
                assertThat(db).as("%s 不得偷偷建库（WB-10）", id).doesNotExist();
            }
        }
    }
}
