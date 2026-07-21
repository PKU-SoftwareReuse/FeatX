package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.dao.repository.ModuleRepository;
import cn.edu.pku.lixutian.dto.result.ModuleResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeMapServiceTest {
    @Test
    void moduleCacheIsPartitionedAndInvalidatedByRepository() {
        ModuleRepository repository = mock(ModuleRepository.class);
        when(repository.findByRepo_Id(801)).thenReturn(List.of());
        when(repository.findByRepo_Id(802)).thenReturn(List.of());
        CodeMapService service = new CodeMapService();
        ReflectionTestUtils.setField(service, "moduleRepository", repository);

        List<ModuleResult> first = service.readFeatureFromDatabase(801);
        List<ModuleResult> firstAgain = service.readFeatureFromDatabase(801);
        List<ModuleResult> second = service.readFeatureFromDatabase(802);

        assertSame(first, firstAgain);
        assertNotSame(first, second);
        verify(repository, times(1)).findByRepo_Id(801);
        verify(repository, times(1)).findByRepo_Id(802);

        service.invalidateRepository(801);
        assertNotSame(first, service.readFeatureFromDatabase(801));
        verify(repository, times(2)).findByRepo_Id(801);
    }
}
