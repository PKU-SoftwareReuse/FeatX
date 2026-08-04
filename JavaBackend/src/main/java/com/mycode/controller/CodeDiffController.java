package com.mycode.controller;

import com.mycode.config.ClusterState;
import com.mycode.config.ProjectState;
import com.mycode.dto.request.UpdateCodeFileRequest;
import com.mycode.dto.result.CodeFileDiffResult;
import com.mycode.graph.SKG;
import com.mycode.graph.softwareGraph.vertex.Vertex;
import com.mycode.graph.softwareGraph.vertex.VertexMap;
import com.mycode.helper.CodeDiffHelper;
import com.mycode.helper.ListFileHelper;
import com.mycode.helper.JavaFilePath;
import com.mycode.helper.ProjectFilePath;
import com.mycode.helper.ProjectPathMapping;
import com.mycode.helper.RewriteFileHelper;
import com.mycode.helper.graphAggregationHelper.DeleteHelper;
import com.mycode.helper.graphAggregationHelper.GraphAggregationHelper;
import com.mycode.helper.graphAggregationHelper.OriginHelper;
import com.mycode.service.CodeMapService;
import com.mycode.service.CandidateCodeService;
import com.mycode.service.RepositoryGitService;
import com.mycode.service.code.AgentService;
import com.mycode.service.code.AgentRunRegistry;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/code")
public class CodeDiffController {
    private static final Set<String> GENERATED_DIRECTORIES = Set.of(
            "preprocess1", "delombok", "preprocess2"
    );

    private final CandidateCodeService candidateCodeService;
    private final RepositoryGitService repositoryGitService;
    private final AgentRunRegistry agentRunRegistry;

    public CodeDiffController(
            CandidateCodeService candidateCodeService,
            RepositoryGitService repositoryGitService,
            AgentRunRegistry agentRunRegistry
    ) {
        this.candidateCodeService = candidateCodeService;
        this.repositoryGitService = repositoryGitService;
        this.agentRunRegistry = agentRunRegistry;
    }

    @GetMapping("/deleteDiffByClass")
    public String deleteDiffByClass(@RequestParam String classId) throws IOException {
        if (ProjectState.getInstance().isPython()) {
            String filePath = CodeMapService.resolvePythonNodeToFile(classId);
            String content = ListFileHelper.getPythonFileContent(ProjectState.getInstance().getSrcPath(), filePath);
            Map<String, String> modifications = ProjectState.getInstance().getModifications();
            String newCode = modifications.get(filePath);
            if (newCode == null) {
                newCode = modifications.get(classId);
            }
            if (AgentService.DELETE_FILE_SENTINEL.equals(newCode)) {
                newCode = "";
            }
            return CodeDiffHelper.generateDiffByCode(content, newCode == null ? content : newCode, filePath);
        }
        VertexMap vertexMap = VertexMap.getInstance();
        Vertex<TypeDeclaration<?>> classVertex = vertexMap.getClassDeclaration(classId);

        return CodeDiffHelper.generateDeleteDiff(SKG.getInstance(), classVertex, ClusterState.getInstance().getClusterIds());
        
//        // 安全检查：确保类顶点存在
//        if (classVertex == null) {
//            System.err.println("Error: Class vertex not found for classId: " + classId);
//            return "// Error: Class not found in memory structures: " + classId;
//        }
//
//        // 安全检查：确保类顶点的声明不为null
//        if (classVertex.getDeclaration() == null) {
//            System.err.println("Error: Class declaration is null for classId: " + classId);
//            return "// Error: Class declaration is null: " + classId;
//        }
//
//        // 使用基于内存的实现，不依赖图数据库查询
//        return CodeDiffHelper.generateMemoryBasedDeleteDiff(classVertex, ClusterState.getInstance().getClusterIds());
    }

    public String deleteCodeByClass(String classId) {
        VertexMap vertexMap = VertexMap.getInstance();
        Vertex<TypeDeclaration<?>> classVertex = vertexMap.getClassDeclaration(classId);
        SKG slicedGraph = SKG.getInstance().getSlicedGraphByClass(classVertex);
        GraphAggregationHelper debloatedHelper = new DeleteHelper(slicedGraph, classVertex, ClusterState.getInstance().getClusterIds());
        String debloatedCode = debloatedHelper.generateCode();

        return debloatedCode;
    }

    @GetMapping("/contextByClass")
    public String contextByClass(@RequestParam String classId) throws IOException {
        Optional<Path> graphFile = resolveGraphFile(classId);
        if (graphFile.isPresent()) {
            String content = Files.readString(graphFile.get(), StandardCharsets.UTF_8);
            return CodeDiffHelper.generateDiffByCode(content, content, classId);
        }
        if (isLanguageSourceFileId(classId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source file not found: " + classId);
        }
        if (ProjectState.getInstance().isPython()) {
            String filePath = CodeMapService.resolvePythonNodeToFile(classId);
            String content = ListFileHelper.getPythonFileContent(ProjectState.getInstance().getSrcPath(), filePath);
            return CodeDiffHelper.generateDiffByCode(content, content, filePath);
        }
        VertexMap vertexMap = VertexMap.getInstance();
        Vertex<TypeDeclaration<?>> classVertex = vertexMap.getClassDeclaration(classId);

        return CodeDiffHelper.generateContextDiff(SKG.getInstance(), classVertex, ClusterState.getInstance().getClusterIds());
    }

    private Optional<Path> resolveGraphFile(String nodeId) {
        if (!isLanguageSourceFileId(nodeId)) {
            return Optional.empty();
        }

        ProjectState project = ProjectState.getInstance();
        try {
            String projectRelativePath = ProjectFilePath.normalize(nodeId);
            Path projectFile = ProjectPathMapping.resolveProjectFile(project, projectRelativePath);
            if (Files.isRegularFile(projectFile)) {
                return Optional.of(projectFile);
            }
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            // The node may use the legacy source-root-relative file protocol.
        }

        try {
            String sourceRelativePath = project.isJava()
                    ? JavaFilePath.normalize(nodeId)
                    : ProjectFilePath.normalize(nodeId);
            Path sourceFile = ProjectFilePath.resolve(
                    Path.of(project.getSrcPath()),
                    sourceRelativePath
            );
            return Files.isRegularFile(sourceFile) ? Optional.of(sourceFile) : Optional.empty();
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            return Optional.empty();
        }
    }

    private boolean isLanguageSourceFileId(String nodeId) {
        if (nodeId == null) {
            return false;
        }
        String normalized = nodeId.trim().toLowerCase();
        return ProjectState.getInstance().isPython()
                ? normalized.endsWith(".py")
                : normalized.endsWith(".java");
    }

    @GetMapping("/newDiffByClass")
    public String newDiffByClass(@RequestParam String classId) throws IOException {
        if (ProjectState.getInstance().isPython()) {
            String filePath = CodeMapService.resolvePythonNodeToFile(classId);
            String originalCode = ListFileHelper.getPythonFileContent(ProjectState.getInstance().getSrcPath(), filePath);
            Map<String, String> modifications = ProjectState.getInstance().getModifications();
            String newCode = modifications.get(filePath);
            if (newCode == null) {
                newCode = modifications.get(classId);
            }
            if (newCode == null) {
                newCode = originalCode;
            }
            if (AgentService.DELETE_FILE_SENTINEL.equals(newCode)) {
                newCode = "";
            }
            return CodeDiffHelper.generateDiffByCode(originalCode, newCode, filePath);
        }
        return CodeDiffHelper.generateNewDiff(SKG.getInstance(), classId);
    }

    @GetMapping("/newFeatureCode")
    public String newFeatureCode(@RequestParam String classId) throws IOException {
        if (ProjectState.getInstance().isPython()) {
            String filePath = CodeMapService.resolvePythonNodeToFile(classId);
            Map<String, String> modifications = ProjectState.getInstance().getModifications();
            String newCode = modifications.getOrDefault(filePath, "");
            if (newCode == null || newCode.isBlank()) {
                newCode = modifications.getOrDefault(classId, "");
            }
            if (newCode == null) {
                newCode = "";
            }
            if (AgentService.DELETE_FILE_SENTINEL.equals(newCode)) {
                newCode = "";
            }
            return CodeDiffHelper.generateDiffByCode("", newCode, filePath);
        }
        // 从内存中获取新生成的代码
        return CodeDiffHelper.generateNewFeatureCode(classId);
    }

    @GetMapping("/candidateDiff")
    public CodeFileDiffResult candidateDiff(
            @RequestParam String classId,
            @RequestParam String operation,
            @RequestParam(required = false) String runId
    ) throws IOException, InterruptedException {
        if (classId == null || classId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Class or file id is required.");
        }

        try {
            agentRunRegistry.requireCompletedOperation(runId, operation);
            String filePath = resolveCandidateFilePath(classId);
            java.util.Optional<CodeFileDiffResult> cached = candidateCodeService.existingCandidate(filePath);
            if (cached.isPresent()) {
                return cached.get();
            }
            Map.Entry<String, String> candidateEntry = findCandidateEntry(filePath, classId);
            if (candidateEntry != null) {
                return candidateCodeService.prepareProjectCandidate(filePath, candidateEntry.getValue());
            }
            if (!ProjectState.getInstance().isPython()
                    && filePath.endsWith(".java")
                    && "delete".equalsIgnoreCase(operation)) {
                String sourceRelativePath = ProjectPathMapping.projectRelativeToSource(
                                ProjectState.getInstance(),
                                filePath
                        )
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Java graph file is outside the configured source root: " + filePath
                        ));
                String candidateContent = RewriteFileHelper.buildJavaFileContent(
                        sourceRelativePath,
                        deleteCodeByClass(classId),
                        null
                );
                return candidateCodeService.prepareProjectCandidate(filePath, candidateContent);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "No generated candidate exists for " + filePath);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
    }

    @GetMapping("/manualCandidate")
    public CodeFileDiffResult manualCandidate(
            @RequestParam String classId,
            @RequestParam String operation,
            @RequestParam(required = false) String runId
    ) throws IOException, InterruptedException {
        if (classId == null || classId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Class or file id is required.");
        }
        try {
            agentRunRegistry.requireCompletedOperation(runId, operation);
            return candidateCodeService.prepareManualProjectCandidate(resolveCandidateFilePath(classId));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
    }

    @PutMapping("/candidateDiff")
    public CodeFileDiffResult updateCandidateDiff(@RequestBody UpdateCodeFileRequest request)
            throws IOException, InterruptedException {
        if (request == null || request.getKey() == null || request.getKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Candidate file key is required.");
        }
        try {
            agentRunRegistry.requireCompletedOperation(request.getRunId(), request.getOperation());
            CodeFileDiffResult result = repositoryGitService.updateCandidate(
                    request.getKey(),
                    request.getOperation(),
                    request.getContent()
            );
            agentRunRegistry.replaceCompletedModifications(
                    request.getRunId(),
                    ProjectState.getInstance().getModifications()
            );
            return result;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
    }

    private Map.Entry<String, String> findCandidateEntry(String primaryKey, String fallbackKey) {
        Map<String, String> modifications = ProjectState.getInstance().getModifications();
        if (modifications.isEmpty()) {
            return null;
        }
        String candidate = modifications.get(primaryKey);
        String candidateKey = primaryKey;
        if (candidate == null && fallbackKey != null) {
            candidate = modifications.get(fallbackKey);
            candidateKey = fallbackKey;
        }
        if (candidate != null) {
            return Map.entry(candidateKey, candidate);
        }
        for (var entry : modifications.entrySet()) {
            if (!ProjectState.getInstance().isPython()) {
                try {
                    if (JavaFilePath.normalize(entry.getKey()).equals(JavaFilePath.normalize(primaryKey))) {
                        return Map.entry(JavaFilePath.normalize(entry.getKey()), entry.getValue());
                    }
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
            }
            if (entry.getKey().equals(primaryKey)
                    || entry.getKey().endsWith("." + primaryKey)
                    || primaryKey.endsWith("." + entry.getKey())) {
                return Map.entry(entry.getKey(), entry.getValue());
            }
        }
        return null;
    }

    private String resolveCandidateFilePath(String classOrFileId) {
        Map<String, String> modifications = ProjectState.getInstance().getModifications();
        if (modifications.containsKey(classOrFileId)) {
            return ProjectFilePath.normalize(classOrFileId);
        }
        try {
            String projectPath = ProjectFilePath.normalize(classOrFileId);
            Path projectRoot = Path.of(ProjectState.getInstance().getProjectPath());
            if (Files.isRegularFile(ProjectFilePath.resolve(projectRoot, projectPath))) {
                return projectPath;
            }
        } catch (IllegalArgumentException ignored) {
            // Graph node ids are not always file paths; language-specific resolution follows.
        }
        if (ProjectState.getInstance().isPython()) {
            return ProjectPathMapping.sourceRelativeToProject(
                    ProjectState.getInstance(),
                    CodeMapService.resolvePythonNodeToFile(classOrFileId)
            );
        }
        if (classOrFileId.endsWith(".java")) {
            return ProjectPathMapping.sourceRelativeToProject(
                    ProjectState.getInstance(),
                    JavaFilePath.normalize(classOrFileId)
            );
        }
        String javaPath = JavaFilePath.fromClassName(classOrFileId);
        if (modifications.containsKey(javaPath)) {
            return ProjectFilePath.normalize(javaPath);
        }
        Path projectRoot = ProjectPathMapping.projectRoot(ProjectState.getInstance());
        if (Files.isRegularFile(ProjectFilePath.resolve(projectRoot, javaPath))) {
            return ProjectFilePath.normalize(javaPath);
        }
        return ProjectPathMapping.sourceRelativeToProject(
                ProjectState.getInstance(),
                javaPath
        );
    }

    @GetMapping("/repositoryDiff")
    public String repositoryDiff() throws IOException, InterruptedException {
        String projectPath = ProjectState.getInstance().getProjectPath();
        if (projectPath == null || projectPath.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "No project is currently selected."
            );
        }
        Path repoPath = Path.of(projectPath).normalize();
        Path gitPath = repoPath.resolve(".git");
        if (!Files.isDirectory(gitPath) && !Files.isRegularFile(gitPath)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The current project is not backed by a Git repository."
            );
        }

        StringBuilder diff = new StringBuilder(runGitCommand(
                repoPath,
                List.of("git", "diff", "--no-ext-diff", "--unified=80", "HEAD", "--"),
                0
        ));

        String untrackedOutput = runGitCommand(
                repoPath,
                List.of("git", "ls-files", "--others", "--exclude-standard", "-z"),
                0
        );
        for (String relativePath : splitNullDelimited(untrackedOutput)) {
            if (isGeneratedPath(relativePath)) {
                continue;
            }
            diff.append(runGitCommand(
                    repoPath,
                    List.of("git", "diff", "--no-ext-diff", "--no-index", "--unified=80", "--", "/dev/null", relativePath),
                    0, 1
            ));
        }
        return diff.toString();
    }

    private boolean isGeneratedPath(String relativePath) {
        for (Path part : Path.of(relativePath)) {
            if (GENERATED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private String runGitCommand(Path workingDirectory, List<String> command, int... acceptedExitCodes)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDirectory.toFile());
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        boolean exited = process.waitFor(120, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!exited) {
            throw new IOException("Git diff command timed out.\n" + output);
        }

        int exitCode = process.exitValue();
        boolean accepted = Arrays.stream(acceptedExitCodes).anyMatch(code -> code == exitCode);
        if (!accepted) {
            throw new IOException("Git diff command failed with exit code " + exitCode + "\n" + output);
        }
        return output;
    }

    private List<String> splitNullDelimited(String output) {
        List<String> paths = new ArrayList<>();
        for (String path : output.split("\\u0000")) {
            if (path != null && !path.isBlank()) {
                paths.add(path);
            }
        }
        return paths;
    }

}
