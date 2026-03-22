package com.microservices.courseservice.service;

import com.microservices.courseservice.config.FeignAuthContext;
import com.microservices.courseservice.dto.GenerateFromOutlineRequest;
import com.microservices.courseservice.model.Lesson;
import com.microservices.courseservice.model.LessonGenerationJob;
import com.microservices.courseservice.repository.LessonGenerationJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class LessonGenerationJobAsyncRunner {

    private final LessonGenerationJobRepository jobRepository;
    private final CourseService courseService;

    @Async("lessonGenerationExecutor")
    public void runFromOutlineJob(
            String jobId,
            Long courseId,
            GenerateFromOutlineRequest request,
            String instructorId,
            String authorizationHeader) {
        LessonGenerationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Lesson generation job {} not found when async started (possible pre-commit race)", jobId);
            return;
        }
        job.setStatus(LessonGenerationJob.Status.RUNNING);
        jobRepository.save(job);
        try {
            FeignAuthContext.setAuthorization(authorizationHeader);
            List<Lesson> lessons = courseService.generateLessonsFromOutlineForInstructor(courseId, request, instructorId);
            String ids = lessons.stream().map(l -> String.valueOf(l.getId())).collect(Collectors.joining(","));
            job.setCreatedLessonIds(ids);
            job.setStatus(LessonGenerationJob.Status.COMPLETED);
            job.setErrorMessage(null);
        } catch (Exception e) {
            log.error("Lesson generation job {} failed: {}", jobId, e.getMessage());
            job.setStatus(LessonGenerationJob.Status.FAILED);
            job.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            FeignAuthContext.clear();
        }
        jobRepository.save(job);
    }
}
