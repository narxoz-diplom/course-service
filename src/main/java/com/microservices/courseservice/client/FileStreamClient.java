package com.microservices.courseservice.client;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Set;

@Component
public class FileStreamClient {

    private static final Set<String> FORWARDED_HEADERS = Set.of(
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.CONTENT_LENGTH,
            HttpHeaders.CONTENT_RANGE,
            HttpHeaders.ACCEPT_RANGES
    );

    private final RestTemplate restTemplate;
    private final String fileServiceUrl;

    public FileStreamClient(@Value("${file-service.url}") String fileServiceUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setBufferRequestBody(false);
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(0);
        this.restTemplate = new RestTemplate(factory);
        this.fileServiceUrl = fileServiceUrl;
    }

    public void streamVideo(
            String objectName,
            String rangeHeader,
            String bearerToken,
            HttpServletResponse servletResponse) throws IOException {
        String url = UriComponentsBuilder.fromHttpUrl(fileServiceUrl)
                .path("/api/files/videos/stream")
                .queryParam("objectName", objectName)
                .toUriString();

        restTemplate.execute(
                url,
                HttpMethod.GET,
                request -> {
                    request.getHeaders().setBearerAuth(bearerToken);
                    if (rangeHeader != null && !rangeHeader.isBlank()) {
                        request.getHeaders().set(HttpHeaders.RANGE, rangeHeader);
                    }
                },
                clientHttpResponse -> {
                    servletResponse.setStatus(clientHttpResponse.getStatusCode().value());
                    clientHttpResponse.getHeaders().forEach((name, values) -> {
                        if (FORWARDED_HEADERS.contains(name)) {
                            values.forEach(value -> servletResponse.addHeader(name, value));
                        }
                    });
                    try (var inputStream = clientHttpResponse.getBody()) {
                        inputStream.transferTo(servletResponse.getOutputStream());
                    }
                    servletResponse.flushBuffer();
                    return null;
                }
        );
    }
}
