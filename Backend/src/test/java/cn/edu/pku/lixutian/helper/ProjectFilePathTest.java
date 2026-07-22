package cn.edu.pku.lixutian.helper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectFilePathTest {
    @Test
    void acceptsAnySourceRootRelativeFileType(@TempDir Path sourceRoot) {
        assertEquals("config/application.yml", ProjectFilePath.normalize("./config/application.yml"));
        assertEquals(
                sourceRoot.resolve("templates/page.html").toAbsolutePath().normalize(),
                ProjectFilePath.resolve(sourceRoot, "templates/page.html")
        );
    }

    @Test
    void rejectsAbsoluteAndTraversalPaths(@TempDir Path sourceRoot) {
        assertThrows(IllegalArgumentException.class, () -> ProjectFilePath.normalize("../outside.yml"));
        assertThrows(IllegalArgumentException.class, () -> ProjectFilePath.normalize("/tmp/outside.yml"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProjectFilePath.resolve(sourceRoot, "nested/../../outside.yml")
        );
    }
}
