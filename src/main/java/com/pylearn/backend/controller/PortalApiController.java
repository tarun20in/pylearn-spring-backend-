package com.pylearn.backend.controller;

import com.pylearn.backend.service.AuthUserService;
import com.pylearn.backend.service.CatalogService;
import com.pylearn.backend.service.StudentProgressService;
import com.pylearn.backend.websocket.PythonExecutionWebSocketHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PortalApiController {

    private final CatalogService catalogService;
    private final StudentProgressService progressService;
    private final PythonExecutionWebSocketHandler wsHandler;
    private final AuthUserService authUserService;

    public PortalApiController(
            CatalogService catalogService,
            StudentProgressService progressService,
            PythonExecutionWebSocketHandler wsHandler,
            AuthUserService authUserService
    ) {
        this.catalogService = catalogService;
        this.progressService = progressService;
        this.wsHandler = wsHandler;
        this.authUserService = authUserService;
    }

    @GetMapping("/navigation/menu")
    public ResponseEntity<List<Map<String, Object>>> getNavigationMenu(
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        // Securely resolve role strictly from verified authentication token only
        AuthUserService.AppUser user = authUserService.resolveToken(authHeader);
        String role = user != null ? user.role() : "STUDENT";
        return ResponseEntity.ok(catalogService.getNavigationMenuForRole(role));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("backend", "Spring Boot 3.4 + Spring Security (Java Web + WebSocket)");
        status.put("wsEndpoint", "ws://localhost:8080/ws");
        status.put("activePythonProcesses", wsHandler.getActiveSessionCount());
        return ResponseEntity.ok(status);
    }

    // =========================================================================
    // Public / Student Read Endpoints
    // =========================================================================

    @GetMapping("/catalog")
    public ResponseEntity<Map<String, Object>> getFullCatalog() {
        return ResponseEntity.ok(catalogService.getFullCatalog());
    }

    @GetMapping("/curriculum")
    public ResponseEntity<Map<String, Object>> getCurriculum() {
        return ResponseEntity.ok(catalogService.getCurriculum());
    }

    @GetMapping("/chapters")
    public ResponseEntity<List<Map<String, Object>>> getChapters(
            @RequestParam(required = false) String classLevel
    ) {
        return ResponseEntity.ok(catalogService.getChapters(classLevel));
    }

    @GetMapping("/chapters/{chapterId}")
    public ResponseEntity<Map<String, Object>> getChapterById(@PathVariable String chapterId) {
        Map<String, Object> chapter = catalogService.getChapterById(chapterId);
        if (chapter == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(chapter);
    }

    @GetMapping("/practicals")
    public ResponseEntity<List<Map<String, Object>>> getPracticals(
            @RequestParam(required = false) String classLevel,
            @RequestParam(required = false) String category
    ) {
        return ResponseEntity.ok(catalogService.getPracticals(classLevel, category));
    }

    @GetMapping("/exams/questions")
    public ResponseEntity<List<Map<String, Object>>> getExamQuestions(
            @RequestParam(required = false) String classLevel
    ) {
        return ResponseEntity.ok(catalogService.getExamQuestions(classLevel));
    }

    @GetMapping("/exams/output-challenges")
    public ResponseEntity<List<Map<String, Object>>> getOutputChallenges() {
        return ResponseEntity.ok(catalogService.getOutputChallenges());
    }

    @GetMapping("/exams/viva-cards")
    public ResponseEntity<List<Map<String, Object>>> getVivaCards() {
        return ResponseEntity.ok(catalogService.getVivaCards());
    }

    @GetMapping("/ide/templates")
    public ResponseEntity<List<Map<String, Object>>> getIdeTemplates() {
        return ResponseEntity.ok(catalogService.getIdeTemplates());
    }

    @GetMapping("/cheatsheet")
    public ResponseEntity<Map<String, Object>> getCheatSheet() {
        return ResponseEntity.ok(catalogService.getCheatSheet());
    }

    // =========================================================================
    // Student Progress Endpoints
    // =========================================================================

    @GetMapping("/progress")
    public ResponseEntity<Map<String, Object>> getStudentProgress() {
        return ResponseEntity.ok(progressService.getProgressState());
    }

    @PostMapping("/progress/chapter/{chapterId}")
    public ResponseEntity<Map<String, Object>> toggleChapter(@PathVariable String chapterId) {
        return ResponseEntity.ok(progressService.toggleChapter(chapterId));
    }

    @PostMapping("/progress/practical/{practicalId}")
    public ResponseEntity<Map<String, Object>> togglePractical(@PathVariable String practicalId) {
        return ResponseEntity.ok(progressService.togglePractical(practicalId));
    }

    @PostMapping("/progress/exam")
    public ResponseEntity<Map<String, Object>> recordExam(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(progressService.recordExamAttempt(payload));
    }

    // =========================================================================
    // Teacher & Admin Content Management Endpoints (Protected by Spring Security)
    // =========================================================================

    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    @PostMapping("/admin/chapters")
    public ResponseEntity<Map<String, Object>> createOrUpdateChapter(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(catalogService.saveChapter(payload));
    }

    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    @PostMapping("/admin/chapters/{chapterId}/topics")
    public ResponseEntity<Map<String, Object>> addTopicToChapter(
            @PathVariable String chapterId,
            @RequestBody Map<String, Object> topicPayload
    ) {
        Map<String, Object> updated = catalogService.addTopicToChapter(chapterId, topicPayload);
        if (updated == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(updated);
    }

    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    @DeleteMapping("/admin/chapters/{chapterId}")
    public ResponseEntity<Map<String, Object>> deleteChapter(@PathVariable String chapterId) {
        boolean removed = catalogService.deleteChapter(chapterId);
        return ResponseEntity.ok(Map.of("deleted", removed, "id", chapterId));
    }

    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    @PostMapping("/admin/exams/questions")
    public ResponseEntity<Map<String, Object>> createExamQuestion(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(catalogService.addExamQuestion(payload));
    }

    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    @DeleteMapping("/admin/exams/questions/{questionId}")
    public ResponseEntity<Map<String, Object>> deleteExamQuestion(@PathVariable String questionId) {
        boolean removed = catalogService.deleteExamQuestion(questionId);
        return ResponseEntity.ok(Map.of("deleted", removed, "id", questionId));
    }

    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    @PostMapping("/admin/practicals")
    public ResponseEntity<Map<String, Object>> createPractical(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(catalogService.addPracticalProgram(payload));
    }

    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    @DeleteMapping("/admin/practicals/{practicalId}")
    public ResponseEntity<Map<String, Object>> deletePractical(@PathVariable String practicalId) {
        boolean removed = catalogService.deletePracticalProgram(practicalId);
        return ResponseEntity.ok(Map.of("deleted", removed, "id", practicalId));
    }

    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    @PostMapping("/admin/exams/viva-cards")
    public ResponseEntity<Map<String, Object>> createVivaCard(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(catalogService.addVivaCard(payload));
    }

    @PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")
    @DeleteMapping("/admin/exams/viva-cards/{cardId}")
    public ResponseEntity<Map<String, Object>> deleteVivaCard(@PathVariable String cardId) {
        boolean removed = catalogService.deleteVivaCard(cardId);
        return ResponseEntity.ok(Map.of("deleted", removed, "id", cardId));
    }
}
