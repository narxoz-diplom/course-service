package com.microservices.courseservice.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class LessonVectorCleanupEvent {
    private final Long courseId;
    private final Long lessonId;
}
