package com.microservices.courseservice.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UpcomingTestDeadlineDto {
    private Long courseId;
    private String courseTitle;
    private String courseTitleKz;
    private String courseTitleEn;
    private Long testId;
    private String testTitle;
    private String testTitleKz;
    private String testTitleEn;
    private String dueAt;
}

