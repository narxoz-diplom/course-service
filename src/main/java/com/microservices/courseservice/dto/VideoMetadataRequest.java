package com.microservices.courseservice.dto;

import lombok.Data;

@Data
public class VideoMetadataRequest {
    private String title;
    private String description;
    private String videoUrl;
    private String objectName;
    private Long fileSize;
    private Integer duration;
    private Integer orderNumber;
    private String status;
}
