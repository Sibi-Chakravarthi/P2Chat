#!/usr/bin/env python3
"""
scripts/build_toxic_vectors.py

Builds offline semantic centroids for P2Chat on-device moderation (T3.4, AI-14, AI-15):
1. Reads Jigsaw training comments from CSV.
2. Partitions into training and held-out test splits with fixed seed (seed=42).
3. Filters to short conversational snippets (length <= 200) with toxic labels.
4. Normalizes with reference normalizer and embeds with shipped quantized .tflite model.
5. Performs K-Means clustering (K=500) to find centroids and assigns majority category labels.
6. Adds curated threat prototypes from scripts/data/threat_prototypes.txt.
7. L2-normalizes all vectors to unit length.
8. Writes:
   - app/src/main/assets/toxic_vectors.bin (N x 1536 bytes, little-endian float32, no header)
   - app/src/main/assets/toxic_vectors_meta.json (JSON array of N category strings)
   - scripts/data/held_out_test_set.json (held-out test split for T3.5 calibration)
"""

import argparse
import csv
import json
import os
import sys
import time
from pathlib import Path

import numpy as np
import tensorflow as tf
from sklearn.cluster import KMeans

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT))

from scripts.ai_reference import ReferenceWordPieceTokenizer, normalize_text

ASSET_DIR = REPO_ROOT / "app" / "src" / "main" / "assets"
DATA_DIR = REPO_ROOT / "scripts" / "data"

DEFAULT_CSV_PATH = DATA_DIR / "train.csv"
DEFAULT_PROTOTYPES_PATH = DATA_DIR / "threat_prototypes.txt"
DEFAULT_TFLITE_PATH = ASSET_DIR / "all-MiniLM-L6-v2-quant.tflite"

OUTPUT_BIN_PATH = ASSET_DIR / "toxic_vectors.bin"
OUTPUT_META_PATH = ASSET_DIR / "toxic_vectors_meta.json"
OUTPUT_TEST_SET_PATH = DATA_DIR / "held_out_test_set.json"

CATEGORY_NAMES = ["toxic", "severe_toxic", "obscene", "threat", "insult", "identity_hate"]


def embed_texts(texts: list[str], interpreter: tf.lite.Interpreter, tokenizer: ReferenceWordPieceTokenizer) -> np.ndarray:
    """
    Embeds a list of texts using the quantized TFLite model and returns L2-normalized vectors.
    """
    output_index = interpreter.get_output_details()[0]["index"]
    vectors = []

    for text in texts:
        norm_text = normalize_text(text)
        ids, mask = tokenizer.encode(norm_text, max_length=128)

        # Tensor 0: attention_mask [1, 128] int32
        # Tensor 1: input_ids [1, 128] int32
        interpreter.set_tensor(0, np.array([mask], dtype=np.int32))
        interpreter.set_tensor(1, np.array([ids], dtype=np.int32))
        interpreter.invoke()

        out_vec = interpreter.get_tensor(output_index)[0].copy()
        norm = np.linalg.norm(out_vec)
        if norm > 1e-9:
            out_vec = out_vec / norm
        vectors.append(out_vec)

    return np.array(vectors, dtype=np.float32)


def load_jigsaw_data(csv_path: Path, max_length_chars: int = 200, seed: int = 42):
    """
    Parses Jigsaw CSV, splits into train and held-out test sets.
    """
    print(f"[build_toxic_vectors] Reading Jigsaw dataset from {csv_path}...")
    toxic_records = []
    clean_records = []

    with open(csv_path, "r", encoding="utf-8", errors="replace") as f:
        reader = csv.reader(f)
        header = next(reader)
        # Verify header structure
        # Expected: ["id", "comment_text", "toxic", "severe_toxic", "obscene", "threat", "insult", "identity_hate"]
        for row in reader:
            if len(row) < 8:
                continue
            text = row[1].strip()
            if not (5 <= len(text) <= max_length_chars):
                continue

            try:
                labels = [int(row[c]) for c in range(2, 8)]
            except ValueError:
                continue

            record = {
                "id": row[0],
                "text": text,
                "labels": labels,
                "is_toxic": int(any(labels)),
            }

            if any(labels):
                toxic_records.append(record)
            elif len(clean_records) < 15000:
                clean_records.append(record)

    print(f"[build_toxic_vectors] Loaded {len(toxic_records)} short toxic samples and {len(clean_records)} short clean samples.")

    # Deterministic split with fixed seed
    rng = np.random.default_rng(seed)
    rng.shuffle(toxic_records)
    rng.shuffle(clean_records)

    # Reserve held-out test split for T3.5 calibration (1,000 toxic + 1,000 clean)
    test_toxic = toxic_records[:1000]
    test_clean = clean_records[:1000]
    held_out_test_set = test_toxic + test_clean
    rng.shuffle(held_out_test_set)

    # Training split (toxic samples only for centroid clustering)
    train_toxic = toxic_records[1000:]
    print(f"[build_toxic_vectors] Split: {len(train_toxic)} train toxic samples, {len(held_out_test_set)} held-out test samples.")

    return train_toxic, held_out_test_set


def build_centroids(
    csv_path: Path,
    prototypes_path: Path,
    tflite_path: Path,
    k_clusters: int = 500,
    seed: int = 42
):
    start_time = time.time()
    tokenizer = ReferenceWordPieceTokenizer()

    print(f"[build_toxic_vectors] Loading TFLite model from {tflite_path}...")
    interpreter = tf.lite.Interpreter(model_path=str(tflite_path))
    interpreter.allocate_tensors()

    train_records, held_out_test_set = load_jigsaw_data(csv_path, seed=seed)

    # Save held-out test split for calibration (T3.5)
    OUTPUT_TEST_SET_PATH.parent.mkdir(parents=True, exist_ok=True)
    with open(OUTPUT_TEST_SET_PATH, "w", encoding="utf-8") as f:
        json.dump(held_out_test_set, f, indent=2, ensure_ascii=False)
    print(f"[build_toxic_vectors] Saved {len(held_out_test_set)} held-out test samples to {OUTPUT_TEST_SET_PATH}.")

    # Limit train records to at most 6,000 to keep K-Means training fast and deterministic
    train_subset = train_records[:6000]
    print(f"[build_toxic_vectors] Embedding {len(train_subset)} training texts with TFLite...")
    train_texts = [r["text"] for r in train_subset]
    train_vectors = embed_texts(train_texts, interpreter, tokenizer)

    # K-Means clustering with K = 500
    print(f"[build_toxic_vectors] Fitting KMeans (K={k_clusters}, seed={seed})...")
    kmeans = KMeans(n_clusters=k_clusters, random_state=seed, n_init=1)
    kmeans.fit(train_vectors)

    cluster_labels = kmeans.labels_
    cluster_centers = kmeans.cluster_centers_

    # Determine majority category for each cluster
    centroid_categories = []
    for c_idx in range(k_clusters):
        member_indices = np.where(cluster_labels == c_idx)[0]
        if len(member_indices) == 0:
            centroid_categories.append("toxic")
            continue

        # Count frequencies of the 6 categories in this cluster
        cat_counts = np.zeros(6, dtype=int)
        for m_idx in member_indices:
            cat_counts += np.array(train_subset[m_idx]["labels"])

        majority_cat_idx = int(np.argmax(cat_counts))
        centroid_categories.append(CATEGORY_NAMES[majority_cat_idx])

    # L2-normalize cluster centroids
    normalized_centroids = []
    for c in cluster_centers:
        norm = np.linalg.norm(c)
        if norm > 1e-9:
            c = c / norm
        normalized_centroids.append(c.astype(np.float32))

    # Load and embed curated threat prototypes
    print(f"[build_toxic_vectors] Loading threat prototypes from {prototypes_path}...")
    prototype_texts = []
    with open(prototypes_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                prototype_texts.append(line)

    print(f"[build_toxic_vectors] Embedding {len(prototype_texts)} threat prototypes...")
    prototype_vectors = embed_texts(prototype_texts, interpreter, tokenizer)

    # Combine K-Means centroids and threat prototypes
    all_vectors = list(normalized_centroids)
    all_categories = list(centroid_categories)

    for p_vec in prototype_vectors:
        all_vectors.append(p_vec.astype(np.float32))
        all_categories.append("prototype:threat")

    total_vectors = len(all_vectors)
    print(f"[build_toxic_vectors] Combined total vectors: {total_vectors} ({k_clusters} clusters + {len(prototype_texts)} prototypes)")

    # Validate norms and array shape
    final_array = np.array(all_vectors, dtype=np.float32)
    assert final_array.shape == (total_vectors, 384), f"Shape mismatch: {final_array.shape}"

    for i in range(total_vectors):
        row_norm = float(np.linalg.norm(final_array[i]))
        assert abs(row_norm - 1.0) < 1e-4, f"Vector {i} norm is not 1.0: {row_norm}"

    # Write toxic_vectors.bin (flat little-endian float32 array with no header)
    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    raw_bytes = final_array.astype("<f4").tobytes()
    expected_bytes = total_vectors * 384 * 4  # N * 1,536 bytes
    assert len(raw_bytes) == expected_bytes, f"Expected {expected_bytes} bytes, got {len(raw_bytes)}"

    with open(OUTPUT_BIN_PATH, "wb") as f:
        f.write(raw_bytes)
    print(f"[build_toxic_vectors] Wrote {len(raw_bytes):,} bytes to {OUTPUT_BIN_PATH} (N={total_vectors})")

    # Write toxic_vectors_meta.json (sidecar JSON list of categories, length == N)
    with open(OUTPUT_META_PATH, "w", encoding="utf-8") as f:
        json.dump(all_categories, f, indent=2)
    print(f"[build_toxic_vectors] Wrote {len(all_categories)} categories to {OUTPUT_META_PATH}")

    elapsed = time.time() - start_time
    print(f"[build_toxic_vectors] Centroids build finished in {elapsed:.1f} s.")
    return total_vectors


def main():
    parser = argparse.ArgumentParser(description="P2Chat Offline Centroid Builder (T3.4)")
    parser.add_argument("--csv", type=Path, default=DEFAULT_CSV_PATH, help="Path to Jigsaw train.csv")
    parser.add_argument("--prototypes", type=Path, default=DEFAULT_PROTOTYPES_PATH, help="Path to threat_prototypes.txt")
    parser.add_argument("--tflite", type=Path, default=DEFAULT_TFLITE_PATH, help="Path to quantized .tflite model")
    parser.add_argument("--clusters", type=int, default=500, help="Number of KMeans clusters")
    parser.add_argument("--seed", type=int, default=42, help="Random seed for reproducibility")
    args = parser.parse_args()

    if not args.csv.is_file():
        print(f"[build_toxic_vectors] ERROR: CSV file not found at {args.csv}!", file=sys.stderr)
        return 1

    build_centroids(
        csv_path=args.csv,
        prototypes_path=args.prototypes,
        tflite_path=args.tflite,
        k_clusters=args.clusters,
        seed=args.seed
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
