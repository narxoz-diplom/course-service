package com.microservices.courseservice.service;

import com.microservices.courseservice.client.AuthServiceClient;
import com.microservices.courseservice.client.FileServiceClient;
import com.microservices.courseservice.client.RagClient;
import com.microservices.courseservice.dto.AdminPlatformStatsDto;
import com.microservices.courseservice.dto.CourseOutlineResponse;
import com.microservices.courseservice.dto.GenerateFromOutlineRequest;
import com.microservices.courseservice.dto.GenerateLessonsRequest;
import com.microservices.courseservice.dto.GenerateTestRequest;
import com.microservices.courseservice.dto.LessonGenerationParamsDto;
import com.microservices.courseservice.dto.LessonOutlineItemDto;
import com.microservices.courseservice.dto.RagLessonDto;
import com.microservices.courseservice.dto.RagQuizQuestionDto;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CourseService {

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
    private final QuestionRepository questionRepository;
    private final LessonTestQualityGate qualityGate;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final OutlineLessonStepService outlineLessonStepService;
    private final LessonGenerationJobRepository lessonGenerationJobRepository;
    private final PlatformTransactionManager transactionManager;

    private TransactionTemplate readOnlyCourseTx;

    @PostConstruct
    void initReadOnlyCourseTemplate() {
        readOnlyCourseTx = new TransactionTemplate(transactionManager);
        readOnlyCourseTx.setReadOnly(true);
    }

    @Transactional
    public Course createCourse(Course course, Jwt jwt) {
        validateCourseCreationPermission(jwt);
        
        log.info("Creating course: {} by instructor: {}", course.getTitle(), course.getInstructorId());
        Course created = courseRepository.save(course);
        
        courseCacheService.invalidatePublishedCoursesCache();
        
        return created;
    }

    public Course getCourseById(Long id) {
        return getCourseById(id, null);
    }

    /**
     * Uses an explicit read-only {@link TransactionTemplate} so lazy collections initialize while the session is open.
     * Works for {@code @Async} and other internal callers (unlike {@code @Transactional} on a method called via {@code this}).
     */
    public Course getCourseById(Long id, Jwt jwt) {
        return readOnlyCourseTx.execute(
                status -> {
                    Course course = courseRepository
                            .findById(id)
                            .orElseThrow(() -> new RuntimeException("Course not found with id: " + id));
                    if (jwt != null && !RoleUtil.isAdmin(jwt) && !RoleUtil.isTeacher(jwt)) {
                        validateStudentCourseAccess(course, jwt);
                    }
                    if (course.getLessons() != null) {
                        course.getLessons().size();
                    }
                    incrementCourseViews(id);
                    return course;
                });
    }

    private void validateStudentCourseAccess(Course course, Jwt jwt) {
        String userId = jwt.getSubject();
        if (course.getEnrolledStudents() != null && course.getEnrolledStudents().contains(userId)) {
            return;
        }
        String email = getUserEmail(userId, jwt);
        if (email != null && course.getAllowedEmails() != null && course.getAllowedEmails().contains(email)) {
            return;
        }
        if (course.getInstructorId().equals(userId)) {
            return;
        }
        throw new AccessDeniedException("You do not have access to this course");
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
    public List<Lesson> generateLessonsFromFiles(Long courseId, GenerateLessonsRequest genRequest, Jwt jwt) {
        Course course = getCourseById(courseId);
        validateCourseUpdatePermission(course, jwt);

        String collectionName = "course_" + courseId;
        List<Long> filterFileIds = resolveCourseFileFilter(courseId, genRequest.getFileIds());

        String prompt = genRequest.getPrompt();
        if (prompt != null && prompt.isBlank()) {
            prompt = null;
        }

        List<Lesson> existingLessons = lessonService.getLessonsByCourse(courseId);
        var ragResponse = ragClient.generateLessonsResponse(
                collectionName,
                filterFileIds,
                prompt,
                genRequest.getTopK(),
                genRequest.getParams(),
                List.of("ru", "kz", "en"));
        List<RagLessonDto> validatedLessons = validateLessonsWithRetry(
                collectionName, filterFileIds, prompt, genRequest.getTopK(), genRequest.getParams(),
                ragResponse.getLessons(), existingLessons);

        return persistRagLessons(course, courseId, collectionName, validatedLessons, ragResponse, jwt);
    }

    public CourseOutlineResponse generateLessonOutline(Long courseId, GenerateLessonsRequest genRequest, Jwt jwt) {
        Course course = getCourseById(courseId);
        validateCourseUpdatePermission(course, jwt);
        return buildCourseOutlineResponse(courseId, genRequest);
    }

    private CourseOutlineResponse buildCourseOutlineResponse(Long courseId, GenerateLessonsRequest genRequest) {
        String collectionName = "course_" + courseId;
        List<Long> filterFileIds = resolveCourseFileFilter(courseId, genRequest.getFileIds());
        String prompt = genRequest.getPrompt();
        if (prompt != null && prompt.isBlank()) {
            prompt = null;
        }
        return ragClient.generateCourseOutline(
                collectionName, filterFileIds, prompt, genRequest.getTopK(), genRequest.getParams());
    }

    /**
     * Async / internal: only the real course instructor (not admin impersonation).
     */
    public CourseOutlineResponse generateLessonOutlineForInstructor(
            Long courseId, GenerateLessonsRequest genRequest, String instructorId) {
        Course course = getCourseById(courseId);
        if (!course.getInstructorId().equals(instructorId)) {
            throw new AccessDeniedException("Only course instructor can generate outline for this course");
        }
        return buildCourseOutlineResponse(courseId, genRequest);
    }

    public List<Lesson> generateLessonsFromOutline(Long courseId, GenerateFromOutlineRequest genRequest, Jwt jwt) {
        Course course = getCourseById(courseId);
        validateCourseUpdatePermission(course, jwt);
        return generateLessonsFromOutlineAuthorized(courseId, genRequest, jwt, null);
    }

    /**
     * Background generation: instructor id must match course owner (no admin bypass in jobs).
     *
     * @param jobId when non-null, job row is updated with incremental progress and marked COMPLETED at the end.
     */
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

    /**
     * Each lesson is persisted in its own transaction ({@link OutlineLessonStepService}) so a failure late in the job
     * does not roll back earlier lessons. Optional {@code jobId} ties into {@link LessonGenerationJob} progress fields.
     */
    private List<Lesson> generateLessonsFromOutlineAuthorized(
            Long courseId,
            GenerateFromOutlineRequest genRequest,
            Jwt jwt,
            String jobId) {
        if (genRequest.getOutline() == null || genRequest.getOutline().isEmpty()) {
            throw new RuntimeException("Outline must contain at least one lesson");
        }
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
                job.setTotalLessons(total);
                job.setCompletedLessons(0);
                job.setCurrentLessonTitle(items.isEmpty() ? null : items.get(0).getTitle());
                lessonGenerationJobRepository.save(job);
            });
        }

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
                    jwt);
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
            List<Lesson> existingLessons) {
        try {
            return qualityGate.validateAndDeduplicateRagLessons(ragLessons, existingLessons);
        } catch (QualityGateException e) {
            log.warn("Quality gate failed for generated lessons, retrying once: {}", e.getMessage());
            List<RagLessonDto> retryLessons = ragClient.generateLessons(collectionName, filterFileIds, prompt, topK, params);
            return qualityGate.validateAndDeduplicateRagLessons(retryLessons, existingLessons);
        }
    }

    @Transactional
    public Test generateTest(Long courseId, GenerateTestRequest testRequest, Jwt jwt) {
        Course course = getCourseById(courseId);
        validateCourseUpdatePermission(course, jwt);

        String collectionName = "course_" + courseId;
        List<Long> fileIds = testRequest.getFileIds();
        List<Long> lessonIds = testRequest.getLessonIds();
        List<Long> filterFileIds = null;
        if (fileIds != null && !fileIds.isEmpty()) {
            filterFileIds = resolveCourseFileFilter(courseId, fileIds);
        }

        var quizResponse = ragClient.generateQuizResponse(
                collectionName,
                filterFileIds,
                lessonIds,
                null,
                testRequest.getQuestionCount(),
                testRequest.getDifficulty(),
                List.of("ru", "kz", "en"));
        List<RagQuizQuestionDto> validatedQuestions = validateQuestionsWithRetry(
                collectionName,
                filterFileIds,
                lessonIds,
                testRequest.getQuestionCount(),
                testRequest.getDifficulty(),
                quizResponse.getQuestions());

        String title = testRequest.getTitle();
        Test test = new Test();
        String baseTitle = title != null && !title.isBlank() ? title : "Тест по курсу";
        test.setTitle(baseTitle);
        // Best-effort: if quiz has translations, title is derived; keep null unless teacher provided custom.
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
        return test;
    }

    private List<RagQuizQuestionDto> validateQuestionsWithRetry(
            String collectionName,
            List<Long> filterFileIds,
            List<Long> lessonIds,
            Integer questionCount,
            String difficulty,
            List<RagQuizQuestionDto> ragQuestions) {
        try {
            return qualityGate.validateAndDeduplicateRagQuestions(ragQuestions);
        } catch (QualityGateException e) {
            log.warn("Quality gate failed for generated questions, retrying once: {}", e.getMessage());
            List<RagQuizQuestionDto> retryQuestions = ragClient.generateQuiz(
                    collectionName, filterFileIds, lessonIds, null, questionCount, difficulty);
            return qualityGate.validateAndDeduplicateRagQuestions(retryQuestions);
        }
    }

    private static String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    public List<Lesson> getLessonsByCourse(Long courseId) {
        return lessonService.getLessonsByCourse(courseId);
    }

    public Lesson getLessonById(Long lessonId) {
        return lessonService.getLessonById(lessonId);
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

    public List<Video> getVideosByLesson(Long lessonId) {
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

    public List<Test> getTestsByCourse(Long courseId) {
        return testRepository.findByCourseId(courseId);
    }

    public Test getTestById(Long testId) {
        return testRepository.findById(testId)
                .orElseThrow(() -> new RuntimeException("Test not found: " + testId));
    }

    @Transactional
    public TestAttempt submitTestAttempt(Long testId, String studentId, java.util.Map<String, String> answers, Boolean suspiciousFlag, Jwt jwt) {
        Test test = getTestById(testId);
        Course course = test.getCourse();
        validateStudentCourseAccess(course, jwt);
        if (!course.getEnrolledStudents().contains(studentId)) {
            throw new AccessDeniedException("You must be enrolled to take this test");
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

    public List<TestAttempt> getTestAttemptsByCourse(Long courseId, Jwt jwt) {
        Course course = getCourseById(courseId);
        validateCourseUpdatePermission(course, jwt);
        return testAttemptRepository.findByTest_Course_IdOrderByCompletedAtDesc(courseId);
    }

    public List<TestAttempt> getMyTestAttempts(String studentId) {
        return testAttemptRepository.findByStudentIdOrderByCompletedAtDesc(studentId);
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
        if (!course.getEnrolledStudents().contains(studentId)) {
            course.getEnrolledStudents().add(studentId);
            courseRepository.save(course);
            log.info("Student {} enrolled in course {}", studentId, courseId);
        }
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

    /** For other services (e.g. async jobs) that need the same rule as update endpoints. */
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
}
