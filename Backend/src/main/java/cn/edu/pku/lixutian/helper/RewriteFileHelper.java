package cn.edu.pku.lixutian.helper;

import cn.edu.pku.lixutian.config.ProjectState;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public class RewriteFileHelper {

    public static boolean isNewFile(String fullName) {
        String absoluteSrcPath = ProjectState.getInstance().getSrcPath();
        String relativePath = fullName.replace('.', File.separatorChar) + ".java";
        Path filePath = Paths.get(absoluteSrcPath, relativePath);

        return !Files.exists(filePath);
    }

    public static void rewriteFile(String fullName, String content) throws IOException {
        rewriteFile(fullName, content, null);
    }

    public static void rewriteFile(String fullName, String content, List<String> generatedImportLines) throws IOException {
        String absoluteSrcPath = ProjectState.getInstance().getSrcPath();
        String relativePath = fullName.replace('.', File.separatorChar) + ".java";
        Path filePath = Paths.get(absoluteSrcPath, relativePath);

        String packageLine = "";
        List<String> importLines = new ArrayList<>();

        if (Files.exists(filePath)) {
            // === 原文件存在：提取 package 和 import ===
            List<String> lines = Files.readAllLines(filePath);
            for (String line : lines) {
                String trim = line.trim();
                if (trim.startsWith("package ")) {
                    packageLine = line;
                } else if (trim.startsWith("import ")) {
                    importLines.add(line);
                }
            }
            if (generatedImportLines != null) {
                importLines = generatedImportLines;
            }
        } else {
            // === 文件不存在：自动生成 package，忽略 import ===
            int lastDot = fullName.lastIndexOf('.');
            if (lastDot != -1) {
                String packageName = fullName.substring(0, lastDot);
                packageLine = "package " + packageName + ";";
            }
            assert generatedImportLines != null;
            importLines = generatedImportLines;
        }

        // === 拼接新文件内容 ===
        StringBuilder sb = new StringBuilder();
        if (!packageLine.isEmpty()) {
            sb.append(packageLine).append("\n\n");
        }
        if (!importLines.isEmpty()) {
            for (String imp : importLines) {
                sb.append(imp).append("\n");
            }
            sb.append("\n");
        }
        sb.append(content).append("\n");

        // === 覆盖写入文件 ===
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

}
