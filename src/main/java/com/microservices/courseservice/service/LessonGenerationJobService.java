package com.microservices.courseservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.courseservice.dto.GenerateFromOutlineRequest;
import com.microservices.courseservice.dto.LessonGenerationJobDto;
import com.microservices.courseservice.model.Course;
import com.microservices.courseservice.model.LessonGenerationJob;
import com.microservices.courseservice.repository.LessonGenerationJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LessonGenerationJobService {

    private final LessonGenerationJobRepository jobRepository;
    private final LessonGenerationJobAsyncRunner asyncRunner;
    private final CourseService courseService;
    private final ObjectMapper objectMapper;

    @Transactional
    public LessonGenerationJobDto startFromOutlineJob(
            Long courseId, GenerateFromOutlineRequest request, Jwt jwt, String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Authorization header is required for background lesson generation (file-service calls)");
        }
        courseService.assertCanMutateCourseContent(courseId, jwt);
        Course course = courseService.getCourseById(courseId);
        String id = UUID.randomUUID().toString();
        LessonGenerationJob job = new LessonGenerationJob();
        job.setId(id);
        job.setCourseId(courseId);
        job.setInstructorId(jwt.getSubject());
        job.setStatus(LessonGenerationJob.Status.PENDING);
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        jobRepository.save(job);
        GenerateFromOutlineRequest copy = cloneRequest(request);
        String instructorId = course.getInstructorId();
        Runnable dispatch = () -> asyncRunner.runFromOutlineJob(id, courseId, copy, instructorId, authorizationHeader);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            dispatch.run();
        }
        return LessonGenerationJobDto.builder()
                .jobId(id)
                .status(job.getStatus().name())
                .build();
    }

    private GenerateFromOutlineRequest cloneRequest(GenerateFromOutlineRequest src) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsBytes(src), GenerateFromOutlineRequest.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to copy generation request", e);
        }
    }

    public LessonGenerationJobDto getJob(String jobId, Long courseId, Jwt jwt) {
        LessonGenerationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found"));
        if (!job.getCourseId().equals(courseId)) {
            throw new org.springframework.security.access.AccessDeniedException("Job not accessible");
        }
        courseService.assertCanMutateCourseContent(courseId, jwt);
        return LessonGenerationJobDto.builder()
                .jobId(job.getId())
                .status(job.getStatus().name())
                .createdLessonIds(job.getCreatedLessonIds())
                .errorMessage(job.getErrorMessage())
                .build();
    }
}
