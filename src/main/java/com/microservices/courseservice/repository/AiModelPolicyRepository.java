package com.microservices.courseservice.repository;

import com.microservices.courseservice.model.ai.AiModelPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiModelPolicyRepository extends JpaRepository<AiModelPolicy, Long> {

    List<AiModelPolicy> findByModelIdAndCapability(String modelId, String capability);

    Optional<AiModelPolicy> findByModelIdAndAllowedRoleAndCapability(
            String modelId, String allowedRole, String capability);

    List<AiModelPolicy> findByAllowedRoleAndCapability(String allowedRole, String capability);
}
