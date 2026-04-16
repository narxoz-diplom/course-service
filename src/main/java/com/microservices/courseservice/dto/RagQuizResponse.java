package com.microservices.courseservice.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class RagQuizResponse {
    private List<RagQuizQuestionDto> questions;
    private Map<String, List<RagQuizQuestionDto>> translations;
}
