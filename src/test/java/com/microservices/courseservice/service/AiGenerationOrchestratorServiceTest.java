package com.microservices.courseservice.service;

import com.microservices.courseservice.ai.AiModelConstants;
import com.microservices.courseservice.dto.ai.GenerationUsageSummaryDto;
import com.microservices.courseservice.dto.ai.RagLlmUsageDto;
import com.microservices.courseservice.model.ai.AiModel;
import com.microservices.courseservice.model.ai.GenerationRun;
import com.microservices.courseservice.model.ai.GenerationRunStatus;
import com.microservices.courseservice.model.ai.GenerationType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiGenerationOrchestratorServiceTest {

    @Mock private AiModelCatalogService modelCatalogService;
    @Mock private GenerationRunService generationRunService;
    @Mock private TokenUsageLedgerService tokenUsageLedgerService;

    private AiGenerationOrchestratorService orchestrator;
    private Jwt jwt;
    private AiModel model;

    @BeforeEach
    void setUp() {
        orchestrator = new AiGenerationOrchestratorService(
                modelCatalogService,
                generationRunService,
                tokenUsageLedgerService,
                new AiGenerationMetricsService(new SimpleMeterRegistry()));
        jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("teacher-1")
                .claim("realm_access", Map.of("roles", List.of("teacher")))
                .build();
        model = AiModel.builder()
                .id(AiModelConstants.DEFAULT_MODEL_ID)
                .provider("google")
                .providerModelId(AiModelConstants.DEFAULT_MODEL_ID)
                .displayName("Gemini 2.5 Flash")
                .tier("fast")
                .selectable(true)
                .enabled(true)
                .capabilities(new LinkedHashSet<>(Set.of(AiModelConstants.CAPABILITY_COURSE_GENERATION)))
                .build();
    }

    @Test
    void startReturnsRagContextForNewRun() {
        GenerationRun run = GenerationRun.builder()
                .id("gr_new")
                .teacherId("teacher-1")
                .courseId(1L)
                .generationType(GenerationType.LESSON_OUTLINE)
                .requestedModelId(model.getId())
                .status(GenerationRunStatus.PENDING)
                .build();
        GenerationRun running = GenerationRun.builder()
                .id("gr_new")
                .teacherId("teacher-1")
                .courseId(1L)
                .generationType(GenerationType.LESSON_OUTLINE)
                .requestedModelId(model.getId())
                .status(GenerationRunStatus.RUNNING)
                .build();

        when(modelCatalogService.resolveModelForGeneration(
                jwt, model.getId(), AiModelConstants.CAPABILITY_COURSE_GENERATION))
                .thenReturn(model);
        when(generationRunService.beginRun(
                eq("teacher-1"), eq(1L), eq(null), eq(GenerationType.LESSON_OUTLINE), eq(model.getId()), eq("idem-1")))
                .thenReturn(run);
        when(generationRunService.markRunning(run)).thenReturn(running);

        AiGenerationOrchestratorService.ActiveGeneration active = orchestrator.start(
                jwt, 1L, null, GenerationType.LESSON_OUTLINE, model.getId(), "idem-1");

        assertThat(active.run().getId()).isEqualTo("gr_new");
        assertThat(active.ragContext().llmModelId()).isEqualTo(model.getId());
        assertThat(active.ragContext().generationRunId()).isEqualTo("gr_new");
    }

    @Test
    void duplicateSucceededIdempotencyKeyThrowsConflict() {
        GenerationRun existing = GenerationRun.builder()
                .id("gr_done")
                .teacherId("teacher-1")
                .generationType(GenerationType.LESSON_OUTLINE)
                .requestedModelId(model.getId())
                .status(GenerationRunStatus.SUCCEEDED)
                .idempotencyKey("idem-1")
                .build();

        when(modelCatalogService.resolveModelForGeneration(any(), any(), any())).thenReturn(model);
        when(generationRunService.beginRun(any(), any(), any(), any(), any(), eq("idem-1")))
                .thenReturn(existing);

        assertThatThrownBy(() -> orchestrator.start(
                jwt, 1L, null, GenerationType.LESSON_OUTLINE, model.getId(), "idem-1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void finishSuccessRecordsLedgerSummaryAndMetrics() {
        GenerationRun run = GenerationRun.builder()
                .id("gr_1")
                .teacherId("teacher-1")
                .generationType(GenerationType.QUIZ_GENERATION)
                .requestedModelId(model.getId())
                .actualModelId(model.getId())
                .actualProvider("google")
                .status(GenerationRunStatus.RUNNING)
                .build();
        RagLlmUsageDto usage = new RagLlmUsageDto();
        usage.setLlmModelId(model.getId());
        usage.setProvider("google");
        usage.setTotalTokens(120);
        usage.setProviderRequestId("prov-req-1");

        GenerationUsageSummaryDto summary = GenerationUsageSummaryDto.builder()
                .generationRunId("gr_1")
                .modelId(model.getId())
                .totalTokens(120)
                .costMicros(90L)
                .currency("USD")
                .build();

        when(generationRunService.markSucceeded(run, "rag-req-1", usage)).thenReturn(run);
        when(tokenUsageLedgerService.summarizeRun("gr_1")).thenReturn(summary);

        GenerationUsageSummaryDto result = orchestrator.finishSuccess(run, "rag-req-1", usage);

        assertThat(result.getTotalTokens()).isEqualTo(120);
        verify(generationRunService).markSucceeded(run, "rag-req-1", usage);
        verify(tokenUsageLedgerService).summarizeRun("gr_1");
    }
}
