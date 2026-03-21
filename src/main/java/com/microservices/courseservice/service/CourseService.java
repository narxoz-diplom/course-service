package com.microservices.courseservice.service;

import com.microservices.courseservice.client.AuthServiceClient;
import com.microservices.courseservice.client.FileServiceClient;
import com.microservices.courseservice.client.RagClient;
import com.microservices.courseservice.dto.RagLessonDto;
import com.microservices.courseservice.dto.RagQuizQuestionDto;
import com.microservices.courseservice.dto.VideoMetadataRequest;
import com.microservices.courseservice.exception.QualityGateException;
import com.microservices.courseservice.mapper.CourseMapper;
import com.microservices.courseservice.mapper.VideoMapper;
import com.microservices.courseservice.model.*;
import com.microservices.courseservice.repository.*;
import com.microservices.courseservice.util.RoleUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    public Course getCourseById(Long id, Jwt jwt) {
        Course course = courseRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Course not found with id: " + id));
        if (jwt != null && !RoleUtil.isAdmin(jwt) && !RoleUtil.isTeacher(jwt)) {
            validateStudentCourseAccess(course, jwt);
        }
        if (course.getLessons() != null) {
            course.getLessons().size();
        }
        incrementCourseViews(id);
        return course;
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
    }

    @Transactional
    public Lesson createLesson(Lesson lesson, Long courseId, Jwt jwt) {
        Course course = getCourseById(courseId);
        return lessonService.createLesson(lesson, course, jwt);
    }

    @Transactional
    public List<Lesson> generateLessonsFromFiles(Long courseId, List<Long> fileIds, Jwt jwt) {
        Course course = getCourseById(courseId);
        validateCourseUpdatePermission(course, jwt);

        String collectionName = "course_" + courseId;
        List<Long> filterFileIds = null;
        if (fileIds != null && !fileIds.isEmpty()) {
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
            filterFileIds = validIds;
        }

        List<Lesson> existingLessons = lessonService.getLessonsByCourse(courseId);
        List<RagLessonDto> ragLessons = ragClient.generateLessons(collectionName, filterFileIds, null);
        List<RagLessonDto> validatedLessons = validateLessonsWithRetry(collectionName, filterFileIds, ragLessons, existingLessons);

        List<Lesson> created = new ArrayList<>();
        int order = 1;
        for (RagLessonDto rl : validatedLessons) {
            Lesson lesson = new Lesson();
            String title = rl.getTitle() != null && !rl.getTitle().isBlank() ? rl.getTitle() : "Урок " + order;
            lesson.setTitle(title);
            lesson.setContent(rl.getContent() != null ? rl.getContent() : "");
            lesson.setDescription(rl.getDescription() != null ? rl.getDescription() : "");
            lesson.setOrderNumber(rl.getOrder() != null && rl.getOrder() >= 1 ? rl.getOrder() : order);
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

    private List<RagLessonDto> validateLessonsWithRetry(String collectionName, List<Long> filterFileIds,
                                                         List<RagLessonDto> ragLessons, List<Lesson> existingLessons) {
        try {
            return qualityGate.validateAndDeduplicateRagLessons(ragLessons, existingLessons);
        } catch (QualityGateException e) {
            log.warn("Quality gate failed for generated lessons, retrying once: {}", e.getMessage());
            List<RagLessonDto> retryLessons = ragClient.generateLessons(collectionName, filterFileIds, null);
            return qualityGate.validateAndDeduplicateRagLessons(retryLessons, existingLessons);
        }
    }

    @Transactional
    public Test generateTest(Long courseId, List<Long> fileIds, List<Long> lessonIds, String title, Jwt jwt) {
        Course course = getCourseById(courseId);
        validateCourseUpdatePermission(course, jwt);

        String collectionName = "course_" + courseId;
        List<Long> filterFileIds = null;
        if (fileIds != null && !fileIds.isEmpty()) {
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
            filterFileIds = validIds;
        }

        List<RagQuizQuestionDto> ragQuestions = ragClient.generateQuiz(collectionName, filterFileIds, lessonIds, null);
        List<RagQuizQuestionDto> validatedQuestions = validateQuestionsWithRetry(
                collectionName, filterFileIds, lessonIds, ragQuestions);

        Test test = new Test();
        test.setTitle(title != null && !title.isBlank() ? title : "Тест по курсу");
        test.setCourse(course);
        test.setIsVisible(true);
        test = testRepository.save(test);

        int order = 1;
        for (RagQuizQuestionDto rq : validatedQuestions) {
            Question q = new Question();
            q.setType(Question.QuestionType.MULTIPLE_CHOICE);
            q.setText(rq.getQuestion() != null ? rq.getQuestion() : "");
            Object opts = rq.getOptions();
            q.setOptions(opts != null ? toJson(opts) : "{}");
            q.setCorrectAnswer(rq.getCorrect() != null ? rq.getCorrect() : "");
            q.setExplanation(rq.getExplanation() != null ? rq.getExplanation() : "");
            q.setHint(rq.getHint() != null ? rq.getHint() : "");
            q.setTest(test);
            q.setOrderNumber(order++);
            qualityGate.validateQuestion(q);
            questionRepository.save(q);
        }
        return test;
    }

    private List<RagQuizQuestionDto> validateQuestionsWithRetry(String collectionName, List<Long> filterFileIds,
                                                                List<Long> lessonIds, List<RagQuizQuestionDto> ragQuestions) {
        try {
            return qualityGate.validateAndDeduplicateRagQuestions(ragQuestions);
        } catch (QualityGateException e) {
            log.warn("Quality gate failed for generated questions, retrying once: {}", e.getMessage());
            List<RagQuizQuestionDto> retryQuestions = ragClient.generateQuiz(collectionName, filterFileIds, lessonIds, null);
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
}
