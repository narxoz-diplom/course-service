package com.microservices.courseservice.service;

import com.microservices.courseservice.client.RagClient;
import com.microservices.courseservice.dto.LessonGenerationParamsDto;
import com.microservices.courseservice.dto.LessonOutlineItemDto;
import com.microservices.courseservice.dto.RagLessonDto;
import com.microservices.courseservice.exception.QualityGateException;
import com.microservices.courseservice.model.Course;
import com.microservices.courseservice.model.Lesson;
import com.microservices.courseservice.repository.CourseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * One outline lesson = one transaction so a long-running job commits incrementally.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutlineLessonStepService {

    private final CourseRepository courseRepository;
    private final RagClient ragClient;
    private final LessonService lessonService;
    private final LessonTestQualityGate qualityGate;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Lesson generateAndPersistOne(
            Long courseId,
            String collectionName,
            List<Long> filterFileIds,
            LessonOutlineItemDto item,
            int lessonIndexOneBased,
            int totalLessons,
            List<Lesson> priorLessonsForQualityGate,
            LessonGenerationParamsDto params,
            Jwt jwt) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Course not found with id: " + courseId));

        RagLessonDto dto = ragClient.generateSingleLesson(
                collectionName,
                filterFileIds,
                item.getTitle(),
                item.getSummary() != null ? item.getSummary() : "",
                lessonIndexOneBased,
                totalLessons,
                24,
                params);

        List<RagLessonDto> validated = qualityGate.validateAndDeduplicateRagLessons(
                List.of(dto), priorLessonsForQualityGate);
        if (validated.isEmpty()) {
            throw new QualityGateException("Generated lesson did not pass quality gate: " + item.getTitle());
        }
        RagLessonDto rl = validated.get(0);
        Lesson lesson = new Lesson();
        lesson.setTitle(rl.getTitle() != null && !rl.getTitle().isBlank() ? rl.getTitle() : item.getTitle());
        lesson.setContent(rl.getContent() != null ? rl.getContent() : "");
        lesson.setDescription(rl.getDescription() != null ? rl.getDescription() : "");
        lesson.setOrderNumber(lessonIndexOneBased);
        lesson.setCourse(course);

        Lesson saved = lessonService.createLesson(lesson, course, jwt);

        String content = rl.getContent() != null ? rl.getContent() : "";
        if (!content.isBlank()) {
            try {
                ragClient.vectorizeText(
                        content,
                        collectionName,
                        Map.of("lesson_id", String.valueOf(saved.getId()), "course_id", String.valueOf(courseId)));
            } catch (Exception e) {
                log.warn("Failed to vectorize lesson {}: {}", saved.getId(), e.getMessage());
            }
        }
        return saved;
    }
}
