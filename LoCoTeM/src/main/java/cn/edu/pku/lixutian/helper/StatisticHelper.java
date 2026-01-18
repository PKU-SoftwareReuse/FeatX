package cn.edu.pku.lixutian.helper;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class StatisticHelper {
    public static Map<String, Integer> countInRepo(String repoPath) {
        File projectDir = new File(repoPath);

        AtomicInteger totalLOC = new AtomicInteger(0);
        AtomicInteger totalClasses = new AtomicInteger(0);
        AtomicInteger totalMethods = new AtomicInteger(0);
        AtomicInteger totalFields = new AtomicInteger(0);
        AtomicInteger totalFiles = new AtomicInteger(0);

        countInDir(projectDir, totalLOC, totalClasses, totalMethods, totalFields, totalFiles);

        Map<String, Integer> map = new HashMap<>();
        map.put("loc", totalLOC.get());
        map.put("noc", totalClasses.get());
        map.put("nom", totalMethods.get());
        map.put("nof", totalFields.get());

        return map;
    }

    private static void countInDir(File dir, AtomicInteger totalLOC, AtomicInteger totalClasses, AtomicInteger totalMethods, AtomicInteger totalFields, AtomicInteger totalFiles) {
        if (!dir.exists() || !dir.isDirectory()) {
            System.out.println("❌ 目录不存在：" + dir.getAbsolutePath());
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                countInDir(file, totalLOC, totalClasses, totalMethods, totalFields, totalFiles); // 递归扫描子目录
            } else if (file.getName().endsWith(".java")) {
                totalFiles.incrementAndGet();
                countInFile(file, totalLOC, totalClasses, totalMethods, totalFields);
            }
        }
    }

    private static void countInFile(File file, AtomicInteger totalLOC, AtomicInteger totalClasses, AtomicInteger totalMethods, AtomicInteger totalFields) {
        try {
            CompilationUnit cu = new JavaParser().parse(file).getResult().orElse(null);
            if (cu == null) return;

            // 统计代码行数（去掉空行 & 注释）
            int loc = countLinesOfCode(file);
            totalLOC.addAndGet(loc);

            // 统计类别数量
            long classCount = cu.findAll(TypeDeclaration.class).size();
            totalClasses.addAndGet((int) classCount);

            // 统计方法数量
            long methodCount = cu.findAll(CallableDeclaration.class).size();

            // RepoSummary


            for(CallableDeclaration callableDeclaration: cu.findAll(CallableDeclaration.class)){
                Optional<ClassOrInterfaceDeclaration> clazzOpt = callableDeclaration.findAncestor(ClassOrInterfaceDeclaration.class);
                if (!clazzOpt.isPresent()) {
                    // 没有所属类，直接忽略
                    continue;
                }

                ClassOrInterfaceDeclaration clazz = clazzOpt.get();

                // 获取包名（可能不存在）
                String packageName = cu.getPackageDeclaration()
                        .map(pd -> pd.getName().asString())
                        .orElse("");

                // 类的全限定名
                String fullClassName = packageName.isEmpty()
                        ? clazz.getNameAsString()
                        : packageName + "." + clazz.getNameAsString();

                // 方法签名
                String signature = callableDeclaration.getSignature().asString();
                String fullSignature = fullClassName + "." + signature;

                System.out.println(fullSignature + ",");
            }


            totalMethods.addAndGet((int) methodCount);

            // 统计字段数量
            long fieldCount = cu.findAll(FieldDeclaration.class).size();
            totalFields.addAndGet((int) fieldCount);


        } catch (IOException e) {
            System.err.println("❌ 读取文件失败：" + file.getAbsolutePath());
        }
    }

    private static int countLinesOfCode(File file) throws IOException {
        try (Stream<String> lines = Files.lines(file.toPath())) {
            return (int) lines
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("//") && !line.startsWith("/*") && !line.startsWith("*"))
                    .count();
        }
    }
}
