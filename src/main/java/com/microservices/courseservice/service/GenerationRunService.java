package com.microservices.courseservice.service;

import com.microservices.courseservice.dto.ai.RagLlmUsageDto;
import com.microservices.courseservice.model.ai.GenerationRun;
import com.microservices.courseservice.model.ai.GenerationRunStatus;
import com.microservices.courseservice.model.ai.GenerationType;
import com.microservices.courseservice.repository.GenerationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GenerationRunService {

    private final GenerationRunRepository generationRunRepository;

    @Transactional
    public GenerationRun beginRun(
            String teacherId,
            Long courseId,
            String jobId,
            GenerationType generationType,
            String requestedModelId,
            String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<GenerationRun> existing = generationRunRepository.findByTeacherIdAndIdempotencyKey(
                    teacherId, idempotencyKey.trim());
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        GenerationRun run = GenerationRun.builder()
                .id(newRunId())
                .idempotencyKey(blankToNull(idempotencyKey))
                .teacherId(teacherId)
                .courseId(courseId)
                .jobId(blankToNull(jobId))
                .generationType(generationType)
                .requestedModelId(requestedModelId)
                .status(GenerationRunStatus.PENDING)
                .createdAt(Instant.now())
                .build();
        return generationRunRepository.save(run);
    }

    @Transactional
    public GenerationRun markRunning(GenerationRun run) {
        run.setStatus(GenerationRunStatus.RUNNING);
        return generationRunRepository.save(run);
    }

    @Transactional
    public GenerationRun markSucceeded(GenerationRun run, String ragRequestId, RagLlmUsageDto usage) {
        applyActualModel(run, usage);
        run.setRagRequestId(blankToNull(ragRequestId));
        run.setStatus(GenerationRunStatus.SUCCEEDED);
        run.setErrorCode(null);
        run.setFinishedAt(Instant.now());
        return generationRunRepository.save(run);
    }

    @Transactional
    public GenerationRun markFailed(
            GenerationRun run,
            String errorCode,
            String ragRequestId,
            RagLlmUsageDto usage) {
        applyActualModel(run, usage);
        run.setRagRequestId(blankToNull(ragRequestId));
        run.setStatus(GenerationRunStatus.FAILED);
        run.setErrorCode(blankToNull(errorCode));
        run.setFinishedAt(Instant.now());
        return generationRunRepository.save(run);
    }

    @Transactional(readOnly = true)
    public Optional<GenerationRun> findById(String runId) {
        return generationRunRepository.findById(runId);
    }

    private static void applyActualModel(GenerationRun run, RagLlmUsageDto usage) {
        if (usage == null) {
            return;
        }
        if (usage.getLlmModelId() != null && !usage.getLlmModelId().isBlank()) {
            run.setActualModelId(usage.getLlmModelId().trim());
        }
        if (usage.getProvider() != null && !usage.getProvider().isBlank()) {
            run.setActualProvider(usage.getProvider().trim());
        }
        if (usage.getProviderModelId() != null && !usage.getProviderModelId().isBlank()) {
            run.setActualProviderModelId(usage.getProviderModelId().trim());
        }
    }

    private static String newRunId() {
        return "gr_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
