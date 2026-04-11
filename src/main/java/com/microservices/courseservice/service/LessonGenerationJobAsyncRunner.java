package com.microservices.courseservice.service;

import com.microservices.courseservice.config.FeignAuthContext;
import com.microservices.courseservice.dto.GenerateFromOutlineRequest;
import com.microservices.courseservice.model.LessonGenerationJob;
import com.microservices.courseservice.repository.LessonGenerationJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

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
            courseService.generateLessonsFromOutlineForInstructor(courseId, request, instructorId, jobId);
        } catch (Exception e) {
            log.error("Lesson generation job {} failed: {}", jobId, e.getMessage());
            LessonGenerationJob failed = jobRepository.findById(jobId).orElse(job);
            failed.setStatus(LessonGenerationJob.Status.FAILED);
            failed.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            jobRepository.save(failed);
        } finally {
            FeignAuthContext.clear();
        }
    }
}
