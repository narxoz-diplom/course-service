package com.microservices.courseservice.repository;

import com.microservices.courseservice.model.ai.AiModelPricing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

public interface AiModelPricingRepository extends JpaRepository<AiModelPricing, Long> {

    @Query("""
            SELECT p FROM AiModelPricing p
            WHERE p.modelId = :modelId
              AND p.effectiveFrom <= :at
              AND (p.effectiveTo IS NULL OR p.effectiveTo > :at)
            ORDER BY p.effectiveFrom DESC
            """)
    List<AiModelPricing> findActivePricingCandidates(
            @Param("modelId") String modelId, @Param("at") Instant at, Pageable pageable);
}
