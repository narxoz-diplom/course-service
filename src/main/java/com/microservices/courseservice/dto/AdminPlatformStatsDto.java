package com.microservices.courseservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPlatformStatsDto {
    private long totalCourses;
    private long uniqueInstructors;
    private long publishedCourses;
    private long draftCourses;
    private long archivedCourses;
    private long totalLessons;
    private long totalTests;
    private long totalEnrollmentSlots;
}
