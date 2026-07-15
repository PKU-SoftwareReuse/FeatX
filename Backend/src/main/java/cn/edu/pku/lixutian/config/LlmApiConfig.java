package cn.edu.pku.lixutian.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


@Component
@ConfigurationProperties(prefix = "llm.api")
@Getter
@Setter
public class LlmApiConfig {

    private String url;

    private String key;

}
