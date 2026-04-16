package com.microservices.courseservice.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "questions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestionType type = QuestionType.MULTIPLE_CHOICE;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(columnDefinition = "TEXT")
    private String options;

    @Column(columnDefinition = "TEXT")
    private String textKz;

    @Column(columnDefinition = "TEXT")
    private String textEn;

    @Column(columnDefinition = "TEXT")
    private String optionsKz;

    @Column(columnDefinition = "TEXT")
    private String optionsEn;

    @Column(columnDefinition = "TEXT")
    private String correctAnswer;

    @Column(columnDefinition = "TEXT")
    private String explanation;

    @Column(columnDefinition = "TEXT")
    private String hint;

    @Column(columnDefinition = "TEXT")
    private String explanationKz;

    @Column(columnDefinition = "TEXT")
    private String explanationEn;

    @Column(columnDefinition = "TEXT")
    private String hintKz;

    @Column(columnDefinition = "TEXT")
    private String hintEn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_id", nullable = false)
    @JsonBackReference("test-questions")
    private Test test;

    @Column(nullable = false)
    private Integer orderNumber;

    public enum QuestionType {
        MULTIPLE_CHOICE,
        MULTIPLE_ANSWER,
        TRUE_FALSE,
        MATCHING,
        OPEN_ENDED
    }
}
