package com.microservices.courseservice.dto;

import com.microservices.courseservice.model.Question;
import lombok.Data;

@Data
public class UpdateQuestionRequest {
    private Long id;
    private Question.QuestionType type;
    private String text;
    private String textKz;
    private String textEn;
    private String options;
    private String optionsKz;
    private String optionsEn;
    private String correctAnswer;
    private String explanation;
    private String hint;
    private String explanationKz;
    private String explanationEn;
    private String hintKz;
    private String hintEn;
    private Integer orderNumber;
}
