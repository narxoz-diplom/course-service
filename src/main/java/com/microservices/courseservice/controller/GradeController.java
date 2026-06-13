package com.microservices.courseservice.controller;

import com.microservices.courseservice.dto.SaveGradesRequestDto;
import com.microservices.courseservice.dto.SaveGradesResponseDto;
import com.microservices.courseservice.dto.StudentGradesResponseDto;
import com.microservices.courseservice.service.GradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/grades")
public class GradeController {

    private final GradeService gradeService;

    @GetMapping("/my")
    public StudentGradesResponseDto getMyGrades(
            @RequestParam(value = "courseId", required = false) Long courseId,
            @AuthenticationPrincipal Jwt jwt) {
        return gradeService.getMyGrades(jwt, courseId);
    }

    @PostMapping("/save")
    public SaveGradesResponseDto saveGrades(
            @RequestBody SaveGradesRequestDto request,
            @AuthenticationPrincipal Jwt jwt) {
        return gradeService.saveGrades(request, jwt);
    }
}
