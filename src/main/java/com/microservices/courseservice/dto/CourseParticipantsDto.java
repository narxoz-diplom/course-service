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
public class CourseParticipantsDto {

    private ParticipantSummaryDto instructor;
    private List<ParticipantSummaryDto> students;
    private int studentCount;
}
