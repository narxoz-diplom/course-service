package com.microservices.courseservice.service;

import com.microservices.courseservice.client.AuthServiceClient;
import com.microservices.courseservice.dto.*;
import com.microservices.courseservice.model.Course;
import com.microservices.courseservice.model.Lesson;
import com.microservices.courseservice.model.LessonGrade;
import com.microservices.courseservice.repository.CourseRepository;
import com.microservices.courseservice.repository.LessonGradeRepository;
import com.microservices.courseservice.repository.LessonRepository;
import com.microservices.courseservice.repository.ProgressRepository;
import com.microservices.courseservice.util.RoleUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GradeService {

    private static final int GRADE_MIN = 0;
    private static final int GRADE_MAX = 100;
    private static final int MAX_FEEDBACK_LENGTH = 2000;
    private static final String DEFAULT_MODULE_ID = "default";

    private final CourseRepository courseRepository;
    private final LessonRepository lessonRepository;
    private final LessonGradeRepository lessonGradeRepository;
    private final ProgressRepository progressRepository;
    private final AuthServiceClient authServiceClient;
    private final NotificationProducerService notificationProducerService;

    @Transactional(readOnly = true)
    public GradeSheetResponseDto getGradeSheet(Long courseId, Long lessonId, Jwt jwt) {
        Course course = requireCourse(courseId);
        Lesson lesson = requireLessonInCourse(lessonId, course);
        assertCanManageGrades(course, jwt);

        List<String> studentIds = enrolledStudentIds(course);
        Map<String, Map<String, Object>> profiles = resolveUserProfiles(studentIds);
        Map<String, LessonGrade> gradesByStudent = lessonGradeRepository.findByLessonId(lessonId).stream()
                .collect(Collectors.toMap(LessonGrade::getStudentId, g -> g, (a, b) -> a));

        List<Long> lessonIds = lessonRepository.findByCourseIdOrderByOrderNumber(courseId).stream()
                .map(Lesson::getId)
                .filter(Objects::nonNull)
                .toList();
        int totalLessons = lessonIds.size();

        List<GradeSheetStudentDto> students = studentIds.stream()
                .map(studentId -> {
                    LessonGrade grade = gradesByStudent.get(studentId);
                    return GradeSheetStudentDto.builder()
                            .studentId(studentId)
                            .fullName(resolveFullName(studentId, profiles, course))
                            .studyStatus(resolveStudyStatus(studentId, lessonIds, totalLessons))
                            .grade(grade != null ? grade.getGrade() : null)
                            .feedback(grade != null && grade.getFeedback() != null ? grade.getFeedback() : "")
                            .build();
                })
                .toList();

        return GradeSheetResponseDto.builder()
                .courseId(courseId)
                .lessonId(lesson.getId())
                .students(students)
                .build();
    }

    @Transactional
    public SaveGradesResponseDto saveGrades(SaveGradesRequestDto request, Jwt jwt) {
        if (request == null || request.getCourseId() == null || request.getLessonId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "courseId and lessonId are required");
        }
        if (request.getEntries() == null || request.getEntries().isEmpty()) {
            return SaveGradesResponseDto.builder().saved(0).build();
        }

        Course course = requireCourse(request.getCourseId());
        Lesson lesson = requireLessonInCourse(request.getLessonId(), course);
        assertCanManageGrades(course, jwt);

        Set<String> enrolled = new HashSet<>(enrolledStudentIds(course));
        String teacherId = jwt.getSubject();
        LocalDateTime now = LocalDateTime.now();
        int saved = 0;
        String courseTitle = course.getTitle() != null ? course.getTitle() : "Курс";
        String lessonTitle = lesson.getTitle() != null ? lesson.getTitle() : "Урок";

        for (SaveGradeEntryDto entry : request.getEntries()) {
            if (entry == null) {
                continue;
            }
            String studentId = normalizeStudentId(entry.getStudentId());
            if (studentId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each entry must include studentId");
            }
            if (!enrolled.contains(studentId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Student is not enrolled in this course: " + studentId);
            }

            Integer grade = entry.getGrade();
            String feedback = normalizeFeedback(entry.getFeedback());

            if (grade != null && (grade < GRADE_MIN || grade > GRADE_MAX)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Grade must be between " + GRADE_MIN + " and " + GRADE_MAX);
            }

            boolean clearGrade = grade == null;
            boolean clearFeedback = feedback == null || feedback.isBlank();

            if (clearGrade && clearFeedback) {
                if (lessonGradeRepository.findByLessonIdAndStudentId(lesson.getId(), studentId).isPresent()) {
                    lessonGradeRepository.deleteByLessonIdAndStudentId(lesson.getId(), studentId);
                    saved++;
                }
                continue;
            }

            LessonGrade entity = lessonGradeRepository
                    .findByLessonIdAndStudentId(lesson.getId(), studentId)
                    .orElseGet(LessonGrade::new);

            entity.setCourseId(course.getId());
            entity.setLesson(lesson);
            entity.setStudentId(studentId);
            entity.setGrade(grade);
            entity.setFeedback(clearFeedback ? null : feedback);
            entity.setGradedBy(teacherId);
            entity.setGradedAt(now);
            entity.setUpdatedAt(now);
            lessonGradeRepository.save(entity);
            saved++;

            if (grade != null) {
                String message = String.format(
                        "Вам выставлена оценка %d за урок \"%s\" в курсе \"%s\"",
                        grade,
                        lessonTitle,
                        courseTitle
                );
                notificationProducerService.sendGradeNotification(
                        studentId,
                        message,
                        course.getId(),
                        lesson.getId(),
                        grade
                );
            }
        }

        return SaveGradesResponseDto.builder().saved(saved).build();
    }

    @Transactional(readOnly = true)
    public StudentGradesResponseDto getMyGrades(Jwt jwt, Long courseIdFilter) {
        if (jwt == null) {
            throw new AccessDeniedException("Authentication required");
        }
        if (RoleUtil.isTeacher(jwt) || RoleUtil.isAdmin(jwt)) {
            throw new AccessDeniedException("Student grades are not available for this role");
        }

        String studentId = jwt.getSubject();
        if (courseIdFilter != null) {
            Course course = requireCourse(courseIdFilter);
            List<String> enrolled = enrolledStudentIds(course);
            if (!enrolled.contains(studentId)) {
                throw new AccessDeniedException("You are not enrolled in this course");
            }
        }

        List<LessonGrade> grades = courseIdFilter != null
                ? lessonGradeRepository.findByCourseIdAndStudentIdWithDetails(courseIdFilter, studentId)
                : lessonGradeRepository.findByStudentIdWithDetails(studentId);

        List<StudentGradeEntryDto> entries = grades.stream()
                .filter(g -> g.getGrade() != null)
                .map(this::toStudentGradeEntry)
                .sorted(Comparator
                        .comparing(StudentGradeEntryDto::getCourseId, Comparator.nullsLast(Long::compareTo))
                        .thenComparing(StudentGradeEntryDto::getLessonId, Comparator.nullsLast(Long::compareTo)))
                .toList();

        return StudentGradesResponseDto.builder().grades(entries).build();
    }

    @Transactional(readOnly = true)
    public List<LessonGradingOverviewDto> buildLessonOverviews(Course course, List<Lesson> lessons) {
        if (lessons == null || lessons.isEmpty()) {
            return List.of();
        }

        int totalStudents = enrolledStudentIds(course).size();
        List<Long> lessonIds = lessons.stream().map(Lesson::getId).filter(Objects::nonNull).toList();
        Map<Long, Long> gradedCountByLesson = new HashMap<>();

        if (!lessonIds.isEmpty()) {
            for (LessonGrade grade : lessonGradeRepository.findByLessonIdInWithLesson(lessonIds)) {
                if (grade.getGrade() != null) {
                    Long lessonId = grade.getLesson().getId();
                    gradedCountByLesson.merge(lessonId, 1L, Long::sum);
                }
            }
        }

        return lessons.stream()
                .map(lesson -> {
                    int gradedCount = gradedCountByLesson.getOrDefault(lesson.getId(), 0L).intValue();
                    return LessonGradingOverviewDto.builder()
                            .id(lesson.getId())
                            .title(lesson.getTitle())
                            .titleKz(lesson.getTitleKz())
                            .titleEn(lesson.getTitleEn())
                            .description(lesson.getDescription())
                            .descriptionKz(lesson.getDescriptionKz())
                            .descriptionEn(lesson.getDescriptionEn())
                            .content(lesson.getContent())
                            .contentKz(lesson.getContentKz())
                            .contentEn(lesson.getContentEn())
                            .orderNumber(lesson.getOrderNumber())
                            .createdAt(lesson.getCreatedAt())
                            .updatedAt(lesson.getUpdatedAt())
                            .gradedCount(gradedCount)
                            .totalStudents(totalStudents)
                            .gradeStatus(deriveGradeStatus(gradedCount, totalStudents))
                            .build();
                })
                .toList();
    }

    private StudentGradeEntryDto toStudentGradeEntry(LessonGrade grade) {
        Lesson lesson = grade.getLesson();
        Course course = lesson != null ? lesson.getCourse() : null;
        return StudentGradeEntryDto.builder()
                .courseId(grade.getCourseId())
                .courseTitle(course != null ? course.getTitle() : null)
                .lessonId(lesson != null ? lesson.getId() : null)
                .lessonTitle(lesson != null ? lesson.getTitle() : null)
                .moduleId(DEFAULT_MODULE_ID)
                .moduleTitle("")
                .grade(grade.getGrade())
                .feedback(grade.getFeedback() != null ? grade.getFeedback() : "")
                .gradedAt(grade.getGradedAt() != null ? grade.getGradedAt().toString() : null)
                .build();
    }

    private Course requireCourse(Long courseId) {
        return courseRepository.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found"));
    }

    private Lesson requireLessonInCourse(Long lessonId, Course course) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lesson not found"));
        if (lesson.getCourse() == null || !Objects.equals(lesson.getCourse().getId(), course.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lesson does not belong to this course");
        }
        return lesson;
    }

    private void assertCanManageGrades(Course course, Jwt jwt) {
        if (jwt == null) {
            throw new AccessDeniedException("Authentication required");
        }
        if (RoleUtil.isAdmin(jwt)) {
            return;
        }
        if (RoleUtil.isTeacher(jwt) && Objects.equals(course.getInstructorId(), jwt.getSubject())) {
            return;
        }
        throw new AccessDeniedException("Only course instructor can manage grades");
    }

    private List<String> enrolledStudentIds(Course course) {
        if (course.getEnrolledStudents() == null) {
            return List.of();
        }
        return course.getEnrolledStudents().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }

    private Map<String, Map<String, Object>> resolveUserProfiles(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        try {
            List<Map<String, Object>> resolved = authServiceClient.resolveUsers(Map.of("userIds", userIds));
            if (resolved == null || resolved.isEmpty()) {
                return Map.of();
            }
            Map<String, Map<String, Object>> byId = new HashMap<>();
            for (Map<String, Object> profile : resolved) {
                if (profile == null) {
                    continue;
                }
                Object id = profile.get("id");
                if (id != null) {
                    byId.put(id.toString(), profile);
                }
            }
            return byId;
        } catch (Exception ex) {
            log.warn("Could not resolve student profiles: {}", ex.getMessage());
            return Map.of();
        }
    }

    private String resolveFullName(String studentId, Map<String, Map<String, Object>> profiles, Course course) {
        Map<String, Object> profile = profiles.get(studentId);
        String fullName = firstNonBlank(
                stringClaim(profile, "fullName"),
                buildNameFromParts(stringClaim(profile, "firstName"), stringClaim(profile, "lastName")),
                stringClaim(profile, "username")
        );
        if (fullName != null && !fullName.isBlank()) {
            return fullName;
        }

        Map<String, String> labels = course.getParticipantDisplayLabels();
        if (labels != null) {
            String label = labels.get(studentId);
            if (label != null && !label.isBlank()) {
                if (label.contains("@")) {
                    return humanizeLocalPart(localPartBeforeAt(label));
                }
                return label.trim();
            }
        }

        String email = stringClaim(profile, "email");
        if (email != null && email.contains("@")) {
            return humanizeLocalPart(localPartBeforeAt(email));
        }
        return "Participant";
    }

    private String resolveStudyStatus(String studentId, List<Long> lessonIds, int totalLessons) {
        if (totalLessons <= 0 || lessonIds.isEmpty()) {
            return "active";
        }
        long completed = progressRepository.countByStudentIdAndLessonIdInAndCompletedTrue(studentId, lessonIds);
        if (completed >= totalLessons) {
            return "completed";
        }
        return "active";
    }

    static String deriveGradeStatus(int gradedCount, int totalStudents) {
        if (totalStudents <= 0) {
            return "needs_review";
        }
        if (gradedCount >= totalStudents) {
            return "complete";
        }
        if (gradedCount > 0) {
            return "in_progress";
        }
        return "needs_review";
    }

    private String normalizeStudentId(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeFeedback(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_FEEDBACK_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Feedback must be at most " + MAX_FEEDBACK_LENGTH + " characters");
        }
        return trimmed;
    }

    private String stringClaim(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        Object value = map.get(key);
        return value != null ? value.toString().trim() : null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String buildNameFromParts(String firstName, String lastName) {
        StringBuilder sb = new StringBuilder();
        if (firstName != null && !firstName.isBlank()) {
            sb.append(firstName.trim());
        }
        if (lastName != null && !lastName.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(lastName.trim());
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private String localPartBeforeAt(String email) {
        if (email == null) {
            return "";
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private String humanizeLocalPart(String local) {
        if (local == null || local.isBlank()) {
            return "Participant";
        }
        String normalized = local.replace('.', ' ').replace('_', ' ').replace('-', ' ').trim();
        if (normalized.isEmpty()) {
            return "Participant";
        }
        return normalized;
    }
}
