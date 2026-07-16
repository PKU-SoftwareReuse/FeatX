package cn.edu.pku.lixutian.service.code;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.helper.ListFileHelper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

@Service
public class PythonModifyAgentService extends AgentService {
    private static final String PYTHON_FILE_START = "<<<FEATX_PYTHON_FILE_START>>>";
    private static final String PYTHON_FILE_END = "<<<FEATX_PYTHON_FILE_END>>>";

    private static class AdditionalFile {
        public String filename;
        public String recommendReason;
    }

    private static class Agent1ParsedResult {
        public boolean needAdditionalFile;
        public List<AdditionalFile> additionalFileList = new ArrayList<>();
    }

    private static class ModifiedFile {
        public String filename;
        public String action;
        public String plan;
        public String note;
    }

    private static class Agent2ParsedResult {
        public List<ModifiedFile> modifiedFileList = new ArrayList<>();
    }

    public SseEmitter runPipeline(String changeRequest, String originalFeatureDesc, String focusGraphContext, String fileList) {
        return runPipelineForOperation("modify", changeRequest, originalFeatureDesc, focusGraphContext, fileList);
    }

    public SseEmitter runAddPipeline(String featureRequest, String focusGraphContext, String fileList) {
        return runPipelineForOperation("add", featureRequest, "", focusGraphContext, fileList);
    }

    public SseEmitter runDeletePipeline(String deleteRequest, String originalFeatureDesc, String focusGraphContext, String fileList) {
        return runPipelineForOperation("delete", deleteRequest, originalFeatureDesc, focusGraphContext, fileList);
    }

    private SseEmitter runPipelineForOperation(
            String operation,
            String changeRequest,
            String originalFeatureDesc,
            String focusGraphContext,
            String fileList
    ) {
        SseEmitter emitter = new SseEmitter(0L);

        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                pythonModifiedMethods = Collections.emptySet();
                emitter.send(SseEmitter.event().data(encode("# === Stage I: Python Information Requirement Analysis (" + operation + ") ===\n")));
                String agent1Result = llmClient.streamGenerateWithPrompt(
                        buildAgent1Prompt(operation, changeRequest, originalFeatureDesc, focusGraphContext, fileList),
                        emitter
                );

                String extraInfo = "None";
                Agent1ParsedResult agent1ParsedResult = parseAgent1Result(agent1Result);
                if (agent1ParsedResult.needAdditionalFile) {
                    StringBuilder extraBuilder = new StringBuilder();
                    for (AdditionalFile file : agent1ParsedResult.additionalFileList) {
                        String filename = normalizePythonPath(file.filename);
                        String fileContent = ListFileHelper.getPythonFileContent(ProjectState.getInstance().getSrcPath(), filename);
                        extraBuilder.append("filename: ").append(filename).append("\n");
                        extraBuilder.append("recommendReason: ").append(file.recommendReason).append("\n");
                        extraBuilder.append("fileContent: ").append(fileContent).append("\n=======================\n");
                    }
                    extraInfo = extraBuilder.toString();
                }

                emitter.send(SseEmitter.event().data(encode("\n# === Stage II: Python File Planning (" + operation + ") ===\n")));
                String agent2Result = llmClient.streamGenerateWithPrompt(
                        buildAgent2Prompt(operation, changeRequest, originalFeatureDesc, focusGraphContext, extraInfo),
                        emitter
                );
                Agent2ParsedResult agent2ParsedResult = parseAgent2Result(agent2Result);

                Map<String, String> map = new HashMap<>();
                for (ModifiedFile file : agent2ParsedResult.modifiedFileList) {
                    String filename = normalizePythonPath(file.filename);
                    if ("delete".equalsIgnoreCase(file.action)) {
                        emitter.send(SseEmitter.event().data(encode("\n# === Stage III: Mark Python File Deletion " + filename + " ===\n")));
                        map.put(filename, DELETE_FILE_SENTINEL);
                        continue;
                    }
                    emitter.send(SseEmitter.event().data(encode("\n# === Stage III: Concrete Python File Rewrite " + filename + " ===\n")));
                    String fileContent = ListFileHelper.getPythonFileContent(ProjectState.getInstance().getSrcPath(), filename);
                    String plan = "filename: " + filename + "\n"
                            + "modificationPlan: " + file.plan + "\n"
                            + "modificationNote: " + file.note + "\n";
                    String agent3Result = llmClient.streamGenerateWithPrompt(
                            buildAgent3Prompt(operation, changeRequest, originalFeatureDesc, plan, fileContent),
                            emitter
                    );
                    map.put(filename, parsePythonCodeBlock(agent3Result));
                }

                emitter.send(SseEmitter.event().data(encode("\n# === Pipeline complete! ===\n")));
                modificationMap = map;
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().data(encode("错误: " + e.getMessage())));
                } catch (IOException ignored) {
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private Agent1ParsedResult parseAgent1Result(String agent1Result) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(extractJsonBlock(agent1Result));
        Agent1ParsedResult result = new Agent1ParsedResult();
        result.needAdditionalFile = root.path("needAdditionalFile").asBoolean(false);
        for (JsonNode fileNode : root.path("additionalFileList")) {
            AdditionalFile file = new AdditionalFile();
            file.filename = fileNode.path("filename").asText();
            file.recommendReason = fileNode.path("recommendReason").asText();
            result.additionalFileList.add(file);
        }
        return result;
    }

    private Agent2ParsedResult parseAgent2Result(String agent2Result) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(extractJsonBlock(agent2Result));
        Agent2ParsedResult result = new Agent2ParsedResult();
        for (JsonNode fileNode : root.path("modifiedFileList")) {
            ModifiedFile file = new ModifiedFile();
            file.filename = fileNode.path("filename").asText();
            file.action = fileNode.path("action").asText("rewrite");
            file.plan = fileNode.path("plan").asText();
            file.note = fileNode.path("note").asText();
            result.modifiedFileList.add(file);
        }
        return result;
    }

    private String extractJsonBlock(String text) {
        int start = text.indexOf("```json");
        if (start >= 0) {
            int end = text.indexOf("```", start + 7);
            if (end >= 0) {
                return text.substring(start + 7, end).trim();
            }
        }
        int objectStart = text.indexOf('{');
        int objectEnd = text.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            return text.substring(objectStart, objectEnd + 1).trim();
        }
        throw new IllegalArgumentException("Cannot find JSON block in agent response.");
    }

    private String parsePythonCodeBlock(String agent3Result) {
        int markerStart = agent3Result.indexOf(PYTHON_FILE_START);
        if (markerStart >= 0) {
            int contentStart = markerStart + PYTHON_FILE_START.length();
            int markerEnd = agent3Result.indexOf(PYTHON_FILE_END, contentStart);
            if (markerEnd >= 0) {
                return stripGeneratedCode(agent3Result.substring(contentStart, markerEnd));
            }
        }

        int start = firstPythonFence(agent3Result);
        if (start >= 0) {
            int contentStart = agent3Result.indexOf('\n', start);
            if (contentStart < 0) {
                contentStart = start + 3;
            } else {
                contentStart += 1;
            }
            int end = lastStandaloneFence(agent3Result, contentStart);
            if (end < 0) {
                end = agent3Result.lastIndexOf("```");
            }
            if (end > contentStart) {
                return stripGeneratedCode(agent3Result.substring(contentStart, end));
            }
        }
        return stripGeneratedCode(agent3Result);
    }

    private int firstPythonFence(String text) {
        int python = text.indexOf("```python");
        int py = text.indexOf("```py");
        if (python < 0) {
            return py;
        }
        if (py < 0) {
            return python;
        }
        return Math.min(python, py);
    }

    private int lastStandaloneFence(String text, int fromIndex) {
        int result = -1;
        int searchIndex = fromIndex;
        while (searchIndex >= 0 && searchIndex < text.length()) {
            int fenceIndex = text.indexOf("```", searchIndex);
            if (fenceIndex < 0) {
                break;
            }
            int lineStart = fenceIndex;
            while (lineStart > 0) {
                char previous = text.charAt(lineStart - 1);
                if (previous == '\n' || previous == '\r') {
                    break;
                }
                lineStart--;
            }
            int lineEnd = fenceIndex + 3;
            while (lineEnd < text.length()) {
                char current = text.charAt(lineEnd);
                if (current == '\n' || current == '\r') {
                    break;
                }
                lineEnd++;
            }
            if ("```".equals(text.substring(lineStart, lineEnd).trim())) {
                result = fenceIndex;
            }
            searchIndex = fenceIndex + 3;
        }
        return result;
    }

    private String stripGeneratedCode(String code) {
        return code
                .replaceFirst("^[\\r\\n]+", "")
                .replaceFirst("[\\r\\n]+$", "");
    }

    private String normalizePythonPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Python filename is required.");
        }
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("/") || normalized.contains("../") || !normalized.endsWith(".py")) {
            throw new IllegalArgumentException("Invalid Python filename: " + path);
        }
        return normalized;
    }

    private String encode(String input) {
        return Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    private String buildAgent1Prompt(String operation, String changeDesc, String originalDesc, String context, String fileList) {
        String operationGoal = switch (operation) {
            case "add" -> "adding a new feature";
            case "delete" -> "deleting the selected feature and cleaning up direct usages";
            default -> "modifying the selected feature";
        };
        String promptTemplate = """
                You are Agent1 for a Python project. Analyze whether the provided FocusGraph context is enough for %s.

                Request:
                \"\"\"
                %s
                \"\"\"

                Original/selected feature, if any:
                \"\"\"
                %s
                \"\"\"

                FocusGraph context:
                \"\"\"
                %s
                \"\"\"

                Complete Python file list, using repository-relative .py paths:
                \"\"\"
                %s
                \"\"\"

                If more files are needed, return only repository-relative .py paths. End your answer with:
                ```json
                {
                  "needAdditionalFile": true/false,
                  "additionalFileList": [
                    {"filename": "package/module.py", "recommendReason": "why this file is needed"}
                  ]
                }
                ```
                """;
        return String.format(promptTemplate, operationGoal, changeDesc, originalDesc, context, fileList);
    }

    private String buildAgent2Prompt(String operation, String changeDesc, String originalDesc, String context, String extraInfo) {
        String operationGuidance = switch (operation) {
            case "add" -> """
                    - Plan the files that must be edited or created to implement the new feature.
                    - Prefer extending existing entry points shown by FocusGraph when that is the natural integration path.
                    - You may include new repository-relative .py paths when a new module/file is required.
                    - Use action="rewrite" for edited or newly created files.
                    """;
            case "delete" -> """
                    - Plan the files that must be edited to remove the selected feature and clean up direct callers/usages.
                    - Preserve unrelated behavior, public APIs, imports, tests hooks, and compatibility code unless they are exclusively part of the deleted feature.
                    - Use action="rewrite" for files that should remain with code removed.
                    - Use action="delete" only when the entire .py file is exclusively owned by the deleted feature and should be removed.
                    """;
            default -> """
                    - Plan the files that must be edited to implement the feature change.
                    - Preserve unrelated behavior and keep the edit scope minimal.
                    - Use action="rewrite" for every changed file.
                    """;
        };
        String promptTemplate = """
                You are Agent2 for a Python project. Produce a concrete file-level plan.

                Request:
                \"\"\"
                %s
                \"\"\"

                Original/selected feature, if any:
                \"\"\"
                %s
                \"\"\"

                FocusGraph context:
                \"\"\"
                %s
                \"\"\"

                Extra file context:
                \"\"\"
                %s
                \"\"\"

                Operation-specific rules:
                %s

                Return only repository-relative .py file paths that must be edited, created, or deleted. End with:
                ```json
                {
                  "modifiedFileList": [
                    {
                      "filename": "package/module.py",
                      "action": "rewrite",
                      "plan": "Detailed modification plan.",
                      "note": "Important constraints."
                    }
                  ]
                }
                ```
                """;
        return String.format(promptTemplate, changeDesc, originalDesc, context, extraInfo, operationGuidance);
    }

    private String buildAgent3Prompt(String operation, String changeDesc, String originalDesc, String plan, String fileContent) {
        String operationGuidance = switch (operation) {
            case "add" -> "Implement the new feature. If this is a brand new file, return the complete new Python file.";
            case "delete" -> "Remove the selected feature from this file and clean up now-unused imports/helpers only when they are exclusively tied to the deleted feature.";
            default -> "Implement the feature modification.";
        };
        String promptTemplate = """
                You are Agent3 for a Python project. Rewrite the full target Python file.

                Request:
                \"\"\"
                %s
                \"\"\"

                Original/selected feature, if any:
                \"\"\"
                %s
                \"\"\"

                Operation-specific rule:
                %s

                Target file and plan:
                \"\"\"
                %s
                \"\"\"

                Original full file content:
                \"\"\"
                %s
                \"\"\"

                Return the complete modified Python file, including imports and unchanged code. Do not omit anything.
                Do not wrap explanations around the code.
                Do not use Markdown code fences, because Python docstrings may contain them.
                Wrap the file content only between these exact markers:
                <<<FEATX_PYTHON_FILE_START>>>
                # full file content
                <<<FEATX_PYTHON_FILE_END>>>
                """;
        return String.format(promptTemplate, changeDesc, originalDesc, operationGuidance, plan, fileContent);
    }
}
