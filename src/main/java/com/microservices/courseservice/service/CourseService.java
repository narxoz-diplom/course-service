package com.microservices.courseservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.courseservice.dto.VideoMetadataRequest;
import com.microservices.courseservice.model.Course;
import com.microservices.courseservice.model.Lesson;
import com.microservices.courseservice.model.Video;
import com.microservices.courseservice.repository.CourseRepository;
import com.microservices.courseservice.repository.LessonRepository;
import com.microservices.courseservice.repository.VideoRepository;
import com.microservices.courseservice.util.RoleUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseRepository courseRepository;
    private final LessonRepository lessonRepository;
    private final VideoRepository videoRepository;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;

    @Transactional
    public Course createCourse(Course course, Jwt jwt) {
        if (!RoleUtil.isTeacher(jwt) && !RoleUtil.isAdmin(jwt)) {
            throw new AccessDeniedException("Only TEACHER and ADMIN can create courses");
        }
        log.info("Creating course: {} by instructor: {}", course.getTitle(), course.getInstructorId());
        Course created = courseRepository.save(course);
        
        cacheService.delete("courses:published");
        
        return created;
    }

    public Course getCourseById(Long id) {
        Course course = courseRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Course not found with id: " + id));
        if (course.getLessons() != null) {
            course.getLessons().size();
        }

        String viewKey = "course:views:" + id;
        cacheService.increment(viewKey, 24, java.util.concurrent.TimeUnit.HOURS);

        return course;
    }

    public List<Course> getAllCourses(Jwt jwt) {
        if (RoleUtil.isAdmin(jwt) || RoleUtil.isTeacher(jwt)) {
            return this.getCoursesByInstructor(jwt.getSubject());
        }
        return this.getAllPublishedCourses();
    }

    public List<Course> getAllPublishedCourses() {
        String cacheKey = "courses:published";
        
        String cached = cacheService.get(cacheKey);
        if (cached != null) {
            try {
                log.debug("Retrieved published courses from cache");
                return objectMapper.readValue(cached, new TypeReference<List<Course>>() {});
            } catch (JsonProcessingException e) {
                log.warn("Error deserializing cached courses", e);
            }
        }
        
        List<Course> courses = courseRepository.findByStatus(Course.CourseStatus.PUBLISHED);
        
        try {
            String json = objectMapper.writeValueAsString(courses);
            cacheService.set(cacheKey, json, 5, TimeUnit.MINUTES);
            log.debug("Cached published courses");
        } catch (JsonProcessingException e) {
            log.warn("Error serializing courses for cache", e);
        }
        
        return courses;
    }

    public List<Course> getCoursesByInstructor(String instructorId) {
        return courseRepository.findByInstructorId(instructorId);
    }

    public List<Course> getEnrolledCourses(String studentId) {
        return courseRepository.findByEnrolledStudentsContaining(studentId);
    }

    @Transactional
    public Course updateCourse(Long id, Jwt jwt, Course course) {
        Course existing = getCourseById(id);
        if (!RoleUtil.isAdmin(jwt) && !existing.getInstructorId().equals(jwt.getSubject())) {
            throw new AccessDeniedException("Only course instructor can update course");
        }

        existing.setTitle(course.getTitle());
        existing.setDescription(course.getDescription());
        existing.setImageUrl(course.getImageUrl());
        existing.setStatus(course.getStatus());
        Course updated = courseRepository.save(existing);
        
        cacheService.delete("courses:published");
        
        return updated;
    }

    @Transactional
    public void deleteCourse(Long id, Jwt jwt) {
        Course course = this.getCourseById(id);
        if (!RoleUtil.isAdmin(jwt) && !course.getInstructorId().equals(jwt.getSubject())) {
            throw new AccessDeniedException("Only course instructor or admin can delete course");
        }
        courseRepository.deleteById(id);
        
        cacheService.delete("courses:published");
    }

    @Transactional
    public Lesson createLesson(Lesson lesson, Long courseId, Jwt jwt) {
        if (!RoleUtil.isTeacher(jwt) && !RoleUtil.isAdmin(jwt)) {
            throw new AccessDeniedException("Only TEACHER and ADMIN can create lessons");
        }
        Course course = this.getCourseById(courseId);
        if (!RoleUtil.isAdmin(jwt) && !course.getInstructorId().equals(jwt.getSubject())) {
            throw new AccessDeniedException("Only course instructor can add lessons");
        }
        lesson.setCourse(course);
        log.info("Creating lesson: {} for course: {}", lesson.getTitle(), lesson.getCourse().getId());
        Lesson savedLesson = lessonRepository.save(lesson);
        
        if (course != null && course.getEnrolledStudents() != null && !course.getEnrolledStudents().isEmpty()) {
            String notificationMessage = String.format(
                "Новый урок добавлен в курс \"%s\": %s",
                course.getTitle(),
                savedLesson.getTitle()
            );
            
            for (String studentId : course.getEnrolledStudents()) {
                sendNotificationToStudent(studentId, notificationMessage, course.getId(), savedLesson.getId());
            }
            
            log.info("Sent notifications to {} students about new lesson in course {}", 
                    course.getEnrolledStudents().size(), course.getId());
        }
        
        return savedLesson;
    }
    
    private void sendNotificationToStudent(String userId, String message, Long courseId, Long lessonId) {
        try {
            Map<String, Object> notification = Map.of(
                "userId", userId,
                "message", message,
                "type", "NEW_LESSON",
                "courseId", courseId != null ? courseId.toString() : "",
                "lessonId", lessonId != null ? lessonId.toString() : "",
                "timestamp", LocalDateTime.now().toString()
            );
            rabbitTemplate.convertAndSend("notification.queue", notification);
            log.debug("Notification sent to student {} about new lesson", userId);
        } catch (Exception e) {
            log.error("Error sending notification to student {}: {}", userId, e.getMessage());
        }
    }

    public List<Lesson> getLessonsByCourse(Long courseId) {
        return lessonRepository.findByCourseIdOrderByOrderNumber(courseId);
    }

    public Lesson getLessonById(Long lessonId) {
        return lessonRepository.findById(lessonId)
                .orElseThrow(() -> new RuntimeException("Lesson not found with id: " + lessonId));
    }

    @Transactional
    public Lesson updateLesson(Long lessonId, Lesson lesson, Jwt jwt) {
        Lesson existing = getLessonById(lessonId);
        Course course = existing.getCourse();
        if (!RoleUtil.isAdmin(jwt) && !course.getInstructorId().equals(jwt.getSubject())) {
            throw new AccessDeniedException("Only course instructor or admin can update lesson");
        }
        existing.setTitle(lesson.getTitle());
        existing.setDescription(lesson.getDescription());
        existing.setContent(lesson.getContent());
        existing.setOrderNumber(lesson.getOrderNumber());
        existing.setUpdatedAt(LocalDateTime.now());
        return lessonRepository.save(existing);
    }

    @Transactional
    public Video createVideo(VideoMetadataRequest request, Long lessonId, Jwt jwt) {
        if (!RoleUtil.canUpload(jwt)) {
            throw new AccessDeniedException("Only TEACHER and ADMIN can create videos");
        }
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new RuntimeException("Lesson not found"));

        Video video = new Video();
        video.setTitle(request.getTitle());
        video.setDescription(request.getDescription());
        video.setVideoUrl(request.getVideoUrl());
        video.setObjectName(request.getObjectName());
        video.setFileSize(request.getFileSize());
        video.setDuration(request.getDuration() != null ? request.getDuration() : 0);
        video.setLesson(lesson);
        video.setOrderNumber(request.getOrderNumber() != null ? request.getOrderNumber() : 0);
        video.setStatus(Video.VideoStatus.READY);

        if (video.getOrderNumber() == null || video.getOrderNumber() == 0) {
            List<Video> existingVideos = videoRepository.findByLessonIdOrderByOrderNumber(video.getLesson().getId());
            int nextOrder = existingVideos.isEmpty() ? 1 : existingVideos.get(existingVideos.size() - 1).getOrderNumber() + 1;
            video.setOrderNumber(nextOrder);
        }
        return videoRepository.save(video);
    }

    public List<Video> getVideosByLesson(Long lessonId) {
        return videoRepository.findByLessonIdOrderByOrderNumber(lessonId);
    }

    @Transactional
    public void enrollStudent(Long courseId, String studentId) {
        Course course = getCourseById(courseId);
        if (!course.getEnrolledStudents().contains(studentId)) {
            course.getEnrolledStudents().add(studentId);
            courseRepository.save(course);
            log.info("Student {} enrolled in course {}", studentId, courseId);
        }
    }
}

