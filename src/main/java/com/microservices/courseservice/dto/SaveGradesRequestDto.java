package com.microservices.courseservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SaveGradesRequestDto {

    private Long courseId;
    private Long lessonId;
    private List<SaveGradeEntryDto> entries;
}
