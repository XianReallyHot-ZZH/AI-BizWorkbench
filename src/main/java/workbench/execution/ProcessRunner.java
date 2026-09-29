package workbench.execution;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 执行器与 Eval 共用的进程运行器（镜像 execution.py 的 _run_process，L04 讲义 JD1）。
 *
 * <p>超时：预算到点杀进程，保留已产出部分输出（timed_out 独立成事实，不与业务失败
 * 混淆——复查轮 S-c6）；启动失败（二进制不存在等）必须落账而非裸崩——B 门实跑教训：
 * 崩在插入前会把执行器已发生的工作记录尽失（上游 V0 同形 launch error preserved，
 * 复查轮 §3-16）。输出一律按 UTF-8 解码留证（不可解码字节以替换符呈现，
 * Python errors="replace" 同形）。errno 词面系语言绑定（Python FileNotFoundError vs
 * Java 异常类名），按移植注记 JD6 如实记录偏差、不伪装同形。
 */
public final class ProcessRunner {

    /** returncode 为 null 表示超时或启动失败（与 Python _ProcessOutcome 同形）。 */
    public record Outcome(Integer returncode, boolean timedOut, String stdoutText, String stderrText) {}

    private ProcessRunner() {}

    public static Outcome run(List<String> command, Path cwd, int timeoutSeconds, String stdinText) {
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
            process = builder.start();
        } catch (IOException error) {
            return new Outcome(null, false, "",
                    "启动失败：" + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        StreamCollector stdout = new StreamCollector(process.getInputStream());
        StreamCollector stderr = new StreamCollector(process.getErrorStream());
        Thread outThread = new Thread(stdout, "process-stdout");
        Thread errThread = new Thread(stderr, "process-stderr");
        outThread.start();
        errThread.start();
        if (stdinText != null) {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(stdinText.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            } catch (IOException ignored) {
                // 执行器先退导致管道断开：stdin 送达失败不改变结局判定
            }
        }
        boolean timedOut = false;
        Integer returncode = null;
        try {
            if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                returncode = process.exitValue();
            } else {
                timedOut = true;
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        try {
            outThread.join(TimeUnit.SECONDS.toMillis(5));
            errThread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return new Outcome(returncode, timedOut, stdout.text(), stderr.text());
    }

    /** 收集一路输出到缓冲（部分输出在进程被杀后仍随管道收尾可读）。 */
    private static final class StreamCollector implements Runnable {
        private final InputStream input;
        private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

        private StreamCollector(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try {
                int read;
                while ((read = input.read(chunk)) >= 0) {
                    buffer.write(chunk, 0, read);
                }
            } catch (IOException ignored) {
                // 进程被杀时管道中断：以已收集字节为准
            }
        }

        private String text() {
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
