package workbench.spec;

import org.junit.jupiter.api.Test;
import workbench.testsupport.Cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC_TEMPLATE 载体机检（移植注记 JD3；Python 载体退役后单载体口径，ADR-0006 尾款
 * 2026-09-30 执行）：`src/main/resources/templates/SPEC_TEMPLATE.md`（Java 面）即工作台
 * 模板唯一载体——退役前与 `workbench/templates/SPEC_TEMPLATE.md`（Python 冻结原件）的
 * 逐字等价由本测试锁定，原件随 Python 载体入 tag {@code python-carrier-final}（再生对照
 * 经 tag checkout），工作树不再持有。红点语义承袭：Java 面缺失即红（移植注记 A2 C5）。
 */
class SpecTemplateDualCarrierTest {

    @Test
    void javaFaceTemplateIsTheCarrierAfterPythonRetirement() throws Exception {
        Path javaFace = Cli.repoRoot().resolve("src").resolve("main").resolve("resources")
                .resolve("templates").resolve("SPEC_TEMPLATE.md");
        assertThat(javaFace).as("Java 面模板载体必须存在（JD3，Python 退役后唯一载体）").exists();
        assertThat(Files.readString(javaFace, StandardCharsets.UTF_8)).isNotEmpty();
        // 退役护栏：Python 原件路径不得在工作树复活（对照源 = tag python-carrier-final）
        assertThat(Cli.repoRoot().resolve("workbench").resolve("templates")).doesNotExist();
    }
}
