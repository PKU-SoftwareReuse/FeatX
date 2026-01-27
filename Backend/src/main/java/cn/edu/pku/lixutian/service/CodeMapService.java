package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.service.code.AgentService;
import cn.edu.pku.lixutian.service.code.GenerateImportLinesService;
import cn.edu.pku.lixutian.config.ClusterState;
import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.controller.CodeDiffController;
import cn.edu.pku.lixutian.dao.*;
import cn.edu.pku.lixutian.dao.Module;
import cn.edu.pku.lixutian.dao.repository.FeatureRepository;
import cn.edu.pku.lixutian.dao.repository.GraphEdgeRepository;
import cn.edu.pku.lixutian.dao.repository.ModuleRepository;
import cn.edu.pku.lixutian.dto.result.FeatureGraphResult;
import cn.edu.pku.lixutian.dto.result.FeatureResult;
import cn.edu.pku.lixutian.dto.result.ModuleResult;
import cn.edu.pku.lixutian.dao.repository.CodeMapRepository;
import cn.edu.pku.lixutian.graph.SKG;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.Vertex;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.VertexMap;
import cn.edu.pku.lixutian.helper.ListFileHelper;
import cn.edu.pku.lixutian.helper.RewriteFileHelper;
import com.github.javaparser.ParseException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CodeMapService {
    // store
    @Getter
    private static List<ModuleResult> moduleResults;

    public static boolean isBuilt = false;

    // 新增缓存字段
    public static Map<Integer, List<CodeMap>> codeMapCache = new HashMap<>();

    @Autowired
    private ModuleRepository moduleRepository;

    @Autowired
    private FeatureRepository featureRepository;

    @Autowired
    private CodeMapRepository codeMapRepository;

    @Autowired
    private GraphEdgeRepository graphEdgeRepository;

    @Autowired
    private CodeDiffController codeDiffController;

    @Autowired
    private ProcessService processService;

    @Autowired
    private GenerateImportLinesService generateImportLinesService;

    @PersistenceContext
    private EntityManager entityManager;


    public List<ModuleResult> readFeatureFromDatabase(Integer repoId) {
        if (!isBuilt) {
            List<Module> modules = moduleRepository.findByRepo_Id(repoId);
            moduleResults = modules.stream().map(ModuleResult::new).collect(Collectors.toCollection(ArrayList::new));
            isBuilt = true;
        }
        return moduleResults;
    }

    public FeatureGraphResult getMaxGraph() {
        SKG maxGraph = SKG.getInstance().getMaxGraph();
        FeatureGraphResult maxFeatureGraph = new FeatureGraphResult(maxGraph);

        Set<String> allClasses = maxFeatureGraph.getNodes().stream()
                .map(node -> node.getLabel())
                .collect(Collectors.toSet());

        List<GraphEdge> graphEdges = graphEdgeRepository.findByRepo_Id(ProjectState.getInstance().getRepoId());
        Set<FeatureGraphResult.Edge> edgeSet = new HashSet<>();
        for (GraphEdge graphEdge : graphEdges) {
            String fullSrc = graphEdge.getSrc();
            String fullDest = graphEdge.getDest();

            if (allClasses.contains(fullSrc) && allClasses.contains(fullDest)) {
                edgeSet.add(new FeatureGraphResult.Edge(fullSrc, fullDest));
            }
        }
        maxFeatureGraph.setEdges(edgeSet);

        return maxFeatureGraph;
    }

    public FeatureResult getFeature(Integer featureId) {
        FeatureResult candidateFeature = null;
        for (ModuleResult moduleResult : moduleResults) {
            for (FeatureResult featureResult : moduleResult.getFeatureList()) {
                if (featureResult.getFeatureId().equals(featureId)) {
                    candidateFeature = featureResult;
                    break;
                }
            }
            if (candidateFeature != null) {
                break;
            }
        }
        return candidateFeature;
    }

    public Set<Integer> clusterMap(FeatureResult candidateFeature) {
        Set<Integer> clusterIds = new HashSet<>();
        candidateFeature.getCandidateMethods().stream().forEach(candidateMethod -> {
            candidateMethod.getLxtFull().stream().forEach(lxtFull -> {
                lxtFull.getClusterIds().stream().forEach(clusterId -> {
                    clusterIds.add(clusterId);
                });
            });
        });

        return clusterIds;
    }

    public Map<String, String> getCodeByFeatureId(Integer featureId) {
        if (featureId == null) {
            return Collections.emptyMap();
        }

        FeatureResult featureResult = getFeature(featureId);
        if (featureResult == null || featureResult.getCandidateMethods() == null) {
            return Collections.emptyMap();
        }

        Set<String> classIds = new LinkedHashSet<>();
        featureResult.getCandidateMethods().forEach(candidate -> {
            if (candidate.getLxtFull() == null) return;
            candidate.getLxtFull().forEach(full -> {
                String signature = full.getLxtFullSignature();
                if (signature == null) return;
                int leftParen = signature.indexOf('(');
                if (leftParen < 0) return;
                String beforeArgs = signature.substring(0, leftParen);
                int lastDot = beforeArgs.lastIndexOf('.');
                if (lastDot <= 0) return;
                String classId = beforeArgs.substring(0, lastDot);
                classIds.add(classId);
            });
        });

        VertexMap vertexMap = VertexMap.getInstance();
        if (vertexMap == null) {
            return Collections.emptyMap();
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (String classId : classIds) {
            Vertex<TypeDeclaration<?>> vertex = vertexMap.getClassDeclaration(classId);
            if (vertex != null && vertex.getDeclaration() != null) {
                result.put(classId, vertex.getDeclaration().toString());
            }
        }
        return result;
    }

    public Map<String, String> getCodeByModuleId(Integer moduleId) {
        if (moduleId == null || moduleResults == null) {
            return Collections.emptyMap();
        }

        Map<String, String> merged = new LinkedHashMap<>();
        moduleResults.stream()
                .filter(m -> m.getModuleId().equals(moduleId))
                .findFirst()
                .map(m -> m.getFeatureList())
                .ifPresent(features -> {
                    for (FeatureResult fr : features) {
                        Map<String, String> perFeature = getCodeByFeatureId(fr.getFeatureId());
                        perFeature.forEach(merged::putIfAbsent);
                    }
                });

        return merged;
    }

    /**
     * 永久化删除特征
     * 1. 删内存表
     * 2. 删数据库
     * 3. 删文件
     * 4. 重建SKG
     */
    public boolean deleteFeatureFromMemoryAndDatabase(Integer featureId) throws ParseException, IOException, InterruptedException {
        // 1. 删内存表
        // 2. 删数据库
        ModuleResult candidateModule = null;
        FeatureResult candidateFeature = null;
        for (ModuleResult moduleResult : moduleResults) {
            for (FeatureResult featureResult : moduleResult.getFeatureList()) {
                if (featureResult.getFeatureId().equals(featureId)) {
                    candidateFeature = featureResult;
                    candidateModule = moduleResult;
                    break;
                }
            }
            if (candidateFeature != null) {
                break;
            }
        }
        assert candidateModule != null && candidateFeature != null;
        candidateModule.getFeatureList().remove(candidateFeature);
        featureRepository.deleteById(candidateFeature.getFeatureId());
        if (candidateModule.getFeatureList().isEmpty()) {
            moduleResults.remove(candidateModule);
            moduleRepository.deleteById(candidateModule.getModuleId());
        }

        // 3. 重写文件
        FeatureGraphResult debloatGraph = getMaxGraph();
        debloatGraph.setDebloatType();
        debloatGraph.getNodes().stream()
                .filter(node -> node.getType() == "Modify")
                .forEach(node -> {
                    try {
                        RewriteFileHelper.rewriteFile(node.getId(), codeDiffController.deleteCodeByClass(node.getId()));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

        // 4. 重建SKG
        ProjectState.getInstance().setForcePreprocessOption(true);
        processService.process();

        isBuilt = false;

        return true;
    }

    public Integer modifyFeatureFromMemoryAndDatabase(Integer featureId, String newFeatureDescription) throws ParseException, IOException, InterruptedException {
        return modifyOrAddFeatureFromMemoryAndDatabase(featureId, newFeatureDescription, "modify");
    }

    public Integer addFeatureFromMemoryAndDatabase(Integer moduleId, String newFeatureDescription) throws ParseException, IOException, InterruptedException {
        return modifyOrAddFeatureFromMemoryAndDatabase(moduleId, newFeatureDescription, "add");
    }

    private Integer modifyOrAddFeatureFromMemoryAndDatabase(Integer featureOrModuleId, String newFeatureDescription, String type) throws ParseException, IOException, InterruptedException {
        Integer featureId;
        if (type.equals("modify")) {
            // 1. 改内存表
            FeatureResult candidateFeature = null;
            for (ModuleResult moduleResult : moduleResults) {
                for (FeatureResult featureResult : moduleResult.getFeatureList()) {
                    if (featureResult.getFeatureId().equals(featureOrModuleId)) {
                        candidateFeature = featureResult;
                        break;
                    }
                }
                if (candidateFeature != null) {
                    break;
                }
            }
            assert candidateFeature != null;
            candidateFeature.setFeatureDescription(newFeatureDescription);

            // 2. 改数据库
            Feature candidateFeatureEntity = featureRepository.findById(candidateFeature.getFeatureId()).get();
            candidateFeatureEntity.setFeatureDesc(newFeatureDescription);
            featureRepository.save(candidateFeatureEntity);
            featureId = featureOrModuleId;
        } else if (type.equals("add")) {
            // 1. 改内存表
            FeatureResult candidateFeature = null;
            for (ModuleResult moduleResult : moduleResults) {
                if (moduleResult.getModuleId() == featureOrModuleId) {
                    candidateFeature = new FeatureResult();
                    candidateFeature.setFeatureDescription(newFeatureDescription);
                    moduleResult.getFeatureList().add(candidateFeature);
                    break;
                }
            }

            // 2. 改数据库
            Feature candidateFeatureEntity = new Feature();
            Module module = entityManager.getReference(Module.class, featureOrModuleId);
            candidateFeatureEntity.setModule(module);
            candidateFeatureEntity.setFeatureDesc(newFeatureDescription);
            candidateFeatureEntity = featureRepository.save(candidateFeatureEntity);
            featureId = candidateFeatureEntity.getId();
        } else {
            throw new UnsupportedOperationException("非法操作");
        }


        // 3. 生成import语句、更新邻接表、重写文件
        for (Map.Entry<String, String> entry : AgentService.modificationMap.entrySet()) {
            try {

                String allFiles = "";
                List<String> javaFiles = ListFileHelper.findJavaFiles(ProjectState.getInstance().getSrcPath());
                for (String javaFile : javaFiles) {
                    allFiles += javaFile + "\n";
                }
                // 3.1 生成import语句
                List<String> importLines = generateImportLinesService.generate(entry.getKey(), entry.getValue(), allFiles);
                // 3.2 更新邻接表
                // 3.2.1 出边
                Set<String> imports = new HashSet<>();
                for (String importLine : importLines) {
                    if (importLine.startsWith("import ")) {
                        // 去掉 "import " 和 ";"，再 trim 一下
                        String className = importLine
                                .replace("import ", "")
                                .replace(";", "")
                                .trim();
                        if (javaFiles.contains(className)) {
                            imports.add(className);
                        }
                    }
                }
                String currentFile = entry.getKey();
                String currentPackage = currentFile.substring(0, currentFile.lastIndexOf('.'));
                // 3.2.2 出入双向边
                Set<String> samePackageClasses = javaFiles.stream()
                        .filter(name -> !name.equals(currentFile)) // 排除当前类自己
                        .filter(name -> {
                            int lastDot = name.lastIndexOf('.');
                            if (lastDot == -1) return false;
                            String pkg = name.substring(0, lastDot);
                            return pkg.equals(currentPackage); // 必须完全相同包名，忽略子包
                        })
                        .collect(Collectors.toSet());
                // 3.2.3 写数据库
                for (String importClass : imports) {
                    GraphEdge newGraphEdge = new GraphEdge();
                    newGraphEdge.setSrc(currentFile);
                    newGraphEdge.setDest(importClass);
                    ProjectInfo repoRef = entityManager.getReference(ProjectInfo.class, ProjectState.getInstance().getRepoId());
                    newGraphEdge.setRepo(repoRef);
                    if (!graphEdgeRepository.existsByObject(newGraphEdge)) {
                        graphEdgeRepository.save(newGraphEdge);
                    }
                }
                for (String samePackageClass : samePackageClasses) {
                    GraphEdge newGraphEdge1 = new GraphEdge();
                    GraphEdge newGraphEdge2 = new GraphEdge();
                    newGraphEdge1.setSrc(currentFile);
                    newGraphEdge1.setDest(samePackageClass);
                    newGraphEdge2.setSrc(samePackageClass);
                    newGraphEdge2.setDest(currentFile);
                    ProjectInfo repoRef = entityManager.getReference(ProjectInfo.class, ProjectState.getInstance().getRepoId());
                    newGraphEdge1.setRepo(repoRef);
                    newGraphEdge2.setRepo(repoRef);
                    if (!graphEdgeRepository.existsByObject(newGraphEdge1)) {
                        graphEdgeRepository.save(newGraphEdge1);
                    }
                    if (!graphEdgeRepository.existsByObject(newGraphEdge2)) {
                        graphEdgeRepository.save(newGraphEdge2);
                    }
                }
                // 3.3 重写文件
                RewriteFileHelper.rewriteFile(entry.getKey(), entry.getValue(), importLines);

                // 3.4 写入CodeMap数据库
                try {
                    // 用 JavaParser 解析
                    CompilationUnit cu = StaticJavaParser.parse(entry.getValue());

                    // 访问所有方法
                    cu.findAll(MethodDeclaration.class).forEach(cd -> {
                        String methodName = cd.getNameAsString();
                        methodName += "( ";
                        for (int i = 0; i < cd.getParameters().size(); i++) {
                            Parameter parameter = cd.getParameters().get(i);
                            if (i != 0) {
                                methodName += ", ";
                            }
                            methodName += parameter.getType().asString();
                            methodName += " ";
                            methodName += parameter.getNameAsString();
                        }
                        methodName += " )";
                        String fullName = currentFile + "." + methodName;
                        CodeMap newCodeMap = new CodeMap();
                        Feature feature = entityManager.getReference(Feature.class, featureId);
                        newCodeMap.setFeature(feature);
                        newCodeMap.setMethodName(fullName);
                        if (!codeMapRepository.existsByMethodName(fullName)) {
                            codeMapRepository.save(newCodeMap);
                        }
                    });

                } catch (Exception e) {
                    throw new RuntimeException("Java 代码解析或格式化失败: " + e.getMessage(), e);
                }


            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        // 4. 重建SKG
        ProjectState.getInstance().setForcePreprocessOption(true);
        processService.process();

        isBuilt = false;

        return featureId;
    }


    public void selectFeature(Integer featureId) {
        FeatureResult candidateFeature;
        Set<Integer> clusterIds;
        if (featureId == null) {
            candidateFeature = null;
            clusterIds = null;
        } else {
            candidateFeature = getFeature(featureId);
            clusterIds = clusterMap(candidateFeature);
        }
        ClusterState.getInstance().setCandidateFeature(candidateFeature);
        ClusterState.getInstance().setClusterIds(clusterIds);
    }

    public List<Feature> getFeaturesByModuleId(Integer moduleId) {
        return featureRepository.findByModule_Id(moduleId);
    }

}
