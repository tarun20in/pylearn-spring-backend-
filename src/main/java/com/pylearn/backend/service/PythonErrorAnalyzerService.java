package com.pylearn.backend.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PythonErrorAnalyzerService {

    private static final Pattern LINE_PATTERN = Pattern.compile("File \".*?\", line (\\d+)");
    private static final Pattern NAME_ERROR_PATTERN = Pattern.compile("name '([^']+)' is not defined");

    public Map<String, Object> analyzeError(String stderrText) {
        if (stderrText == null || stderrText.isBlank()) {
            return null;
        }

        Integer lineNumber = null;
        Matcher lineMatcher = LINE_PATTERN.matcher(stderrText);
        while (lineMatcher.find()) {
            lineNumber = Integer.parseInt(lineMatcher.group(1));
        }

        if (stderrText.contains("SyntaxError:")) {
            return buildHint(
                    "SyntaxError",
                    lineNumber,
                    "Syntax Error — Python couldn't parse this line",
                    "Check for missing colons (:) at the end of if/for/while/def statements, unclosed quotes, or unmatched parentheses."
            );
        }
        if (stderrText.contains("IndentationError:")) {
            return buildHint(
                    "IndentationError",
                    lineNumber,
                    "Indentation Error — Spacing alignment issue",
                    "Python uses 4 spaces to group code inside blocks after a colon (:). Ensure all lines inside a block are aligned consistently."
            );
        }
        if (stderrText.contains("NameError:")) {
            Matcher m = NAME_ERROR_PATTERN.matcher(stderrText);
            String varName = m.find() ? m.group(1) : "a variable";
            return buildHint(
                    "NameError",
                    lineNumber,
                    "NameError — '" + varName + "' is not defined",
                    "Check spelling and capitalization for '" + varName + "', or assign a value to it before using it."
            );
        }
        if (stderrText.contains("TypeError:")) {
            return buildHint(
                    "TypeError",
                    lineNumber,
                    "TypeError — Incompatible data types combined",
                    "Remember that input() returns a string (str). Convert numeric inputs using int(input(...)) or float(input(...))."
            );
        }
        if (stderrText.contains("ValueError:")) {
            return buildHint(
                    "ValueError",
                    lineNumber,
                    "ValueError — Invalid value conversion",
                    "int() or float() received text that is not a valid number. Enter digits or handle invalid inputs with try / except ValueError."
            );
        }
        if (stderrText.contains("ZeroDivisionError:")) {
            return buildHint(
                    "ZeroDivisionError",
                    lineNumber,
                    "ZeroDivisionError — Division by zero",
                    "Check that your denominator is not zero before dividing."
            );
        }
        if (stderrText.contains("EOFError:")) {
            return buildHint(
                    "EOFError",
                    lineNumber,
                    "EOFError — input() or pickle.load() reached end of stream",
                    "Your program called input() without receiving a line, or pickle.load() reached the end of the binary file."
            );
        }
        if (stderrText.contains("IndexError:")) {
            return buildHint(
                    "IndexError",
                    lineNumber,
                    "IndexError — Sequence index out of range",
                    "Python indices start at 0 up to len(seq) - 1. Check your loop bounds and list size."
            );
        }

        return buildHint(
                "RuntimeError",
                lineNumber,
                "Python Runtime Error",
                "Check the last line of the traceback for the exact error type and line number."
        );
    }

    private Map<String, Object> buildHint(String type, Integer line, String title, String tip) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("line", line);
        map.put("title", title);
        map.put("tip", tip);
        return map;
    }
}
