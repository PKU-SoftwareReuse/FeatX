package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dto.result.CodeFileDiffResult;
import cn.edu.pku.lixutian.helper.ListFileHelper;
import cn.edu.pku.lixutian.helper.RewriteFileHelper;
import cn.edu.pku.lixutian.service.code.AgentService;
import cn.edu.pku.lixutian.service.code.GenerateImportLinesService;
import com.github.javaparser.StaticJavaParser;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
public class CandidateCodeService {
    private static final long MAX_EDITABLE_FILE_BYTES = 2 * 1024 * 1024;

    private final GenerateImportLinesService generateImportLinesService;
    private final Map<String, CandidateDocument> javaDocuments = new ConcurrentHashMap<>();

    public CandidateCodeService(GenerateImportLinesService generateImportLinesService) {
        this.generateImportLinesService = generateImportLinesService;
    }

    public void clear() {
        javaDocuments.clear();
    }

    public CodeFileDiffResult prepareJavaCandidate(
            String classId,
            String operation,
            String candidateBody
    ) throws IOException, InterruptedException {
        CandidateDocument cached = javaDocuments.get(classId);
        if (cached != null) {
            return toResult(cached);
        }

        Path sourceFile = RewriteFileHelper.resolveJavaFilePath(classId).toAbsolutePath().normalize();
        boolean originalExists = Files.isRegularFile(sourceFile);
        String originalContent = originalExists ? readEditableFile(sourceFile) : "";
        List<String> importLines = null;
        boolean authoritative = "delete".equalsIgnoreCase(operation);
        String warning = null;

        if (!authoritative && candidateBody != null && !candidateBody.isBlank()) {
            try {
                List<String> javaFiles = ListFileHelper.findJavaFiles(ProjectState.getInstance().getSrcPath());
                importLines = generateImportLinesService.generate(
                        classId,
                        candidateBody,
                        String.join("\n", javaFiles)
                );
                authoritative = true;
            } catch (RuntimeException exception) {
                warning = "Import generation failed; existing imports are shown until the candidate is saved.";
            }
        }

        String modifiedContent = RewriteFileHelper.buildJavaFileContent(classId, candidateBody, importLines);
        CandidateDocument document = new CandidateDocument(
                classId,
                projectRelativePath(sourceFile),
                "java",
                originalContent,
                modifiedContent,
                originalExists,
                authoritative,
                warning
        );
        javaDocuments.put(classId, document);
        return toResult(document);
    }

    public CodeFileDiffResult preparePythonCandidate(String key, String relativePath, String candidateContent)
            throws IOException, InterruptedException {
        Path sourceRoot = Path.of(ProjectState.getInstance().getSrcPath()).toAbsolutePath().normalize();
        Path sourceFile = safeResolve(sourceRoot, relativePath);
        boolean originalExists = Files.isRegularFile(sourceFile);
        String originalContent = originalExists ? readEditableFile(sourceFile) : "";
        String modifiedContent = AgentService.DELETE_FILE_SENTINEL.equals(candidateContent) ? "" : candidateContent;
        CandidateDocument document = new CandidateDocument(
                key,
                projectRelativePath(sourceFile),
                "python",
                originalContent,
                modifiedContent == null ? originalContent : modifiedContent,
                originalExists,
                true,
                null
        );
        return toResult(document);
    }

    public CodeFileDiffResult updateCandidate(String key, String operation, String content)
            throws IOException, InterruptedException {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Candidate file key is required.");
        }
        String updatedContent = content == null ? "" : content;
        if (updatedContent.getBytes(StandardCharsets.UTF_8).length > MAX_EDITABLE_FILE_BYTES) {
            throw new IllegalArgumentException("Candidate file is too large to edit online.");
        }

        if (ProjectState.getInstance().isPython()) {
            Map<String, String> modifications = mutableModifications();
            modifications.put(key, updatedContent.isEmpty() && "delete".equalsIgnoreCase(operation)
                    ? AgentService.DELETE_FILE_SENTINEL
                    : updatedContent);
            AgentService.modificationMap = modifications;
            return preparePythonCandidate(key, key, modifications.get(key));
        }

        CandidateDocument document = javaDocuments.get(key);
        if (document == null) {
            throw new IllegalStateException("Open the candidate diff before saving it.");
        }
        if (updatedContent.isBlank() && !"delete".equalsIgnoreCase(operation)) {
            throw new IllegalArgumentException("Java candidate content cannot be empty.");
        }
        if (!updatedContent.isBlank()) {
            StaticJavaParser.parse(updatedContent);
        }
        document.modifiedContent = updatedContent;
        document.authoritative = true;
        document.warning = null;

        Map<String, String> modifications = mutableModifications();
        modifications.put(key, RewriteFileHelper.stripJavaPackageAndImports(updatedContent));
        AgentService.modificationMap = modifications;
        return toResult(document);
    }

    public Optional<String> authoritativeJavaContent(String classId) {
        CandidateDocument document = javaDocuments.get(classId);
        if (document == null || !document.authoritative) {
            return Optional.empty();
        }
        return Optional.of(document.modifiedContent);
    }

    private Map<String, String> mutableModifications() {
        return AgentService.modificationMap == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(AgentService.modificationMap);
    }

    private CodeFileDiffResult toResult(CandidateDocument document) throws IOException, InterruptedException {
        CodeFileDiffResult result = new CodeFileDiffResult();
        result.setKey(document.key);
        result.setPath(document.path);
        result.setLanguage(document.language);
        result.setOriginalContent(document.originalContent);
        result.setModifiedContent(document.modifiedContent);
        result.setDiff(generateGitDiff(
                document.path,
                document.originalContent,
                document.modifiedContent,
                document.originalExists
        ));
        result.setNewFile(!document.originalExists);
        result.setDeleted(document.originalExists && document.modifiedContent.isEmpty());
        result.setEditable(true);
        result.setWarning(document.warning);
        return result;
    }

    private String generateGitDiff(
            String relativePath,
            String originalContent,
            String modifiedContent,
            boolean originalExists
    ) throws IOException, InterruptedException {
        Path tempRoot = Files.createTempDirectory("featx-git-diff-");
        try {
            Path before = safeResolve(tempRoot.resolve("before"), relativePath);
            Path after = safeResolve(tempRoot.resolve("after"), relativePath);
            if (originalExists) {
                Files.createDirectories(before.getParent());
                Files.writeString(before, originalContent, StandardCharsets.UTF_8);
            }
            boolean modifiedExists = !originalExists || !modifiedContent.isEmpty();
            if (modifiedExists) {
                Files.createDirectories(after.getParent());
                Files.writeString(after, modifiedContent, StandardCharsets.UTF_8);
            }

            String nullDevice = System.getProperty("os.name").toLowerCase().contains("win") ? "NUL" : "/dev/null";
            String beforeArg = originalExists ? tempRoot.relativize(before).toString() : nullDevice;
            String afterArg = modifiedExists ? tempRoot.relativize(after).toString() : nullDevice;
            Process process = new ProcessBuilder(
                    "git", "diff", "--no-ext-diff", "--no-index", "--unified=3", "--", beforeArg, afterArg
            )
                    .directory(tempRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean exited = process.waitFor(30, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                throw new IOException("Git candidate diff timed out.");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0 && process.exitValue() != 1) {
                throw new IOException("Git candidate diff failed with exit code " + process.exitValue() + "\n" + output);
            }
            return output
                    .replace("a/before/", "a/")
                    .replace("b/before/", "b/")
                    .replace("a/after/", "a/")
                    .replace("b/after/", "b/")
                    .replace("a" + nullDevice, "/dev/null")
                    .replace("b" + nullDevice, "/dev/null");
        } finally {
            deleteRecursively(tempRoot);
        }
    }

    private String readEditableFile(Path path) throws IOException {
        if (Files.size(path) > MAX_EDITABLE_FILE_BYTES) {
            throw new IllegalArgumentException("File is too large to edit online: " + path.getFileName());
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private String projectRelativePath(Path sourceFile) {
        Path projectRoot = Path.of(ProjectState.getInstance().getProjectPath()).toAbsolutePath().normalize();
        if (!sourceFile.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Candidate file is outside the selected project.");
        }
        return projectRoot.relativize(sourceFile).toString().replace('\\', '/');
    }

    private Path safeResolve(Path root, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("File path is required.");
        }
        String normalizedPath = relativePath.replace('\\', '/');
        if (normalizedPath.startsWith("/") || normalizedPath.contains("../")) {
            throw new IllegalArgumentException("Invalid file path: " + relativePath);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path result = normalizedRoot.resolve(normalizedPath).normalize();
        if (!result.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Invalid file path: " + relativePath);
        }
        return result;
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class CandidateDocument {
        private final String key;
        private final String path;
        private final String language;
        private final String originalContent;
        private String modifiedContent;
        private final boolean originalExists;
        private boolean authoritative;
        private String warning;

        private CandidateDocument(
                String key,
                String path,
                String language,
                String originalContent,
                String modifiedContent,
                boolean originalExists,
                boolean authoritative,
                String warning
        ) {
            this.key = key;
            this.path = path;
            this.language = language;
            this.originalContent = originalContent;
            this.modifiedContent = modifiedContent;
            this.originalExists = originalExists;
            this.authoritative = authoritative;
            this.warning = warning;
        }
    }
}
