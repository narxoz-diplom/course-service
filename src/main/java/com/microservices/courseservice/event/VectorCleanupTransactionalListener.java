package com.microservices.courseservice.event;

import com.microservices.courseservice.service.VectorCleanupEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class VectorCleanupTransactionalListener {

    private final VectorCleanupEventPublisher vectorCleanupEventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCourseDeleted(CourseVectorCleanupEvent event) {
        vectorCleanupEventPublisher.publishCourseDeleted(event.getCourseId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLessonDeleted(LessonVectorCleanupEvent event) {
        vectorCleanupEventPublisher.publishLessonDeleted(event.getCourseId(), event.getLessonId());
    }
}
