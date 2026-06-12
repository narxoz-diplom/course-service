package com.microservices.courseservice.repository;

import com.microservices.courseservice.model.ai.TeacherAiLimit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeacherAiLimitRepository extends JpaRepository<TeacherAiLimit, String> {
}
