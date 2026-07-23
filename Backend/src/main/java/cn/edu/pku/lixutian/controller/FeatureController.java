package cn.edu.pku.lixutian.controller;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dto.request.AddOrModifyRequest;
import cn.edu.pku.lixutian.dto.result.ModuleResult;
import cn.edu.pku.lixutian.dto.result.AgentRunStartResult;
import cn.edu.pku.lixutian.service.CodeMapService;
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
    public AgentRunStartResult delete(@RequestBody AddOrModifyRequest request) throws IOException, InterruptedException {
        return llmController.deleteFeature(request);
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
