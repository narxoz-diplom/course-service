package com.microservices.courseservice.dto;

import com.microservices.courseservice.model.Course;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CourseViewerResponse {
    private boolean preview;
    private Course course;
}
