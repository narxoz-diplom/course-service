package com.microservices.courseservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@FeignClient(name = "auth-service", url = "${auth-service.url}")
public interface AuthServiceClient {

    @GetMapping("/api/auth/user/{userId}")
    Map<String, Object> getUserById(@PathVariable("userId") String userId);

    @PostMapping("/api/auth/users/resolve")
    List<Map<String, Object>> resolveUsers(@RequestBody Map<String, List<String>> body);
}
