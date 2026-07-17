package cn.edu.pku.lixutian.helper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListFileHelperTest {
    @Test
    void findAllFilesIncludesNonJavaFilesAndSkipsGeneratedDirectories(@TempDir Path sourceRoot) throws Exception {
        Files.createDirectories(sourceRoot.resolve("config"));
        Files.createDirectories(sourceRoot.resolve("target"));
        Files.createDirectories(sourceRoot.resolve("preprocess2"));
        Files.writeString(sourceRoot.resolve("Main.java"), "class Main {}\n");
        Files.writeString(sourceRoot.resolve("config/application.yml"), "server:\n");
        Files.writeString(sourceRoot.resolve("target/Generated.java"), "class Generated {}\n");
        Files.writeString(sourceRoot.resolve("preprocess2/Generated.java"), "class Generated {}\n");

        List<String> files = ListFileHelper.findAllFiles(sourceRoot.toString());

        assertEquals(List.of("Main.java", "config/application.yml"), files);
        assertTrue(files.contains("config/application.yml"));
        assertFalse(files.contains("target/Generated.java"));
        assertFalse(files.contains("preprocess2/Generated.java"));
    }
}
