package cn.edu.pku.lixutian.dto.result;

import cn.edu.pku.lixutian.dao.Module;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.stream.Collectors;

@Getter
@Setter
public class ModuleResult {
    private Integer moduleId;
    private String moduleDesc;
    private String moduleDescCN;
    private List<FeatureResult> featureList;

    public ModuleResult(Module module) {
        this.moduleId = module.getId();
        this.moduleDesc = module.getModuleDesc();
        this.moduleDescCN = module.getModuleDescCN();
        this.featureList = module.getFeatureList().stream().map(FeatureResult::new).collect(Collectors.toList());
    }

    public ModuleResult() {}
}
