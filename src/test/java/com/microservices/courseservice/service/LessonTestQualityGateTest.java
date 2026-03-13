package com.microservices.courseservice.service;

import com.microservices.courseservice.dto.RagLessonDto;
import com.microservices.courseservice.dto.RagQuizQuestionDto;
import com.microservices.courseservice.exception.QualityGateException;
import com.microservices.courseservice.model.Course;
import com.microservices.courseservice.model.Lesson;
import com.microservices.courseservice.model.Question;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    private static RagLessonDto ragLesson(String title, String content) {
        RagLessonDto dto = new RagLessonDto();
        dto.setTitle(title);
        dto.setContent(content);
        return dto;
    }

    @Test
    void validateAndDeduplicateRagLessons_returnsDedupedAndOrdered() {
        List<RagLessonDto> input = List.of(
                ragLesson("Lesson One", "Content for lesson one that is long enough to pass validation."),
                ragLesson("Lesson Two", "Content for lesson two that is long enough to pass validation.")
        );
        List<RagLessonDto> result = qualityGate.validateAndDeduplicateRagLessons(input, List.of());
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getOrder()).isEqualTo(1);
        assertThat(result.get(1).getOrder()).isEqualTo(2);
    }

    @Test
    void validateAndDeduplicateRagLessons_removesDuplicatesByTitle() {
        List<RagLessonDto> input = List.of(
                ragLesson("Same Title", "Content one that is long enough to pass the minimum length."),
                ragLesson("Same Title", "Content two that is long enough to pass the minimum length.")
        );
        List<RagLessonDto> result = qualityGate.validateAndDeduplicateRagLessons(input, List.of());
        assertThat(result).hasSize(1);
    }

    @Test
    void validateAndDeduplicateRagLessons_throwsWhenAllFilteredOut() {
        List<RagLessonDto> input = List.of(ragLesson("A", "short"));
        assertThatThrownBy(() -> qualityGate.validateAndDeduplicateRagLessons(input, List.of()))
                .isInstanceOf(QualityGateException.class)
                .hasMessageContaining("No lessons passed");
    }

    private static RagQuizQuestionDto ragQuestion(String question, String correct) {
        RagQuizQuestionDto dto = new RagQuizQuestionDto();
        dto.setQuestion(question);
        dto.setCorrect(correct);
        return dto;
    }

    @Test
    void validateAndDeduplicateRagQuestions_returnsDeduped() {
        List<RagQuizQuestionDto> input = List.of(
                ragQuestion("First question with enough text?", "Yes"),
                ragQuestion("Second question with enough text?", "No")
        );
        List<RagQuizQuestionDto> result = qualityGate.validateAndDeduplicateRagQuestions(input);
        assertThat(result).hasSize(2);
    }

    @Test
    void validateAndDeduplicateRagQuestions_throwsWhenNoValidQuestions() {
        List<RagQuizQuestionDto> input = List.of(ragQuestion("short", ""));
        assertThatThrownBy(() -> qualityGate.validateAndDeduplicateRagQuestions(input))
                .isInstanceOf(QualityGateException.class)
                .hasMessageContaining("No questions passed");
    }

    @Test
    void validateQuestion_acceptsValidQuestion() {
        Question q = new Question();
        q.setText("Valid question text here?");
        q.setCorrectAnswer("Yes");
        q.setOrderNumber(1);
        qualityGate.validateQuestion(q);
    }

    @Test
    void validateQuestion_throwsWhenTextTooShort() {
        Question q = new Question();
        q.setText("short");
        q.setCorrectAnswer("A");
        q.setOrderNumber(1);
        assertThatThrownBy(() -> qualityGate.validateQuestion(q))
                .isInstanceOf(QualityGateException.class)
                .hasMessageContaining("text");
    }

    @Test
    void validateQuestion_throwsWhenOrderInvalid() {
        Question q = new Question();
        q.setText("Valid question text here?");
        q.setCorrectAnswer("Yes");
        q.setOrderNumber(0);
        assertThatThrownBy(() -> qualityGate.validateQuestion(q))
                .isInstanceOf(QualityGateException.class)
                .hasMessageContaining("orderNumber");
    }
}
