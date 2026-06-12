package com.microservices.courseservice.model.ai;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "ai_model_policies",
        uniqueConstraints = @UniqueConstraint(columnNames = {"model_id", "allowed_role", "capability"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiModelPolicy {

    @Id
    @GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_id", nullable = false, length = 64)
    private String modelId;

    @Column(name = "allowed_role", nullable = false, length = 32)
    private String allowedRole;

    @Column(nullable = false, length = 64)
    private String capability;

    @Column(name = "monthly_token_quota")
    private Long monthlyTokenQuota;

    @Column(name = "daily_token_quota")
    private Long dailyTokenQuota;
}
