#!/usr/bin/env python3
"""
check_secure_log.py: Enforces SEC-16 compile/source-time invariant.
Verifies that no direct android.util.Log or println calls exist in production
code outside of SecureLog.kt.
"""

import os
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MAIN_JAVA_DIR = REPO_ROOT / "app" / "src" / "main" / "java"

FORBIDDEN_PATTERNS = [
    (re.compile(r"\bLog\.[vdiew]|Log\.wtf\("), "Direct android.util.Log usage (use SecureLog instead)"),
    (re.compile(r"\bprintln\("), "Direct println() usage (use SecureLog instead)"),
    (re.compile(r"\bprint\("), "Direct print() usage (use SecureLog instead)"),
    (re.compile(r"System\.(out|err)\.print"), "Direct System.out/err usage (use SecureLog instead)"),
]

EXCLUDED_FILES = {
    "SecureLog.kt"
}

def scan_files():
    violations = []
    
    if not MAIN_JAVA_DIR.exists():
        print(f"[check_secure_log] Directory not found: {MAIN_JAVA_DIR}")
        return 0

    for root, _, files in os.walk(MAIN_JAVA_DIR):
        for file in files:
            if not (file.endswith(".kt") or file.endswith(".java")):
                continue
            if file in EXCLUDED_FILES:
                continue

            filepath = Path(root) / file
            rel_path = filepath.relative_to(REPO_ROOT)

            try:
                with open(filepath, "r", encoding="utf-8") as f:
                    for line_num, line in enumerate(f, 1):
                        stripped = line.strip()
                        # Ignore comments
                        if stripped.startswith("//") or stripped.startswith("*"):
                            continue
                        for pattern, desc in FORBIDDEN_PATTERNS:
                            if pattern.search(line):
                                violations.append((str(rel_path), line_num, stripped, desc))
            except Exception as e:
                print(f"[check_secure_log] Error reading {rel_path}: {e}", file=sys.stderr)
                return 1

    if violations:
        print(f"\n[check_secure_log] FAILED: Found {len(violations)} SEC-16 logging violation(s):", file=sys.stderr)
        for path, line_num, line_content, desc in violations:
            print(f"  {path}:{line_num} -> {desc}", file=sys.stderr)
            print(f"    Line: {line_content}", file=sys.stderr)
        return 1

    print("[check_secure_log] SUCCESS: Zero direct Log/println calls found outside SecureLog.kt (SEC-16 compliant).")
    return 0

if __name__ == "__main__":
    sys.exit(scan_files())
