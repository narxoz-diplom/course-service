package com.microservices.courseservice.dto.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class RagLlmUsageDto {

    @JsonProperty("llm_model_id")
    private String llmModelId;

    private String provider;

    @JsonProperty("provider_model_id")
    private String providerModelId;

    @JsonProperty("provider_request_id")
    private String providerRequestId;

    @JsonProperty("finish_reason")
    private String finishReason;

    @JsonProperty("input_tokens")
    private Integer inputTokens;

    @JsonProperty("output_tokens")
    private Integer outputTokens;

    @JsonProperty("cached_tokens")
    private Integer cachedTokens;

    @JsonProperty("reasoning_tokens")
    private Integer reasoningTokens;

    @JsonProperty("total_tokens")
    private Integer totalTokens;

    @JsonProperty("usage_source")
    private String usageSource;

    @JsonProperty("attempt_number")
    private Integer attemptNumber;

    @JsonProperty("requested_model_id")
    private String requestedModelId;

    @JsonProperty("used_fallback")
    private Boolean usedFallback;

    @JsonProperty("fallback_from_model_id")
    private String fallbackFromModelId;
}
