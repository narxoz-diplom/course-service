package com.microservices.courseservice.service.backfill;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class BackfillJobDto {
    private String jobId;
    private Long courseId;
    private BackfillJobStatus status;
    private String message;
    private int processed;
    private int total;
    private Instant startedAt;
    private Instant finishedAt;
    private Map<String, Object> result;
}

