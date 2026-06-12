package com.microservices.courseservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class RagLessonsResponse {
    private List<RagLessonDto> lessons;
    private Map<String, List<RagLessonDto>> translations;

    @JsonProperty("request_id")
    private String requestId;

    private com.microservices.courseservice.dto.ai.RagLlmUsageDto usage;
}
