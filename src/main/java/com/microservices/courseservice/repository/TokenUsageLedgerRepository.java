package com.microservices.courseservice.repository;

import com.microservices.courseservice.model.ai.TokenUsageLedger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TokenUsageLedgerRepository extends JpaRepository<TokenUsageLedger, Long> {

    List<TokenUsageLedger> findByGenerationRunIdOrderByCreatedAtAsc(String generationRunId);

    boolean existsByGenerationRunIdAndAttemptNumber(String generationRunId, int attemptNumber);
}
