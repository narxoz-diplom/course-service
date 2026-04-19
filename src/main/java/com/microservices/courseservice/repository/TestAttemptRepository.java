package com.microservices.courseservice.repository;

import com.microservices.courseservice.model.TestAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestAttemptRepository extends JpaRepository<TestAttempt, Long> {
    List<TestAttempt> findByTestIdOrderByCompletedAtDesc(Long testId);
    List<TestAttempt> findByStudentIdOrderByCompletedAtDesc(String studentId);
    List<TestAttempt> findByTest_Course_IdOrderByCompletedAtDesc(Long courseId);

    @Query("SELECT ta FROM TestAttempt ta JOIN FETCH ta.test t WHERE t.course.id = :courseId ORDER BY ta.completedAt DESC")
    List<TestAttempt> findByCourseIdWithTest(@Param("courseId") Long courseId);

    /** Remove attempts for all tests of the course (FK test_attempts → tests). */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM TestAttempt ta WHERE ta.test.course.id = :courseId")
    void deleteByCourseId(@Param("courseId") Long courseId);
}
