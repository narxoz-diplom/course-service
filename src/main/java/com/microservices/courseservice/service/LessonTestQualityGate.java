package com.microservices.courseservice.service;

import com.microservices.courseservice.dto.RagLessonDto;
import com.microservices.courseservice.dto.RagQuizQuestionDto;
import com.microservices.courseservice.exception.QualityGateException;
import com.microservices.courseservice.model.Lesson;
import com.microservices.courseservice.model.Question;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

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

    /** Drop batch lesson if Jaccard(word) similarity with an earlier kept lesson exceeds this (0–1). */
    @Value("${course-service.quality-gate.max-batch-content-jaccard:0.92}")
    private double maxBatchContentJaccard = 0.92;

    /** Constructor for testing with explicit limits. */
    public LessonTestQualityGate(int minLessonTitleLength, int minLessonContentLength,
                                int minQuestionTextLength, int minQuestionCorrectLength) {
        this.minLessonTitleLength = minLessonTitleLength;
        this.minLessonContentLength = minLessonContentLength;
        this.minQuestionTextLength = minQuestionTextLength;
        this.minQuestionCorrectLength = minQuestionCorrectLength;
    }

    public LessonTestQualityGate(int minLessonTitleLength, int minLessonContentLength,
                                 int minQuestionTextLength, int minQuestionCorrectLength,
                                 double maxBatchContentJaccard) {
        this.minLessonTitleLength = minLessonTitleLength;
        this.minLessonContentLength = minLessonContentLength;
        this.minQuestionTextLength = minQuestionTextLength;
        this.minQuestionCorrectLength = minQuestionCorrectLength;
        this.maxBatchContentJaccard = maxBatchContentJaccard;
    }

    public LessonTestQualityGate() {
        // default for Spring injection
    }

    private static String normalizeForDedup(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static java.util.Set<String> wordBag(String text) {
        if (text == null || text.isBlank()) {
            return java.util.Collections.emptySet();
        }
        String[] parts = normalizeForDedup(text).split("[^\\p{L}\\p{N}]+");
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String p : parts) {
            if (p.length() > 1) {
                out.add(p);
            }
        }
        return out;
    }

    static double jaccardWordSimilarity(String a, String b) {
        java.util.Set<String> sa = wordBag(a);
        java.util.Set<String> sb = wordBag(b);
        if (sa.isEmpty() && sb.isEmpty()) {
            return 1.0;
        }
        if (sa.isEmpty() || sb.isEmpty()) {
            return 0.0;
        }
        java.util.Set<String> inter = new java.util.HashSet<>(sa);
        inter.retainAll(sb);
        java.util.Set<String> union = new java.util.HashSet<>(sa);
        union.addAll(sb);
        return union.isEmpty() ? 0.0 : (double) inter.size() / (double) union.size();
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

    /**
     * Validates a single question (for create/update). Throws QualityGateException on failure.
     */
    public void validateQuestion(Question question) {
        if (question == null) {
            throw new QualityGateException("Question must not be null");
        }
        String text = question.getText();
        if (text == null || text.isBlank()) {
            throw new QualityGateException("Question text is required and must not be blank");
        }
        if (text.trim().length() < minQuestionTextLength) {
            throw new QualityGateException("Question text must be at least " + minQuestionTextLength + " characters");
        }
        String correct = question.getCorrectAnswer();
        if (correct == null) {
            correct = "";
        }
        if (correct.trim().length() < minQuestionCorrectLength) {
            throw new QualityGateException("Question correct answer must be at least " + minQuestionCorrectLength + " character(s)");
        }
        if (question.getOrderNumber() == null || question.getOrderNumber() < 1) {
            throw new QualityGateException("Question orderNumber must be a positive integer");
        }
    }

    /**
     * Validates and deduplicates RAG lessons (DTO). Returns filtered list with normalized order 1,2,3...
     * Throws QualityGateException if result would be empty.
     */
    public List<RagLessonDto> validateAndDeduplicateRagLessons(List<RagLessonDto> ragLessons, List<Lesson> existingLessons) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (RagLessonDto dto : ragLessons != null ? ragLessons : List.<RagLessonDto>of()) {
            Map<String, Object> m = new HashMap<>();
            m.put("title", dto.getTitle());
            m.put("content", dto.getContent());
            m.put("description", dto.getDescription());
            m.put("order", dto.getOrder());
            maps.add(m);
        }
        List<Map<String, Object>> validated = validateAndDeduplicateRagLessonsMaps(maps, existingLessons);
        List<RagLessonDto> result = new ArrayList<>();
        for (int i = 0; i < validated.size(); i++) {
            Map<String, Object> m = validated.get(i);
            RagLessonDto dto = new RagLessonDto();
            dto.setTitle(getString(m, "title", ""));
            dto.setContent(getString(m, "content", ""));
            dto.setDescription(getString(m, "description", ""));
            dto.setOrder(i + 1);
            result.add(dto);
        }
        return result;
    }

    /**
     * Validates and deduplicates RAG quiz questions (DTO). Returns filtered list.
     * Throws QualityGateException if result would be empty.
     */
    public List<RagQuizQuestionDto> validateAndDeduplicateRagQuestions(List<RagQuizQuestionDto> questions) {
        if (questions == null || questions.isEmpty()) {
            throw new QualityGateException("At least one question is required for the test");
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (RagQuizQuestionDto dto : questions) {
            Map<String, Object> m = new HashMap<>();
            m.put("question", dto.getQuestion());
            m.put("correct", dto.getCorrect());
            m.put("options", dto.getOptions());
            m.put("explanation", dto.getExplanation());
            m.put("hint", dto.getHint());
            maps.add(m);
        }
        List<Map<String, Object>> validated = validateAndDeduplicateRagQuestionsMaps(maps);
        List<RagQuizQuestionDto> result = new ArrayList<>();
        for (Map<String, Object> m : validated) {
            RagQuizQuestionDto dto = new RagQuizQuestionDto();
            dto.setQuestion(getString(m, "question", ""));
            dto.setCorrect(getString(m, "correct", ""));
            dto.setOptions(m.get("options"));
            dto.setExplanation(getString(m, "explanation", ""));
            dto.setHint(getString(m, "hint", ""));
            result.add(dto);
        }
        return result;
    }

    private static String getString(Map<String, Object> m, String key, String def) {
        Object v = m == null ? null : m.get(key);
        return v != null ? v.toString().trim() : def;
    }

    /**
     * Validates and deduplicates RAG lessons (Map from RAG response). Normalizes order to 1,2,3...
     * Returns filtered list; throws QualityGateException if result would be empty or validation fails.
     */
    private List<Map<String, Object>> validateAndDeduplicateRagLessonsMaps(List<Map<String, Object>> ragLessons, List<Lesson> existingLessons) {
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
        List<Map<String, Object>> lowSimilarity = filterNearDuplicateLessonBodies(deduped);
        if (lowSimilarity.isEmpty()) {
            throw new QualityGateException(
                "No lessons passed quality gate: min title length=" + minLessonTitleLength +
                ", min content length=" + minLessonContentLength + ", duplicates or near-duplicates removed");
        }
        // Normalize order to 1, 2, 3, ...
        for (int i = 0; i < lowSimilarity.size(); i++) {
            lowSimilarity.get(i).put("order", i + 1);
        }
        return lowSimilarity;
    }

    /**
     * Remove lessons whose body is almost identical (Jaccard on words) to an earlier kept lesson.
     */
    private List<Map<String, Object>> filterNearDuplicateLessonBodies(List<Map<String, Object>> lessons) {
        if (lessons == null || lessons.isEmpty()) {
            return lessons;
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> candidate : lessons) {
            String ctext = getString(candidate, "content", "");
            boolean tooSimilar = false;
            for (Map<String, Object> k : kept) {
                double sim = jaccardWordSimilarity(ctext, getString(k, "content", ""));
                if (sim >= maxBatchContentJaccard) {
                    tooSimilar = true;
                    log.debug("Skipping near-duplicate lesson content (Jaccard {} >= {})", sim, maxBatchContentJaccard);
                    break;
                }
            }
            if (!tooSimilar) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    /**
     * Validates and deduplicates RAG quiz questions (Map from RAG response).
     * Returns filtered list; throws QualityGateException if result would be empty.
     */
    private List<Map<String, Object>> validateAndDeduplicateRagQuestionsMaps(List<Map<String, Object>> questions) {
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
