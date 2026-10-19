#!/usr/bin/env python3
"""
scripts/check_wire_parity.py

Verifies 100% constant parity between:
- app/src/main/java/com/p2chat/wire/BleConstants.kt
- scripts/wire_spec.py

Fails with non-zero exit code if any constant is missing or has a differing value.
"""

import sys
import re
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
KOTLIN_FILE = ROOT_DIR / "app" / "src" / "main" / "java" / "com" / "p2chat" / "wire" / "BleConstants.kt"
PYTHON_FILE = ROOT_DIR / "scripts" / "wire_spec.py"


def parse_kotlin_constants(path: Path) -> dict:
    if not path.is_file():
        raise FileNotFoundError(f"Kotlin constants file not found: {path}")

    constants = {}
    pattern = re.compile(
        r'const\s+val\s+([A-Za-z0-9_]+)\s*:\s*([A-Za-z0-9_]+)\s*=\s*(.+)'
    )

    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        match = pattern.match(line)
        if match:
            name, type_str, val_str = match.groups()
            val_str = val_str.split("//")[0].strip()  # strip inline comments
            if type_str in ("Byte", "Int", "Long"):
                # Handle hex or decimal, and suffix like L
                val_str = val_str.rstrip("L").rstrip("l")
                val = int(val_str, 0)
            elif type_str == "String":
                val = val_str.strip('"')
            else:
                val = val_str
            constants[name] = val

    return constants


def parse_python_constants(path: Path) -> dict:
    if not path.is_file():
        raise FileNotFoundError(f"Python constants file not found: {path}")

    constants = {}
    pattern = re.compile(
        r'^([A-Z0-9_]+)\s*(?::\s*[A-Za-z0-9_]+)?\s*=\s*(.+)'
    )

    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        match = pattern.match(line)
        if match:
            name, val_str = match.groups()
            val_str = val_str.split("#")[0].strip()
            if val_str.startswith('"') and val_str.endswith('"'):
                val = val_str.strip('"')
            elif val_str.startswith("'") and val_str.endswith("'"):
                val = val_str.strip("'")
            else:
                try:
                    val = int(val_str, 0)
                except ValueError:
                    val = val_str
            constants[name] = val

    return constants


def main() -> int:
    print(f"[check_wire_parity] Checking parity between:")
    print(f"  - Kotlin: {KOTLIN_FILE}")
    print(f"  - Python: {PYTHON_FILE}")

    try:
        kt_consts = parse_kotlin_constants(KOTLIN_FILE)
        py_consts = parse_python_constants(PYTHON_FILE)
    except Exception as e:
        print(f"ERROR: Failed to read constants: {e}", file=sys.stderr)
        return 1

    all_keys = sorted(set(kt_consts.keys()) | set(py_consts.keys()))
    mismatches = []

    for key in all_keys:
        if key not in kt_consts:
            mismatches.append(f"Missing in Kotlin: {key} (Python value: {py_consts[key]})")
        elif key not in py_consts:
            mismatches.append(f"Missing in Python: {key} (Kotlin value: {kt_consts[key]})")
        elif kt_consts[key] != py_consts[key]:
            mismatches.append(
                f"Value mismatch for '{key}': Kotlin={kt_consts[key]!r} vs Python={py_consts[key]!r}"
            )

    if mismatches:
        print(f"[check_wire_parity] FAILED: {len(mismatches)} parity error(s) found:", file=sys.stderr)
        for m in mismatches:
            print(f"  ❌ {m}", file=sys.stderr)
        return 1

    print(f"[check_wire_parity] SUCCESS: All {len(all_keys)} wire constants match 100% across Kotlin and Python.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
