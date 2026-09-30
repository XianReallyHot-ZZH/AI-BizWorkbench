package workbench.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 候选工作区校验与改动采集（镜像 execution.py 的 _workspace_error/_collect_changes，L04 讲义 JD1）。
 *
 * <p>校验拒于进程前：目录存在 → .git 在场 → 有起点提交（能力信封"候选 + 起点版本"，
 * 复查轮 S-c4）。采集以候选 git 状态实测为准：``status --porcelain -z -uall`` 逐文件
 * 列改动（新目录不折叠——复查轮 S-c8）；``add -N .`` 意图添加仅写索引标记（不改文件
 * 内容，候选是 scratch 副本）让未跟踪文件进入 diff；前后摘要一律对原始字节
 * （复查轮 S6b，decode 同形篡改不可逃逸）。词面中的路径为传入形。
 */
public final class WorkspaceInspector {

    public record Change(String path, String change, String beforeSha256, String afterSha256) {}

    public record Changes(List<String> changedFiles, List<Map<String, Object>> manifest,
                          List<String> outOfScopeFiles, String diffText) {}

    private WorkspaceInspector() {}

    /** 候选不可用时的错误词面；null = 可用。 */
    public static String workspaceError(Path workspace) {
        if (!Files.isDirectory(workspace)) {
            return "workspace_invalid: 工作目录不存在：" + workspace;
        }
        if (!Files.exists(workspace.resolve(".git"))) {
            return "workspace_invalid: 工作目录不是 git 候选（缺 .git）：" + workspace;
        }
        if (git(workspace, "rev-parse", "--verify", "HEAD").exitCode() != 0) {
            return "workspace_invalid: 候选没有起点提交（缺 HEAD）：" + workspace;
        }
        return null;
    }

    public static Changes collectChanges(Path workspace, List<String> scope) throws IOException {
        GitResult status = git(workspace, "status", "--porcelain", "-z", "-uall");
        String raw = new String(status.stdout, StandardCharsets.UTF_8);
        List<String> entries = new ArrayList<>();
        for (String entry : raw.split("\0", -1)) {
            if (!entry.isEmpty()) {
                entries.add(entry);
            }
        }
        TreeSet<String> changed = new TreeSet<>();
        int index = 0;
        while (index < entries.size()) {
            String entry = entries.get(index);
            changed.add(normalizePath(entry.substring(3)));
            if (index + 1 < entries.size() && isRename(entry)) {
                changed.add(normalizePath(entries.get(index + 1))); // 重命名的原路径也算改动
                index++;
            }
            index++;
        }
        git(workspace, "add", "-N", ".");
        GitResult diff = git(workspace, "diff", "HEAD");
        String diffText = new String(diff.stdout, StandardCharsets.UTF_8);
        List<Map<String, Object>> manifest = new ArrayList<>();
        List<String> outOfScope = new ArrayList<>();
        for (String path : changed) {
            GitResult head = git(workspace, "show", "HEAD:" + path);
            String before = head.exitCode() == 0 ? sha256Bytes(head.stdout) : null;
            Path file = workspace.resolve(path);
            String after = Files.isRegularFile(file) ? sha256Bytes(Files.readAllBytes(file)) : null;
            String change = before == null ? "added" : (after == null ? "deleted" : "modified");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", path);
            row.put("change", change);
            row.put("before_sha256", before);
            row.put("after_sha256", after);
            manifest.add(row);
            if (!WriteScope.inScope(path, scope)) {
                outOfScope.add(path);
            }
        }
        return new Changes(new ArrayList<>(changed), manifest, outOfScope, diffText);
    }

    /** porcelain 字段：XY 后随一个空格；R/C 表示 XY 之一带重命名标记。 */
    private static boolean isRename(String entry) {
        return "RC".indexOf(entry.charAt(0)) >= 0 || "RC".indexOf(entry.charAt(1)) >= 0;
    }

    private static String normalizePath(String raw) {
        return raw.replace('\\', '/');
    }

    // ---- git 子进程（镜像 _git；输出按字节留证）---------------------------------

    public record GitResult(int exitCode, byte[] stdout, byte[] stderr) {}

    static GitResult git(Path workspace, String... argv) {
        try {
            List<String> command = new ArrayList<>(List.of("git", "-C", workspace.toString()));
            command.addAll(List.of(argv));
            Process process = new ProcessBuilder(command).start();
            byte[] stdout = process.getInputStream().readAllBytes();
            byte[] stderr = process.getErrorStream().readAllBytes();
            int exit = process.waitFor();
            return new GitResult(exit, stdout, stderr);
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new GitResult(-1, new byte[0], new byte[0]);
        }
    }

    /** 前后值摘要一律对原始字节（复查轮 S6b）。 */
    static String sha256Bytes(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder out = new StringBuilder();
            for (byte b : digest) {
                out.append("%02x".formatted(b & 0xff));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
