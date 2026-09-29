package workbench.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * L01-java 交付物（ADR-0006 附录 A2 C6）：以 Java 复刻 vendor {@code import_evidence.py}
 * 的语义——读取 evidence.py 产出的 meta.json，**重算 SHA-256 验封条**，再经子进程以
 * 与 Python 载体一致的参数形状（--runtime-dir/--task-id/--phase/--command-text/
 * --output-file/--returncode/--observed-at）调用本仓库 workbench-evidence-add。
 * 导入只追加记录，**从不执行**其记录的命令文本。vendor 工具本体只读不动。
 *
 * <p>用法：{@code java -cp … workbench.tools.ImportEvidence <meta.json>
 * [--runtime-dir <dir>] [--task-id <id>]}；默认值与 vendor 同形。
 */
public final class ImportEvidence {

    /** UTF-8 BOM（U+FEFF）：以 Java 转义写出，源码保持纯 ASCII，杜绝不可见字符。 */
    private static final String BOM = "﻿";

    private ImportEvidence() {}

    public static void main(String[] args) throws Exception {
        String metaPath = null;
        String runtimeDir = ".runtime/course/L01-workbench";
        String taskId = "CASE-WB-L01-001";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--runtime-dir" -> runtimeDir = args[++i];
                case "--task-id" -> taskId = args[++i];
                default -> {
                    if (metaPath != null) {
                        System.err.println("unrecognized argument: " + args[i]);
                        System.exit(2);
                    }
                    metaPath = args[i];
                }
            }
        }
        if (metaPath == null) {
            System.err.println("usage: ImportEvidence <meta.json> [--runtime-dir <dir>] [--task-id <id>]");
            System.exit(2);
        }

        JsonNode entry;
        try {
            String text = Files.readString(Path.of(metaPath), StandardCharsets.UTF_8);
            if (text.startsWith(BOM)) {  // vendor 用 utf-8-sig 读，容忍 BOM 同形
                text = text.substring(1);
            }
            entry = new ObjectMapper().readTree(text);
        } catch (IOException error) {
            System.err.println("meta.json 不可读取：" + error.getMessage());
            System.exit(1);
            return;
        }

        // 重算封条：output_file 的 SHA-256 必须与 meta.sha256 一致，否则拒绝导入
        Path output = Path.of(entry.get("output_file").asText());
        String actualSha256;
        try {
            byte[] bytes = Files.readAllBytes(output);
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                hex.append("%02x".formatted(b & 0xff));
            }
            actualSha256 = hex.toString();
        } catch (IOException | NoSuchAlgorithmException error) {
            System.err.println("输出不可读取或摘要不可用：" + error.getMessage());
            System.exit(1);
            return;
        }
        if (!actualSha256.equals(entry.get("sha256").asText())) {
            System.err.println("输出与采集时的摘要不一致，请核对原记录，不要修改元数据凑通过");
            System.exit(1);
        }

        List<String> argv = new ArrayList<>(List.of("java", "-cp",
                System.getProperty("java.class.path"), "workbench.cli.Main",
                "workbench-evidence-add",
                "--runtime-dir", runtimeDir, "--task-id", taskId,
                "--phase", entry.get("phase").asText(),
                "--command-text", entry.get("command").asText(),
                "--output-file", output.toString(),
                "--returncode", entry.get("returncode").asText(),
                "--observed-at", entry.get("observed_at").asText()));
        System.exit(new ProcessBuilder(argv).inheritIO().start().waitFor());
    }
}
