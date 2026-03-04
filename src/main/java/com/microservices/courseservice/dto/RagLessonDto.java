package com.microservices.courseservice.dto;

import lombok.Data;

@Data
public class RagLessonDto {
    private String title;
    private String content;
    private String description;
    private Integer order;
}
