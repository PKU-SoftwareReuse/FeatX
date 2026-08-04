package com.mycode.helper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ListFileHelper {
    private static final long MAX_AGENT_FILE_BYTES = 2L * 1024 * 1024;
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", "node_modules", "target", "build", "dist", "__pycache__", ".venv", "venv", "env",
            "preprocess1", "delombok", "preprocess2"
    );

    /**
     * 递归查找指定目录下所有以.java结尾的文件。
     * 返回相对于源码根的路径，例如 cn/edu/pku/Foo.java。
     * @param folderPath 文件夹路径
     * @return 替换后的文件路径列表
     */
    public static List<String> findJavaFiles(String folderPath) {
        List<String> javaFiles = new ArrayList<>();
        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            return javaFiles;
        }
        recursiveFind(folder, javaFiles, folder.getAbsolutePath());
        javaFiles.sort(Comparator.naturalOrder());
        return javaFiles;
    }

    /**
     * Recursively lists every regular file under the supplied source root.
     * Paths are relative to that root and always use '/'.
     */
    public static List<String> findAllFiles(String folderPath) {
        List<String> files = new ArrayList<>();
        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            return files;
        }
        recursiveFindAll(folder, files, folder.getAbsolutePath());
        files.sort(Comparator.naturalOrder());
        return files;
    }

    private static void recursiveFindAll(File current, List<String> result, String rootPath) {
        File[] files = current.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (Files.isSymbolicLink(file.toPath())) {
                continue;
            }
            if (file.isDirectory()) {
                if (!IGNORED_DIRECTORIES.contains(file.getName())) {
                    recursiveFindAll(file, result, rootPath);
                }
            } else if (file.isFile()) {
                String relativePath = file.getAbsolutePath().substring(rootPath.length() + 1);
                result.add(relativePath.replace(File.separatorChar, '/'));
            }
        }
    }

    private static void recursiveFind(File current, List<String> result, String rootPath) {
        File[] files = current.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (Files.isSymbolicLink(f.toPath())) {
                continue;
            }
            if (f.isDirectory()) {
                if (IGNORED_DIRECTORIES.contains(f.getName())) {
                    continue;
                }
                recursiveFind(f, result, rootPath);
            } else if (f.isFile() && f.getName().endsWith(".java")) {
                String relativePath = f.getAbsolutePath().substring(rootPath.length() + 1);
                result.add(relativePath.replace(File.separatorChar, '/'));
            }
        }
    }

    public static String getFileContent(String baseFolder, String javaFileName) throws IOException {
        Path file = JavaFilePath.resolve(Path.of(baseFolder), javaFileName);
        if (!Files.exists(file)) return "This file does not exist before, it's a brand new file.";
        if (!Files.isRegularFile(file)) {
            throw new IOException("Java source path is not a regular file: " + javaFileName);
        }
        if (Files.size(file) > MAX_AGENT_FILE_BYTES) {
            throw new IOException("Java source file exceeds the 2 MiB Agent limit: " + javaFileName);
        }

        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public static String getProjectFileContent(String baseFolder, String relativePath) throws IOException {
        Path file = ProjectFilePath.resolve(Path.of(baseFolder), relativePath);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Project path is not a regular file: " + relativePath);
        }
        if (Files.size(file) > MAX_AGENT_FILE_BYTES) {
            throw new IOException("Project file exceeds the 2 MiB Agent limit: " + relativePath);
        }
        byte[] content = Files.readAllBytes(file);
        for (byte value : content) {
            if (value == 0) {
                throw new IOException("Binary project file cannot be loaded into the Agent: " + relativePath);
            }
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    public static List<String> findPythonFiles(String folderPath) {
        List<String> pythonFiles = new ArrayList<>();
        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            return pythonFiles;
        }
        recursiveFindPython(folder, pythonFiles, folder.getAbsolutePath());
        pythonFiles.sort(Comparator.naturalOrder());
        return pythonFiles;
    }

    private static void recursiveFindPython(File current, List<String> result, String rootPath) {
        File[] files = current.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (Files.isSymbolicLink(file.toPath())) {
                continue;
            }
            if (file.isDirectory()) {
                String name = file.getName();
                if (IGNORED_DIRECTORIES.contains(name)) {
                    continue;
                }
                recursiveFindPython(file, result, rootPath);
            } else if (file.isFile() && file.getName().endsWith(".py")) {
                String relativePath = file.getAbsolutePath().substring(rootPath.length() + 1);
                result.add(relativePath.replace(File.separatorChar, '/'));
            }
        }
    }

    public static String getPythonFileContent(String baseFolder, String pythonFileName) throws IOException {
        String relativePath = normalizePythonPath(pythonFileName);
        File root = new File(baseFolder).getCanonicalFile();
        File file = new File(root, relativePath).getCanonicalFile();
        if (!file.toPath().startsWith(root.toPath())) {
            throw new IOException("Invalid Python file path: " + pythonFileName);
        }
        if (!file.exists()) return "This file does not exist before, it's a brand new file.";

        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String normalizePythonPath(String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IOException("Python file path is required.");
        }
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.contains("../") || normalized.startsWith("/") || !normalized.endsWith(".py")) {
            throw new IOException("Invalid Python file path: " + path);
        }
        return normalized;
    }

}
