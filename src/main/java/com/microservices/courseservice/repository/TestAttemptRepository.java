package com.microservices.courseservice.repository;

import com.microservices.courseservice.model.TestAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestAttemptRepository extends JpaRepository<TestAttempt, Long> {
    List<TestAttempt> findByTestIdOrderByCompletedAtDesc(Long testId);
    List<TestAttempt> findByStudentIdOrderByCompletedAtDesc(String studentId);
    List<TestAttempt> findByTest_Course_IdOrderByCompletedAtDesc(Long courseId);
}
