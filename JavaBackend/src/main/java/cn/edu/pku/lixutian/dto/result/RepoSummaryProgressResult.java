package cn.edu.pku.lixutian.dto.result;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class RepoSummaryProgressResult {
    private Integer repoId;
    private String status;
    private String currentStage;
    private String messageKey;
    private Map<String, Object> messageArgs = new LinkedHashMap<>();
    private long startedAtEpochMs;
    private long updatedAtEpochMs;
    private Long finishedAtEpochMs;
    private long elapsedMs;
    private int currentStep;
    private int totalSteps;
    private Double percent;
    private List<StepResult> steps = new ArrayList<>();

    @Getter
    @Setter
    public static class StepResult {
        private String id;
        private String status;
        private String messageKey;
        private Map<String, Object> messageArgs = new LinkedHashMap<>();
        private long startedAtEpochMs;
        private Long finishedAtEpochMs;
        private long elapsedMs;
        private Double percent;
    }
}
