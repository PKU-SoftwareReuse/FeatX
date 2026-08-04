package com.mycode.helper;

import com.mycode.config.ProjectState;

import java.nio.file.Path;
import java.util.Optional;

/** Maps paths between the repository workspace and the language analysis root. */
public final class ProjectPathMapping {
    private ProjectPathMapping() {
    }

    public static Path projectRoot(ProjectState project) {
        return requiredRoot(project.getProjectPath(), "Project root");
    }

    public static Path sourceRoot(ProjectState project) {
        return requiredRoot(project.getSrcPath(), "Source root");
    }

    public static Path resolveProjectFile(ProjectState project, String projectRelativePath) {
        return ProjectFilePath.resolve(projectRoot(project), projectRelativePath);
    }

    public static String sourceRelativeToProject(ProjectState project, String sourceRelativePath) {
        return sourceRelativeToProject(projectRoot(project), sourceRoot(project), sourceRelativePath);
    }

    public static String sourceRelativeToProject(
            Path projectRoot,
            Path sourceRoot,
            String sourceRelativePath
    ) {
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        Path sourceFile = ProjectFilePath.resolve(sourceRoot, sourceRelativePath);
        if (!sourceFile.startsWith(normalizedProjectRoot)) {
            throw new IllegalStateException("Source root is outside the selected project.");
        }
        return slashPath(normalizedProjectRoot.relativize(sourceFile));
    }

    public static Optional<String> projectRelativeToSource(ProjectState project, String projectRelativePath) {
        return projectRelativeToSource(projectRoot(project), sourceRoot(project), projectRelativePath);
    }

    public static Optional<String> projectRelativeToSource(
            Path projectRoot,
            Path sourceRoot,
            String projectRelativePath
    ) {
        Path normalizedSourceRoot = sourceRoot.toAbsolutePath().normalize();
        Path projectFile = ProjectFilePath.resolve(projectRoot, projectRelativePath);
        if (!projectFile.startsWith(normalizedSourceRoot)) {
            return Optional.empty();
        }
        return Optional.of(slashPath(normalizedSourceRoot.relativize(projectFile)));
    }

    public static String absoluteToProject(ProjectState project, Path file) {
        Path projectRoot = projectRoot(project);
        Path normalizedFile = file.toAbsolutePath().normalize();
        if (!normalizedFile.startsWith(projectRoot)) {
            throw new IllegalArgumentException("File is outside the selected project: " + file);
        }
        return slashPath(projectRoot.relativize(normalizedFile));
    }

    private static Path requiredRoot(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(label + " is not configured.");
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static String slashPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
