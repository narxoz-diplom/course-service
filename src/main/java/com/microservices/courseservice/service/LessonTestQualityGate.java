package com.microservices.courseservice.service;

import com.microservices.courseservice.exception.QualityGateException;
import com.microservices.courseservice.model.Lesson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Quality gates for lessons and test questions before persistence:
 * min-length content, non-empty required fields, valid order, deduplication by title/content.
 */
@Slf4j
@Component
public class LessonTestQualityGate {

    public static final int DEFAULT_MIN_LESSON_TITLE_LENGTH = 2;
    public static final int DEFAULT_MIN_LESSON_CONTENT_LENGTH = 50;
    public static final int DEFAULT_MIN_QUESTION_TEXT_LENGTH = 10;
    public static final int DEFAULT_MIN_QUESTION_CORRECT_LENGTH = 1;

    @Value("${course-service.quality-gate.min-lesson-title-length:" + DEFAULT_MIN_LESSON_TITLE_LENGTH + "}")
    private int minLessonTitleLength = DEFAULT_MIN_LESSON_TITLE_LENGTH;

    @Value("${course-service.quality-gate.min-lesson-content-length:" + DEFAULT_MIN_LESSON_CONTENT_LENGTH + "}")
    private int minLessonContentLength = DEFAULT_MIN_LESSON_CONTENT_LENGTH;

    @Value("${course-service.quality-gate.min-question-text-length:" + DEFAULT_MIN_QUESTION_TEXT_LENGTH + "}")
    private int minQuestionTextLength = DEFAULT_MIN_QUESTION_TEXT_LENGTH;

    @Value("${course-service.quality-gate.min-question-correct-length:" + DEFAULT_MIN_QUESTION_CORRECT_LENGTH + "}")
    private int minQuestionCorrectLength = DEFAULT_MIN_QUESTION_CORRECT_LENGTH;

    /** Constructor for testing with explicit limits. */
    public LessonTestQualityGate(int minLessonTitleLength, int minLessonContentLength,
                                int minQuestionTextLength, int minQuestionCorrectLength) {
        this.minLessonTitleLength = minLessonTitleLength;
        this.minLessonContentLength = minLessonContentLength;
        this.minQuestionTextLength = minQuestionTextLength;
        this.minQuestionCorrectLength = minQuestionCorrectLength;
    }

    public LessonTestQualityGate() {
        // default for Spring injection
    }

    private static String normalizeForDedup(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    /**
     * Validates a single lesson (for create/update). Throws QualityGateException on failure.
     */
    public void validateLesson(Lesson lesson) {
        if (lesson == null) {
            throw new QualityGateException("Lesson must not be null");
        }
        String title = lesson.getTitle();
        if (title == null || title.isBlank()) {
            throw new QualityGateException("Lesson title is required and must not be blank");
        }
        if (title.trim().length() < minLessonTitleLength) {
            throw new QualityGateException("Lesson title must be at least " + minLessonTitleLength + " characters");
        }
        String content = lesson.getContent();
        if (content == null) {
            content = "";
        }
        if (content.trim().length() < minLessonContentLength) {
            throw new QualityGateException("Lesson content must be at least " + minLessonContentLength + " characters");
        }
        if (lesson.getOrderNumber() == null || lesson.getOrderNumber() < 1) {
            throw new QualityGateException("Lesson orderNumber must be a positive integer");
        }
    }

    private static String getString(Map<String, Object> m, String key, String def) {
        Object v = m == null ? null : m.get(key);
        return v != null ? v.toString().trim() : def;
    }

    /**
     * Validates and deduplicates RAG lessons (Map from RAG response). Normalizes order to 1,2,3...
     * Returns filtered list; throws QualityGateException if result would be empty or validation fails.
     */
    public List<Map<String, Object>> validateAndDeduplicateRagLessons(List<Map<String, Object>> ragLessons, List<Lesson> existingLessons) {
        if (ragLessons == null || ragLessons.isEmpty()) {
            throw new QualityGateException("At least one lesson is required");
        }
        Set<String> seenTitleOrContent = new LinkedHashSet<>();
        if (existingLessons != null) {
            for (Lesson existing : existingLessons) {
                if (existing.getTitle() != null) {
                    seenTitleOrContent.add(normalizeForDedup(existing.getTitle()));
                }
                String content = existing.getContent();
                if (content != null && content.trim().length() >= minLessonContentLength) {
                    seenTitleOrContent.add(normalizeForDedup(content));
                }
            }
        }
        List<Map<String, Object>> deduped = new ArrayList<>();
        for (Map<String, Object> rl : ragLessons) {
            String title = getString(rl, "title", "");
            String content = getString(rl, "content", "");
            if (title.length() < minLessonTitleLength) {
                log.debug("Skipping lesson with too short title: '{}'", title);
                continue;
            }
            if (content.length() < minLessonContentLength) {
                log.debug("Skipping lesson with too short content: title='{}'", title);
                continue;
            }
            String normTitle = normalizeForDedup(title);
            String normContent = normalizeForDedup(content);
            if (seenTitleOrContent.contains(normTitle) || seenTitleOrContent.contains(normContent)) {
                log.debug("Skipping duplicate lesson by title/content: '{}'", title);
                continue;
            }
            seenTitleOrContent.add(normTitle);
            seenTitleOrContent.add(normContent);
            deduped.add(new HashMap<>(rl));
        }
        if (deduped.isEmpty()) {
            throw new QualityGateException(
                "No lessons passed quality gate: min title length=" + minLessonTitleLength +
                ", min content length=" + minLessonContentLength + ", duplicates removed");
        }
        // Normalize order to 1, 2, 3, ...
        for (int i = 0; i < deduped.size(); i++) {
            deduped.get(i).put("order", i + 1);
        }
        return deduped;
    }

    /**
     * Validates and deduplicates RAG quiz questions (Map from RAG response).
     * Returns filtered list; throws QualityGateException if result would be empty.
     */
    public List<Map<String, Object>> validateAndDeduplicateRagQuestions(List<Map<String, Object>> questions) {
        if (questions == null || questions.isEmpty()) {
            throw new QualityGateException("At least one question is required for the test");
        }
        Set<String> seenText = new LinkedHashSet<>();
        List<Map<String, Object>> deduped = new ArrayList<>();
        for (Map<String, Object> q : questions) {
            String text = getString(q, "question", "");
            String correct = getString(q, "correct", "");
            if (text.length() < minQuestionTextLength) {
                log.debug("Skipping question with too short text");
                continue;
            }
            if (correct.length() < minQuestionCorrectLength) {
                log.debug("Skipping question with missing or too short correct answer");
                continue;
            }
            String normText = normalizeForDedup(text);
            if (seenText.contains(normText)) {
                log.debug("Skipping duplicate question by text");
                continue;
            }
            seenText.add(normText);
            deduped.add(q);
        }
        if (deduped.isEmpty()) {
            throw new QualityGateException(
                "No questions passed quality gate: min text length=" + minQuestionTextLength +
                ", min correct answer length=" + minQuestionCorrectLength + ", duplicates removed");
        }
        return deduped;
    }
}
