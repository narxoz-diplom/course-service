package com.microservices.courseservice.dto;

import lombok.Data;

@Data
public class UpdateNewsRequest {
    private String title;
    private String shortDescription;
    private String content;
    private Long imageFileId;
    private boolean removeImage;
}

