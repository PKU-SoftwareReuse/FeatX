package cn.edu.pku.lixutian.dto.result;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class GitWorkspaceStatusResult {
    private String branch;
    private String commitScope;
    private List<String> stagedPaths = new ArrayList<>();
    private List<String> unstagedPaths = new ArrayList<>();
    private List<String> untrackedPaths = new ArrayList<>();
    private List<String> candidatePaths = new ArrayList<>();
    private List<String> pendingCandidatePaths = new ArrayList<>();
    private List<String> committedCandidatePaths = new ArrayList<>();
    private List<String> unstagedCandidatePaths = new ArrayList<>();
}
