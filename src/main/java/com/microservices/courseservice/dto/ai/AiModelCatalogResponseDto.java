package com.microservices.courseservice.dto.ai;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AiModelCatalogResponseDto {
    private String defaultModelId;
    private boolean modelSelectionEnabled;
    private TeacherAiLimitStatusDto userLimit;
    private List<AiModelOptionDto> models;
}
