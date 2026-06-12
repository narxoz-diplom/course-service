package com.microservices.courseservice.service;

import com.microservices.courseservice.model.ai.GenerationRun;
import com.microservices.courseservice.model.ai.GenerationRunStatus;
import com.microservices.courseservice.model.ai.GenerationType;
import com.microservices.courseservice.repository.GenerationRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenerationRunServiceTest {

    @Mock private GenerationRunRepository generationRunRepository;

    private GenerationRunService service;

    @BeforeEach
    void setUp() {
        service = new GenerationRunService(generationRunRepository);
    }

    @Test
    void beginRunCreatesPendingRun() {
        when(generationRunRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        GenerationRun run = service.beginRun(
                "teacher-1",
                24L,
                null,
                GenerationType.LESSON_OUTLINE,
                "gemini-2.5-flash",
                "idem-1");

        assertThat(run.getId()).startsWith("gr_");
        assertThat(run.getStatus()).isEqualTo(GenerationRunStatus.PENDING);
        assertThat(run.getRequestedModelId()).isEqualTo("gemini-2.5-flash");
        assertThat(run.getTeacherId()).isEqualTo("teacher-1");
        assertThat(run.getCourseId()).isEqualTo(24L);
    }

    @Test
    void duplicateIdempotencyKeyReturnsExistingRun() {
        GenerationRun existing = GenerationRun.builder()
                .id("gr_existing")
                .teacherId("teacher-1")
                .idempotencyKey("idem-1")
                .generationType(GenerationType.LESSON_OUTLINE)
                .requestedModelId("gemini-2.5-flash")
                .status(GenerationRunStatus.SUCCEEDED)
                .build();
        when(generationRunRepository.findByTeacherIdAndIdempotencyKey("teacher-1", "idem-1"))
                .thenReturn(Optional.of(existing));

        GenerationRun run = service.beginRun(
                "teacher-1", 24L, null, GenerationType.LESSON_OUTLINE, "gemini-2.5-flash", "idem-1");

        assertThat(run.getId()).isEqualTo("gr_existing");
        verify(generationRunRepository, times(0)).save(any());
    }

    @Test
    void markSucceededSetsFinishedState() {
        GenerationRun run = GenerationRun.builder()
                .id("gr_1")
                .teacherId("teacher-1")
                .generationType(GenerationType.QUIZ_GENERATION)
                .requestedModelId("gemini-2.5-flash")
                .status(GenerationRunStatus.RUNNING)
                .build();
        when(generationRunRepository.save(run)).thenReturn(run);

        GenerationRun updated = service.markSucceeded(run, "rag-req-1", null);

        assertThat(updated.getStatus()).isEqualTo(GenerationRunStatus.SUCCEEDED);
        assertThat(updated.getRagRequestId()).isEqualTo("rag-req-1");
        assertThat(updated.getFinishedAt()).isNotNull();
    }
}
