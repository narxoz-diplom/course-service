package com.microservices.courseservice.dto;

import lombok.Data;

@Data
public class LessonOutlineItemDto {
    private String title;
    private String summary;
    private Integer order;
}
