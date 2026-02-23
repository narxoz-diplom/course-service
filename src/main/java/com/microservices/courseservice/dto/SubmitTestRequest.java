package com.microservices.courseservice.dto;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class SubmitTestRequest {
    private Map<String, String> answers = new HashMap<>();
    private Boolean suspiciousFlag;
}
