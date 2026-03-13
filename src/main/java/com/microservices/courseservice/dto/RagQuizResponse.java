package com.microservices.courseservice.dto;

import lombok.Data;

import java.util.List;

@Data
public class RagQuizResponse {
    private List<RagQuizQuestionDto> questions;
}
