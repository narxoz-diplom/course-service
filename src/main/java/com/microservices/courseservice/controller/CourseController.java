package com.microservices.courseservice.controller;

import com.microservices.courseservice.dto.AdminPlatformStatsDto;
import com.microservices.courseservice.dto.StatusUpdateRequest;
import com.microservices.courseservice.dto.VideoMetadataRequest;
import com.microservices.courseservice.model.Course;
import com.microservices.courseservice.model.Lesson;
import com.microservices.courseservice.model.Video;
import com.microservices.courseservice.service.CacheService;
import com.microservices.courseservice.service.CourseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseService courseService;
    private final CacheService cacheService;
    private final com.microservices.courseservice.service.LessonGenerationJobService lessonGenerationJobService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Course createCourse(
            @RequestBody Course course,
            @AuthenticationPrincipal Jwt jwt) {
        course.setInstructorId(jwt.getSubject());
        return courseService.createCourse(course, jwt);
    }

    @GetMapping
    public List<Course> getAllCourses(@AuthenticationPrincipal Jwt jwt) {
        return courseService.getAllCourses(jwt);
    }

    @GetMapping("/enrolled")
    public List<Course> getEnrolledCourses(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        return courseService.getEnrolledCourses(userId);
    }

    @GetMapping("/published")
    public List<Course> getPublishedCourses() {
        return courseService.getAllPublishedCourses();
    }

    @GetMapping("/{id}")
    public Course getCourse(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return courseService.getCourseById(id, jwt);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Course> updateCourse(
            @PathVariable Long id,
            @RequestBody Course course,
            @AuthenticationPrincipal Jwt jwt) {
        Course updated = courseService.updateCourse(id, jwt, course);
        return ResponseEntity.ok(updated);
    }

    @PatchMapping("/{id}/status")
    public Course updateCourseStatus(
            @PathVariable Long id,
            @RequestBody StatusUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        Course.CourseStatus newStatus = Course.CourseStatus.valueOf(request.getStatus().toUpperCase());
        return courseService.updateCourseStatus(id, jwt, newStatus);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCourse(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        courseService.deleteCourse(id, jwt);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{courseId}/lessons")
    @ResponseStatus(HttpStatus.CREATED)
    public Lesson createLesson(
            @PathVariable Long courseId,
            @RequestBody Lesson lesson,
            @AuthenticationPrincipal Jwt jwt) {
        return courseService.createLesson(lesson, courseId, jwt);
    }

    @PostMapping("/{courseId}/lessons/generate-from-files")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Lesson> generateLessonsFromFiles(
            @PathVariable Long courseId,
            @RequestBody com.microservices.courseservice.dto.GenerateLessonsRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return courseService.generateLessonsFromFiles(courseId, request, jwt);
    }

    @PostMapping("/{courseId}/lessons/generate-outline")
    public com.microservices.courseservice.dto.CourseOutlineResponse generateLessonOutline(
            @PathVariable Long courseId,
            @RequestBody com.microservices.courseservice.dto.GenerateLessonsRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return courseService.generateLessonOutline(courseId, request, jwt);
    }

    @PostMapping("/{courseId}/lessons/generate-from-outline")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Lesson> generateLessonsFromOutline(
            @PathVariable Long courseId,
            @RequestBody com.microservices.courseservice.dto.GenerateFromOutlineRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return courseService.generateLessonsFromOutline(courseId, request, jwt);
    }

    @PostMapping("/{courseId}/lessons/generation-jobs/from-outline")
    public com.microservices.courseservice.dto.LessonGenerationJobDto startLessonGenerationJobFromOutline(
            @PathVariable Long courseId,
            @RequestBody com.microservices.courseservice.dto.GenerateFromOutlineRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return lessonGenerationJobService.startFromOutlineJob(courseId, request, jwt, authorization);
    }

    @GetMapping("/{courseId}/lessons/generation-jobs/{jobId}")
    public com.microservices.courseservice.dto.LessonGenerationJobDto getLessonGenerationJob(
            @PathVariable Long courseId,
            @PathVariable String jobId,
            @AuthenticationPrincipal Jwt jwt) {
        return lessonGenerationJobService.getJob(jobId, courseId, jwt);
    }

    @GetMapping("/{courseId}/lessons")
    @ResponseStatus(HttpStatus.OK)
    public List<Lesson> getLessons(@PathVariable Long courseId) {
        return courseService.getLessonsByCourse(courseId);
    }

    @GetMapping("/lessons/{lessonId}")
    public Lesson getLesson(@PathVariable Long lessonId) {
        return courseService.getLessonById(lessonId);
    }

    @PutMapping("/lessons/{lessonId}")
    @ResponseStatus(HttpStatus.OK)
    public Lesson updateLesson(
            @PathVariable Long lessonId,
            @RequestBody Lesson lesson,
            @AuthenticationPrincipal Jwt jwt) {
        return courseService.updateLesson(lessonId, lesson, jwt);
    }

    @DeleteMapping("/lessons/{lessonId}")
    public ResponseEntity<Void> deleteLesson(
            @PathVariable Long lessonId,
            @AuthenticationPrincipal Jwt jwt) {
        courseService.deleteLesson(lessonId, jwt);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/lessons/{lessonId}/videos")
    @ResponseStatus(HttpStatus.CREATED)
    public Video createVideoMetadata(
            @PathVariable Long lessonId,
            @RequestBody VideoMetadataRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return courseService.createVideo(request, lessonId, jwt);
    }

    @GetMapping("/lessons/{lessonId}/videos")
    public List<Video> getVideos(@PathVariable Long lessonId) {
        return courseService.getVideosByLesson(lessonId);
    }

    @DeleteMapping("/lessons/{lessonId}/videos/{videoId}")
    public ResponseEntity<Void> deleteVideo(
            @PathVariable Long lessonId,
            @PathVariable Long videoId,
            @AuthenticationPrincipal Jwt jwt) {
        courseService.deleteVideo(videoId, jwt);
        return ResponseEntity.noContent().build();
    }


    @PostMapping("/{courseId}/enroll")
    public ResponseEntity<Void> enrollInCourse(
            @PathVariable Long courseId,
            @AuthenticationPrincipal Jwt jwt) {
        courseService.enrollStudent(courseId, jwt.getSubject(), jwt);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{id}/allowed-emails")
    public ResponseEntity<Course> updateAllowedEmails(
            @PathVariable Long id,
            @RequestBody java.util.List<String> emails,
            @AuthenticationPrincipal Jwt jwt) {
        courseService.updateAllowedEmails(id, emails, jwt);
        return ResponseEntity.ok(courseService.getCourseById(id));
    }

    @GetMapping("/{id}/views")
    public Long getCourseViews(@PathVariable Long id) {
        String viewKey = "course:views:" + id;
        return cacheService.getCounter(viewKey);
    }

    @PostMapping("/{courseId}/backfill-localizations")
    public ResponseEntity<java.util.Map<String, Object>> backfillLocalizations(
            @PathVariable Long courseId,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(courseService.backfillLocalizations(courseId, jwt));
    }

    @PostMapping("/{courseId}/tests/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public com.microservices.courseservice.model.Test generateTest(
            @PathVariable Long courseId,
            @RequestBody com.microservices.courseservice.dto.GenerateTestRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return courseService.generateTest(courseId, request, jwt);
    }

    @GetMapping("/{courseId}/tests")
    public java.util.List<com.microservices.courseservice.model.Test> getTests(
            @PathVariable Long courseId) {
        return courseService.getTestsByCourse(courseId);
    }

    @GetMapping("/tests/{testId}")
    public com.microservices.courseservice.model.Test getTest(@PathVariable Long testId) {
        return courseService.getTestById(testId);
    }

    @PostMapping("/tests/{testId}/submit")
    @ResponseStatus(HttpStatus.CREATED)
    public com.microservices.courseservice.model.TestAttempt submitTest(
            @PathVariable Long testId,
            @RequestBody com.microservices.courseservice.dto.SubmitTestRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return courseService.submitTestAttempt(
                testId,
                jwt.getSubject(),
                request.getAnswers(),
                request.getSuspiciousFlag(),
                jwt);
    }

    @GetMapping("/{courseId}/test-results")
    public java.util.List<com.microservices.courseservice.model.TestAttempt> getTestResults(
            @PathVariable Long courseId,
            @AuthenticationPrincipal Jwt jwt) {
        return courseService.getTestAttemptsByCourse(courseId, jwt);
    }

    @GetMapping("/my/test-attempts")
    public java.util.List<com.microservices.courseservice.model.TestAttempt> getMyTestAttempts(
            @AuthenticationPrincipal Jwt jwt) {
        return courseService.getMyTestAttempts(jwt.getSubject());
    }

    @GetMapping("/admin/platform-stats")
    public AdminPlatformStatsDto getAdminPlatformStats(@AuthenticationPrincipal Jwt jwt) {
        return courseService.getAdminPlatformStats(jwt);
    }

}