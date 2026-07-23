package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.LtmConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaImportAnalyzerServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void analyzesImportsAndIgnoresGeneratedDirectories() throws Exception {
        Path repository = tempDirectory.resolve("12");
        Path source = repository.resolve("src/main/java/example");
        Files.createDirectories(source);
        Files.writeString(source.resolve("Consumer.java"), """
                package example;
                import example.Provider;
                class Consumer {}
                """);
        Files.writeString(source.resolve("Provider.java"), """
                package example;
                class Provider {}
                """);

        Path generated = repository.resolve("preprocess2/main/java/example");
        Files.createDirectories(generated);
        Files.writeString(generated.resolve("Ignored.java"), """
                package example;
                class Ignored {}
                """);

        LtmConfig config = new LtmConfig();
        config.setRepoPath(tempDirectory.toString());
        JavaImportAnalyzerService service = new JavaImportAnalyzerService(config);

        String matrix = service.analyzeRepository(12);

        assertTrue(matrix.contains("example.Consumer"));
        assertTrue(matrix.contains("example.Provider"));
        assertTrue(!matrix.contains("example.Ignored"));
        assertTrue(matrix.contains("example.Consumer,0,1"));
    }

    @Test
    void doesNotRetainClassesBetweenAnalyses() throws Exception {
        Path first = tempDirectory.resolve("first");
        Files.createDirectories(first);
        Files.writeString(first.resolve("First.java"), "class First {}\n");

        Path second = tempDirectory.resolve("second");
        Files.createDirectories(second);
        Files.writeString(second.resolve("Second.java"), "class Second {}\n");

        LtmConfig config = new LtmConfig();
        config.setRepoPath(tempDirectory.toString());
        JavaImportAnalyzerService service = new JavaImportAnalyzerService(config);

        String firstMatrix = service.analyze(first);
        String secondMatrix = service.analyze(second);

        assertTrue(firstMatrix.contains("First"));
        assertTrue(!firstMatrix.contains("Second"));
        assertTrue(secondMatrix.contains("Second"));
        assertTrue(!secondMatrix.contains("First"));
    }
}
