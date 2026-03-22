package com.microservices.courseservice.repository;

import com.microservices.courseservice.model.LessonGenerationJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LessonGenerationJobRepository extends JpaRepository<LessonGenerationJob, String> {
    List<LessonGenerationJob> findByCourseIdAndInstructorIdOrderByCreatedAtDesc(Long courseId, String instructorId);
}
