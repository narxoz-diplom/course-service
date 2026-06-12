package com.microservices.courseservice.controller;

import com.microservices.courseservice.dto.ai.usage.AdminAiUsageReportDto;
import com.microservices.courseservice.dto.ai.usage.TeacherAiUsageReportDto;
import com.microservices.courseservice.service.AiUsageReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/courses")
public class AiUsageController {

    private final AiUsageReportService aiUsageReportService;

    @GetMapping("/ai/usage/me")
    public TeacherAiUsageReportDto myUsage(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return aiUsageReportService.teacherUsage(jwt, from, to);
    }

    @GetMapping("/admin/ai/usage")
    public AdminAiUsageReportDto adminUsage(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) String modelId,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String generationType,
            @RequestParam(required = false) String status) {
        return aiUsageReportService.adminUsage(
                jwt, from, to, userId, courseId, modelId, provider, generationType, status);
    }
}
