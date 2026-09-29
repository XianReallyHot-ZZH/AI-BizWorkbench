package workbench.execution;

import java.util.ArrayList;
import java.util.List;

/**
 * shell 形单串 → argv（镜像 Python {@code shlex.split} 的 POSIX 规则子集，L04 讲义 JD1）。
 *
 * <p>规则面：空白切分；单引号内逐字取字（无转义）；双引号内反斜杠仅转义 ``\`` ``"`` ``````
 * ``$`` 与换行（其余保留原字）；引号外反斜杠转义下一字符。引号不闭合即解析失败
 * （Python ValueError 同位；错误词面由调用方固定给出，本类只承载失败事实）。
 * 服务于 ``--executor-command`` / ``--eval-command`` 的单串命令形（Python 复查轮
 * §3-15：nargs='+' 会把选项形 token 误当旗标，单串 + shlex 是冻结终态口径）。
 */
public final class Shlex {

    /** 解析失败（引号不闭合/悬空转义）：调用方转固定错误词面，异常文本不外泄。 */
    public static final class Unparseable extends Exception {
        public Unparseable(String message) {
            super(message);
        }
    }

    private Shlex() {}

    public static List<String> split(String line) throws Unparseable {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean hasCurrent = false;
        int index = 0;
        while (index < line.length()) {
            char c = line.charAt(index);
            if (isWhitespace(c)) {
                if (hasCurrent) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    hasCurrent = false;
                }
                index++;
            } else if (c == '\'') {
                hasCurrent = true;
                index++;
                int close = line.indexOf('\'', index);
                if (close < 0) {
                    throw new Unparseable("No closing quotation");
                }
                current.append(line, index, close);
                index = close + 1;
            } else if (c == '"') {
                hasCurrent = true;
                index++;
                boolean closed = false;
                while (index < line.length()) {
                    char d = line.charAt(index);
                    if (d == '"') {
                        closed = true;
                        index++;
                        break;
                    }
                    if (d == '\\' && index + 1 < line.length()
                            && ("\\\"`$\n".indexOf(line.charAt(index + 1)) >= 0)) {
                        current.append(line.charAt(index + 1));
                        index += 2;
                    } else {
                        current.append(d);
                        index++;
                    }
                }
                if (!closed) {
                    throw new Unparseable("No closing quotation");
                }
            } else if (c == '\\') {
                if (index + 1 >= line.length()) {
                    throw new Unparseable("No escaped character");
                }
                hasCurrent = true;
                current.append(line.charAt(index + 1));
                index += 2;
            } else {
                hasCurrent = true;
                current.append(c);
                index++;
            }
        }
        if (hasCurrent) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /** shlex 默认空白集：空格、\t、\r、\n。 */
    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }
}
