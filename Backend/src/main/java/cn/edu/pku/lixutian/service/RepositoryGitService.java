package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dto.result.GitWorkspaceStatusResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class RepositoryGitService {
    private static final Set<String> GENERATED_DIRECTORIES = Set.of(
            "preprocess1", "delombok", "preprocess2"
    );

    private final CandidateCodeService candidateCodeService;
    private final Map<Integer, Object> repositoryLocks = new ConcurrentHashMap<>();

    public RepositoryGitService(CandidateCodeService candidateCodeService) {
        this.candidateCodeService = candidateCodeService;
    }

    public GitWorkspaceStatusResult status() throws IOException, InterruptedException {
        synchronized (currentRepositoryLock()) {
            return statusLocked();
        }
    }

    private GitWorkspaceStatusResult statusLocked() throws IOException, InterruptedException {
        Path repository = repositoryRoot();
        List<String> stagedPaths = listPaths(repository,
                List.of("git", "diff", "--cached", "--name-only", "-z", "--"));
        List<String> unstagedPaths = listPaths(repository,
                List.of("git", "diff", "--name-only", "-z", "--"));
        List<String> untrackedPaths = listPaths(repository,
                List.of("git", "ls-files", "--others", "--exclude-standard", "-z"));

        List<String> candidatePaths = candidateCodeService.allCandidateProjectPaths();
        List<String> pendingCandidatePaths = candidateCodeService.pendingCandidateProjectPaths();
        List<String> committedCandidatePaths = candidateCodeService.committedCandidateProjectPaths();
        Set<String> stagedSet = new LinkedHashSet<>(stagedPaths);
        Set<String> unstagedSet = new LinkedHashSet<>(unstagedPaths);
        List<String> unstagedCandidatePaths = pendingCandidatePaths.stream()
                .filter(path -> !stagedSet.contains(path) || unstagedSet.contains(path))
                .toList();

        GitWorkspaceStatusResult result = new GitWorkspaceStatusResult();
        result.setBranch(runGit(repository, List.of("git", "branch", "--show-current"), 0).trim());
        result.setStagedPaths(stagedPaths);
        result.setUnstagedPaths(unstagedPaths);
        result.setUntrackedPaths(untrackedPaths);
        result.setCandidatePaths(candidatePaths);
        result.setPendingCandidatePaths(pendingCandidatePaths);
        result.setCommittedCandidatePaths(committedCandidatePaths);
        result.setUnstagedCandidatePaths(unstagedCandidatePaths);
        result.setCommitScope(stagedPaths.isEmpty()
                ? "NONE"
                : unstagedCandidatePaths.isEmpty() ? "COMPLETE" : "PARTIAL");
        return result;
    }

    public GitWorkspaceStatusResult stageCandidate(String key) throws IOException, InterruptedException {
        synchronized (currentRepositoryLock()) {
            repositoryRoot();
            CandidateCodeService.MaterializedCandidate candidate = candidateCodeService.materializeCandidate(key);
            stagePathsLocked(List.of(candidate.path()));
            candidateCodeService.markStaged(candidate.key());
            return statusLocked();
        }
    }

    public GitWorkspaceStatusResult revertCandidate(String key) throws IOException, InterruptedException {
        synchronized (currentRepositoryLock()) {
            Path repository = repositoryRoot();
            String relativePath = candidateCodeService.requireCandidateProjectPath(key);
            validateRepositoryPath(repository, relativePath);
            String normalizedPath = normalizePath(relativePath);

            runGit(repository, List.of("git", "reset", "-q", "HEAD", "--", normalizedPath), 0);
            int trackedAtHead = gitExitCode(
                    repository,
                    List.of("git", "cat-file", "-e", "HEAD:" + normalizedPath)
            );
            if (trackedAtHead == 0) {
                runGit(repository, List.of(
                        "git", "restore", "--source=HEAD", "--worktree", "--", normalizedPath
                ), 0);
            } else if (trackedAtHead == 1 || trackedAtHead == 128) {
                Files.deleteIfExists(repository.resolve(normalizedPath).normalize());
            } else {
                throw new IOException("Unable to determine whether the candidate file exists at HEAD.");
            }

            candidateCodeService.discardCandidate(key);
            return statusLocked();
        }
    }

    public void stagePaths(Collection<String> relativePaths) throws IOException, InterruptedException {
        synchronized (currentRepositoryLock()) {
            stagePathsLocked(relativePaths);
        }
    }

    public void replaceStagedPaths(Collection<String> relativePaths) throws IOException, InterruptedException {
        synchronized (currentRepositoryLock()) {
            Path repository = repositoryRoot();
            runGit(repository, List.of("git", "reset", "-q", "HEAD", "--", "."), 0);
            stagePathsLocked(relativePaths);
        }
    }

    public Map<String, String> readIndexContents(Collection<String> relativePaths)
            throws IOException, InterruptedException {
        synchronized (currentRepositoryLock()) {
            Path repository = repositoryRoot();
            Map<String, String> contents = new java.util.LinkedHashMap<>();
            for (String relativePath : new LinkedHashSet<>(relativePaths)) {
                validateRepositoryPath(repository, relativePath);
                String normalizedPath = normalizePath(relativePath);
                String indexEntry = runGit(repository, List.of(
                        "git", "ls-files", "--stage", "--", normalizedPath
                ), 0);
                if (indexEntry.isBlank()) {
                    contents.put(normalizedPath, null);
                } else {
                    contents.put(normalizedPath, runGit(repository, List.of(
                            "git", "show", ":" + normalizedPath
                    ), 0));
                }
            }
            return contents;
        }
    }

    private void stagePathsLocked(Collection<String> relativePaths) throws IOException, InterruptedException {
        if (relativePaths == null) return;
        Path repository = repositoryRoot();
        for (String relativePath : new LinkedHashSet<>(relativePaths)) {
            validateRepositoryPath(repository, relativePath);
            runGit(repository, List.of("git", "add", "-A", "--", normalizePath(relativePath)), 0);
        }
    }

    public String commit(String message) throws IOException, InterruptedException {
        synchronized (currentRepositoryLock()) {
            Path repository = repositoryRoot();
            String normalizedMessage = normalizeCommitMessage(message);
            runGit(repository, List.of(
                    "git",
                    "-c", "user.name=FeatX",
                    "-c", "user.email=featx@localhost",
                    "commit", "-m", normalizedMessage
            ), 0);
            return runGit(repository, List.of("git", "rev-parse", "HEAD"), 0).trim();
        }
    }

    public GitWorkspaceStatusResult discardUncommittedChanges() throws IOException, InterruptedException {
        synchronized (currentRepositoryLock()) {
            Path repository = repositoryRoot();
            runGit(repository, List.of("git", "restore", "--source=HEAD", "--staged", "--worktree", "--", "."), 0);
            runGit(repository, List.of("git", "clean", "-fd", "--", "."), 0);
            candidateCodeService.discardCandidateState();
            return statusLocked();
        }
    }

    private Object currentRepositoryLock() {
        Integer repositoryId = ProjectState.getInstance().getRepoId();
        if (repositoryId == null) {
            throw new IllegalStateException("No project is currently selected.");
        }
        return repositoryLocks.computeIfAbsent(repositoryId, ignored -> new Object());
    }

    private Path repositoryRoot() throws IOException, InterruptedException {
        String projectPath = ProjectState.getInstance().getProjectPath();
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalStateException("No project is currently selected.");
        }
        Path expectedRoot = Path.of(projectPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(expectedRoot)) {
            throw new IllegalStateException("The selected project directory does not exist.");
        }
        Path gitMetadata = expectedRoot.resolve(".git");
        if (!Files.isDirectory(gitMetadata) && !Files.isRegularFile(gitMetadata)) {
            initializeLegacyRepository(expectedRoot);
        }
        Path actualRoot = Path.of(runGit(
                expectedRoot,
                List.of("git", "rev-parse", "--show-toplevel"),
                0
        ).trim()).toRealPath();
        if (!expectedRoot.toRealPath().equals(actualRoot)) {
            throw new IllegalStateException("Git repository root does not match the selected project directory.");
        }
        return actualRoot;
    }

    private void initializeLegacyRepository(Path repository) throws IOException, InterruptedException {
        runGit(repository, List.of("git", "init", "-b", "main"), 0);
        Path excludeFile = repository.resolve(".git/info/exclude");
        Files.createDirectories(excludeFile.getParent());
        List<String> existingLines = Files.exists(excludeFile)
                ? Files.readAllLines(excludeFile, StandardCharsets.UTF_8)
                : new ArrayList<>();
        List<String> generatedPatterns = List.of("/preprocess1/", "/delombok/", "/preprocess2/");
        StringBuilder additions = new StringBuilder();
        for (String pattern : generatedPatterns) {
            if (!existingLines.contains(pattern)) {
                additions.append(pattern).append('\n');
            }
        }
        if (!additions.isEmpty()) {
            Files.writeString(
                    excludeFile,
                    additions,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND
            );
        }
        runGit(repository, List.of("git", "add", "-A", "--", "."), 0);
        runGit(repository, List.of(
                "git",
                "-c", "user.name=FeatX",
                "-c", "user.email=featx@localhost",
                "commit", "--allow-empty", "-m", "FeatX legacy import baseline"
        ), 0);
        runGit(repository, List.of("git", "checkout", "-B", "featx-dev/main"), 0);
    }

    private List<String> listPaths(Path repository, List<String> command) throws IOException, InterruptedException {
        String output = runGit(repository, command, 0);
        List<String> paths = new ArrayList<>();
        for (String path : output.split("\\u0000")) {
            if (path == null || path.isBlank()) {
                continue;
            }
            String normalized = normalizePath(path);
            if (!isGeneratedPath(normalized)) {
                paths.add(normalized);
            }
        }
        return paths.stream().distinct().sorted().toList();
    }

    private void validateRepositoryPath(Path repository, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Repository file path is required.");
        }
        String normalized = normalizePath(relativePath);
        if (normalized.startsWith("/") || normalized.contains("../")) {
            throw new IllegalArgumentException("Invalid repository file path: " + relativePath);
        }
        Path resolved = repository.resolve(normalized).normalize();
        if (!resolved.startsWith(repository)) {
            throw new IllegalArgumentException("Invalid repository file path: " + relativePath);
        }
    }

    private boolean isGeneratedPath(String relativePath) {
        for (Path part : Path.of(relativePath)) {
            if (GENERATED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    private String normalizeCommitMessage(String message) {
        String normalized = message == null ? "" : message.replaceAll("[\\r\\n]+", " ").trim();
        if (normalized.isBlank()) {
            return "FeatX: apply code changes";
        }
        return normalized.length() > 200 ? normalized.substring(0, 200) : normalized;
    }

    private String runGit(Path workingDirectory, List<String> command, int... acceptedExitCodes)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        boolean exited = process.waitFor(120, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!exited) {
            throw new IOException("Git command timed out.\n" + output);
        }
        int exitCode = process.exitValue();
        if (Arrays.stream(acceptedExitCodes).noneMatch(code -> code == exitCode)) {
            throw new IOException("Git command failed with exit code " + exitCode + "\n" + output);
        }
        return output;
    }

    private int gitExitCode(Path workingDirectory, List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        boolean exited = process.waitFor(120, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            throw new IOException("Git command timed out.");
        }
        process.getInputStream().readAllBytes();
        return process.exitValue();
    }

}
