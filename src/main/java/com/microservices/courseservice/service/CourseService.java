package com.microservices.courseservice.service;

import com.microservices.courseservice.client.AuthServiceClient;
import com.microservices.courseservice.client.FileServiceClient;
import com.microservices.courseservice.client.RagClient;
import com.microservices.courseservice.dto.AdminPlatformStatsDto;
import com.microservices.courseservice.dto.CourseViewerResponse;
import com.microservices.courseservice.dto.CourseOutlineResponse;
import com.microservices.courseservice.dto.GenerateFromOutlineRequest;
import com.microservices.courseservice.dto.GenerateLessonsRequest;
import com.microservices.courseservice.dto.GenerateLessonsResultDto;
import com.microservices.courseservice.dto.GenerateTestRequest;
import com.microservices.courseservice.dto.GenerateTestResultDto;
import com.microservices.courseservice.dto.UpdateQuestionRequest;
import com.microservices.courseservice.dto.UpdateTestRequest;
import com.microservices.courseservice.dto.RagGenerationContext;
import com.microservices.courseservice.dto.ai.GenerationUsageSummaryDto;
import com.microservices.courseservice.dto.ai.RagLlmUsageDto;
import com.microservices.courseservice.model.ai.GenerationRun;
import com.microservices.courseservice.model.ai.GenerationType;
import com.microservices.courseservice.dto.LessonGenerationParamsDto;
import com.microservices.courseservice.dto.LessonOutlineItemDto;
import com.microservices.courseservice.dto.RagLessonDto;
import com.microservices.courseservice.dto.RagQuizQuestionDto;
import com.microservices.courseservice.dto.SearchResultDto;
import com.microservices.courseservice.dto.VideoMetadataRequest;
import com.microservices.courseservice.event.CourseVectorCleanupEvent;
import com.microservices.courseservice.exception.QualityGateException;
import com.microservices.courseservice.mapper.CourseMapper;
import com.microservices.courseservice.mapper.VideoMapper;
import com.microservices.courseservice.model.*;
import com.microservices.courseservice.repository.*;
import com.microservices.courseservice.util.RoleUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CourseService {
    private static final int SEARCH_MIN_QUERY_LENGTH = 2;
    private static final int SEARCH_DEFAULT_LIMIT = 12;
    private static final int SEARCH_MAX_LIMIT = 30;

    private final CourseRepository courseRepository;
    private final LessonRepository lessonRepository;
    private final VideoRepository videoRepository;
    private final CourseCacheService courseCacheService;
    private final LessonService lessonService;
    private final CacheService cacheService;
    private final CourseMapper courseMapper;
    private final VideoMapper videoMapper;
    private final AuthServiceClient authServiceClient;
    private final FileServiceClient fileServiceClient;
    private final RagClient ragClient;
    private final TestRepository testRepository;
    private final TestAttemptRepository testAttemptRepository;
    private final ProgressRepository progressRepository;
    private final GradeService gradeService;
    private final QuestionRepository questionRepository;
    private final LessonTestQualityGate qualityGate;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final OutlineLessonStepService outlineLessonStepService;
    private final LessonGenerationJobRepository lessonGenerationJobRepository;
    private final PlatformTransactionManager transactionManager;
    private final AiGenerationOrchestratorService aiGenerationOrchestrator;

    private TransactionTemplate readOnlyCourseTx;

    private record SearchHit(SearchResultDto dto, int score, int typePriority, String title) {
    }

    @PostConstruct
    void initReadOnlyCourseTemplate() {
        readOnlyCourseTx = new TransactionTemplate(transactionManager);
        readOnlyCourseTx.setReadOnly(true);
    }

    @Transactional
    public Course createCourse(Course course, Jwt jwt) {
        validateCourseCreationPermission(jwt);
        java.util.Map<String, String> labels = new java.util.HashMap<>();
        labels.put(jwt.getSubject(), displayLabelFromJwt(jwt));
        course.setParticipantDisplayLabels(labels);

        log.info("Creating course: {} by instructor: {}", course.getTitle(), course.getInstructorId());
        Course created = courseRepository.save(course);
        
        courseCacheService.invalidatePublishedCoursesCache();
        
        return created;
    }

    public Course getCourseById(Long id) {
        return readOnlyCourseTx.execute(
                status -> {
                    Course course = courseRepository
                            .findById(id)
                            .orElseThrow(() -> new RuntimeException("Course not found with id: " + id));
                    if (course.getLessons() != null) {
                        course.getLessons().size();
                    }
                    if (course.getParticipantDisplayLabels() != null) {
                        course.getParticipantDisplayLabels().size();
                    }
                    return course;
                });
    }

    public CourseViewerResponse getCourseForViewer(Long id, Jwt jwt) {
        return readOnlyCourseTx.execute(
                status -> {
                    Course course = courseRepository
                            .findById(id)
                            .orElseThrow(() -> new RuntimeException("Course not found with id: " + id));
                    if (jwt == null) {
                        throw new AccessDeniedException("Authentication required");
                    }
                    boolean privileged = RoleUtil.isAdmin(jwt) || RoleUtil.isTeacher(jwt);
                    boolean participant = hasParticipationAccess(course, jwt);
                    if (privileged || participant) {
                        touchLazyCollections(course);
                        incrementCourseViews(id);
                        return new CourseViewerResponse(false, course);
                    }
                    if (course.getStatus() == Course.CourseStatus.PUBLISHED) {
                        incrementCourseViews(id);
                        return new CourseViewerResponse(true, toPreviewCourse(course));
                    }
                    throw new AccessDeniedException("You do not have access to this course");
                });
    }

    private static void touchLazyCollections(Course course) {
        if (course.getLessons() != null) {
            course.getLessons().size();
        }
        if (course.getParticipantDisplayLabels() != null) {
            course.getParticipantDisplayLabels().size();
        }
    }

    private Course toPreviewCourse(Course source) {
        Course c = new Course();
        c.setId(source.getId());
        c.setTitle(source.getTitle());
        c.setDescription(source.getDescription());
        c.setTitleKz(source.getTitleKz());
        c.setTitleEn(source.getTitleEn());
        c.setDescriptionKz(source.getDescriptionKz());
        c.setDescriptionEn(source.getDescriptionEn());
        c.setImageUrl(source.getImageUrl());
        c.setInstructorId(source.getInstructorId());
        c.setCreatedAt(source.getCreatedAt());
        c.setUpdatedAt(source.getUpdatedAt());
        c.setStatus(source.getStatus());
        c.setLessons(new ArrayList<>());
        c.setTests(new ArrayList<>());
        c.setEnrolledStudents(new ArrayList<>());
        c.setAllowedEmails(new ArrayList<>());
        c.setParticipantDisplayLabels(new HashMap<>());
        return c;
    }

    private boolean hasParticipationAccess(Course course, Jwt jwt) {
        String userId = jwt.getSubject();
        if (course.getEnrolledStudents() != null && course.getEnrolledStudents().contains(userId)) {
            return true;
        }
        String email = getUserEmail(userId, jwt);
        if (email != null && course.getAllowedEmails() != null && course.getAllowedEmails().contains(email)) {
            return true;
        }
        return course.getInstructorId() != null && course.getInstructorId().equals(userId);
    }

    private void validateStudentCourseAccess(Course course, Jwt jwt) {
        if (hasParticipationAccess(course, jwt)) {
            return;
        }
        throw new AccessDeniedException("You do not have access to this course");
    }

    private void assertCanViewCourseMaterials(Course course, Jwt jwt) {
        if (jwt == null) {
            throw new AccessDeniedException("Authentication required");
        }
        if (RoleUtil.isAdmin(jwt) || RoleUtil.isTeacher(jwt)) {
            return;
        }
        validateStudentCourseAccess(course, jwt);
    }

    private void assertCanViewCourseMembers(Course course, Jwt jwt) {
        if (RoleUtil.isAdmin(jwt)) {
            return;
        }
        validateStudentCourseAccess(course, jwt);
    }

    public List<Course> getAllCourses(Jwt jwt) {
        if (RoleUtil.isAdmin(jwt) || RoleUtil.isTeacher(jwt)) {
            return getCoursesByInstructor(jwt.getSubject());
        }
        return getAccessibleCoursesForStudent(jwt);
    }

    public List<Course> getAccessibleCoursesForStudent(Jwt jwt) {
        String userId = jwt.getSubject();
        String email = getUserEmail(userId, jwt);
        List<Course> byEnrolled = courseRepository.findByEnrolledStudentsContaining(userId);
        List<Course> byEmail = email != null ? courseRepository.findByAllowedEmailsContaining(email) : List.of();
        return java.util.stream.Stream.concat(byEnrolled.stream(), byEmail.stream())
                .filter(c -> c.getStatus() == Course.CourseStatus.PUBLISHED)
                .distinct()
                .toList();
    }

    private String getUserEmail(String userId, Jwt jwt) {
        String email = RoleUtil.getEmail(jwt);
        if (email != null) return email;
        try {
            Map<String, Object> user = authServiceClient.getUserById(userId);
            Object e = user != null ? user.get("email") : null;
            return e != null ? e.toString().toLowerCase().trim() : null;
        } catch (Exception ex) {
            log.warn("Could not fetch user email: {}", ex.getMessage());
            return null;
        }
    }

    public List<Course> getAllPublishedCourses() {
        List<Course> cached = courseCacheService.getPublishedCoursesFromCache();
        if (cached != null) {
            return cached;
        }
        
        List<Course> courses = courseRepository.findByStatus(Course.CourseStatus.PUBLISHED);
        courseCacheService.cachePublishedCourses(courses);
        
        return courses;
    }

    public List<Course> getCoursesByInstructor(String instructorId) {
        return courseRepository.findByInstructorId(instructorId);
    }

    public List<Course> getEnrolledCourses(String studentId) {
        return courseRepository.findByEnrolledStudentsContaining(studentId);
    }

    @Transactional(readOnly = true)
    public List<SearchResultDto> searchMaterials(String rawQuery, Integer requestedLimit, Jwt jwt) {
        if (jwt == null) {
            throw new AccessDeniedException("Authentication required");
        }

        String query = normalizeSearchQuery(rawQuery);
        if (query.length() < SEARCH_MIN_QUERY_LENGTH) {
            return List.of();
        }

        int limit = normalizeSearchLimit(requestedLimit);
        List<SearchHit> hits = new ArrayList<>();

        for (Course course : getCoursesVisibleInCatalog(jwt)) {
            int score = scoreCourse(query, course);
            if (score > 0) {
                hits.add(new SearchHit(toCourseSearchResult(course), score, 0, normalizeSearchQuery(course.getTitle())));
            }
        }

        List<Course> materialCourses = getCoursesVisibleForMaterials(jwt);
        if (!materialCourses.isEmpty()) {
            Map<Long, Course> coursesById = materialCourses.stream()
                    .collect(Collectors.toMap(Course::getId, course -> course, (left, right) -> left));
            List<Long> courseIds = new ArrayList<>(coursesById.keySet());

            for (Lesson lesson : lessonRepository.findByCourseIdInOrderByCourseIdAscOrderNumberAsc(courseIds)) {
                Course course = coursesById.get(lesson.getCourse().getId());
                if (course == null) {
                    continue;
                }
                int score = scoreLesson(query, lesson, course);
                if (score > 0) {
                    hits.add(new SearchHit(toLessonSearchResult(course, lesson), score, 1, normalizeSearchQuery(lesson.getTitle())));
                }
            }

            for (Test test : testRepository.findByCourseIdInOrderByCourseIdAscIdAsc(courseIds)) {
                Course course = coursesById.get(test.getCourse().getId());
                if (course == null) {
                    continue;
                }
                int score = scoreTest(query, test, course);
                if (score > 0) {
                    hits.add(new SearchHit(toTestSearchResult(course, test), score, 2, normalizeSearchQuery(test.getTitle())));
                }
            }
        }

        return hits.stream()
                .sorted(Comparator
                        .comparingInt(SearchHit::score).reversed()
                        .thenComparingInt(SearchHit::typePriority)
                        .thenComparing(SearchHit::title))
                .limit(limit)
                .map(SearchHit::dto)
                .toList();
    }

    private List<Course> getCoursesVisibleInCatalog(Jwt jwt) {
        if (RoleUtil.isAdmin(jwt) || RoleUtil.isTeacher(jwt)) {
            return getAllCourses(jwt);
        }
        return getAccessibleCoursesForStudent(jwt);
    }

    private List<Course> getCoursesVisibleForMaterials(Jwt jwt) {
        if (RoleUtil.isAdmin(jwt) || RoleUtil.isTeacher(jwt)) {
            return getAllCourses(jwt);
        }
        return getAccessibleCoursesForStudent(jwt);
    }

    private int scoreCourse(String query, Course course) {
        return scoreByFields(
                query,
                400,
                nullableFields(course.getTitle(), course.getTitleKz(), course.getTitleEn()),
                nullableFields(course.getDescription(), course.getDescriptionKz(), course.getDescriptionEn()));
    }

    private int scoreLesson(String query, Lesson lesson, Course course) {
        int score = scoreByFields(
                query,
                320,
                nullableFields(lesson.getTitle(), lesson.getTitleKz(), lesson.getTitleEn()),
                nullableFields(lesson.getDescription(), lesson.getDescriptionKz(), lesson.getDescriptionEn()));
        if (score == 0) {
            return 0;
        }
        return score + scoreByAuxiliaryField(query, course.getTitle(), course.getTitleKz(), course.getTitleEn());
    }

    private int scoreTest(String query, Test test, Course course) {
        int score = scoreByFields(
                query,
                300,
                nullableFields(test.getTitle(), test.getTitleKz(), test.getTitleEn()),
                List.of());
        if (score == 0) {
            return 0;
        }
        return score + scoreByAuxiliaryField(query, course.getTitle(), course.getTitleKz(), course.getTitleEn());
    }

    private int scoreByFields(String query, int titleBaseScore, List<String> titleFields, List<String> descriptionFields) {
        int titleScore = bestTextScore(query, titleFields, titleBaseScore);
        int descriptionScore = bestTextScore(query, descriptionFields, 120);
        return Math.max(titleScore, descriptionScore);
    }

    private int scoreByAuxiliaryField(String query, String... values) {
        return bestTextScore(query, nullableFields(values), 40);
    }

    private int bestTextScore(String query, List<String> fields, int baseScore) {
        int best = 0;
        for (String field : new LinkedHashSet<>(fields)) {
            String normalized = normalizeSearchQuery(field);
            if (normalized.isBlank()) {
                continue;
            }
            if (normalized.equals(query)) {
                best = Math.max(best, baseScore + 30);
                continue;
            }
            if (normalized.startsWith(query)) {
                best = Math.max(best, baseScore + 15);
                continue;
            }
            if (normalized.contains(query)) {
                best = Math.max(best, baseScore);
            }
        }
        return best;
    }

    private SearchResultDto toCourseSearchResult(Course course) {
        return SearchResultDto.builder()
                .type("course")
                .courseId(course.getId())
                .title(course.getTitle())
                .titleKz(course.getTitleKz())
                .titleEn(course.getTitleEn())
                .description(course.getDescription())
                .descriptionKz(course.getDescriptionKz())
                .descriptionEn(course.getDescriptionEn())
                .courseTitle(course.getTitle())
                .courseTitleKz(course.getTitleKz())
                .courseTitleEn(course.getTitleEn())
                .build();
    }

    private SearchResultDto toLessonSearchResult(Course course, Lesson lesson) {
        return SearchResultDto.builder()
                .type("lesson")
                .courseId(course.getId())
                .lessonId(lesson.getId())
                .title(lesson.getTitle())
                .titleKz(lesson.getTitleKz())
                .titleEn(lesson.getTitleEn())
                .description(lesson.getDescription())
                .descriptionKz(lesson.getDescriptionKz())
                .descriptionEn(lesson.getDescriptionEn())
                .courseTitle(course.getTitle())
                .courseTitleKz(course.getTitleKz())
                .courseTitleEn(course.getTitleEn())
                .build();
    }

    private SearchResultDto toTestSearchResult(Course course, Test test) {
        return SearchResultDto.builder()
                .type("test")
                .courseId(course.getId())
                .testId(test.getId())
                .title(test.getTitle())
                .titleKz(test.getTitleKz())
                .titleEn(test.getTitleEn())
                .courseTitle(course.getTitle())
                .courseTitleKz(course.getTitleKz())
                .courseTitleEn(course.getTitleEn())
                .build();
    }

    private int normalizeSearchLimit(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit <= 0) {
            return SEARCH_DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, SEARCH_MAX_LIMIT);
    }

    private String normalizeSearchQuery(String value) {
        if (value == null) {
            return "";
        }
        return value
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private List<String> nullableFields(String... values) {
        return java.util.Arrays.asList(values);
    }

    @Transactional
    public Course updateCourse(Long id, Jwt jwt, Course courseUpdate) {
        Course existing = getCourseById(id);
        validateCourseUpdatePermission(existing, jwt);

        Course.CourseStatus oldStatus = existing.getStatus();
        courseMapper.updateCourseFromSource(existing, courseUpdate);
        Course updated = courseRepository.save(existing);
        courseCacheService.invalidateCacheOnStatusChange(oldStatus, updated.getStatus());
        courseCacheService.invalidateCourseCache(id);
        return updated;
    }

    @Transactional
    public Course updateCourseStatus(Long id, Jwt jwt, Course.CourseStatus newStatus) {
        Course existing = getCourseById(id);
        validateCourseUpdatePermission(existing, jwt);

        Course.CourseStatus oldStatus = existing.getStatus();
        existing.setStatus(newStatus);

        Course updated = courseRepository.save(existing);

        courseCacheService.invalidateCacheOnStatusChange(oldStatus, updated.getStatus());
        courseCacheService.invalidateCourseCache(id);

        return updated;
    }

    @Transactional
    public void deleteCourse(Long id, Jwt jwt) {
        Course course = getCourseById(id);
        validateCourseDeletePermission(course, jwt);

        // test_attempts FK → tests: remove attempts first (deleteById may not load lazy collections).
        testAttemptRepository.deleteByCourseId(id);

        courseRepository.deleteById(id);

        courseCacheService.invalidateCacheOnDelete(course);
        applicationEventPublisher.publishEvent(new CourseVectorCleanupEvent(id));
    }

    @Transactional
    public Lesson createLesson(Lesson lesson, Long courseId, Jwt jwt) {
        Course course = getCourseById(courseId);
        return lessonService.createLesson(lesson, course, jwt);
    }

    private List<Long> resolveCourseFileFilter(Long courseId, List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> courseFiles = fileServiceClient.getFilesByCourseId(courseId);
        var validIds = courseFiles.stream()
                .map(f -> f.get("id"))
                .filter(Objects::nonNull)
                .map(id -> id instanceof Number ? ((Number) id).longValue() : Long.parseLong(id.toString()))
                .filter(fileIds::contains)
                .toList();
        if (validIds.isEmpty()) {
            throw new RuntimeException("No valid files found for the selected file IDs");
        }
        return validIds;
    }

    @Transactional
    public GenerateLessonsResultDto generateLessonsFromFiles(Long courseId, GenerateLessonsRequest genRequest, Jwt jwt) {
        Course course = getCourseById(courseId);
        validateCourseUpdatePermission(course, jwt);

        AiGenerationOrchestratorService.ActiveGeneration active = aiGenerationOrchestrator.start(
                jwt,
                courseId,
                null,
                GenerationType.LESSON_FROM_FILES,
                genRequest.getModelId(),
                genRequest.getIdempotencyKey());

        String collectionName = "course_" + courseId;
        List<Long> filterFileIds = resolveCourseFileFilter(courseId, genRequest.getFileIds());

        String prompt = genRequest.getPrompt();
        if (prompt != null && prompt.isBlank()) {
            prompt = null;
        }

        try {
            List<Lesson> existingLessons = lessonService.getLessonsByCourse(courseId);
            var ragResponse = ragClient.generateLessonsResponse(
                    collectionName,
                    filterFileIds,
                    prompt,
                    genRequest.getTopK(),
                    genRequest.getParams(),
                    List.of("ru", "kz", "en"),
                    active.ragContext());
            aiGenerationOrchestrator.recordRagUsage(active.run(), ragResponse.getUsage());
            List<RagLessonDto> validatedLessons = validateLessonsWithRetry(
                    collectionName,
                    filterFileIds,
                    prompt,
                    genRequest.getTopK(),
                    genRequest.getParams(),
                    ragResponse.getLessons(),
                    existingLessons,
                    active.run(),
                    active.ragContext());
            List<Lesson> lessons = persistRagLessons(
                    course, courseId, collectionName, validatedLessons, ragResponse, jwt);
            GenerationUsageSummaryDto usageSummary = aiGenerationOrchestrator.finishSuccess(
                    active.run(), ragResponse.getRequestId(), ragResponse.getUsage());
            return GenerateLessonsResultDto.builder()
                    .lessons(lessons)
                    .usageSummary(usageSummary)
                    .build();
        } catch (RuntimeException e) {
            aiGenerationOrchestrator.finishFailure(active.run(), "generation_failed", null, null);
            throw e;
        }
    }

    public CourseOutlineResponse generateLessonOutline(Long courseId, GenerateLessonsRequest genRequest, Jwt jwt) {
        Course course = getCourseById(courseId);
        validateCourseUpdatePermission(course, jwt);
        return buildCourseOutlineResponse(courseId, genRequest, jwt);
    }

    private CourseOutlineResponse buildCourseOutlineResponse(
            Long courseId, GenerateLessonsRequest genRequest, Jwt jwt) {
        AiGenerationOrchestratorService.ActiveGeneration active = aiGenerationOrchestrator.start(
                jwt,
                courseId,
                null,
                GenerationType.LESSON_OUTLINE,
                genRequest.getModelId(),
                genRequest.getIdempotencyKey());

        String collectionName = "course_" + courseId;
        List<Long> filterFileIds = resolveCourseFileFilter(courseId, genRequest.getFileIds());
        String prompt = genRequest.getPrompt();
        if (prompt != null && prompt.isBlank()) {
            prompt = null;
        }
        try {
            CourseOutlineResponse response = ragClient.generateCourseOutline(
                    collectionName,
                    filterFileIds,
                    prompt,
                    genRequest.getTopK(),
                    genRequest.getParams(),
                    active.ragContext());
            aiGenerationOrchestrator.recordRagUsage(active.run(), response.getUsage());
            GenerationUsageSummaryDto usageSummary = aiGenerationOrchestrator.finishSuccess(
                    active.run(), response.getRequestId(), response.getUsage());
            response.setUsageSummary(usageSummary);
            return response;
        } catch (RuntimeException e) {
            aiGenerationOrchestrator.finishFailure(active.run(), "generation_failed", null, null);
            throw e;
        }
    }

    public CourseOutlineResponse generateLessonOutlineForInstructor(
            Long courseId, GenerateLessonsRequest genRequest, String instructorId) {
        Course course = getCourseById(courseId);
        if (!course.getInstructorId().equals(instructorId)) {
            throw new AccessDeniedException("Only course instructor can generate outline for this course");
        }
        Jwt jobJwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("job")
                .header("alg", "none")
                .subject(instructorId)
                .claim("realm_access", Map.of("roles", List.of("teacher")))
                .build();
        return buildCourseOutlineResponse(courseId, genRequest, jobJwt);
    }

    public List<Lesson> generateLessonsFromOutline(Long courseId, GenerateFromOutlineRequest genRequest, Jwt jwt) {
        Course course = getCourseById(courseId);
        validateCourseUpdatePermission(course, jwt);
        return generateLessonsFromOutlineAuthorized(courseId, genRequest, jwt, null);
    }

    public List<Lesson> generateLessonsFromOutlineForInstructor(
            Long courseId, GenerateFromOutlineRequest genRequest, String instructorId, String jobId) {
        Course course = getCourseById(courseId);
        if (!course.getInstructorId().equals(instructorId)) {
            throw new AccessDeniedException("Only course instructor can generate lessons for this course");
        }
        Jwt jobJwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("job")
                .header("alg", "none")
                .subject(instructorId)
                .claim("realm_access", Map.of("roles", List.of("teacher")))
                .build();
        return generateLessonsFromOutlineAuthorized(courseId, genRequest, jobJwt, jobId);
    }

    private List<Lesson> generateLessonsFromOutlineAuthorized(
            Long courseId,
            GenerateFromOutlineRequest genRequest,
            Jwt jwt,
            String jobId) {
        if (genRequest.getOutline() == null || genRequest.getOutline().isEmpty()) {
            throw new RuntimeException("Outline must contain at least one lesson");
        }

        AiGenerationOrchestratorService.ActiveGeneration active = aiGenerationOrchestrator.start(
                jwt,
                courseId,
                jobId,
                GenerationType.LESSON_FROM_OUTLINE,
                genRequest.getModelId(),
                genRequest.getIdempotencyKey());

        String collectionName = "course_" + courseId;
        List<Long> filterFileIds = resolveCourseFileFilter(courseId, genRequest.getFileIds());

        List<LessonOutlineItemDto> items = new ArrayList<>(genRequest.getOutline());
        items.removeIf(o -> o.getTitle() == null || o.getTitle().isBlank());
        items.sort(Comparator.comparingInt(o -> o.getOrder() != null ? o.getOrder() : Integer.MAX_VALUE));
        for (int i = 0; i < items.size(); i++) {
            LessonOutlineItemDto it = items.get(i);
            it.setOrder(i + 1);
        }

        List<Lesson> existingLessons = lessonService.getLessonsByCourse(courseId);
        List<Lesson> created = new ArrayList<>();
        LessonGenerationParamsDto params = genRequest.getParams();
        int total = items.size();

        if (jobId != null) {
            lessonGenerationJobRepository.findById(jobId).ifPresent(job -> {
                job.setGenerationRunId(active.run().getId());
                job.setTotalLessons(total);
                job.setCompletedLessons(0);
                job.setCurrentLessonTitle(items.isEmpty() ? null : items.get(0).getTitle());
                lessonGenerationJobRepository.save(job);
            });
        }

        try {
            for (int i = 0; i < items.size(); i++) {
                LessonOutlineItemDto item = items.get(i);
                int lessonNum = i + 1;
                if (jobId != null) {
                    final String title = item.getTitle();
                    lessonGenerationJobRepository.findById(jobId).ifPresent(job -> {
                        job.setCurrentLessonTitle(title);
                        lessonGenerationJobRepository.save(job);
                    });
                }
                List<Lesson> prior = new ArrayList<>(existingLessons);
                prior.addAll(created);
                Lesson saved = outlineLessonStepService.generateAndPersistOne(
                        courseId,
                        collectionName,
                        filterFileIds,
                        item,
                        lessonNum,
                        total,
                        prior,
                        params,
                        jwt,
                        active.ragContext(),
                        active.run());
                created.add(saved);
                if (jobId != null) {
                    lessonGenerationJobRepository.findById(jobId).ifPresent(job -> {
                        job.setCompletedLessons(created.size());
                        job.setCreatedLessonIds(
                                created.stream().map(l -> String.valueOf(l.getId())).collect(Collectors.joining(",")));
                        lessonGenerationJobRepository.save(job);
                    });
                }
            }

            aiGenerationOrchestrator.finishSuccess(active.run(), null, null);

            if (jobId != null) {
                lessonGenerationJobRepository.findById(jobId).ifPresent(job -> {
                    job.setStatus(LessonGenerationJob.Status.COMPLETED);
                    job.setCompletedLessons(created.size());
                    job.setCreatedLessonIds(
                            created.stream().map(l -> String.valueOf(l.getId())).collect(Collectors.joining(",")));
                    job.setCurrentLessonTitle(null);
                    job.setErrorMessage(null);
                    lessonGenerationJobRepository.save(job);
                });
            }

            return created;
        } catch (RuntimeException e) {
            aiGenerationOrchestrator.finishFailure(active.run(), "generation_failed", null, null);
            throw e;
        }
    }

    private List<Lesson> persistRagLessons(
            Course course,
            Long courseId,
            String collectionName,
            List<RagLessonDto> validatedLessons,
            com.microservices.courseservice.dto.RagLessonsResponse ragResponse,
            Jwt jwt) {
        List<Lesson> created = new ArrayList<>();
        var tr = ragResponse != null ? ragResponse.getTranslations() : null;
        var kz = tr != null ? tr.get("kz") : null;
        var en = tr != null ? tr.get("en") : null;
        int order = 1;
        for (int idx = 0; idx < validatedLessons.size(); idx++) {
            RagLessonDto rl = validatedLessons.get(idx);
            Lesson lesson = new Lesson();
            String title = rl.getTitle() != null && !rl.getTitle().isBlank() ? rl.getTitle() : "Урок " + order;
            lesson.setTitle(title);
            lesson.setContent(rl.getContent() != null ? rl.getContent() : "");
            lesson.setDescription(rl.getDescription() != null ? rl.getDescription() : "");
            lesson.setOrderNumber(rl.getOrder() != null && rl.getOrder() >= 1 ? rl.getOrder() : order);
            if (kz != null && idx < kz.size() && kz.get(idx) != null) {
                lesson.setTitleKz(kz.get(idx).getTitle());
                lesson.setContentKz(kz.get(idx).getContent());
                lesson.setDescriptionKz(kz.get(idx).getDescription());
            }
            if (en != null && idx < en.size() && en.get(idx) != null) {
                lesson.setTitleEn(en.get(idx).getTitle());
                lesson.setContentEn(en.get(idx).getContent());
                lesson.setDescriptionEn(en.get(idx).getDescription());
            }
            lesson.setCourse(course);
            Lesson saved = lessonService.createLesson(lesson, course, jwt);
            created.add(saved);
            String content = rl.getContent() != null ? rl.getContent() : "";
            if (!content.isBlank()) {
                try {
                    ragClient.vectorizeText(content, collectionName,
                            Map.of("lesson_id", String.valueOf(saved.getId()), "course_id", String.valueOf(courseId)));
                } catch (Exception e) {
                    log.warn("Failed to vectorize lesson {}: {}", saved.getId(), e.getMessage());
                }
            }
            order++;
        }
        return created;
    }

    private List<RagLessonDto> validateLessonsWithRetry(
            String collectionName,
            List<Long> filterFileIds,
            String prompt,
            Integer topK,
            LessonGenerationParamsDto params,
            List<RagLessonDto> ragLessons,
            List<Lesson> existingLessons,
            GenerationRun run,
            RagGenerationContext ragContext) {
        try {
            return qualityGate.validateAndDeduplicateRagLessons(ragLessons, existingLessons);
        } catch (QualityGateException e) {
            log.warn("Quality gate failed for generated lessons, retrying once: {}", e.getMessage());
            var retryResponse = ragClient.generateLessonsResponse(
                    collectionName,
                    filterFileIds,
                    prompt,
                    topK,
                    params,
                    List.of("ru", "kz", "en"),
                    ragContext);
            RagLlmUsageDto retryUsage = AiGenerationOrchestratorService.withAttemptNumber(retryResponse.getUsage(), 2);
            aiGenerationOrchestrator.recordRagUsage(run, retryUsage);
            return qualityGate.validateAndDeduplicateRagLessons(retryResponse.getLessons(), existingLessons);
        }
    }

    @Transactional
    public GenerateTestResultDto generateTest(Long courseId, GenerateTestRequest testRequest, Jwt jwt) {
        Course course = getCourseById(courseId);
        validateCourseUpdatePermission(course, jwt);

        AiGenerationOrchestratorService.ActiveGeneration active = aiGenerationOrchestrator.start(
                jwt,
                courseId,
                null,
                GenerationType.QUIZ_GENERATION,
                testRequest.getModelId(),
                testRequest.getIdempotencyKey());

        String collectionName = "course_" + courseId;
        List<Long> fileIds = testRequest.getFileIds();
        List<Long> lessonIds = testRequest.getLessonIds();
        List<Long> filterFileIds = null;
        if (fileIds != null && !fileIds.isEmpty()) {
            filterFileIds = resolveCourseFileFilter(courseId, fileIds);
        }

        try {
            var quizResponse = ragClient.generateQuizResponse(
                    collectionName,
                    filterFileIds,
                    lessonIds,
                    null,
                    testRequest.getQuestionCount(),
                    testRequest.getDifficulty(),
                    List.of("ru", "kz", "en"),
                    active.ragContext());
            aiGenerationOrchestrator.recordRagUsage(active.run(), quizResponse.getUsage());
            List<RagQuizQuestionDto> validatedQuestions = validateQuestionsWithRetry(
                    collectionName,
                    filterFileIds,
                    lessonIds,
                    testRequest.getQuestionCount(),
                    testRequest.getDifficulty(),
                    quizResponse.getQuestions(),
                    active.run(),
                    active.ragContext());

            String title = testRequest.getTitle();
            Test test = new Test();
            String baseTitle = title != null && !title.isBlank() ? title : "Тест по курсу";
            test.setTitle(baseTitle);
            test.setCourse(course);
            test.setIsVisible(true);
            test = testRepository.save(test);

            var tr = quizResponse.getTranslations();
            var kzTr = tr != null ? tr.get("kz") : null;
            var enTr = tr != null ? tr.get("en") : null;

            int order = 1;
            for (int idx = 0; idx < validatedQuestions.size(); idx++) {
                RagQuizQuestionDto rq = validatedQuestions.get(idx);
                Question q = new Question();
                q.setType(Question.QuestionType.MULTIPLE_CHOICE);
                q.setText(rq.getQuestion() != null ? rq.getQuestion() : "");
                Object opts = rq.getOptions();
                q.setOptions(opts != null ? toJson(opts) : "{}");
                q.setCorrectAnswer(rq.getCorrect() != null ? rq.getCorrect() : "");
                q.setExplanation(rq.getExplanation() != null ? rq.getExplanation() : "");
                q.setHint(rq.getHint() != null ? rq.getHint() : "");
                if (kzTr != null && idx < kzTr.size() && kzTr.get(idx) != null) {
                    q.setTextKz(kzTr.get(idx).getQuestion());
                    q.setOptionsKz(kzTr.get(idx).getOptions() != null ? toJson(kzTr.get(idx).getOptions()) : null);
                    q.setExplanationKz(kzTr.get(idx).getExplanation());
                    q.setHintKz(kzTr.get(idx).getHint());
                }
                if (enTr != null && idx < enTr.size() && enTr.get(idx) != null) {
                    q.setTextEn(enTr.get(idx).getQuestion());
                    q.setOptionsEn(enTr.get(idx).getOptions() != null ? toJson(enTr.get(idx).getOptions()) : null);
                    q.setExplanationEn(enTr.get(idx).getExplanation());
                    q.setHintEn(enTr.get(idx).getHint());
                }
                q.setTest(test);
                q.setOrderNumber(order++);
                qualityGate.validateQuestion(q);
                questionRepository.save(q);
            }

            GenerationUsageSummaryDto usageSummary = aiGenerationOrchestrator.finishSuccess(
                    active.run(), quizResponse.getRequestId(), quizResponse.getUsage());
            return GenerateTestResultDto.builder()
                    .test(test)
                    .usageSummary(usageSummary)
                    .build();
        } catch (RuntimeException e) {
            aiGenerationOrchestrator.finishFailure(active.run(), "generation_failed", null, null);
            throw e;
        }
    }

    private List<RagQuizQuestionDto> validateQuestionsWithRetry(
            String collectionName,
            List<Long> filterFileIds,
            List<Long> lessonIds,
            Integer questionCount,
            String difficulty,
            List<RagQuizQuestionDto> ragQuestions,
            GenerationRun run,
            RagGenerationContext ragContext) {
        try {
            return qualityGate.validateAndDeduplicateRagQuestions(ragQuestions);
        } catch (QualityGateException e) {
            log.warn("Quality gate failed for generated questions, retrying once: {}", e.getMessage());
            var retryResponse = ragClient.generateQuizResponse(
                    collectionName,
                    filterFileIds,
                    lessonIds,
                    null,
                    questionCount,
                    difficulty,
                    List.of("ru", "kz", "en"),
                    ragContext);
            RagLlmUsageDto retryUsage = AiGenerationOrchestratorService.withAttemptNumber(retryResponse.getUsage(), 2);
            aiGenerationOrchestrator.recordRagUsage(run, retryUsage);
            return qualityGate.validateAndDeduplicateRagQuestions(retryResponse.getQuestions());
        }
    }

    private static String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    public List<com.microservices.courseservice.dto.LessonGradingOverviewDto> getLessonsByCourse(Long courseId, Jwt jwt) {
        Course course = getCourseById(courseId);
        assertCanViewCourseMaterials(course, jwt);
        List<Lesson> lessons = lessonService.getLessonsByCourse(courseId);
        return gradeService.buildLessonOverviews(course, lessons);
    }

    public Lesson getLessonById(Long lessonId, Jwt jwt) {
        Lesson lesson = lessonService.getLessonById(lessonId);
        assertCanViewCourseMaterials(lesson.getCourse(), jwt);
        return lesson;
    }

    @Transactional
    public Lesson updateLesson(Long lessonId, Lesson lesson, Jwt jwt) {
        return lessonService.updateLesson(lessonId, lesson, jwt);
    }

    @Transactional
    public Video createVideo(VideoMetadataRequest request, Long lessonId, Jwt jwt) {
        validateVideoCreationPermission(jwt);
        
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new RuntimeException("Lesson not found"));

        Video video = videoMapper.toVideo(request);
        video.setLesson(lesson);
        video.setStatus(Video.VideoStatus.READY);

        if (video.getOrderNumber() == null || video.getOrderNumber() == 0) {
            int nextOrder = calculateNextVideoOrder(lessonId);
            video.setOrderNumber(nextOrder);
        }
        
        return videoRepository.save(video);
    }

    public List<Video> getVideosByLesson(Long lessonId, Jwt jwt) {
        Lesson lesson = lessonService.getLessonById(lessonId);
        assertCanViewCourseMaterials(lesson.getCourse(), jwt);
        return videoRepository.findByLessonIdOrderByOrderNumber(lessonId);
    }

    @Transactional
    public void deleteLesson(Long lessonId, Jwt jwt) {
        lessonService.deleteLesson(lessonId, jwt);
    }

    @Transactional
    public void deleteVideo(Long videoId, Jwt jwt) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new RuntimeException("Video not found with id: " + videoId));
        
        Lesson lesson = video.getLesson();
        if (lesson == null) {
            throw new RuntimeException("Video lesson not found");
        }
        
        Course course = lesson.getCourse();
        validateVideoDeletePermission(course, jwt);
        
        videoRepository.deleteById(videoId);
        log.info("Deleted video: {} from lesson: {}", videoId, lesson.getId());
    }

    private void validateVideoDeletePermission(Course course, Jwt jwt) {
        if (!RoleUtil.isAdmin(jwt) && !course.getInstructorId().equals(jwt.getSubject())) {
            throw new AccessDeniedException("Only course instructor or admin can delete video");
        }
    }

    public List<Test> getTestsByCourse(Long courseId, Jwt jwt) {
        Course course = getCourseById(courseId);
        assertCanViewCourseMaterials(course, jwt);
        return testRepository.findByCourseId(courseId);
    }

    public Test getTestById(Long testId, Jwt jwt) {
        Test test = testRepository.findById(testId)
                .orElseThrow(() -> new RuntimeException("Test not found: " + testId));
        assertCanViewCourseMaterials(test.getCourse(), jwt);
        return test;
    }

    private Test requireTestEntity(Long testId) {
        return testRepository.findById(testId)
                .orElseThrow(() -> new RuntimeException("Test not found: " + testId));
    }

    @Transactional
    public TestAttempt submitTestAttempt(Long testId, String studentId, java.util.Map<String, String> answers, Boolean suspiciousFlag, Jwt jwt) {
        Test test = requireTestEntity(testId);
        Course course = test.getCourse();
        validateStudentCourseAccess(course, jwt);
        if (!course.getEnrolledStudents().contains(studentId)) {
            throw new AccessDeniedException("You must be enrolled to take this test");
        }
        Integer limit = test.getMaxAttempts();
        if (limit != null && limit > 0) {
            long used = testAttemptRepository.countByTestIdAndStudentId(testId, studentId);
            if (used >= limit) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attempt limit reached for this test");
            }
        }
        List<Question> questions = questionRepository.findByTestIdOrderByOrderNumber(testId);
        int maxScore = questions.size();
        int score = 0;
        for (Question q : questions) {
            String key = String.valueOf(q.getId());
            String userAnswer = answers != null ? answers.get(key) : null;
            if (userAnswer != null && userAnswer.trim().equalsIgnoreCase(q.getCorrectAnswer() != null ? q.getCorrectAnswer().trim() : "")) {
                score++;
            }
        }
        String answersJson = toJson(answers != null ? answers : Map.of());
        TestAttempt attempt = new TestAttempt();
        attempt.setStudentId(studentId);
        attempt.setTest(test);
        attempt.setScore(score);
        attempt.setMaxScore(maxScore);
        attempt.setAnswersJson(answersJson);
        attempt.setCompletedAt(java.time.LocalDateTime.now());
        attempt.setSuspiciousFlag(Boolean.TRUE.equals(suspiciousFlag));
        return testAttemptRepository.save(attempt);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.List<com.microservices.courseservice.dto.TestAttemptTeacherRowDto> getTestAttemptsByCourse(Long courseId, Jwt jwt) {
        Course course = getCourseById(courseId);
        validateCourseUpdatePermission(course, jwt);
        java.util.List<TestAttempt> attempts = testAttemptRepository.findByCourseIdWithTest(courseId);
        java.util.List<String> studentIds = attempts.stream()
                .map(TestAttempt::getStudentId)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        Map<String, Map<String, Object>> profiles = resolveUserProfiles(studentIds);
        Map<String, String> labels = course.getParticipantDisplayLabels() != null
                ? course.getParticipantDisplayLabels()
                : Map.of();
        return attempts.stream()
                .map(ta -> toTeacherTestRow(ta, profiles, labels))
                .toList();
    }

    private com.microservices.courseservice.dto.TestAttemptTeacherRowDto toTeacherTestRow(
            TestAttempt ta,
            Map<String, Map<String, Object>> profiles,
            Map<String, String> labels) {
        Test t = ta.getTest();
        String studentId = ta.getStudentId();
        Map<String, Object> profile = studentId != null ? profiles.get(studentId) : null;
        String label = studentId != null ? labels.get(studentId) : null;
        String email = firstNonBlank(stringClaim(profile, "email"), emailFromDisplayLabel(label));
        String fullName = firstNonBlank(
                stringClaim(profile, "fullName"),
                buildNameFromParts(stringClaim(profile, "firstName"), stringClaim(profile, "lastName")),
                stringClaim(profile, "username"),
                nameFromDisplayLabel(label)
        );
        return com.microservices.courseservice.dto.TestAttemptTeacherRowDto.builder()
                .attemptId(ta.getId())
                .studentId(studentId)
                .studentEmail(email)
                .studentName(fullName)
                .studentDisplayLabel(label)
                .testId(t != null ? t.getId() : null)
                .testTitle(t != null ? t.getTitle() : null)
                .testTitleKz(t != null ? t.getTitleKz() : null)
                .testTitleEn(t != null ? t.getTitleEn() : null)
                .score(ta.getScore())
                .maxScore(ta.getMaxScore())
                .completedAt(ta.getCompletedAt() != null ? ta.getCompletedAt().toString() : null)
                .suspiciousFlag(ta.getSuspiciousFlag())
                .build();
    }

    public List<TestAttempt> getMyTestAttempts(String studentId) {
        return testAttemptRepository.findByStudentIdOrderByCompletedAtDesc(studentId);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.List<com.microservices.courseservice.dto.MyTestAttemptDto> getMyTestAttemptDtos(String studentId) {
        return testAttemptRepository.findByStudentIdWithTest(studentId).stream()
                .map(ta -> com.microservices.courseservice.dto.MyTestAttemptDto.builder()
                        .attemptId(ta.getId())
                        .testId(ta.getTest() != null ? ta.getTest().getId() : null)
                        .score(ta.getScore())
                        .maxScore(ta.getMaxScore())
                        .completedAt(ta.getCompletedAt() != null ? ta.getCompletedAt().toString() : null)
                        .suspiciousFlag(ta.getSuspiciousFlag())
                        .build())
                .toList();
    }

    @Transactional
    public Test updateTest(Long testId, UpdateTestRequest request, Jwt jwt) {
        Test test = requireTestEntity(testId);
        Course course = test.getCourse();
        validateCourseUpdatePermission(course, jwt);

        if (request.getTitle() != null) {
            String title = request.getTitle().trim();
            if (title.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Test title is required");
            }
            test.setTitle(title);
        }
        if (request.getTitleKz() != null) {
            test.setTitleKz(request.getTitleKz().trim());
        }
        if (request.getTitleEn() != null) {
            test.setTitleEn(request.getTitleEn().trim());
        }

        Integer normalizedAttempts = request.getMaxAttempts();
        if (normalizedAttempts != null && normalizedAttempts <= 0) {
            normalizedAttempts = null;
        }
        test.setMaxAttempts(normalizedAttempts);

        String dueAtRaw = request.getDueAt();
        java.time.LocalDateTime dueAt = null;
        if (dueAtRaw != null && !dueAtRaw.isBlank()) {
            try {
                dueAt = java.time.LocalDateTime.parse(dueAtRaw.trim());
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid dueAt формат");
            }
        }
        test.setDueAt(dueAt);

        if (request.getQuestions() != null) {
            if (request.getQuestions().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one question is required");
            }
            syncTestQuestions(test, request.getQuestions());
        }

        return testRepository.save(test);
    }

    private void syncTestQuestions(Test test, List<UpdateQuestionRequest> questionDtos) {
        List<Question> existing = questionRepository.findByTestIdOrderByOrderNumber(test.getId());
        Map<Long, Question> byId = existing.stream()
                .filter(q -> q.getId() != null)
                .collect(Collectors.toMap(Question::getId, q -> q, (a, b) -> a));

        java.util.Set<Long> keptIds = new java.util.HashSet<>();
        int order = 1;
        for (UpdateQuestionRequest dto : questionDtos) {
            Question question;
            if (dto.getId() != null && byId.containsKey(dto.getId())) {
                question = byId.get(dto.getId());
                keptIds.add(question.getId());
            } else {
                question = new Question();
                question.setTest(test);
            }
            applyQuestionUpdate(question, dto, order++);
            qualityGate.validateQuestion(question);
            questionRepository.save(question);
        }

        for (Question old : existing) {
            if (old.getId() != null && !keptIds.contains(old.getId())) {
                questionRepository.delete(old);
            }
        }
    }

    private void applyQuestionUpdate(Question question, UpdateQuestionRequest dto, int fallbackOrder) {
        Question.QuestionType type = dto.getType() != null ? dto.getType() : Question.QuestionType.MULTIPLE_CHOICE;
        question.setType(type);
        question.setText(trimOrEmpty(dto.getText()));
        question.setTextKz(trimToNull(dto.getTextKz()));
        question.setTextEn(trimToNull(dto.getTextEn()));
        question.setOptions(trimOrEmpty(dto.getOptions()));
        question.setOptionsKz(trimToNull(dto.getOptionsKz()));
        question.setOptionsEn(trimToNull(dto.getOptionsEn()));
        question.setCorrectAnswer(trimOrEmpty(dto.getCorrectAnswer()));
        question.setExplanation(trimToNull(dto.getExplanation()));
        question.setHint(trimToNull(dto.getHint()));
        question.setExplanationKz(trimToNull(dto.getExplanationKz()));
        question.setExplanationEn(trimToNull(dto.getExplanationEn()));
        question.setHintKz(trimToNull(dto.getHintKz()));
        question.setHintEn(trimToNull(dto.getHintEn()));
        Integer orderNumber = dto.getOrderNumber();
        question.setOrderNumber(orderNumber != null && orderNumber > 0 ? orderNumber : fallbackOrder);
    }

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Transactional
    public Test updateTestSettings(Long testId, Integer maxAttempts, Jwt jwt) {
        Test test = requireTestEntity(testId);
        Course course = test.getCourse();
        validateCourseUpdatePermission(course, jwt);
        Integer normalized = maxAttempts;
        if (normalized != null && normalized <= 0) {
            normalized = null;
        }
        test.setMaxAttempts(normalized);
        return testRepository.save(test);
    }

    @Transactional
    public Test updateTestSettings(Long testId, Integer maxAttempts, String dueAtRaw, Jwt jwt) {
        Test test = requireTestEntity(testId);
        Course course = test.getCourse();
        validateCourseUpdatePermission(course, jwt);

        Integer normalizedAttempts = maxAttempts;
        if (normalizedAttempts != null && normalizedAttempts <= 0) {
            normalizedAttempts = null;
        }
        test.setMaxAttempts(normalizedAttempts);

        java.time.LocalDateTime dueAt = null;
        if (dueAtRaw != null && !dueAtRaw.isBlank()) {
            try {
                // Expect: "YYYY-MM-DDTHH:mm" (from datetime-local). Also accept full ISO local datetime.
                dueAt = java.time.LocalDateTime.parse(dueAtRaw.trim());
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid dueAt формат");
            }
        }
        test.setDueAt(dueAt);

        return testRepository.save(test);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public java.util.List<com.microservices.courseservice.dto.UpcomingTestDeadlineDto> getMyUpcomingTestDeadlines(Jwt jwt) {
        String studentId = jwt.getSubject();
        List<Course> enrolled = courseRepository.findByEnrolledStudentsContaining(studentId);
        if (enrolled == null || enrolled.isEmpty()) {
            return List.of();
        }
        List<Long> courseIds = enrolled.stream().map(Course::getId).filter(java.util.Objects::nonNull).toList();
        if (courseIds.isEmpty()) return List.of();

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        List<Test> tests = testRepository.findByCourseIdInAndDueAtAfterOrderByDueAtAsc(courseIds, now);

        java.util.Map<Long, Course> byId = enrolled.stream()
                .filter(c -> c.getId() != null)
                .collect(java.util.stream.Collectors.toMap(Course::getId, c -> c, (a, b) -> a));

        return tests.stream()
                .filter(t -> t.getDueAt() != null)
                .limit(10)
                .map(t -> {
                    Course c = byId.get(t.getCourse() != null ? t.getCourse().getId() : null);
                    return com.microservices.courseservice.dto.UpcomingTestDeadlineDto.builder()
                            .courseId(c != null ? c.getId() : (t.getCourse() != null ? t.getCourse().getId() : null))
                            .courseTitle(c != null ? c.getTitle() : null)
                            .courseTitleKz(c != null ? c.getTitleKz() : null)
                            .courseTitleEn(c != null ? c.getTitleEn() : null)
                            .testId(t.getId())
                            .testTitle(t.getTitle())
                            .testTitleKz(t.getTitleKz())
                            .testTitleEn(t.getTitleEn())
                            .dueAt(t.getDueAt().toString())
                            .build();
                })
                .toList();
    }

    @Transactional
    public void enrollStudent(Long courseId, String studentId, Jwt jwt) {
        Course course = getCourseById(courseId);
        String email = getUserEmail(studentId, jwt);
        if (course.getAllowedEmails() != null && !course.getAllowedEmails().isEmpty()) {
            if (email == null || !course.getAllowedEmails().contains(email)) {
                throw new AccessDeniedException("Your email is not in the allowed list for this course");
            }
        }
        if (course.getParticipantDisplayLabels() == null) {
            course.setParticipantDisplayLabels(new java.util.HashMap<>());
        }
        if (course.getStudentEnrolledAt() == null) {
            course.setStudentEnrolledAt(new java.util.HashMap<>());
        }
        course.getParticipantDisplayLabels().put(studentId, displayLabelFromJwt(jwt));
        if (!course.getEnrolledStudents().contains(studentId)) {
            course.getEnrolledStudents().add(studentId);
            course.getStudentEnrolledAt().put(studentId, LocalDateTime.now());
        }
        courseRepository.save(course);
        log.info("Student {} enrolled in course {}", studentId, courseId);
        if (course.getStatus() == Course.CourseStatus.PUBLISHED) {
            courseCacheService.invalidatePublishedCoursesCache();
        }
        courseCacheService.invalidateCourseCache(courseId);
    }

    private String displayLabelFromJwt(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        if (email != null && !email.isBlank()) {
            return email.trim();
        }
        String username = jwt.getClaimAsString("preferred_username");
        if (username != null && !username.isBlank()) {
            return username.trim();
        }
        return jwt.getSubject();
    }

    @Transactional(readOnly = true)
    public com.microservices.courseservice.dto.CourseParticipantsDto getCourseParticipants(Long courseId, Jwt jwt) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Course not found with id: " + courseId));
        assertCanViewCourseMembers(course, jwt);
        if (course.getParticipantDisplayLabels() != null) {
            course.getParticipantDisplayLabels().size();
        }
        if (course.getEnrolledStudents() != null) {
            course.getEnrolledStudents().size();
        }
        if (course.getStudentEnrolledAt() != null) {
            course.getStudentEnrolledAt().size();
        }

        Map<String, String> labels = course.getParticipantDisplayLabels() != null
                ? new HashMap<>(course.getParticipantDisplayLabels())
                : new HashMap<>();
        Map<String, LocalDateTime> enrolledAt = course.getStudentEnrolledAt() != null
                ? new HashMap<>(course.getStudentEnrolledAt())
                : new HashMap<>();

        String instId = course.getInstructorId();
        List<String> studs = course.getEnrolledStudents() != null
                ? new ArrayList<>(course.getEnrolledStudents())
                : new ArrayList<>();
        studs.sort(String::compareTo);

        List<String> profileIds = new ArrayList<>();
        if (instId != null && !instId.isBlank()) {
            profileIds.add(instId);
        }
        for (String sid : studs) {
            if (instId == null || !sid.equals(instId)) {
                profileIds.add(sid);
            }
        }
        Map<String, Map<String, Object>> profiles = resolveUserProfiles(profileIds);

        List<Long> lessonIds = lessonRepository.findByCourseIdOrderByOrderNumber(courseId).stream()
                .map(Lesson::getId)
                .filter(Objects::nonNull)
                .toList();
        int totalLessons = lessonIds.size();

        com.microservices.courseservice.dto.ParticipantSummaryDto instructor = instId != null
                ? buildParticipantSummary(instId, "INSTRUCTOR", profiles, labels, enrolledAt, lessonIds, totalLessons)
                : null;

        List<com.microservices.courseservice.dto.ParticipantSummaryDto> studentRows = studs.stream()
                .filter(sid -> instId == null || !sid.equals(instId))
                .map(sid -> buildParticipantSummary(sid, "STUDENT", profiles, labels, enrolledAt, lessonIds, totalLessons))
                .toList();

        return com.microservices.courseservice.dto.CourseParticipantsDto.builder()
                .instructor(instructor)
                .students(studentRows)
                .studentCount(studentRows.size())
                .build();
    }

    private Map<String, Map<String, Object>> resolveUserProfiles(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        try {
            List<Map<String, Object>> resolved = authServiceClient.resolveUsers(Map.of("userIds", userIds));
            if (resolved == null || resolved.isEmpty()) {
                return Map.of();
            }
            Map<String, Map<String, Object>> byId = new HashMap<>();
            for (Map<String, Object> profile : resolved) {
                if (profile == null) {
                    continue;
                }
                Object id = profile.get("id");
                if (id != null) {
                    byId.put(id.toString(), profile);
                }
            }
            return byId;
        } catch (Exception ex) {
            log.warn("Could not resolve participant profiles: {}", ex.getMessage());
            return Map.of();
        }
    }

    private com.microservices.courseservice.dto.ParticipantSummaryDto buildParticipantSummary(
            String userId,
            String role,
            Map<String, Map<String, Object>> profiles,
            Map<String, String> labels,
            Map<String, LocalDateTime> enrolledAtMap,
            List<Long> lessonIds,
            int totalLessons) {
        Map<String, Object> profile = profiles.get(userId);
        String label = labels.get(userId);
        String email = firstNonBlank(stringClaim(profile, "email"), emailFromDisplayLabel(label));
        String fullName = firstNonBlank(
                stringClaim(profile, "fullName"),
                buildNameFromParts(stringClaim(profile, "firstName"), stringClaim(profile, "lastName")),
                stringClaim(profile, "username"),
                nameFromDisplayLabel(label)
        );
        if (fullName == null || fullName.isBlank()) {
            fullName = email != null && email.contains("@") ? localPartBeforeAt(email) : "Participant";
        }

        String enrolledAt = null;
        LocalDateTime enrolled = enrolledAtMap.get(userId);
        if (enrolled != null) {
            enrolledAt = enrolled.toString();
        }

        Integer progressPercent = null;
        if ("STUDENT".equals(role) && totalLessons > 0) {
            long completed = progressRepository.countByStudentIdAndLessonIdInAndCompletedTrue(userId, lessonIds);
            progressPercent = (int) Math.round(100.0 * completed / totalLessons);
        }

        return com.microservices.courseservice.dto.ParticipantSummaryDto.builder()
                .userId(userId)
                .fullName(fullName)
                .email(email)
                .role(role)
                .enrolledAt(enrolledAt)
                .progressPercent(progressPercent)
                .displayLabel(label)
                .avatarUrl(stringClaim(profile, "avatarUrl"))
                .build();
    }

    private String stringClaim(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        Object value = map.get(key);
        return value != null ? value.toString().trim() : null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String buildNameFromParts(String firstName, String lastName) {
        StringBuilder sb = new StringBuilder();
        if (firstName != null && !firstName.isBlank()) {
            sb.append(firstName.trim());
        }
        if (lastName != null && !lastName.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(lastName.trim());
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private String emailFromDisplayLabel(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String trimmed = label.trim();
        return trimmed.contains("@") ? trimmed.toLowerCase() : null;
    }

    private String nameFromDisplayLabel(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String trimmed = label.trim();
        if (trimmed.contains("@")) {
            return humanizeLocalPart(localPartBeforeAt(trimmed));
        }
        return trimmed;
    }

    private String localPartBeforeAt(String email) {
        if (email == null) {
            return "";
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private String humanizeLocalPart(String localPart) {
        if (localPart == null || localPart.isBlank()) {
            return null;
        }
        return localPart.replace('.', ' ').replace('_', ' ').trim();
    }

    @Transactional
    public void updateAllowedEmails(Long courseId, List<String> emails, Jwt jwt) {
        Course course = getCourseById(courseId);
        validateCourseUpdatePermission(course, jwt);
        List<String> mutableList = emails == null ? new ArrayList<>()
                : new ArrayList<>(emails.stream()
                        .map(e -> e != null ? e.toLowerCase().trim() : "")
                        .filter(e -> !e.isBlank())
                        .distinct()
                        .toList());
        course.setAllowedEmails(mutableList);
        courseRepository.save(course);
    }

    private void validateCourseCreationPermission(Jwt jwt) {
        if (!RoleUtil.isTeacher(jwt) && !RoleUtil.isAdmin(jwt)) {
            throw new AccessDeniedException("Only TEACHER and ADMIN can create courses");
        }
    }

    private void validateCourseUpdatePermission(Course course, Jwt jwt) {
        if (!RoleUtil.isAdmin(jwt) && !course.getInstructorId().equals(jwt.getSubject())) {
            throw new AccessDeniedException("Only course instructor can update course");
        }
    }

    public void assertCanMutateCourseContent(Long courseId, Jwt jwt) {
        Course course = getCourseById(courseId);
        validateCourseUpdatePermission(course, jwt);
    }

    private void validateCourseDeletePermission(Course course, Jwt jwt) {
        if (!RoleUtil.isAdmin(jwt) && !course.getInstructorId().equals(jwt.getSubject())) {
            throw new AccessDeniedException("Only course instructor or admin can delete course");
        }
    }

    private void validateVideoCreationPermission(Jwt jwt) {
        if (!RoleUtil.canUpload(jwt)) {
            throw new AccessDeniedException("Only TEACHER and ADMIN can create videos");
        }
    }

    private void incrementCourseViews(Long courseId) {
        String viewKey = "course:views:" + courseId;
        cacheService.increment(viewKey, 24, java.util.concurrent.TimeUnit.HOURS);
    }

    private int calculateNextVideoOrder(Long lessonId) {
        List<Video> existingVideos = videoRepository.findByLessonIdOrderByOrderNumber(lessonId);
        return existingVideos.isEmpty() ? 1 : existingVideos.get(existingVideos.size() - 1).getOrderNumber() + 1;
    }

    public AdminPlatformStatsDto getAdminPlatformStats(Jwt jwt) {
        if (!RoleUtil.isAdmin(jwt)) {
            throw new AccessDeniedException("Admin only");
        }
        List<Course> all = courseRepository.findAll();
        long published = all.stream().filter(c -> c.getStatus() == Course.CourseStatus.PUBLISHED).count();
        long draft = all.stream().filter(c -> c.getStatus() == Course.CourseStatus.DRAFT).count();
        long archived = all.stream().filter(c -> c.getStatus() == Course.CourseStatus.ARCHIVED).count();
        long enrollmentSlots = all.stream()
                .mapToLong(c -> c.getEnrolledStudents() == null ? 0 : c.getEnrolledStudents().size())
                .sum();
        long uniqueInstructors = all.stream()
                .map(Course::getInstructorId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        return AdminPlatformStatsDto.builder()
                .totalCourses(all.size())
                .uniqueInstructors(uniqueInstructors)
                .publishedCourses(published)
                .draftCourses(draft)
                .archivedCourses(archived)
                .totalLessons(lessonRepository.count())
                .totalTests(testRepository.count())
                .totalEnrollmentSlots(enrollmentSlots)
                .build();
    }

    @Transactional
    public Map<String, Object> backfillLocalizations(Long courseId, Jwt jwt) {
        Course course = getCourseById(courseId);
        validateCourseUpdatePermission(course, jwt);

        int coursesUpdated = 0;
        int lessonsUpdated = 0;
        int testsUpdated = 0;
        int questionsUpdated = 0;

        if (backfillCourse(course)) {
            courseRepository.save(course);
            coursesUpdated++;
        }

        List<Lesson> lessons = lessonRepository.findByCourseIdOrderByOrderNumber(courseId);
        for (Lesson lesson : lessons) {
            if (backfillLesson(lesson)) {
                lessonRepository.save(lesson);
                lessonsUpdated++;
            }
        }

        List<Test> tests = testRepository.findByCourseId(courseId);
        for (Test test : tests) {
            boolean testChanged = backfillTest(test);
            List<Question> questions = questionRepository.findByTestIdOrderByOrderNumber(test.getId());
            int updatedQuestionsForTest = 0;
            for (Question question : questions) {
                if (backfillQuestion(question)) {
                    questionRepository.save(question);
                    updatedQuestionsForTest++;
                    questionsUpdated++;
                }
            }
            if (testChanged) {
                testRepository.save(test);
                testsUpdated++;
            }
        }

        return Map.of(
                "courseId", courseId,
                "coursesUpdated", coursesUpdated,
                "lessonsUpdated", lessonsUpdated,
                "testsUpdated", testsUpdated,
                "questionsUpdated", questionsUpdated
        );
    }

    public void requireBackfillLocalizationsPermission(Long courseId, Jwt jwt) {
        Course course = getCourseById(courseId);
        validateCourseUpdatePermission(course, jwt);
    }

    public boolean backfillCourse(Course course) {
        return backfillCourse(course, true, true);
    }

    public boolean backfillCourse(Course course, boolean doKz, boolean doEn) {
        boolean needsKz = isBlank(course.getTitleKz()) || isBlank(course.getDescriptionKz());
        boolean needsEn = isBlank(course.getTitleEn()) || isBlank(course.getDescriptionEn());
        if (!needsKz && !needsEn) return false;
        if (isBlank(course.getTitle()) && isBlank(course.getDescription())) return false;

        if (doKz && needsKz) {
            Map<String, Object> tr = ragClient.translateJson(Map.of(
                    "title", nvl(course.getTitle()),
                    "description", nvl(course.getDescription())
            ), "kz");
            if (isBlank(course.getTitleKz())) course.setTitleKz(str(tr.get("title")));
            if (isBlank(course.getDescriptionKz())) course.setDescriptionKz(str(tr.get("description")));
        }
        if (doEn && needsEn) {
            Map<String, Object> tr = ragClient.translateJson(Map.of(
                    "title", nvl(course.getTitle()),
                    "description", nvl(course.getDescription())
            ), "en");
            if (isBlank(course.getTitleEn())) course.setTitleEn(str(tr.get("title")));
            if (isBlank(course.getDescriptionEn())) course.setDescriptionEn(str(tr.get("description")));
        }
        return true;
    }

    public boolean backfillLesson(Lesson lesson) {
        return backfillLesson(lesson, true, true);
    }

    public boolean backfillLesson(Lesson lesson, boolean doKz, boolean doEn) {
        boolean needsKz = isBlank(lesson.getTitleKz()) || isBlank(lesson.getDescriptionKz()) || isBlank(lesson.getContentKz());
        boolean needsEn = isBlank(lesson.getTitleEn()) || isBlank(lesson.getDescriptionEn()) || isBlank(lesson.getContentEn());
        if (!needsKz && !needsEn) return false;
        if (isBlank(lesson.getTitle()) && isBlank(lesson.getDescription()) && isBlank(lesson.getContent())) return false;

        Map<String, Object> source = new HashMap<>();
        source.put("title", nvl(lesson.getTitle()));
        source.put("description", nvl(lesson.getDescription()));
        source.put("content", nvl(lesson.getContent()));

        if (doKz && needsKz) {
            Map<String, Object> tr = ragClient.translateJson(source, "kz");
            if (isBlank(lesson.getTitleKz())) lesson.setTitleKz(str(tr.get("title")));
            if (isBlank(lesson.getDescriptionKz())) lesson.setDescriptionKz(str(tr.get("description")));
            if (isBlank(lesson.getContentKz())) lesson.setContentKz(str(tr.get("content")));
        }
        if (doEn && needsEn) {
            Map<String, Object> tr = ragClient.translateJson(source, "en");
            if (isBlank(lesson.getTitleEn())) lesson.setTitleEn(str(tr.get("title")));
            if (isBlank(lesson.getDescriptionEn())) lesson.setDescriptionEn(str(tr.get("description")));
            if (isBlank(lesson.getContentEn())) lesson.setContentEn(str(tr.get("content")));
        }
        return true;
    }

    public boolean backfillTest(Test test) {
        return backfillTest(test, true, true);
    }

    public boolean backfillTest(Test test, boolean doKz, boolean doEn) {
        boolean needsKz = isBlank(test.getTitleKz());
        boolean needsEn = isBlank(test.getTitleEn());
        if (!needsKz && !needsEn) return false;
        if (isBlank(test.getTitle())) return false;

        if (doKz && needsKz) {
            Map<String, Object> tr = ragClient.translateJson(Map.of("title", nvl(test.getTitle())), "kz");
            if (isBlank(test.getTitleKz())) test.setTitleKz(str(tr.get("title")));
        }
        if (doEn && needsEn) {
            Map<String, Object> tr = ragClient.translateJson(Map.of("title", nvl(test.getTitle())), "en");
            if (isBlank(test.getTitleEn())) test.setTitleEn(str(tr.get("title")));
        }
        return true;
    }

    public boolean backfillQuestion(Question question) {
        return backfillQuestion(question, true, true);
    }

    public boolean backfillQuestion(Question question, boolean doKz, boolean doEn) {
        boolean needsKz = isBlank(question.getTextKz()) || isBlank(question.getOptionsKz())
                || isBlank(question.getExplanationKz()) || isBlank(question.getHintKz());
        boolean needsEn = isBlank(question.getTextEn()) || isBlank(question.getOptionsEn())
                || isBlank(question.getExplanationEn()) || isBlank(question.getHintEn());
        if (!needsKz && !needsEn) return false;

        Map<String, Object> source = new HashMap<>();
        source.put("text", nvl(question.getText()));
        source.put("options", parseJsonObject(question.getOptions()));
        source.put("explanation", nvl(question.getExplanation()));
        source.put("hint", nvl(question.getHint()));

        if (doKz && needsKz) {
            Map<String, Object> tr = ragClient.translateJson(source, "kz");
            if (isBlank(question.getTextKz())) question.setTextKz(str(tr.get("text")));
            if (isBlank(question.getOptionsKz())) question.setOptionsKz(toJson(tr.get("options")));
            if (isBlank(question.getExplanationKz())) question.setExplanationKz(str(tr.get("explanation")));
            if (isBlank(question.getHintKz())) question.setHintKz(str(tr.get("hint")));
        }
        if (doEn && needsEn) {
            Map<String, Object> tr = ragClient.translateJson(source, "en");
            if (isBlank(question.getTextEn())) question.setTextEn(str(tr.get("text")));
            if (isBlank(question.getOptionsEn())) question.setOptionsEn(toJson(tr.get("options")));
            if (isBlank(question.getExplanationEn())) question.setExplanationEn(str(tr.get("explanation")));
            if (isBlank(question.getHintEn())) question.setHintEn(str(tr.get("hint")));
        }
        return true;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Object parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(raw, Map.class);
        } catch (Exception e) {
            return raw;
        }
    }
}
