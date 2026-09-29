package workbench.spec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 六段式 Spec 结构解析器与解析半边（镜像 workbench/spec.py，L03 合同，移植注记 JD1）。
 *
 * <p>只回答一个问题：合同的六段结构是否完整。缺章、空章、重复、乱序、未知标题、
 * 未闭合代码围栏各给可指导修订的原因；不判断业务语义——结构通过 ≠ 业务正确，
 * 业务取舍由人检查（上游辅导资料 §5 分层纪律，讲义 C7/C10）。
 *
 * <p>接口与错误词面同冻结 Python 解析器逐字等价（检查顺序：未知→重复→缺章→乱序→
 * 空章，围栏未闭合在标题收集时先行）；{@code build_delivery_spec} 等需求生成机器
 * 属上游 L04 消费面，本讲不复刻（D5）。{@code load} 不设默认路径参数——上游根
 * FDE_SPEC.md 为 REQ 级交付主合同，本讲不建（D4），调用方一律显式传路径。
 */
public final class SpecParser {

    /** 六个必要章节（vendor 冻结顺序）。 */
    public static final List<String> REQUIRED_SECTIONS =
            List.of("来源", "目标", "非目标", "约束", "验收用例", "完成定义");

    /** 结构拒绝（镜像 ValueError；message = vendor 原词面，由适配器转 JSON 错误契约）。 */
    public static final class SpecParseException extends RuntimeException {
        public SpecParseException(String message) {
            super(message);
        }
    }

    /** 六字段不可变解析结果（镜像 ParsedSpec；asMap 键序与 Python as_dict 同形）。 */
    public record ParsedSpec(String source, String goal, String nonGoals,
                             String constraints, String acceptance, String done) {

        /** 六字段字典（Python as_dict：source/goal/non_goals/constraints/acceptance/done）。 */
        public LinkedHashMap<String, String> asMap() {
            LinkedHashMap<String, String> out = new LinkedHashMap<>();
            out.put("source", source);
            out.put("goal", goal);
            out.put("non_goals", nonGoals);
            out.put("constraints", constraints);
            out.put("acceptance", acceptance);
            out.put("done", done);
            return out;
        }
    }

    private static final Pattern FENCE_LINE =
            Pattern.compile("^[ ]{0,3}(`{3,}|~{3,})([^\\r\\n]*)");
    // 无 MULTILINE：标题行界由调用方按 \n 手工分行给出（Python re.MULTILINE 的 $ 只认 \n；
    // Java Pattern.MULTILINE 会把 \u0085/\u2028/\u2029 也当行界，宽于 Python——复查轮 S-1 对齐）
    private static final Pattern HEADING_LINE =
            Pattern.compile("^##[ \\t]+([^\\r\\n]+?)[ \\t]*\\r?$");

    private SpecParser() {}

    /** UTF-8 只读加载（Python load_spec 同形；非 UTF-8/缺失路径词面偏差见移植注记 JD6）。 */
    public static ParsedSpec load(Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    /** 六段结构解析：全部拒绝检查通过后返回六字段原文（章节正文 strip）。 */
    public static ParsedSpec parse(String text) {
        List<Heading> headings = contractHeadings(text);
        List<String> names = new ArrayList<>();
        for (Heading heading : headings) {
            names.add(text.substring(heading.titleStart(), heading.titleEnd()).strip());
        }

        List<String> unknown = firstOccurrenceOrder(names.stream()
                .filter(name -> !REQUIRED_SECTIONS.contains(name)).toList());
        if (!unknown.isEmpty()) {
            throw new SpecParseException("Spec 包含未知章节：" + String.join(", ", unknown));
        }
        List<String> duplicates = firstOccurrenceOrder(names.stream()
                .filter(name -> names.stream().filter(name::equals).count() > 1).toList());
        if (!duplicates.isEmpty()) {
            throw new SpecParseException("Spec 包含重复章节：" + String.join(", ", duplicates));
        }
        List<String> missing = REQUIRED_SECTIONS.stream()
                .filter(name -> !names.contains(name)).toList();
        if (!missing.isEmpty()) {
            throw new SpecParseException("Spec 缺少必要章节：" + String.join(", ", missing));
        }
        if (!names.equals(REQUIRED_SECTIONS)) {
            throw new SpecParseException("Spec 章节顺序错误；应为：" + String.join(" → ", REQUIRED_SECTIONS)
                    + "；实际为：" + String.join(" → ", names));
        }

        String[] contents = new String[REQUIRED_SECTIONS.size()];
        for (int index = 0; index < headings.size(); index++) {
            Heading heading = headings.get(index);
            int end = index + 1 < headings.size()
                    ? headings.get(index + 1).headingStart() : text.length();
            contents[index] = text.substring(heading.headingEnd(), end).strip();
        }
        List<String> empty = new ArrayList<>();
        for (int index = 0; index < REQUIRED_SECTIONS.size(); index++) {
            if (contents[index].isEmpty()) {
                empty.add(REQUIRED_SECTIONS.get(index));
            }
        }
        if (!empty.isEmpty()) {
            throw new SpecParseException("Spec 章节内容不能为空：" + String.join(", ", empty));
        }
        return new ParsedSpec(contents[0], contents[1], contents[2], contents[3], contents[4], contents[5]);
    }

    /** 标题匹配（offset 在掩码文本与原文间通用——掩码为逐字符替换，等长）。 */
    private record Heading(int headingStart, int headingEnd, int titleStart, int titleEnd) {}

    /**
     * 围栏屏蔽后的正式章节标题收集。代码围栏里的 ``## 目标`` 是正文不是章节；围栏未闭合
     * 必须报错而不是吞掉后续正式章节（检查点 0002 教学节）。
     */
    private static List<Heading> contractHeadings(String text) {
        StringBuilder masked = new StringBuilder(text.length());
        boolean inFence = false;
        char fenceChar = 0;
        int fenceLength = 0;
        for (String line : splitLinesKeepingEnds(text)) {
            Matcher fence = FENCE_LINE.matcher(line);
            boolean isFenceLine = fence.lookingAt();
            if (inFence) {
                if (isFenceLine && fence.group(1).charAt(0) == fenceChar
                        && fence.group(1).length() >= fenceLength
                        && fence.group(2).strip().isEmpty()) {
                    inFence = false;
                }
                masked.append(maskLine(line));
            } else if (isFenceLine && !(fence.group(1).charAt(0) == '`'
                    && fence.group(2).indexOf('`') >= 0)) {
                inFence = true;
                fenceChar = fence.group(1).charAt(0);
                fenceLength = fence.group(1).length();
                masked.append(maskLine(line));
            } else {
                masked.append(line);
            }
        }
        if (inFence) {
            throw new SpecParseException("Spec 代码围栏未闭合，请补齐示例的结束标记");
        }
        // 标题半边按 \n 分行（Python re.MULTILINE 行界语义）；围栏半边仍用 splitlines 全集
        // （splitLinesKeepingEnds），两个半边行界与 Python 逐字同形（复查轮 S-1）
        List<Heading> headings = new ArrayList<>();
        String maskText = masked.toString();
        int lineStart = 0;
        for (int index = 0; index < maskText.length(); index++) {
            if (maskText.charAt(index) != '\n') {
                continue;
            }
            collectHeadingIfPresent(maskText, lineStart, index + 1, headings);
            lineStart = index + 1;
        }
        if (lineStart < maskText.length()) {
            collectHeadingIfPresent(maskText, lineStart, maskText.length(), headings);
        }
        return headings;
    }

    /** 对一行（[from, to)，to 含结尾 \n 时由正则的 $ 在终止符前匹配）收集标题匹配。 */
    private static void collectHeadingIfPresent(String text, int from, int to,
                                                List<Heading> headings) {
        Matcher heading = HEADING_LINE.matcher(text.substring(from, to));
        if (heading.lookingAt()) {
            headings.add(new Heading(from + heading.start(), from + heading.end(),
                    from + heading.start(1), from + heading.end(1)));
        }
    }

    /** 围栏内行掩码：仅保留 \r\n 以维持行结构与 offset，其余字符替换为空格。 */
    private static String maskLine(String line) {
        StringBuilder out = new StringBuilder(line.length());
        for (int index = 0; index < line.length(); index++) {
            char c = line.charAt(index);
            out.append(c == '\r' || c == '\n' ? c : ' ');
        }
        return out.toString();
    }

    private static boolean isLineBreak(char c) {
        return c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C'
                || c == '\u001C' || c == '\u001D' || c == '\u001E'
                || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    /** Python splitlines(keepends=True) 同形：行终止符全集断行并保留在行尾。 */
    private static List<String> splitLinesKeepingEnds(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '\r') {
                index += index + 1 < text.length() && text.charAt(index + 1) == '\n' ? 2 : 1;
            } else if (isLineBreak(c)) {
                index++;
            } else {
                index++;
                continue;
            }
            lines.add(text.substring(start, index));
            start = index;
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    /** Python dict.fromkeys 语义：去重且保留首次出现序。 */
    private static List<String> firstOccurrenceOrder(List<String> items) {
        return new ArrayList<>(new LinkedHashSet<>(items));
    }
}
