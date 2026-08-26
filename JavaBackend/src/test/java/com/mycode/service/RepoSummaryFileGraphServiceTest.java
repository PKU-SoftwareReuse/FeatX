package com.mycode.service;

import com.mycode.config.ProjectState;
import com.mycode.dao.CodeMap;
import com.mycode.dao.repository.CodeMapRepository;
import com.mycode.dto.result.FeatureGraphResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RepoSummaryFileGraphServiceTest {
    @Test
    void buildsJavaFeatureGraphAtFileGranularity(@TempDir Path projectRoot) throws Exception {
        Path sourceRoot = projectRoot.resolve("src/main/java");
        Path firstFile = sourceRoot.resolve("example/First.java");
        Path secondFile = sourceRoot.resolve("example/Second.java");
        Files.createDirectories(firstFile.getParent());
        Files.writeString(firstFile, "package example; class First {}\n");
        Files.writeString(secondFile, "package example; class Second {}\n");

        Path outputDirectory = Files.createDirectory(projectRoot.resolve("summary"));
        Files.writeString(outputDirectory.resolve("method.csv"), String.join("\n",
                "method_signature,file_path,class_name",
                "example.First.run(  )," + firstFile + ",example.First",
                "example.First.help()," + firstFile + ",example.First",
                "example.Second.call()," + secondFile + ",example.Second",
                "example.Outside.skip()," + sourceRoot.resolve("example/Outside.java") + ",example.Outside"
        ));
        Files.writeString(outputDirectory.resolve("file_adj_matrix.csv"), String.join("\n",
                ",example.First,example.Second,example.Outside",
                "example.First,0,1,1",
                "example.Second,0,0,1",
                "example.Outside,1,0,0"
        ));

        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "JAVA");
        ProjectState.getInstance().setRepoId(901);
        CodeMapRepository repository = mock(CodeMapRepository.class);
        when(repository.findByFeature_Id(41)).thenReturn(List.of(
                codeMap("example.First.run()"),
                codeMap("example.First.help()"),
                codeMap("example.Second.call()")
        ));

        FeatureGraphResult graph = new RepoSummaryFileGraphService(repository)
                .getFeatureFileGraph(41, outputDirectory);

        Map<String, FeatureGraphResult.Node> nodes = graph.getNodes().stream()
                .collect(Collectors.toMap(FeatureGraphResult.Node::getId, Function.identity()));
        assertEquals(Set.of(
                "src/main/java/example/First.java",
                "src/main/java/example/Second.java"
        ), nodes.keySet());
        assertEquals(
                List.of("example.First.run()", "example.First.help()"),
                nodes.get("src/main/java/example/First.java").getMethods()
        );
        assertEquals(
                Set.of("src/main/java/example/First.java->src/main/java/example/Second.java"),
                graph.getEdges().stream()
                        .map(edge -> edge.getFrom() + "->" + edge.getTo())
                        .collect(Collectors.toSet())
        );
    }

    @Test
    void readsAdjacencyEdgesForArbitraryCandidateFiles(@TempDir Path projectRoot) throws Exception {
        Path sourceRoot = projectRoot.resolve("src/main/java");
        Path firstFile = sourceRoot.resolve("example/First.java");
        Path secondFile = sourceRoot.resolve("example/Second.java");
        Files.createDirectories(firstFile.getParent());
        Files.writeString(firstFile, "package example; class First {}\n");
        Files.writeString(secondFile, "package example; class Second {}\n");

        Path outputDirectory = Files.createDirectory(projectRoot.resolve("summary"));
        Files.writeString(outputDirectory.resolve("file_adj_matrix.csv"), String.join("\n",
                ",example.First,example.Second",
                "example.First,0,1",
                "example.Second,1,0"
        ));

        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "JAVA");
        ProjectState.getInstance().setRepoId(903);
        Set<FeatureGraphResult.Edge> edges = new RepoSummaryFileGraphService(mock(CodeMapRepository.class))
                .getFileAdjacencyEdges(
                        List.of(
                                "src/main/java/example/First.java",
                                "src/main/java/example/Second.java"
                        ),
                        outputDirectory
                );

        assertEquals(
                Set.of(
                        "src/main/java/example/First.java->src/main/java/example/Second.java",
                        "src/main/java/example/Second.java->src/main/java/example/First.java"
                ),
                edges.stream()
                        .map(edge -> edge.getFrom() + "->" + edge.getTo())
                        .collect(Collectors.toSet())
        );
    }

    @Test
    void buildsPythonFeatureGraphWithTheSameFileProtocol(@TempDir Path projectRoot) throws Exception {
        Path sourceRoot = projectRoot.resolve("src/main/python");
        Path firstFile = sourceRoot.resolve("sample/first.py");
        Path secondFile = sourceRoot.resolve("sample/second.py");
        Files.createDirectories(firstFile.getParent());
        Files.writeString(firstFile, "def run(value, flag):\n    return value\n");
        Files.writeString(secondFile, "def call():\n    return None\n");

        Path outputDirectory = Files.createDirectory(projectRoot.resolve("summary"));
        Files.writeString(outputDirectory.resolve("method_file_map.csv"), String.join("\n",
                "method_name,func_file",
                "\"sample.first.run(value, flag)\",sample/first.py",
                "sample.first.help(),sample/first.py",
                "sample.second.call(),sample/second.py",
                "sample.outside.skip(),sample/outside.py"
        ));
        Files.writeString(outputDirectory.resolve("file_adj_matrix.csv"), String.join("\n",
                ",sample/first.py,sample/second.py,sample/outside.py",
                "sample/first.py,0,1,1",
                "sample/second.py,0,0,1",
                "sample/outside.py,1,0,0"
        ));

        ProjectState.getInstance().setProjectPath(projectRoot.toString(), "PYTHON");
        ProjectState.getInstance().setRepoId(902);
        CodeMapRepository repository = mock(CodeMapRepository.class);
        when(repository.findByFeature_Id(42)).thenReturn(List.of(
                codeMap("sample.first.run(value, flag)"),
                codeMap("sample.first.help()"),
                codeMap("sample.second.call()")
        ));

        FeatureGraphResult graph = new RepoSummaryFileGraphService(repository)
                .getFeatureFileGraph(42, outputDirectory);

        Map<String, FeatureGraphResult.Node> nodes = graph.getNodes().stream()
                .collect(Collectors.toMap(FeatureGraphResult.Node::getId, Function.identity()));
        assertEquals(Set.of(
                "src/main/python/sample/first.py",
                "src/main/python/sample/second.py"
        ), nodes.keySet());
        assertEquals(
                List.of("sample.first.run(value, flag)", "sample.first.help()"),
                nodes.get("src/main/python/sample/first.py").getMethods()
        );
        assertEquals(
                Set.of("src/main/python/sample/first.py->src/main/python/sample/second.py"),
                graph.getEdges().stream()
                        .map(edge -> edge.getFrom() + "->" + edge.getTo())
                        .collect(Collectors.toSet())
        );
    }

    private static CodeMap codeMap(String methodName) {
        CodeMap codeMap = new CodeMap();
        codeMap.setMethodName(methodName);
        return codeMap;
    }
}
