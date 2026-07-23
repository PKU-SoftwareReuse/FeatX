package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.graph.CallGraph;
import cn.edu.pku.lixutian.graph.ClassGraph;
import cn.edu.pku.lixutian.graph.ReferenceGraph;
import cn.edu.pku.lixutian.graph.SKG;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.VertexMap;
import cn.edu.pku.lixutian.helper.PreprocessHelper;
import cn.edu.pku.lixutian.helper.CodeDiffHelper;
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
