package cn.edu.pku.lixutian.service.code;

import cn.edu.pku.lixutian.config.ProjectState;
import cn.edu.pku.lixutian.helper.ListFileHelper;
import cn.edu.pku.lixutian.helper.RewriteFileHelper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.printer.configuration.PrettyPrinterConfiguration;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

@Service
public class AddAgentService extends AgentService {

    private static class AdditionalFile {
        public String filename;
        public String recommendReason;
    }

    private static class Agent1ParsedResult {
        public boolean needAdditionalFile;
        public List<AdditionalFile> additionalFileList = new ArrayList<>();
    }

    private Agent1ParsedResult parseAgent1Result(String agent1Result) throws JsonProcessingException {
        // 提取 ```json ... ``` 中的内容
        int start = agent1Result.indexOf("```json");
        if (start == -1) throw new IllegalArgumentException("找不到json起始标记");
        int end = agent1Result.indexOf("```", start + 7);
        if (end == -1) throw new IllegalArgumentException("找不到json结束标记");

        String jsonStr = agent1Result.substring(start + 7, end).trim();

        // 反序列化
        JsonNode root = objectMapper.readTree(jsonStr);

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

    private static class ModifiedFile {
        public String filename;
        public String plan;
        public String note;
    }

    private static class Agent2ParsedResult {
        public List<ModifiedFile> modifiedFileList = new ArrayList<>();
    }

    private Agent2ParsedResult parseAgent2Result(String agent2Result) throws JsonProcessingException {
        int start = agent2Result.indexOf("```json");
        if (start == -1) throw new IllegalArgumentException("找不到json起始标记");
        int end = agent2Result.indexOf("```", start + 7);
        if (end == -1) throw new IllegalArgumentException("找不到json结束标记");

        String jsonStr = agent2Result.substring(start + 7, end).trim();

        JsonNode root = objectMapper.readTree(jsonStr);

        Agent2ParsedResult result = new Agent2ParsedResult();

        for (JsonNode fileNode : root.path("modifiedFileList")) {
            ModifiedFile file = new ModifiedFile();
            file.filename = fileNode.path("filename").asText();
            file.plan = fileNode.path("plan").asText();
            file.note = fileNode.path("note").asText();
            result.modifiedFileList.add(file);
        }
        return result;
    }

    public String parseAgent3Result(String agent3Result) {
        // 提取 ```json ... ``` 中的内容
        int start = agent3Result.indexOf("```java");
        if (start == -1) throw new IllegalArgumentException("找不到java起始标记");
        int end = agent3Result.indexOf("```", start + 7);
        if (end == -1) throw new IllegalArgumentException("找不到java结束标记");

        String javaStr = agent3Result.substring(start + 7, end).trim();

        try {
            // 用 JavaParser 解析
            CompilationUnit cu = StaticJavaParser.parse(javaStr);

            // 配置 PrettyPrinter
            PrettyPrinterConfiguration config = new PrettyPrinterConfiguration();
            config.setIndentSize(4);       // 统一 4 空格缩进
            config.setPrintComments(true); // 保留注释
            config.setOrderImports(true);  // 如果要排序 import，可以加这个

            // 格式化回字符串，并统一保存为不含 package/import 的类型定义。
            javaStr = RewriteFileHelper.stripJavaPackageAndImports(cu.toString(config));

        } catch (Exception e) {
            throw new RuntimeException("Java 代码解析或格式化失败: " + e.getMessage(), e);
        }

        return javaStr;
    }

    public SseEmitter runPipeline(
            String changeRequest,
            String originalCode,
            String fileList,
            AgentLanguage language,
            String model
    ) {
        SseEmitter emitter = new SseEmitter(0L); // 不超时
        AgentLanguage responseLanguage = AgentLanguage.orDefault(language);

        // 在独立线程运行，避免阻塞
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                // ===== Agent1 =====
                emitter.send(SseEmitter.event().data(encode(responseLanguage.stageOneDescription())));
                String agent1Prompt = buildAgent1Prompt(changeRequest, originalCode, fileList, responseLanguage);

                String agent1Result = llmClient.streamGenerateWithPrompt(agent1Prompt, emitter, model);

                String extraInfo = "";
                Agent1ParsedResult agent1ParsedResult = parseAgent1Result(agent1Result);
                if (agent1ParsedResult.needAdditionalFile) {
                    for (AdditionalFile file : agent1ParsedResult.additionalFileList) {
                        String fileContent = ListFileHelper.getFileContent(ProjectState.getInstance().getSrcPath(), file.filename);
                        extraInfo += "filename: " + file.filename + "\n";
                        extraInfo += "recommendReason: " + file.recommendReason + "\n";
                        extraInfo += "fileContent: " + fileContent + "\n=======================\n";
                    }
                } else {
                    extraInfo = responseLanguage.noExtraInformation();
                }


                // ===== Agent2 =====
                emitter.send(SseEmitter.event().data(encode(responseLanguage.stageTwoDescription())));

                String agent2Prompt = buildAgent2Prompt(changeRequest, originalCode, extraInfo, responseLanguage);

                String agent2Result = llmClient.streamGenerateWithPrompt(agent2Prompt, emitter, model);

                Agent2ParsedResult agent2ParsedResult = parseAgent2Result(agent2Result);

                // ===== Agent3 =====
                Map<String, String> map = new HashMap<>();
                for (ModifiedFile file : agent2ParsedResult.modifiedFileList) {
                    emitter.send(SseEmitter.event().data(encode(responseLanguage.stageThreeDescription(file.filename))));
                    String fileContent = ListFileHelper.getFileContent(ProjectState.getInstance().getSrcPath(), file.filename);
                    String plan = "";
                    plan += "filename: " + file.filename + "\n";
                    plan += "modificationPlan: " + file.plan + "\n";
                    plan += "modificationNote: " + file.note + "\n";
                    String agent3Prompt = buildAgent3Prompt(changeRequest, plan, fileContent, responseLanguage);

                    String agent3Result = llmClient.streamGenerateWithPrompt(agent3Prompt, emitter, model);
                    map.put(file.filename, parseAgent3Result(agent3Result));
                }
                emitter.send(SseEmitter.event().data(encode(responseLanguage.pipelineCompleteDescription())));

                modificationMap = map;

                emitter.complete();

            } catch (Exception e) {
                logger.error("Add-feature agent pipeline failed", e);
                try {
                    emitter.send(SseEmitter.event().data(encode(responseLanguage.pipelineErrorDescription())));
                } catch (IOException ignored) {

                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private String encode(String input) {
        return Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    private String buildAgent1Prompt(
            String changeDesc,
            String code,
            String fileList,
            AgentLanguage language
    ) {
        String promptTemplate = """
                你是Agent1，负责分析新增功能相关的信息需求。
                
                你将收到以下输入：
                1. 新的功能需求描述
                \"\"\"
                %s
                \"\"\"
                2. 同一功能模块下其他原有功能对应的核心代码列表（关键代码片段）
                \"\"\"
                %s
                \"\"\"
                3. 软件项目的完整文件列表（文件名、路径）
                \"\"\"
                %s
                \"\"\"
                
                你的任务是：
                - 理解新增功能需求与其他功能下的相关代码
                - 判断现有信息是否足够完成后续新增功能代码的编写
                - 如果不够，明确列出需要额外获取的文件路径列表，并说明原因
                - 如果已有信息充分，明确回复“不需要额外文件”
                
                请全程使用%s回答，写出你的思考过程，并在回答末尾严格输出以下格式：
                ```json
                {
                    "needAdditionalFile": true/false,
                    "additionalFileList": [
                        {"filename": "top.naccl.service.impl.DashboardServiceImpl", "recommendReason":"%s"},
                        ...
                    ]
                }
                ```
                """;
        return String.format(
                promptTemplate,
                changeDesc,
                code,
                fileList,
                language.promptLanguageName(),
                language.recommendReasonExample()
        );
    }

    private String buildAgent2Prompt(
            String changeDesc,
            String code,
            String extraInfo,
            AgentLanguage language
    ) {
        String promptTemplate = """
                你是Agent2，负责基于新的功能需求及现有代码制定详细的修改方案。
                
                你将收到以下输入：
                1. 新的功能需求描述
                \"\"\"
                %s
                \"\"\"
                2. 同一功能模块下其他原有功能对应的核心代码列表（关键代码片段）
                \"\"\"
                %s
                \"\"\"
                3. 软件项目中一些额外的参考（代码和理由）
                \"\"\"
                %s
                \"\"\"
                
                你的任务是：
                - 全面分析需求变更的影响范围
                - 制定分步详细的修改或新增方案
                - 指出修改过程中的关键重难点或潜在风险
                - 明确列出需要修改的文件路径列表，确保列表只包含必须改动的文件
                - 如果你认为不需要任何更改，请在json中返回空列表，不要给任何多余字段
                
                请全程使用%s回答，写出你关于修改方案和关键重难点的思考过程，并在回答末尾严格输出以下格式：
                ```json
                {
                    "modifiedFileList": [
                        {
                            "filename": "top.naccl.service.impl.DashboardServiceImpl", 
                            "plan": "%s",
                            "note": "%s"
                        },
                        {...},
                        ...
                    ]
                }
                ```
                """;
        return String.format(
                promptTemplate,
                changeDesc,
                code,
                extraInfo,
                language.promptLanguageName(),
                language.modificationPlanExample(),
                language.modificationNoteExample()
        );
    }

    private String buildAgent3Prompt(
            String changeDesc,
            String plan,
            String fileContent,
            AgentLanguage language
    ) {
        String promptTemplate = """
                你是Agent3，负责具体文件级别的修改。
                
                你将收到以下输入：
                1. 新的功能需求描述
                \"\"\"
                %s
                \"\"\"
                2. 待修改文件名称和修改意见
                \"\"\"
                %s
                \"\"\"
                3. 待修改文件的完整原始内容（确保完整且格式正确）
                \"\"\"
                %s
                \"\"\"
                
                你的任务是：
                - 基于需求变更，重写给定文件内容，完成所有必要修改
                - 保持代码风格和结构一致
                - 不要省略任何代码部分，完整返回修改后文件的全部内容
                - 确保代码逻辑正确，避免引入新错误
                - 跳过文件的 package 和 import 部分
                - 如果需要新增或修改自然语言注释，请使用%s
                
                仅输出修改后完整的文件内容，不要添加其他无关说明，也不要省略不需要修改的部分，严格遵守以下格式。
                ```java
                public class Example{
                    private xxx;
                    ....
                }
                ```
                """;
        return String.format(
                promptTemplate,
                changeDesc,
                plan,
                fileContent,
                language.promptLanguageName()
        );
    }
}
