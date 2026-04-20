package com.microservices.courseservice.service.backfill;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class BackfillLocalizationJobService {

    private final BackfillLocalizationWorker worker;

    private final ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2)
    );

    private final ConcurrentHashMap<String, JobState> jobs = new ConcurrentHashMap<>();

    public Map<String, Object> summary(Long courseId, Set<String> languages) {
        return worker.summary(courseId, languages);
    }

    public BackfillJobDto start(Long courseId, Set<String> languages) {
        String jobId = UUID.randomUUID().toString();
        JobState s = new JobState(jobId, courseId);
        s.languages = (languages == null || languages.isEmpty()) ? Set.of("kz", "en") : Set.copyOf(languages);
        jobs.put(jobId, s);

        Map<String, Object> summary = worker.summary(courseId, s.languages);
        int missingTotal = ((Number) summary.getOrDefault("missingTotal", 0)).intValue();
        if (missingTotal <= 0) {
            s.status = BackfillJobStatus.COMPLETED;
            s.startedAt = Instant.now();
            s.finishedAt = s.startedAt;
            s.total.set(1);
            s.processed.set(1);
            s.message = "Уже переведено";
            s.result = Map.of("summary", summary);
            return toDto(s);
        }

        executor.submit(() -> {
            s.status = BackfillJobStatus.RUNNING;
            s.startedAt = Instant.now();
            s.message = "Запущено…";
            try {
                Map<String, Object> result = worker.run(
                        courseId,
                        s.languages,
                        total -> s.total.set(Math.max(1, total)),
                        delta -> s.processed.addAndGet(Math.max(0, delta)),
                        msg -> s.message = msg
                );
                s.result = result;
                s.status = BackfillJobStatus.COMPLETED;
                s.message = "Готово";
            } catch (Exception e) {
                s.status = BackfillJobStatus.FAILED;
                s.message = e.getMessage() != null ? e.getMessage() : "Backfill failed";
                s.result = Map.of("error", s.message);
            } finally {
                s.finishedAt = Instant.now();
            }
        });

        return toDto(s);
    }

    public BackfillJobDto get(String jobId) {
        JobState s = jobs.get(jobId);
        if (s == null) return null;
        return toDto(s);
    }

    private static BackfillJobDto toDto(JobState s) {
        return BackfillJobDto.builder()
                .jobId(s.jobId)
                .courseId(s.courseId)
                .status(s.status)
                .message(s.message)
                .processed(s.processed.get())
                .total(s.total.get())
                .startedAt(s.startedAt)
                .finishedAt(s.finishedAt)
                .result(s.result)
                .build();
    }

    private static final class JobState {
        private final String jobId;
        private final Long courseId;
        private volatile Set<String> languages = Set.of("kz", "en");
        private volatile BackfillJobStatus status = BackfillJobStatus.PENDING;
        private volatile String message = "Ожидание…";
        private final AtomicInteger processed = new AtomicInteger(0);
        private final AtomicInteger total = new AtomicInteger(1);
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile Map<String, Object> result;

        private JobState(String jobId, Long courseId) {
            this.jobId = jobId;
            this.courseId = courseId;
        }
    }
}

