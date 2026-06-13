package com.microservices.courseservice.controller;

import com.microservices.courseservice.dto.GradeSheetResponseDto;
import com.microservices.courseservice.service.GradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/teacher")
public class TeacherGradeController {

    private final GradeService gradeService;

    @GetMapping("/courses/{courseId}/lessons/{lessonId}/grade-sheet")
    public GradeSheetResponseDto getGradeSheet(
            @PathVariable Long courseId,
            @PathVariable Long lessonId,
            @AuthenticationPrincipal Jwt jwt) {
        return gradeService.getGradeSheet(courseId, lessonId, jwt);
    }
}
