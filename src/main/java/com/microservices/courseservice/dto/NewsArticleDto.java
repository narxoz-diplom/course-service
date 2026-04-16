package com.microservices.courseservice.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class NewsArticleDto {
    private Long id;
    private String title;
    private String shortDescription;
    private String content;
    private Instant publishedAt;
    private String authorId;
    private String authorName;
    private String imageUrl;
}

