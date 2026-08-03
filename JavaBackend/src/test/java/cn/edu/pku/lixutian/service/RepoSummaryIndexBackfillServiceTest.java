package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.LtmConfig;
import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.dao.ProjectInfo;
import cn.edu.pku.lixutian.dao.repository.ProjectInfoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepoSummaryIndexBackfillServiceTest {
    private final ProjectInfoRepository projectInfoRepository = mock(ProjectInfoRepository.class);
    private final ProcessService processService = mock(ProcessService.class);
    private final CodeMapService codeMapService = mock(CodeMapService.class);
    private final RepoSummaryIndexService indexService = mock(RepoSummaryIndexService.class);

    @BeforeEach
    void setUp() {
        LtmConfig ltmConfig = new LtmConfig();
        ltmConfig.setRepoPath("/tmp/featx-index-backfill-test");
        ltmConfig.init();
    }

    @Test
    void backfillsOnlySummarizedRepositoriesWithMissingIndexes() throws Exception {
        ProjectInfo ready = project(12, "JAVA", true);
        ProjectInfo missing = project(13, "PYTHON", true);
        ProjectInfo stillSummarizing = project(14, "PYTHON", false);
        when(projectInfoRepository.findAllByArchivedFalseOrderByIdAsc())
                .thenReturn(List.of(ready, missing, stillSummarizing));
        when(indexService.isRepositoryIndexReady(12)).thenReturn(true);
        when(indexService.isRepositoryIndexReady(13)).thenReturn(false);

        RepoSummaryIndexBackfillService service = new RepoSummaryIndexBackfillService(
                projectInfoRepository,
                processService,
                codeMapService,
                indexService,
                true
        );
        service.backfillExistingRepositories();

        verify(indexService, never()).warmRepositoryIndexes(any(ProjectState.class), org.mockito.ArgumentMatchers.eq(12));
        verify(indexService, never()).isRepositoryIndexReady(14);
        verify(processService, never()).process();
        InOrder order = inOrder(codeMapService, indexService);
        order.verify(codeMapService).invalidateRepository(13);
        order.verify(indexService).warmRepositoryIndexes(any(ProjectState.class), org.mockito.ArgumentMatchers.eq(13));
    }

    private ProjectInfo project(int id, String type, boolean summarized) {
        ProjectInfo project = new ProjectInfo();
        project.setId(id);
        project.setProjectType(type);
        project.setSummaryFlag(summarized);
        project.setArchived(false);
        return project;
    }
}
