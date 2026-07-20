package cn.edu.pku.lixutian.controller;

import cn.edu.pku.lixutian.config.ClusterState;
import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dto.request.AddOrModifyRequest;
import cn.edu.pku.lixutian.dto.result.ModuleResult;
import cn.edu.pku.lixutian.dto.result.AgentRunStartResult;
import cn.edu.pku.lixutian.service.CodeMapService;
import cn.edu.pku.lixutian.service.code.AgentRunContext;
import cn.edu.pku.lixutian.service.code.AgentRunRegistry;
import com.github.javaparser.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/feature")
public class FeatureController {
    @Autowired
    private CodeMapService codemapService;

    @Autowired
    private LlmController llmController;

    @Autowired
    private AgentRunRegistry agentRunRegistry;

    @GetMapping("/get")
    public List<ModuleResult> getFeature() {
        return codemapService.readFeatureFromDatabase(ProjectState.getInstance().getRepoId());
    }

    public void select(Integer featureId) {
        codemapService.selectFeature(featureId);
    }

    @PostMapping("/modify")
    public AgentRunStartResult modify(@RequestBody AddOrModifyRequest request) throws IOException, InterruptedException {
        return llmController.modifyFeature(request);
    }

    @PostMapping("/add")
    public AgentRunStartResult add(@RequestBody AddOrModifyRequest request) throws IOException, InterruptedException {
        return llmController.addFeature(request);
    }

    @PostMapping("/delete")
    public void delete(@RequestBody AddOrModifyRequest request) throws IOException, InterruptedException {
        llmController.deleteFeature(request);
    }

    @PostMapping("/confirm/delete")
    public void deleteConfirm() throws ParseException, IOException, InterruptedException {
        Integer featureId = ClusterState.getInstance().getCandidateFeature().getFeatureId();
        codemapService.deleteFeatureFromMemoryAndDatabase(featureId);
    }

    @PostMapping("/confirm/modify")
    public void modifyConfirm(@RequestParam String runId)
            throws ParseException, IOException, InterruptedException {
        AgentRunContext run = agentRunRegistry.requireCompleted(runId);
        codemapService.modifyFeatureFromMemoryAndDatabase(run.featureId(), run.newRequest(), run.language());
        agentRunRegistry.clear();
    }

    @PostMapping("/confirm/add")
    public Integer addConfirm(@RequestParam String runId)
            throws ParseException, IOException, InterruptedException {
        AgentRunContext run = agentRunRegistry.requireCompleted(runId);
        Integer featureId = codemapService.addFeatureFromMemoryAndDatabase(
                run.moduleId(),
                run.newRequest(),
                run.language()
        );
        agentRunRegistry.clear();
        return featureId;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleOperationConflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }

}
