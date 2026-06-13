package com.microservices.courseservice.repository;

import com.microservices.courseservice.model.LessonGrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface LessonGradeRepository extends JpaRepository<LessonGrade, Long> {

    List<LessonGrade> findByLessonId(Long lessonId);

    Optional<LessonGrade> findByLessonIdAndStudentId(Long lessonId, String studentId);

    long countByLessonIdAndGradeIsNotNull(Long lessonId);

    void deleteByLessonIdAndStudentId(Long lessonId, String studentId);

    @Query("SELECT g FROM LessonGrade g JOIN FETCH g.lesson l JOIN FETCH l.course WHERE g.studentId = :studentId")
    List<LessonGrade> findByStudentIdWithDetails(@Param("studentId") String studentId);

    @Query("SELECT g FROM LessonGrade g JOIN FETCH g.lesson l JOIN FETCH l.course "
            + "WHERE g.courseId = :courseId AND g.studentId = :studentId")
    List<LessonGrade> findByCourseIdAndStudentIdWithDetails(
            @Param("courseId") Long courseId,
            @Param("studentId") String studentId);

    @Query("SELECT g FROM LessonGrade g JOIN FETCH g.lesson WHERE g.lesson.id IN :lessonIds")
    List<LessonGrade> findByLessonIdInWithLesson(@Param("lessonIds") Collection<Long> lessonIds);
}
