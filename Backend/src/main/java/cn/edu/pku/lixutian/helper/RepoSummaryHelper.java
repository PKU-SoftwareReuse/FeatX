package cn.edu.pku.lixutian.helper;

import java.io.*;
import java.nio.charset.Charset;

public class RepoSummaryHelper {

    public static void runRepoSummary(Integer repoId) throws IOException {
        // 1. 系统判断
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        // 2. 你的 Miniconda / Anaconda 根路径（自行修改）
        String condaRoot = isWindows
                ? "C:\\Users\\lixutian\\anaconda3"        // 修改这里
                : "/home/lixutian/.conda/";            // 修改这里

        String envName = "RepoSummary";

        // 3. 拼出 conda 环境中的 python
        String pythonExec = isWindows
                ? condaRoot + "\\envs\\" + envName + "\\python.exe"
                : condaRoot + "/envs/" + envName + "/bin/python";

        // 4. 构建命令
        ProcessBuilder processBuilder = new ProcessBuilder(
                pythonExec,
                "-m", "src.main",
                repoId.toString()
        );

        // 5. 设置工作目录（当前路径）
        processBuilder.directory(new File("./RepoSummary"));

        // 6. 合并 stderr
        processBuilder.redirectErrorStream(true);

        // 7. 启动进程
        Process process = processBuilder.start();

        // 8. 自动选择编码
        Charset charset = isWindows ? Charset.forName("GBK") : Charset.forName("UTF-8");

        // 9. 异步打印输出
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

        System.out.println("RepoSummary started (pid=" + process.pid() + ", using env=" + envName + ")");
    }
}
