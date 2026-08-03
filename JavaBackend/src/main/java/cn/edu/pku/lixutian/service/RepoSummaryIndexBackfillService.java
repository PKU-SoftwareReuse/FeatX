package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.LtmConfig;
import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dao.ProjectInfo;
import cn.edu.pku.lixutian.dao.repository.ProjectInfoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/** Backfills indexes created before persistent embedding-cache warmup was available. */
@Service
public class RepoSummaryIndexBackfillService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RepoSummaryIndexBackfillService.class);

    private final ProjectInfoRepository projectInfoRepository;
    private final ProcessService processService;
    private final CodeMapService codeMapService;
    private final RepoSummaryIndexService indexService;
    private final boolean enabled;
    private final AtomicBoolean scheduled = new AtomicBoolean();

    public RepoSummaryIndexBackfillService(
            ProjectInfoRepository projectInfoRepository,
            ProcessService processService,
            CodeMapService codeMapService,
            RepoSummaryIndexService indexService,
            @Value("${featx.index-backfill.enabled:true}") boolean enabled
    ) {
        this.projectInfoRepository = projectInfoRepository;
        this.processService = processService;
        this.codeMapService = codeMapService;
        this.indexService = indexService;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scheduleExistingRepositoryBackfill() {
        if (!enabled || !scheduled.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(this::backfillExistingRepositories, "reposummary-index-backfill");
        worker.setDaemon(true);
        worker.start();
    }

    void backfillExistingRepositories() {
        for (ProjectInfo projectInfo : projectInfoRepository.findAllByArchivedFalseOrderByIdAsc()) {
            Integer repositoryId = projectInfo.getId();
            if (repositoryId == null || !Boolean.TRUE.equals(projectInfo.getSummaryFlag())) {
                continue;
            }
            try {
                if (indexService.isRepositoryIndexReady(repositoryId)) {
                    LOGGER.info("RepoSummary index cache is already ready for repository {}", repositoryId);
                    continue;
                }
                backfillProject(projectInfo);
                LOGGER.info("RepoSummary index cache backfill completed for repository {}", repositoryId);
            } catch (Exception exception) {
                LOGGER.error("RepoSummary index cache backfill failed for repository {}", repositoryId, exception);
            }
        }
    }

    private void backfillProject(ProjectInfo projectInfo) throws Exception {
        Integer repositoryId = projectInfo.getId();
        ProjectState state = ProjectState.loadRepository(
                repositoryId,
                LtmConfig.getRepoPath() + "/" + repositoryId,
                projectInfo.getProjectType()
        );
        try (ProjectState.Scope ignored = ProjectState.bindProject("index-backfill-" + repositoryId, state)) {
            if (state.isJava()) {
                processService.process();
            }
            codeMapService.invalidateRepository(repositoryId);
            indexService.warmRepositoryIndexes(state, repositoryId);
        }
    }
}
