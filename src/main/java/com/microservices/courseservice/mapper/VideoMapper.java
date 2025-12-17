package com.microservices.courseservice.mapper;

import com.microservices.courseservice.dto.VideoMetadataRequest;
import com.microservices.courseservice.model.Video;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = org.mapstruct.NullValuePropertyMappingStrategy.IGNORE)
public interface VideoMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "lesson", ignore = true)
    @Mapping(target = "uploadedAt", ignore = true)
    @Mapping(target = "processedAt", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "duration", source = "duration", defaultValue = "0")
    @Mapping(target = "orderNumber", source = "orderNumber", defaultValue = "0")
    Video toVideo(VideoMetadataRequest request);
}

