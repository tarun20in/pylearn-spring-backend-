package com.pylearn.backend.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AuthUserService manages role-based users (STUDENT, TEACHER, ADMIN) and
 * token issuance/validation for Spring Security.
 * Ready to be backed by a JPA UserRepository when a SQL database is connected.
 */
@Service
public class AuthUserService {

    public record AppUser(
            String id,
            String username,
            String password,
            String fullName,
            String role,       // "STUDENT", "TEACHER", "ADMIN"
            String badgeTitle,
            String classLevel,
            List<String> permissions
    ) {}

    private final Map<String, AppUser> usersByUsername = new LinkedHashMap<>();
    private final Map<String, AppUser> activeTokens = new ConcurrentHashMap<>();

    public AuthUserService() {
        seedDefaultUsers();
    }

    private void seedDefaultUsers() {
        register(new AppUser(
                "usr-student-1",
                "student",
                "student123",
                "Aarav Sharma (Student)",
                "STUDENT",
                "CBSE Class 12 Student",
                "class12",
                List.of("READ_CURRICULUM", "RUN_IDE", "ATTEMPT_EXAM", "TRACK_PROGRESS")
        ));

        register(new AppUser(
                "usr-teacher-1",
                "teacher",
                "teacher123",
                "Prof. Rajesh Verma (PGT Computer Science)",
                "TEACHER",
                "CBSE PGT Teacher",
                "all",
                List.of("READ_CURRICULUM", "RUN_IDE", "ATTEMPT_EXAM", "MANAGE_CURRICULUM", "MANAGE_EXAMS", "MANAGE_PRACTICALS")
        ));

        register(new AppUser(
                "usr-admin-1",
                "admin",
                "admin123",
                "Dr. Meera Krishnan (Academic Admin)",
                "ADMIN",
                "Portal Administrator",
                "all",
                List.of("READ_CURRICULUM", "RUN_IDE", "ATTEMPT_EXAM", "MANAGE_CURRICULUM", "MANAGE_EXAMS", "MANAGE_PRACTICALS", "MANAGE_USERS")
        ));
    }

    private void register(AppUser user) {
        usersByUsername.put(user.username().toLowerCase(), user);
        // Deterministic token so restarts keep valid dev tokens working
        String deterministicToken = createDeterministicToken(user);
        activeTokens.put(deterministicToken, user);
    }

    private String createDeterministicToken(AppUser user) {
        String raw = "pylearn-sec:" + user.username() + ":" + user.role();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public AppUser authenticate(String username, String password) {
        if (username == null || password == null) return null;
        AppUser user = usersByUsername.get(username.trim().toLowerCase());
        if (user != null && user.password().equals(password)) {
            return user;
        }
        return null;
    }

    public String issueToken(AppUser user) {
        String token = createDeterministicToken(user);
        activeTokens.put(token, user);
        return token;
    }

    public AppUser resolveToken(String token) {
        if (token == null || token.isBlank()) return null;
        String clean = token.startsWith("Bearer ") ? token.substring(7).trim() : token.trim();
        return activeTokens.get(clean);
    }

    public void revokeToken(String token) {
        if (token == null || token.isBlank()) return;
        String clean = token.startsWith("Bearer ") ? token.substring(7).trim() : token.trim();
        activeTokens.remove(clean);
    }

    public Map<String, Object> toPublicUserMap(AppUser user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.id());
        map.put("username", user.username());
        map.put("fullName", user.fullName());
        map.put("role", user.role());
        map.put("badgeTitle", user.badgeTitle());
        map.put("classLevel", user.classLevel());
        map.put("permissions", user.permissions());
        return map;
    }

    public List<Map<String, Object>> getDemoAccounts() {
        return usersByUsername.values().stream().map(u -> {
            Map<String, Object> m = toPublicUserMap(u);
            m.put("demoPassword", u.password());
            return m;
        }).toList();
    }
}
