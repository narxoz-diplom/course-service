package com.microservices.courseservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@FeignClient(name = "file-service", url = "${file-service.url}")
public interface FileServiceClient {

    @PostMapping(value = "/api/files/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> uploadFile(@RequestPart("file") MultipartFile file,
                                    @RequestHeader("Authorization") String authorization);

    @PostMapping(value = "/api/files/upload-to-lesson", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> uploadFileToLesson(@RequestPart("file") MultipartFile file,
                                            @RequestParam("lessonId") Long lessonId,
                                            @RequestHeader("Authorization") String authorization);

    @GetMapping("/api/files/lesson/{lessonId}")
    java.util.List<Map<String, Object>> getFilesByLessonId(@PathVariable("lessonId") Long lessonId,
                                                             @RequestHeader("Authorization") String authorization);

    @GetMapping("/api/courses/videos/{objectName}/stream")
    org.springframework.core.io.Resource streamVideo(@PathVariable("objectName") String objectName,
                                                      @RequestHeader(value = "Range", required = false) String rangeHeader);
}

