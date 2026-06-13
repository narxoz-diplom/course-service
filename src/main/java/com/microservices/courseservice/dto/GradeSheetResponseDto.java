package com.microservices.courseservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GradeSheetResponseDto {

    private Long courseId;
    private Long lessonId;
    private List<GradeSheetStudentDto> students;
}
