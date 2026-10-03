package workbench.environment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import workbench.bootstrap.Args;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L16 冷启动自检（讲义 C4，workbench_increment[0]「冷启动」安装面）：上游
 * {@code vendors/CodexFDE/workbench/environment_check.py}（55 行，只读）的对应物——
 * {@code scope:'installation'} **只查不修**：逐项 {@code {name, ok, detail}}，
 * 顶层 {@code ok = all(checks)}；默认不查客户环境（{@code product_checked:false}——
 * 客户面由冷启动实验整链承载，不走自检命令，spec Out of Scope）。
 *
 * <p>检查面六项 = 讲义 D3 推荐清单的合同化（上游 python 版本 / venv / 模块路径归属 /
 * web 件四面的本仓形态映射——Java 线无 venv 与 import 归属面，等价换成构建产物面）：
 * java（JDK ≥21，pom 口径）/ compiled_classes（编译产物在）/ dependency_classpath
 * （依赖清单落盘在）/ golden_resources（golden 六套基准在）/ web_panel_assets
 * （面板三件在）/ wb_wrapper（bin/wb 可执行）。
 *
 * <p>接缝（spec 具名，零新缝）：REGISTRY 注册缝（{@code environment-check}，
 * 上游 CLI 同名直承，无必填参数）。
 */
public final class EnvironmentCheck {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final int REQUIRED_JAVA_MAJOR = 21;

    private EnvironmentCheck() {}

    public static int execute(String[] argv) {
        try {
            new Args(argv, Set.of(), Set.of());
        } catch (Args.UsageException error) {
            System.err.println(error.getMessage());
            return 2;
        }
        try {
            System.out.println(MAPPER.writeValueAsString(checkEnvironment()));
            return 0;
        } catch (Exception error) {
            return 1;
        }
    }

    /** 只读安装检查（上游 check_environment 对应物；永不修改任何状态）。 */
    public static Map<String, Object> checkEnvironment() {
        Path root = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        List<Map<String, Object>> checks = new ArrayList<>();

        // java：JDK 版本面（上游 record('python', version>=(3,10)) 的本仓对应）
        int major = javaMajorVersion();
        checks.add(check("java", major >= REQUIRED_JAVA_MAJOR,
                "JDK " + System.getProperty("java.version") + "（要求 ≥" + REQUIRED_JAVA_MAJOR + "）"));

        // compiled_classes：编译产物面（本仓无 venv/import 归属面，等价构建产物面）
        Path classes = root.resolve("target/classes/workbench/cli/Main.class");
        checks.add(check("compiled_classes", Files.isRegularFile(classes), classes.toString()));

        // dependency_classpath：依赖清单落盘（运行时依赖白名单的落盘件）
        Path classpath = root.resolve("target/child-classpath.txt");
        boolean classpathOk = false;
        try {
            classpathOk = Files.isRegularFile(classpath)
                    && !Files.readString(classpath).strip().isEmpty();
        } catch (Exception ignored) {
            classpathOk = false;
        }
        checks.add(check("dependency_classpath", classpathOk, classpath.toString()));

        // golden_resources：golden 六套对照基准（l01–l06 manifest 各在）
        int manifests = 0;
        for (int lesson = 1; lesson <= 6; lesson++) {
            if (Files.isRegularFile(root.resolve(
                    "src/test/resources/golden/l%02d/manifest.json".formatted(lesson)))) {
                manifests++;
            }
        }
        checks.add(check("golden_resources", manifests == 6,
                "golden manifest %d/6".formatted(manifests)));

        // web_panel_assets：交付面板三件（L14，零依赖零 CDN）
        boolean webOk = true;
        StringBuilder webDetail = new StringBuilder();
        for (String name : List.of("index.html", "app.js", "styles.css")) {
            Path asset = root.resolve("workbench_web").resolve(name);
            boolean present = Files.isRegularFile(asset);
            webOk = webOk && present;
            webDetail.append(present ? name : "缺:" + name).append(' ');
        }
        checks.add(check("web_panel_assets", webOk, webDetail.toString().strip()));

        // wb_wrapper：CLI 包装脚本可执行
        Path wrapper = root.resolve("bin/wb");
        checks.add(check("wb_wrapper",
                Files.isRegularFile(wrapper) && Files.isExecutable(wrapper), wrapper.toString()));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", checks.stream().allMatch(item -> Boolean.TRUE.equals(item.get("ok"))));
        report.put("scope", "installation");
        report.put("product_checked", false);
        report.put("checks", checks);
        return report;
    }

    private static Map<String, Object> check(String name, boolean ok, String detail) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("ok", ok);
        item.put("detail", detail);
        return item;
    }

    /** 主版本号（8 以前是 1.x 形态；21+ 直接返回）。 */
    private static int javaMajorVersion() {
        try {
            String version = System.getProperty("java.version");
            String[] parts = version.split("[._]");
            return Integer.parseInt(parts[0].equals("1") ? parts[1] : parts[0]);
        } catch (Exception error) {
            return 0;
        }
    }
}
