#!/usr/bin/env python3
"""Turn captured Gradle output into GitHub Actions check-run annotations.

The sandboxed environment cannot download CI logs, so the workflow tees Gradle
output into a file and this script re-emits the interesting lines as
::error annotations, which are readable through the GitHub API.

Usage: annotate-gradle-errors.py <logfile> [<logfile> ...]
"""

import os
import re
import sys

MAX_ANNOTATIONS = 60


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def repo_relative(path: str) -> str:
    workspace = os.environ.get("GITHUB_WORKSPACE")
    if workspace and path.startswith(workspace):
        return path[len(workspace) + 1 :]
    marker = "/fcmself/"
    idx = path.rfind(marker)
    if idx >= 0:
        return path[idx + len(marker) :]
    return path


# Kotlin: e: file:///abs/path/File.kt:12:34 message
KOTLIN = re.compile(r"^e:\s+file://(.+?):(\d+):(\d+)\s*(.*)$")
# javac: /abs/path/File.java:12: error: message
JAVAC = re.compile(r"^(.*?\.java):(\d+):\s+error:\s*(.*)$")
# aapt2: /abs/path/res/values/styles.xml:12:5-8: AAPT: error: message
AAPT = re.compile(r"^(.*?\.xml):(\d+):\d+(?:-\d+)?:\s+AAPT: error:\s*(.*)$")
# Fallback markers for failures without a file location.
TASK = re.compile(r"Execution failed for task\s+':([^']+)'")
FAILURE = re.compile(r"^FAILURE:\s*(.*)$")


def main() -> int:
    seen = set()
    emitted = 0

    def emit(title: str, file: str, line: str, message: str) -> bool:
        nonlocal emitted
        if emitted >= MAX_ANNOTATIONS:
            return False
        key = (title, file, line, message)
        if key in seen:
            return True
        seen.add(key)
        if file:
            print(
                f"::error file={file},line={line},title={title}::{escape(message)}"
            )
        else:
            print(f"::error title={title}::{escape(message)}")
        emitted += 1
        return emitted < MAX_ANNOTATIONS

    for logfile in sys.argv[1:]:
        try:
            with open(logfile, encoding="utf-8", errors="replace") as f:
                lines = f.readlines()
        except OSError as e:
            print(f"::warning title=annotate-gradle-errors::{escape(str(e))}")
            continue
        for raw in lines:
            line = raw.rstrip("\n")
            m = KOTLIN.match(line)
            if m:
                emit(
                    "Kotlin compile",
                    repo_relative(m.group(1)),
                    m.group(2),
                    m.group(4).strip(),
                )
                continue
            m = JAVAC.match(line)
            if m:
                emit(
                    "Java compile",
                    repo_relative(m.group(1)),
                    m.group(2),
                    m.group(3).strip(),
                )
                continue
            m = AAPT.match(line)
            if m:
                emit(
                    "Resource link",
                    repo_relative(m.group(1)),
                    m.group(2),
                    m.group(3).strip(),
                )
                continue
            m = TASK.search(line)
            if m:
                emit("Gradle task", "", "", line.strip())
                continue
            m = FAILURE.match(line)
            if m:
                emit("Build failure", "", "", line.strip())
                continue
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
