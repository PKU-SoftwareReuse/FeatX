package com.mycode.controller;

import com.mycode.dto.request.GitCommitRequest;
import com.mycode.dto.request.StageCandidateRequest;
import com.mycode.dto.result.GitCommitResult;
import com.mycode.dto.result.GitWorkspaceStatusResult;
import com.mycode.service.FeatureGitWorkflowService;
import com.github.javaparser.ParseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/code/git")
public class FeatureGitController {
    private final FeatureGitWorkflowService workflowService;

    public FeatureGitController(FeatureGitWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @GetMapping("/status")
    public GitWorkspaceStatusResult status() throws IOException, InterruptedException {
        return workflowService.status();
    }

    @PostMapping("/stage")
    public GitWorkspaceStatusResult stage(@RequestBody StageCandidateRequest request)
            throws IOException, InterruptedException {
        if (request == null || request.getKey() == null || request.getKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Candidate file key is required.");
        }
        return workflowService.stageCandidate(request.getKey(), request.getRunId());
    }

    @PostMapping("/unstage")
    public GitWorkspaceStatusResult unstage(@RequestBody StageCandidateRequest request)
            throws IOException, InterruptedException {
        if (request == null || request.getKey() == null || request.getKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Candidate file key is required.");
        }
        return workflowService.unstageCandidate(request.getKey(), request.getRunId());
    }

    @PostMapping("/revert")
    public GitWorkspaceStatusResult revert(@RequestBody StageCandidateRequest request)
            throws IOException, InterruptedException {
        if (request == null || request.getKey() == null || request.getKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Candidate file key is required.");
        }
        return workflowService.revertCandidate(request.getKey(), request.getRunId());
    }

    @PostMapping("/commit")
    public GitCommitResult commit(@RequestBody GitCommitRequest request)
            throws IOException, InterruptedException, ParseException {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Commit request is required.");
        }
        return workflowService.commit(request.getOperation(), request.getMessage(), request.getRunId());
    }

    @PostMapping("/discard")
    public GitWorkspaceStatusResult discard() throws IOException, InterruptedException {
        return workflowService.discard();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", exception.getMessage()));
    }
}
