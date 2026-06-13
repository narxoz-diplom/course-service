package com.microservices.courseservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "lesson_grades", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"lesson_id", "student_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LessonGrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lesson_id", nullable = false)
    private Lesson lesson;

    @Column(name = "student_id", nullable = false)
    private String studentId;

    @Column
    private Integer grade;

    @Column(columnDefinition = "TEXT")
    private String feedback;

    @Column(name = "graded_by", nullable = false)
    private String gradedBy;

    @Column(name = "graded_at", nullable = false)
    private LocalDateTime gradedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (gradedAt == null) {
            gradedAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
