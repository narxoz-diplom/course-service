package com.microservices.courseservice.service;

import com.microservices.courseservice.ai.AiModelConstants;
import com.microservices.courseservice.config.AiFeatureProperties;
import com.microservices.courseservice.config.AiProviderProperties;
import com.microservices.courseservice.dto.ai.AiModelCatalogResponseDto;
import com.microservices.courseservice.dto.ai.AiModelOptionDto;
import com.microservices.courseservice.dto.ai.AiModelPriceHintDto;
import com.microservices.courseservice.dto.ai.AiModelQuotaDto;
import com.microservices.courseservice.exception.AiModelException;
import com.microservices.courseservice.model.ai.AiModel;
import com.microservices.courseservice.model.ai.AiModelPolicy;
import com.microservices.courseservice.model.ai.AiModelPricing;
import com.microservices.courseservice.repository.AiModelPolicyRepository;
import com.microservices.courseservice.repository.AiModelPricingRepository;
import com.microservices.courseservice.repository.AiModelRepository;
import com.microservices.courseservice.util.RoleUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AiModelCatalogService {

    private final AiModelRepository aiModelRepository;
    private final AiModelPricingRepository aiModelPricingRepository;
    private final AiModelPolicyRepository aiModelPolicyRepository;
    private final AiProviderProperties aiProviderProperties;
    private final AiFeatureProperties aiFeatureProperties;
    private final AiQuotaService aiQuotaService;
    private final TeacherAiLimitService teacherAiLimitService;
    private final AiGenerationMetricsService aiGenerationMetricsService;

    @Transactional(readOnly = true)
    public AiModelCatalogResponseDto listModelsForUser(Jwt jwt, String capability) {
        requireTeacherOrAdmin(jwt);
        String normalizedCapability = normalizeCapability(capability);
        String role = resolveCatalogRole(jwt);
        boolean modelSelectionEnabled = aiFeatureProperties.isModelSelectionEnabled();

        List<AiModelOptionDto> models = new ArrayList<>();
        for (AiModel model : aiModelRepository.findSelectableByCapability(normalizedCapability)) {
            Optional<AiModelPolicy> policy = aiModelPolicyRepository.findByModelIdAndAllowedRoleAndCapability(
                    model.getId(), role, normalizedCapability);
            if (policy.isEmpty()) {
                continue;
            }
            models.add(toOptionDto(jwt.getSubject(), model, policy.get()));
        }

        if (!modelSelectionEnabled) {
            models = models.stream()
                    .filter(AiModelOptionDto::isDefault)
                    .findFirst()
                    .map(List::of)
                    .orElse(models.stream().filter(AiModelOptionDto::isEnabled).limit(1).toList());
        }

        String defaultModelId = models.stream()
                .filter(AiModelOptionDto::isEnabled)
                .filter(AiModelOptionDto::isDefault)
                .map(AiModelOptionDto::getId)
                .findFirst()
                .orElse(models.stream()
                        .filter(AiModelOptionDto::isEnabled)
                        .map(AiModelOptionDto::getId)
                        .findFirst()
                        .orElse(AiModelConstants.DEFAULT_MODEL_ID));

        boolean isAdmin = RoleUtil.isAdmin(jwt);
        return AiModelCatalogResponseDto.builder()
                .defaultModelId(defaultModelId)
                .modelSelectionEnabled(modelSelectionEnabled)
                .userLimit(teacherAiLimitService.statusForTeacher(jwt.getSubject(), isAdmin))
                .models(models)
                .build();
    }

    @Transactional(readOnly = true)
    public AiModel resolveModelForGeneration(Jwt jwt, String modelId, String capability) {
        requireTeacherOrAdmin(jwt);
        teacherAiLimitService.assertWithinUserQuota(jwt.getSubject(), RoleUtil.isAdmin(jwt));
        String normalizedCapability = normalizeCapability(capability);
        String effectiveModelId = !aiFeatureProperties.isModelSelectionEnabled()
                || modelId == null
                || modelId.isBlank()
                ? AiModelConstants.DEFAULT_MODEL_ID
                : modelId.trim();
        String role = resolveCatalogRole(jwt);

        AiModel model = aiModelRepository.findById(effectiveModelId)
                .orElseThrow(() -> new AiModelException(
                        AiModelException.Code.INVALID_MODEL_ID,
                        HttpStatus.BAD_REQUEST,
                        "Unknown model id: " + effectiveModelId));

        if (!model.isSelectable()) {
            throw new AiModelException(
                    AiModelException.Code.MODEL_NOT_ALLOWED,
                    HttpStatus.FORBIDDEN,
                    "Model is not available for selection: " + effectiveModelId);
        }

        if (!model.getCapabilities().contains(normalizedCapability)) {
            throw new AiModelException(
                    AiModelException.Code.MODEL_NOT_ALLOWED,
                    HttpStatus.FORBIDDEN,
                    "Model does not support capability: " + normalizedCapability);
        }

        AiModelPolicy policy = aiModelPolicyRepository
                .findByModelIdAndAllowedRoleAndCapability(model.getId(), role, normalizedCapability)
                .orElseThrow(() -> new AiModelException(
                        AiModelException.Code.MODEL_NOT_ALLOWED,
                        HttpStatus.FORBIDDEN,
                        "Model is not allowed for your role: " + effectiveModelId));

        Availability availability = resolveAvailability(jwt.getSubject(), role, model, policy);
        if (!availability.selectable()) {
            if (availability.code() == AiModelException.Code.QUOTA_EXCEEDED) {
                aiGenerationMetricsService.recordQuotaBlocked(model.getId(), role);
            }
            throw new AiModelException(
                    availability.code(),
                    availability.httpStatus(),
                    availability.message());
        }

        return model;
    }

    @Transactional(readOnly = true)
    public Optional<AiModelPricing> findActivePricing(String modelId, Instant at) {
        return aiModelPricingRepository
                .findActivePricingCandidates(modelId, at, PageRequest.of(0, 1))
                .stream()
                .findFirst();
    }

    private AiModelOptionDto toOptionDto(String teacherId, AiModel model, AiModelPolicy policy) {
        Availability availability = resolveAvailability(teacherId, policy.getAllowedRole(), model, policy);
        AiModelPriceHintDto priceHint = findActivePricing(model.getId(), Instant.now())
                .map(this::toPriceHint)
                .orElse(null);

        AiQuotaService.QuotaEvaluation quotaEvaluation = aiQuotaService.evaluate(teacherId, model, policy);
        AiModelQuotaDto quota = aiQuotaService.toQuotaDto(quotaEvaluation);

        return AiModelOptionDto.builder()
                .id(model.getId())
                .displayName(model.getDisplayName())
                .provider(model.getProvider())
                .tier(model.getTier())
                .description(model.getDescription())
                .contextWindowTokens(model.getContextWindowTokens())
                .capabilities(List.copyOf(model.getCapabilities()))
                .isDefault(model.isDefault())
                .enabled(availability.selectable())
                .unavailableReason(availability.selectable() ? null : availability.message())
                .priceHint(priceHint)
                .quota(quota)
                .build();
    }

    private AiModelPriceHintDto toPriceHint(AiModelPricing pricing) {
        return AiModelPriceHintDto.builder()
                .inputPer1MTokensMicros(pricing.getInputPricePerMillionMicros())
                .outputPer1MTokensMicros(pricing.getOutputPricePerMillionMicros())
                .currency(pricing.getCurrency())
                .build();
    }

    private Availability resolveAvailability(
            String teacherId, String role, AiModel model, AiModelPolicy policy) {
        if (!model.isEnabled()) {
            String reason = model.getDisabledReason() != null && !model.getDisabledReason().isBlank()
                    ? model.getDisabledReason()
                    : "Model is disabled";
            return Availability.blocked(
                    AiModelException.Code.MODEL_DISABLED,
                    HttpStatus.FORBIDDEN,
                    reason);
        }

        if (!isProviderEnabled(model.getProvider())) {
            return Availability.blocked(
                    AiModelException.Code.MODEL_DISABLED,
                    HttpStatus.FORBIDDEN,
                    providerUnavailableMessage(model.getProvider()));
        }

        TeacherAiLimitService.UserQuotaEvaluation userQuota =
                teacherAiLimitService.evaluate(teacherId, "admin".equals(role));
        if (!userQuota.unlimited() && userQuota.blocked()) {
            return Availability.blocked(
                    AiModelException.Code.QUOTA_EXCEEDED,
                    HttpStatus.TOO_MANY_REQUESTS,
                    userQuota.blockReason());
        }

        AiQuotaService.QuotaEvaluation quota = aiQuotaService.evaluate(teacherId, model, policy);
        if (quota.blocked()) {
            return Availability.blocked(
                    AiModelException.Code.QUOTA_EXCEEDED,
                    HttpStatus.TOO_MANY_REQUESTS,
                    quota.blockReason());
        }

        return Availability.available();
    }

    private boolean isProviderEnabled(String provider) {
        if (provider == null) {
            return false;
        }
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "google" -> aiProviderProperties.getGoogle().isEnabled();
            case "openai" -> aiProviderProperties.getOpenai().isEnabled();
            default -> false;
        };
    }

    private static String providerUnavailableMessage(String provider) {
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "openai" -> "OpenAI provider is not configured";
            case "google" -> "Google provider is not configured";
            default -> "Provider is not configured: " + provider;
        };
    }

    private static void requireTeacherOrAdmin(Jwt jwt) {
        if (!RoleUtil.isTeacher(jwt) && !RoleUtil.isAdmin(jwt)) {
            throw new AccessDeniedException("Only teachers and admins can access AI model catalog");
        }
    }

    private static String resolveCatalogRole(Jwt jwt) {
        if (RoleUtil.isAdmin(jwt)) {
            return "admin";
        }
        return "teacher";
    }

    private static String normalizeCapability(String capability) {
        if (capability == null || capability.isBlank()) {
            return AiModelConstants.CAPABILITY_COURSE_GENERATION;
        }
        return capability.trim().toLowerCase(Locale.ROOT);
    }

    private record Availability(
            boolean selectable,
            AiModelException.Code code,
            HttpStatus httpStatus,
            String message) {

        static Availability available() {
            return new Availability(true, null, null, null);
        }

        static Availability blocked(AiModelException.Code code, HttpStatus status, String message) {
            return new Availability(false, code, status, message);
        }
    }
}
