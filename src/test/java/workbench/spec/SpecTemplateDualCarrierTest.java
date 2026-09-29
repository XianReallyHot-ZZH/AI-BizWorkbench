package workbench.spec;

import org.junit.jupiter.api.Test;
import workbench.testsupport.Cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC_TEMPLATE 双载体机检（移植注记 JD3，铁律 4 CourseContracts 先例）：
 * `workbench/templates/SPEC_TEMPLATE.md`（Python 冻结原件）与
 * `src/main/resources/templates/SPEC_TEMPLATE.md`（Java 面拷贝）字符串内容逐字等价。
 *
 * <p>红点语义：Java 面拷贝是 L03 候选的交付物（commit 2），缺失即红（移植注记 A2 C5）；
 * 原件只读，零触碰。Python 退役（L06 重走完成后）工作台经 Java 面保有模板。
 */
class SpecTemplateDualCarrierTest {

    @Test
    void javaFaceTemplateIsVerbatimEqualToFrozenOriginal() throws Exception {
        Path original = Cli.repoRoot().resolve("workbench").resolve("templates")
                .resolve("SPEC_TEMPLATE.md");
        Path javaFace = Cli.repoRoot().resolve("src").resolve("main").resolve("resources")
                .resolve("templates").resolve("SPEC_TEMPLATE.md");
        assertThat(javaFace).as("Java 面模板拷贝必须存在（JD3 双载体，commit 2 交付物）").exists();
        assertThat(Files.readString(javaFace, StandardCharsets.UTF_8))
                .as("双载体逐字等价（铁律 4 CourseContracts 先例）")
                .isEqualTo(Files.readString(original, StandardCharsets.UTF_8));
    }
}
