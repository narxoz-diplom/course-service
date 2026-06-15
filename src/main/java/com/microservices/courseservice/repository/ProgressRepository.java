package com.microservices.courseservice.repository;

import com.microservices.courseservice.model.Progress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProgressRepository extends JpaRepository<Progress, Long> {
    List<Progress> findByStudentId(String studentId);
    Optional<Progress> findByStudentIdAndLessonId(String studentId, Long lessonId);
    List<Progress> findByStudentIdAndLessonIdIn(String studentId, Collection<Long> lessonIds);
    Long countByStudentIdAndCompletedTrue(String studentId);
    long countByStudentIdAndLessonIdInAndCompletedTrue(String studentId, Collection<Long> lessonIds);
}

