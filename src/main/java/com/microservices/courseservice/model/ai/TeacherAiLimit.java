package com.microservices.courseservice.model.ai;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "teacher_ai_limits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeacherAiLimit {

    @Id
    @Column(name = "teacher_id", length = 128)
    private String teacherId;

    @Column(name = "unlimited_access", nullable = false)
    @Builder.Default
    private boolean unlimitedAccess = false;

    @Column(name = "monthly_token_limit")
    private Long monthlyTokenLimit;

    @Column(name = "daily_token_limit")
    private Long dailyTokenLimit;

    @Column(length = 512)
    private String note;

    @Column(name = "updated_by", length = 128)
    private String updatedBy;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
