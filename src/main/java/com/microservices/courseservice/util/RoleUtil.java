package com.microservices.courseservice.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

@Slf4j
public class RoleUtil {
    
    // Роли в Keycloak называются без префикса ROLE_ и в нижнем регистре
    private static final String ADMIN_ROLE = "admin";
    private static final String TEACHER_ROLE = "teacher";
    private static final String CLIENT_ROLE = "client";

    public static List<String> getRoles(Jwt jwt) {
        if (jwt == null) {
            return List.of();
        }
        
        List<String> allRoles = new java.util.ArrayList<>();
        
        // 1. Проверяем роли из realm_access (realm roles)
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof java.util.Map) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> realmAccessMap = (java.util.Map<String, Object>) realmAccess;
            Object rolesObj = realmAccessMap.get("roles");
            if (rolesObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<String> roles = (List<String>) rolesObj;
                allRoles.addAll(roles);
                log.info("DEBUG: Found roles in realm_access: {}", roles);
            }
        }
        
        // 2. Проверяем роли из resource_access (client roles)
        Object resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess instanceof java.util.Map) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> resourceAccessMap = (java.util.Map<String, Object>) resourceAccess;
            
            // Проверяем роли для клиента microservices-client
            Object clientAccess = resourceAccessMap.get("microservices-client");
            if (clientAccess instanceof java.util.Map) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> clientAccessMap = (java.util.Map<String, Object>) clientAccess;
                Object clientRolesObj = clientAccessMap.get("roles");
                if (clientRolesObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> clientRoles = (List<String>) clientRolesObj;
                    allRoles.addAll(clientRoles);
                    log.info("DEBUG: Found roles in resource_access.microservices-client: {}", clientRoles);
                }
            }
        }
        
        if (!allRoles.isEmpty()) {
            log.info("DEBUG: All roles found: {}", allRoles);
            return allRoles;
        }
        
        // Альтернативный способ - проверка напрямую из claims
        Object rolesClaim = jwt.getClaim("roles");
        if (rolesClaim instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) rolesClaim;
            log.info("DEBUG: Found roles in roles claim: {}", roles);
            return roles;
        }
        
        log.warn("DEBUG: No roles found in token. All claims: {}", String.valueOf(jwt.getClaims().keySet()));
        log.warn("DEBUG: Full realm_access claim: {}", String.valueOf(jwt.getClaim("realm_access")));
        log.warn("DEBUG: Full resource_access claim: {}", String.valueOf(jwt.getClaim("resource_access")));
        return List.of();
    }

    public static boolean hasRole(Jwt jwt, String role) {
        List<String> roles = getRoles(jwt);
        if (roles.isEmpty()) {
            return false;
        }
        
        // Проверяем роль без учета регистра и с/без префикса ROLE_
        String roleLower = role.toLowerCase().replace("role_", "");
        return roles.stream()
                .anyMatch(r -> r.equalsIgnoreCase(role) || 
                             r.equalsIgnoreCase(roleLower) ||
                             r.equalsIgnoreCase(role.replace("ROLE_", "")));
    }

    public static boolean isAdmin(Jwt jwt) {
        List<String> roles = getRoles(jwt);
        return roles.stream().anyMatch(r -> r.equalsIgnoreCase(ADMIN_ROLE));
    }

    public static boolean isTeacher(Jwt jwt) {
        List<String> roles = getRoles(jwt);
        return roles.stream().anyMatch(r -> r.equalsIgnoreCase(TEACHER_ROLE));
    }

    public static boolean isClient(Jwt jwt) {
        List<String> roles = getRoles(jwt);
        return roles.stream().anyMatch(r -> r.equalsIgnoreCase(CLIENT_ROLE));
    }

    public static boolean canUpload(Jwt jwt) {
        return isAdmin(jwt) || isTeacher(jwt);
    }

    public static boolean canDelete(Jwt jwt) {
        return isAdmin(jwt);
    }

    public static boolean canView(Jwt jwt) {
        return isAdmin(jwt) || isTeacher(jwt) || isClient(jwt);
    }
}

