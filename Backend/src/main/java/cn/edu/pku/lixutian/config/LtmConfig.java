package cn.edu.pku.lixutian.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ltm")
@Getter
@Setter
public class LtmConfig {
    private String repoPath;

    private static LtmConfig instance;

    @PostConstruct
    public void init() {
        instance = this;
    }

    public static String getRepoPath() {
        return instance.repoPath;
    }
}
