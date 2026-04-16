package com.microservices.courseservice.service;

import com.microservices.courseservice.dto.CreateNewsRequest;
import com.microservices.courseservice.dto.NewsArticleDto;
import com.microservices.courseservice.dto.UpdateNewsRequest;
import com.microservices.courseservice.model.NewsArticle;
import com.microservices.courseservice.repository.NewsArticleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NewsArticleService {

    private final NewsArticleRepository repository;

    @Transactional(readOnly = true)
    public List<NewsArticleDto> listAll() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(NewsArticle::getPublishedAt).reversed())
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public NewsArticleDto getById(Long id) {
        NewsArticle a = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("News not found"));
        return toDto(a);
    }

    @Transactional
    public NewsArticleDto create(CreateNewsRequest request, Jwt jwt) {
        NewsArticle a = NewsArticle.builder()
                .title(trimRequired(request.getTitle(), "title"))
                .shortDescription(trimRequired(request.getShortDescription(), "shortDescription"))
                .content(trimRequired(request.getContent(), "content"))
                .authorId(jwt.getSubject())
                .authorName(extractAuthorName(jwt))
                .imageFileId(request.getImageFileId())
                .build();
        return toDto(repository.save(a));
    }

    @Transactional
    public NewsArticleDto update(Long id, UpdateNewsRequest request) {
        NewsArticle a = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("News not found"));
        a.setTitle(trimRequired(request.getTitle(), "title"));
        a.setShortDescription(trimRequired(request.getShortDescription(), "shortDescription"));
        a.setContent(trimRequired(request.getContent(), "content"));

        if (request.isRemoveImage()) {
            a.setImageFileId(null);
        } else if (request.getImageFileId() != null) {
            a.setImageFileId(request.getImageFileId());
        }

        return toDto(repository.save(a));
    }

    @Transactional
    public void delete(Long id) {
        NewsArticle a = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("News not found"));
        repository.delete(a);
    }

    private NewsArticleDto toDto(NewsArticle a) {
        String imageUrl = (a.getImageFileId() == null)
                ? null
                : ("/api/files/" + a.getImageFileId() + "/content");
        return NewsArticleDto.builder()
                .id(a.getId())
                .title(a.getTitle())
                .shortDescription(a.getShortDescription())
                .content(a.getContent())
                .publishedAt(a.getPublishedAt())
                .authorId(a.getAuthorId())
                .authorName(a.getAuthorName())
                .imageUrl(imageUrl)
                .build();
    }

    private static String extractAuthorName(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        if (name != null && !name.isBlank()) return name;
        String preferred = jwt.getClaimAsString("preferred_username");
        if (preferred != null && !preferred.isBlank()) return preferred;
        String email = jwt.getClaimAsString("email");
        if (email != null && !email.isBlank()) return email;
        return jwt.getSubject();
    }

    private static String trimRequired(String v, String field) {
        if (v == null) throw new IllegalArgumentException(field + " is required");
        String s = v.trim();
        if (s.isBlank()) throw new IllegalArgumentException(field + " is required");
        return s;
    }
}

