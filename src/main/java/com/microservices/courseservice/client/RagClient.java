package com.microservices.courseservice.client;

import com.microservices.courseservice.dto.CourseOutlineResponse;
import com.microservices.courseservice.dto.LessonGenerationParamsDto;
import com.microservices.courseservice.dto.RagLessonDto;
import com.microservices.courseservice.dto.RagLessonsResponse;
import com.microservices.courseservice.dto.RagQuizQuestionDto;
import com.microservices.courseservice.dto.RagQuizResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.net.ConnectException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Component
@Slf4j
public class RagClient {

    private final WebClient webClient;
    private final Duration timeout;
    private final int retryMaxAttempts;
    private final Duration retryFirstBackoff;

    public RagClient(
            WebClient.Builder webClientBuilder,
            @Value("${rag.service.url:http://localhost:8000}") String ragServiceUrl,
            @Value("${rag.service.api-key:}") String apiKey,
            @Value("${rag.service.timeout:90}") int timeoutSeconds,
            @Value("${rag.service.retry.max-attempts:3}") int retryMaxAttempts,
            @Value("${rag.service.retry.first-backoff-ms:500}") long retryFirstBackoffMs) {
        WebClient.Builder builder = webClientBuilder.baseUrl(ragServiceUrl);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("X-API-Key", apiKey);
        }
        this.webClient = builder.build();
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryFirstBackoff = Duration.ofMillis(Math.max(0, retryFirstBackoffMs));
    }

    private static Throwable rootCause(Throwable t) {
        while (t != null && t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    /**
     * Map transient (timeout, connection) and downstream errors to RagClientException
     * so callers get a consistent error type. 4xx/5xx are already mapped in onStatus.
     */
    private static Throwable mapToRagClientException(Throwable t) {
        if (t instanceof RagClientException) {
            return t;
        }
        Throwable root = rootCause(t);
        if (root instanceof TimeoutException) {
            return new RagClientException("RAG request timed out", t);
        }
        if (root instanceof ConnectException) {
            return new RagClientException("RAG service unreachable: " + root.getMessage(), t);
        }
        if (t instanceof WebClientResponseException wcre) {
            String msg = "RAG request failed with status " + wcre.getStatusCode().value();
            if (wcre.getResponseBodyAsString() != null && !wcre.getResponseBodyAsString().isBlank()) {
                msg += ": " + wcre.getResponseBodyAsString();
            }
            return new RagClientException(msg, t);
        }
        return new RagClientException("RAG request failed: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()), t);
    }

    private static final int TOP_K_CAP = 2000;

    static void putGenerationParams(Map<String, Object> body, LessonGenerationParamsDto p) {
        if (p == null) {
            return;
        }
        Map<String, Object> m = new HashMap<>();
        if (p.getTeacherBrief() != null && !p.getTeacherBrief().isBlank()) {
            m.put("teacher_brief", p.getTeacherBrief());
        }
        if (p.getTargetAudience() != null && !p.getTargetAudience().isBlank()) {
            m.put("target_audience", p.getTargetAudience());
        }
        if (p.getMinLessons() != null) {
            m.put("min_lessons", p.getMinLessons());
        }
        if (p.getMaxLessons() != null) {
            m.put("max_lessons", p.getMaxLessons());
        }
        if (p.getDepth() != null && !p.getDepth().isBlank()) {
            m.put("depth", p.getDepth());
        }
        if (p.getRetrievalMode() != null && !p.getRetrievalMode().isBlank()) {
            m.put("retrieval_mode", p.getRetrievalMode());
        }
        if (p.getRetrievalQuery() != null && !p.getRetrievalQuery().isBlank()) {
            m.put("retrieval_query", p.getRetrievalQuery());
        }
        if (!m.isEmpty()) {
            body.put("params", m);
        }
    }

    /** Retry only on timeout/connection (transient), not on 4xx/5xx. */
    private static boolean isTransient(Throwable t) {
        if (t instanceof RagClientException || t instanceof WebClientResponseException) {
            return false;
        }
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof TimeoutException || x instanceof ConnectException) {
                return true;
            }
        }
        return t instanceof TimeoutException || t instanceof ConnectException;
    }

    /**
     * Generate lessons from RAG collection (legacy one-shot).
     */
    public List<RagLessonDto> generateLessons(String collectionName, List<Long> fileIds, String prompt) {
        return generateLessons(collectionName, fileIds, prompt, null, null);
    }

    /**
     * Generate lessons with optional top_k and pedagogy params.
     */
    public List<RagLessonDto> generateLessons(
            String collectionName,
            List<Long> fileIds,
            String prompt,
            Integer topK,
            LessonGenerationParamsDto params) {
        return generateLessonsResponse(collectionName, fileIds, prompt, topK, params, null).getLessons();
    }

    public RagLessonsResponse generateLessonsResponse(
            String collectionName,
            List<Long> fileIds,
            String prompt,
            Integer topK,
            LessonGenerationParamsDto params,
            List<String> languages) {
        var request = new java.util.HashMap<String, Object>();
        request.put("collection_name", collectionName);
        String p = prompt != null && !prompt.isBlank() ? prompt
                : "Создай структурированный курс из нескольких уроков на основе загруженных материалов. "
                + "В каждом уроке, где это уместно, добавь 1–3 иллюстрации в виде markdown-картинок с полными https-ссылками на изображения из интернета "
                + "(формат: ![краткий текст](https://...)). Эти картинки будут показаны ученику внутри текста урока. "
                + "Если по какому-то фрагменту материала подходящей картинки нет, просто не добавляй её.";
        request.put("prompt", p);
        int k = topK != null ? Math.min(Math.max(topK, 1), TOP_K_CAP) : 16;
        request.put("top_k", k);
        if (fileIds != null && !fileIds.isEmpty()) {
            request.put("file_ids", fileIds.stream().map(String::valueOf).toList());
        }
        if (languages != null && !languages.isEmpty()) {
            request.put("languages", languages);
        }
        putGenerationParams(request, params);

        RagLessonsResponse response = webClient.post()
                .uri("/api/v1/generate-lessons")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> {
                                    String message = "RAG generate-lessons failed with status " + clientResponse.statusCode().value();
                                    if (!body.isBlank()) {
                                        message += ": " + body;
                                    }
                                    return new RagClientException(message);
                                })
                )
                .bodyToMono(RagLessonsResponse.class)
                .timeout(timeout)
                .retryWhen(Retry.backoff(retryMaxAttempts - 1, retryFirstBackoff)
                        .filter(RagClient::isTransient)
                        .doBeforeRetry(s -> log.warn("RAG generate-lessons retry attempt {} after: {}", s.totalRetries() + 1, s.failure().getMessage())))
                .onErrorMap(RagClient::mapToRagClientException)
                .block();

        if (response == null) {
            throw new RagClientException("RAG generate-lessons returned null response");
        }
        if (response.getLessons() == null) {
            throw new RagClientException("RAG generate-lessons returned invalid format: 'lessons' field is missing");
        }
        return response;
    }

    public CourseOutlineResponse generateCourseOutline(
            String collectionName,
            List<Long> fileIds,
            String prompt,
            Integer topK,
            LessonGenerationParamsDto params) {
        var request = new java.util.HashMap<String, Object>();
        request.put("collection_name", collectionName);
        if (prompt != null && !prompt.isBlank()) {
            request.put("prompt", prompt);
        }
        if (topK != null) {
            request.put("top_k", Math.min(Math.max(topK, 1), TOP_K_CAP));
        }
        if (fileIds != null && !fileIds.isEmpty()) {
            request.put("file_ids", fileIds.stream().map(String::valueOf).toList());
        }
        putGenerationParams(request, params);

        CourseOutlineResponse response = webClient.post()
                .uri("/api/v1/generate-course-outline")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> {
                                    String message = "RAG generate-course-outline failed with status "
                                            + clientResponse.statusCode().value();
                                    if (!body.isBlank()) {
                                        message += ": " + body;
                                    }
                                    return new RagClientException(message);
                                })
                )
                .bodyToMono(CourseOutlineResponse.class)
                .timeout(timeout)
                .retryWhen(Retry.backoff(retryMaxAttempts - 1, retryFirstBackoff)
                        .filter(RagClient::isTransient)
                        .doBeforeRetry(s -> log.warn("RAG generate-course-outline retry attempt {} after: {}",
                                s.totalRetries() + 1, s.failure().getMessage())))
                .onErrorMap(RagClient::mapToRagClientException)
                .block();

        if (response == null || response.getOutline() == null) {
            throw new RagClientException("RAG generate-course-outline returned invalid response");
        }
        return response;
    }

    public RagLessonDto generateSingleLesson(
            String collectionName,
            List<Long> fileIds,
            String title,
            String summary,
            int lessonIndex,
            int totalLessons,
            int topK,
            LessonGenerationParamsDto params) {
        return generateSingleLessonResponse(collectionName, fileIds, title, summary, lessonIndex, totalLessons, topK, params, null)
                .getLessons().get(0);
    }

    public RagLessonsResponse generateSingleLessonResponse(
            String collectionName,
            List<Long> fileIds,
            String title,
            String summary,
            int lessonIndex,
            int totalLessons,
            int topK,
            LessonGenerationParamsDto params,
            List<String> languages) {
        var request = new java.util.HashMap<String, Object>();
        request.put("collection_name", collectionName);
        request.put("title", title);
        request.put("summary", summary != null ? summary : "");
        request.put("lesson_index", lessonIndex);
        request.put("total_lessons", totalLessons);
        request.put("top_k", Math.min(Math.max(topK, 1), 80));
        if (fileIds != null && !fileIds.isEmpty()) {
            request.put("file_ids", fileIds.stream().map(String::valueOf).toList());
        }
        if (languages != null && !languages.isEmpty()) {
            request.put("languages", languages);
        }
        putGenerationParams(request, params);

        RagLessonsResponse response = webClient.post()
                .uri("/api/v1/generate-single-lesson-lms")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> {
                                    String message = "RAG generate-single-lesson-lms failed with status "
                                            + clientResponse.statusCode().value();
                                    if (!body.isBlank()) {
                                        message += ": " + body;
                                    }
                                    return new RagClientException(message);
                                })
                )
                .bodyToMono(RagLessonsResponse.class)
                .timeout(timeout)
                .retryWhen(Retry.backoff(retryMaxAttempts - 1, retryFirstBackoff)
                        .filter(RagClient::isTransient)
                        .doBeforeRetry(s -> log.warn("RAG generate-single-lesson-lms retry attempt {} after: {}",
                                s.totalRetries() + 1, s.failure().getMessage())))
                .onErrorMap(RagClient::mapToRagClientException)
                .block();

        if (response == null || response.getLessons() == null || response.getLessons().isEmpty()) {
            throw new RagClientException("RAG generate-single-lesson-lms returned no lesson");
        }
        return response;
    }

    /**
     * Generate quiz/test from RAG collection (LMS endpoint).
     *
     * @param collectionName course collection (e.g. course_1)
     * @param fileIds        optional filter by file IDs; if null/empty, use all files
     * @param lessonIds      optional filter by lesson IDs
     * @param prompt         optional prompt
     * @return list of questions: [{question, options, correct, explanation, hint}, ...]
     */
    public List<RagQuizQuestionDto> generateQuiz(
            String collectionName, List<Long> fileIds, List<Long> lessonIds, String prompt) {
        return generateQuiz(collectionName, fileIds, lessonIds, prompt, null, null);
    }

    public List<RagQuizQuestionDto> generateQuiz(
            String collectionName,
            List<Long> fileIds,
            List<Long> lessonIds,
            String prompt,
            Integer questionCount,
            String difficulty) {
        return generateQuizResponse(collectionName, fileIds, lessonIds, prompt, questionCount, difficulty, null).getQuestions();
    }

    public RagQuizResponse generateQuizResponse(
            String collectionName,
            List<Long> fileIds,
            List<Long> lessonIds,
            String prompt,
            Integer questionCount,
            String difficulty,
            List<String> languages) {
        var requestBuilder = new java.util.HashMap<String, Object>();
        requestBuilder.put("collection_name", collectionName);
        requestBuilder.put("prompt", prompt != null ? prompt : "Создай тест по загруженным материалам.");
        requestBuilder.put("top_k", 20);
        if (fileIds != null && !fileIds.isEmpty()) {
            requestBuilder.put("file_ids", fileIds.stream().map(String::valueOf).toList());
        }
        if (lessonIds != null && !lessonIds.isEmpty()) {
            requestBuilder.put("lesson_ids", lessonIds.stream().map(String::valueOf).toList());
        }
        if (questionCount != null) {
            requestBuilder.put("question_count", questionCount);
        }
        if (difficulty != null && !difficulty.isBlank()) {
            requestBuilder.put("difficulty", difficulty);
        }
        if (languages != null && !languages.isEmpty()) {
            requestBuilder.put("languages", languages);
        }

        RagQuizResponse response = webClient.post()
                .uri("/api/v1/generate-quiz-lms")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBuilder)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> {
                                    String message = "RAG generate-quiz failed with status " + clientResponse.statusCode().value();
                                    if (!body.isBlank()) {
                                        message += ": " + body;
                                    }
                                    return new RagClientException(message);
                                })
                )
                .bodyToMono(RagQuizResponse.class)
                .timeout(timeout)
                .retryWhen(Retry.backoff(retryMaxAttempts - 1, retryFirstBackoff)
                        .filter(RagClient::isTransient)
                        .doBeforeRetry(s -> log.warn("RAG generate-quiz retry attempt {} after: {}", s.totalRetries() + 1, s.failure().getMessage())))
                .onErrorMap(RagClient::mapToRagClientException)
                .block();

        if (response == null) {
            throw new RagClientException("RAG generate-quiz returned null response");
        }
        if (response.getQuestions() == null) {
            throw new RagClientException("RAG generate-quiz returned invalid format: 'questions' field is missing");
        }
        return response;
    }

    /**
     * Vectorize lesson content for RAG (enables test generation filtered by lesson).
     */
    public void vectorizeText(String text, String collectionName, Map<String, Object> metadata) {
        var request = new java.util.HashMap<String, Object>();
        request.put("text", text);
        request.put("collection_name", collectionName);
        if (metadata != null && !metadata.isEmpty()) {
            request.put("metadata", metadata);
        }

        webClient.post()
                .uri("/api/v1/vectorize-text")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> {
                                    String message = "RAG vectorize-text failed with status " + clientResponse.statusCode().value();
                                    if (!body.isBlank()) {
                                        message += ": " + body;
                                    }
                                    return new RagClientException(message);
                                })
                )
                .toBodilessEntity()
                .timeout(timeout)
                .retryWhen(Retry.backoff(retryMaxAttempts - 1, retryFirstBackoff)
                        .filter(RagClient::isTransient)
                        .doBeforeRetry(s -> log.warn("RAG vectorize-text retry attempt {} after: {}", s.totalRetries() + 1, s.failure().getMessage())))
                .onErrorMap(RagClient::mapToRagClientException)
                .block();
    }
}
