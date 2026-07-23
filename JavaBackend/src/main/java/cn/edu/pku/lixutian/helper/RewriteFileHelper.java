package cn.edu.pku.lixutian.helper;

import cn.edu.pku.lixutian.config.ProjectState;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.printer.configuration.PrettyPrinterConfiguration;

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
        return !Files.exists(resolveJavaFilePath(fullName));
    }

    public static Path resolveJavaFilePath(String fullName) {
        String absoluteSrcPath = ProjectState.getInstance().getSrcPath();
        return JavaFilePath.resolve(Paths.get(absoluteSrcPath), fullName);
    }

    public static void rewriteFile(String fullName, String content) throws IOException {
        rewriteFile(fullName, content, null);
    }

    public static void rewriteFile(String fullName, String content, List<String> generatedImportLines) throws IOException {
        rewriteJavaFileContent(fullName, buildJavaFileContent(fullName, content, generatedImportLines));
    }

    public static String buildJavaFileContent(
            String fullName,
            String content,
            List<String> generatedImportLines
    ) throws IOException {
        Path filePath = resolveJavaFilePath(fullName);

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
            String className = JavaFilePath.toClassName(fullName);
            int lastDot = className.lastIndexOf('.');
            if (lastDot != -1) {
                String packageName = className.substring(0, lastDot);
                packageLine = "package " + packageName + ";";
            }
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

        return sb.toString();
    }

    public static void rewriteJavaFileContent(String fullName, String content) throws IOException {
        Path filePath = resolveJavaFilePath(fullName);
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public static List<String> extractJavaImportLines(String content) {
        if (content == null || content.isBlank()) {
            return new ArrayList<>();
        }
        try {
            CompilationUnit cu = StaticJavaParser.parse(content);
            return cu.getImports().stream()
                    .map(importDeclaration -> importDeclaration.toString().trim())
                    .toList();
        } catch (Exception ignored) {
            List<String> imports = new ArrayList<>();
            for (String line : content.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("import ") && trimmed.endsWith(";")) {
                    imports.add(trimmed);
                }
            }
            return imports;
        }
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

        Path rootPath = Paths.get(ProjectState.getInstance().getProjectPath()).normalize();
        Path filePath = rootPath.resolve(normalizedRelativePath).normalize();
        if (!filePath.startsWith(rootPath)) {
            throw new IOException("Invalid Python file path: " + relativePath);
        }

        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

}
