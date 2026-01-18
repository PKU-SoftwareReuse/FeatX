package cn.edu.pku.lixutian.controller;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.config.LtmConfig;
import cn.edu.pku.lixutian.dao.ProjectInfo;
import cn.edu.pku.lixutian.dao.repository.ProjectInfoRepository;
import cn.edu.pku.lixutian.dto.request.SelectProjectRequest;
import cn.edu.pku.lixutian.dto.result.ProjectInfoResult;
import cn.edu.pku.lixutian.helper.RepoSummaryHelper;
import cn.edu.pku.lixutian.service.ProcessService;
import cn.edu.pku.lixutian.service.CodeMapService;
import cn.edu.pku.lixutian.helper.StatisticHelper;
import com.github.javaparser.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/project")
public class ProjectController {
    @Autowired
    ProcessService processService;

    @Autowired
    ProjectInfoRepository projectInfoRepository;

    @Autowired
    CodeMapService codeMapService;

    @GetMapping("/getList")
    public List<ProjectInfoResult> getProjectList() {
        return projectInfoRepository.findAll()
                .stream()
                .map(ProjectInfoResult::new)
                .toList();
    }

    @PostMapping("/select")
    public void selectProject(@RequestBody SelectProjectRequest request) throws ParseException, IOException, InterruptedException {
        ProjectState state = ProjectState.getInstance();
        state.setRepoId(request.getRepoId());
        state.setProjectPath(repoId2Path(request.getRepoId()));

        // 处理项目数据
        processService.process();
        CodeMapService.isBuilt = false;

//        // 初始化缓存 - 在数据处理完成后进行
//        codeMapService.initializeCache(request.getRepoId());
    }

    private String repoId2Path(Integer repoId) {
        return LtmConfig.getRepoPath() + "/" + repoId;
    }

    @PostMapping("/upload")
    public void uploadFolder(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("paths") List<String> paths,
            @RequestParam("folderName") String folderName) throws IOException, InterruptedException {

        ProjectInfo projectInfo = new ProjectInfo();
        projectInfo.setRepoName(folderName);
        projectInfo.setDescription("New Repo");
        projectInfo.setSummaryFlag(false);
        projectInfo = projectInfoRepository.save(projectInfo);

        String repoPath = LtmConfig.getRepoPath() + "/" + projectInfo.getId();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String relativePath = paths.get(i);

            File targetFile = new File(repoPath + "/src/main/" + relativePath);
            targetFile.getParentFile().mkdirs();
            file.transferTo(targetFile);
        }

        Map<String, Integer> statisticInfo = StatisticHelper.countInRepo(repoPath + "/src/main/java");
        projectInfo.setLoc(statisticInfo.get("loc"));
        projectInfo.setNoc(statisticInfo.get("noc"));
        projectInfo.setNom(statisticInfo.get("nom"));
        projectInfo.setNof(statisticInfo.get("nof"));
        projectInfoRepository.save(projectInfo);

        RepoSummaryHelper.runRepoSummary(projectInfo.getId());
    }

    @GetMapping("/tempRepoSummary")
    public void temp() throws IOException, InterruptedException {


        String repoPath = LtmConfig.getRepoPath() + "/12";

        Map<String, Integer> statisticInfo = StatisticHelper.countInRepo(repoPath + "/src/main/java");
    }

    @PostMapping("/resummary")
    public void reSummary(@RequestBody SelectProjectRequest request) throws IOException, InterruptedException {
        ProjectInfo projectInfo = projectInfoRepository.findById(request.getRepoId()).get();
        projectInfo.setSummaryFlag(false);
        projectInfoRepository.save(projectInfo);

        RepoSummaryHelper.runRepoSummary(projectInfo.getId());
    }

}
