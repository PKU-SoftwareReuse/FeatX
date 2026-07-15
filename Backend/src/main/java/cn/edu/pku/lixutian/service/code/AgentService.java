package cn.edu.pku.lixutian.service.code;

import cn.edu.pku.lixutian.service.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AgentService {
    protected static final ObjectMapper objectMapper = new ObjectMapper();
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    public static Map<String, String> modificationMap;

    @Autowired
    protected LlmClient llmClient;
}
