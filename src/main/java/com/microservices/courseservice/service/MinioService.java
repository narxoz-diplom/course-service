package com.microservices.courseservice.service;

import com.microservices.courseservice.config.MinioConfig;
import io.minio.*;
import io.minio.errors.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MinioService {

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    public void initializeBucket() {
        try {
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(minioConfig.getBucketName())
                    .build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(minioConfig.getBucketName())
                        .build());
                log.info("Bucket '{}' created successfully", minioConfig.getBucketName());
            }
        } catch (Exception e) {
            log.error("Error initializing bucket", e);
        }
    }

    public String uploadFile(MultipartFile file) throws IOException, ServerException, 
            InsufficientDataException, ErrorResponseException, NoSuchAlgorithmException, 
            InvalidKeyException, InvalidResponseException, XmlParserException, 
            InternalException {
        
        String objectName = UUID.randomUUID().toString() + "_" + file.getOriginalFilename();
        
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(minioConfig.getBucketName())
                .object(objectName)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType())
                .build());
        
        log.info("File uploaded successfully: {}", objectName);
        return objectName;
    }

    public InputStream downloadFile(String objectName) throws ServerException, 
            InsufficientDataException, ErrorResponseException, IOException, 
            NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, 
            XmlParserException, InternalException {
        
        return minioClient.getObject(GetObjectArgs.builder()
                .bucket(minioConfig.getBucketName())
                .object(objectName)
                .build());
    }

    public InputStream downloadFile(String objectName, long offset, long length) throws ServerException, 
            InsufficientDataException, ErrorResponseException, IOException, 
            NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, 
            XmlParserException, InternalException {
        
        return minioClient.getObject(GetObjectArgs.builder()
                .bucket(minioConfig.getBucketName())
                .object(objectName)
                .offset(offset)
                .length(length)
                .build());
    }

    public io.minio.StatObjectResponse getFileInfo(String objectName) throws ServerException, 
            InsufficientDataException, ErrorResponseException, IOException, 
            NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, 
            XmlParserException, InternalException {
        
        return minioClient.statObject(StatObjectArgs.builder()
                .bucket(minioConfig.getBucketName())
                .object(objectName)
                .build());
    }

    public void deleteFile(String objectName) throws ServerException, 
            InsufficientDataException, ErrorResponseException, IOException, 
            NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, 
            XmlParserException, InternalException {
        
        minioClient.removeObject(RemoveObjectArgs.builder()
                .bucket(minioConfig.getBucketName())
                .object(objectName)
                .build());
        
        log.info("File deleted successfully: {}", objectName);
    }
}

