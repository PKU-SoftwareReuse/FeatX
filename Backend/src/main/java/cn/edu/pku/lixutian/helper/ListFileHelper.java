package cn.edu.pku.lixutian.helper;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ListFileHelper {
    /**
     * 递归查找指定目录下所有以.java结尾的文件，
     * 返回文件路径字符串，路径分隔符统一替换为点（'.'）
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
        return javaFiles;
    }

    private static void recursiveFind(File current, List<String> result, String rootPath) {
        File[] files = current.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                recursiveFind(f, result, rootPath);
            } else if (f.isFile() && f.getName().endsWith(".java")) {
                // 获取相对于根目录的路径，替换文件分隔符
                String relativePath = f.getAbsolutePath().substring(rootPath.length() + 1);
                String replaced = relativePath.replace(File.separatorChar, '.');
                result.add(replaced.substring(0,replaced.length() - 5));
            }
        }
    }

    public static String getFileContent(String baseFolder, String javaFileName) throws IOException {
        // javaFileName示例: "top.naccl.service.impl.DashboardServiceImpl"
        // 转换成路径 + ".java"
        String relativePath = javaFileName.replace('.', File.separatorChar) + ".java";
        File file = new File(baseFolder, relativePath);
        if (!file.exists()) return "This file does not exist before, it's a brand new file.";

        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

}
