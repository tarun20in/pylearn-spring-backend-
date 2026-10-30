"""
runner.py - Interactive Python Execution Wrapper for Spring Boot WebSocket Backend
Ensures unbuffered UTF-8 streaming, real-time input() detection over pipes,
and clean student-friendly tracebacks.
"""

import builtins
import os
import sys
import traceback

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", write_through=True)
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", write_through=True)
if hasattr(sys.stdin, "reconfigure"):
    sys.stdin.reconfigure(encoding="utf-8")

INPUT_SIGNAL = "\x00__WS_INPUT_REQUEST__\x00"


def _interactive_input(prompt=""):
    sys.stderr.flush()
    if prompt is not None and str(prompt) != "":
        sys.stdout.write(str(prompt))
    sys.stdout.write(INPUT_SIGNAL)
    sys.stdout.flush()

    line = sys.stdin.readline()
    if line == "":
        raise EOFError("EOF when reading a line (no input received)")
    return line.rstrip("\r\n")


builtins.input = _interactive_input


def run_user_script(script_path):
    script_abs = os.path.abspath(script_path)
    script_dir = os.path.dirname(script_abs)
    if script_dir not in sys.path:
        sys.path.insert(0, script_dir)

    with open(script_abs, "r", encoding="utf-8") as f:
        source = f.read()

    user_globals = {
        "__name__": "__main__",
        "__file__": os.path.basename(script_abs),
        "__builtins__": builtins,
    }

    try:
        code_obj = compile(source, os.path.basename(script_abs), "exec")
        exec(code_obj, user_globals)
    except SyntaxError as exc:
        sys.stdout.flush()
        lines = traceback.format_exception_only(type(exc), exc)
        sys.stderr.write("".join(lines))
        sys.stderr.flush()
        sys.exit(1)
    except SystemExit as exc:
        sys.stdout.flush()
        sys.stderr.flush()
        code = exc.code if isinstance(exc.code, int) else (0 if exc.code is None else 1)
        sys.exit(code)
    except BaseException as exc:
        sys.stdout.flush()
        tb = exc.__traceback__
        if tb is not None and tb.tb_next is not None:
            tb = tb.tb_next
        formatted = traceback.format_exception(type(exc), exc, tb)
        sys.stderr.write("".join(formatted))
        sys.stderr.flush()
        sys.exit(1)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.stderr.write("Usage: python -u runner.py <script.py>\n")
        sys.exit(1)
    target_script = sys.argv[1]
    sys.argv = sys.argv[1:]
    run_user_script(target_script)
