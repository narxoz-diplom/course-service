package com.microservices.courseservice.dto;

import lombok.Data;

@Data
public class RagQuizQuestionDto {
    private String question;
    /**
     * Options structure is defined by RAG service contract
     * and is stored as JSON in the Question entity.
     */
    private Object options;
    private String correct;
    private String explanation;
    private String hint;
}
