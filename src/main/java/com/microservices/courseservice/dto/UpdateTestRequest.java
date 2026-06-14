package com.microservices.courseservice.dto;

import lombok.Data;

import java.util.List;

@Data
public class UpdateTestRequest {
    private String title;
    private String titleKz;
    private String titleEn;
    private Integer maxAttempts;
    private String dueAt;
    private List<UpdateQuestionRequest> questions;
}
