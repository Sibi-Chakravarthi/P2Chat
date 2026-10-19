#!/usr/bin/env python3
"""
scripts/gen_embedder_fixtures.py

Generates 20 deterministic reference embedding fixtures using the shipped
quantized TFLite model (all-MiniLM-L6-v2-quant.tflite).
Matches the exact token_ids and attention_mask generated in T3.3 / T3.7.

Outputs:
- app/src/test/resources/ai/embedder_fixtures.json
- app/src/androidTest/assets/embedder_fixtures.json
"""

import json
import sys
from pathlib import Path
import numpy as np

REPO_ROOT = Path(__file__).resolve().parent.parent
MODEL_PATH = REPO_ROOT / "app" / "src" / "main" / "assets" / "all-MiniLM-L6-v2-quant.tflite"
TOKENIZER_FIXTURES_PATH = REPO_ROOT / "app" / "src" / "test" / "resources" / "ai" / "tokenizer_fixtures.json"

TEST_RESOURCES_OUT = REPO_ROOT / "app" / "src" / "test" / "resources" / "ai" / "embedder_fixtures.json"
ANDROID_TEST_ASSETS_DIR = REPO_ROOT / "app" / "src" / "androidTest" / "assets"
ANDROID_TEST_OUT = ANDROID_TEST_ASSETS_DIR / "embedder_fixtures.json"


def main() -> int:
    import tensorflow as tf

    if not MODEL_PATH.is_file():
        print(f"ERROR: Model file not found at {MODEL_PATH}", file=sys.stderr)
        return 1
    if not TOKENIZER_FIXTURES_PATH.is_file():
        print(f"ERROR: Tokenizer fixtures not found at {TOKENIZER_FIXTURES_PATH}", file=sys.stderr)
        return 1

    with open(TOKENIZER_FIXTURES_PATH, "r", encoding="utf-8") as f:
        tok_data = json.load(f)

    fixtures = tok_data["fixtures"][:20]

    interpreter = tf.lite.Interpreter(model_path=str(MODEL_PATH))
    interpreter.allocate_tensors()

    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    # Locate input tensor indices by name
    mask_idx = None
    ids_idx = None
    for item in input_details:
        name = item["name"].lower()
        if "mask" in name:
            mask_idx = item["index"]
        elif "input_ids" in name or "ids" in name:
            ids_idx = item["index"]

    if mask_idx is None or ids_idx is None:
        # Fallback to positional 0 and 1
        mask_idx = input_details[0]["index"]
        ids_idx = input_details[1]["index"]

    out_idx = output_details[0]["index"]

    embed_fixtures = []

    for f in fixtures:
        input_ids = np.array([f["token_ids"]], dtype=np.int32)
        attention_mask = np.array([f["attention_mask"]], dtype=np.int32)

        interpreter.set_tensor(mask_idx, attention_mask)
        interpreter.set_tensor(ids_idx, input_ids)
        interpreter.invoke()

        raw_out = interpreter.get_tensor(out_idx)[0]  # shape (384,)
        norm = float(np.linalg.norm(raw_out))
        if norm > 1e-9:
            l2_vec = raw_out / norm
        else:
            l2_vec = np.zeros_like(raw_out)

        embed_fixtures.append({
            "id": f["id"],
            "input": f["input"],
            "normalized": f["normalized"],
            "token_ids": f["token_ids"],
            "attention_mask": f["attention_mask"],
            "embedding": [round(float(v), 8) for v in l2_vec],
            "norm": round(norm, 6),
        })

    payload = {
        "generator": "scripts/gen_embedder_fixtures.py",
        "total_fixtures": len(embed_fixtures),
        "embedding_dim": 384,
        "fixtures": embed_fixtures
    }

    TEST_RESOURCES_OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(TEST_RESOURCES_OUT, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)
    print(f"Wrote {len(embed_fixtures)} fixtures to {TEST_RESOURCES_OUT}")

    ANDROID_TEST_ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    with open(ANDROID_TEST_OUT, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)
    print(f"Wrote {len(embed_fixtures)} fixtures to {ANDROID_TEST_OUT}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
