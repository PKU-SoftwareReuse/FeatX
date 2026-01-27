package cn.edu.pku.lixutian.service.code;

import cn.edu.pku.lixutian.service.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AgentService {
    protected static final ObjectMapper objectMapper = new ObjectMapper();

    public static Map<String, String> modificationMap;

    @Autowired
    protected LlmClient llmClient;
}
