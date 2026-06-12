package com.microservices.courseservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.providers")
public class AiProviderProperties {

    private ProviderConfig google = new ProviderConfig(true);
    private ProviderConfig openai = new ProviderConfig(false);

    @Data
    public static class ProviderConfig {
        private boolean enabled;

        public ProviderConfig() {
        }

        public ProviderConfig(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
