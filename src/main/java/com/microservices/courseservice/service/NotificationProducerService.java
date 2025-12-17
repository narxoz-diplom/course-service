package com.microservices.courseservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationProducerService {

    private static final String NOTIFICATION_QUEUE = "notification.queue";

    private final RabbitTemplate rabbitTemplate;

    public void sendNewLessonNotification(String userId, String message, Long courseId, Long lessonId) {
        try {
            Map<String, Object> notification = Map.of(
                "userId", userId,
                "message", message,
                "type", "NEW_LESSON",
                "courseId", courseId != null ? courseId.toString() : "",
                "lessonId", lessonId != null ? lessonId.toString() : "",
                "timestamp", LocalDateTime.now().toString()
            );
            rabbitTemplate.convertAndSend(NOTIFICATION_QUEUE, notification);
            log.debug("Notification sent to student {} about new lesson", userId);
        } catch (Exception e) {
            log.error("Error sending notification to student {}: {}", userId, e.getMessage(), e);
        }
    }

    public void sendNewLessonNotificationsToAllStudents(
            java.util.List<String> studentIds,
            String courseTitle,
            String lessonTitle,
            Long courseId,
            Long lessonId) {
        if (studentIds == null || studentIds.isEmpty()) {
            log.debug("No students to notify for course {}", courseId);
            return;
        }

        String notificationMessage = String.format(
            "Новый урок добавлен в курс \"%s\": %s",
            courseTitle,
            lessonTitle
        );

        for (String studentId : studentIds) {
            sendNewLessonNotification(studentId, notificationMessage, courseId, lessonId);
        }

        log.info("Sent notifications to {} students about new lesson in course {}",
                studentIds.size(), courseId);
    }
}

