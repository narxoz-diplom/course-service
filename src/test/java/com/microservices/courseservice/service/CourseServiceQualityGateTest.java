package com.microservices.courseservice.service;

import com.microservices.courseservice.exception.QualityGateException;
import com.microservices.courseservice.model.Course;
import com.microservices.courseservice.model.Lesson;
import com.microservices.courseservice.repository.CourseRepository;
import com.microservices.courseservice.repository.LessonRepository;
import com.microservices.courseservice.repository.QuestionRepository;
import com.microservices.courseservice.repository.TestAttemptRepository;
import com.microservices.courseservice.repository.TestRepository;
import com.microservices.courseservice.client.AuthServiceClient;
import com.microservices.courseservice.client.FileServiceClient;
import com.microservices.courseservice.client.RagClient;
import com.microservices.courseservice.mapper.CourseMapper;
import com.microservices.courseservice.mapper.VideoMapper;
import com.microservices.courseservice.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CourseService quality-gate integration: validation before save, 1 regenerate on fail.
 */
@ExtendWith(MockitoExtension.class)
class CourseServiceQualityGateTest {

    @Mock private CourseRepository courseRepository;
    @Mock private LessonRepository lessonRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private CourseCacheService courseCacheService;
    @Mock private LessonService lessonService;
    @Mock private CacheService cacheService;
    @Mock private RagClient ragClient;
    @Mock private TestRepository testRepository;
    @Mock private TestAttemptRepository testAttemptRepository;
    @Mock private QuestionRepository questionRepository;
    @Mock private LessonTestQualityGate qualityGate;
    @Mock private AuthServiceClient authServiceClient;
    @Mock private FileServiceClient fileServiceClient;
    @Mock private CourseMapper courseMapper;
    @Mock private VideoMapper videoMapper;

    private CourseService courseService;
    private Course course;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        courseService = new CourseService(
                courseRepository, lessonRepository, videoRepository,
                courseCacheService, lessonService, cacheService, courseMapper, videoMapper,
                authServiceClient, fileServiceClient, ragClient,
                testRepository, testAttemptRepository, questionRepository, qualityGate);
        course = new Course();
        course.setId(1L);
        course.setTitle("Test Course");
        course.setInstructorId("instructor-1");
        course.setStatus(Course.CourseStatus.DRAFT);
        jwt = org.mockito.Mockito.mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("instructor-1");
        Map<String, Object> realmAccess = new HashMap<>();
        realmAccess.put("roles", List.of("teacher"));
        when(jwt.getClaim("realm_access")).thenReturn(realmAccess);
    }

    @Test
    void generateLessonsFromFiles_usesQualityGateAndSavesValidLessons() {
        when(courseRepository.findById(1L)).thenReturn(Optional.of(course));
        when(lessonService.getLessonsByCourse(1L)).thenReturn(List.of());
        List<Map<String, Object>> ragLessons = List.of(
                Map.of("title", "Lesson One", "content", "Content that is long enough to pass the minimum length for lessons."),
                Map.of("title", "Lesson Two", "content", "Another content that is long enough to pass the minimum length.")
        );
        when(ragClient.generateLessons(eq("course_1"), eq(null), eq(null))).thenReturn(ragLessons);
        List<Map<String, Object>> validated = List.of(
                Map.of("title", "Lesson One", "content", "Content that is long enough to pass the minimum length for lessons.", "order", 1),
                Map.of("title", "Lesson Two", "content", "Another content that is long enough to pass the minimum length.", "order", 2)
        );
        when(qualityGate.validateAndDeduplicateRagLessons(ragLessons, List.of())).thenReturn(validated);
        Lesson saved1 = new Lesson();
        saved1.setId(10L);
        Lesson saved2 = new Lesson();
        saved2.setId(11L);
        when(lessonService.createLesson(any(Lesson.class), eq(course), eq(jwt))).thenReturn(saved1, saved2);

        List<Lesson> result = courseService.generateLessonsFromFiles(1L, null, jwt);

        verify(qualityGate, times(1)).validateAndDeduplicateRagLessons(ragLessons, List.of());
        verify(lessonService, times(2)).createLesson(any(Lesson.class), eq(course), eq(jwt));
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(10L);
        assertThat(result.get(1).getId()).isEqualTo(11L);
    }

    @Test
    void generateLessonsFromFiles_regeneratesOnceWhenQualityGateFailsFirstTime() {
        when(courseRepository.findById(1L)).thenReturn(Optional.of(course));
        when(lessonService.getLessonsByCourse(1L)).thenReturn(List.of());
        List<Map<String, Object>> firstRag = List.of(Map.of("title", "A", "content", "short"));
        List<Map<String, Object>> secondRag = List.of(
                Map.of("title", "Good Lesson", "content", "Content that is long enough to pass the minimum length for lessons.")
        );
        when(ragClient.generateLessons(eq("course_1"), eq(null), eq(null))).thenReturn(firstRag).thenReturn(secondRag);
        when(qualityGate.validateAndDeduplicateRagLessons(firstRag, List.of()))
                .thenThrow(new QualityGateException("No lessons passed"));
        List<Map<String, Object>> validated = List.of(
                Map.of("title", "Good Lesson", "content", "Content that is long enough to pass the minimum length for lessons.", "order", 1)
        );
        when(qualityGate.validateAndDeduplicateRagLessons(secondRag, List.of())).thenReturn(validated);
        Lesson saved = new Lesson();
        saved.setId(10L);
        when(lessonService.createLesson(any(Lesson.class), eq(course), eq(jwt))).thenReturn(saved);

        List<Lesson> result = courseService.generateLessonsFromFiles(1L, null, jwt);

        verify(ragClient, times(2)).generateLessons(eq("course_1"), eq(null), eq(null));
        verify(qualityGate, times(1)).validateAndDeduplicateRagLessons(firstRag, List.of());
        verify(qualityGate, times(1)).validateAndDeduplicateRagLessons(secondRag, List.of());
        assertThat(result).hasSize(1);
    }

    @Test
    void generateLessonsFromFiles_throwsWhenQualityGateFailsAfterRegenerate() {
        when(courseRepository.findById(1L)).thenReturn(Optional.of(course));
        when(lessonService.getLessonsByCourse(1L)).thenReturn(List.of());
        List<Map<String, Object>> badRag = List.of(Map.of("title", "A", "content", "short"));
        when(ragClient.generateLessons(eq("course_1"), eq(null), eq(null))).thenReturn(badRag);
        when(qualityGate.validateAndDeduplicateRagLessons(any(), any()))
                .thenThrow(new QualityGateException("No lessons passed"));

        assertThatThrownBy(() -> courseService.generateLessonsFromFiles(1L, null, jwt))
                .isInstanceOf(QualityGateException.class)
                .hasMessageContaining("No lessons passed");

        verify(ragClient, times(2)).generateLessons(eq("course_1"), eq(null), eq(null));
        verify(lessonService, never()).createLesson(any(), any(), any());
    }
}
