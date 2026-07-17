package cn.edu.pku.lixutian.controller;

import cn.edu.pku.lixutian.config.ClusterState;
import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.graph.SKG;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.Vertex;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.VertexMap;
import cn.edu.pku.lixutian.helper.CodeDiffHelper;
import cn.edu.pku.lixutian.helper.ListFileHelper;
import cn.edu.pku.lixutian.helper.graphAggregationHelper.DeleteHelper;
import cn.edu.pku.lixutian.helper.graphAggregationHelper.GraphAggregationHelper;
import cn.edu.pku.lixutian.helper.graphAggregationHelper.OriginHelper;
import cn.edu.pku.lixutian.service.CodeMapService;
import cn.edu.pku.lixutian.service.code.AgentService;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/code")
public class CodeDiffController {
    private static final Set<String> GENERATED_DIRECTORIES = Set.of(
            "preprocess1", "delombok", "preprocess2"
    );

    @GetMapping("/deleteDiffByClass")
    public String deleteDiffByClass(@RequestParam String classId) throws IOException {
        if (ProjectState.getInstance().isPython()) {
            String filePath = CodeMapService.resolvePythonNodeToFile(classId);
            String content = ListFileHelper.getPythonFileContent(ProjectState.getInstance().getSrcPath(), filePath);
            String newCode = AgentService.modificationMap == null ? null : AgentService.modificationMap.get(filePath);
            if (newCode == null && AgentService.modificationMap != null) {
                newCode = AgentService.modificationMap.get(classId);
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
        if (ProjectState.getInstance().isPython()) {
            String filePath = CodeMapService.resolvePythonNodeToFile(classId);
            String content = ListFileHelper.getPythonFileContent(ProjectState.getInstance().getSrcPath(), filePath);
            return CodeDiffHelper.generateDiffByCode(content, content, filePath);
        }
        VertexMap vertexMap = VertexMap.getInstance();
        Vertex<TypeDeclaration<?>> classVertex = vertexMap.getClassDeclaration(classId);

        return CodeDiffHelper.generateContextDiff(SKG.getInstance(), classVertex, ClusterState.getInstance().getClusterIds());
    }

    @GetMapping("/newDiffByClass")
    public String newDiffByClass(@RequestParam String classId) throws IOException {
        if (ProjectState.getInstance().isPython()) {
            String filePath = CodeMapService.resolvePythonNodeToFile(classId);
            String originalCode = ListFileHelper.getPythonFileContent(ProjectState.getInstance().getSrcPath(), filePath);
            String newCode = AgentService.modificationMap == null ? null : AgentService.modificationMap.get(filePath);
            if (newCode == null && AgentService.modificationMap != null) {
                newCode = AgentService.modificationMap.get(classId);
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
            String newCode = AgentService.modificationMap == null ? "" : AgentService.modificationMap.getOrDefault(filePath, "");
            if ((newCode == null || newCode.isBlank()) && AgentService.modificationMap != null) {
                newCode = AgentService.modificationMap.getOrDefault(classId, "");
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
