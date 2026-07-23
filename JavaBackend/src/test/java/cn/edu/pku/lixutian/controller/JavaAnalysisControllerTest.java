package cn.edu.pku.lixutian.controller;

import cn.edu.pku.lixutian.service.JavaImportAnalyzerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JavaAnalysisController.class)
class JavaAnalysisControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JavaImportAnalyzerService javaImportAnalyzerService;

    @Test
    void returnsCsvMatrixForRepository() throws Exception {
        when(javaImportAnalyzerService.analyzeRepository(12))
                .thenReturn(",example.Consumer,example.Provider\nexample.Consumer,0,1\nexample.Provider,0,0\n");

        mockMvc.perform(get("/analysis/java/import-matrix")
                        .param("repoId", "12"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("text/csv")))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith(",example.Consumer")));
    }
}
