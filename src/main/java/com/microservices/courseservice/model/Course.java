package com.microservices.courseservice.model;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "courses")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(length = 2000)
    private String description;

    // Optional localized variants (generated or filled later). Base fields are treated as RU by default.
    @Column
    private String titleKz;

    @Column
    private String titleEn;

    @Column(length = 2000)
    private String descriptionKz;

    @Column(length = 2000)
    private String descriptionEn;

    @Column
    private String imageUrl;

    @Column(nullable = false)
    private String instructorId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING)
    private CourseStatus status = CourseStatus.DRAFT;

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference("course-lessons")
    private List<Lesson> lessons = new ArrayList<>();

    @OneToMany(mappedBy = "course", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference("course-tests")
    private List<Test> tests = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "course_students", joinColumns = @JoinColumn(name = "course_id"))
    @Column(name = "student_id")
    private List<String> enrolledStudents = new ArrayList<>();

    /**
     * Optional display label (email or username) keyed by user id — filled on course create / enroll.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "course_participant_labels", joinColumns = @JoinColumn(name = "course_id"))
    @MapKeyColumn(name = "user_id", length = 255)
    @Column(name = "display_label", length = 512)
    private Map<String, String> participantDisplayLabels = new HashMap<>();

    @ElementCollection
    @CollectionTable(name = "course_allowed_emails", joinColumns = @JoinColumn(name = "course_id"))
    @Column(name = "email")
    private List<String> allowedEmails = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum CourseStatus {
        DRAFT, PUBLISHED, ARCHIVED
    }
}

