package com.pylearn.backend.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pylearn.backend.service.CatalogService;
import com.pylearn.backend.service.PythonErrorAnalyzerService;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
public class PythonExecutionWebSocketHandler extends TextWebSocketHandler {

    private static final String INPUT_SIGNAL = "\0__WS_INPUT_REQUEST__\0";

    private final ObjectMapper objectMapper;
    private final PythonErrorAnalyzerService errorAnalyzer;
    private final CatalogService catalogService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private final Map<String, Path> activeWorkDirs = new ConcurrentHashMap<>();
    private Path extractedRunnerPath;

    public PythonExecutionWebSocketHandler(
            ObjectMapper objectMapper,
            PythonErrorAnalyzerService errorAnalyzer,
            CatalogService catalogService
    ) {
        this.objectMapper = objectMapper;
        this.errorAnalyzer = errorAnalyzer;
        this.catalogService = catalogService;
    }

    @PostConstruct
    public void initRunnerScript() throws IOException {
        extractedRunnerPath = Files.createTempFile("pylearn-spring-runner-", ".py");
        extractedRunnerPath.toFile().deleteOnExit();
        try (InputStream in = new ClassPathResource("runner.py").getInputStream()) {
            Files.copy(in, extractedRunnerPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public int getActiveSessionCount() {
        return activeProcesses.size();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "status");
        msg.put("status", "connected");
        msg.put("sessionId", session.getId());
        msg.put("engine", "Spring Boot WebSocket Engine");
        msg.put("message", "Connected to Spring Boot Python WebSocket Server");
        sendJson(session, msg);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    message.getPayload(),
                    new TypeReference<Map<String, Object>>() {}
            );
            String type = String.valueOf(payload.getOrDefault("type", ""));

            switch (type) {
                case "run" -> handleRunCommand(session, payload);
                case "stdin" -> handleStdinCommand(session, payload);
                case "stop" -> stopSessionProcess(session, "stopped");
                case "check_challenge" -> handleCheckChallenge(session, payload);
                case "ping" -> sendJson(session, Map.of("type", "pong", "timestamp", System.currentTimeMillis()));
                default -> {
                    // Ignore unknown commands
                }
            }
        } catch (Exception e) {
            sendJson(session, Map.of(
                    "type", "stderr",
                    "data", "Invalid WebSocket payload: " + e.getMessage() + "\n"
            ));
        }
    }

    private void handleRunCommand(WebSocketSession session, Map<String, Object> payload) throws IOException {
        stopSessionProcess(session, "restarting");

        String sessionId = session.getId();
        String code = String.valueOf(payload.getOrDefault("code", ""));
        String rawEntryFile = String.valueOf(payload.getOrDefault("entryFile", "main.py"));
        String safeEntryFile = Path.of(rawEntryFile).getFileName().toString();
        if (safeEntryFile.isBlank() || safeEntryFile.equals("runner.py") || !safeEntryFile.toLowerCase().endsWith(".py")) {
            safeEntryFile = "main.py";
        }
        String prefillStdin = String.valueOf(payload.getOrDefault("prefillStdin", ""));

        Path workDir = Files.createTempDirectory("pylearn-sb-" + sessionId + "-");
        activeWorkDirs.put(sessionId, workDir);

        Object filesObj = payload.get("files");
        if (filesObj instanceof Map<?, ?> extraFiles) {
            for (Map.Entry<?, ?> entry : extraFiles.entrySet()) {
                String safeName = Path.of(String.valueOf(entry.getKey())).getFileName().toString();
                if (!safeName.isBlank() && !safeName.equals("runner.py")) {
                    Files.writeString(
                            workDir.resolve(safeName),
                            String.valueOf(entry.getValue()),
                            StandardCharsets.UTF_8
                    );
                }
            }
        }

        Path mainScript = workDir.resolve(safeEntryFile);
        Files.writeString(mainScript, code, StandardCharsets.UTF_8);

        ProcessBuilder pb = new ProcessBuilder(
                "python", "-u",
                extractedRunnerPath.toAbsolutePath().toString(),
                mainScript.toAbsolutePath().toString()
        );
        pb.directory(workDir.toFile());
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUNBUFFERED", "1");

        long startTime = System.currentTimeMillis();
        Process process = pb.start();
        activeProcesses.put(sessionId, process);

        sendJson(session, Map.of(
                "type", "status",
                "status", "running",
                "pid", process.pid()
        ));

        if (!prefillStdin.isEmpty()) {
            String formatted = prefillStdin.endsWith("\n") ? prefillStdin : prefillStdin + "\n";
            OutputStream os = process.getOutputStream();
            os.write(formatted.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        StringBuilder stderrAcc = new StringBuilder();

        // Stream stdout & detect INPUT_SIGNAL
        executor.submit(() -> {
            try (InputStreamReader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buf = new char[1024];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    String chunk = new String(buf, 0, n);
                    if (chunk.contains(INPUT_SIGNAL)) {
                        String[] parts = chunk.split(Pattern.quote(INPUT_SIGNAL), -1);
                        for (int i = 0; i < parts.length; i++) {
                            if (!parts[i].isEmpty()) {
                                sendJson(session, Map.of("type", "stdout", "data", parts[i]));
                            }
                            if (i < parts.length - 1) {
                                sendJson(session, Map.of(
                                        "type", "input_request",
                                        "status", "waiting_input"
                                ));
                            }
                        }
                    } else {
                        sendJson(session, Map.of("type", "stdout", "data", chunk));
                    }
                }
            } catch (IOException ignored) {
            }
        });

        // Stream stderr
        executor.submit(() -> {
            try (InputStreamReader reader = new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)) {
                char[] buf = new char[1024];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    String chunk = new String(buf, 0, n);
                    synchronized (stderrAcc) {
                        stderrAcc.append(chunk);
                    }
                    sendJson(session, Map.of("type", "stderr", "data", chunk));
                }
            } catch (IOException ignored) {
            }
        });

        // Wait for process completion or timeout
        executor.submit(() -> {
            try {
                boolean finished = process.waitFor(90, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    sendJson(session, Map.of(
                            "type", "stderr",
                            "data", "\nExecution timed out after 90 seconds.\n"
                    ));
                }
                int exitCode = finished ? process.exitValue() : 1;
                long durationMs = System.currentTimeMillis() - startTime;

                activeProcesses.remove(sessionId, process);
                cleanupWorkDir(sessionId, workDir);

                String fullStderr;
                synchronized (stderrAcc) {
                    fullStderr = stderrAcc.toString();
                }

                Map<String, Object> errorHint = (exitCode != 0 && !fullStderr.isBlank())
                        ? errorAnalyzer.analyzeError(fullStderr)
                        : null;

                Map<String, Object> exitMsg = new LinkedHashMap<>();
                exitMsg.put("type", "exit");
                exitMsg.put("code", exitCode);
                exitMsg.put("durationMs", durationMs);
                exitMsg.put("errorHint", errorHint);
                sendJson(session, exitMsg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void handleStdinCommand(WebSocketSession session, Map<String, Object> payload) {
        Process process = activeProcesses.get(session.getId());
        if (process != null && process.isAlive()) {
            try {
                String data = String.valueOf(payload.getOrDefault("data", ""));
                String line = data.endsWith("\n") ? data : data + "\n";
                OutputStream os = process.getOutputStream();
                os.write(line.getBytes(StandardCharsets.UTF_8));
                os.flush();
                sendJson(session, Map.of("type", "status", "status", "running"));
            } catch (IOException e) {
                sendJson(session, Map.of(
                        "type", "stderr",
                        "data", "Failed to write to stdin: " + e.getMessage() + "\n"
                ));
            }
        } else {
            sendJson(session, Map.of(
                    "type", "stderr",
                    "data", "No Python program is currently running to receive input.\n"
            ));
        }
    }

    private void stopSessionProcess(WebSocketSession session, String reason) {
        String sessionId = session.getId();
        Process proc = activeProcesses.remove(sessionId);
        if (proc != null && proc.isAlive()) {
            proc.descendants().forEach(ProcessHandle::destroyForcibly);
            proc.destroyForcibly();
            if (session != null && session.isOpen()) {
                sendJson(session, Map.of("type", "status", "status", reason));
            }
        }
        Path dir = activeWorkDirs.remove(sessionId);
        if (dir != null) {
            FileSystemUtils.deleteRecursively(dir.toFile());
        }
    }

    private void cleanupWorkDir(String sessionId, Path workDir) {
        activeWorkDirs.remove(sessionId, workDir);
        if (workDir != null) {
            FileSystemUtils.deleteRecursively(workDir.toFile());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        stopSessionProcess(session, "disconnected");
    }

    @SuppressWarnings("unchecked")
    private void handleCheckChallenge(WebSocketSession session, Map<String, Object> payload) {
        String lessonId = String.valueOf(payload.getOrDefault("lessonId", ""));
        String code = String.valueOf(payload.getOrDefault("code", ""));

        Map<String, Object> lesson = catalogService.getLessonById(lessonId);
        if (lesson == null || !(lesson.get("challenge") instanceof Map<?, ?> challengeMap)) {
            sendJson(session, Map.of(
                    "type", "challenge_result",
                    "passed", false,
                    "error", "Challenge not found for this lesson."
            ));
            return;
        }

        sendJson(session, Map.of("type", "status", "status", "testing"));

        List<Map<String, Object>> results = new ArrayList<>();
        Object rawTests = challengeMap.get("tests");
        if (rawTests instanceof List<?> testsList) {
            for (Object testObj : testsList) {
                if (testObj instanceof Map<?, ?> tMap) {
                    Map<String, Object> testCase = new LinkedHashMap<>();
                    tMap.forEach((k, v) -> testCase.put(String.valueOf(k), v));
                    results.add(runChallengeTestCase(code, testCase));
                }
            }
        }

        boolean allPassed = !results.isEmpty() && results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("passed")));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("type", "challenge_result");
        resp.put("lessonId", lessonId);
        resp.put("passed", allPassed);
        resp.put("results", results);
        sendJson(session, resp);
    }

    private Map<String, Object> runChallengeTestCase(String code, Map<String, Object> testCase) {
        Map<String, Object> result = new LinkedHashMap<>();
        String name = String.valueOf(testCase.getOrDefault("name", "Test Case"));
        String stdin = String.valueOf(testCase.getOrDefault("stdin", ""));
        List<String> expectContains = new ArrayList<>();
        if (testCase.get("expectContains") instanceof List<?> list) {
            list.forEach(item -> expectContains.add(String.valueOf(item)));
        }

        Path tmpDir = null;
        long startTime = System.currentTimeMillis();
        boolean timedOut = false;
        int exitCode = -1;
        StringBuilder stdoutBuilder = new StringBuilder();
        StringBuilder stderrBuilder = new StringBuilder();

        try {
            tmpDir = Files.createTempDirectory("pylearn-test-");
            Path scriptPath = tmpDir.resolve("main.py");
            Files.writeString(scriptPath, code, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(
                    "python", "-u",
                    extractedRunnerPath.toAbsolutePath().toString(),
                    scriptPath.toAbsolutePath().toString()
            );
            pb.directory(tmpDir.toFile());
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            pb.environment().put("PYTHONUNBUFFERED", "1");

            Process process = pb.start();

            if (!stdin.isEmpty()) {
                String inputWithNewline = stdin.endsWith("\n") ? stdin : stdin + "\n";
                OutputStream os = process.getOutputStream();
                os.write(inputWithNewline.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            process.getOutputStream().close();

            Future<?> outFuture = executor.submit(() -> {
                try (InputStreamReader isr = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buf = new char[1024];
                    int n;
                    while ((n = isr.read(buf)) != -1) {
                        String s = new String(buf, 0, n).replace(INPUT_SIGNAL, "");
                        stdoutBuilder.append(s);
                    }
                } catch (IOException ignored) {}
            });

            Future<?> errFuture = executor.submit(() -> {
                try (InputStreamReader isr = new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)) {
                    char[] buf = new char[1024];
                    int n;
                    while ((n = isr.read(buf)) != -1) {
                        stderrBuilder.append(new String(buf, 0, n));
                    }
                } catch (IOException ignored) {}
            });

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                timedOut = true;
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            } else {
                exitCode = process.exitValue();
                try {
                    outFuture.get(1, TimeUnit.SECONDS);
                    errFuture.get(1, TimeUnit.SECONDS);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            stderrBuilder.append(e.getMessage());
        } finally {
            if (tmpDir != null) {
                FileSystemUtils.deleteRecursively(tmpDir.toFile());
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;
        String stdout = stdoutBuilder.toString().trim();
        String stderr = stderrBuilder.toString().trim();

        List<String> missing = new ArrayList<>();
        for (String exp : expectContains) {
            if (!stdout.contains(exp)) {
                missing.add(exp);
            }
        }

        boolean passed = !timedOut && exitCode == 0 && missing.isEmpty();
        result.put("name", name);
        result.put("passed", passed);
        result.put("stdin", stdin);
        result.put("stdout", stdout);
        result.put("stderr", stderr);
        result.put("expected", String.join(", ", expectContains));
        result.put("timedOut", timedOut);
        result.put("durationMs", durationMs);
        return result;
    }

    private synchronized void sendJson(WebSocketSession session, Map<String, Object> data) {
        if (session == null || !session.isOpen()) return;
        try {
            String json = objectMapper.writeValueAsString(data);
            session.sendMessage(new TextMessage(json));
        } catch (Exception ignored) {
        }
    }
}
