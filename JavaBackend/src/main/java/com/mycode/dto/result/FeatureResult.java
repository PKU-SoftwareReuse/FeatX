package com.mycode.dto.result;

import com.mycode.dao.CodeMap;
import com.mycode.dao.Feature;
import com.mycode.config.ProjectState;
import com.mycode.graph.softwareGraph.vertex.Vertex;
import com.mycode.graph.softwareGraph.vertex.VertexMap;
import com.github.javaparser.ast.body.CallableDeclaration;
import lombok.Getter;
import lombok.Setter;

import java.util.*;

@Getter
@Setter
public class FeatureResult {

    private Integer featureId;
    private String featureDescription;
    private String featureDescriptionCn;
    private List<CandidateMethod> candidateMethods;

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
            List<String> matchedFullSignatures = matchingFullSignatures(
                    candidateMethod.getShortSignature(),
                    fullMethodSignatureList
            );

            for (String matchedFullSignature : matchedFullSignatures) {
                candidateMethod.addFullCandidateMethod(matchedFullSignature);
            }
            candidateMethods.add(candidateMethod);
        }
    }

    public static String normalizeShortSignature(String fullSignature) {
        MethodSignature signature = parseSignature(fullSignature);
        if (signature == null) {
            return fullSignature;
        }
        return signature.declaringType + "." + signature.methodName
                + "(" + String.join(", ", signature.parameterTypes) + ")";
    }

    static List<String> matchingFullSignatures(
            String codeMapSignature,
            Collection<String> fullMethodSignatures
    ) {
        MethodSignature requested = parseSignature(codeMapSignature);
        if (requested == null || fullMethodSignatures == null || fullMethodSignatures.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> exactMatches = new ArrayList<>();
        List<String> legacyArrayMatches = new ArrayList<>();
        for (String fullSignature : fullMethodSignatures) {
            MethodSignature available = parseSignature(fullSignature);
            if (!sameCallable(requested, available)) {
                continue;
            }
            if (requested.parameterTypes.equals(available.parameterTypes)) {
                exactMatches.add(fullSignature);
            } else if (sameParametersIgnoringArrayDimensions(requested, available)) {
                legacyArrayMatches.add(fullSignature);
            }
        }

        if (!exactMatches.isEmpty()) {
            return exactMatches;
        }
        // Older RepoSummary output omitted [] from array parameters. Only use
        // that relaxed match when it identifies one unambiguous declaration.
        return legacyArrayMatches.size() == 1
                ? legacyArrayMatches
                : Collections.emptyList();
    }

    private static boolean sameCallable(MethodSignature requested, MethodSignature available) {
        if (available == null || requested.parameterTypes.size() != available.parameterTypes.size()) {
            return false;
        }
        if (!sameDeclaringType(requested.declaringType, available.declaringType)) {
            return false;
        }
        String requestedMethod = requested.methodName;
        if ("<init>".equals(requestedMethod)) {
            requestedMethod = simpleTypeName(requested.declaringType);
        }
        return requestedMethod.equals(available.methodName);
    }

    private static boolean sameDeclaringType(String left, String right) {
        String normalizedLeft = left.replace('$', '.');
        String normalizedRight = right.replace('$', '.');
        return normalizedLeft.equals(normalizedRight)
                || normalizedLeft.endsWith("." + normalizedRight)
                || normalizedRight.endsWith("." + normalizedLeft);
    }

    private static boolean sameParametersIgnoringArrayDimensions(
            MethodSignature requested,
            MethodSignature available
    ) {
        for (int index = 0; index < requested.parameterTypes.size(); index++) {
            String requestedType = requested.parameterTypes.get(index).replace("[]", "");
            String availableType = available.parameterTypes.get(index).replace("[]", "");
            if (!requestedType.equals(availableType)) {
                return false;
            }
        }
        return true;
    }

    private static MethodSignature parseSignature(String signature) {
        if (signature == null) {
            return null;
        }
        int leftParen = signature.indexOf('(');
        int rightParen = signature.lastIndexOf(')');
        if (leftParen < 0 || rightParen <= leftParen) {
            return null;
        }

        String callable = signature.substring(0, leftParen).trim();
        int methodSeparator = callable.lastIndexOf('.');
        if (methodSeparator <= 0 || methodSeparator == callable.length() - 1) {
            return null;
        }

        String declaringType = callable.substring(0, methodSeparator).trim();
        String methodName = callable.substring(methodSeparator + 1).trim();
        String arguments = signature.substring(leftParen + 1, rightParen).trim();
        List<String> parameterTypes = new ArrayList<>();
        if (!arguments.isEmpty()) {
            for (String parameter : safeSplitParams(arguments)) {
                parameterTypes.add(canonicalType(extractType(parameter)));
            }
        }
        return new MethodSignature(declaringType, methodName, parameterTypes);
    }

    private static String canonicalType(String declaredType) {
        String type = declaredType == null ? "" : declaredType.trim();
        type = type.replaceAll("^final\\s+", "")
                .replaceAll("^@[\\w.$]+(?:\\([^)]*\\))?\\s*", "")
                .replace("...", "[]");
        type = removeGenericTypeInfo(type).replaceAll("\\s+", "");

        int dimensions = 0;
        while (type.endsWith("[]")) {
            dimensions++;
            type = type.substring(0, type.length() - 2);
        }
        return simpleTypeName(type) + "[]".repeat(dimensions);
    }

    private static String simpleTypeName(String type) {
        String normalized = type == null ? "" : type.trim().replace('$', '.');
        int separator = normalized.lastIndexOf('.');
        return separator < 0 ? normalized : normalized.substring(separator + 1);
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

    private record MethodSignature(
            String declaringType,
            String methodName,
            List<String> parameterTypes
    ) {}

    @Getter
    @Setter
    public static class CandidateMethod {
        private String shortSignature;
        private List<FullCandidateMethod> fullCandidateMethods;

        public CandidateMethod() {
            this.fullCandidateMethods = new ArrayList<>();
        }

        public CandidateMethod(String shortSignature) {
            this.shortSignature = shortSignature;
            this.fullCandidateMethods = new ArrayList<>();
        }

        public void addFullCandidateMethod(String fullSignature) {
            this.fullCandidateMethods.add(new FullCandidateMethod(fullSignature));
        }

        public void addPythonCandidateMethod(String signature) {
            this.fullCandidateMethods.add(new FullCandidateMethod(signature, Collections.emptySet()));
        }

        @Getter
        @Setter
        public static class FullCandidateMethod {
            private String fullSignature;
            private Set<Integer> clusterIds;

            public FullCandidateMethod() {
                this.clusterIds = new HashSet<>();
            }
            public FullCandidateMethod(String fullSignature) {
                this.fullSignature = fullSignature;
                this.clusterIds = VertexMap.getInstance().getMethodDeclarationMap()
                        .get(fullSignature).getClusterIds();
            }

            public FullCandidateMethod(String fullSignature, Set<Integer> clusterIds) {
                this.fullSignature = fullSignature;
                this.clusterIds = clusterIds == null ? new HashSet<>() : new HashSet<>(clusterIds);
            }
        }
    }
}
