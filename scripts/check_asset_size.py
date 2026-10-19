#!/usr/bin/env python3
"""
scripts/check_asset_size.py

Enforces the < 30 MB on-device AI asset budget (PRD §8, §11, Decision 2).
Monitors:
- app/src/main/assets/all-MiniLM-L6-v2-quant.tflite
- app/src/main/assets/vocab.txt
- app/src/main/assets/toxic_vectors.bin
"""

import sys
from pathlib import Path

MAX_BUDGET_BYTES = 30 * 1024 * 1024  # 30 Megabytes

ASSET_DIR = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets"

TRACKED_ASSETS = [
    "all-MiniLM-L6-v2-quant.tflite",
    "vocab.txt",
    "toxic_vectors.bin",
]


def main() -> int:
    print("[check_asset_size] Checking on-device AI asset budget (< 30 MB)...")

    if not ASSET_DIR.is_dir():
        print(f"[check_asset_size] NOTICE: Assets directory {ASSET_DIR} does not exist yet (M0-M2 stage). Budget: 0 B / 30 MB. PASS.")
        return 0

    total_size = 0
    found_assets = 0

    for name in TRACKED_ASSETS:
        p = ASSET_DIR / name
        if p.is_file():
            size = p.stat().st_size
            total_size += size
            found_assets += 1
            print(f"  - {name}: {size:,} bytes ({size / (1024 * 1024):.2f} MB)")
        else:
            print(f"  - {name}: Not present (will be added in M3)")

    print(f"[check_asset_size] Total tracked assets size: {total_size:,} bytes ({total_size / (1024 * 1024):.2f} MB)")

    if total_size > MAX_BUDGET_BYTES:
        print(
            f"[check_asset_size] FAILED: Total size {total_size:,} bytes exceeds 30 MB limit ({MAX_BUDGET_BYTES:,} bytes)!",
            file=sys.stderr,
        )
        return 1

    print(f"[check_asset_size] SUCCESS: Assets within budget ({total_size:,} <= {MAX_BUDGET_BYTES:,} bytes).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
