package com.microservices.courseservice.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

@Slf4j
public class RoleUtil {
    
    private static final String ADMIN_ROLE = "admin";
    private static final String TEACHER_ROLE = "teacher";
    private static final String CLIENT_ROLE = "client";

    public static List<String> getRoles(Jwt jwt) {
        if (jwt == null) {
            return List.of();
        }
        
        List<String> allRoles = new java.util.ArrayList<>();
        
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof java.util.Map) {
            java.util.Map<String, Object> realmAccessMap = (java.util.Map<String, Object>) realmAccess;
            Object rolesObj = realmAccessMap.get("roles");
            if (rolesObj instanceof List) {
                List<String> roles = (List<String>) rolesObj;
                allRoles.addAll(roles);
                log.info("DEBUG: Found roles in realm_access: {}", roles);
            }
        }
        
        Object resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess instanceof java.util.Map) {
            java.util.Map<String, Object> resourceAccessMap = (java.util.Map<String, Object>) resourceAccess;
            
            Object clientAccess = resourceAccessMap.get("microservices-client");
            if (clientAccess instanceof java.util.Map) {
                java.util.Map<String, Object> clientAccessMap = (java.util.Map<String, Object>) clientAccess;
                Object clientRolesObj = clientAccessMap.get("roles");
                if (clientRolesObj instanceof List) {
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
        
        Object rolesClaim = jwt.getClaim("roles");
        if (rolesClaim instanceof List) {
            List<String> roles = (List<String>) rolesClaim;
            log.info("DEBUG: Found roles in roles claim: {}", roles);
            return roles;
        }
        
        log.warn("DEBUG: No roles found in token. All claims: {}", jwt.getClaims().keySet());
        log.warn("DEBUG: Full realm_access claim: {}", String.valueOf(jwt.getClaim("realm_access")));
        log.warn("DEBUG: Full resource_access claim: {}", String.valueOf(jwt.getClaim("resource_access")));
        return List.of();
    }

    public static boolean isAdmin(Jwt jwt) {
        return getRoles(jwt).stream().anyMatch(RoleUtil::matchesAdmin);
    }

    public static boolean isTeacher(Jwt jwt) {
        return getRoles(jwt).stream().anyMatch(RoleUtil::matchesTeacher);
    }

    public static boolean isClient(Jwt jwt) {
        return getRoles(jwt).stream().anyMatch(RoleUtil::matchesClient);
    }

    private static boolean matchesAdmin(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String r = role.trim();
        return ADMIN_ROLE.equalsIgnoreCase(r) || "ROLE_ADMIN".equalsIgnoreCase(r);
    }

    private static boolean matchesTeacher(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String r = role.trim();
        return TEACHER_ROLE.equalsIgnoreCase(r) || "ROLE_TEACHER".equalsIgnoreCase(r);
    }

    private static boolean matchesClient(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String r = role.trim();
        return CLIENT_ROLE.equalsIgnoreCase(r) || "ROLE_CLIENT".equalsIgnoreCase(r);
    }

    public static boolean canUpload(Jwt jwt) {
        return isAdmin(jwt) || isTeacher(jwt);
    }

    public static boolean canView(Jwt jwt) {
        return jwt != null;
    }

    public static String getEmail(Jwt jwt) {
        if (jwt == null) return null;
        String email = jwt.getClaimAsString("email");
        if (email != null && !email.isBlank()) return email.toLowerCase().trim();
        String preferred = jwt.getClaimAsString("preferred_username");
        if (preferred != null && preferred.contains("@")) return preferred.toLowerCase().trim();
        return null;
    }

}

