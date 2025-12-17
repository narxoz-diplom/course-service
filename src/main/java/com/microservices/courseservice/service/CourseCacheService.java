package com.microservices.courseservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.courseservice.model.Course;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class CourseCacheService {

    private static final String PUBLISHED_COURSES_KEY = "courses:published";
    private static final String COURSE_KEY_PREFIX = "course:";
    private static final long CACHE_TTL_MINUTES = 5;

    private final CacheService cacheService;
    private final ObjectMapper objectMapper;

    public List<Course> getPublishedCoursesFromCache() {
        String cached = cacheService.get(PUBLISHED_COURSES_KEY);
        if (cached != null) {
            try {
                log.debug("Retrieved published courses from cache");
                return objectMapper.readValue(cached, new TypeReference<List<Course>>() {});
            } catch (JsonProcessingException e) {
                log.warn("Error deserializing cached courses", e);
            }
        }
        return null;
    }

    public void cachePublishedCourses(List<Course> courses) {
        try {
            String json = objectMapper.writeValueAsString(courses);
            cacheService.set(PUBLISHED_COURSES_KEY, json, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            log.debug("Cached published courses");
        } catch (JsonProcessingException e) {
            log.warn("Error serializing courses for cache", e);
        }
    }

    public void invalidatePublishedCoursesCache() {
        cacheService.delete(PUBLISHED_COURSES_KEY);
        log.debug("Invalidated published courses cache");
    }

    public void invalidateCourseCache(Long courseId) {
        String courseKey = COURSE_KEY_PREFIX + courseId;
        cacheService.delete(courseKey);
        log.debug("Invalidated cache for course: {}", courseId);
    }

    public void invalidateCacheOnStatusChange(Course.CourseStatus oldStatus, Course.CourseStatus newStatus) {
        boolean wasPublished = oldStatus == Course.CourseStatus.PUBLISHED;
        boolean isPublished = newStatus == Course.CourseStatus.PUBLISHED;
        
        if (wasPublished != isPublished) {
            invalidatePublishedCoursesCache();
        }
    }

    public void invalidateCacheOnDelete(Course course) {
        if (course.getStatus() == Course.CourseStatus.PUBLISHED) {
            invalidatePublishedCoursesCache();
        }
        invalidateCourseCache(course.getId());
    }
}

