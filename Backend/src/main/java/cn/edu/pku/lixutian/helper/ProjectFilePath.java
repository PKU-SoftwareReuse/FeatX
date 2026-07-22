package cn.edu.pku.lixutian.helper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Safe source-root-relative paths used by the language-neutral Agent pipeline. */
public final class ProjectFilePath {
    private ProjectFilePath() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Project file path is required.");
        }

        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("/") || normalized.contains("//")) {
            throw new IllegalArgumentException("Invalid project file path: " + value);
        }

        Path path;
        try {
            path = Path.of(normalized).normalize();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid project file path: " + value, exception);
        }
        if (path.isAbsolute() || path.startsWith("..") || path.toString().isBlank()) {
            throw new IllegalArgumentException("Invalid project file path: " + value);
        }

        String result = path.toString().replace('\\', '/');
        if (!result.equals(normalized) || ".".equals(result)) {
            throw new IllegalArgumentException("Invalid project file path: " + value);
        }
        return result;
    }

    public static Path resolve(Path sourceRoot, String relativePath) {
        Path normalizedRoot = sourceRoot.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(normalize(relativePath)).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Project file escapes the source root: " + relativePath);
        }
        try {
            if (Files.exists(normalizedRoot)) {
                Path realRoot = normalizedRoot.toRealPath();
                Path existingAncestor = resolved;
                while (existingAncestor != null
                        && !Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
                    existingAncestor = existingAncestor.getParent();
                }
                if (existingAncestor != null && !existingAncestor.toRealPath().startsWith(realRoot)) {
                    throw new IllegalArgumentException(
                            "Project file resolves through a symbolic link outside the source root: " + relativePath
                    );
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot safely resolve project file: " + relativePath, exception);
        }
        return resolved;
    }
}
