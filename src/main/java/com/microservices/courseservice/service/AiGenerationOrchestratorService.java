package com.microservices.courseservice.service;

import com.microservices.courseservice.ai.AiModelConstants;
import com.microservices.courseservice.dto.RagGenerationContext;
import com.microservices.courseservice.dto.ai.GenerationUsageSummaryDto;
import com.microservices.courseservice.dto.ai.RagLlmUsageDto;
import com.microservices.courseservice.model.ai.AiModel;
import com.microservices.courseservice.model.ai.GenerationRun;
import com.microservices.courseservice.model.ai.GenerationRunStatus;
import com.microservices.courseservice.model.ai.GenerationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiGenerationOrchestratorService {

    private final AiModelCatalogService modelCatalogService;
    private final GenerationRunService generationRunService;
    private final TokenUsageLedgerService tokenUsageLedgerService;
    private final AiGenerationMetricsService aiGenerationMetricsService;

    public record ActiveGeneration(GenerationRun run, RagGenerationContext ragContext) {}

    @Transactional
    public ActiveGeneration start(
            Jwt jwt,
            Long courseId,
            String jobId,
            GenerationType generationType,
            String modelId,
            String idempotencyKey) {
        AiModel model = modelCatalogService.resolveModelForGeneration(
                jwt, modelId, AiModelConstants.CAPABILITY_COURSE_GENERATION);
        GenerationRun run = generationRunService.beginRun(
                jwt.getSubject(),
                courseId,
                jobId,
                generationType,
                model.getId(),
                idempotencyKey);

        if (run.getStatus() == GenerationRunStatus.SUCCEEDED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Generation already completed for this idempotency key");
        }

        if (run.getStatus() != GenerationRunStatus.RUNNING) {
            run = generationRunService.markRunning(run);
        }

        log.info(
                "AI generation started generationRunId={} teacherId={} courseId={} generationType={} modelId={} idempotencyKey={}",
                run.getId(),
                run.getTeacherId(),
                run.getCourseId(),
                run.getGenerationType(),
                model.getId(),
                run.getIdempotencyKey());

        return new ActiveGeneration(run, new RagGenerationContext(model.getId(), run.getId()));
    }

    @Transactional
    public void recordRagUsage(GenerationRun run, RagLlmUsageDto usage) {
        if (usage != null) {
            tokenUsageLedgerService.recordUsage(run, usage);
        }
    }

    @Transactional
    public GenerationUsageSummaryDto finishSuccess(
            GenerationRun run, String ragRequestId, RagLlmUsageDto lastUsage) {
        generationRunService.markSucceeded(run, ragRequestId, lastUsage);
        GenerationUsageSummaryDto summary = tokenUsageLedgerService.summarizeRun(run.getId());
        aiGenerationMetricsService.recordRunFinished(run, summary);
        log.info(
                "AI generation succeeded generationRunId={} ragRequestId={} providerRequestId={} modelId={} totalTokens={} costMicros={}",
                run.getId(),
                ragRequestId,
                lastUsage != null ? lastUsage.getProviderRequestId() : null,
                summary != null ? summary.getModelId() : run.getRequestedModelId(),
                summary != null ? summary.getTotalTokens() : null,
                summary != null ? summary.getCostMicros() : null);
        return summary;
    }

    @Transactional
    public void finishFailure(
            GenerationRun run,
            String errorCode,
            String ragRequestId,
            RagLlmUsageDto usage) {
        if (usage != null) {
            tokenUsageLedgerService.recordUsage(run, usage);
        }
        generationRunService.markFailed(run, errorCode, ragRequestId, usage);
        GenerationUsageSummaryDto summary = tokenUsageLedgerService.summarizeRun(run.getId());
        aiGenerationMetricsService.recordRunFinished(run, summary);
        log.warn(
                "AI generation failed generationRunId={} ragRequestId={} providerRequestId={} errorCode={} modelId={} totalTokens={}",
                run.getId(),
                ragRequestId,
                usage != null ? usage.getProviderRequestId() : null,
                errorCode,
                run.getRequestedModelId(),
                summary != null ? summary.getTotalTokens() : null);
    }

    @Transactional(readOnly = true)
    public GenerationUsageSummaryDto usageSummary(String generationRunId) {
        return tokenUsageLedgerService.summarizeRun(generationRunId);
    }

    public static RagLlmUsageDto withAttemptNumber(RagLlmUsageDto usage, int attemptNumber) {
        if (usage == null) {
            return null;
        }
        RagLlmUsageDto copy = new RagLlmUsageDto();
        copy.setLlmModelId(usage.getLlmModelId());
        copy.setProvider(usage.getProvider());
        copy.setProviderModelId(usage.getProviderModelId());
        copy.setProviderRequestId(usage.getProviderRequestId());
        copy.setInputTokens(usage.getInputTokens());
        copy.setOutputTokens(usage.getOutputTokens());
        copy.setCachedTokens(usage.getCachedTokens());
        copy.setReasoningTokens(usage.getReasoningTokens());
        copy.setTotalTokens(usage.getTotalTokens());
        copy.setUsageSource(usage.getUsageSource());
        copy.setAttemptNumber(attemptNumber);
        copy.setFinishReason(usage.getFinishReason());
        copy.setUsedFallback(usage.getUsedFallback());
        copy.setFallbackFromModelId(usage.getFallbackFromModelId());
        return copy;
    }
}
