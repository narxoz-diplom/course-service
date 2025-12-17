package com.microservices.courseservice.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "videos")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Video {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column
    private String description;

    @Column(nullable = false)
    private String videoUrl; // URL в MinIO

    @Column(nullable = false)
    private String objectName; // Имя объекта в MinIO

    @Column(nullable = false)
    private Long fileSize;

    @Column(nullable = false)
    private Integer duration; // Длительность в секундах

    @Column(nullable = false)
    private Integer orderNumber = 1; // Порядок видео в уроке

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lesson_id", nullable = false)
    @JsonBackReference("lesson-videos")
    private Lesson lesson;

    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    @Column
    private LocalDateTime processedAt;

    @Enumerated(EnumType.STRING)
    private VideoStatus status = VideoStatus.UPLOADING;

    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
    }

    public enum VideoStatus {
        UPLOADING, PROCESSING, READY, FAILED
    }
}

