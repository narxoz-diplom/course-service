package com.microservices.courseservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SaveGradeEntryDto {

    private String studentId;
    private Long enrollmentId;
    private Integer grade;
    private String feedback;
}
