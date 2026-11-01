package com.pylearn.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CatalogService serves and mutates all curriculum chapters, practical programs,
 * exam questions, output challenges, viva flashcards, IDE templates, and cheat sheet tables.
 *
 * Designed as a clean data-access service layer so backing storage can be swapped
 * directly to Spring Data JPA repositories / SQL database tables.
 */
@Service
public class CatalogService {

    private final ObjectMapper objectMapper;
    private Map<String, Object> catalogData = new LinkedHashMap<>();
    private Map<String, Object> curriculumData = new LinkedHashMap<>();
    private final Path writableCatalogPath = Paths.get("src", "main", "resources", "portal-catalog.json");
    private final Path writableCurriculumPath = Paths.get("src", "main", "resources", "curriculum-lessons.json");

    public CatalogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public synchronized void initCatalog() throws IOException {
        if (Files.exists(writableCatalogPath)) {
            catalogData = objectMapper.readValue(writableCatalogPath.toFile(), new TypeReference<Map<String, Object>>() {});
        } else {
            try (InputStream in = new ClassPathResource("portal-catalog.json").getInputStream()) {
                catalogData = objectMapper.readValue(in, new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {}
        }

        if (Files.exists(writableCurriculumPath)) {
            curriculumData = objectMapper.readValue(writableCurriculumPath.toFile(), new TypeReference<Map<String, Object>>() {});
        } else {
            try (InputStream in = new ClassPathResource("curriculum-lessons.json").getInputStream()) {
                curriculumData = objectMapper.readValue(in, new TypeReference<Map<String, Object>>() {});
            } catch (Exception ignored) {}
        }
    }

    private synchronized void persistCatalog() {
        try {
            File target = writableCatalogPath.toFile();
            if (target.getParentFile() != null) {
                target.getParentFile().mkdirs();
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target, catalogData);
        } catch (IOException ignored) {
        }
    }

    public synchronized Map<String, Object> getFullCatalog() {
        return catalogData;
    }

    public synchronized Map<String, Object> getCurriculum() {
        return curriculumData;
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> getLessonById(String lessonId) {
        Object rawLessons = curriculumData.get("lessons");
        if (rawLessons instanceof List<?> list) {
            for (Object obj : list) {
                if (obj instanceof Map<?, ?> m) {
                    if (lessonId.equals(String.valueOf(m.get("id")))) {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                        return copy;
                    }
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> getChapters(String classLevel) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) catalogData.getOrDefault("chapters", new ArrayList<>());
        if (classLevel == null || classLevel.isBlank() || "all".equalsIgnoreCase(classLevel)) {
            return list;
        }
        return list.stream()
                .filter(c -> classLevel.equalsIgnoreCase(String.valueOf(c.get("classLevel"))))
                .collect(Collectors.toList());
    }

    public synchronized Map<String, Object> getChapterById(String chapterId) {
        return getChapters(null).stream()
                .filter(c -> chapterId.equals(String.valueOf(c.get("id"))))
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> getPracticals(String classLevel, String category) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) catalogData.getOrDefault("practicals", new ArrayList<>());
        return list.stream()
                .filter(p -> (classLevel == null || classLevel.isBlank() || "all".equalsIgnoreCase(classLevel)
                        || classLevel.equalsIgnoreCase(String.valueOf(p.get("classLevel")))))
                .filter(p -> (category == null || category.isBlank() || "All".equalsIgnoreCase(category)
                        || category.equalsIgnoreCase(String.valueOf(p.get("category")))))
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> getExamQuestions(String classLevel) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) catalogData.getOrDefault("examQuestions", new ArrayList<>());
        if (classLevel == null || classLevel.isBlank() || "all".equalsIgnoreCase(classLevel)) {
            return list;
        }
        return list.stream()
                .filter(q -> classLevel.equalsIgnoreCase(String.valueOf(q.get("classLevel"))))
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> getOutputChallenges() {
        return (List<Map<String, Object>>) catalogData.getOrDefault("outputChallenges", new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> getVivaCards() {
        return (List<Map<String, Object>>) catalogData.getOrDefault("vivaCards", new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> getIdeTemplates() {
        return (List<Map<String, Object>>) catalogData.getOrDefault("ideTemplates", new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> getCheatSheet() {
        return (Map<String, Object>) catalogData.getOrDefault("cheatSheet", Collections.emptyMap());
    }

    /**
     * Dynamically builds the navigation menu and submenu tree from the server
     * catalog based on the user's authentication state (GUEST, STUDENT, TEACHER, ADMIN).
     */
    public synchronized List<Map<String, Object>> getNavigationMenuForRole(String role) {
        String cleanRole = (role == null || role.isBlank()) ? "STUDENT" : role.toUpperCase().trim();
        List<Map<String, Object>> items = new ArrayList<>();

        if ("ADMIN".equals(cleanRole)) {
            // ADMIN MENU: Full CMS, System Analytics, Master Curriculum, All Labs, Python Engine
            items.add(createMenuItem("dashboard", "Admin Command Center", "Admin", "/dashboard", "⚡", "Admin", null, false));
            items.add(createMenuItem("admin-studio", "Master Content Studio", "Studio", "/admin-studio", "🎨", "CRUD", null, false));
            items.add(createMenuItem("learn", "Curriculum Manager", "Curric", "/learn", "📖", null, null, false));
            items.add(createMenuItem("practical-lab", "Practical Lab Bank", "Labs", "/practical-lab", "🧪", null, null, false));
            items.add(createMenuItem("ide", "Python Engine (WS)", "IDE", "/ide", "💻", "Live", null, false));
            items.add(createMenuItem("exam-prep", "System Analytics", "Metrics", "/exam-prep", "📈", null, null, false));
            items.add(createMenuItem("cheatsheet", "CBSE Blueprint & Notes", "CBSE", "/cheatsheet", "📚", null, null, true));
            items.add(createMenuItem("help", "System Health & Config", "Health", "/cheatsheet", "⚙️", null, null, false));
        } else if ("TEACHER".equals(cleanRole)) {
            // TEACHER MENU: Overview, Curriculum & Question Studio, Lessons Review, Student Reports
            items.add(createMenuItem("dashboard", "Teacher Overview", "Dash", "/dashboard", "📊", "Teacher", null, false));
            items.add(createMenuItem("admin-studio", "Curriculum Studio", "Studio", "/admin-studio", "🎨", "Manage", null, false));
            items.add(createMenuItem("learn", "CBSE Lessons Review", "Lessons", "/learn", "📖", null, null, false));
            items.add(createMenuItem("practical-lab", "Practicals & Assignments", "Lab", "/practical-lab", "🧪", null, null, false));
            items.add(createMenuItem("ide", "Teacher Python Sandbox", "IDE", "/ide", "💻", null, null, false));
            items.add(createMenuItem("exam-prep", "Student Progress & Reports", "Reports", "/exam-prep", "📈", null, null, false));
            items.add(createMenuItem("cheatsheet", "Syllabus Notes & Blueprint", "CBSE", "/cheatsheet", "📚", null, null, true));
            items.add(createMenuItem("help", "Teacher Guides & Support", "Help", "/cheatsheet", "❓", null, null, false));
        } else {
            // STUDENT MENU: Focused on Learning, Practice Lab, Running Python, Progress, Resources
            items.add(createMenuItem("dashboard", "Dashboard", "Dash", "/dashboard", "📊", null, null, false));
            items.add(createMenuItem("learn", "Learn Python", "Learn", "/learn", "📖", null, null, false));
            items.add(createMenuItem("practical-lab", "Practice Lab", "Lab", "/practical-lab", "🧪", null, null, false));
            items.add(createMenuItem("ide", "Python IDE", "IDE", "/ide", "💻", null, null, false));
            items.add(createMenuItem("exam-prep", "My Progress", "Progress", "/exam-prep", "📈", null, null, false));
            items.add(createMenuItem("cheatsheet", "CBSE Resources", "CBSE", "/cheatsheet", "📚", null, null, true));
            items.add(createMenuItem("leaderboard", "Leaderboard", "Ranks", "/exam-prep", "🏆", null, null, false));
            items.add(createMenuItem("help", "Help & Support", "Help", "/cheatsheet", "❓", null, null, false));
        }

        return items;
    }

    private Map<String, Object> createMenuItem(
            String id,
            String label,
            String miniLabel,
            String route,
            String icon,
            String badge,
            Map<String, String> queryParams,
            boolean dividerBefore
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("label", label);
        map.put("miniLabel", miniLabel != null ? miniLabel : label);
        map.put("route", route);
        map.put("icon", icon);
        if (badge != null && !badge.isBlank()) {
            map.put("badge", badge);
        }
        if (queryParams != null && !queryParams.isEmpty()) {
            map.put("queryParams", queryParams);
        }
        if (dividerBefore) {
            map.put("dividerBefore", true);
        }
        return map;
    }

    // =========================================================================
    // Teacher & Admin Mutation Methods (Create / Update / Delete)
    // =========================================================================

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> saveChapter(Map<String, Object> chapterPayload) {
        List<Map<String, Object>> chapters = new ArrayList<>((List<Map<String, Object>>) catalogData.getOrDefault("chapters", new ArrayList<>()));
        String id = String.valueOf(chapterPayload.getOrDefault("id", "ch-" + System.currentTimeMillis()));
        chapterPayload.put("id", id);
        chapterPayload.putIfAbsent("topics", new ArrayList<>());
        chapterPayload.putIfAbsent("importantFormulasOrSyntax", new ArrayList<>());

        chapters.removeIf(c -> id.equals(String.valueOf(c.get("id"))));
        chapters.add(chapterPayload);
        catalogData.put("chapters", chapters);
        persistCatalog();
        return chapterPayload;
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> addTopicToChapter(String chapterId, Map<String, Object> topicPayload) {
        Map<String, Object> chapter = getChapterById(chapterId);
        if (chapter == null) return null;

        List<Map<String, Object>> topics = new ArrayList<>((List<Map<String, Object>>) chapter.getOrDefault("topics", new ArrayList<>()));
        topicPayload.putIfAbsent("id", "tp-" + System.currentTimeMillis());
        topics.add(topicPayload);
        chapter.put("topics", topics);
        persistCatalog();
        return chapter;
    }

    @SuppressWarnings("unchecked")
    public synchronized boolean deleteChapter(String chapterId) {
        List<Map<String, Object>> chapters = new ArrayList<>((List<Map<String, Object>>) catalogData.getOrDefault("chapters", new ArrayList<>()));
        boolean removed = chapters.removeIf(c -> chapterId.equals(String.valueOf(c.get("id"))));
        if (removed) {
            catalogData.put("chapters", chapters);
            persistCatalog();
        }
        return removed;
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> addExamQuestion(Map<String, Object> questionPayload) {
        List<Map<String, Object>> questions = new ArrayList<>((List<Map<String, Object>>) catalogData.getOrDefault("examQuestions", new ArrayList<>()));
        String id = String.valueOf(questionPayload.getOrDefault("id", "mcq-" + System.currentTimeMillis()));
        questionPayload.put("id", id);
        questions.add(questionPayload);
        catalogData.put("examQuestions", questions);
        persistCatalog();
        return questionPayload;
    }

    @SuppressWarnings("unchecked")
    public synchronized boolean deleteExamQuestion(String questionId) {
        List<Map<String, Object>> questions = new ArrayList<>((List<Map<String, Object>>) catalogData.getOrDefault("examQuestions", new ArrayList<>()));
        boolean removed = questions.removeIf(q -> questionId.equals(String.valueOf(q.get("id"))));
        if (removed) {
            catalogData.put("examQuestions", questions);
            persistCatalog();
        }
        return removed;
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> addPracticalProgram(Map<String, Object> practicalPayload) {
        List<Map<String, Object>> practicals = new ArrayList<>((List<Map<String, Object>>) catalogData.getOrDefault("practicals", new ArrayList<>()));
        String id = String.valueOf(practicalPayload.getOrDefault("id", "prac-" + System.currentTimeMillis()));
        practicalPayload.put("id", id);
        practicals.add(practicalPayload);
        catalogData.put("practicals", practicals);
        persistCatalog();
        return practicalPayload;
    }

    @SuppressWarnings("unchecked")
    public synchronized boolean deletePracticalProgram(String practicalId) {
        List<Map<String, Object>> practicals = new ArrayList<>((List<Map<String, Object>>) catalogData.getOrDefault("practicals", new ArrayList<>()));
        boolean removed = practicals.removeIf(p -> practicalId.equals(String.valueOf(p.get("id"))));
        if (removed) {
            catalogData.put("practicals", practicals);
            persistCatalog();
        }
        return removed;
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> addVivaCard(Map<String, Object> vivaPayload) {
        List<Map<String, Object>> cards = new ArrayList<>((List<Map<String, Object>>) catalogData.getOrDefault("vivaCards", new ArrayList<>()));
        String id = String.valueOf(vivaPayload.getOrDefault("id", "viva-" + System.currentTimeMillis()));
        vivaPayload.put("id", id);
        cards.add(vivaPayload);
        catalogData.put("vivaCards", cards);
        persistCatalog();
        return vivaPayload;
    }

    @SuppressWarnings("unchecked")
    public synchronized boolean deleteVivaCard(String cardId) {
        List<Map<String, Object>> cards = new ArrayList<>((List<Map<String, Object>>) catalogData.getOrDefault("vivaCards", new ArrayList<>()));
        boolean removed = cards.removeIf(v -> cardId.equals(String.valueOf(v.get("id"))));
        if (removed) {
            catalogData.put("vivaCards", cards);
            persistCatalog();
        }
        return removed;
    }
}
