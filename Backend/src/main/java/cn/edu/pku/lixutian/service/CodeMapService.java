package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.service.code.AgentService;
import cn.edu.pku.lixutian.service.code.AgentLanguage;
import cn.edu.pku.lixutian.service.code.GenerateImportLinesService;
import cn.edu.pku.lixutian.config.ClusterState;
import cn.edu.pku.lixutian.config.LtmConfig;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class CodeMapService {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static class PythonMethodContainer {
        private final String methodName;
        private final String containerId;
        private final String containerType;
        private final String containerFile;
        private final String methodShortName;

        private PythonMethodContainer(
                String methodName,
                String containerId,
                String containerType,
                String containerFile,
                String methodShortName
        ) {
            this.methodName = methodName;
            this.containerId = containerId;
            this.containerType = containerType;
            this.containerFile = containerFile;
            this.methodShortName = methodShortName;
        }
    }

    private static class PythonContainerEdge {
        private final String srcContainer;
        private final String dstContainer;

        private PythonContainerEdge(String srcContainer, String dstContainer) {
            this.srcContainer = srcContainer;
            this.dstContainer = dstContainer;
        }
    }

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

    @Autowired
    private CandidateCodeService candidateCodeService;

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

    public FeatureGraphResult getPythonFeatureGraph(Integer featureId) {
        List<CodeMap> codeMaps = codeMapRepository.findByFeature_Id(featureId);
        Map<String, PythonMethodContainer> methodContainerMap = loadPythonMethodContainerMap();
        Map<String, String> methodFileMap = loadPythonMethodFileMap();
        Map<String, List<String>> containerMethods = new LinkedHashMap<>();
        Map<String, Set<String>> containerIdsByFile = new LinkedHashMap<>();
        for (CodeMap codeMap : codeMaps) {
            String methodName = codeMap.getMethodName();
            PythonMethodContainer container = resolvePythonMethodContainer(methodName, methodContainerMap, methodFileMap);
            List<String> methods = containerMethods.computeIfAbsent(container.containerId, ignored -> new ArrayList<>());
            String methodShortName = container.methodShortName == null || container.methodShortName.isBlank()
                    ? shortPythonMethodName(methodName)
                    : container.methodShortName;
            if (!methods.contains(methodShortName)) {
                methods.add(methodShortName);
            }
            containerIdsByFile
                    .computeIfAbsent(container.containerFile, ignored -> new LinkedHashSet<>())
                    .add(container.containerId);
        }

        Set<FeatureGraphResult.Node> nodes = new LinkedHashSet<>();
        containerMethods.forEach((containerId, methods) -> nodes.add(new FeatureGraphResult.Node(containerId, methods)));

        Set<FeatureGraphResult.Edge> edges = new LinkedHashSet<>();
        Set<String> selectedContainerIds = new LinkedHashSet<>(containerMethods.keySet());
        List<PythonContainerEdge> containerEdges = loadPythonContainerEdges();
        if (containerEdges != null) {
            Set<String> edgeKeys = new LinkedHashSet<>();
            for (PythonContainerEdge containerEdge : containerEdges) {
                if (!selectedContainerIds.contains(containerEdge.srcContainer)
                        || !selectedContainerIds.contains(containerEdge.dstContainer)
                        || containerEdge.srcContainer.equals(containerEdge.dstContainer)) {
                    continue;
                }
                String edgeKey = containerEdge.srcContainer + "\u0000" + containerEdge.dstContainer;
                if (edgeKeys.add(edgeKey)) {
                    edges.add(new FeatureGraphResult.Edge(containerEdge.srcContainer, containerEdge.dstContainer));
                }
            }
            return new FeatureGraphResult(nodes, edges);
        }

        Set<String> edgeKeys = new LinkedHashSet<>();
        for (GraphEdge graphEdge : graphEdgeRepository.findByRepo_Id(ProjectState.getInstance().getRepoId())) {
            String src = normalizePythonPath(graphEdge.getSrc());
            String dest = normalizePythonPath(graphEdge.getDest());
            Set<String> srcContainers = containerIdsByFile.getOrDefault(src, Collections.emptySet());
            Set<String> destContainers = containerIdsByFile.getOrDefault(dest, Collections.emptySet());
            for (String srcContainer : srcContainers) {
                for (String destContainer : destContainers) {
                    if (srcContainer.equals(destContainer)) {
                        continue;
                    }
                    String edgeKey = srcContainer + "\u0000" + destContainer;
                    if (edgeKeys.add(edgeKey)) {
                        edges.add(new FeatureGraphResult.Edge(srcContainer, destContainer));
                    }
                }
            }
        }
        return new FeatureGraphResult(nodes, edges);
    }

    public FeatureGraphResult getPythonModificationGraph() {
        FeatureResult candidateFeature = ClusterState.getInstance().getCandidateFeature();
        if (candidateFeature != null && candidateFeature.getFeatureId() != null) {
            FeatureGraphResult graph = getPythonFeatureGraph(candidateFeature.getFeatureId());
            Set<String> modifiedContainerIds = resolvePythonModifiedContainerIds(candidateFeature);
            graph.getNodes().forEach(node -> {
                if (modifiedContainerIds.contains(node.getId())) {
                    node.setType("Modify");
                }
            });
            return graph;
        }

        Set<FeatureGraphResult.Node> nodes = new LinkedHashSet<>();
        Map<String, String> pendingModifications = candidateCodeService.pendingModificationMap();
        if (!pendingModifications.isEmpty()) {
            pendingModifications.keySet().forEach(filePath -> {
                FeatureGraphResult.Node node = new FeatureGraphResult.Node(filePath);
                node.setType("Modify");
                nodes.add(node);
            });
        }
        return new FeatureGraphResult(nodes, new LinkedHashSet<>());
    }

    private Set<String> resolvePythonModifiedContainerIds(FeatureResult candidateFeature) {
        Map<String, PythonMethodContainer> methodContainerMap = loadPythonMethodContainerMap();
        Map<String, String> methodFileMap = loadPythonMethodFileMap();
        Set<String> result = new LinkedHashSet<>();

        Set<String> explicitMethods = AgentService.pythonModifiedMethods == null
                ? Collections.emptySet()
                : AgentService.pythonModifiedMethods;
        if (!explicitMethods.isEmpty()) {
            for (String methodName : explicitMethods) {
                PythonMethodContainer container = resolvePythonMethodContainer(methodName, methodContainerMap, methodFileMap);
                result.add(container.containerId);
            }
            return result;
        }

        Map<String, String> pendingModifications = candidateCodeService.pendingModificationMap();
        Set<String> modifiedFiles = pendingModifications.isEmpty()
                ? Collections.emptySet()
                : pendingModifications.keySet().stream()
                .map(CodeMapService::normalizePythonPath)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (modifiedFiles.isEmpty()) {
            return result;
        }

        for (String methodName : collectPythonFeatureMethodNames(candidateFeature)) {
            PythonMethodContainer container = resolvePythonMethodContainer(methodName, methodContainerMap, methodFileMap);
            if (modifiedFiles.contains(normalizePythonPath(container.containerFile))) {
                result.add(container.containerId);
            }
        }
        return result;
    }

    private List<String> collectPythonFeatureMethodNames(FeatureResult candidateFeature) {
        if (candidateFeature == null || candidateFeature.getCandidateMethods() == null) {
            return Collections.emptyList();
        }
        List<String> methods = new ArrayList<>();
        candidateFeature.getCandidateMethods().forEach(candidate -> {
            if (candidate.getLxtFull() == null || candidate.getLxtFull().isEmpty()) {
                if (candidate.getZyfShortSignature() != null && !candidate.getZyfShortSignature().isBlank()) {
                    methods.add(candidate.getZyfShortSignature());
                }
                return;
            }
            candidate.getLxtFull().forEach(full -> {
                if (full.getLxtFullSignature() != null && !full.getLxtFullSignature().isBlank()) {
                    methods.add(full.getLxtFullSignature());
                }
            });
        });
        return methods.stream().distinct().collect(Collectors.toCollection(ArrayList::new));
    }

    public JsonNode preparePythonDeleteFeature(Integer featureId, List<String> currentCodeMap) throws IOException, InterruptedException {
        if (featureId == null) {
            throw new IllegalArgumentException("Python Delete Feature requires a featureId.");
        }
        List<String> featureMethods = currentCodeMap == null
                ? Collections.emptyList()
                : currentCodeMap.stream()
                .filter(method -> method != null && !method.isBlank())
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));
        if (featureMethods.isEmpty()) {
            AgentService.modificationMap = Collections.emptyMap();
            AgentService.pythonModifiedMethods = Collections.emptySet();
            throw new UnsupportedOperationException("The selected Python feature has no CodeMap methods to delete.");
        }

        Set<String> sharedMethods = new LinkedHashSet<>();
        for (String method : featureMethods) {
            boolean shared = codeMapRepository.findByMethodName(method).stream()
                    .map(CodeMap::getFeature)
                    .filter(Objects::nonNull)
                    .map(Feature::getId)
                    .anyMatch(ownerFeatureId -> !featureId.equals(ownerFeatureId));
            if (shared) {
                sharedMethods.add(method);
            }
        }

        JsonNode result = runPythonDeletePlanner(featureMethods, sharedMethods);
        Map<String, String> modifications = new LinkedHashMap<>();
        JsonNode modificationNode = result.path("modificationMap");
        if (modificationNode.isObject()) {
            Iterator<String> fieldNames = modificationNode.fieldNames();
            while (fieldNames.hasNext()) {
                String filePath = fieldNames.next();
                modifications.put(normalizePythonPath(filePath), modificationNode.path(filePath).asText(""));
            }
        }
        AgentService.modificationMap = modifications;
        Set<String> deletedMethods = new LinkedHashSet<>();
        for (JsonNode methodNode : result.path("deletedMethods")) {
            String methodName = methodNode.asText("");
            if (!methodName.isBlank()) {
                deletedMethods.add(methodName);
            }
        }
        AgentService.pythonModifiedMethods = deletedMethods;

        if (modifications.isEmpty() || result.path("deletedMethods").size() == 0) {
            StringBuilder message = new StringBuilder("No uniquely owned Python functions/methods can be deleted for this feature.");
            JsonNode skipped = result.path("skippedMethods");
            if (skipped.isArray() && !skipped.isEmpty()) {
                message.append(" Skipped methods: ");
                List<String> skippedSummaries = new ArrayList<>();
                for (JsonNode item : skipped) {
                    skippedSummaries.add(item.path("method").asText("") + " (" + item.path("reason").asText("unknown") + ")");
                }
                message.append(String.join("; ", skippedSummaries));
            }
            throw new UnsupportedOperationException(message.toString());
        }

        return result;
    }

    public FeatureResult getFeature(Integer featureId) {
        if (!isBuilt && ProjectState.getInstance().getRepoId() != null) {
            readFeatureFromDatabase(ProjectState.getInstance().getRepoId());
        }
        if (moduleResults == null) {
            return null;
        }
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
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteFeatureFromMemoryAndDatabase(Integer featureId) throws ParseException, IOException, InterruptedException {
        if (ProjectState.getInstance().isPython()) {
            return deletePythonFeatureFromMemoryAndDatabase(featureId);
        }

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
                        Optional<String> editedContent = candidateCodeService.authoritativeJavaContent(node.getId());
                        if (editedContent.isPresent()) {
                            RewriteFileHelper.rewriteJavaFileContent(node.getId(), editedContent.get());
                        } else {
                            RewriteFileHelper.rewriteFile(node.getId(), codeDiffController.deleteCodeByClass(node.getId()));
                        }
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

    @Transactional(rollbackFor = Exception.class)
    public Integer modifyFeatureFromMemoryAndDatabase(
            Integer featureId,
            String newFeatureDescription,
            AgentLanguage language
    ) throws ParseException, IOException, InterruptedException {
        return modifyOrAddFeatureFromMemoryAndDatabase(featureId, newFeatureDescription, "modify", language);
    }

    @Transactional(rollbackFor = Exception.class)
    public Integer addFeatureFromMemoryAndDatabase(
            Integer moduleId,
            String newFeatureDescription,
            AgentLanguage language
    ) throws ParseException, IOException, InterruptedException {
        return modifyOrAddFeatureFromMemoryAndDatabase(moduleId, newFeatureDescription, "add", language);
    }

    public Integer modifyFeatureFromMemoryAndDatabase(
            Integer featureId,
            String newFeatureDescription
    ) throws ParseException, IOException, InterruptedException {
        return modifyFeatureFromMemoryAndDatabase(featureId, newFeatureDescription, AgentLanguage.EN);
    }

    public Integer addFeatureFromMemoryAndDatabase(
            Integer moduleId,
            String newFeatureDescription
    ) throws ParseException, IOException, InterruptedException {
        return addFeatureFromMemoryAndDatabase(moduleId, newFeatureDescription, AgentLanguage.EN);
    }

    private Integer modifyOrAddFeatureFromMemoryAndDatabase(
            Integer featureOrModuleId,
            String newFeatureDescription,
            String type,
            AgentLanguage language
    ) throws ParseException, IOException, InterruptedException {
        if (ProjectState.getInstance().isPython()) {
            if ("modify".equals(type)) {
                return modifyPythonFeatureFromMemoryAndDatabase(featureOrModuleId, newFeatureDescription);
            }
            if ("add".equals(type)) {
                return addPythonFeatureFromMemoryAndDatabase(featureOrModuleId, newFeatureDescription);
            }
            throw new UnsupportedOperationException("非法操作");
        }

        Integer featureId;
        AgentLanguage descriptionLanguage = AgentLanguage.orDefault(language);
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

            // 2. 改数据库
            Feature candidateFeatureEntity = featureRepository.findById(candidateFeature.getFeatureId()).get();
            if (descriptionLanguage == AgentLanguage.CN) {
                candidateFeature.setFeatureDescriptionCn(newFeatureDescription);
                candidateFeatureEntity.setFeatureDescCN(newFeatureDescription);
            } else {
                candidateFeature.setFeatureDescription(newFeatureDescription);
                candidateFeatureEntity.setFeatureDesc(newFeatureDescription);
            }
            featureRepository.save(candidateFeatureEntity);
            featureId = featureOrModuleId;
        } else if (type.equals("add")) {
            // 1. 改内存表
            FeatureResult candidateFeature = null;
            for (ModuleResult moduleResult : moduleResults) {
                if (moduleResult.getModuleId() == featureOrModuleId) {
                    candidateFeature = new FeatureResult();
                    if (descriptionLanguage == AgentLanguage.CN) {
                        candidateFeature.setFeatureDescriptionCn(newFeatureDescription);
                    } else {
                        candidateFeature.setFeatureDescription(newFeatureDescription);
                    }
                    moduleResult.getFeatureList().add(candidateFeature);
                    break;
                }
            }

            // 2. 改数据库
            Feature candidateFeatureEntity = new Feature();
            Module module = entityManager.getReference(Module.class, featureOrModuleId);
            candidateFeatureEntity.setModule(module);
            if (descriptionLanguage == AgentLanguage.CN) {
                candidateFeatureEntity.setFeatureDescCN(newFeatureDescription);
            } else {
                candidateFeatureEntity.setFeatureDesc(newFeatureDescription);
            }
            candidateFeatureEntity = featureRepository.save(candidateFeatureEntity);
            featureId = candidateFeatureEntity.getId();
        } else {
            throw new UnsupportedOperationException("非法操作");
        }


        // 3. 生成import语句、更新邻接表、重写文件
        for (Map.Entry<String, String> entry : AgentService.modificationMap.entrySet()) {
            try {
                Optional<String> editedContent = candidateCodeService.authoritativeJavaContent(entry.getKey());

                String allFiles = "";
                List<String> javaFiles = ListFileHelper.findJavaFiles(ProjectState.getInstance().getSrcPath());
                for (String javaFile : javaFiles) {
                    allFiles += javaFile + "\n";
                }
                // 3.1 生成import语句
                List<String> importLines = editedContent.isPresent()
                        ? RewriteFileHelper.extractJavaImportLines(editedContent.get())
                        : generateImportLinesService.generate(entry.getKey(), entry.getValue(), allFiles);
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
                if (editedContent.isPresent()) {
                    RewriteFileHelper.rewriteJavaFileContent(entry.getKey(), editedContent.get());
                } else {
                    RewriteFileHelper.rewriteFile(entry.getKey(), entry.getValue(), importLines);
                }

                // 3.4 写入CodeMap数据库
                try {
                    // 用 JavaParser 解析
                    String candidateBody = editedContent
                            .map(RewriteFileHelper::stripJavaPackageAndImports)
                            .orElse(entry.getValue());
                    CompilationUnit cu = StaticJavaParser.parse(candidateBody);

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

    private Integer modifyPythonFeatureFromMemoryAndDatabase(Integer featureId, String newFeatureDescription) throws IOException, InterruptedException {
        FeatureResult candidateFeature = null;
        if (moduleResults != null) {
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
        }
        if (candidateFeature != null) {
            candidateFeature.setFeatureDescription(newFeatureDescription);
        }

        Feature candidateFeatureEntity = featureRepository.findById(featureId).orElseThrow();
        candidateFeatureEntity.setFeatureDesc(newFeatureDescription);
        featureRepository.save(candidateFeatureEntity);

        Map<String, String> modifications = currentPythonModifications();
        applyPythonModifications(modifications);

        List<String> changedFiles = modifications.entrySet().stream()
                .filter(entry -> !AgentService.DELETE_FILE_SENTINEL.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(ArrayList::new));
        savePythonCodeMapForChangedFiles(featureId, changedFiles);

        isBuilt = false;
        return featureId;
    }

    private Integer addPythonFeatureFromMemoryAndDatabase(Integer moduleId, String newFeatureDescription) throws IOException, InterruptedException {
        if (moduleId == null) {
            throw new IllegalArgumentException("Python Add Feature requires a moduleId.");
        }

        Feature candidateFeatureEntity = new Feature();
        Module module = entityManager.getReference(Module.class, moduleId);
        candidateFeatureEntity.setModule(module);
        candidateFeatureEntity.setFeatureDesc(newFeatureDescription);
        candidateFeatureEntity = featureRepository.save(candidateFeatureEntity);
        Integer featureId = candidateFeatureEntity.getId();

        Map<String, String> modifications = currentPythonModifications();
        applyPythonModifications(modifications);

        List<String> changedFiles = modifications.entrySet().stream()
                .filter(entry -> !AgentService.DELETE_FILE_SENTINEL.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(ArrayList::new));
        savePythonCodeMapForChangedFiles(featureId, changedFiles);

        isBuilt = false;
        return featureId;
    }

    private boolean deletePythonFeatureFromMemoryAndDatabase(Integer featureId) throws IOException {
        if (featureId == null) {
            throw new IllegalArgumentException("Python Delete Feature requires a featureId.");
        }

        Map<String, String> modifications = currentPythonModifications();
        applyPythonModifications(modifications);

        ModuleResult candidateModule = null;
        FeatureResult candidateFeature = null;
        Integer emptyModuleId = null;
        if (moduleResults != null) {
            for (ModuleResult moduleResult : moduleResults) {
                for (FeatureResult featureResult : moduleResult.getFeatureList()) {
                    if (featureResult.getFeatureId().equals(featureId)) {
                        candidateModule = moduleResult;
                        candidateFeature = featureResult;
                        break;
                    }
                }
                if (candidateFeature != null) {
                    break;
                }
            }
        }
        if (candidateModule != null && candidateFeature != null) {
            candidateModule.getFeatureList().remove(candidateFeature);
            if (candidateModule.getFeatureList().isEmpty()) {
                emptyModuleId = candidateModule.getModuleId();
                moduleResults.remove(candidateModule);
            }
        }

        codeMapRepository.deleteByFeature_Id(featureId);
        featureRepository.deleteById(featureId);
        if (emptyModuleId != null) {
            moduleRepository.deleteById(emptyModuleId);
        }

        isBuilt = false;
        return true;
    }

    private JsonNode runPythonDeletePlanner(List<String> featureMethods, Set<String> sharedMethods) throws IOException, InterruptedException {
        String pythonExec = getEnvOrDefault("REPOSUMMARY_PYTHON", "python3");
        String repoSummaryDir = getEnvOrDefault("REPOSUMMARY_DIR", "./RepoSummary");
        Integer repoId = ProjectState.getInstance().getRepoId();
        if (repoId == null) {
            throw new IOException("Current repoId is not set.");
        }

        Path methodsCsv = Path.of(repoSummaryDir, "output", repoId.toString(), "methods.csv");
        if (!Files.exists(methodsCsv)) {
            throw new IOException("Python methods.csv not found: " + methodsCsv);
        }

        ObjectNode request = objectMapper.createObjectNode();
        request.put("srcRoot", ProjectState.getInstance().getSrcPath());
        request.put("methodsCsv", methodsCsv.toString());
        ArrayNode featureMethodsNode = request.putArray("featureMethods");
        featureMethods.forEach(featureMethodsNode::add);
        ArrayNode sharedMethodsNode = request.putArray("sharedMethods");
        sharedMethods.forEach(sharedMethodsNode::add);

        ProcessBuilder processBuilder = new ProcessBuilder(pythonExec, "src/python_delete_feature.py");
        processBuilder.directory(new File(repoSummaryDir));
        Process process = processBuilder.start();
        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> readProcessStream(process.getInputStream()));
        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> readProcessStream(process.getErrorStream()));

        try (OutputStream stdin = process.getOutputStream()) {
            objectMapper.writeValue(stdin, request);
        }

        boolean exited = process.waitFor(120, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        String stdout = stdoutFuture.join();
        String stderr = stderrFuture.join();
        if (!exited) {
            throw new IOException("Python Delete planner timed out.\n" + stderr);
        }
        if (process.exitValue() != 0) {
            throw new IOException("Python Delete planner failed with exit code " + process.exitValue()
                    + "\nSTDERR:\n" + stderr
                    + "\nSTDOUT:\n" + stdout);
        }
        return objectMapper.readTree(stdout);
    }

    private String readProcessStream(java.io.InputStream inputStream) {
        try {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, String> currentPythonModifications() {
        return AgentService.modificationMap == null
                ? Collections.emptyMap()
                : AgentService.modificationMap;
    }

    private void applyPythonModifications(Map<String, String> modifications) throws IOException {
        for (Map.Entry<String, String> entry : modifications.entrySet()) {
            if (AgentService.DELETE_FILE_SENTINEL.equals(entry.getValue())) {
                deletePythonFile(entry.getKey());
            } else {
                RewriteFileHelper.rewritePythonFile(entry.getKey(), entry.getValue());
            }
        }
    }

    private void deletePythonFile(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IOException("Python file path is required.");
        }
        String normalizedRelativePath = relativePath.replace('\\', '/');
        while (normalizedRelativePath.startsWith("./")) {
            normalizedRelativePath = normalizedRelativePath.substring(2);
        }
        if (normalizedRelativePath.startsWith("/")
                || normalizedRelativePath.contains("../")
                || !normalizedRelativePath.endsWith(".py")) {
            throw new IOException("Invalid Python file path: " + relativePath);
        }

        Path rootPath = Path.of(ProjectState.getInstance().getSrcPath()).normalize();
        Path filePath = rootPath.resolve(normalizedRelativePath).normalize();
        if (!filePath.startsWith(rootPath)) {
            throw new IOException("Invalid Python file path: " + relativePath);
        }
        Files.deleteIfExists(filePath);
    }

    private void savePythonCodeMapForChangedFiles(Integer featureId, List<String> changedFiles) throws IOException, InterruptedException {
        for (String signature : extractPythonMethodSignatures(changedFiles)) {
            if (signature == null || signature.isBlank()) {
                continue;
            }
            if (codeMapRepository.existsByFeature_IdAndMethodName(featureId, signature)) {
                continue;
            }
            CodeMap newCodeMap = new CodeMap();
            Feature feature = entityManager.getReference(Feature.class, featureId);
            newCodeMap.setFeature(feature);
            newCodeMap.setMethodName(signature);
            codeMapRepository.save(newCodeMap);
        }
    }

    private List<String> extractPythonMethodSignatures(List<String> changedFiles) throws IOException, InterruptedException {
        if (changedFiles == null || changedFiles.isEmpty()) {
            return Collections.emptyList();
        }

        String pythonExec = getEnvOrDefault("REPOSUMMARY_PYTHON", "python3");
        String repoSummaryDir = getEnvOrDefault("REPOSUMMARY_DIR", "./RepoSummary");
        ProcessBuilder processBuilder = new ProcessBuilder(pythonExec, "src/extract_python_methods.py");
        processBuilder.directory(new File(repoSummaryDir));
        processBuilder.environment().putIfAbsent("LOTM_REPO_PATH", LtmConfig.getRepoPath());

        ObjectNode request = objectMapper.createObjectNode();
        request.put("srcRoot", ProjectState.getInstance().getSrcPath());
        ArrayNode files = request.putArray("files");
        changedFiles.forEach(files::add);

        Process process = processBuilder.start();
        process.getOutputStream().write(objectMapper.writeValueAsBytes(request));
        process.getOutputStream().close();
        boolean exited = process.waitFor(120, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!exited) {
            throw new IOException("Python method extraction timed out.\n" + stderr);
        }
        if (process.exitValue() != 0) {
            throw new IOException("Python method extraction failed with exit code " + process.exitValue()
                    + "\nSTDERR:\n" + stderr
                    + "\nSTDOUT:\n" + stdout);
        }

        JsonNode root = objectMapper.readTree(stdout);
        List<String> signatures = new ArrayList<>();
        for (JsonNode method : root.path("methods")) {
            signatures.add(method.path("signature").asText());
        }
        return signatures;
    }

    private static String getEnvOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
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

    private static Map<String, String> loadPythonMethodFileMap() {
        String repoSummaryDir = getEnvOrDefault("REPOSUMMARY_DIR", "./RepoSummary");
        Integer repoId = ProjectState.getInstance().getRepoId();
        if (repoId == null) {
            return Collections.emptyMap();
        }

        Path outputDir = Path.of(repoSummaryDir, "output", repoId.toString());
        Path explicitMap = outputDir.resolve("method_file_map.csv");
        if (Files.exists(explicitMap)) {
            return readMethodFileMap(explicitMap, "method_name", "func_file");
        }

        Path methodsCsv = outputDir.resolve("methods.csv");
        if (Files.exists(methodsCsv)) {
            return readMethodFileMap(methodsCsv, "method_signature", "func_file");
        }
        return Collections.emptyMap();
    }

    private static Map<String, String> readMethodFileMap(Path csvPath, String methodColumn, String fileColumn) {
        Map<String, String> result = new LinkedHashMap<>();
        try (var reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return result;
            }
            List<String> header = parseCsvLine(headerLine);
            int methodIndex = header.indexOf(methodColumn);
            int fileIndex = header.indexOf(fileColumn);
            if (methodIndex < 0 || fileIndex < 0) {
                return result;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                List<String> columns = parseCsvLine(line);
                if (columns.size() <= Math.max(methodIndex, fileIndex)) {
                    continue;
                }
                String methodName = columns.get(methodIndex).trim();
                String filePath = normalizePythonPath(columns.get(fileIndex));
                if (!methodName.isBlank() && !filePath.isBlank()) {
                    result.put(methodName, filePath);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read Python method file map from " + csvPath + ": " + e.getMessage());
        }
        return result;
    }

    private static Map<String, PythonMethodContainer> loadPythonMethodContainerMap() {
        String repoSummaryDir = getEnvOrDefault("REPOSUMMARY_DIR", "./RepoSummary");
        Integer repoId = ProjectState.getInstance().getRepoId();
        if (repoId == null) {
            return Collections.emptyMap();
        }

        Path mapPath = Path.of(repoSummaryDir, "output", repoId.toString(), "method_container_map.csv");
        if (!Files.exists(mapPath)) {
            return Collections.emptyMap();
        }

        Map<String, PythonMethodContainer> result = new LinkedHashMap<>();
        try (var reader = Files.newBufferedReader(mapPath, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return result;
            }
            List<String> header = parseCsvLine(headerLine);
            int methodIndex = header.indexOf("method_name");
            int containerIndex = header.indexOf("container_id");
            int typeIndex = header.indexOf("container_type");
            int fileIndex = header.indexOf("container_file");
            int shortNameIndex = header.indexOf("method_short_name");
            if (methodIndex < 0 || containerIndex < 0 || fileIndex < 0) {
                return result;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                List<String> columns = parseCsvLine(line);
                if (columns.size() <= Math.max(methodIndex, Math.max(containerIndex, fileIndex))) {
                    continue;
                }
                String methodName = columns.get(methodIndex).trim();
                String containerId = columns.get(containerIndex).trim();
                String containerType = typeIndex >= 0 && columns.size() > typeIndex
                        ? columns.get(typeIndex).trim()
                        : "Module";
                String containerFile = normalizePythonPath(columns.get(fileIndex));
                String methodShortName = shortNameIndex >= 0 && columns.size() > shortNameIndex
                        ? columns.get(shortNameIndex).trim()
                        : shortPythonMethodName(methodName);
                if (!methodName.isBlank() && !containerId.isBlank() && !containerFile.isBlank()) {
                    result.put(
                            methodName,
                            new PythonMethodContainer(
                                    methodName,
                                    containerId,
                                    containerType.isBlank() ? "Module" : containerType,
                                    containerFile,
                                    methodShortName
                            )
                    );
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read Python method container map from " + mapPath + ": " + e.getMessage());
        }
        return result;
    }

    private static List<PythonContainerEdge> loadPythonContainerEdges() {
        String repoSummaryDir = getEnvOrDefault("REPOSUMMARY_DIR", "./RepoSummary");
        Integer repoId = ProjectState.getInstance().getRepoId();
        if (repoId == null) {
            return null;
        }

        Path edgePath = Path.of(repoSummaryDir, "output", repoId.toString(), "container_edges.csv");
        if (!Files.exists(edgePath)) {
            return null;
        }

        List<PythonContainerEdge> result = new ArrayList<>();
        try (var reader = Files.newBufferedReader(edgePath, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return result;
            }
            List<String> header = parseCsvLine(headerLine);
            int srcIndex = header.indexOf("src_container");
            int dstIndex = header.indexOf("dst_container");
            if (srcIndex < 0 || dstIndex < 0) {
                return result;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                List<String> columns = parseCsvLine(line);
                if (columns.size() <= Math.max(srcIndex, dstIndex)) {
                    continue;
                }
                String srcContainer = columns.get(srcIndex).trim();
                String dstContainer = columns.get(dstIndex).trim();
                if (!srcContainer.isBlank() && !dstContainer.isBlank()) {
                    result.add(new PythonContainerEdge(srcContainer, dstContainer));
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to read Python container edges from " + edgePath + ": " + e.getMessage());
            return Collections.emptyList();
        }
        return result;
    }

    public static String resolvePythonNodeToFile(String nodeId) {
        String normalized = normalizePythonPath(nodeId);
        if (normalized.endsWith(".py")) {
            return normalized;
        }

        for (PythonMethodContainer container : loadPythonMethodContainerMap().values()) {
            if (container.containerId.equals(normalized)) {
                return container.containerFile;
            }
        }

        return pythonContainerToFile(normalized);
    }

    private static PythonMethodContainer resolvePythonMethodContainer(
            String methodName,
            Map<String, PythonMethodContainer> methodContainerMap,
            Map<String, String> methodFileMap
    ) {
        PythonMethodContainer container = methodContainerMap.get(methodName);
        if (container == null) {
            container = findPythonContainerByKnownPrefix(methodName, methodContainerMap.values());
        }
        if (container == null) {
            String filePath = methodFileMap.getOrDefault(methodName, pythonFileFromSignature(methodName));
            container = inferPythonMethodContainer(methodName, filePath);
        }
        return container;
    }

    private static PythonMethodContainer inferPythonMethodContainer(String methodName, String filePath) {
        String cleanFilePath = normalizePythonPath(filePath);
        if (cleanFilePath.isBlank()) {
            cleanFilePath = pythonFileFromSignature(methodName);
        }
        String moduleId = moduleIdFromPythonFile(cleanFilePath);
        String base = methodName == null ? "" : methodName.split("\\(", 2)[0].trim();
        String owner = base.contains(".") ? base.substring(0, base.lastIndexOf('.')) : moduleId;
        String containerId = moduleId;
        String containerType = "Module";

        if (owner.startsWith(moduleId + ".")) {
            String remainder = owner.substring(moduleId.length() + 1);
            String firstOwnerPart = remainder.split("\\.", 2)[0];
            if (!firstOwnerPart.isBlank() && Character.isUpperCase(firstOwnerPart.charAt(0))) {
                containerId = moduleId + "." + firstOwnerPart;
                containerType = "Class";
            }
        }

        String methodShortName;
        if (base.startsWith(containerId + ".")) {
            methodShortName = base.substring(containerId.length() + 1);
        } else if (base.startsWith(moduleId + ".")) {
            methodShortName = base.substring(moduleId.length() + 1);
        } else {
            methodShortName = shortPythonMethodName(methodName);
        }
        return new PythonMethodContainer(methodName, containerId, containerType, cleanFilePath, methodShortName);
    }

    private static PythonMethodContainer findPythonContainerByKnownPrefix(
            String methodName,
            Collection<PythonMethodContainer> knownContainers
    ) {
        if (methodName == null || knownContainers == null || knownContainers.isEmpty()) {
            return null;
        }
        String base = methodName.split("\\(", 2)[0].trim();
        if (!base.contains(".")) {
            return null;
        }
        String owner = base.substring(0, base.lastIndexOf('.'));
        PythonMethodContainer best = null;
        for (PythonMethodContainer candidate : knownContainers) {
            if (candidate == null || candidate.containerId == null || candidate.containerId.isBlank()) {
                continue;
            }
            if (owner.equals(candidate.containerId) || owner.startsWith(candidate.containerId + ".")) {
                if (best == null || candidate.containerId.length() > best.containerId.length()) {
                    best = candidate;
                }
            }
        }
        if (best == null) {
            return null;
        }
        String methodShortName = base.startsWith(best.containerId + ".")
                ? base.substring(best.containerId.length() + 1)
                : shortPythonMethodName(methodName);
        return new PythonMethodContainer(
                methodName,
                best.containerId,
                best.containerType,
                best.containerFile,
                methodShortName
        );
    }

    private static String moduleIdFromPythonFile(String filePath) {
        String normalized = normalizePythonPath(filePath);
        if (normalized.endsWith(".py")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        if (normalized.endsWith("/__init__")) {
            normalized = normalized.substring(0, normalized.length() - "/__init__".length());
        }
        return normalized.replace('/', '.');
    }

    private static String pythonContainerToFile(String containerId) {
        String current = containerId == null ? "" : containerId.trim();
        if (current.isBlank()) {
            return "unknown.py";
        }

        String srcPath = ProjectState.getInstance().getSrcPath();
        while (!current.isBlank()) {
            String candidate = normalizePythonPath(current.replace('.', '/') + ".py");
            if (srcPath != null && !srcPath.isBlank() && Files.exists(Path.of(srcPath, candidate))) {
                return candidate;
            }
            int lastDot = current.lastIndexOf('.');
            if (lastDot < 0) {
                break;
            }
            current = current.substring(0, lastDot);
        }
        return normalizePythonPath(containerId.replace('.', '/') + ".py");
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static String normalizePythonPath(String path) {
        String normalized = path == null ? "" : path.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String pythonFileFromSignature(String signature) {
        String base = signature == null ? "" : signature.split("\\(", 2)[0];
        List<String> parts = new ArrayList<>(Arrays.asList(base.split("\\.")));
        if (parts.size() >= 2) {
            parts.remove(parts.size() - 1);
        }
        if (!parts.isEmpty()) {
            String maybeClass = parts.get(parts.size() - 1);
            if (!maybeClass.isBlank() && Character.isUpperCase(maybeClass.charAt(0))) {
                parts.remove(parts.size() - 1);
            }
        }
        if (parts.isEmpty()) {
            return "unknown.py";
        }
        return normalizePythonPath(String.join("/", parts) + ".py");
    }

    private static String shortPythonMethodName(String signature) {
        String base = signature == null ? "" : signature.split("\\(", 2)[0];
        int lastDot = base.lastIndexOf('.');
        return lastDot >= 0 ? base.substring(lastDot + 1) : base;
    }

}
