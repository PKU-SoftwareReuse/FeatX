package cn.edu.pku.lixutian.helper;

import cn.edu.pku.lixutian.config.ProjectState;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.printer.configuration.PrettyPrinterConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        importLines = sanitizeImportLines(importLines);
        String javaBody = stripJavaPackageAndImports(content);

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
        sb.append(javaBody).append("\n");

        // === 覆盖写入文件 ===
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public static String stripJavaPackageAndImports(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        try {
            CompilationUnit cu = StaticJavaParser.parse(content);
            cu.removePackageDeclaration();
            cu.getImports().clear();

            PrettyPrinterConfiguration config = new PrettyPrinterConfiguration();
            config.setIndentSize(4);
            config.setPrintComments(true);
            config.setOrderImports(true);
            return cu.toString(config).trim();
        } catch (Exception ignored) {
            return stripJavaHeaderLines(content).trim();
        }
    }

    private static String stripJavaHeaderLines(String content) {
        StringBuilder sb = new StringBuilder();
        String[] lines = content.split("\\R", -1);
        for (String line : lines) {
            String trim = line.trim();
            if (trim.startsWith("package ") || trim.startsWith("import ")) {
                continue;
            }
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static List<String> sanitizeImportLines(List<String> importLines) {
        if (importLines == null) {
            return new ArrayList<>();
        }
        Set<String> uniqueImports = new LinkedHashSet<>();
        for (String line : importLines) {
            if (line == null) {
                continue;
            }
            String trim = line.trim();
            if (trim.startsWith("import ") && trim.endsWith(";")) {
                uniqueImports.add(trim);
            }
        }
        return new ArrayList<>(uniqueImports);
    }

    public static void rewritePythonFile(String relativePath, String content) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IOException("Python file path is required.");
        }
        String normalizedRelativePath = relativePath.replace('\\', '/');
        while (normalizedRelativePath.startsWith("./")) {
            normalizedRelativePath = normalizedRelativePath.substring(2);
        }
        if (normalizedRelativePath.startsWith("/")
                || normalizedRelativePath.contains("../")
                || !normalizedRelativePath.endsWith(".py")) {
            throw new IOException("Invalid Python file path: " + relativePath);
        }

        Path rootPath = Paths.get(ProjectState.getInstance().getSrcPath()).normalize();
        Path filePath = rootPath.resolve(normalizedRelativePath).normalize();
        if (!filePath.startsWith(rootPath)) {
            throw new IOException("Invalid Python file path: " + relativePath);
        }

        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

}
