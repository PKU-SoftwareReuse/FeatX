package cn.edu.pku.lixutian.helper;

import java.io.*;
import java.nio.charset.Charset;
import java.util.Map;

public class RepoSummaryHelper {

    public static void runRepoSummary(Integer repoId) throws IOException {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        String defaultPythonExec = isWindows ? "python" : "python3";
        String pythonExec = getEnvOrDefault("REPOSUMMARY_PYTHON", defaultPythonExec);
        String repoSummaryDir = getEnvOrDefault("REPOSUMMARY_DIR", "./RepoSummary");

        ProcessBuilder processBuilder = new ProcessBuilder(
                pythonExec,
                "-m", "src.main",
                repoId.toString()
        );

        processBuilder.directory(new File(repoSummaryDir));
        applyProxy(processBuilder);

        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        Charset charset = isWindows ? Charset.forName("GBK") : Charset.forName("UTF-8");

        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[RepoSummary] " + line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();

        System.out.println("RepoSummary started (pid=" + process.pid()
                + ", python=" + pythonExec
                + ", cwd=" + repoSummaryDir + ")");
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static void applyProxy(ProcessBuilder processBuilder) {
        String proxyPort = System.getenv("GIT_PROXY_PORT");
        if (proxyPort == null || proxyPort.isBlank()) {
            return;
        }

        String proxyHost = System.getenv("GIT_PROXY_HOST");
        if (proxyHost == null || proxyHost.isBlank()) {
            proxyHost = "10.0.2.2";
        }

        String proxyUrl = "http://" + proxyHost.trim() + ":" + proxyPort.trim();
        Map<String, String> environment = processBuilder.environment();
        environment.put("http_proxy", proxyUrl);
        environment.put("https_proxy", proxyUrl);
        environment.put("HTTP_PROXY", proxyUrl);
        environment.put("HTTPS_PROXY", proxyUrl);
        environment.put("ALL_PROXY", proxyUrl);
    }
}
