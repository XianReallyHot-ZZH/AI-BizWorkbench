package workbench.coursecontracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * 冻结合同 fixture 完整性测试（L01 合同 C1；镜像 tests/test_course_contracts.py，
 * 另增全字段投影比对）。
 *
 * <p>数据基线 CodexFDE@58f4612（ADR-0001/0003）。本测试失败意味着 fixture 与基线漂移，
 * 处理方式是走 ADR-0003 检查点显式采纳，而不是"修好"功能。
 */
class CourseContractFixtureTest {

    /** Python 载体的机械 JSON 投影（tools/export_course_contracts_json.py 生成，不手改）。 */
    private static JsonNode projection() {
        try (InputStream in = CourseContractFixtureTest.class
                .getResourceAsStream("/coursecontracts/frozen-python-projection.json")) {
            if (in == null) {
                throw new IllegalStateException(
                        "缺少 frozen-python-projection.json：由 tools/export_course_contracts_json.py 生成");
            }
            return new ObjectMapper().readTree(in);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    @Test
    void lessonsCoverL01ToL16() {
        assertThat(CourseContracts.LESSONS).hasSize(16);
        for (int i = 0; i < 16; i++) {
            assertThat(CourseContracts.LESSONS.get(i).number()).isEqualTo(i + 1);
        }
    }

    @Test
    void l01ContractIsFrozenBaseline() {
        var lesson = CourseContracts.lessonContract(1);
        assertThat(lesson.title()).isEqualTo("以终为始：一次可验证的 AI 交付怎样完成？");
        assertThat(lesson.phase()).isEqualTo("bootstrap");
        assertThat(lesson.writeScope()).containsExactly("workbench/", "tests/", "docs/courses/L01/");
        assertThat(lesson.acceptance()).containsExactly(
                "关键要求能回指原始痛点、Codex 建议和学生决定。",
                "测试能回指验收项，且保留执行前红灯、范围内 Diff 和执行后绿灯。",
                "学生能使用自己开发的命令完成第一次自举记录。",
                "本讲不接入或开发 FlowERP。");
        assertThat(lesson.evalCases()).containsExactly("bootstrap_evidence_is_honest");
        assertThat(lesson.prerequisites()).isEmpty();
        assertThat(lesson.baselineRef()).isEqualTo("course/l01-start");
        assertThat(lesson.erpIncrement()).contains("尚未接入");
    }

    @Test
    void l01StoryFieldsPresent() {
        var story = CourseContracts.LESSON_STORY.get(1);
        assertThat(story.codexRole()).isNotBlank();
        assertThat(story.fdeLoop()).isNotBlank();
        assertThat(story.causalLink()).isNotBlank();
    }

    @Test
    void outOfRangeLessonRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CourseContracts.lessonContract(0))
                .withMessage("课次必须在 1 到 16 之间");
        assertThatIllegalArgumentException().isThrownBy(() -> CourseContracts.lessonContract(17));
    }

    /**
     * 全字段逐字等价机检（铁律 4 ADR-0006 口径）：Java 常量对 Python fixture 的机械
     * JSON 投影逐字段比对，覆盖全部 16 讲的每个字符串——任何转录滑手（错字、漏项、
     * 串位）都在此暴露，而不是靠人眼。
     */
    @Test
    void javaConstantsMatchFrozenPythonProjectionFieldByField() {
        JsonNode projection = projection();
        assertThat(CourseContracts.COURSE_BUILD_THESIS)
                .isEqualTo(projection.get("course_build_thesis").asText());
        assertThat(CourseContracts.COURSE_WORKSPACE_BOUNDARY)
                .isEqualTo(projection.get("course_workspace_boundary").asText());
        assertThat(projection.get("lessons")).hasSize(CourseContracts.LESSONS.size());

        List<String> problems = new ArrayList<>();
        for (int i = 0; i < CourseContracts.LESSONS.size(); i++) {
            var java = CourseContracts.LESSONS.get(i);
            JsonNode py = projection.get("lessons").get(i);
            check(problems, i, "number", String.valueOf(java.number()), py.get("number").asText());
            check(problems, i, "title", java.title(), py.get("title").asText());
            check(problems, i, "phase", java.phase(), py.get("phase").asText());
            check(problems, i, "workbench_increment", java.workbenchIncrement(),
                    py.get("workbench_increment").asText());
            check(problems, i, "erp_increment", java.erpIncrement(), py.get("erp_increment").asText());
            check(problems, i, "request", java.request(), py.get("request").asText());
            checkList(problems, i, "business_refs", java.businessRefs(), py.get("business_refs"));
            checkList(problems, i, "write_scope", java.writeScope(), py.get("write_scope"));
            checkList(problems, i, "acceptance", java.acceptance(), py.get("acceptance"));
            checkList(problems, i, "eval_cases", java.evalCases(), py.get("eval_cases"));
            checkList(problems, i, "prerequisites",
                    java.prerequisites().stream().map(String::valueOf).toList(), py.get("prerequisites"));
            check(problems, i, "baseline_ref", java.baselineRef(), py.get("baseline_ref").asText());
            check(problems, i, "live_request", String.valueOf(java.liveRequest()),
                    py.get("live_request").asText());
            check(problems, i, "dynamic_eval_required", String.valueOf(java.dynamicEvalRequired()),
                    py.get("dynamic_eval_required").asText());
            check(problems, i, "requirement_id", java.requirementId(), py.get("requirement_id").asText());
            check(problems, i, "construction_stage", CourseContracts.constructionStage(java.number()),
                    py.get("construction_stage").asText());
            check(problems, i, "write_scope_base",
                    java.number() >= 4 ? "isolated_course_candidate" : "learner_workbench",
                    py.get("write_scope_base").asText());

            JsonNode story = projection.get("lesson_story").get(String.valueOf(java.number()));
            var javaStory = CourseContracts.LESSON_STORY.get(java.number());
            check(problems, i, "codex_role", javaStory.codexRole(), story.get("codex_role").asText());
            check(problems, i, "fde_loop", javaStory.fdeLoop(), story.get("fde_loop").asText());
            check(problems, i, "causal_link", javaStory.causalLink(), story.get("causal_link").asText());
        }
        assertThat(problems).as("逐字等价偏差清单").isEmpty();
    }

    private static void check(List<String> problems, int lesson, String field,
                              String actual, String expected) {
        if (!actual.equals(expected)) {
            problems.add("L%d.%s：Java=%s != Python投影=%s".formatted(lesson + 1, field, actual, expected));
        }
    }

    private static void checkList(List<String> problems, int lesson, String field,
                                  List<String> actual, JsonNode expected) {
        List<String> expectedList = new ArrayList<>();
        expected.forEach(item -> expectedList.add(item.asText()));
        if (!actual.equals(expectedList)) {
            problems.add("L%d.%s：Java=%s != Python投影=%s".formatted(lesson + 1, field, actual, expectedList));
        }
    }
}
