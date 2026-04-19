package com.microservices.courseservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParticipantSummaryDto {

    private String userId;
    /** Email or username from enrollment / course creation; may be null for legacy rows */
    private String displayLabel;
    private String role;
}
