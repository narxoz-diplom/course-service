package com.microservices.courseservice.controller;

import com.microservices.courseservice.model.Course;
import com.microservices.courseservice.model.Lesson;
import com.microservices.courseservice.model.Video;
import com.microservices.courseservice.repository.LessonRepository;
import com.microservices.courseservice.service.CacheService;
import com.microservices.courseservice.service.CourseService;
import com.microservices.courseservice.service.MinioService;
import com.microservices.courseservice.util.RoleUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

@RestController
@RequestMapping("/api/courses")
@RequiredArgsConstructor
@Slf4j
public class CourseController {

    private final CourseService courseService;
    private final MinioService minioService;
    private final LessonRepository lessonRepository;
    private final CacheService cacheService;

    @PostMapping
    public ResponseEntity<Course> createCourse(
            @RequestBody Course course,
            @AuthenticationPrincipal Jwt jwt) {
        if (!RoleUtil.isTeacher(jwt) && !RoleUtil.isAdmin(jwt)) {
            throw new AccessDeniedException("Only TEACHER and ADMIN can create courses");
        }
        course.setInstructorId(jwt.getSubject());
        Course created = courseService.createCourse(course);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<List<Course>> getAllCourses(@AuthenticationPrincipal Jwt jwt) {
        if (RoleUtil.isAdmin(jwt) || RoleUtil.isTeacher(jwt)) {
            // Админы и учителя видят все курсы
            List<Course> courses = courseService.getCoursesByInstructor(jwt.getSubject());
            return ResponseEntity.ok(courses);
        } else {
            // Студенты видят только опубликованные курсы
            List<Course> courses = courseService.getAllPublishedCourses();
            return ResponseEntity.ok(courses);
        }
    }

    @GetMapping("/enrolled")
    public ResponseEntity<List<Course>> getEnrolledCourses(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        List<Course> courses = courseService.getEnrolledCourses(userId);
        return ResponseEntity.ok(courses);
    }

    @GetMapping("/published")
    public ResponseEntity<List<Course>> getPublishedCourses() {
        return ResponseEntity.ok(courseService.getAllPublishedCourses());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Course> getCourse(@PathVariable Long id) {
        try {
            Course course = courseService.getCourseById(id);
            
            // Увеличиваем счетчик просмотров курса в Redis
            String viewKey = "course:views:" + id;
            cacheService.increment(viewKey, 24, java.util.concurrent.TimeUnit.HOURS);
            
            return ResponseEntity.ok(course);
        } catch (RuntimeException e) {
            log.error("Error getting course with id: {}", id, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            log.error("Unexpected error getting course with id: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Course> updateCourse(
            @PathVariable Long id,
            @RequestBody Course course,
            @AuthenticationPrincipal Jwt jwt) {
        Course existing = courseService.getCourseById(id);
        if (!RoleUtil.isAdmin(jwt) && !existing.getInstructorId().equals(jwt.getSubject())) {
            throw new AccessDeniedException("Only course instructor can update course");
        }
        Course updated = courseService.updateCourse(id, course);
        return ResponseEntity.ok(updated);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Course> updateCourseStatus(
            @PathVariable Long id,
            @RequestBody StatusUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        log.info("=== PATCH /api/courses/{}/status called ===", id);
        log.info("DEBUG: Request body status: {}", request.getStatus());
        
        if (jwt == null) {
            log.error("JWT token is null!");
            throw new AccessDeniedException("Authentication required");
        }
        
        Course existing = courseService.getCourseById(id);
        String userId = jwt.getSubject();
        
        // Детальное логирование для отладки
        log.info("DEBUG: Attempting to change course status. Course ID: {}, Course Title: {}, " +
                "Course Instructor ID: {}, Current User ID: {}", 
                id, existing.getTitle(), existing.getInstructorId(), userId);
        
        // Проверяем права: либо админ, либо учитель, либо инструктор курса
        boolean isAdmin = RoleUtil.isAdmin(jwt);
        boolean isTeacher = RoleUtil.isTeacher(jwt);
        boolean isInstructor = existing.getInstructorId() != null && existing.getInstructorId().equals(userId);
        
        // Логируем все проверки
        List<String> roles = RoleUtil.getRoles(jwt);
        log.info("DEBUG: User roles from token: {}", roles);
        log.info("DEBUG: Permission check - Admin: {}, Teacher: {}, Instructor: {}, " +
                "Course Instructor ID: '{}', Current User ID: '{}', Match: {}", 
                isAdmin, isTeacher, isInstructor, existing.getInstructorId(), userId, isInstructor);
        
        if (!isAdmin && !isTeacher && !isInstructor) {
            log.warn("ACCESS DENIED: User {} attempted to change status of course {} (id: {}). " +
                    "Admin: {}, Teacher: {}, Instructor: {}, " +
                    "Course Instructor ID: '{}', Current User ID: '{}'", 
                    userId, existing.getTitle(), id, isAdmin, isTeacher, isInstructor,
                    existing.getInstructorId(), userId);
            throw new AccessDeniedException(
                    String.format("Only course instructor, teacher, or admin can change course status. " +
                            "Your User ID: '%s', Course Instructor ID: '%s', " +
                            "Is Admin: %s, Is Teacher: %s, Is Instructor: %s",
                            userId, existing.getInstructorId(), isAdmin, isTeacher, isInstructor));
        }
        
        try {
            Course.CourseStatus newStatus = Course.CourseStatus.valueOf(request.getStatus().toUpperCase());
            existing.setStatus(newStatus);
            Course updated = courseService.updateCourse(id, existing);
            log.info("SUCCESS: Course {} (id: {}) status changed to {} by user {} (admin: {}, teacher: {}, instructor: {})", 
                    existing.getTitle(), id, newStatus, userId, isAdmin, isTeacher, isInstructor);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            log.error("Invalid status value: {}", request.getStatus());
            return ResponseEntity.badRequest().build();
        }
    }

    @lombok.Data
    static class StatusUpdateRequest {
        private String status;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCourse(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        Course course = courseService.getCourseById(id);
        if (!RoleUtil.isAdmin(jwt) && !course.getInstructorId().equals(jwt.getSubject())) {
            throw new AccessDeniedException("Only course instructor or admin can delete course");
        }
        courseService.deleteCourse(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{courseId}/lessons")
    public ResponseEntity<Lesson> createLesson(
            @PathVariable Long courseId,
            @RequestBody Lesson lesson,
            @AuthenticationPrincipal Jwt jwt) {
        if (!RoleUtil.isTeacher(jwt) && !RoleUtil.isAdmin(jwt)) {
            throw new AccessDeniedException("Only TEACHER and ADMIN can create lessons");
        }
        Course course = courseService.getCourseById(courseId);
        if (!RoleUtil.isAdmin(jwt) && !course.getInstructorId().equals(jwt.getSubject())) {
            throw new AccessDeniedException("Only course instructor can add lessons");
        }
        lesson.setCourse(course);
        Lesson created = courseService.createLesson(lesson);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{courseId}/lessons")
    public ResponseEntity<List<Lesson>> getLessons(@PathVariable Long courseId) {
        List<Lesson> lessons = courseService.getLessonsByCourse(courseId);
        return ResponseEntity.ok(lessons);
    }

    @GetMapping("/lessons/{lessonId}")
    public ResponseEntity<Lesson> getLesson(@PathVariable Long lessonId) {
        try {
            Lesson lesson = courseService.getLessonById(lessonId);
            return ResponseEntity.ok(lesson);
        } catch (RuntimeException e) {
            log.error("Error getting lesson with id: {}", lessonId, e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @PutMapping("/lessons/{lessonId}")
    public ResponseEntity<Lesson> updateLesson(
            @PathVariable Long lessonId,
            @RequestBody Lesson lesson,
            @AuthenticationPrincipal Jwt jwt) {
        Lesson existing = courseService.getLessonById(lessonId);
        Course course = existing.getCourse();
        if (!RoleUtil.isAdmin(jwt) && !course.getInstructorId().equals(jwt.getSubject())) {
            throw new AccessDeniedException("Only course instructor or admin can update lesson");
        }
        Lesson updated = courseService.updateLesson(lessonId, lesson);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/lessons/{lessonId}/videos")
    public ResponseEntity<Video> uploadVideo(
            @PathVariable Long lessonId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "orderNumber", required = false) Integer orderNumber,
            @AuthenticationPrincipal Jwt jwt) {
        if (!RoleUtil.canUpload(jwt)) {
            throw new AccessDeniedException("Only TEACHER and ADMIN can upload videos");
        }
        
        try {
            String objectName = minioService.uploadFile(file);
            String videoUrl = "/api/courses/videos/" + objectName + "/stream";
            
            Lesson lesson = lessonRepository.findById(lessonId)
                    .orElseThrow(() -> new RuntimeException("Lesson not found"));
            
            Video video = new Video();
            video.setTitle(title);
            video.setDescription(description);
            video.setVideoUrl(videoUrl);
            video.setObjectName(objectName);
            video.setFileSize(file.getSize());
            video.setDuration(0); // TODO: Calculate duration
            video.setLesson(lesson);
            video.setOrderNumber(orderNumber != null ? orderNumber : 0);
            video.setStatus(Video.VideoStatus.READY);
            
            Video created = courseService.createVideo(video);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (Exception e) {
            log.error("Error uploading video", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/lessons/{lessonId}/videos")
    public ResponseEntity<List<Video>> getVideos(@PathVariable Long lessonId) {
        List<Video> videos = courseService.getVideosByLesson(lessonId);
        return ResponseEntity.ok(videos);
    }

    @GetMapping("/videos/{objectName}/stream")
    public ResponseEntity<InputStreamResource> streamVideo(
            @PathVariable String objectName,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {
        try {
            // Spring автоматически декодирует path variables
            // Но если было двойное кодирование, пробуем декодировать еще раз
            String decodedObjectName = objectName;
            try {
                // Пробуем декодировать, если не получится - используем исходное значение
                String testDecode = java.net.URLDecoder.decode(objectName, java.nio.charset.StandardCharsets.UTF_8);
                // Если декодирование изменило строку и она не содержит % - значит было кодирование
                if (!testDecode.equals(objectName) && !testDecode.contains("%")) {
                    decodedObjectName = testDecode;
                }
            } catch (Exception e) {
                // Если ошибка декодирования - используем исходное значение
                log.debug("Could not decode objectName, using original: {}", objectName);
            }
            
            // Получаем информацию о файле
            io.minio.StatObjectResponse statObject = minioService.getFileInfo(decodedObjectName);
            long fileSize = statObject.size();
            
            // Определяем Content-Type из метаданных или по расширению
            String contentType = statObject.contentType();
            if (contentType == null || contentType.isEmpty()) {
                // Пытаемся определить по имени файла
                String fileName = objectName.toLowerCase();
                if (fileName.endsWith(".mp4")) {
                    contentType = "video/mp4";
                } else if (fileName.endsWith(".webm")) {
                    contentType = "video/webm";
                } else if (fileName.endsWith(".ogg")) {
                    contentType = "video/ogg";
                } else {
                    contentType = "video/mp4"; // По умолчанию
                }
            }
            
            // Обработка Range requests для поддержки стриминга
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] ranges = rangeHeader.substring(6).split("-");
                long rangeStart = Long.parseLong(ranges[0]);
                long rangeEnd = ranges.length > 1 && !ranges[1].isEmpty() 
                    ? Long.parseLong(ranges[1]) 
                    : fileSize - 1;
                
                // Проверяем валидность диапазона
                if (rangeStart < 0 || rangeEnd >= fileSize || rangeStart > rangeEnd) {
                    return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                            .header("Content-Range", "bytes */" + fileSize)
                            .build();
                }
                
                long contentLength = rangeEnd - rangeStart + 1;
                InputStream inputStream = minioService.downloadFile(decodedObjectName, rangeStart, contentLength);
                
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                        .header("Content-Type", contentType)
                        .header("Accept-Ranges", "bytes")
                        .header("Content-Length", String.valueOf(contentLength))
                        .header("Content-Range", 
                            String.format("bytes %d-%d/%d", rangeStart, rangeEnd, fileSize))
                        .body(new org.springframework.core.io.InputStreamResource(inputStream));
            } else {
                // Полный файл
                InputStream inputStream = minioService.downloadFile(decodedObjectName);
                return ResponseEntity.ok()
                        .header("Content-Type", contentType)
                        .header("Accept-Ranges", "bytes")
                        .header("Content-Length", String.valueOf(fileSize))
                        .body(new org.springframework.core.io.InputStreamResource(inputStream));
            }
        } catch (Exception e) {
            log.error("Error streaming video: {}", objectName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/{courseId}/enroll")
    public ResponseEntity<Void> enrollInCourse(
            @PathVariable Long courseId,
            @AuthenticationPrincipal Jwt jwt) {
        courseService.enrollStudent(courseId, jwt.getSubject());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/views")
    public ResponseEntity<Long> getCourseViews(@PathVariable Long id) {
        String viewKey = "course:views:" + id;
        Long views = cacheService.getCounter(viewKey);
        return ResponseEntity.ok(views);
    }

    @PostMapping("/lessons/{lessonId}/files")
    public ResponseEntity<Object> uploadFileToLesson(
            @PathVariable Long lessonId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) {
        if (!RoleUtil.canUpload(jwt)) {
            throw new AccessDeniedException("Only TEACHER and ADMIN can upload files to lessons");
        }
        try {
            // Проверяем, что урок существует
            Lesson lesson = courseService.getLessonById(lessonId);
            
            // Используем file-service для загрузки файла
            // В реальной реализации здесь должен быть вызов file-service через Feign или RestTemplate
            // Пока возвращаем заглушку
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body("File upload to lesson should be done through file-service API");
        } catch (Exception e) {
            log.error("Error uploading file to lesson: {}", lessonId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/lessons/{lessonId}/files")
    public ResponseEntity<Object> getLessonFiles(@PathVariable Long lessonId) {
        try {
            // Проверяем, что урок существует
            Lesson lesson = courseService.getLessonById(lessonId);
            
            // Используем file-service для получения файлов
            // В реальной реализации здесь должен быть вызов file-service через Feign или RestTemplate
            // Пока возвращаем заглушку
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body("File retrieval should be done through file-service API");
        } catch (Exception e) {
            log.error("Error getting files for lesson: {}", lessonId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}

