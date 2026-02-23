package com.microservices.courseservice.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class RagClient {

    private final WebClient webClient;

    public RagClient(
            WebClient.Builder webClientBuilder,
            @Value("${rag.service.url:http://localhost:8000}") String ragServiceUrl,
            @Value("${rag.service.api-key:}") String apiKey) {
        WebClient.Builder builder = webClientBuilder.baseUrl(ragServiceUrl);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("X-API-Key", apiKey);
        }
        this.webClient = builder.build();
    }

    /**
     * Generate lessons from RAG collection.
     *
     * @param collectionName course collection (e.g. course_1)
     * @param fileIds        optional filter by file IDs; if null/empty, use all content
     * @param prompt         optional prompt
     * @return list of lessons: [{title, content, description, order}, ...]
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> generateLessons(String collectionName, List<Long> fileIds, String prompt) {
        var request = new java.util.HashMap<String, Object>();
        request.put("collection_name", collectionName);
        request.put("prompt", prompt != null ? prompt : "Создай структурированный курс из нескольких уроков на основе загруженных материалов.");
        request.put("top_k", 16);
        if (fileIds != null && !fileIds.isEmpty()) {
            request.put("file_ids", fileIds.stream().map(String::valueOf).toList());
        }

        var response = webClient.post()
                .uri("/api/v1/generate-lessons")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (response == null) {
            throw new RuntimeException("RAG generate-lessons returned null");
        }

        Object lessonsObj = response.get("lessons");
        if (lessonsObj instanceof List) {
            return (List<Map<String, Object>>) lessonsObj;
        }
        throw new RuntimeException("RAG generate-lessons returned invalid format");
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
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> generateQuiz(String collectionName, List<Long> fileIds, List<Long> lessonIds, String prompt) {
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

        var response = webClient.post()
                .uri("/api/v1/generate-quiz-lms")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBuilder)
                .retrieve()
                .bodyToMono(Map.class)
                .block();

        if (response == null) {
            throw new RuntimeException("RAG generate-quiz returned null");
        }

        Object questionsObj = response.get("questions");
        if (questionsObj instanceof List) {
            return (List<Map<String, Object>>) questionsObj;
        }
        throw new RuntimeException("RAG generate-quiz returned invalid format");
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
                .toBodilessEntity()
                .block();
    }
}
