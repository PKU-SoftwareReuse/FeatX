package cn.edu.pku.lixutian.service.code;

import cn.edu.pku.lixutian.service.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Service
public class AgentService {
    protected static final ObjectMapper objectMapper = new ObjectMapper();
    public static final String DELETE_FILE_SENTINEL = "__FEATX_DELETE_FILE__";

    public static Map<String, String> modificationMap;
    public static Set<String> pythonModifiedMethods;

    @Autowired
    protected LlmClient llmClient;
}
