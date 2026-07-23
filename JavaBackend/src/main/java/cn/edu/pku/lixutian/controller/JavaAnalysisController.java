package cn.edu.pku.lixutian.controller;

import cn.edu.pku.lixutian.service.JavaImportAnalyzerService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/analysis/java")
public class JavaAnalysisController {
    private static final MediaType CSV_MEDIA_TYPE = MediaType.parseMediaType("text/csv;charset=UTF-8");

    private final JavaImportAnalyzerService javaImportAnalyzerService;

    public JavaAnalysisController(JavaImportAnalyzerService javaImportAnalyzerService) {
        this.javaImportAnalyzerService = javaImportAnalyzerService;
    }

    @GetMapping(value = "/import-matrix", produces = "text/csv")
    public ResponseEntity<String> importMatrix(@RequestParam Integer repoId) {
        try {
            return ResponseEntity.ok()
                    .contentType(CSV_MEDIA_TYPE)
                    .body(javaImportAnalyzerService.analyzeRepository(repoId));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage(), e);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("does not exist")) {
                throw new ResponseStatusException(NOT_FOUND, e.getMessage(), e);
            }
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "Java import analysis failed.", e);
        }
    }
}
