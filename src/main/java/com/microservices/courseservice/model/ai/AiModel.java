package com.microservices.courseservice.model.ai;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "ai_models")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiModel {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(name = "provider_model_id", nullable = false, length = 128)
    private String providerModelId;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 16)
    private String tier;

    @Column(name = "context_window_tokens")
    private Long contextWindowTokens;

    @Column(nullable = false)
    @Builder.Default
    private boolean selectable = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "disabled_reason", length = 512)
    private String disabledReason;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private boolean isDefault = false;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ai_model_capabilities", joinColumns = @JoinColumn(name = "model_id"))
    @Column(name = "capability", length = 64)
    @Builder.Default
    private Set<String> capabilities = new LinkedHashSet<>();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
