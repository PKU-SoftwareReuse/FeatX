package cn.edu.pku.lixutian.service.code;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class GenerateImportLinesService extends AgentService {

    private String parseResult(String agentResult) {
        // 提取 ```json ... ``` 中的内容
        int start = agentResult.indexOf("```java");
        if (start == -1) throw new IllegalArgumentException("找不到java起始标记");
        int end = agentResult.indexOf("```", start + 7);
        if (end == -1) throw new IllegalArgumentException("找不到java结束标记");

        String javaStr = agentResult.substring(start + 7, end).trim();

        return javaStr;
    }

    public List<String> generate(String fileName, String originalCode, String fileList) {
        String prompt = buildPrompt(fileName, originalCode, fileList);
        String result = llmClient.generateWithSinglePrompt(prompt);
        String javaStr = parseResult(result);
        List<String> importList = Arrays.asList(javaStr.split("\\r?\\n"));
        return importList;
    }

    private String buildPrompt(String fileName, String originalCode, String fileList) {
        String promptTemplate = """
                你是Agent，负责根据得到的纯代码和项目文件列表为纯代码生成import语句。
                
                你将收到以下输入：
                1. 目标纯代码的全文件名
                \"\"\"
                %s
                \"\"\"
                2. 目标纯代码
                \"\"\"
                %s
                \"\"\"
                3. 软件项目的完整文件列表（文件名、路径）
                \"\"\"
                %s
                \"\"\"
                
                你的任务是：
                - 理解目标代码中的类使用情况
                - 阅读软件项目的完整文件列表
                - 参考文件列表，为目标代码生成Java的import语句
                
                请仅输出需要添加的import语句，不要添加其他无关说明和代码，严格遵守以下格式。
                ```java
                import org.springframework.transaction.annotation.Transactional;
                import top.naccl.mapper.CategoryMapper;
                ...
                import java.util.List;
                ```
                """;
        return String.format(promptTemplate, fileName, originalCode, fileList);
    }

}
