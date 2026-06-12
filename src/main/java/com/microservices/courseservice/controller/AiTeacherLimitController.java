package com.microservices.courseservice.controller;

import com.microservices.courseservice.dto.ai.TeacherAiLimitStatusDto;
import com.microservices.courseservice.dto.ai.UpdateTeacherAiLimitRequest;
import com.microservices.courseservice.service.TeacherAiLimitService;
import com.microservices.courseservice.util.RoleUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/courses")
public class AiTeacherLimitController {

    private final TeacherAiLimitService teacherAiLimitService;

    @GetMapping("/ai/usage/me/limit")
    public TeacherAiLimitStatusDto myLimit(@AuthenticationPrincipal Jwt jwt) {
        requireTeacherOrAdmin(jwt);
        return teacherAiLimitService.statusForTeacher(
                jwt.getSubject(), RoleUtil.isAdmin(jwt));
    }

    @GetMapping("/admin/ai/teacher-limits/{userId}")
    public TeacherAiLimitStatusDto adminGet(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String userId) {
        requireAdmin(jwt);
        return teacherAiLimitService.adminGet(userId.trim());
    }

    @PutMapping("/admin/ai/teacher-limits/{userId}")
    public TeacherAiLimitStatusDto adminUpdate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String userId,
            @RequestBody UpdateTeacherAiLimitRequest request) {
        requireAdmin(jwt);
        return teacherAiLimitService.adminUpdate(userId.trim(), jwt.getSubject(), request);
    }

    @DeleteMapping("/admin/ai/teacher-limits/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void adminResetToDefault(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String userId) {
        requireAdmin(jwt);
        teacherAiLimitService.adminDeleteOverride(userId.trim());
    }

    private static void requireTeacherOrAdmin(Jwt jwt) {
        if (jwt == null || (!RoleUtil.isTeacher(jwt) && !RoleUtil.isAdmin(jwt))) {
            throw new AccessDeniedException("Teacher or admin role required");
        }
    }

    private static void requireAdmin(Jwt jwt) {
        if (jwt == null || !RoleUtil.isAdmin(jwt)) {
            throw new AccessDeniedException("Admin role required");
        }
    }
}
