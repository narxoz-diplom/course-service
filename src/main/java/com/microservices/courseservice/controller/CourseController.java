package com.microservices.courseservice.controller;

import com.microservices.courseservice.dto.StatusUpdateRequest;
import com.microservices.courseservice.dto.VideoMetadataRequest;
import com.microservices.courseservice.model.Course;
import com.microservices.courseservice.model.Lesson;
import com.microservices.courseservice.model.Video;
import com.microservices.courseservice.repository.LessonRepository;
import com.microservices.courseservice.service.CacheService;
import com.microservices.courseservice.service.CourseService;
import com.microservices.courseservice.util.RoleUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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
    private final LessonRepository lessonRepository;
    private final CacheService cacheService;

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
    public Course getCourse(@PathVariable Long id) {
        return courseService.getCourseById(id);
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
        Course existing = courseService.getCourseById(id);
        Course.CourseStatus newStatus = Course.CourseStatus.valueOf(request.getStatus().toUpperCase());
        existing.setStatus(newStatus);
        return courseService.updateCourse(id, jwt, existing);
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


    @PostMapping("/{courseId}/enroll")
    public ResponseEntity<Void> enrollInCourse(
            @PathVariable Long courseId,
            @AuthenticationPrincipal Jwt jwt) {
        courseService.enrollStudent(courseId, jwt.getSubject());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/views")
    public Long getCourseViews(@PathVariable Long id) {
        String viewKey = "course:views:" + id;
        return cacheService.getCounter(viewKey);
    }
}

