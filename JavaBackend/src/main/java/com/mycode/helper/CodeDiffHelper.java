package com.mycode.helper;

import com.mycode.graph.SKG;
import com.mycode.graph.softwareGraph.vertex.Vertex;
import com.mycode.helper.graphAggregationHelper.ContextHelper;
import com.mycode.helper.graphAggregationHelper.DeleteHelper;
import com.mycode.helper.graphAggregationHelper.GraphAggregationHelper;
import com.mycode.helper.graphAggregationHelper.OriginHelper;
import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.*;

public class CodeDiffHelper {

    public static String generateDeleteDiff(SKG skg, Vertex<TypeDeclaration<?>> topVertex, Set<Integer> clusterIds) {
        SKG slicedGraph = skg.getSlicedGraphByClass(topVertex);
        GraphAggregationHelper originHelper = new OriginHelper(slicedGraph, topVertex);
        GraphAggregationHelper debloatedHelper = new DeleteHelper(slicedGraph, topVertex, clusterIds);

        String originalCode = originHelper.generateCode();
        String debloatedCode = debloatedHelper.generateCode();

        return generateDiffByCode(originalCode, debloatedCode, topVertex.getId());
    }

    public static boolean justifyDeleteDiff(SKG skg, Vertex<TypeDeclaration<?>> topVertex, Set<Integer> clusterIds) {
        SKG slicedGraph = skg.getSlicedGraphByClass(topVertex);
        GraphAggregationHelper originHelper = new OriginHelper(slicedGraph, topVertex);
        GraphAggregationHelper debloatedHelper = new DeleteHelper(slicedGraph, topVertex, clusterIds);

        String originalCode = originHelper.generateCode();
        String debloatedCode = debloatedHelper.generateCode();

        List<String> original = Arrays.asList(originalCode.split("\n"));
        List<String> revised = Arrays.asList(debloatedCode.split("\n"));

        Patch<String> patch = DiffUtils.diff(original, revised);

        return patch.getDeltas().isEmpty();
    }

    public static String generateContextDiff(SKG skg, Vertex<TypeDeclaration<?>> topVertex, Set<Integer> clusterIds) {
        SKG maxGraph = skg.getMaxGraph();
        SKG slicedGraph = skg.getSlicedGraphByClass(topVertex);
        GraphAggregationHelper originHelper = new OriginHelper(slicedGraph, topVertex);
        GraphAggregationHelper contextHelper = new ContextHelper(maxGraph, topVertex, clusterIds);

        String originalCode = originHelper.generateCode();
        String contextCode = contextHelper.generateCode();

        return generateDiffByCode(contextCode, contextCode, topVertex.getId());
    }

    public static String generateDiffByCode(String oldCode, String newCode, String fileName) {
        List<String> original = Arrays.asList(oldCode.split("\n"));
        List<String> revised = Arrays.asList(newCode.split("\n"));

        int maxLength = Math.max(original.size(), revised.size());

        Patch<String> patch = DiffUtils.diff(original, revised);

        List<String> unifiedDiff;

        if (patch.getDeltas().isEmpty()) {
            // fakeDiff
            unifiedDiff = new ArrayList<>();
            unifiedDiff.add("--- " + fileName);
            unifiedDiff.add("+++ " + fileName);
            unifiedDiff.add(String.format("@@ -1,%d +1,%d @@", maxLength, maxLength));
            original.forEach(line -> unifiedDiff.add(" " + line)); // 原文件内容作为 context 加进去
        } else {
            unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(
                    fileName, fileName, original, patch, maxLength
            );
        }

        return String.join("\n", unifiedDiff);
    }

}
