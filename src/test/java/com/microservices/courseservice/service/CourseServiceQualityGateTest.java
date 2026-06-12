package com.microservices.courseservice.service;

import com.microservices.courseservice.client.AuthServiceClient;
import com.microservices.courseservice.client.FileServiceClient;
import com.microservices.courseservice.client.RagClient;
import com.microservices.courseservice.dto.GenerateLessonsRequest;
import com.microservices.courseservice.dto.GenerateTestRequest;
import com.microservices.courseservice.dto.RagGenerationContext;
import com.microservices.courseservice.dto.RagLessonDto;
import com.microservices.courseservice.dto.RagLessonsResponse;
import com.microservices.courseservice.dto.RagQuizQuestionDto;
import com.microservices.courseservice.dto.RagQuizResponse;
import com.microservices.courseservice.exception.QualityGateException;
import com.microservices.courseservice.mapper.CourseMapper;
import com.microservices.courseservice.mapper.VideoMapper;
import com.microservices.courseservice.model.Course;
import com.microservices.courseservice.model.Lesson;
import com.microservices.courseservice.ai.AiModelConstants;
import com.microservices.courseservice.model.ai.AiModel;
import com.microservices.courseservice.model.ai.GenerationRun;
import com.microservices.courseservice.model.ai.GenerationRunStatus;
import com.microservices.courseservice.model.ai.GenerationType;
import com.microservices.courseservice.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

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
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private OutlineLessonStepService outlineLessonStepService;
    @Mock private LessonGenerationJobRepository lessonGenerationJobRepository;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private AiModelCatalogService aiModelCatalogService;
    @Mock private GenerationRunService generationRunService;
    @Mock private TokenUsageLedgerService tokenUsageLedgerService;

    private AiGenerationOrchestratorService aiGenerationOrchestrator;
    private CourseService courseService;
    private Course course;
    private Jwt jwt;
    private GenerationRun generationRun;

    @BeforeEach
    void setUp() {
        aiGenerationOrchestrator = new AiGenerationOrchestratorService(
                aiModelCatalogService,
                generationRunService,
                tokenUsageLedgerService,
                new AiGenerationMetricsService(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        courseService = new CourseService(
                courseRepository, lessonRepository, videoRepository,
                courseCacheService, lessonService, cacheService, courseMapper, videoMapper,
                authServiceClient, fileServiceClient, ragClient,
                testRepository, testAttemptRepository, questionRepository, qualityGate,
                applicationEventPublisher, outlineLessonStepService, lessonGenerationJobRepository,
                transactionManager, aiGenerationOrchestrator);
        course = new Course();
        course.setId(1L);
        course.setTitle("Test Course");
        course.setInstructorId("instructor-1");
        course.setStatus(Course.CourseStatus.DRAFT);
        jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("instructor-1")
                .claim("realm_access", Map.of("roles", List.of("teacher")))
                .build();
        generationRun = GenerationRun.builder()
                .id("gr_test")
                .teacherId("instructor-1")
                .status(GenerationRunStatus.RUNNING)
                .requestedModelId("gemini-2.5-flash")
                .generationType(GenerationType.LESSON_FROM_FILES)
                .build();
        AiModel model = AiModel.builder()
                .id(AiModelConstants.DEFAULT_MODEL_ID)
                .provider("google")
                .selectable(true)
                .build();
        lenient().when(aiModelCatalogService.resolveModelForGeneration(any(), any(), any())).thenReturn(model);
        lenient().when(generationRunService.beginRun(any(), any(), any(), any(), any(), any()))
                .thenReturn(generationRun);
        lenient().when(generationRunService.markRunning(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(generationRunService.markSucceeded(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(generationRunService.markFailed(any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        TransactionStatus txStatus = new SimpleTransactionStatus();
        lenient().when(transactionManager.getTransaction(any())).thenReturn(txStatus);
        courseService.initReadOnlyCourseTemplate();
    }

    private static RagLessonDto ragLesson(String title, String content, Integer order) {
        RagLessonDto dto = new RagLessonDto();
        dto.setTitle(title);
        dto.setContent(content);
        dto.setOrder(order);
        return dto;
    }

    private static RagLessonsResponse lessonsResponse(List<RagLessonDto> lessons) {
        RagLessonsResponse response = new RagLessonsResponse();
        response.setLessons(lessons);
        return response;
    }

    @Test
    void generateLessonsFromFiles_usesQualityGateAndSavesValidLessons() {
        when(courseRepository.findById(1L)).thenReturn(Optional.of(course));
        when(lessonService.getLessonsByCourse(1L)).thenReturn(List.of());
        List<RagLessonDto> ragLessons = List.of(
                ragLesson("Lesson One", "Content that is long enough to pass the minimum length for lessons.", null),
                ragLesson("Lesson Two", "Another content that is long enough to pass the minimum length.", null)
        );
        when(ragClient.generateLessonsResponse(
                eq("course_1"), eq(null), isNull(), isNull(), isNull(), any(), any()))
                .thenReturn(lessonsResponse(ragLessons));
        List<RagLessonDto> validated = List.of(
                ragLesson("Lesson One", "Content that is long enough to pass the minimum length for lessons.", 1),
                ragLesson("Lesson Two", "Another content that is long enough to pass the minimum length.", 2)
        );
        when(qualityGate.validateAndDeduplicateRagLessons(ragLessons, List.of())).thenReturn(validated);
        Lesson saved1 = new Lesson();
        saved1.setId(10L);
        Lesson saved2 = new Lesson();
        saved2.setId(11L);
        when(lessonService.createLesson(any(Lesson.class), eq(course), eq(jwt))).thenReturn(saved1, saved2);

        var result = courseService.generateLessonsFromFiles(1L, new GenerateLessonsRequest(), jwt);

        verify(qualityGate, times(1)).validateAndDeduplicateRagLessons(ragLessons, List.of());
        verify(lessonService, times(2)).createLesson(any(Lesson.class), eq(course), eq(jwt));
        assertThat(result.getLessons()).hasSize(2);
        assertThat(result.getLessons().get(0).getId()).isEqualTo(10L);
        assertThat(result.getLessons().get(1).getId()).isEqualTo(11L);
    }

    @Test
    void generateLessonsFromFiles_regeneratesOnceWhenQualityGateFailsFirstTime() {
        when(courseRepository.findById(1L)).thenReturn(Optional.of(course));
        when(lessonService.getLessonsByCourse(1L)).thenReturn(List.of());
        List<RagLessonDto> firstRag = List.of(ragLesson("A", "short", null));
        List<RagLessonDto> secondRag = List.of(
                ragLesson("Good Lesson", "Content that is long enough to pass the minimum length for lessons.", null)
        );
        when(ragClient.generateLessonsResponse(
                eq("course_1"), eq(null), isNull(), isNull(), isNull(), any(), any()))
                .thenReturn(lessonsResponse(firstRag))
                .thenReturn(lessonsResponse(secondRag));
        when(qualityGate.validateAndDeduplicateRagLessons(firstRag, List.of()))
                .thenThrow(new QualityGateException("No lessons passed"));
        List<RagLessonDto> validated = List.of(
                ragLesson("Good Lesson", "Content that is long enough to pass the minimum length for lessons.", 1)
        );
        when(qualityGate.validateAndDeduplicateRagLessons(secondRag, List.of())).thenReturn(validated);
        Lesson saved = new Lesson();
        saved.setId(10L);
        when(lessonService.createLesson(any(Lesson.class), eq(course), eq(jwt))).thenReturn(saved);

        var result = courseService.generateLessonsFromFiles(1L, new GenerateLessonsRequest(), jwt);

        verify(ragClient, times(2)).generateLessonsResponse(
                eq("course_1"), eq(null), isNull(), isNull(), isNull(), any(), any());
        verify(qualityGate, times(1)).validateAndDeduplicateRagLessons(firstRag, List.of());
        verify(qualityGate, times(1)).validateAndDeduplicateRagLessons(secondRag, List.of());
        assertThat(result.getLessons()).hasSize(1);
    }

    @Test
    void generateLessonsFromFiles_throwsWhenQualityGateFailsAfterRegenerate() {
        when(courseRepository.findById(1L)).thenReturn(Optional.of(course));
        when(lessonService.getLessonsByCourse(1L)).thenReturn(List.of());
        List<RagLessonDto> badRag = List.of(ragLesson("A", "short", null));
        when(ragClient.generateLessonsResponse(
                eq("course_1"), eq(null), isNull(), isNull(), isNull(), any(), any()))
                .thenReturn(lessonsResponse(badRag));
        when(qualityGate.validateAndDeduplicateRagLessons(any(), any()))
                .thenThrow(new QualityGateException("No lessons passed"));

        assertThatThrownBy(() -> courseService.generateLessonsFromFiles(1L, new GenerateLessonsRequest(), jwt))
                .isInstanceOf(QualityGateException.class)
                .hasMessageContaining("No lessons passed");

        verify(ragClient, times(2)).generateLessonsResponse(
                eq("course_1"), eq(null), isNull(), isNull(), isNull(), any(), any());
        verify(lessonService, never()).createLesson(any(), any(), any());
    }

    private static RagQuizQuestionDto ragQuestion(String question, String correct) {
        RagQuizQuestionDto dto = new RagQuizQuestionDto();
        dto.setQuestion(question);
        dto.setCorrect(correct);
        return dto;
    }

    private static RagQuizResponse quizResponse(List<RagQuizQuestionDto> questions) {
        RagQuizResponse response = new RagQuizResponse();
        response.setQuestions(questions);
        return response;
    }

    @Test
    void generateTest_usesQualityGateAndSavesValidQuestions() {
        when(courseRepository.findById(1L)).thenReturn(Optional.of(course));
        List<RagQuizQuestionDto> ragQuestions = List.of(
                ragQuestion("First question with enough text?", "Yes"),
                ragQuestion("Second question with enough text?", "No")
        );
        when(ragClient.generateQuizResponse(
                eq("course_1"), eq(null), eq(null), eq(null), isNull(), isNull(), any(), any()))
                .thenReturn(quizResponse(ragQuestions));
        when(qualityGate.validateAndDeduplicateRagQuestions(ragQuestions)).thenReturn(ragQuestions);
        when(testRepository.save(any(com.microservices.courseservice.model.Test.class))).thenAnswer(inv -> {
            com.microservices.courseservice.model.Test t = inv.getArgument(0);
            t.setId(100L);
            return t;
        });

        GenerateTestRequest tr = new GenerateTestRequest();
        tr.setFileIds(null);
        tr.setLessonIds(null);
        tr.setTitle("Test Title");
        var result = courseService.generateTest(1L, tr, jwt);

        verify(qualityGate, times(1)).validateAndDeduplicateRagQuestions(ragQuestions);
        verify(questionRepository, times(2)).save(any());
        assertThat(result.getTest().getId()).isEqualTo(100L);
        assertThat(result.getTest().getTitle()).isEqualTo("Test Title");
    }

    @Test
    void generateTest_regeneratesOnceWhenQualityGateFailsFirstTime() {
        when(courseRepository.findById(1L)).thenReturn(Optional.of(course));
        List<RagQuizQuestionDto> firstRag = List.of(ragQuestion("short", ""));
        List<RagQuizQuestionDto> secondRag = List.of(ragQuestion("Valid question with enough text?", "A"));
        when(ragClient.generateQuizResponse(
                eq("course_1"), eq(null), eq(null), eq(null), isNull(), isNull(), any(), any()))
                .thenReturn(quizResponse(firstRag))
                .thenReturn(quizResponse(secondRag));
        when(qualityGate.validateAndDeduplicateRagQuestions(firstRag))
                .thenThrow(new QualityGateException("No questions passed"));
        when(qualityGate.validateAndDeduplicateRagQuestions(secondRag)).thenReturn(secondRag);
        when(testRepository.save(any(com.microservices.courseservice.model.Test.class))).thenAnswer(inv -> {
            com.microservices.courseservice.model.Test t = inv.getArgument(0);
            t.setId(100L);
            return t;
        });

        GenerateTestRequest tr = new GenerateTestRequest();
        tr.setTitle("Quiz");
        var result = courseService.generateTest(1L, tr, jwt);

        verify(ragClient, times(2)).generateQuizResponse(
                eq("course_1"), eq(null), eq(null), eq(null), isNull(), isNull(), any(), any());
        verify(qualityGate, times(1)).validateAndDeduplicateRagQuestions(firstRag);
        verify(qualityGate, times(1)).validateAndDeduplicateRagQuestions(secondRag);
        assertThat(result.getTest().getId()).isEqualTo(100L);
    }

    @Test
    void generateTest_throwsWhenQualityGateFailsAfterRegenerate() {
        when(courseRepository.findById(1L)).thenReturn(Optional.of(course));
        List<RagQuizQuestionDto> badQuestions = List.of(ragQuestion("short", ""));
        when(ragClient.generateQuizResponse(
                eq("course_1"), eq(null), eq(null), eq(null), isNull(), isNull(), any(), any()))
                .thenReturn(quizResponse(badQuestions));
        when(qualityGate.validateAndDeduplicateRagQuestions(any()))
                .thenThrow(new QualityGateException("No questions passed"));

        GenerateTestRequest tr = new GenerateTestRequest();
        tr.setTitle("Quiz");
        assertThatThrownBy(() -> courseService.generateTest(1L, tr, jwt))
                .isInstanceOf(QualityGateException.class)
                .hasMessageContaining("No questions passed");

        verify(ragClient, times(2)).generateQuizResponse(
                eq("course_1"), eq(null), eq(null), eq(null), isNull(), isNull(), any(), any());
        verify(testRepository, never()).save(any());
    }
}
