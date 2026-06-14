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

    /** Internal id for matching current user; not intended for UI display. */
    private String userId;
    private String fullName;
    private String email;
    private String role;
    /** ISO-8601 enrollment timestamp, when known. */
    private String enrolledAt;
    /** Course lesson completion percent 0–100. */
    private Integer progressPercent;
    /** Public avatar URL path from auth-service, e.g. /api/files/{id}/content */
    private String avatarUrl;
    /** @deprecated use fullName/email */
    @Deprecated
    private String displayLabel;
}
