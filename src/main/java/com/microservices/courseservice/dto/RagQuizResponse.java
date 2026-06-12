package com.microservices.courseservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class RagQuizResponse {
    private List<RagQuizQuestionDto> questions;
    private Map<String, List<RagQuizQuestionDto>> translations;

    @JsonProperty("request_id")
    private String requestId;

    private com.microservices.courseservice.dto.ai.RagLlmUsageDto usage;
}
