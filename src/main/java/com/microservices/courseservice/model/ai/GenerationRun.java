package com.microservices.courseservice.model.ai;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "generation_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerationRun {

    @Id
    @Column(length = 48)
    private String id;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "teacher_id", nullable = false, length = 128)
    private String teacherId;

    @Column(name = "course_id")
    private Long courseId;

    @Column(name = "job_id", length = 48)
    private String jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "generation_type", nullable = false, length = 40)
    private GenerationType generationType;

    @Column(name = "requested_model_id", nullable = false, length = 64)
    private String requestedModelId;

    @Column(name = "actual_model_id", length = 64)
    private String actualModelId;

    @Column(name = "actual_provider", length = 32)
    private String actualProvider;

    @Column(name = "actual_provider_model_id", length = 128)
    private String actualProviderModelId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GenerationRunStatus status;

    @Column(name = "rag_request_id", length = 128)
    private String ragRequestId;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;
}
