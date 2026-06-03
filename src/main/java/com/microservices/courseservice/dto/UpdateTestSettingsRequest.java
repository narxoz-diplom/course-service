package com.microservices.courseservice.dto;

import lombok.Data;

@Data
public class UpdateTestSettingsRequest {
    private Integer maxAttempts;
    private String dueAt;
}

