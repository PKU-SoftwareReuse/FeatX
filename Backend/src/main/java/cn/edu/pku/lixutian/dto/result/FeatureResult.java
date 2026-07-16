package cn.edu.pku.lixutian.dto.result;

import cn.edu.pku.lixutian.dao.CodeMap;
import cn.edu.pku.lixutian.dao.Feature;
import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.Vertex;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.VertexMap;
import com.github.javaparser.ast.body.CallableDeclaration;
import lombok.Getter;
import lombok.Setter;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Getter
@Setter
public class FeatureResult {

    private Integer featureId;
    private String featureDescription;
    private String featureDescriptionCn;
    private List<CandidateMethod> candidateMethods;
//    private Boolean isTemp; // 标记是否为临时特征
//    private Boolean isNewGenerated; // 标记是否是新生成的feature
//
    public FeatureResult() {
        this.candidateMethods = new ArrayList<>();
    }

    public FeatureResult(Feature feature) {
        this.featureId = feature.getId();
        this.featureDescription = feature.getFeatureDesc();
        this.featureDescriptionCn = feature.getFeatureDescCN();
        this.candidateMethods = new ArrayList<>();

        if (ProjectState.getInstance().isPython()) {
            for (CodeMap codeMap : feature.getMethodNameList()) {
                CandidateMethod candidateMethod = new CandidateMethod(codeMap.getMethodName());
                candidateMethod.addPythonCandidateMethod(codeMap.getMethodName());
                candidateMethods.add(candidateMethod);
            }
            return;
        }

        VertexMap vertexMap = VertexMap.getInstance();
        Map<String, Vertex<CallableDeclaration<?>>> methodDeclarationMap = vertexMap.getMethodDeclarationMap();
        Set<String> fullMethodSignatureList = methodDeclarationMap.keySet();

        for (CodeMap codeMap : feature.getMethodNameList()) {
            CandidateMethod candidateMethod = new CandidateMethod(codeMap.getMethodName());
            String shortSignature = candidateMethod.getZyfShortSignature();
            // 构建正则：例如 Result.setCode -> .*\.Result\.setCode\(
            String normalizedShortSignature = normalizeShortSignature(shortSignature);
            String regex = ".*\\b" + Pattern.quote(normalizedShortSignature) + ".*";

            List<String> matchedFullSignatures = fullMethodSignatureList.stream()
                    .filter(fullSignature -> fullSignature.matches(regex))
                    .collect(Collectors.toList());

            for (String matchedFullSignature : matchedFullSignatures) {
                candidateMethod.addFullCandidateMethod(matchedFullSignature);
            }
            candidateMethods.add(candidateMethod);
        }
    }

    public static String normalizeShortSignature(String fullSignature) {
        int leftParen = fullSignature.indexOf('(');
        int rightParen = fullSignature.lastIndexOf(')');
        if (leftParen == -1 || rightParen == -1 || rightParen <= leftParen) return fullSignature;

        String beforeArgs = fullSignature.substring(0, leftParen).trim();
        String args = fullSignature.substring(leftParen + 1, rightParen).trim();

        if (args.isEmpty()) return beforeArgs + "()";

        List<String> paramTokens = safeSplitParams(args);
        List<String> typesOnly = new ArrayList<>();

        for (String param : paramTokens) {
            param = param.trim();
            // 如果是泛型或数组，变量名在最后，我们只保留类型
            // 思路：从后往前找第一个非泛型/非中括号标识符
            String typePart = extractType(param);
            String erasedGeneric = removeGenericTypeInfo(typePart);
            typesOnly.add(erasedGeneric.trim());
        }

        return beforeArgs + "(" + String.join(", ", typesOnly) + ")";
    }

    // 提取类型部分，去掉变量名，保留泛型、数组
    private static String extractType(String param) {
        int angleDepth = 0;
        int bracketDepth = 0;

        for (int i = param.length() - 1; i >= 0; i--) {
            char c = param.charAt(i);
            if (c == '>') angleDepth++;
            else if (c == '<') angleDepth--;
            else if (c == ']') bracketDepth++;
            else if (c == '[') bracketDepth--;
            else if (c == ' ' && angleDepth == 0 && bracketDepth == 0) {
                return param.substring(0, i).trim();
            }
        }
        // 如果没找到空格，说明没有变量名，整个就是类型
        return param.trim();
    }

    private static List<String> safeSplitParams(String args) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int angleDepth = 0;
        int parenDepth = 0;
        int bracketDepth = 0;

        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '<') angleDepth++;
            else if (c == '>') angleDepth--;
            else if (c == '(') parenDepth++;
            else if (c == ')') parenDepth--;
            else if (c == '[') bracketDepth++;
            else if (c == ']') bracketDepth--;

            if (c == ',' && angleDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            result.add(current.toString().trim());
        }

        return result;
    }

    private static String removeGenericTypeInfo(String signature) {
        StringBuilder result = new StringBuilder();
        int depth = 0;

        for (int i = 0; i < signature.length(); i++) {
            char c = signature.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (depth == 0) {
                result.append(c);
            }
        }

        return result.toString();
    }

    @Getter
    @Setter
    public static class CandidateMethod {
        private String zyfShortSignature;
        private List<FullCandidateMethod> lxtFull;

        public CandidateMethod() {
            this.lxtFull = new ArrayList<>();
        }

        public CandidateMethod(String zyfShortSignature) {
            this.zyfShortSignature = zyfShortSignature;
            this.lxtFull = new ArrayList<>();
        }

        public void addFullCandidateMethod(String lxtFullSignature) {
            this.lxtFull.add(new FullCandidateMethod(lxtFullSignature));
        }

        public void addPythonCandidateMethod(String signature) {
            this.lxtFull.add(new FullCandidateMethod(signature, Collections.emptySet()));
        }

        @Getter
        @Setter
        public static class FullCandidateMethod {
            private String lxtFullSignature;
            private Set<Integer> clusterIds;

            public FullCandidateMethod() {
                this.clusterIds = new HashSet<>();
            }
//
            public FullCandidateMethod(String lxtFullSignature) {
                this.lxtFullSignature = lxtFullSignature;
                this.clusterIds = VertexMap.getInstance().getMethodDeclarationMap()
                        .get(lxtFullSignature).getClusterIds();
            }

            public FullCandidateMethod(String lxtFullSignature, Set<Integer> clusterIds) {
                this.lxtFullSignature = lxtFullSignature;
                this.clusterIds = clusterIds == null ? new HashSet<>() : new HashSet<>(clusterIds);
            }
        }
    }
}
