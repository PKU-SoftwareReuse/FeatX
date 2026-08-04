package com.mycode.helper;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.io.IOException;

/**
 * Canonical Java source-file identifiers used at the Agent boundary.
 *
 * <p>The public protocol is always a source-root-relative path such as
 * {@code cn/edu/pku/Foo.java}. Legacy fully-qualified class names are accepted
 * at internal compatibility boundaries, but are immediately normalized.</p>
 */
public final class JavaFilePath {
    private static final String JAVA_SUFFIX = ".java";

    private JavaFilePath() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Java file path is required.");
        }

        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }

        if (normalized.startsWith("/") || !normalized.endsWith(JAVA_SUFFIX)) {
            throw new IllegalArgumentException(
                    "Java files must use a source-root-relative path ending in .java: " + value
            );
        }
        if (normalized.contains("//")) {
            throw new IllegalArgumentException("Invalid Java file path: " + value);
        }

        Path path;
        try {
            path = Path.of(normalized).normalize();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid Java file path: " + value, exception);
        }
        if (path.isAbsolute() || path.startsWith("..") || path.toString().isBlank()) {
            throw new IllegalArgumentException("Invalid Java file path: " + value);
        }

        String result = path.toString().replace('\\', '/');
        if (!result.equals(normalized) || result.equals(JAVA_SUFFIX)) {
            throw new IllegalArgumentException("Invalid Java file path: " + value);
        }
        return result;
    }

    public static String fromClassName(String className) {
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("Java class name is required.");
        }
        String normalizedClassName = className.trim();
        if (!normalizedClassName.matches("(?:[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*\\.)*"
                + "[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*")) {
            throw new IllegalArgumentException("Invalid Java class name: " + className);
        }
        return normalize(normalizedClassName.replace('.', '/') + JAVA_SUFFIX);
    }

    public static String toClassName(String filePathOrClassName) {
        String normalized = normalize(filePathOrClassName);
        return normalized.substring(0, normalized.length() - JAVA_SUFFIX.length()).replace('/', '.');
    }

    public static String simpleTypeName(String filePathOrClassName) {
        String normalized = normalize(filePathOrClassName);
        int slash = normalized.lastIndexOf('/');
        return normalized.substring(slash + 1, normalized.length() - JAVA_SUFFIX.length());
    }

    public static String packageName(String filePathOrClassName) {
        String className = toClassName(filePathOrClassName);
        int separator = className.lastIndexOf('.');
        return separator < 0 ? "" : className.substring(0, separator);
    }

    public static Path resolve(Path sourceRoot, String filePathOrClassName) {
        Path normalizedRoot = sourceRoot.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(normalize(filePathOrClassName)).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Java file escapes the source root: " + filePathOrClassName);
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
                            "Java file resolves through a symbolic link outside the source root: "
                                    + filePathOrClassName
                    );
                }
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot safely resolve Java file: " + filePathOrClassName, exception);
        }
        return resolved;
    }
}
