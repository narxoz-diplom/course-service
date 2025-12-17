package com.microservices.courseservice.repository;

import com.microservices.courseservice.model.Video;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VideoRepository extends JpaRepository<Video, Long> {
    List<Video> findByLessonId(Long lessonId);
    List<Video> findByLessonIdOrderByOrderNumber(Long lessonId);
}

