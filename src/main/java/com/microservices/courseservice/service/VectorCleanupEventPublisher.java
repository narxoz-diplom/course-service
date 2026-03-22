package com.microservices.courseservice.service;

import com.microservices.courseservice.dto.VectorCleanupMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorCleanupEventPublisher {

    public static final String RAG_EVENTS_EXCHANGE = "rag.events";
    public static final String ROUTING_KEY_COURSE_DELETED = "vector.delete.course";
    public static final String ROUTING_KEY_LESSON_DELETED = "vector.delete.lesson";

    private final RabbitTemplate rabbitTemplate;

    public void publishCourseDeleted(Long courseId) {
        if (courseId == null) {
            return;
        }
        VectorCleanupMessage msg = VectorCleanupMessage.builder()
                .eventType(VectorCleanupMessage.EVENT_COURSE_DELETED)
                .courseId(courseId.toString())
                .collectionName(courseCollectionName(courseId))
                .build();
        send(ROUTING_KEY_COURSE_DELETED, msg);
    }

    public void publishLessonDeleted(Long courseId, Long lessonId) {
        if (lessonId == null) {
            return;
        }
        VectorCleanupMessage msg = VectorCleanupMessage.builder()
                .eventType(VectorCleanupMessage.EVENT_LESSON_DELETED)
                .courseId(courseId != null ? courseId.toString() : null)
                .lessonId(lessonId.toString())
                .collectionName(courseId != null ? courseCollectionName(courseId) : null)
                .build();
        send(ROUTING_KEY_LESSON_DELETED, msg);
    }

    private static String courseCollectionName(Long courseId) {
        return "course_" + courseId;
    }

    private void send(String routingKey, VectorCleanupMessage msg) {
        try {
            rabbitTemplate.convertAndSend(RAG_EVENTS_EXCHANGE, routingKey, msg);
            log.debug("Published vector cleanup: {} {}", routingKey, msg);
        } catch (Exception e) {
            log.error("Failed to publish vector cleanup event {}: {}", routingKey, e.getMessage(), e);
        }
    }
}
