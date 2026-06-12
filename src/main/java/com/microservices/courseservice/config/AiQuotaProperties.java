package com.microservices.courseservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.quotas")
public class AiQuotaProperties {

    /**
     * Default monthly token budget per teacher when no per-user override exists.
     * ~1M tokens ≈ dozens of outline/quiz runs or several full lesson batches on Gemini Flash.
     */
    private long teacherDefaultMonthlyTokens = 1_000_000L;

    /**
     * Default daily burst cap per teacher (sum across all models).
     */
    private long teacherDefaultDailyTokens = 150_000L;
}
