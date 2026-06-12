package com.microservices.courseservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.features")
public class AiFeatureProperties {

    /**
     * When false, teachers always use the platform default model and the picker is hidden.
     */
    private boolean modelSelectionEnabled = true;
}
