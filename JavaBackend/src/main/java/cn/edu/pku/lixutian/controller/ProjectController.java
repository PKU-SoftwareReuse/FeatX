package cn.edu.pku.lixutian.controller;

import cn.edu.pku.lixutian.config.ClusterState;
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
import cn.edu.pku.lixutian.service.CandidateCodeService;
import cn.edu.pku.lixutian.service.OperationProgressService;
import cn.edu.pku.lixutian.service.RepoSummaryIndexService;
import cn.edu.pku.lixutian.service.code.AgentRunRegistry;
import cn.edu.pku.lixutian.helper.StatisticHelper;
import com.github.javaparser.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
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

    @Autowired
    AgentRunRegistry agentRunRegistry;

    @Autowired
    CandidateCodeService candidateCodeService;

    @Autowired
    OperationProgressService progressService;

    @Autowired
    RepoSummaryIndexService repoSummaryIndexService;

    @GetMapping("/getList")
    public List<ProjectInfoResult> getProjectList() {
        return projectInfoRepository.findAllByArchivedFalseOrderByIdAsc()
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
        if (request == null || request.getRepoId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project id is required.");
        }
        ProjectInfo projectInfo = projectInfoRepository.findById(request.getRepoId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found."));
        ProjectType type = extractProjectType(projectInfo);

        String workspaceId = ProjectState.currentWorkspaceId();
        ProjectState previousProject = ProjectState.currentProject().orElse(null);
        if (previousProject != null && Objects.equals(previousProject.getRepoId(), request.getRepoId())) {
            // Opening the workspace that already owns the active Agent run is
            // navigation, not a project switch. Reprocessing it would also
            // invalidate the in-memory candidate the user is returning to.
            return;
        }

        ensureWorkspaceCanChange(previousProject);
        ProjectState state = ProjectState.loadRepository(
                request.getRepoId(),
                repoId2Path(request.getRepoId()),
                type.name()
        );

        try (ProjectState.Scope ignored = ProjectState.bindProject(workspaceId, state)) {
            if (type == ProjectType.JAVA) {
                processService.process();
            }
            if (Boolean.TRUE.equals(projectInfo.getSummaryFlag())) {
                repoSummaryIndexService.warmRepositoryIndexes(state, request.getRepoId());
            }
            codeMapService.invalidateRepository(request.getRepoId());
        }
        ProjectState.assignWorkspace(workspaceId, request.getRepoId());
        ClusterState.clearWorkspace(workspaceId);

//        // 初始化缓存 - 在数据处理完成后进行
//        codeMapService.initializeCache(request.getRepoId());
    }

    @GetMapping("/current")
    public Map<String, Object> currentProject() {
        ProjectState state = ProjectState.currentProject().orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repoId", state == null ? null : state.getRepoId());
        result.put("projectType", state == null ? null : state.getProjectType());
        result.put("projectRoot", state == null ? null : state.getProjectPath());
        result.put("sourceRoot", state == null ? null : state.getSrcPath());
        result.put("preprocess1Root", state == null ? null : state.getPreprocess1Path());
        result.put("delombokRoot", state == null ? null : state.getDelombokPath());
        result.put("preprocess2Root", state == null ? null : state.getPreprocess2Path());
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

    private void ensureWorkspaceCanChange(ProjectState currentProject) {
        if (currentProject == null || currentProject.getRepoId() == null) {
            return;
        }
        if (agentRunRegistry.hasActiveOperation(currentProject.getRepoId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The current project still has an unfinished or unconfirmed Agent operation."
            );
        }
    }

    @PostMapping("/drop")
    public void dropProject(@RequestBody SelectProjectRequest request) throws IOException {
        if (request == null || request.getRepoId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project id is required.");
        }
        Integer repoId = request.getRepoId();
        if (agentRunRegistry.hasActiveOperation(repoId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This project still has an unfinished or unconfirmed Agent operation."
            );
        }
        if (!projectInfoRepository.existsById(repoId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found.");
        }

        graphEdgeRepository.deleteAll(graphEdgeRepository.findByRepo_Id(repoId));
        moduleRepository.deleteAll(moduleRepository.findByRepo_Id(repoId));
        projectInfoRepository.deleteById(repoId);
        deleteDirectoryIfExists(repoPath(repoId));
        agentRunRegistry.clearRepository(repoId);
        processService.clearRepository(repoId);
        codeMapService.invalidateRepository(repoId);
        candidateCodeService.clearRepository(repoId);
        progressService.clearRepository(repoId);
        ProjectState.removeRepository(repoId);
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

        saveUploadedFiles(files, paths, repoPath);
        initializeUploadedRepository(repoPath);
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
        return new GitRepoPreviewResult(defaultRepoName(request), type.name(), listNormalizedProjectPaths(stagingPath));
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
        moveGitWorkspace(stagingPath, repoPath);
        createFeatxBranch(repoPath, detectOriginalBranch(repoPath));
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

        startRepoSummaryPipeline(projectInfo, extractProjectType(projectInfo), repoPath(projectInfo.getId()));
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

    private void saveUploadedFiles(List<MultipartFile> files, List<String> paths, Path repoPath) throws IOException {
        int savedFiles = 0;
        Path targetRoot = repoPath.normalize();
        for (int i = 0; i < files.size(); i++) {
            String originalPath = paths.get(i);
            if (isRepositoryMetadataPath(Path.of(originalPath.replace("\\", "/")))) {
                continue;
            }

            String relativePath = normalizeProjectPath(originalPath);
            Path targetFile = safeResolve(targetRoot, relativePath);
            Files.createDirectories(targetFile.getParent());
            files.get(i).transferTo(targetFile);
            savedFiles++;
        }

        if (savedFiles == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No source files were found in the uploaded project.");
        }
    }

    private void initializeUploadedRepository(Path repoPath) throws IOException, InterruptedException {
        runCommand(repoPath, List.of("git", "init", "-b", "main"));
        runCommand(repoPath, List.of("git", "add", "-A"));
        runCommand(repoPath, List.of(
                "git", "-c", "user.name=FeatX", "-c", "user.email=featx@localhost",
                "commit", "--allow-empty", "-m", "FeatX import baseline"
        ));
        createFeatxBranch(repoPath, "main");
    }

    private void moveGitWorkspace(Path sourceRepoPath, Path targetRepoPath) throws IOException {
        Files.createDirectories(targetRepoPath.getParent());
        try {
            Files.move(sourceRepoPath, targetRepoPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(sourceRepoPath, targetRepoPath);
        }
    }

    private void createFeatxBranch(Path repoPath, String originalBranch) throws IOException, InterruptedException {
        assertStandaloneGitRepository(repoPath);
        String branch = "featx-dev/" + normalizeBranchName(originalBranch);
        runCommand(repoPath, List.of("git", "checkout", "-B", branch));
    }

    private void assertStandaloneGitRepository(Path repoPath) throws IOException, InterruptedException {
        Path expectedRoot = repoPath.toAbsolutePath().normalize();
        Path gitMetadata = expectedRoot.resolve(".git");
        if (!Files.isDirectory(gitMetadata) && !Files.isRegularFile(gitMetadata)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The imported project is not backed by its own Git repository."
            );
        }

        String topLevelOutput = runCommandOutput(
                expectedRoot,
                List.of("git", "rev-parse", "--show-toplevel")
        ).trim();
        Path actualRoot = Path.of(topLevelOutput).toRealPath();
        if (!expectedRoot.toRealPath().equals(actualRoot)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Git repository root does not match the imported project directory."
            );
        }
    }

    private String detectOriginalBranch(Path repoPath) {
        String remoteHead = tryGitOutput(repoPath, List.of("git", "symbolic-ref", "--short", "refs/remotes/origin/HEAD"));
        if (hasText(remoteHead)) {
            String value = remoteHead.trim();
            if (value.startsWith("origin/")) {
                value = value.substring("origin/".length());
            }
            if (hasText(value) && !value.equals("HEAD")) {
                return value;
            }
        }

        String currentBranch = tryGitOutput(repoPath, List.of("git", "branch", "--show-current"));
        return hasText(currentBranch) && !currentBranch.trim().equals("HEAD")
                ? currentBranch.trim()
                : "main";
    }

    private String normalizeBranchName(String value) {
        String normalized = hasText(value) ? value.trim().replace('\\', '/') : "main";
        normalized = normalized.replaceFirst("^origin/", "");
        normalized = normalized.replaceAll("[^A-Za-z0-9._/-]+", "_");
        normalized = normalized.replaceAll("/{2,}", "/");
        normalized = normalized.replace("..", "_");
        normalized = normalized.replaceAll("(?i)\\.lock(?=/|$)", "_lock");
        normalized = normalized.replaceAll("^[/.-]+|[/.-]+$", "");
        return hasText(normalized) ? normalized : "main";
    }

    private String tryGitOutput(Path workingDirectory, List<String> command) {
        try {
            return runCommandOutput(workingDirectory, command);
        } catch (IOException | InterruptedException | ResponseStatusException ignored) {
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        }
    }

    private void finishProjectImport(ProjectInfo projectInfo, ProjectType type, Path repoPath) throws IOException {
        Path analysisRoot = resolveAnalysisRoot(repoPath, type);
        Map<String, Integer> statisticInfo = type == ProjectType.JAVA
                ? StatisticHelper.countJavaProject(analysisRoot.toString())
                : StatisticHelper.countPythonProject(analysisRoot.toString());
        projectInfo.setLoc(statisticInfo.get("loc"));
        projectInfo.setNoc(statisticInfo.get("noc"));
        projectInfo.setNom(statisticInfo.get("nom"));
        projectInfo.setNof(statisticInfo.get("nof"));
        projectInfoRepository.save(projectInfo);

        startRepoSummaryPipeline(projectInfo, type, repoPath);
    }

    private void startRepoSummaryPipeline(ProjectInfo projectInfo, ProjectType type, Path repoPath) throws IOException {
        Integer repositoryId = projectInfo.getId();
        RepoSummaryHelper.runRepoSummary(repositoryId, () -> {
            ProjectState state = ProjectState.loadRepository(repositoryId, repoPath.toString(), type.name());
            try (ProjectState.Scope ignored = ProjectState.bindProject("reposummary-index-" + repositoryId, state)) {
                if (type == ProjectType.JAVA) {
                    processService.process();
                }
                repoSummaryIndexService.warmRepositoryIndexes(state, repositoryId);
            }
        });
    }

    private ProjectType extractProjectType(ProjectInfo projectInfo) {
        if (hasText(projectInfo.getProjectType())) {
            try {
                return ProjectType.valueOf(projectInfo.getProjectType().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Fall back to the legacy description-based detection below.
            }
        }

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

    private List<String> listNormalizedProjectPaths(Path root) throws IOException {
        return listProjectFiles(root).stream()
                .map(path -> normalizeProjectPath(root.relativize(path).toString()))
                .distinct()
                .sorted()
                .limit(1000)
                .toList();
    }

    private List<Path> listProjectFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !isRepositoryMetadataPath(root.relativize(path)))
                    .toList();
        }
    }

    private boolean isSourceFile(String path, ProjectType type) {
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(type.extension);
    }

    private String normalizeProjectPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid project path.");
        }

        String path = rawPath.replace("\\", "/");
        if (path.startsWith("/") || path.matches("^[A-Za-z]:/.*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid project path: " + rawPath);
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

    private Path resolveAnalysisRoot(Path repoPath, ProjectType type) throws IOException {
        Path conventionalRoot = sourceRoot(repoPath, type);
        if (Files.isDirectory(conventionalRoot) && !listSourceFiles(conventionalRoot, type).isEmpty()) {
            return conventionalRoot;
        }
        return repoPath;
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
        runCommandOutput(workingDirectory, command);
    }

    private String runCommandOutput(Path workingDirectory, List<String> command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workingDirectory.toFile());
        applyGitRepositoryBoundary(processBuilder, workingDirectory, command);
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
        return output;
    }

    private void applyGitRepositoryBoundary(
            ProcessBuilder processBuilder,
            Path workingDirectory,
            List<String> command
    ) {
        if (command.isEmpty() || !Objects.equals(Path.of(command.get(0)).getFileName().toString(), "git")) {
            return;
        }
        Path boundary = workingDirectory.toAbsolutePath().normalize().getParent();
        if (boundary != null) {
            processBuilder.environment().put("GIT_CEILING_DIRECTORIES", boundary.toString());
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
                    || Objects.equals(name, "env")
                    || Objects.equals(name, "preprocess1")
                    || Objects.equals(name, "delombok")
                    || Objects.equals(name, "preprocess2")) {
                return true;
            }
        }
        return false;
    }

    private boolean isRepositoryMetadataPath(Path path) {
        for (Path part : path) {
            if (Objects.equals(part.toString(), ".git")) {
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
