package com.mycode.service;

import com.mycode.config.ProjectState;
import com.mycode.graph.CallGraph;
import com.mycode.graph.ClassGraph;
import com.mycode.graph.ReferenceGraph;
import com.mycode.graph.SKG;
import com.mycode.graph.softwareGraph.vertex.VertexMap;
import com.mycode.helper.PreprocessHelper;
import com.mycode.helper.CodeDiffHelper;
import com.github.javaparser.ParseException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.concurrent.ConcurrentHashMap;


@Service
public class ProcessService {
    private final ConcurrentHashMap<Integer, Object> repositoryBuildLocks = new ConcurrentHashMap<>();

    public void process() throws ParseException, IOException, InterruptedException {
        ProjectState project = ProjectState.getInstance();
        Integer repositoryId = project.getRepoId();
        if (repositoryId == null) {
            throw new IllegalStateException("No project is currently selected.");
        }
        Object buildLock = repositoryBuildLocks.computeIfAbsent(repositoryId, ignored -> new Object());
        synchronized (buildLock) {
            SKG existing = SKG.findInstance().orElse(null);
            if (existing != null && existing.isBuilt() && !project.isForcePreprocessOption()) {
                return;
            }

            System.out.println("=-=-=-=-=-=-=-= Parse All Files =-=-=-=-=-=-=-=");
            NodeList<CompilationUnit> units = PreprocessHelper.parseAllFiles();

            System.out.println("=-=-=-=-=-=-=-= Build All Graph =-=-=-=-=-=-=-=");
            SKG skg = SKG.getNewInstance();
            skg.build(units);
            skg.cluster();
            project.setForcePreprocessOption(false);
        }
    }

    /** Rebuilds every Java preprocessing stage and the in-memory static graph from confirmed source files. */
    public void rebuildAfterConfirmedSourceChange() throws ParseException, IOException, InterruptedException {
        ProjectState project = ProjectState.getInstance();
        if (!project.isJava()) {
            return;
        }
        project.setForcePreprocessOption(true);
        process();
    }

    public void clearRepository(Integer repositoryId) {
        SKG.clearRepository(repositoryId);
        VertexMap.clearRepository(repositoryId);
        ClassGraph.clearRepository(repositoryId);
        ReferenceGraph.clearRepository(repositoryId);
        CallGraph.clearRepository(repositoryId);
        CodeDiffHelper.clearRepository(repositoryId);
        repositoryBuildLocks.remove(repositoryId);
    }
}
