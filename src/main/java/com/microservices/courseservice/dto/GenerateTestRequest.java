package com.microservices.courseservice.dto;

import lombok.Data;

import java.util.List;

@Data
public class GenerateTestRequest {
    private List<Long> fileIds;
    private List<Long> lessonIds;
    private String title;
}
