package cn.edu.pku.lixutian.helper;

import java.io.*;
import java.nio.charset.Charset;

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
}
