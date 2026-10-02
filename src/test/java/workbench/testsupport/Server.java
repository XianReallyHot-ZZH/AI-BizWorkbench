package workbench.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 常驻服务子进程支撑（l13 起）：起 {@code delivery-serve}（经 REGISTRY 真实入口，
 * 不绕 CLI 缝）、读 stdout 监听行拿实际端口（{@code --port 0} 随机分配）、
 * HttpClient 便捷请求面、AutoCloseable 随测试销毁。
 *
 * <p>与 {@link Cli} 的分工：Cli.run 须 waitFor 子进程结束，不适合常驻服务
 * （delivery-serve 阻塞直到被销毁）；本类保活进程 + 部分读 stdout 首行。
 * 红面形态（讲义 §3 步骤 2）：命令未注册 → 子进程 rc 2 退出（invalid choice）
 * → {@link #start} 抛 IllegalStateException，用例红。
 */
public final class Server implements AutoCloseable {

    /** 服务启动行读取上限（秒）：编译冷启动 + 建库的余量。 */
    private static final int STARTUP_TIMEOUT_SECONDS = 60;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Process process;
    private final int port;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private Server(Process process, int port) {
        this.process = process;
        this.port = port;
    }

    /**
     * 起服务子进程：{@code workbench.cli.Main delivery-serve --runtime-dir <dir> --port 0 …}。
     * runtimeDir 由调用方给临时目录；extraArgs 透传（--host/--suite-command 等）。
     */
    public static Server start(Path runtimeDir, String... extraArgs) {
        List<String> argv = new ArrayList<>(List.of("java",
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-cp", Cli.childClasspathForSubprocess(), "workbench.cli.Main",
                "delivery-serve", "--runtime-dir", runtimeDir.toString(), "--port", "0"));
        argv.addAll(List.of(extraArgs));
        try {
            Process process = new ProcessBuilder(argv)
                    .directory(Cli.repoRoot().toFile()).start();
            int port = awaitStartup(process);
            return new Server(process, port);
        } catch (IOException error) {
            throw new UncheckedIOException("delivery-serve 子进程启动失败", error);
        }
    }

    /** 读 stdout 首行 JSON（{"ok":true,"port":N,…}）；失败/超时/非 JSON 都抛（红面承载）。 */
    private static int awaitStartup(Process process) {
        long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_SECONDS * 1000L;
        StringBuilder stdout = new StringBuilder();
        try {
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive()) {
                    throw new IllegalStateException("delivery-serve 子进程提前退出 rc="
                            + process.exitValue() + " stderr=" + readAll(process));
                }
                int available = process.getInputStream().available();
                if (available > 0) {
                    byte[] chunk = process.getInputStream().readNBytes(available);
                    stdout.append(new String(chunk, StandardCharsets.UTF_8));
                    int newline = stdout.indexOf("\n");
                    if (newline >= 0) {
                        JsonNode line = MAPPER.readTree(stdout.substring(0, newline));
                        if (!line.path("ok").asBoolean(false)) {
                            throw new IllegalStateException("delivery-serve 启动行报错：" + line);
                        }
                        return line.path("port").asInt(-1);
                    }
                } else {
                    Thread.sleep(50);
                }
            }
            throw new IllegalStateException("delivery-serve 启动超时（" + STARTUP_TIMEOUT_SECONDS
                    + "s 无监听行）；stdout=" + stdout + " stderr=" + readAll(process));
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 delivery-serve 启动被中断", error);
        } catch (IOException error) {
            process.destroyForcibly();
            throw new UncheckedIOException("读取 delivery-serve 启动行失败", error);
        }
    }

    private static String readAll(Process process) {
        try {
            return new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            return "<stderr 读取失败>";
        }
    }

    public int port() {
        return port;
    }

    /** GET 便捷面：返回 (statusCode, body)。 */
    public Response get(String path) {
        return exchange(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path)).GET().build(), null);
    }

    /** POST 便捷面：JSON body + 可选 Idempotency-Key 头。 */
    public Response post(String path, Map<String, Object> body, String idempotencyKey) {
        String payload;
        try {
            payload = MAPPER.writeValueAsString(body);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return exchange(request.build(), payload);
    }

    private Response exchange(HttpRequest request, String requestBody) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), requestBody);
        } catch (IOException error) {
            throw new UncheckedIOException("delivery-serve 请求失败：" + request.uri(), error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("delivery-serve 请求被中断", error);
        }
    }

    public JsonNode json(Response response) {
        try {
            return MAPPER.readTree(response.body());
        } catch (IOException error) {
            throw new IllegalStateException("响应体不是 JSON：" + response.body(), error);
        }
    }

    @Override
    public void close() {
        process.destroy();
        try {
            process.waitFor();
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /** HTTP 响应记录：状态码 + 响应体 + 请求体（失败诊断用）。 */
    public record Response(int statusCode, String body, String requestBody) {}
}
