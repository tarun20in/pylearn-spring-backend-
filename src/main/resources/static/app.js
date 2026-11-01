/**
 * app.js - Client-Side Application for PyLearn Studio
 *
 * Manages:
 * - Bidirectional WebSocket connection (run, stdin for input(), stop, check_challenge)
 * - Python Code Editor with syntax highlighting, line numbers, and smart auto-indent
 * - Multi-file workspace tabs (main.py + custom helper files)
 * - Structured 8-lesson Python curriculum, hints, & automated verification
 */

(function () {
  "use strict";

  // State
  let ws = null;
  let wsConnected = false;
  let isProgramRunning = false;
  let isWaitingForInput = false;
  let errorLineNumber = null;

  let lessons = [];
  let templates = [];
  let currentLessonIndex = 0;
  let revealedHintsCount = 0;

  // Multi-file state
  let files = {
    "main.py": ""
  };
  let activeFile = "main.py";
  let editorFontSize = 14;

  // Completed lessons persisted in localStorage
  const STORAGE_KEY_COMPLETED = "pylearn_completed_lessons_v1";
  const STORAGE_KEY_SAVED_CODE = "pylearn_saved_code_v1";

  let completedLessons = new Set(
    JSON.parse(localStorage.getItem(STORAGE_KEY_COMPLETED) || "[]")
  );
  let savedLessonCode = JSON.parse(
    localStorage.getItem(STORAGE_KEY_SAVED_CODE) || "{}"
  );

  // DOM Elements
  const wsBadge = document.getElementById("wsBadge");
  const wsStatusText = document.getElementById("wsStatusText");
  const progressText = document.getElementById("progressText");
  const progressBarFill = document.getElementById("progressBarFill");
  const templateSelect = document.getElementById("templateSelect");

  const runBtn = document.getElementById("runBtn");
  const stopBtn = document.getElementById("stopBtn");
  const checkChallengeBtn = document.getElementById("checkChallengeBtn");

  // Left pane elements
  const tabBtns = document.querySelectorAll(".tab-btn");
  const tabPanels = document.querySelectorAll(".tab-panel");
  const lessonSelect = document.getElementById("lessonSelect");
  const prevLessonBtn = document.getElementById("prevLessonBtn");
  const nextLessonBtn = document.getElementById("nextLessonBtn");
  const lessonMetaHeader = document.getElementById("lessonMetaHeader");
  const lessonConceptContent = document.getElementById("lessonConceptContent");
  const loadStarterBtn = document.getElementById("loadStarterBtn");
  const goToChallengeBtn = document.getElementById("goToChallengeBtn");
  const challengeContainer = document.getElementById("challengeContainer");

  // Editor elements
  const fileTabsContainer = document.getElementById("fileTabs");
  const addFileBtn = document.getElementById("addFileBtn");
  const codeEditor = document.getElementById("codeEditor");
  const codeHighlight = document.getElementById("codeHighlight");
  const codeHighlightInner = document.getElementById("codeHighlightInner");
  const lineNumbers = document.getElementById("lineNumbers");
  const cursorPosText = document.getElementById("cursorPosText");
  const fontDecBtn = document.getElementById("fontDecBtn");
  const fontIncBtn = document.getElementById("fontIncBtn");
  const copyCodeBtn = document.getElementById("copyCodeBtn");
  const downloadCodeBtn = document.getElementById("downloadCodeBtn");
  const resetCodeBtn = document.getElementById("resetCodeBtn");

  // Terminal elements
  const execStateBadge = document.getElementById("execStateBadge");
  const toggleBatchStdinBtn = document.getElementById("toggleBatchStdinBtn");
  const batchStdinDrawer = document.getElementById("batchStdinDrawer");
  const batchStdinInput = document.getElementById("batchStdinInput");
  const clearBatchStdinBtn = document.getElementById("clearBatchStdinBtn");
  const clearTerminalBtn = document.getElementById("clearTerminalBtn");
  const terminalBody = document.getElementById("terminalBody");
  const terminalWelcome = document.getElementById("terminalWelcome");
  const terminalStream = document.getElementById("terminalStream");

  // Interactive Stdin controls
  const inlineInputRow = document.getElementById("inlineInputRow");
  const inlineTerminalInput = document.getElementById("inlineTerminalInput");
  const sendInlineInputBtn = document.getElementById("sendInlineInputBtn");
  const terminalStdinBar = document.getElementById("terminalStdinBar");
  const stdinStatusLabel = document.getElementById("stdinStatusLabel");
  const bottomStdinInput = document.getElementById("bottomStdinInput");
  const bottomStdinSendBtn = document.getElementById("bottomStdinSendBtn");

  // Friendly Error Coach
  const errorCoachCard = document.getElementById("errorCoachCard");
  const errorCoachType = document.getElementById("errorCoachType");
  const errorCoachTitle = document.getElementById("errorCoachTitle");
  const errorCoachTip = document.getElementById("errorCoachTip");
  const closeErrorCoachBtn = document.getElementById("closeErrorCoachBtn");

  // ==========================================================================
  // 1. WebSocket Connection & Real-Time Protocol
  // ==========================================================================

  function connectWebSocket() {
    const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
    const wsUrl = `${protocol}//${window.location.host}/ws`;

    wsBadge.classList.remove("connected", "disconnected");
    wsStatusText.textContent = "Connecting WS...";

    ws = new WebSocket(wsUrl);

    ws.addEventListener("open", () => {
      wsConnected = true;
      wsBadge.classList.add("connected");
      wsBadge.classList.remove("disconnected");
      wsStatusText.textContent = "WS Connected";
    });

    ws.addEventListener("message", (event) => {
      try {
        const msg = JSON.parse(event.data);
        handleServerMessage(msg);
      } catch (err) {
        console.error("Failed to parse WS message:", err);
      }
    });

    ws.addEventListener("close", () => {
      wsConnected = false;
      wsBadge.classList.remove("connected");
      wsBadge.classList.add("disconnected");
      wsStatusText.textContent = "WS Disconnected (Reconnecting...)";
      setProgramRunningState(false);
      setWaitingForInputState(false);
      setTimeout(connectWebSocket, 2000);
    });

    ws.addEventListener("error", () => {
      wsConnected = false;
    });
  }

  function sendWsMessage(payload) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(payload));
      return true;
    }
    appendTerminalChunk("⚠️ WebSocket is not connected yet. Reconnecting...\n", "term-stderr");
    return false;
  }

  function handleServerMessage(msg) {
    switch (msg.type) {
      case "status":
        if (msg.status === "running") {
          setProgramRunningState(true);
          setWaitingForInputState(false);
        } else if (msg.status === "stopped" || msg.status === "timeout") {
          setProgramRunningState(false);
          setWaitingForInputState(false);
          appendTerminalMeta(`Program ${msg.status}.`);
        } else if (msg.status === "testing") {
          execStateBadge.className = "exec-state-badge running";
          execStateBadge.textContent = "Testing Challenge...";
        }
        break;

      case "stdout":
        appendTerminalChunk(msg.data, "term-stdout");
        break;

      case "stderr":
        appendTerminalChunk(msg.data, "term-stderr");
        break;

      case "input_request":
        // Python called input()! Activate the live inline input prompt
        setWaitingForInputState(true);
        break;

      case "exit":
        setProgramRunningState(false);
        setWaitingForInputState(false);
        if (msg.code === 0) {
          appendTerminalMeta(`✓ Process finished with exit code 0 (${msg.durationMs} ms)`);
        } else {
          execStateBadge.className = "exec-state-badge error";
          execStateBadge.textContent = `Exited (${msg.code})`;
          appendTerminalMeta(`✕ Process exited with code ${msg.code} (${msg.durationMs} ms)`);
        }
        if (msg.errorHint) {
          showErrorCoach(msg.errorHint);
        }
        break;

      case "challenge_result":
        setProgramRunningState(false);
        renderChallengeResults(msg);
        break;
    }
  }

  // ==========================================================================
  // 2. Running Code, Interactive input() & Terminal Output
  // ==========================================================================

  function runCurrentCode() {
    saveActiveFileContent();
    clearTerminal();
    hideErrorCoach();

    const mainCode = files["main.py"] || "";
    const extraFiles = {};
    for (const [fname, content] of Object.entries(files)) {
      if (fname !== "main.py") {
        extraFiles[fname] = content;
      }
    }

    const prefillStdin = batchStdinInput.value ? batchStdinInput.value : "";

    terminalWelcome.classList.add("hidden");
    sendWsMessage({
      type: "run",
      code: mainCode,
      files: extraFiles,
      prefillStdin
    });
  }

  function stopRunningCode() {
    sendWsMessage({ type: "stop" });
  }

  function sendInteractiveStdin(rawText) {
    if (!isProgramRunning) return;
    const text = String(rawText ?? "");

    // Echo user's typed input in cyan right inside the terminal stream
    appendTerminalChunk(text + "\n", "term-stdin-echo");

    inlineTerminalInput.value = "";
    bottomStdinInput.value = "";
    setWaitingForInputState(false);

    sendWsMessage({
      type: "stdin",
      data: text + "\n"
    });
  }

  function setProgramRunningState(running) {
    isProgramRunning = running;
    runBtn.disabled = false;
    stopBtn.disabled = !running;
    bottomStdinInput.disabled = !running;
    bottomStdinSendBtn.disabled = !running;

    if (running) {
      execStateBadge.className = "exec-state-badge running";
      execStateBadge.textContent = "Running...";
      bottomStdinInput.placeholder = "Type input for running Python program and press Enter...";
    } else {
      if (!execStateBadge.classList.contains("error")) {
        execStateBadge.className = "exec-state-badge idle";
        execStateBadge.textContent = "Idle";
      }
      bottomStdinInput.placeholder = "Run a program with input() to type live responses here...";
    }
  }

  function setWaitingForInputState(waiting) {
    isWaitingForInput = waiting;
    if (waiting) {
      execStateBadge.className = "exec-state-badge waiting_input";
      execStateBadge.textContent = "Waiting for input()...";
      inlineInputRow.classList.remove("hidden");
      terminalStdinBar.classList.add("waiting");
      stdinStatusLabel.innerHTML = "<strong>Awaiting <code>input()</code>:</strong>";
      scrollToTerminalBottom();
      setTimeout(() => {
        inlineTerminalInput.focus();
      }, 20);
    } else {
      inlineInputRow.classList.add("hidden");
      terminalStdinBar.classList.remove("waiting");
      stdinStatusLabel.innerHTML = "Interactive <code>stdin</code>";
      if (isProgramRunning) {
        execStateBadge.className = "exec-state-badge running";
        execStateBadge.textContent = "Running...";
      }
    }
  }

  function appendTerminalChunk(text, className) {
    terminalWelcome.classList.add("hidden");
    const span = document.createElement("span");
    span.className = className;
    span.textContent = text;
    terminalStream.appendChild(span);
    scrollToTerminalBottom();
  }

  function appendTerminalMeta(text) {
    const span = document.createElement("span");
    span.className = "term-meta";
    span.textContent = text;
    terminalStream.appendChild(span);
    scrollToTerminalBottom();
  }

  function scrollToTerminalBottom() {
    terminalBody.scrollTop = terminalBody.scrollHeight;
  }

  function clearTerminal() {
    terminalStream.innerHTML = "";
    inlineInputRow.classList.add("hidden");
    execStateBadge.className = "exec-state-badge idle";
    execStateBadge.textContent = "Idle";
  }

  function showErrorCoach(hint) {
    if (!hint) return;
    errorCoachType.textContent = hint.type || "Error";
    errorCoachTitle.textContent = hint.title || "Python Error Helper";
    errorCoachTip.textContent = hint.tip || "";
    errorCoachCard.classList.remove("hidden");

    if (hint.line && activeFile === "main.py") {
      errorLineNumber = hint.line;
      updateLineNumbers();
    }
  }

  function hideErrorCoach() {
    errorCoachCard.classList.add("hidden");
    if (errorLineNumber !== null) {
      errorLineNumber = null;
      updateLineNumbers();
    }
  }

  // ==========================================================================
  // 3. Python Code Editor: Highlighting, Line Numbers, & Smart Indentation
  // ==========================================================================

  const PY_KEYWORDS = new Set([
    "and", "as", "assert", "async", "await", "break", "class", "continue",
    "def", "del", "elif", "else", "except", "False", "finally", "for",
    "from", "global", "if", "import", "in", "is", "lambda", "None",
    "nonlocal", "not", "or", "pass", "raise", "return", "True", "try",
    "while", "with", "yield"
  ]);

  const PY_BUILTINS = new Set([
    "print", "input", "int", "float", "str", "bool", "list", "dict", "set",
    "tuple", "len", "range", "enumerate", "zip", "sum", "max", "min", "abs",
    "round", "sorted", "reversed", "type", "isinstance", "open", "ValueError",
    "TypeError", "ZeroDivisionError", "IndexError", "KeyError", "Exception"
  ]);

  function escapeHtml(str) {
    return str
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
  }

  function highlightPython(code) {
    if (!activeFile.endsWith(".py")) {
      return escapeHtml(code) + (code.endsWith("\n") ? " " : "");
    }

    const tokenRegex =
      /("""[\s\S]*?"""|'''[\s\S]*?'''|"(?:\\.|[^"\\\r\n])*"|'(?:\\.|[^'\\\r\n])*'|#[^\r\n]*|\b\d+(?:\.\d+)?\b|\b[A-Za-z_][A-Za-z0-9_]*\b)/g;

    let result = "";
    let lastIndex = 0;
    let match;

    while ((match = tokenRegex.exec(code)) !== null) {
      if (match.index > lastIndex) {
        result += escapeHtml(code.slice(lastIndex, match.index));
      }

      const token = match[0];
      const escaped = escapeHtml(token);

      if (token.startsWith("#")) {
        result += `<span class="tok-comment">${escaped}</span>`;
      } else if (token.startsWith('"') || token.startsWith("'")) {
        result += `<span class="tok-str">${escaped}</span>`;
      } else if (/^\d/.test(token)) {
        result += `<span class="tok-num">${escaped}</span>`;
      } else if (PY_KEYWORDS.has(token)) {
        result += `<span class="tok-kw">${escaped}</span>`;
      } else if (PY_BUILTINS.has(token)) {
        result += `<span class="tok-builtin">${escaped}</span>`;
      } else {
        result += escaped;
      }

      lastIndex = tokenRegex.lastIndex;
    }

    if (lastIndex < code.length) {
      result += escapeHtml(code.slice(lastIndex));
    }

    if (code.endsWith("\n")) {
      result += " ";
    }
    return result;
  }

  function updateEditorView() {
    const code = codeEditor.value;
    codeHighlightInner.innerHTML = highlightPython(code);
    updateLineNumbers();
    syncEditorScroll();
  }

  function updateLineNumbers() {
    const linesCount = Math.max(1, codeEditor.value.split("\n").length);
    const html = [];
    for (let i = 1; i <= linesCount; i++) {
      if (errorLineNumber === i) {
        html.push(`<div class="err-line" title="Error on line ${i}">${i}</div>`);
      } else {
        html.push(`<div>${i}</div>`);
      }
    }
    lineNumbers.innerHTML = html.join("");
  }

  function syncEditorScroll() {
    codeHighlight.scrollTop = codeEditor.scrollTop;
    codeHighlight.scrollLeft = codeEditor.scrollLeft;
    lineNumbers.scrollTop = codeEditor.scrollTop;
  }

  function updateCursorStatus() {
    const pos = codeEditor.selectionStart || 0;
    const textBefore = codeEditor.value.slice(0, pos);
    const lines = textBefore.split("\n");
    const ln = lines.length;
    const col = lines[lines.length - 1].length + 1;
    cursorPosText.textContent = `Ln ${ln}, Col ${col}`;
  }

  function handleEditorKeyDown(e) {
    // Ctrl+Enter or Cmd+Enter -> Run Code
    if ((e.ctrlKey || e.metaKey) && e.key === "Enter") {
      e.preventDefault();
      runCurrentCode();
      return;
    }

    // Escape -> Stop running code if running
    if (e.key === "Escape" && isProgramRunning) {
      e.preventDefault();
      stopRunningCode();
      return;
    }

    const start = codeEditor.selectionStart;
    const end = codeEditor.selectionEnd;
    const val = codeEditor.value;

    // Tab / Shift+Tab for 4-space indentation
    if (e.key === "Tab") {
      e.preventDefault();
      if (start === end && !e.shiftKey) {
        codeEditor.value = val.slice(0, start) + "    " + val.slice(end);
        codeEditor.selectionStart = codeEditor.selectionEnd = start + 4;
      } else {
        const lineStart = val.lastIndexOf("\n", start - 1) + 1;
        const selectedLines = val.slice(lineStart, end).split("\n");
        const transformed = selectedLines.map((line) => {
          if (e.shiftKey) {
            return line.startsWith("    ")
              ? line.slice(4)
              : line.replace(/^\s{1,4}/, "");
          }
          return "    " + line;
        });
        const replacement = transformed.join("\n");
        codeEditor.value = val.slice(0, lineStart) + replacement + val.slice(end);
        codeEditor.selectionStart = lineStart;
        codeEditor.selectionEnd = lineStart + replacement.length;
      }
      onEditorModified();
      return;
    }

    // Enter -> Auto-indent (and +4 spaces after a colon `:`)
    if (e.key === "Enter") {
      e.preventDefault();
      const lineStart = val.lastIndexOf("\n", start - 1) + 1;
      const currentLine = val.slice(lineStart, start);
      const indentMatch = currentLine.match(/^\s*/);
      let indent = indentMatch ? indentMatch[0] : "";

      const trimmedLine = currentLine.replace(/#.*$/, "").trimEnd();
      if (trimmedLine.endsWith(":")) {
        indent += "    ";
      }

      const insertStr = "\n" + indent;
      codeEditor.value = val.slice(0, start) + insertStr + val.slice(end);
      codeEditor.selectionStart = codeEditor.selectionEnd = start + insertStr.length;
      onEditorModified();
      return;
    }

    // Backspace -> Delete 4 spaces at once if on an indentation boundary
    if (e.key === "Backspace" && start === end && start >= 4) {
      const lineStart = val.lastIndexOf("\n", start - 1) + 1;
      const beforeInLine = val.slice(lineStart, start);
      if (beforeInLine.length > 0 && /^\s+$/.test(beforeInLine) && beforeInLine.length % 4 === 0) {
        e.preventDefault();
        codeEditor.value = val.slice(0, start - 4) + val.slice(end);
        codeEditor.selectionStart = codeEditor.selectionEnd = start - 4;
        onEditorModified();
        return;
      }
    }
  }

  function onEditorModified() {
    if (errorLineNumber !== null) {
      errorLineNumber = null;
    }
    updateEditorView();
    updateCursorStatus();
    saveActiveFileContent();
  }

  function saveActiveFileContent() {
    files[activeFile] = codeEditor.value;
    if (activeFile === "main.py" && lessons[currentLessonIndex]) {
      const lessonId = lessons[currentLessonIndex].id;
      savedLessonCode[lessonId] = codeEditor.value;
      localStorage.setItem(STORAGE_KEY_SAVED_CODE, JSON.stringify(savedLessonCode));
    }
  }

  // ==========================================================================
  // 4. Multi-File Tabs Management
  // ==========================================================================

  function renderFileTabs() {
    fileTabsContainer.innerHTML = "";
    for (const filename of Object.keys(files)) {
      const btn = document.createElement("button");
      btn.className = "file-tab" + (filename === activeFile ? " active" : "");
      const icon = filename.endsWith(".py") ? "🐍" : "📄";
      btn.innerHTML = `<span>${icon}</span> <span>${escapeHtml(filename)}</span>`;

      if (filename !== "main.py") {
        const closeSpan = document.createElement("span");
        closeSpan.className = "file-tab-close";
        closeSpan.textContent = "✕";
        closeSpan.title = "Delete file";
        closeSpan.addEventListener("click", (e) => {
          e.stopPropagation();
          delete files[filename];
          if (activeFile === filename) {
            switchFileTab("main.py");
          } else {
            renderFileTabs();
          }
        });
        btn.appendChild(closeSpan);
      }

      btn.addEventListener("click", () => switchFileTab(filename));
      fileTabsContainer.appendChild(btn);
    }

    fileTabsContainer.appendChild(addFileBtn);
  }

  function switchFileTab(filename) {
    if (! Object.prototype.hasOwnProperty.call(files, filename)) return;
    saveActiveFileContent();
    activeFile = filename;
    codeEditor.value = files[filename];
    renderFileTabs();
    updateEditorView();
  }

  // ==========================================================================
  // 5. Curriculum, Lessons & Automated Challenges
  // ==========================================================================

  async function loadCurriculum() {
    try {
      const res = await fetch("/api/curriculum");
      const data = await res.json();
      lessons = data.lessons || [];
      templates = data.templates || [];

      populateLessonSelect();
      populateTemplatesSelect();
      updateProgressUI();

      if (lessons.length > 0) {
        selectLesson(0);
      }
    } catch (err) {
      console.error("Failed to load curriculum:", err);
    }
  }

  function populateLessonSelect() {
    lessonSelect.innerHTML = "";
    lessons.forEach((lesson, idx) => {
      const opt = document.createElement("option");
      opt.value = String(idx);
      const check = completedLessons.has(lesson.id) ? "✅ " : "";
      opt.textContent = `${check}Lesson ${lesson.number}: ${lesson.title}`;
      lessonSelect.appendChild(opt);
    });
  }

  function populateTemplatesSelect() {
    templateSelect.innerHTML = `<option value="">✨ Quick Program Templates...</option>`;
    templates.forEach((tpl) => {
      const opt = document.createElement("option");
      opt.value = tpl.id;
      opt.textContent = tpl.name;
      templateSelect.appendChild(opt);
    });
  }

  function updateProgressUI() {
    const total = lessons.length || 8;
    const done = completedLessons.size;
    progressText.textContent = `${done} / ${total}`;
    const pct = Math.min(100, Math.round((done / total) * 100));
    progressBarFill.style.width = `${pct}%`;
  }

  function selectLesson(index) {
    if (index < 0 || index >= lessons.length) return;
    saveActiveFileContent();

    currentLessonIndex = index;
    revealedHintsCount = 0;
    lessonSelect.value = String(index);
    prevLessonBtn.disabled = index === 0;
    nextLessonBtn.disabled = index === lessons.length - 1;

    const lesson = lessons[index];
    const isDone = completedLessons.has(lesson.id);

    lessonMetaHeader.innerHTML = `
      <div>
        <span class="badge-tag">${escapeHtml(lesson.category)}</span>
        <span class="badge-tag">${escapeHtml(lesson.difficulty)}</span>
      </div>
      ${isDone ? `<span class="badge-tag done">✓ Completed</span>` : ""}
    `;

    lessonConceptContent.innerHTML = lesson.conceptHtml;

    // Load saved student code for this lesson or its starterCode
    const codeToLoad = savedLessonCode[lesson.id] || lesson.starterCode;
    files["main.py"] = codeToLoad;
    activeFile = "main.py";
    codeEditor.value = codeToLoad;
    renderFileTabs();
    updateEditorView();

    renderChallengePanel(lesson);
  }

  function renderChallengePanel(lesson) {
    const ch = lesson.challenge;
    if (!ch) {
      challengeContainer.innerHTML = `<p>No challenge for this lesson.</p>`;
      return;
    }

    const isDone = completedLessons.has(lesson.id);

    challengeContainer.innerHTML = `
      <div style="display:flex; justify-content:space-between; align-items:center;">
        <div class="challenge-title">🎯 ${escapeHtml(ch.title)}</div>
        ${isDone ? `<span class="badge-tag done">✓ Solved</span>` : ""}
      </div>
      <div class="challenge-desc">${escapeHtml(ch.description)}</div>

      <div class="challenge-io-grid">
        <div class="io-box">
          <div class="io-box-label">Sample Input (stdin)</div>
          <pre>${escapeHtml((ch.sampleInput || "").trim())}</pre>
        </div>
        <div class="io-box">
          <div class="io-box-label">Expected in Output</div>
          <pre>${escapeHtml(ch.expectedSubstring || "")}</pre>
        </div>
      </div>

      <div style="display:flex; gap:8px; flex-wrap:wrap;">
        <button id="verifyInsideTabBtn" class="btn btn-verify btn-sm">
          ✓ Verify My Code
        </button>
        <button id="showHintBtn" class="btn btn-secondary btn-sm">
          💡 Show Hint (0/${(ch.hints || []).length})
        </button>
        <button id="loadSolutionBtn" class="btn btn-secondary btn-sm">
          🔑 View Solution
        </button>
      </div>

      <div class="hint-list" id="hintList"></div>
      <div class="test-results-box" id="testResultsBox"></div>
    `;

    const verifyInsideTabBtn = document.getElementById("verifyInsideTabBtn");
    const showHintBtn = document.getElementById("showHintBtn");
    const loadSolutionBtn = document.getElementById("loadSolutionBtn");
    const hintList = document.getElementById("hintList");

    verifyInsideTabBtn.addEventListener("click", triggerChallengeVerification);

    showHintBtn.addEventListener("click", () => {
      const hints = ch.hints || [];
      if (revealedHintsCount < hints.length) {
        const div = document.createElement("div");
        div.className = "hint-item";
        div.textContent = `💡 Hint ${revealedHintsCount + 1}: ${hints[revealedHintsCount]}`;
        hintList.appendChild(div);
        revealedHintsCount++;
        showHintBtn.textContent = `💡 Show Hint (${revealedHintsCount}/${hints.length})`;
      }
    });

    loadSolutionBtn.addEventListener("click", () => {
      if (ch.solutionCode) {
        files["main.py"] = ch.solutionCode;
        switchFileTab("main.py");
        saveActiveFileContent();
      }
    });
  }

  function triggerChallengeVerification() {
    saveActiveFileContent();
    switchLeftTab("challenge");
    const lesson = lessons[currentLessonIndex];
    if (!lesson) return;

    const testResultsBox = document.getElementById("testResultsBox");
    if (testResultsBox) {
      testResultsBox.innerHTML = `<div class="test-case-card">⏳ Running automated test cases via WebSocket...</div>`;
    }

    sendWsMessage({
      type: "check_challenge",
      lessonId: lesson.id,
      code: files["main.py"] || ""
    });
  }

  function renderChallengeResults(msg) {
    const testResultsBox = document.getElementById("testResultsBox");
    if (!testResultsBox) return;

    execStateBadge.className = msg.passed
      ? "exec-state-badge running"
      : "exec-state-badge error";
    execStateBadge.textContent = msg.passed ? "Challenge Passed!" : "Tests Failed";

    if (msg.passed && msg.lessonId) {
      completedLessons.add(msg.lessonId);
      localStorage.setItem(
        STORAGE_KEY_COMPLETED,
        JSON.stringify(Array.from(completedLessons))
      );
      populateLessonSelect();
      lessonSelect.value = String(currentLessonIndex);
      updateProgressUI();
    }

    const cardsHtml = (msg.results || []).map((r) => {
      const statusIcon = r.passed ? "✅ PASS" : "❌ FAIL";
      return `
        <div class="test-case-card ${r.passed ? "pass" : "fail"}">
          <div style="display:flex; justify-content:space-between; font-weight:700; margin-bottom:4px;">
            <span>${escapeHtml(r.name)}</span>
            <span>${statusIcon} (${r.durationMs}ms)</span>
          </div>
          <div style="font-family:var(--font-mono); font-size:11.5px; color:var(--text-secondary);">
            <div><strong>Input:</strong> ${escapeHtml(JSON.stringify(r.stdin.trim()))}</div>
            <div><strong>Expected:</strong> ${escapeHtml(r.expected)}</div>
            <div><strong>Your Output:</strong> ${escapeHtml(r.stdout || "(empty)")}</div>
            ${r.stderr ? `<div style="color:var(--accent-red);"><strong>Error:</strong> ${escapeHtml(r.stderr)}</div>` : ""}
          </div>
        </div>
      `;
    });

    if (msg.passed) {
      cardsHtml.unshift(`
        <div class="test-case-card pass" style="font-weight:700; color:var(--accent-green-bright);">
          🎉 Congratulations! All challenge tests passed!
        </div>
      `);
    }

    testResultsBox.innerHTML = cardsHtml.join("");
  }

  function switchLeftTab(tabName) {
    tabBtns.forEach((btn) => {
      btn.classList.toggle("active", btn.dataset.tab === tabName);
    });
    tabPanels.forEach((panel) => {
      panel.classList.toggle("active", panel.id === `tab-${tabName}`);
    });
  }

  // ==========================================================================
  // 6. Event Listeners & Initialization
  // ==========================================================================

  // Editor events
  codeEditor.addEventListener("input", onEditorModified);
  codeEditor.addEventListener("keydown", handleEditorKeyDown);
  codeEditor.addEventListener("scroll", syncEditorScroll);
  codeEditor.addEventListener("click", updateCursorStatus);
  codeEditor.addEventListener("keyup", updateCursorStatus);

  // Topbar buttons
  runBtn.addEventListener("click", runCurrentCode);
  stopBtn.addEventListener("click", stopRunningCode);
  checkChallengeBtn.addEventListener("click", triggerChallengeVerification);

  wsBadge.addEventListener("click", () => {
    if (!wsConnected) connectWebSocket();
  });

  // Template selector
  templateSelect.addEventListener("change", () => {
    const tpl = templates.find((t) => t.id === templateSelect.value);
    if (tpl) {
      files["main.py"] = tpl.code;
      switchFileTab("main.py");
      templateSelect.value = "";
    }
  });

  // Left pane tabs
  tabBtns.forEach((btn) => {
    btn.addEventListener("click", () => switchLeftTab(btn.dataset.tab));
  });

  lessonSelect.addEventListener("change", () => {
    selectLesson(parseInt(lessonSelect.value, 10));
  });

  prevLessonBtn.addEventListener("click", () => {
    if (currentLessonIndex > 0) selectLesson(currentLessonIndex - 1);
  });

  nextLessonBtn.addEventListener("click", () => {
    if (currentLessonIndex < lessons.length - 1) selectLesson(currentLessonIndex + 1);
  });

  loadStarterBtn.addEventListener("click", () => {
    const lesson = lessons[currentLessonIndex];
    if (lesson) {
      delete savedLessonCode[lesson.id];
      localStorage.setItem(STORAGE_KEY_SAVED_CODE, JSON.stringify(savedLessonCode));
      files["main.py"] = lesson.starterCode;
      switchFileTab("main.py");
    }
  });

  goToChallengeBtn.addEventListener("click", () => {
    switchLeftTab("challenge");
  });

  // Editor toolbar buttons
  fontDecBtn.addEventListener("click", () => {
    editorFontSize = Math.max(11, editorFontSize - 1);
    document.documentElement.style.setProperty("--editor-font-size", `${editorFontSize}px`);
    syncEditorScroll();
  });

  fontIncBtn.addEventListener("click", () => {
    editorFontSize = Math.min(22, editorFontSize + 1);
    document.documentElement.style.setProperty("--editor-font-size", `${editorFontSize}px`);
    syncEditorScroll();
  });

  copyCodeBtn.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(codeEditor.value);
      const orig = copyCodeBtn.textContent;
      copyCodeBtn.textContent = "✓ Copied!";
      setTimeout(() => {
        copyCodeBtn.textContent = orig;
      }, 1400);
    } catch (e) {
      // Ignore clipboard error
    }
  });

  downloadCodeBtn.addEventListener("click", () => {
    const blob = new Blob([codeEditor.value], { type: "text/x-python;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = activeFile || "main.py";
    a.click();
    URL.revokeObjectURL(url);
  });

  resetCodeBtn.addEventListener("click", () => {
    const lesson = lessons[currentLessonIndex];
    if (lesson) {
      files["main.py"] = lesson.starterCode;
      switchFileTab("main.py");
      saveActiveFileContent();
    }
  });

  addFileBtn.addEventListener("click", () => {
    const name = prompt("Enter new file name (e.g., helper.py or notes.txt):", "helper.py");
    if (!name) return;
    const clean = name.trim().replace(/[^a-zA-Z0-9._-]/g, "");
    if (!clean) return;
    if (!files[clean]) {
      files[clean] = clean.endsWith(".py")
        ? `# ${clean} - Helper module\n`
        : "";
    }
    switchFileTab(clean);
  });

  // Terminal & Interactive Stdin Events
  toggleBatchStdinBtn.addEventListener("click", () => {
    batchStdinDrawer.classList.toggle("hidden");
  });

  clearBatchStdinBtn.addEventListener("click", () => {
    batchStdinInput.value = "";
  });

  clearTerminalBtn.addEventListener("click", clearTerminal);

  closeErrorCoachBtn.addEventListener("click", hideErrorCoach);

  inlineTerminalInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      sendInteractiveStdin(inlineTerminalInput.value);
    }
  });

  sendInlineInputBtn.addEventListener("click", () => {
    sendInteractiveStdin(inlineTerminalInput.value);
  });

  bottomStdinInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      sendInteractiveStdin(bottomStdinInput.value);
    }
  });

  bottomStdinSendBtn.addEventListener("click", () => {
    sendInteractiveStdin(bottomStdinInput.value);
  });

  // Boot
  connectWebSocket();
  loadCurriculum();
})();
