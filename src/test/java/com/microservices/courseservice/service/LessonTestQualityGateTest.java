package com.microservices.courseservice.service;

import com.microservices.courseservice.exception.QualityGateException;
import com.microservices.courseservice.model.Course;
import com.microservices.courseservice.model.Lesson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for LessonTestQualityGate: min-length, non-empty fields, order, deduplication.
 */
class LessonTestQualityGateTest {

    private static final int MIN_TITLE = 2;
    private static final int MIN_CONTENT = 50;
    private static final int MIN_QUESTION_TEXT = 10;
    private static final int MIN_QUESTION_CORRECT = 1;

    private final LessonTestQualityGate qualityGate = new LessonTestQualityGate(
            MIN_TITLE, MIN_CONTENT, MIN_QUESTION_TEXT, MIN_QUESTION_CORRECT);

    @Test
    void validateLesson_acceptsValidLesson() {
        Lesson lesson = new Lesson();
        lesson.setTitle("Valid Title");
        lesson.setContent("This is content that is long enough to pass the minimum length requirement for lessons.");
        lesson.setOrderNumber(1);
        lesson.setCourse(new Course());

        qualityGate.validateLesson(lesson);
    }

    @Test
    void validateLesson_throwsWhenTitleBlank() {
        Lesson lesson = new Lesson();
        lesson.setTitle("  ");
        lesson.setContent("A".repeat(60));
        lesson.setOrderNumber(1);

        assertThatThrownBy(() -> qualityGate.validateLesson(lesson))
                .isInstanceOf(QualityGateException.class)
                .hasMessageContaining("title");
    }

    @Test
    void validateLesson_throwsWhenContentTooShort() {
        Lesson lesson = new Lesson();
        lesson.setTitle("Good Title");
        lesson.setContent("short");
        lesson.setOrderNumber(1);

        assertThatThrownBy(() -> qualityGate.validateLesson(lesson))
                .isInstanceOf(QualityGateException.class)
                .hasMessageContaining("content");
    }

    @Test
    void validateLesson_throwsWhenOrderInvalid() {
        Lesson lesson = new Lesson();
        lesson.setTitle("Good Title");
        lesson.setContent("A".repeat(60));
        lesson.setOrderNumber(0);

        assertThatThrownBy(() -> qualityGate.validateLesson(lesson))
                .isInstanceOf(QualityGateException.class)
                .hasMessageContaining("orderNumber");
    }

    @Test
    void validateAndDeduplicateRagLessons_returnsDedupedAndOrdered() {
        List<Map<String, Object>> input = List.of(
                Map.of("title", "Lesson One", "content", "Content for lesson one that is long enough to pass validation."),
                Map.of("title", "Lesson Two", "content", "Content for lesson two that is long enough to pass validation.")
        );
        List<Map<String, Object>> result = qualityGate.validateAndDeduplicateRagLessons(input, List.of());
        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("order")).isEqualTo(1);
        assertThat(result.get(1).get("order")).isEqualTo(2);
    }

    @Test
    void validateAndDeduplicateRagLessons_removesDuplicatesByTitle() {
        List<Map<String, Object>> input = List.of(
                Map.of("title", "Same Title", "content", "Content one that is long enough to pass the minimum length."),
                Map.of("title", "Same Title", "content", "Content two that is long enough to pass the minimum length.")
        );
        List<Map<String, Object>> result = qualityGate.validateAndDeduplicateRagLessons(input, List.of());
        assertThat(result).hasSize(1);
    }

    @Test
    void validateAndDeduplicateRagLessons_throwsWhenAllFilteredOut() {
        List<Map<String, Object>> input = List.of(
                Map.of("title", "A", "content", "short")
        );
        assertThatThrownBy(() -> qualityGate.validateAndDeduplicateRagLessons(input, List.of()))
                .isInstanceOf(QualityGateException.class)
                .hasMessageContaining("No lessons passed");
    }

    @Test
    void validateAndDeduplicateRagQuestions_returnsDeduped() {
        List<Map<String, Object>> input = List.of(
                Map.of("question", "First question with enough text?", "correct", "Yes"),
                Map.of("question", "Second question with enough text?", "correct", "No")
        );
        List<Map<String, Object>> result = qualityGate.validateAndDeduplicateRagQuestions(input);
        assertThat(result).hasSize(2);
    }

    @Test
    void validateAndDeduplicateRagQuestions_throwsWhenNoValidQuestions() {
        List<Map<String, Object>> input = List.of(
                Map.of("question", "short", "correct", "")
        );
        assertThatThrownBy(() -> qualityGate.validateAndDeduplicateRagQuestions(input))
                .isInstanceOf(QualityGateException.class)
                .hasMessageContaining("No questions passed");
    }
}
