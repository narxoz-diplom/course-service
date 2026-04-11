package com.microservices.courseservice.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Entity
@Table(name = "lesson_generation_jobs")
@Data
public class LessonGenerationJob {

    public enum Status {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED
    }

    @Id
    @Column(length = 48)
    private String id;

    @Column(nullable = false)
    private Long courseId;

    @Column(nullable = false, length = 128)
    private String instructorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    /** Comma-separated lesson ids after successful from-outline job */
    @Column(columnDefinition = "TEXT")
    private String createdLessonIds;

    /** Total outline items in the current job (after filtering empty titles). */
    private Integer totalLessons;

    /** Successfully persisted lessons so far (incremental jobs). */
    private Integer completedLessons;

    @Column(length = 512)
    private String currentLessonTitle;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
