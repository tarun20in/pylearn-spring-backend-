#!/usr/bin/env python3
"""
PyLearn CBSE Studio — Line-by-Line Code Video Animation Generator
Executes student Python code with sys.settrace, captures variable state & stdout,
and renders an animated 720p H.264 MP4 video visualization using Pillow & imageio.
"""

import sys
import os
import io
import json
import argparse
import numpy as np
import imageio
from PIL import Image, ImageDraw, ImageFont

def trace_execution(code: str, max_steps: int = 50):
    lines = code.splitlines()
    steps = []
    stdout_capture = io.StringIO()
    old_stdout = sys.stdout

    trace_locals = {}
    prev_vars = {}

    def tracer(frame, event, arg):
        if event == 'line':
            if len(steps) >= max_steps:
                return None
            lineno = frame.f_lineno
            # Filter variables
            cur_vars = {}
            for k, v in frame.f_locals.items():
                if not k.startswith('_') and not callable(v):
                    try:
                        cur_vars[k] = repr(v)
                    except:
                        cur_vars[k] = str(v)

            # Find which variable changed
            changed = None
            for k, v in cur_vars.items():
                if k not in prev_vars or prev_vars[k] != v:
                    changed = k
                    break

            sys.stdout.flush()
            out_so_far = stdout_capture.getvalue()

            stmt_text = lines[lineno - 1] if 0 <= lineno - 1 < len(lines) else ''

            steps.append({
                'line': lineno,
                'stmt': stmt_text.strip(),
                'vars': dict(cur_vars),
                'changed': changed,
                'output': out_so_far
            })
            prev_vars.clear()
            prev_vars.update(cur_vars)
        return tracer

    sys.stdout = stdout_capture
    try:
        compiled = compile(code, '<student_code>', 'exec')
        sys.settrace(tracer)
        exec(compiled, trace_locals)
    except Exception as e:
        sys.stdout.flush()
        steps.append({
            'line': len(lines),
            'stmt': f'# Runtime Exception: {type(e).__name__}',
            'vars': dict(prev_vars),
            'changed': None,
            'output': stdout_capture.getvalue() + f"\n[Runtime Error: {e}]"
        })
    finally:
        sys.settrace(None)
        sys.stdout = old_stdout

    # Fallback if no steps captured
    if not steps:
        steps.append({
            'line': 1,
            'stmt': lines[0] if lines else '# empty code',
            'vars': {},
            'changed': None,
            'output': stdout_capture.getvalue()
        })

    return lines, steps

def render_video(lines, steps, output_mp4, title="main.py"):
    W, H = 1280, 720
    fps = 20
    frames_per_step = 24  # ~1.2 seconds per step

    os.makedirs(os.path.dirname(os.path.abspath(output_mp4)), exist_ok=True)
    writer = imageio.get_writer(output_mp4, fps=fps, codec='libx264', pixelformat='yuv420p')

    # Fonts loading with robust fallbacks
    try:
        font_code = ImageFont.truetype("consola.ttf", 16)
        font_code_bold = ImageFont.truetype("consolab.ttf", 17)
        font_ui = ImageFont.truetype("arial.ttf", 14)
        font_ui_bold = ImageFont.truetype("arialbd.ttf", 15)
        font_title = ImageFont.truetype("arialbd.ttf", 18)
    except:
        font_code = font_code_bold = font_ui = font_ui_bold = font_title = ImageFont.load_default()

    total_steps = len(steps)

    for step_idx, step in enumerate(steps):
        cur_line = step['line']
        cur_vars = step['vars']
        changed_var = step['changed']
        out_text = step['output'].strip()

        img = Image.new("RGB", (W, H), color=(15, 23, 42))  # Slate-900 background
        draw = ImageDraw.Draw(img)

        # 1. Top Header Bar
        draw.rectangle([0, 0, W, 50], fill=(30, 41, 59))
        draw.line([0, 50, W, 50], fill=(51, 65, 85), width=1)
        draw.text((20, 15), f"🐍 PyLearn CBSE Studio — Line-by-Line Code Video ({title})", font=font_title, fill=(248, 250, 252))

        step_badge_text = f"Step {step_idx + 1} of {total_steps}"
        draw.rectangle([W - 170, 12, W - 20, 38], fill=(79, 70, 229), outline=(99, 102, 241), width=1)
        draw.text((W - 150, 16), step_badge_text, font=font_ui_bold, fill=(255, 255, 255))

        # 2. Left Pane: Source Code Editor (x: 20 to 660, y: 65 to 695)
        draw.rectangle([20, 65, 660, 695], fill=(24, 32, 47), outline=(51, 65, 85), width=1)
        draw.rectangle([20, 65, 660, 100], fill=(30, 41, 59))
        draw.line([20, 100, 660, 100], fill=(51, 65, 85), width=1)
        draw.text((35, 75), f"📄 {title}", font=font_ui_bold, fill=(203, 213, 225))

        # Gutter & Code lines
        line_y_start = 115
        line_height = 28
        gutter_w = 45

        draw.rectangle([20, 100, 20 + gutter_w, 695], fill=(19, 26, 38))
        draw.line([20 + gutter_w, 100, 20 + gutter_w, 695], fill=(40, 53, 72), width=1)

        # Center visible window around cur_line if code is long
        start_line_idx = max(0, cur_line - 10)
        end_line_idx = min(len(lines), start_line_idx + 19)

        for l_idx in range(start_line_idx, end_line_idx):
            ln_num = l_idx + 1
            line_str = lines[l_idx]
            y = line_y_start + (l_idx - start_line_idx) * line_height

            is_active = (ln_num == cur_line)
            if is_active:
                draw.rectangle([20 + gutter_w + 1, y - 4, 659, y + line_height - 6], fill=(59, 130, 246), outline=(96, 165, 250), width=1)
                draw.text((26, y), "▶", font=font_code_bold, fill=(96, 165, 250))
            else:
                draw.text((30, y), str(ln_num), font=font_code, fill=(100, 116, 139))

            # Syntax styling
            text_color = (241, 245, 249)
            stripped = line_str.strip()
            if stripped.startswith("#"):
                text_color = (148, 163, 184)
            elif any(stripped.startswith(kw) for kw in ["for ", "if ", "def ", "while ", "import ", "from ", "class ", "return "]):
                text_color = (192, 132, 252)

            draw.text((20 + gutter_w + 14, y), line_str, font=font_code, fill=text_color)

        # 3. Right Pane: Variable State & Memory (x: 680 to 1260, y: 65 to 390)
        draw.rectangle([680, 65, 1260, 390], fill=(24, 32, 47), outline=(51, 65, 85), width=1)
        draw.rectangle([680, 65, 1260, 100], fill=(30, 41, 59))
        draw.line([680, 100, 1260, 100], fill=(51, 65, 85), width=1)
        draw.text((695, 75), "🧠 Variable State & Memory Inspector", font=font_ui_bold, fill=(203, 213, 225))

        if not cur_vars:
            draw.text((710, 125), "No variables in scope at this step.", font=font_ui, fill=(148, 163, 184))
        else:
            vy = 115
            for vname, vval in cur_vars.items():
                if vy >= 355:
                    break
                is_changed = (vname == changed_var)
                card_bg = (55, 48, 20) if is_changed else (30, 41, 59)
                card_border = (234, 179, 8) if is_changed else (71, 85, 105)

                draw.rectangle([695, vy, 1245, vy + 36], fill=card_bg, outline=card_border, width=2 if is_changed else 1)
                draw.text((710, vy + 8), f"{vname}", font=font_code_bold, fill=(254, 240, 138) if is_changed else (147, 197, 253))
                draw.text((820, vy + 9), "=", font=font_code, fill=(148, 163, 184))
                draw.text((845, vy + 8), str(vval)[:40], font=font_code, fill=(255, 255, 255))

                if is_changed:
                    draw.rectangle([1150, vy + 6, 1235, vy + 30], fill=(234, 179, 8))
                    draw.text((1158, vy + 8), "UPDATED", font=font_ui_bold, fill=(0, 0, 0))

                vy += 44

        # 4. Right Pane Bottom: Console Output (x: 680 to 1260, y: 410 to 695)
        draw.rectangle([680, 410, 1260, 695], fill=(15, 23, 42), outline=(51, 65, 85), width=1)
        draw.rectangle([680, 410, 1260, 445], fill=(30, 41, 59))
        draw.line([680, 445, 1260, 445], fill=(51, 65, 85), width=1)
        draw.text((695, 420), "🖥️ Terminal Console Output (stdout)", font=font_ui_bold, fill=(203, 213, 225))

        if out_text:
            out_lines = out_text.splitlines()[-8:]
            for oi, o_line in enumerate(out_lines):
                draw.text((695, 460 + oi * 22), o_line, font=font_code, fill=(52, 211, 153))
        else:
            draw.text((695, 460), "(waiting for print() output...)", font=font_code, fill=(100, 116, 139))

        # 5. Bottom Progress Bar (y: 712 to 720)
        progress_pct = (step_idx + 1) / total_steps
        draw.rectangle([0, 712, W, 720], fill=(30, 41, 59))
        draw.rectangle([0, 712, int(W * progress_pct), 720], fill=(99, 102, 241))

        frame_np = np.array(img)
        for _ in range(frames_per_step):
            writer.append_data(frame_np)

    writer.close()
    duration = total_steps * (frames_per_step / fps)
    return total_steps, duration

def main():
    parser = argparse.ArgumentParser(description="Render line-by-line Python execution video")
    parser.add_argument("--code-file", required=True, help="Path to input Python code file")
    parser.add_argument("--output", required=True, help="Output MP4 file path")
    parser.add_argument("--title", default="main.py", help="Display title in video")
    args = parser.parse_args()

    with open(args.code_file, "r", encoding="utf-8") as f:
        code = f.read()

    lines, steps = trace_execution(code)
    steps_count, duration = render_video(lines, steps, args.output, title=args.title)

    result = {
        "success": True,
        "videoFile": args.output,
        "stepsCount": steps_count,
        "durationSeconds": round(duration, 1),
        "fileSizeBytes": os.path.getsize(args.output) if os.path.exists(args.output) else 0
    }
    print(f"PYLEARN_VIDEO_RESULT:{json.dumps(result)}")

if __name__ == "__main__":
    main()
