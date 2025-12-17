package com.microservices.courseservice.mapper;

import com.microservices.courseservice.model.Course;
import org.mapstruct.Mapper;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface CourseMapper {

    void updateCourseFromSource(@MappingTarget Course target, Course source);
}

