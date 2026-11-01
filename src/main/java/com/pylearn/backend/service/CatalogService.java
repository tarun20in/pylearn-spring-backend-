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
        boolean isGuest = role == null || role.isBlank() || "GUEST".equalsIgnoreCase(role);
        boolean isTeacherOrAdmin = "TEACHER".equalsIgnoreCase(role) || "ADMIN".equalsIgnoreCase(role);
        List<Map<String, Object>> sections = new ArrayList<>();

        List<Map<String, Object>> ideChildren = new ArrayList<>();
        ideChildren.add(Map.of("id", "ide-active", "label", "Active Workspace", "route", "/ide"));
        List<Map<String, Object>> templates = getIdeTemplates();
        for (int i = 0; i < templates.size(); i++) {
            Map<String, Object> tpl = templates.get(i);
            ideChildren.add(Map.of(
                    "id", "ide-tpl-" + i,
                    "label", String.valueOf(tpl.getOrDefault("name", "Template")),
                    "route", "/ide",
                    "templateIndex", i
            ));
        }

        Map<String, Object> welcomeItem = new LinkedHashMap<>();
        welcomeItem.put("id", "welcome");
        welcomeItem.put("label", "Welcome");
        welcomeItem.put("route", "/welcome");
        welcomeItem.put("icon", "home");

        Map<String, Object> ideItem = new LinkedHashMap<>();
        ideItem.put("id", "ide");
        ideItem.put("label", "Python IDE");
        ideItem.put("route", "/ide");
        ideItem.put("icon", "terminal");
        ideItem.put("badge", "WS");
        ideItem.put("badgeType", "live");
        ideItem.put("defaultExpanded", false);
        ideItem.put("children", ideChildren);

        // Unauthenticated Guest Menu: Only Welcome + Python IDE (Available without login)
        if (isGuest) {
            sections.add(Map.of(
                    "sectionTitle", "Public Workspace",
                    "items", List.of(welcomeItem, ideItem)
            ));
            return sections;
        }

        // 1. Teacher / Admin CMS Section (Only returned when role is TEACHER or ADMIN)
        if (isTeacherOrAdmin) {
            Map<String, Object> cmsItem = new LinkedHashMap<>();
            cmsItem.put("id", "admin-studio");
            cmsItem.put("label", "Content Creator");
            cmsItem.put("route", "/admin-studio");
            cmsItem.put("icon", "edit");
            cmsItem.put("badge", role.toUpperCase());
            cmsItem.put("badgeType", "live");
            cmsItem.put("defaultExpanded", true);
            cmsItem.put("children", List.of(
                    Map.of("id", "cms-exams", "label", "Mock Exam Creator", "route", "/admin-studio", "queryParams", Map.of("tab", "exams")),
                    Map.of("id", "cms-curriculum", "label", "CBSE Curriculum Builder", "route", "/admin-studio", "queryParams", Map.of("tab", "curriculum")),
                    Map.of("id", "cms-practicals", "label", "Practical Lab Creator", "route", "/admin-studio", "queryParams", Map.of("tab", "practicals"))
            ));

            sections.add(Map.of(
                    "sectionTitle", "Teacher & Admin CMS",
                    "items", List.of(cmsItem)
            ));
        }

        // 2. Workspace Section
        Map<String, Object> dashboardItem = new LinkedHashMap<>();
        dashboardItem.put("id", "dashboard");
        dashboardItem.put("label", "Overview");
        dashboardItem.put("route", "/dashboard");
        dashboardItem.put("icon", "grid");

        sections.add(Map.of(
                "sectionTitle", "Workspace",
                "items", List.of(welcomeItem, dashboardItem, ideItem)
        ));

        // 3. CBSE Curriculum Section (Populated from server chapters & practical categories)
        List<Map<String, Object>> class10Children = getChapters("class10").stream()
                .map(ch -> Map.<String, Object>of(
                        "id", String.valueOf(ch.get("id")),
                        "chapterId", String.valueOf(ch.get("id")),
                        "label", "Ch " + ch.get("chapterNumber") + ": " + ch.get("title"),
                        "route", "/class10/" + ch.get("id")
                ))
                .toList();

        Map<String, Object> class10Item = new LinkedHashMap<>();
        class10Item.put("id", "class10");
        class10Item.put("label", "Class 10 Syllabus");
        class10Item.put("route", "/class10");
        class10Item.put("icon", "book");
        class10Item.put("progressMetric", "class10");
        class10Item.put("defaultExpanded", true);
        class10Item.put("children", class10Children);

        List<Map<String, Object>> class12Children = getChapters("class12").stream()
                .map(ch -> Map.<String, Object>of(
                        "id", String.valueOf(ch.get("id")),
                        "chapterId", String.valueOf(ch.get("id")),
                        "label", "Ch " + ch.get("chapterNumber") + ": " + ch.get("title"),
                        "route", "/class12/" + ch.get("id")
                ))
                .toList();

        Map<String, Object> class12Item = new LinkedHashMap<>();
        class12Item.put("id", "class12");
        class12Item.put("label", "Class 12 Syllabus");
        class12Item.put("route", "/class12");
        class12Item.put("icon", "book-open");
        class12Item.put("progressMetric", "class12");
        class12Item.put("defaultExpanded", true);
        class12Item.put("children", class12Children);

        List<String> categories = List.of("All", "Text File", "Binary File", "CSV File", "Stack (DSA)", "Fundamentals");
        List<Map<String, Object>> pracChildren = categories.stream()
                .map(cat -> Map.<String, Object>of(
                        "id", "prac-cat-" + cat,
                        "label", "All".equals(cat) ? "All Practical Programs" : cat + " Programs",
                        "route", "/practical-lab",
                        "queryParams", Map.of("category", cat)
                ))
                .toList();

        Map<String, Object> practicalItem = new LinkedHashMap<>();
        practicalItem.put("id", "practical-lab");
        practicalItem.put("label", "Practical Lab (30M)");
        practicalItem.put("route", "/practical-lab");
        practicalItem.put("icon", "flask");
        practicalItem.put("progressMetric", "practicals");
        practicalItem.put("defaultExpanded", false);
        practicalItem.put("children", pracChildren);

        sections.add(Map.of(
                "sectionTitle", "CBSE Curriculum",
                "items", List.of(class10Item, class12Item, practicalItem)
        ));

        // 4. Assessment & Reference Section
        Map<String, Object> examPrepItem = new LinkedHashMap<>();
        examPrepItem.put("id", "exam-prep");
        examPrepItem.put("label", "Exam Prep & Tests");
        examPrepItem.put("route", "/exam-prep");
        examPrepItem.put("icon", "check-square");
        examPrepItem.put("defaultExpanded", false);
        examPrepItem.put("children", List.of(
                Map.of("id", "exam-mock", "label", "Timed Mock Exam", "route", "/exam-prep", "queryParams", Map.of("tab", "mock-test")),
                Map.of("id", "exam-output", "label", "Output & Error Lab", "route", "/exam-prep", "queryParams", Map.of("tab", "output-challenges")),
                Map.of("id", "exam-viva", "label", "Viva-Voce Flashcards", "route", "/exam-prep", "queryParams", Map.of("tab", "viva-cards"))
        ));

        Map<String, Object> cheatSheetItem = new LinkedHashMap<>();
        cheatSheetItem.put("id", "cheatsheet");
        cheatSheetItem.put("label", "Quick Reference");
        cheatSheetItem.put("route", "/cheatsheet");
        cheatSheetItem.put("icon", "zap");

        sections.add(Map.of(
                "sectionTitle", "Assessment & Reference",
                "items", List.of(examPrepItem, cheatSheetItem)
        ));

        return sections;
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
