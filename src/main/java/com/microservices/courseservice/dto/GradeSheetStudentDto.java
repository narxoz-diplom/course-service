package com.microservices.courseservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GradeSheetStudentDto {

    private String studentId;
    private String fullName;
    private String studyStatus;
    private Integer grade;
    private String feedback;
}
