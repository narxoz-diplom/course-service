package com.microservices.courseservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.courseservice.model.Course;
import com.microservices.courseservice.model.Lesson;
import com.microservices.courseservice.model.Video;
import com.microservices.courseservice.repository.CourseRepository;
import com.microservices.courseservice.repository.LessonRepository;
import com.microservices.courseservice.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class CourseService {

    private final CourseRepository courseRepository;
    private final LessonRepository lessonRepository;
    private final VideoRepository videoRepository;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    
    // Конструктор для инжекции зависимостей
    public CourseService(CourseRepository courseRepository, 
                        LessonRepository lessonRepository,
                        VideoRepository videoRepository,
                        CacheService cacheService,
                        ObjectMapper objectMapper,
                        RabbitTemplate rabbitTemplate) {
        this.courseRepository = courseRepository;
        this.lessonRepository = lessonRepository;
        this.videoRepository = videoRepository;
        this.cacheService = cacheService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.rabbitTemplate = rabbitTemplate;
    }

    @Transactional
    public Course createCourse(Course course) {
        log.info("Creating course: {} by instructor: {}", course.getTitle(), course.getInstructorId());
        Course created = courseRepository.save(course);
        
        // Инвалидируем кеш опубликованных курсов
        cacheService.delete("courses:published");
        
        return created;
    }

    // Не используем кеширование, так как Course содержит lazy-связанные сущности (lessons),
    // которые не могут быть сериализованы в Redis
    public Course getCourseById(Long id) {
        Course course = courseRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Course not found with id: " + id));
        // Инициализируем lazy коллекции для безопасного доступа
        if (course.getLessons() != null) {
            course.getLessons().size(); // Force initialization
        }
        return course;
    }

    /**
     * Получить все опубликованные курсы с кешированием в Redis
     */
    public List<Course> getAllPublishedCourses() {
        String cacheKey = "courses:published";
        
        // Пытаемся получить из кеша
        String cached = cacheService.get(cacheKey);
        if (cached != null) {
            try {
                log.debug("Retrieved published courses from cache");
                return objectMapper.readValue(cached, new TypeReference<List<Course>>() {});
            } catch (JsonProcessingException e) {
                log.warn("Error deserializing cached courses", e);
            }
        }
        
        // Если нет в кеше, получаем из БД
        List<Course> courses = courseRepository.findByStatus(Course.CourseStatus.PUBLISHED);
        
        // Сохраняем в кеш на 5 минут
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
    public Course updateCourse(Long id, Course course) {
        Course existing = getCourseById(id);
        existing.setTitle(course.getTitle());
        existing.setDescription(course.getDescription());
        existing.setImageUrl(course.getImageUrl());
        existing.setStatus(course.getStatus());
        Course updated = courseRepository.save(existing);
        
        // Инвалидируем кеш опубликованных курсов
        cacheService.delete("courses:published");
        
        return updated;
    }

    @Transactional
    public void deleteCourse(Long id) {
        courseRepository.deleteById(id);
        
        // Инвалидируем кеш опубликованных курсов
        cacheService.delete("courses:published");
    }

    @Transactional
    public Lesson createLesson(Lesson lesson) {
        log.info("Creating lesson: {} for course: {}", lesson.getTitle(), lesson.getCourse().getId());
        Lesson savedLesson = lessonRepository.save(lesson);
        
        // Отправляем уведомления всем записанным студентам
        Course course = lesson.getCourse();
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
    public Lesson updateLesson(Long lessonId, Lesson lesson) {
        Lesson existing = getLessonById(lessonId);
        existing.setTitle(lesson.getTitle());
        existing.setDescription(lesson.getDescription());
        existing.setContent(lesson.getContent());
        existing.setOrderNumber(lesson.getOrderNumber());
        existing.setUpdatedAt(LocalDateTime.now());
        return lessonRepository.save(existing);
    }

    @Transactional
    public Video createVideo(Video video) {
        log.info("Creating video: {} for lesson: {}", video.getTitle(), video.getLesson().getId());
        // Если orderNumber не установлен, устанавливаем следующий номер
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

