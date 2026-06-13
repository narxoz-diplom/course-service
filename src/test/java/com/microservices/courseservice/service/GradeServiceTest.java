package com.microservices.courseservice.service;

import com.microservices.courseservice.client.AuthServiceClient;
import com.microservices.courseservice.dto.SaveGradeEntryDto;
import com.microservices.courseservice.dto.SaveGradesRequestDto;
import com.microservices.courseservice.model.Course;
import com.microservices.courseservice.model.Lesson;
import com.microservices.courseservice.model.LessonGrade;
import com.microservices.courseservice.repository.CourseRepository;
import com.microservices.courseservice.repository.LessonGradeRepository;
import com.microservices.courseservice.repository.LessonRepository;
import com.microservices.courseservice.repository.ProgressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GradeServiceTest {

    @Mock private CourseRepository courseRepository;
    @Mock private LessonRepository lessonRepository;
    @Mock private LessonGradeRepository lessonGradeRepository;
    @Mock private ProgressRepository progressRepository;
    @Mock private AuthServiceClient authServiceClient;
    @Mock private NotificationProducerService notificationProducerService;

    private GradeService gradeService;

    @BeforeEach
    void setUp() {
        gradeService = new GradeService(
                courseRepository,
                lessonRepository,
                lessonGradeRepository,
                progressRepository,
                authServiceClient,
                notificationProducerService
        );
    }

    @Test
    void deriveGradeStatus_completeWhenAllGraded() {
        assertThat(GradeService.deriveGradeStatus(5, 5)).isEqualTo("complete");
    }

    @Test
    void deriveGradeStatus_inProgressWhenPartiallyGraded() {
        assertThat(GradeService.deriveGradeStatus(2, 5)).isEqualTo("in_progress");
    }

    @Test
    void deriveGradeStatus_needsReviewWhenNoneGraded() {
        assertThat(GradeService.deriveGradeStatus(0, 5)).isEqualTo("needs_review");
    }

    @Test
    void getGradeSheet_deniesNonInstructor() {
        Course course = new Course();
        course.setId(1L);
        course.setInstructorId("teacher-1");
        course.setEnrolledStudents(List.of("student-1"));

        Lesson lesson = new Lesson();
        lesson.setId(10L);
        lesson.setCourse(course);

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "other-teacher")
                .claim("roles", List.of("teacher"))
                .build();

        when(courseRepository.findById(1L)).thenReturn(Optional.of(course));
        when(lessonRepository.findById(10L)).thenReturn(Optional.of(lesson));

        assertThatThrownBy(() -> gradeService.getGradeSheet(1L, 10L, jwt))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getGradeSheet_returnsEnrolledStudents() {
        Course course = new Course();
        course.setId(1L);
        course.setInstructorId("teacher-1");
        course.setEnrolledStudents(List.of("student-1"));

        Lesson lesson = new Lesson();
        lesson.setId(10L);
        lesson.setCourse(course);

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "teacher-1")
                .claim("roles", List.of("teacher"))
                .build();

        when(courseRepository.findById(1L)).thenReturn(Optional.of(course));
        when(lessonRepository.findById(10L)).thenReturn(Optional.of(lesson));
        when(lessonRepository.findByCourseIdOrderByOrderNumber(1L)).thenReturn(List.of(lesson));
        when(lessonGradeRepository.findByLessonId(10L)).thenReturn(List.of());
        when(authServiceClient.resolveUsers(any())).thenReturn(List.of(
                Map.of("id", "student-1", "firstName", "Ivan", "lastName", "Ivanov")
        ));
        when(progressRepository.countByStudentIdAndLessonIdInAndCompletedTrue(any(), any())).thenReturn(0L);

        var sheet = gradeService.getGradeSheet(1L, 10L, jwt);

        assertThat(sheet.getStudents()).hasSize(1);
        assertThat(sheet.getStudents().get(0).getStudentId()).isEqualTo("student-1");
        assertThat(sheet.getStudents().get(0).getFullName()).isEqualTo("Ivan Ivanov");
    }

    @Test
    void saveGrades_sendsNotificationWhenGradeSet() {
        Course course = new Course();
        course.setId(1L);
        course.setTitle("Java");
        course.setInstructorId("teacher-1");
        course.setEnrolledStudents(List.of("student-1"));

        Lesson lesson = new Lesson();
        lesson.setId(10L);
        lesson.setTitle("Введение");
        lesson.setCourse(course);

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "teacher-1")
                .claim("roles", List.of("teacher"))
                .build();

        SaveGradesRequestDto request = new SaveGradesRequestDto(
                1L,
                10L,
                List.of(new SaveGradeEntryDto("student-1", null, 85, "Хорошо"))
        );

        when(courseRepository.findById(1L)).thenReturn(Optional.of(course));
        when(lessonRepository.findById(10L)).thenReturn(Optional.of(lesson));
        when(lessonGradeRepository.findByLessonIdAndStudentId(10L, "student-1"))
                .thenReturn(Optional.empty());
        when(lessonGradeRepository.save(any(LessonGrade.class))).thenAnswer(inv -> inv.getArgument(0));

        gradeService.saveGrades(request, jwt);

        verify(notificationProducerService).sendGradeNotification(
                eq("student-1"),
                eq("Вам выставлена оценка 85 за урок \"Введение\" в курсе \"Java\""),
                eq(1L),
                eq(10L),
                eq(85)
        );
    }
}
