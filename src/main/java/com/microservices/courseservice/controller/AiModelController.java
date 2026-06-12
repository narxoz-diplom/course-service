package com.microservices.courseservice.controller;

import com.microservices.courseservice.dto.ai.AiModelCatalogResponseDto;
import com.microservices.courseservice.service.AiModelCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/courses/ai")
public class AiModelController {

    private final AiModelCatalogService aiModelCatalogService;

    @GetMapping("/models")
    public AiModelCatalogResponseDto listModels(
            @RequestParam(value = "capability", defaultValue = "course-generation") String capability,
            @AuthenticationPrincipal Jwt jwt) {
        return aiModelCatalogService.listModelsForUser(jwt, capability);
    }
}
