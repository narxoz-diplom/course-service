package com.microservices.courseservice.service.backfill;

import com.microservices.courseservice.model.Course;
import com.microservices.courseservice.model.Lesson;
import com.microservices.courseservice.model.Question;
import com.microservices.courseservice.model.Test;
import com.microservices.courseservice.repository.CourseRepository;
import com.microservices.courseservice.repository.LessonRepository;
import com.microservices.courseservice.repository.QuestionRepository;
import com.microservices.courseservice.repository.TestRepository;
import com.microservices.courseservice.service.CourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

@Service
@RequiredArgsConstructor
public class BackfillLocalizationWorker {

    private final CourseRepository courseRepository;
    private final LessonRepository lessonRepository;
    private final TestRepository testRepository;
    private final QuestionRepository questionRepository;

    private final CourseService courseService;

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public Map<String, Object> summary(Long courseId, java.util.Set<String> languages) {
        java.util.Set<String> langs = (languages == null || languages.isEmpty())
                ? java.util.Set.of("kz", "en")
                : java.util.Set.copyOf(languages);

        Course course = courseService.getCourseById(courseId);
        List<Lesson> lessons = lessonRepository.findByCourseIdOrderByOrderNumber(courseId);
        List<Test> tests = testRepository.findByCourseId(courseId);

        int missingCourse = 0;
        if (langs.contains("kz") && (isBlank(course.getTitleKz()) || isBlank(course.getDescriptionKz()))) missingCourse++;
        if (langs.contains("en") && (isBlank(course.getTitleEn()) || isBlank(course.getDescriptionEn()))) missingCourse++;

        int missingLessons = 0;
        for (Lesson l : lessons) {
            if (langs.contains("kz") && (isBlank(l.getTitleKz()) || isBlank(l.getDescriptionKz()) || isBlank(l.getContentKz()))) missingLessons++;
            if (langs.contains("en") && (isBlank(l.getTitleEn()) || isBlank(l.getDescriptionEn()) || isBlank(l.getContentEn()))) missingLessons++;
        }

        int missingTests = 0;
        int missingQuestions = 0;
        for (Test t : tests) {
            if (langs.contains("kz") && isBlank(t.getTitleKz())) missingTests++;
            if (langs.contains("en") && isBlank(t.getTitleEn())) missingTests++;
            List<Question> qs = questionRepository.findByTestIdOrderByOrderNumber(t.getId());
            for (Question q : qs) {
                if (langs.contains("kz") && (isBlank(q.getTextKz()) || isBlank(q.getOptionsKz()) || isBlank(q.getExplanationKz()) || isBlank(q.getHintKz()))) missingQuestions++;
                if (langs.contains("en") && (isBlank(q.getTextEn()) || isBlank(q.getOptionsEn()) || isBlank(q.getExplanationEn()) || isBlank(q.getHintEn()))) missingQuestions++;
            }
        }

        int totalMissing = missingCourse + missingLessons + missingTests + missingQuestions;
        return Map.of(
                "courseId", courseId,
                "languages", langs,
                "missingTotal", totalMissing,
                "missingCourse", missingCourse,
                "missingLessons", missingLessons,
                "missingTests", missingTests,
                "missingQuestions", missingQuestions
        );
    }

    /**
     * Runs backfill in a transaction and reports progress via callback.
     * Permission checks must be done BEFORE calling this method.
     */
    @Transactional
    public Map<String, Object> run(
            Long courseId,
            java.util.Set<String> languages,
            java.util.function.IntConsumer onTotal,
            IntConsumer onProcessed,
            java.util.function.Consumer<String> onMessage) {
        Course course = courseService.getCourseById(courseId);

        java.util.Set<String> langs = (languages == null || languages.isEmpty())
                ? java.util.Set.of("kz", "en")
                : java.util.Set.copyOf(languages);

        List<Lesson> lessons = lessonRepository.findByCourseIdOrderByOrderNumber(courseId);
        List<Test> tests = testRepository.findByCourseId(courseId);

        // Use "missingTotal" as progress total so UI is meaningful and "already translated" becomes 0.
        int total = (int) summary(courseId, langs).getOrDefault("missingTotal", 1);
        onTotal.accept(Math.max(1, total));

        int coursesUpdated = 0;
        int lessonsUpdated = 0;
        int testsUpdated = 0;
        int questionsUpdated = 0;

        onMessage.accept("Переводим поля курса…");
        if (courseService.backfillCourse(course, langs.contains("kz"), langs.contains("en"))) {
            courseRepository.save(course);
            coursesUpdated++;
        }
        // increment progress only if there was work for this entity/language set
        onProcessed.accept(1);

        int lessonIdx = 0;
        for (Lesson lesson : lessons) {
            lessonIdx++;
            onMessage.accept("Переводим уроки: " + lessonIdx + "/" + lessons.size());
            if (courseService.backfillLesson(lesson, langs.contains("kz"), langs.contains("en"))) {
                lessonRepository.save(lesson);
                lessonsUpdated++;
            }
            onProcessed.accept(1);
        }

        int testIdx = 0;
        for (Test test : tests) {
            testIdx++;
            onMessage.accept("Переводим тесты: " + testIdx + "/" + tests.size());
            boolean testChanged = courseService.backfillTest(test, langs.contains("kz"), langs.contains("en"));

            List<Question> questions = questionRepository.findByTestIdOrderByOrderNumber(test.getId());
            int qIdx = 0;
            for (Question question : questions) {
                qIdx++;
                onMessage.accept("Переводим вопросы: " + qIdx + "/" + questions.size());
                if (courseService.backfillQuestion(question, langs.contains("kz"), langs.contains("en"))) {
                    questionRepository.save(question);
                    questionsUpdated++;
                }
                onProcessed.accept(1);
            }

            if (testChanged) {
                testRepository.save(test);
                testsUpdated++;
            }
            onProcessed.accept(1);
        }

        return Map.of(
                "courseId", courseId,
                "coursesUpdated", coursesUpdated,
                "lessonsUpdated", lessonsUpdated,
                "testsUpdated", testsUpdated,
                "questionsUpdated", questionsUpdated
        );
    }
}

