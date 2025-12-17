package com.microservices.courseservice.service;

import com.microservices.courseservice.model.Course;
import com.microservices.courseservice.model.Lesson;
import com.microservices.courseservice.repository.LessonRepository;
import com.microservices.courseservice.util.RoleUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LessonService {

    private final LessonRepository lessonRepository;
    private final NotificationProducerService notificationProducerService;

    public List<Lesson> getLessonsByCourse(Long courseId) {
        return lessonRepository.findByCourseIdOrderByOrderNumber(courseId);
    }

    public Lesson getLessonById(Long lessonId) {
        return lessonRepository.findById(lessonId)
                .orElseThrow(() -> new RuntimeException("Lesson not found with id: " + lessonId));
    }

    @Transactional
    public Lesson createLesson(Lesson lesson, Course course, Jwt jwt) {
        validateLessonCreationPermission(course, jwt);
        
        lesson.setCourse(course);
        log.info("Creating lesson: {} for course: {}", lesson.getTitle(), course.getId());
        
        Lesson savedLesson = lessonRepository.save(lesson);
        
        sendNewLessonNotifications(course, savedLesson);
        
        return savedLesson;
    }

    @Transactional
    public Lesson updateLesson(Long lessonId, Lesson lessonUpdate, Jwt jwt) {
        Lesson existing = getLessonById(lessonId);
        Course course = existing.getCourse();
        
        validateLessonUpdatePermission(course, jwt);
        
        existing.setTitle(lessonUpdate.getTitle());
        existing.setDescription(lessonUpdate.getDescription());
        existing.setContent(lessonUpdate.getContent());
        existing.setOrderNumber(lessonUpdate.getOrderNumber());
        existing.setUpdatedAt(LocalDateTime.now());
        
        return lessonRepository.save(existing);
    }

    @Transactional
    public void deleteLesson(Long lessonId, Jwt jwt) {
        Lesson lesson = getLessonById(lessonId);
        Course course = lesson.getCourse();
        
        validateLessonUpdatePermission(course, jwt);
        
        lessonRepository.deleteById(lessonId);
        log.info("Deleted lesson: {} from course: {}", lessonId, course.getId());
    }

    private void validateLessonCreationPermission(Course course, Jwt jwt) {
        if (!RoleUtil.isTeacher(jwt) && !RoleUtil.isAdmin(jwt)) {
            throw new AccessDeniedException("Only TEACHER and ADMIN can create lessons");
        }
        if (!RoleUtil.isAdmin(jwt) && !course.getInstructorId().equals(jwt.getSubject())) {
            throw new AccessDeniedException("Only course instructor can add lessons");
        }
    }

    private void validateLessonUpdatePermission(Course course, Jwt jwt) {
        if (!RoleUtil.isAdmin(jwt) && !course.getInstructorId().equals(jwt.getSubject())) {
            throw new AccessDeniedException("Only course instructor or admin can modify lesson");
        }
    }

    private void sendNewLessonNotifications(Course course, Lesson lesson) {
        if (course.getEnrolledStudents() != null && !course.getEnrolledStudents().isEmpty()) {
            notificationProducerService.sendNewLessonNotificationsToAllStudents(
                course.getEnrolledStudents(),
                course.getTitle(),
                lesson.getTitle(),
                course.getId(),
                lesson.getId()
            );
        }
    }
}

