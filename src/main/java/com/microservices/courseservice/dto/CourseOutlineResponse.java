package com.microservices.courseservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class CourseOutlineResponse {
    private List<LessonOutlineItemDto> outline;

    @JsonProperty("collection_name")
    private String collectionName;

    @JsonProperty("chunks_used")
    private int chunksUsed;

    @JsonProperty("request_id")
    private String requestId;
}
