package com.microservices.courseservice.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "news_articles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "short_description", nullable = false, length = 2000)
    private String shortDescription;

    @Lob
    @Column(nullable = false)
    private String content;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "author_id", nullable = false, length = 255)
    private String authorId;

    @Column(name = "author_name", nullable = false, length = 255)
    private String authorName;

    @Column(name = "image_file_id")
    private Long imageFileId;

    @PrePersist
    public void prePersist() {
        if (publishedAt == null) {
            publishedAt = Instant.now();
        }
    }
}

