package cn.edu.pku.lixutian.helper;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaFilePathTest {
    @Test
    void keepsRepositoryRelativeJavaPathAsCanonicalProtocol() {
        assertEquals("cn/edu/pku/Foo.java", JavaFilePath.normalize("cn/edu/pku/Foo.java"));
        assertEquals("cn.edu.pku.Foo", JavaFilePath.toClassName("cn/edu/pku/Foo.java"));
        assertEquals("cn.edu.pku", JavaFilePath.packageName("cn/edu/pku/Foo.java"));
        assertEquals("", JavaFilePath.packageName("Foo.java"));
    }

    @Test
    void normalizesLegacyClassIdentifiersAtCompatibilityBoundaries() {
        assertEquals("cn/edu/pku/Foo.java", JavaFilePath.fromClassName("cn.edu.pku.Foo"));
        assertThrows(IllegalArgumentException.class, () -> JavaFilePath.normalize("cn.edu.pku.Foo"));
    }

    @Test
    void rejectsTraversalAndNonJavaFiles() {
        assertThrows(IllegalArgumentException.class, () -> JavaFilePath.normalize("../Foo.java"));
        assertThrows(IllegalArgumentException.class, () -> JavaFilePath.normalize("application.yml"));
        assertThrows(IllegalArgumentException.class, () -> JavaFilePath.resolve(Path.of("/tmp/source"), "/tmp/Foo.java"));
    }
}
