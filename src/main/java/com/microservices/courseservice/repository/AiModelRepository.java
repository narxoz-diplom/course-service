package com.microservices.courseservice.repository;

import com.microservices.courseservice.model.ai.AiModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AiModelRepository extends JpaRepository<AiModel, String> {

    @Query("""
            SELECT DISTINCT m FROM AiModel m
            JOIN m.capabilities c
            WHERE m.selectable = true
              AND c = :capability
            ORDER BY m.sortOrder ASC, m.id ASC
            """)
    List<AiModel> findSelectableByCapability(@Param("capability") String capability);
}
