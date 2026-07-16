package cn.edu.pku.lixutian.controller;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.config.LtmConfig;
import cn.edu.pku.lixutian.dao.ProjectInfo;
import cn.edu.pku.lixutian.dao.repository.GraphEdgeRepository;
import cn.edu.pku.lixutian.dao.repository.ModuleRepository;
import cn.edu.pku.lixutian.dao.repository.ProjectInfoRepository;
import cn.edu.pku.lixutian.dto.request.GitRepoRequest;
import cn.edu.pku.lixutian.dto.request.SelectProjectRequest;
import cn.edu.pku.lixutian.dto.request.UpdateProjectRequest;
import cn.edu.pku.lixutian.dto.result.GitRepoPreviewResult;
import cn.edu.pku.lixutian.dto.result.ProjectInfoResult;
import cn.edu.pku.lixutian.dto.result.RepoSummaryProgressResult;
import cn.edu.pku.lixutian.helper.GitRemoteHelper;
import cn.edu.pku.lixutian.helper.RepoSummaryHelper;
import cn.edu.pku.lixutian.service.ProcessService;
import cn.edu.pku.lixutian.service.CodeMapService;
import cn.edu.pku.lixutian.helper.StatisticHelper;
import com.github.javaparser.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@RestController
@RequestMapping("/project")
public class ProjectController {
    @Autowired
    ProcessService processService;

    @Autowired
    ProjectInfoRepository projectInfoRepository;

    @Autowired
    ModuleRepository moduleRepository;

    @Autowired
    GraphEdgeRepository graphEdgeRepository;

    @Autowired
    CodeMapService codeMapService;

    @GetMapping("/getList")
    public List<ProjectInfoResult> getProjectList() {
        return projectInfoRepository.findAll()
                .stream()
                .map(ProjectInfoResult::new)
                .toList();
    }

    @PutMapping("/{projectId}")
    public ProjectInfoResult updateProject(
            @PathVariable Integer projectId,
            @RequestBody UpdateProjectRequest request
    ) {
        if (request == null || !hasText(request.getProjectName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project name is required.");
        }

        String projectName = request.getProjectName().trim();
        if (projectName.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project name is too long.");
        }

        ProjectInfo projectInfo = projectInfoRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found."));
        projectInfo.setRepoName(projectName);
        projectInfo.setDescription(cleanOptionalText(request.getDescription()));
        projectInfo.setDescriptionCn(cleanOptionalText(request.getDescriptionCn()));
        projectInfo.setGitLink(normalizeOptionalGitUrl(request.getGitLink()));
        return new ProjectInfoResult(projectInfoRepository.save(projectInfo));
    }

    @PostMapping("/select")
    public void selectProject(@RequestBody SelectProjectRequest request) throws ParseException, IOException, InterruptedException {
        ProjectInfo projectInfo = projectInfoRepository.findById(request.getRepoId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found."));
        ProjectType type = extractProjectType(projectInfo);

        ProjectState state = ProjectState.getInstance();
        state.setRepoId(request.getRepoId());
        state.setProjectPath(repoId2Path(request.getRepoId()), type.name());

        if (type == ProjectType.JAVA) {
            processService.process();
        }
        CodeMapService.isBuilt = false;

//        // 初始化缓存 - 在数据处理完成后进行
//        codeMapService.initializeCache(request.getRepoId());
    }

    @GetMapping("/current")
    public Map<String, Object> currentProject() {
        ProjectState state = ProjectState.getInstance();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repoId", state.getRepoId());
        result.put("projectType", state.getProjectType());
        result.put("sourceRoot", state.getSrcPath());
        return result;
    }

    @GetMapping("/summary/progress")
    public RepoSummaryProgressResult summaryProgress(@RequestParam Integer repoId) {
        return RepoSummaryHelper.getProgress(repoId);
    }

    @GetMapping("/summary/progress/all")
    public Map<Integer, RepoSummaryProgressResult> allSummaryProgress() {
        return RepoSummaryHelper.getAllProgress();
    }

    private String repoId2Path(Integer repoId) {
        return LtmConfig.getRepoPath() + "/" + repoId;
    }

    @PostMapping("/drop")
    public void dropProject(@RequestBody SelectProjectRequest request) throws IOException {
        if (request == null || request.getRepoId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project id is required.");
        }
        Integer repoId = request.getRepoId();
        if (!projectInfoRepository.existsById(repoId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found.");
        }

        graphEdgeRepository.deleteAll(graphEdgeRepository.findByRepo_Id(repoId));
        moduleRepository.deleteAll(moduleRepository.findByRepo_Id(repoId));
        projectInfoRepository.deleteById(repoId);
        deleteDirectoryIfExists(repoPath(repoId));
    }

    @PostMapping("/upload")
    public void uploadFolder(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("paths") List<String> paths,
            @RequestParam("folderName") String folderName,
            @RequestParam(value = "projectType", required = false) String projectType) throws IOException, InterruptedException {
        if (files.size() != paths.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded files and paths do not match.");
        }

        ProjectType type = parseProjectType(projectType, paths);
        assertHasSourceFiles(paths, type);

        ProjectInfo projectInfo = createProjectInfo(
                folderName,
                type,
                null,
                "Uploaded repository.",
                "上传的代码仓库。"
        );
        Path repoPath = repoPath(projectInfo.getId());

        saveUploadedSourceFiles(files, paths, repoPath, type);
        finishProjectImport(projectInfo, type, repoPath);
    }

    @PostMapping("/gitdown")
    public GitRepoPreviewResult gitDownRepo(@RequestBody GitRepoRequest request) throws IOException, InterruptedException {
        validateGitRequest(request);

        Path stagingPath = gitStagingPath(request);
        deleteDirectoryIfExists(stagingPath);
        Files.createDirectories(stagingPath.getParent());

        String gitUrl = normalizeGitUrl(request.getGitRepoName());
        runGitClone(stagingPath.getParent(), stagingPath, gitUrl);
        if (hasText(request.getCommitId())) {
            runCommand(stagingPath, List.of("git", "checkout", request.getCommitId().trim()));
        }

        ProjectType type = detectProjectType(listRelativePaths(stagingPath));
        return new GitRepoPreviewResult(defaultRepoName(request), type.name(), listNormalizedSourcePaths(stagingPath, type));
    }

    @PostMapping("/gitrepo")
    public void gitRepo(@RequestBody GitRepoRequest request) throws IOException, InterruptedException {
        validateGitRequest(request);

        Path stagingPath = gitStagingPath(request);
        if (!Files.exists(stagingPath)) {
            gitDownRepo(request);
        }

        ProjectType type = detectProjectType(listRelativePaths(stagingPath));
        String normalizedGitUrl = normalizeGitUrl(request.getGitRepoName());
        String gitProvider = GitRemoteHelper.provider(normalizedGitUrl);
        ProjectInfo projectInfo = createProjectInfo(
                hasText(request.getRepoName()) ? request.getRepoName().trim() : defaultRepoName(request),
                type,
                normalizedGitUrl,
                "Cloned from " + gitProvider + ".",
                "从 " + gitProvider + " 克隆的代码仓库。"
        );

        Path repoPath = repoPath(projectInfo.getId());
        copySourceFiles(stagingPath, repoPath, type);
        finishProjectImport(projectInfo, type, repoPath);
        deleteDirectoryIfExists(stagingPath);
    }

    @PostMapping("/gitclear")
    public void gitClear(@RequestBody GitRepoRequest request) throws IOException {
        validateGitRequest(request);
        deleteDirectoryIfExists(gitStagingPath(request));
    }

    @GetMapping("/tempRepoSummary")
    public void temp() throws IOException, InterruptedException {

        Path repoPath = repoPath(12);

        Map<String, Integer> statisticInfo = StatisticHelper.countInRepo(repoPath.resolve("src/main/java").toString());
    }

    @PostMapping("/resummary")
    public void reSummary(@RequestBody SelectProjectRequest request) throws IOException, InterruptedException {
        ProjectInfo projectInfo = projectInfoRepository.findById(request.getRepoId()).get();
        projectInfo.setSummaryFlag(false);
        projectInfoRepository.save(projectInfo);

        RepoSummaryHelper.runRepoSummary(projectInfo.getId());
    }

    private ProjectInfo createProjectInfo(
            String repoName,
            ProjectType type,
            String gitLink,
            String sourceDescription,
            String sourceDescriptionCn
    ) {
        ProjectInfo projectInfo = new ProjectInfo();
        projectInfo.setRepoName(hasText(repoName) ? repoName.trim() : "Untitled Repo");
        projectInfo.setDescription(sourceDescription);
        projectInfo.setDescriptionCn(sourceDescriptionCn);
        projectInfo.setProjectType(type.name());
        projectInfo.setGitLink(gitLink);
        projectInfo.setLoc(0);
        projectInfo.setNoc(0);
        projectInfo.setNom(0);
        projectInfo.setNof(0);
        projectInfo.setSummaryFlag(false);
        return projectInfoRepository.save(projectInfo);
    }

    private void saveUploadedSourceFiles(List<MultipartFile> files, List<String> paths, Path repoPath, ProjectType type) throws IOException {
        int savedFiles = 0;
        Path targetRoot = sourceRoot(repoPath, type);
        for (int i = 0; i < files.size(); i++) {
            String originalPath = paths.get(i);
            if (!isSourceFile(originalPath, type)) {
                continue;
            }

            String relativePath = normalizeSourcePath(originalPath, type);
            Path targetFile = safeResolve(targetRoot, relativePath);
            Files.createDirectories(targetFile.getParent());
            files.get(i).transferTo(targetFile);
            savedFiles++;
        }

        if (savedFiles == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No source files were found in the uploaded project.");
        }
    }

    private void copySourceFiles(Path sourceRepoPath, Path targetRepoPath, ProjectType type) throws IOException {
        List<Path> sourceFiles = listSourceFiles(sourceRepoPath, type);
        if (sourceFiles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No source files were found in the cloned project.");
        }

        Path targetRoot = sourceRoot(targetRepoPath, type);
        for (Path sourceFile : sourceFiles) {
            String relativePath = normalizeSourcePath(sourceRepoPath.relativize(sourceFile).toString(), type);
            Path targetFile = safeResolve(targetRoot, relativePath);
            Files.createDirectories(targetFile.getParent());
            Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void finishProjectImport(ProjectInfo projectInfo, ProjectType type, Path repoPath) throws IOException {
        Map<String, Integer> statisticInfo = type == ProjectType.JAVA
                ? StatisticHelper.countJavaProject(sourceRoot(repoPath, type).toString())
                : StatisticHelper.countPythonProject(sourceRoot(repoPath, type).toString());
        projectInfo.setLoc(statisticInfo.get("loc"));
        projectInfo.setNoc(statisticInfo.get("noc"));
        projectInfo.setNom(statisticInfo.get("nom"));
        projectInfo.setNof(statisticInfo.get("nof"));
        projectInfoRepository.save(projectInfo);

        RepoSummaryHelper.runRepoSummary(projectInfo.getId());
    }

    private ProjectType extractProjectType(ProjectInfo projectInfo) {
        String description = projectInfo.getDescription();
        if (description == null) {
            return ProjectType.JAVA;
        }
        String normalized = description.toLowerCase(Locale.ROOT);
        if (normalized.contains("[python]") || normalized.contains("python repo")) {
            return ProjectType.PYTHON;
        }
        return ProjectType.JAVA;
    }

    private void assertHasSourceFiles(List<String> paths, ProjectType type) {
        boolean hasSourceFiles = paths.stream().anyMatch(path -> isSourceFile(path, type));
        if (!hasSourceFiles) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No " + type.extension + " source files were found.");
        }
    }

    private ProjectType parseProjectType(String projectType, List<String> paths) {
        if (hasText(projectType)) {
            try {
                return ProjectType.valueOf(projectType.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported project type: " + projectType);
            }
        }
        return detectProjectType(paths);
    }

    private ProjectType detectProjectType(List<String> paths) {
        int javaScore = 0;
        int pythonScore = 0;

        for (String path : paths) {
            String normalized = path.replace("\\", "/").toLowerCase(Locale.ROOT);
            if (normalized.endsWith(".java")) javaScore += 3;
            if (normalized.contains("/src/main/java/") || normalized.startsWith("src/main/java/")) javaScore += 5;
            if (normalized.endsWith("pom.xml") || normalized.endsWith("build.gradle") || normalized.endsWith("build.gradle.kts")) javaScore += 2;

            if (normalized.endsWith(".py")) pythonScore += 3;
            if (normalized.endsWith("pyproject.toml") || normalized.endsWith("setup.py") || normalized.endsWith("requirements.txt")) pythonScore += 2;
        }

        if (javaScore == 0 && pythonScore == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only Java and Python projects are supported.");
        }
        return javaScore >= pythonScore ? ProjectType.JAVA : ProjectType.PYTHON;
    }

    private List<String> listRelativePaths(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !isIgnoredPath(root.relativize(path)))
                    .map(path -> root.relativize(path).toString().replace("\\", "/"))
                    .toList();
        }
    }

    private List<Path> listSourceFiles(Path root, ProjectType type) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !isIgnoredPath(root.relativize(path)))
                    .filter(path -> isSourceFile(path.toString(), type))
                    .toList();
        }
    }

    private List<String> listNormalizedSourcePaths(Path root, ProjectType type) throws IOException {
        return listSourceFiles(root, type).stream()
                .map(path -> normalizeSourcePath(root.relativize(path).toString(), type))
                .distinct()
                .sorted()
                .limit(1000)
                .toList();
    }

    private boolean isSourceFile(String path, ProjectType type) {
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(type.extension);
    }

    private String normalizeSourcePath(String rawPath, ProjectType type) {
        String path = rawPath.replace("\\", "/");
        String lowerPath = path.toLowerCase(Locale.ROOT);

        if (type == ProjectType.JAVA) {
            path = stripThrough(path, lowerPath, "src/main/java/");
            if (path.toLowerCase(Locale.ROOT).startsWith("java/")) {
                path = path.substring("java/".length());
            }
        } else {
            path = stripThrough(path, lowerPath, "src/main/python/");
            path = stripPythonSourceRoot(path);
        }

        String[] parts = path.split("/");
        StringBuilder sanitizedPath = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank() || part.equals(".")) continue;
            if (part.equals("..")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid source path: " + rawPath);
            }
            if (!sanitizedPath.isEmpty()) {
                sanitizedPath.append("/");
            }
            sanitizedPath.append(part);
        }

        if (sanitizedPath.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid source path: " + rawPath);
        }
        return sanitizedPath.toString();
    }

    private String stripThrough(String path, String lowerPath, String marker) {
        int index = lowerPath.indexOf(marker);
        if (index < 0) {
            return path;
        }
        return path.substring(index + marker.length());
    }

    private String stripPythonSourceRoot(String path) {
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (lowerPath.startsWith("src/")) {
            return path.substring("src/".length());
        }

        int nestedSourceRootIndex = lowerPath.indexOf("/src/");
        if (nestedSourceRootIndex >= 0) {
            return path.substring(nestedSourceRootIndex + "/src/".length());
        }

        return path;
    }

    private Path safeResolve(Path root, String relativePath) {
        Path normalizedRoot = root.normalize();
        Path target = normalizedRoot.resolve(relativePath).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid source path: " + relativePath);
        }
        return target;
    }

    private Path repoPath(Integer repoId) {
        return Path.of(LtmConfig.getRepoPath(), repoId.toString());
    }

    private Path sourceRoot(Path repoPath, ProjectType type) {
        return repoPath.resolve(Path.of("src", "main", type.sourceDirectory));
    }

    private void validateGitRequest(GitRepoRequest request) {
        if (request == null || !hasText(request.getGitRepoName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Git repository URL is required.");
        }
    }

    private Path gitStagingPath(GitRepoRequest request) {
        String key = safeFileName(request.getGitRepoName()) + "-"
                + (hasText(request.getCommitId()) ? safeFileName(request.getCommitId()) : "HEAD");
        return Path.of(LtmConfig.getRepoPath(), ".gitdown", key);
    }

    private String normalizeGitUrl(String gitRepoName) {
        try {
            return GitRemoteHelper.normalize(gitRepoName);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    private String normalizeOptionalGitUrl(String gitLink) {
        if (!hasText(gitLink)) {
            return null;
        }
        String normalized = normalizeGitUrl(gitLink);
        if (normalized.length() > 2048) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Git repository URL is too long.");
        }
        return normalized;
    }

    private String cleanOptionalText(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String defaultRepoName(GitRepoRequest request) {
        String value = GitRemoteHelper.displayName(normalizeGitUrl(request.getGitRepoName()));
        int slashIndex = value.lastIndexOf("/");
        String repoName = slashIndex >= 0 ? value.substring(slashIndex + 1) : value;
        if (!hasText(repoName)) {
            repoName = "repository";
        }
        if (hasText(request.getCommitId())) {
            String commit = request.getCommitId().trim();
            repoName += "_" + commit.substring(0, Math.min(commit.length(), 8));
        }
        return repoName;
    }

    private void runGitClone(Path workingDirectory, Path stagingPath, String gitUrl) throws IOException, InterruptedException {
        List<String> command = List.of(
                "git",
                "-c", "http.version=HTTP/1.1",
                "clone",
                gitUrl,
                stagingPath.toString());
        ResponseStatusException lastException = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            deleteDirectoryIfExists(stagingPath);
            try {
                runCommand(workingDirectory, command);
                return;
            } catch (ResponseStatusException exception) {
                lastException = exception;
                if (attempt == 3 || !isTransientGitFailure(exception.getReason())) {
                    break;
                }
                Thread.sleep(1000L * attempt);
            }
        }

        throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Git clone failed after retries.\n" + (lastException == null ? "" : lastException.getReason()));
    }

    private boolean isTransientGitFailure(String reason) {
        if (!hasText(reason)) {
            return true;
        }
        String normalized = reason.toLowerCase(Locale.ROOT);
        return normalized.contains("gnutls_handshake")
                || normalized.contains("tls connection")
                || normalized.contains("failed to connect")
                || normalized.contains("connection timed out")
                || normalized.contains("connection was reset")
                || normalized.contains("early eof")
                || normalized.contains("rpc failed")
                || normalized.contains("operation timed out")
                || normalized.contains("could not resolve host");
    }

    private void runCommand(Path workingDirectory, List<String> command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDirectory.toFile());
        applyGitProxy(processBuilder);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        boolean exited = process.waitFor(180, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!exited) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Command timed out: " + String.join(" ", command) + "\n" + output);
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Command failed: " + String.join(" ", command) + "\n" + output);
        }
    }

    private void applyGitProxy(ProcessBuilder processBuilder) {
        String proxyPort = System.getenv("GIT_PROXY_PORT");
        if (!hasText(proxyPort)) {
            return;
        }

        String proxyHost = System.getenv("GIT_PROXY_HOST");
        if (!hasText(proxyHost)) {
            proxyHost = "10.0.2.2";
        }

        String proxyUrl = "http://" + proxyHost.trim() + ":" + proxyPort.trim();
        Map<String, String> environment = processBuilder.environment();
        environment.put("http_proxy", proxyUrl);
        environment.put("https_proxy", proxyUrl);
        environment.put("HTTP_PROXY", proxyUrl);
        environment.put("HTTPS_PROXY", proxyUrl);
        environment.put("ALL_PROXY", proxyUrl);
        environment.put("GIT_HTTP_PROXY", proxyUrl);
        environment.put("GIT_HTTPS_PROXY", proxyUrl);
    }

    private void deleteDirectoryIfExists(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(file -> {
                        if (!file.delete()) {
                            throw new IllegalStateException("Failed to delete " + file.getAbsolutePath());
                        }
                    });
        }
    }

    private boolean isIgnoredPath(Path path) {
        for (Path part : path) {
            String name = part.toString();
            if (Objects.equals(name, ".git")
                    || Objects.equals(name, "node_modules")
                    || Objects.equals(name, "target")
                    || Objects.equals(name, "build")
                    || Objects.equals(name, "dist")
                    || Objects.equals(name, "__pycache__")
                    || Objects.equals(name, ".venv")
                    || Objects.equals(name, "venv")
                    || Objects.equals(name, "env")) {
                return true;
            }
        }
        return false;
    }

    private String safeFileName(String value) {
        String safe = value.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        return safe.isBlank() ? UUID.randomUUID().toString() : safe;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private enum ProjectType {
        JAVA("Java", ".java", "java"),
        PYTHON("Python", ".py", "python");

        private final String displayName;
        private final String extension;
        private final String sourceDirectory;

        ProjectType(String displayName, String extension, String sourceDirectory) {
            this.displayName = displayName;
            this.extension = extension;
            this.sourceDirectory = sourceDirectory;
        }
    }

}
