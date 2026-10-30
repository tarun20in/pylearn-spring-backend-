# ☕ PyLearn Spring Boot Backend (`pylearn-spring-backend`)

A **Spring Boot 3.4** backend for the **PyLearn CBSE Studio** Angular application (`pylearn-angular`). It provides:
1. **Real-Time WebSocket Python Execution Engine (`ws://localhost:8080/ws`)** using `spring-boot-starter-websocket` (`PythonExecutionWebSocketHandler`) with full interactive `input()` streaming (`stdin`/`stdout`/`stderr`) and automated Python traceback analysis (`PythonErrorAnalyzerService`).
2. **RESTful API (`http://localhost:8080/api/**`)** using `spring-boot-starter-web` (`PortalApiController` & `StudentProgressService`) for persisting CBSE chapter completion, practical file verification, and mock exam attempts.

---

## 📂 Project Structure

```text
pylearn-spring-backend/
├── pom.xml                                      # Maven configuration (Spring Boot 3.4.2, Java 17+)
├── src/main/resources/
│   ├── application.properties                   # Server port (8080) & WebSocket limits
│   └── runner.py                                # Unbuffered Python wrapper with input() signal hook
└── src/main/java/com/pylearn/backend/
    ├── PyLearnBackendApplication.java           # @SpringBootApplication entry point
    ├── config/
    │   ├── CorsConfig.java                      # Global CORS config for Angular (localhost:4200)
    │   └── WebSocketConfig.java                 # Registers /ws endpoint with allowed origins
    ├── controller/
    │   └── PortalApiController.java             # REST APIs (/api/health, /api/progress/**)
    ├── service/
    │   ├── PythonErrorAnalyzerService.java      # Beginner-friendly Python traceback analyzer
    │   └── StudentProgressService.java          # JSON-backed persistence for student progress
    └── websocket/
        └── PythonExecutionWebSocketHandler.java # Spawns Python processes & streams I/O over WS
```

---

## 🔌 Angular ↔ Spring Boot Communication Endpoints

### 1. WebSocket Endpoint: `ws://localhost:8080/ws`
Used by Angular's `PythonWsService` (`pylearn-angular/src/app/services/python-ws.service.ts`):
- **Client → Server (`run`)**:
  ```json
  {
    "type": "run",
    "code": "name = input('Name: ')\nprint('Hello', name)",
    "files": {},
    "prefillStdin": ""
  }
  ```
- **Client → Server (`stdin`)** (When Python calls `input()`):
  ```json
  {
    "type": "stdin",
    "data": "Aarav\n"
  }
  ```
- **Client → Server (`stop`)**:
  ```json
  {
    "type": "stop"
  }
  ```
- **Server → Client Messages**:
  - `{"type": "status", "status": "connected" | "running" | "stopped"}`
  - `{"type": "stdout", "data": "..."}`
  - `{"type": "stderr", "data": "..."}`
  - `{"type": "input_request", "status": "waiting_input"}`
  - `{"type": "exit", "code": 0, "durationMs": 115, "errorHint": {...}}`

### 2. REST API Endpoints: `http://localhost:8080/api`
Used by Angular's `ProgressService` (`pylearn-angular/src/app/services/progress.service.ts`):
- `GET  /api/health` — Returns backend status and active Python process count.
- `GET  /api/progress` — Returns `{ completedChapters, completedPracticals, examAttempts }`.
- `POST /api/progress/chapter/{chapterId}` — Toggles completion of a CBSE Class 10/12 chapter.
- `POST /api/progress/practical/{practicalId}` — Toggles verification of a CBSE Practical File program.
- `POST /api/progress/exam` — Saves a graded Mock Exam attempt.

---

## 🚀 How to Run the Spring Boot Backend

### Prerequisites
- **JDK 17+** (`java -version`)
- **Maven 3.8+** (`mvn -version`)
- **Python 3.8+** (`python --version`)

### Start Spring Boot Server (Port 8080)
```powershell
cd pylearn-spring-backend
mvn spring-boot:run
```

### Start Angular Frontend (Port 4200)
```powershell
cd pylearn-angular
npm.cmd start
```
Then open **`http://localhost:4200`** in your browser.
