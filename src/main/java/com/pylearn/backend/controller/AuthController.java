package com.pylearn.backend.controller;

import com.pylearn.backend.service.AuthUserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthUserService authUserService;

    public AuthController(AuthUserService authUserService) {
        this.authUserService = authUserService;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> credentials) {
        String username = credentials.get("username");
        String password = credentials.get("password");

        AuthUserService.AppUser user = authUserService.authenticate(username, password);
        if (user == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "Invalid username or password. Try student/student123, teacher/teacher123, or admin/admin123.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(err);
        }

        String token = authUserService.issueToken(user);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", token);
        response.put("user", authUserService.toPublicUserMap(user));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getCurrentUser(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        AuthUserService.AppUser user = authUserService.resolveToken(authHeader);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        return ResponseEntity.ok(authUserService.toPublicUserMap(user));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        authUserService.revokeToken(authHeader);
        return ResponseEntity.ok(Map.of("status", "LOGGED_OUT"));
    }

    @GetMapping("/demo-accounts")
    public ResponseEntity<List<Map<String, Object>>> getDemoAccounts() {
        return ResponseEntity.ok(authUserService.getDemoAccounts());
    }
}
