package com.microservices.courseservice.mapper;

import com.microservices.courseservice.model.Course;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface CourseMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "instructorId", ignore = true)
    @Mapping(target = "enrolledStudents", ignore = true)
    @Mapping(target = "participantDisplayLabels", ignore = true)
    @Mapping(target = "allowedEmails", ignore = true)
    @Mapping(target = "lessons", ignore = true)
    @Mapping(target = "tests", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateCourseFromSource(@MappingTarget Course target, Course source);
}

