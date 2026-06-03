package com.microservices.courseservice.dto;

import lombok.Data;

@Data
public class RagQuizQuestionDto {
    private String question;
    private Object options;
    private String correct;
    private String explanation;
    private String hint;
}
