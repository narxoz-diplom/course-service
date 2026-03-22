package com.microservices.courseservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncGenerationConfig {

    @Bean(name = "lessonGenerationExecutor")
    public Executor lessonGenerationExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(3);
        ex.setQueueCapacity(50);
        ex.setThreadNamePrefix("lesson-gen-");
        ex.initialize();
        return ex;
    }
}
