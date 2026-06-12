package com.microservices.courseservice.service;

import com.microservices.courseservice.ai.AiModelConstants;
import com.microservices.courseservice.config.AiFeatureProperties;
import com.microservices.courseservice.config.AiProviderProperties;
import com.microservices.courseservice.dto.ai.AiModelCatalogResponseDto;
import com.microservices.courseservice.exception.AiModelException;
import com.microservices.courseservice.model.ai.AiModel;
import com.microservices.courseservice.model.ai.AiModelPolicy;
import com.microservices.courseservice.model.ai.AiModelPricing;
import com.microservices.courseservice.repository.AiModelPolicyRepository;
import com.microservices.courseservice.repository.AiModelPricingRepository;
import com.microservices.courseservice.repository.AiModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiModelCatalogServiceTest {

    @Mock private AiModelRepository aiModelRepository;
    @Mock private AiModelPricingRepository aiModelPricingRepository;
    @Mock private AiModelPolicyRepository aiModelPolicyRepository;
    @Mock private AiQuotaService aiQuotaService;
    @Mock private TeacherAiLimitService teacherAiLimitService;
    @Mock private AiGenerationMetricsService aiGenerationMetricsService;

    private AiModelCatalogService service;
    private AiProviderProperties providerProperties;
    private AiFeatureProperties featureProperties;

    @BeforeEach
    void setUp() {
        stubOpenUserQuota();
        providerProperties = new AiProviderProperties();
        providerProperties.getGoogle().setEnabled(true);
        providerProperties.getOpenai().setEnabled(true);
        featureProperties = new AiFeatureProperties();
        featureProperties.setModelSelectionEnabled(true);
        service = new AiModelCatalogService(
                aiModelRepository,
                aiModelPricingRepository,
                aiModelPolicyRepository,
                providerProperties,
                featureProperties,
                aiQuotaService,
                teacherAiLimitService,
                aiGenerationMetricsService);
    }

    private void stubOpenUserQuota() {
        TeacherAiLimitService.UserQuotaEvaluation open = new TeacherAiLimitService.UserQuotaEvaluation(
                false, false, 1_000_000L, 0L, 1_000_000L, 150_000L, 0L, 150_000L, false, null, null);
        org.mockito.Mockito.lenient()
                .when(teacherAiLimitService.evaluate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(open);
        org.mockito.Mockito.lenient()
                .when(teacherAiLimitService.statusForTeacher(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(com.microservices.courseservice.dto.ai.TeacherAiLimitStatusDto.builder()
                        .monthlyLimit(1_000_000L)
                        .monthlyUsed(0L)
                        .monthlyRemaining(1_000_000L)
                        .dailyLimit(150_000L)
                        .dailyUsed(0L)
                        .dailyRemaining(150_000L)
                        .blocked(false)
                        .build());
    }

    @Test
    void teacherSeesEnabledCourseGenerationModels() {
        Jwt jwt = jwtWithRoles("teacher-1", "teacher");
        AiModel flash = selectableModel("gemini-2.5-flash", "google", true, true);
        AiModel pro = selectableModel("gemini-2.5-pro", "google", false, true);

        when(aiModelRepository.findSelectableByCapability(AiModelConstants.CAPABILITY_COURSE_GENERATION))
                .thenReturn(List.of(flash, pro));
        when(aiModelPolicyRepository.findByModelIdAndAllowedRoleAndCapability(
                "gemini-2.5-flash", "teacher", AiModelConstants.CAPABILITY_COURSE_GENERATION))
                .thenReturn(Optional.of(policy("gemini-2.5-flash", "teacher", null)));
        when(aiModelPolicyRepository.findByModelIdAndAllowedRoleAndCapability(
                "gemini-2.5-pro", "teacher", AiModelConstants.CAPABILITY_COURSE_GENERATION))
                .thenReturn(Optional.of(policy("gemini-2.5-pro", "teacher", null)));
        when(aiModelPricingRepository.findActivePricingCandidates(eq("gemini-2.5-flash"), any(), any(Pageable.class)))
                .thenReturn(List.of(pricing("gemini-2.5-flash", 150000L, 600000L)));
        when(aiModelPricingRepository.findActivePricingCandidates(eq("gemini-2.5-pro"), any(), any(Pageable.class)))
                .thenReturn(List.of(pricing("gemini-2.5-pro", 1250000L, 10000000L)));
        stubOpenQuota("teacher-1");

        AiModelCatalogResponseDto response = service.listModelsForUser(jwt, "course-generation");

        assertThat(response.getDefaultModelId()).isEqualTo("gemini-2.5-flash");
        assertThat(response.getModels()).hasSize(2);
        assertThat(response.getModels()).extracting("id")
                .containsExactly("gemini-2.5-flash", "gemini-2.5-pro");
        assertThat(response.getModels().get(0).getPriceHint().getInputPer1MTokensMicros()).isEqualTo(150000L);
    }

    @Test
    void clientCannotAccessModelCatalog() {
        Jwt jwt = jwtWithRoles("student-1", "client");

        assertThatThrownBy(() -> service.listModelsForUser(jwt, "course-generation"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void disabledModelIsRejectedForGeneration() {
        Jwt jwt = jwtWithRoles("teacher-1", "teacher");
        AiModel disabled = selectableModel("gemini-2.5-pro", "google", false, false);
        disabled.setDisabledReason("Temporarily unavailable");

        when(aiModelRepository.findById("gemini-2.5-pro")).thenReturn(Optional.of(disabled));
        when(aiModelPolicyRepository.findByModelIdAndAllowedRoleAndCapability(
                "gemini-2.5-pro", "teacher", AiModelConstants.CAPABILITY_COURSE_GENERATION))
                .thenReturn(Optional.of(policy("gemini-2.5-pro", "teacher", null)));
        stubOpenQuota("teacher-1");

        assertThatThrownBy(() -> service.resolveModelForGeneration(
                jwt, "gemini-2.5-pro", AiModelConstants.CAPABILITY_COURSE_GENERATION))
                .isInstanceOf(AiModelException.class)
                .satisfies(ex -> assertThat(((AiModelException) ex).getCode())
                        .isEqualTo(AiModelException.Code.MODEL_DISABLED));
    }

    @Test
    void unknownModelIdIsRejected() {
        Jwt jwt = jwtWithRoles("teacher-1", "teacher");
        when(aiModelRepository.findById("unknown-model")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveModelForGeneration(
                jwt, "unknown-model", AiModelConstants.CAPABILITY_COURSE_GENERATION))
                .isInstanceOf(AiModelException.class)
                .satisfies(ex -> assertThat(((AiModelException) ex).getCode())
                        .isEqualTo(AiModelException.Code.INVALID_MODEL_ID));
    }

    @Test
    void pricingLookupUsesActiveVersion() {
        Instant at = Instant.parse("2026-06-15T00:00:00Z");
        AiModelPricing oldPricing = pricing("gemini-2.5-flash", 100000L, 400000L);
        oldPricing.setEffectiveFrom(Instant.parse("2025-01-01T00:00:00Z"));
        oldPricing.setEffectiveTo(Instant.parse("2026-01-01T00:00:00Z"));
        AiModelPricing newPricing = pricing("gemini-2.5-flash", 150000L, 600000L);
        newPricing.setEffectiveFrom(Instant.parse("2026-01-01T00:00:00Z"));

        when(aiModelPricingRepository.findActivePricingCandidates(
                eq("gemini-2.5-flash"), eq(at), any(Pageable.class)))
                .thenReturn(List.of(newPricing));

        Optional<AiModelPricing> active = service.findActivePricing("gemini-2.5-flash", at);

        assertThat(active).isPresent();
        assertThat(active.get().getInputPricePerMillionMicros()).isEqualTo(150000L);
    }

    @Test
    void embeddingModelIsNotReturnedInSelectableCatalogQuery() {
        Jwt jwt = jwtWithRoles("teacher-1", "teacher");
        when(aiModelRepository.findSelectableByCapability(AiModelConstants.CAPABILITY_COURSE_GENERATION))
                .thenReturn(List.of(selectableModel("gemini-2.5-flash", "google", true, true)));
        when(aiModelPolicyRepository.findByModelIdAndAllowedRoleAndCapability(
                "gemini-2.5-flash", "teacher", AiModelConstants.CAPABILITY_COURSE_GENERATION))
                .thenReturn(Optional.of(policy("gemini-2.5-flash", "teacher", null)));
        when(aiModelPricingRepository.findActivePricingCandidates(eq("gemini-2.5-flash"), any(), any(Pageable.class)))
                .thenReturn(List.of(pricing("gemini-2.5-flash", 150000L, 600000L)));
        stubOpenQuota("teacher-1");

        AiModelCatalogResponseDto response = service.listModelsForUser(jwt, "course-generation");

        assertThat(response.getModels()).extracting("id")
                .doesNotContain(AiModelConstants.EMBEDDING_MODEL_ID);
    }

    @Test
    void modelSelectionDisabledReturnsOnlyDefaultModel() {
        featureProperties.setModelSelectionEnabled(false);
        Jwt jwt = jwtWithRoles("teacher-1", "teacher");
        AiModel flash = selectableModel("gemini-2.5-flash", "google", true, true);
        AiModel pro = selectableModel("gemini-2.5-pro", "google", false, true);

        when(aiModelRepository.findSelectableByCapability(AiModelConstants.CAPABILITY_COURSE_GENERATION))
                .thenReturn(List.of(flash, pro));
        when(aiModelPolicyRepository.findByModelIdAndAllowedRoleAndCapability(
                "gemini-2.5-flash", "teacher", AiModelConstants.CAPABILITY_COURSE_GENERATION))
                .thenReturn(Optional.of(policy("gemini-2.5-flash", "teacher", null)));
        when(aiModelPolicyRepository.findByModelIdAndAllowedRoleAndCapability(
                "gemini-2.5-pro", "teacher", AiModelConstants.CAPABILITY_COURSE_GENERATION))
                .thenReturn(Optional.of(policy("gemini-2.5-pro", "teacher", null)));
        when(aiModelPricingRepository.findActivePricingCandidates(eq("gemini-2.5-flash"), any(), any(Pageable.class)))
                .thenReturn(List.of(pricing("gemini-2.5-flash", 150000L, 600000L)));
        stubOpenQuota("teacher-1");

        AiModelCatalogResponseDto response = service.listModelsForUser(jwt, "course-generation");

        assertThat(response.isModelSelectionEnabled()).isFalse();
        assertThat(response.getModels()).hasSize(1);
        assertThat(response.getModels().get(0).getId()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void quotaExceededModelIsBlocked() {
        Jwt jwt = jwtWithRoles("teacher-1", "teacher");
        AiModel gpt4o = selectableModel("gpt-4o", "openai", false, true);
        AiModelPolicy limited = policy("gpt-4o", "teacher", 500_000L);

        when(aiModelRepository.findById("gpt-4o")).thenReturn(Optional.of(gpt4o));
        when(aiModelPolicyRepository.findByModelIdAndAllowedRoleAndCapability(
                "gpt-4o", "teacher", AiModelConstants.CAPABILITY_COURSE_GENERATION))
                .thenReturn(Optional.of(limited));
        when(aiQuotaService.evaluate("teacher-1", gpt4o, limited))
                .thenReturn(new AiQuotaService.QuotaEvaluation(
                        500_000L, 500_000L, 0L, null, 0L, null, true, "Monthly token quota exceeded for model: gpt-4o"));

        assertThatThrownBy(() -> service.resolveModelForGeneration(
                jwt, "gpt-4o", AiModelConstants.CAPABILITY_COURSE_GENERATION))
                .isInstanceOf(AiModelException.class)
                .satisfies(ex -> assertThat(((AiModelException) ex).getCode())
                        .isEqualTo(AiModelException.Code.QUOTA_EXCEEDED));
    }

    private void stubOpenQuota(String teacherId) {
        org.mockito.Mockito.lenient()
                .when(aiQuotaService.evaluate(
                        org.mockito.ArgumentMatchers.eq(teacherId),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AiQuotaService.QuotaEvaluation(null, 0L, null, null, 0L, null, false, null));
        org.mockito.Mockito.lenient()
                .when(aiQuotaService.toQuotaDto(org.mockito.ArgumentMatchers.any()))
                .thenReturn(null);
    }

    private static Jwt jwtWithRoles(String subject, String role) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();
    }

    private static AiModel selectableModel(String id, String provider, boolean isDefault, boolean enabled) {
        return AiModel.builder()
                .id(id)
                .provider(provider)
                .providerModelId(id)
                .displayName(id)
                .description("desc")
                .tier("fast")
                .contextWindowTokens(128000L)
                .selectable(true)
                .enabled(enabled)
                .isDefault(isDefault)
                .sortOrder(10)
                .capabilities(new LinkedHashSet<>(Set.of(AiModelConstants.CAPABILITY_COURSE_GENERATION)))
                .build();
    }

    private static AiModelPolicy policy(String modelId, String role, Long quota) {
        return AiModelPolicy.builder()
                .modelId(modelId)
                .allowedRole(role)
                .capability(AiModelConstants.CAPABILITY_COURSE_GENERATION)
                .monthlyTokenQuota(quota)
                .build();
    }

    private static AiModelPricing pricing(String modelId, long input, long output) {
        return AiModelPricing.builder()
                .modelId(modelId)
                .inputPricePerMillionMicros(input)
                .outputPricePerMillionMicros(output)
                .currency("USD")
                .effectiveFrom(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }
}
