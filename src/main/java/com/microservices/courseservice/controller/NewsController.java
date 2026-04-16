package com.microservices.courseservice.controller;

import com.microservices.courseservice.dto.CreateNewsRequest;
import com.microservices.courseservice.dto.NewsArticleDto;
import com.microservices.courseservice.dto.UpdateNewsRequest;
import com.microservices.courseservice.service.NewsArticleService;
import com.microservices.courseservice.util.RoleUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
public class NewsController {

    private final NewsArticleService service;

    @GetMapping
    public ResponseEntity<List<NewsArticleDto>> list(@AuthenticationPrincipal Jwt jwt) {
        requireAuthenticated(jwt);
        return ResponseEntity.ok(service.listAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<NewsArticleDto> getOne(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        requireAuthenticated(jwt);
        try {
            return ResponseEntity.ok(service.getById(id));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    @PostMapping
    public ResponseEntity<NewsArticleDto> create(
            @RequestBody CreateNewsRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        try {
            NewsArticleDto created = service.create(request, jwt);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<NewsArticleDto> update(
            @PathVariable Long id,
            @RequestBody UpdateNewsRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        try {
            return ResponseEntity.ok(service.update(id, request));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("not found")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        requireAdmin(jwt);
        try {
            service.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    private static void requireAuthenticated(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    private static void requireAdmin(Jwt jwt) {
        requireAuthenticated(jwt);
        if (!RoleUtil.isAdmin(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin only");
        }
    }
}

