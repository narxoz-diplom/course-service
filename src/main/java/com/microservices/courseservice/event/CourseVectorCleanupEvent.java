package com.microservices.courseservice.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class CourseVectorCleanupEvent {
    private final Long courseId;
}
