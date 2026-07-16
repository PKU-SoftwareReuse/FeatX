package cn.edu.pku.lixutian.helper;

import cn.edu.pku.lixutian.service.code.ModifyAgentService;
import cn.edu.pku.lixutian.graph.SKG;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.Vertex;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.VertexMap;
import cn.edu.pku.lixutian.helper.graphAggregationHelper.ContextHelper;
import cn.edu.pku.lixutian.helper.graphAggregationHelper.DeleteHelper;
import cn.edu.pku.lixutian.helper.graphAggregationHelper.GraphAggregationHelper;
//import cn.edu.pku.lixutian.helper.graphAggregationHelper.MemoryBasedDeleteHelper;
import cn.edu.pku.lixutian.helper.graphAggregationHelper.OriginHelper;
import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.util.*;

public class CodeDiffHelper {

    // 静态缓存，用于存储新生成的代码
    private static final Map<String, String> newFeatureCodeCache = new HashMap<>();

    /**
     * 添加新生成的代码到缓存
     */
    public static void addNewFeatureCode(String classId, String code) {
        System.out.println("=== Debug: Adding to cache - classId: " + classId + ", code length: " + (code != null ? code.length() : 0) + " ===");
        newFeatureCodeCache.put(classId, code);
        System.out.println("✓ Cache now contains: " + newFeatureCodeCache.keySet());
    }

    /**
     * 从缓存中获取新生成的代码
     */
    public static String getNewFeatureCode(String classId) {
        // 首先尝试直接匹配
        String cachedCode = newFeatureCodeCache.get(classId);
        if (cachedCode != null) {
            return cachedCode;
        }

        // 如果直接匹配失败，尝试模糊匹配（查找以classId结尾的完整类名）
        for (Map.Entry<String, String> entry : newFeatureCodeCache.entrySet()) {
            String fullClassName = entry.getKey();
            if (fullClassName.endsWith("." + classId) || fullClassName.equals(classId)) {
                System.out.println("=== Debug: Found fuzzy match - " + fullClassName + " for " + classId + " ===");
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * 清空新生成代码的缓存
     */
    public static void clearNewFeatureCodeCache() {
        newFeatureCodeCache.clear();
    }

    public static String generateDeleteDiff(SKG skg, Vertex<TypeDeclaration<?>> topVertex, Set<Integer> clusterIds) {
        SKG slicedGraph = skg.getSlicedGraphByClass(topVertex);
        GraphAggregationHelper originHelper = new OriginHelper(slicedGraph, topVertex);
        GraphAggregationHelper debloatedHelper = new DeleteHelper(slicedGraph, topVertex, clusterIds);

        String originalCode = originHelper.generateCode();
        String debloatedCode = debloatedHelper.generateCode();

        return generateDiffByCode(originalCode, debloatedCode, topVertex.getId());
    }

//    /**
//     * 基于内存数据生成删除差异
//     * 完全从VertexMap和SKG实例中获取数据，不依赖图数据库查询
//     */
//    public static String generateMemoryBasedDeleteDiff(Vertex<TypeDeclaration<?>> topVertex, Set<Integer> clusterIds) {
//        // 使用基于内存的删除辅助类
//        MemoryBasedDeleteHelper memoryBasedDeleteHelper = new MemoryBasedDeleteHelper(topVertex, clusterIds);
//        String debloatedCode = memoryBasedDeleteHelper.generateCode();
//
//        // 生成原始代码（使用现有的OriginHelper）
//        SKG slicedGraph = SKG.getInstance().getSlicedGraphByClass(topVertex);
//        GraphAggregationHelper originHelper = new OriginHelper(slicedGraph, topVertex);
//        String originalCode = originHelper.generateCode();
//
//        return generateDiffByCode(originalCode, debloatedCode, topVertex.getId());
//    }

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

    public static String generateNewDiff(SKG skg, String classId) {
        VertexMap vertexMap = VertexMap.getInstance();
        String originalCode;
        try {
            Vertex<TypeDeclaration<?>> classVertex = vertexMap.getClassDeclaration(classId);

            SKG slicedGraph = skg.getSlicedGraphByClass(classVertex);
            GraphAggregationHelper originHelper = new OriginHelper(slicedGraph, classVertex);

            originalCode = originHelper.generateCode();
        } catch (Exception e) {
            originalCode = "";
        }

        String newCode = ModifyAgentService.modificationMap.get(classId);
        if (newCode == null) {
            newCode = originalCode;
        }

        return generateDiffByCode(originalCode, newCode, classId);
    }

    public static String generateNewFeatureCode(String classId) {
        System.out.println("=== Debug: generateNewFeatureCode called for classId: " + classId + " ===");

        // 从新生成代码缓存中获取
        String cachedCode = getNewFeatureCode(classId);
        if (cachedCode != null) {
            System.out.println("✓ Found in CodeDiffHelper cache");
            // 返回diff格式，与deleteByClass保持一致
            return generateDiffByCode("", cachedCode, classId);
        }

        // 如果缓存中没有找到，尝试从VertexMap中获取
        VertexMap vertexMap = VertexMap.getInstance();
        if (vertexMap != null) {
            try {
                Vertex<TypeDeclaration<?>> classVertex = vertexMap.getClassDeclaration(classId);
                if (classVertex != null && classVertex.getDeclaration() != null) {
                    System.out.println("✓ Found in VertexMap");
                    String classCode = classVertex.getDeclaration().toString();
                    // 返回diff格式，与deleteByClass保持一致
                    return generateDiffByCode("", classCode, classId);
                }
            } catch (Exception e) {
                System.out.println("✗ Error accessing VertexMap: " + e.getMessage());
            }
        }

        System.out.println("✗ Not found in any cache");
        System.out.println("=== Debug: CodeDiffHelper cache keys: " + newFeatureCodeCache.keySet() + " ===");
        // 返回diff格式的错误信息
        return generateDiffByCode("", "// Class not found in memory structures: " + classId, classId);
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
