package com.pylearn.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class StudentProgressService {

    private final ObjectMapper objectMapper;
    private final Path storagePath = Path.of("data", "student-progress.json");

    private final Set<String> completedChapters = new LinkedHashSet<>();
    private final Set<String> completedPracticals = new LinkedHashSet<>();
    private final List<Map<String, Object>> examAttempts = new ArrayList<>();

    public StudentProgressService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public synchronized void loadFromDisk() {
        try {
            if (Files.exists(storagePath)) {
                Map<?, ?> data = objectMapper.readValue(storagePath.toFile(), Map.class);
                if (data.get("completedChapters") instanceof List<?> chList) {
                    chList.forEach(item -> completedChapters.add(String.valueOf(item)));
                }
                if (data.get("completedPracticals") instanceof List<?> prList) {
                    prList.forEach(item -> completedPracticals.add(String.valueOf(item)));
                }
                if (data.get("examAttempts") instanceof List<?> exList) {
                    for (Object obj : exList) {
                        if (obj instanceof Map<?, ?> m) {
                            Map<String, Object> copy = new LinkedHashMap<>();
                            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                            examAttempts.add(copy);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized Map<String, Object> getProgressState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("completedChapters", new ArrayList<>(completedChapters));
        state.put("completedPracticals", new ArrayList<>(completedPracticals));
        state.put("examAttempts", new ArrayList<>(examAttempts));
        return state;
    }

    public synchronized Map<String, Object> toggleChapter(String chapterId) {
        if (completedChapters.contains(chapterId)) {
            completedChapters.remove(chapterId);
        } else {
            completedChapters.add(chapterId);
        }
        saveToDisk();
        return getProgressState();
    }

    public synchronized Map<String, Object> togglePractical(String practicalId) {
        if (completedPracticals.contains(practicalId)) {
            completedPracticals.remove(practicalId);
        } else {
            completedPracticals.add(practicalId);
        }
        saveToDisk();
        return getProgressState();
    }

    public synchronized Map<String, Object> recordExamAttempt(Map<String, Object> payload) {
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("id", "exam-" + System.currentTimeMillis());
        attempt.put("date", LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a")));
        attempt.put("title", payload.getOrDefault("title", "CBSE Mock Test"));
        attempt.put("classLevel", payload.getOrDefault("classLevel", "all"));
        attempt.put("score", payload.getOrDefault("score", 0));
        attempt.put("total", payload.getOrDefault("total", 0));
        attempt.put("percentage", payload.getOrDefault("percentage", 0));

        examAttempts.add(0, attempt);
        if (examAttempts.size() > 20) {
            examAttempts.subList(20, examAttempts.size()).clear();
        }
        saveToDisk();
        return getProgressState();
    }

    private void saveToDisk() {
        try {
            Files.createDirectories(storagePath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(storagePath.toFile(), getProgressState());
        } catch (IOException ignored) {
        }
    }
}
