package cn.edu.pku.lixutian.controller;

import cn.edu.pku.lixutian.config.ClusterState;
import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dto.request.AddOrModifyRequest;
import cn.edu.pku.lixutian.dto.result.ModuleResult;
import cn.edu.pku.lixutian.service.CodeMapService;
import cn.edu.pku.lixutian.service.code.AgentLanguage;
import com.github.javaparser.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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
    public void modify(@RequestBody AddOrModifyRequest request) throws IOException, InterruptedException {
        llmController.modifyFeature(request);
    }

    @PostMapping("/add")
    public void add(@RequestBody AddOrModifyRequest request) throws IOException, InterruptedException {
        llmController.addFeature(request);
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
    public void modifyConfirm() throws ParseException, IOException, InterruptedException {
        Integer featureId = ClusterState.getInstance().getCandidateFeature().getFeatureId();
        String featureDescription = ClusterState.getInstance().getNewFeatureDescription();
        AgentLanguage language = ClusterState.getInstance().getAgentLanguage();
        codemapService.modifyFeatureFromMemoryAndDatabase(featureId, featureDescription, language);
    }

    @PostMapping("/confirm/add")
    public Integer addConfirm() throws ParseException, IOException, InterruptedException {
        Integer moduleId = ClusterState.getInstance().getCandidateModuleId();
        String featureDescription = ClusterState.getInstance().getNewFeatureDescription();
        AgentLanguage language = ClusterState.getInstance().getAgentLanguage();
        return codemapService.addFeatureFromMemoryAndDatabase(moduleId, featureDescription, language);
    }

}
