# AI Assets Specification & Verification (T3.2, AI-4)

This document records the exact specifications, tensor contracts, quantization details, cryptographic digests, baseline evaluations, and quality bar benchmark results for on-device AI assets in P2Chat.

---

## 1. Asset Inventory & Hashes

| Asset | Path | Size | SHA-256 | Purpose |
|---|---|---|---|---|
| **Quantized Model** | `app/src/main/assets/all-MiniLM-L6-v2-quant.tflite` | ~21.74 MB | `5d464cae0e38e59f08ffeaf86b2a72ec459c74b3518425126eaf571ebc6c9367` | 384-d semantic sentence embedder |
| **WordPiece Vocab** | `app/src/main/assets/vocab.txt` | 231,508 bytes | `07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3` | BERT uncased 30,522-token vocabulary |
| **Toxic Centroids** | `app/src/main/assets/toxic_vectors.bin` | 806,400 bytes | `5368a1fba9aa2bbd71f762299c89eecb08499ee0632a0c4f826359f4fbb4e5dc` | 525 L2-normalized 384-d centroids (K=500 + 25 prototypes) |
| **Category Meta** | `app/src/main/assets/toxic_vectors_meta.json` | 6,594 bytes | `36c4b22f6a73c2331599a0d783d463fa7dbfeea798159b3a0ceb68a7b686d1ee` | Centroid category tags & prototypes |

**Budget Check:** Total combined assets: `~22.73 MB / 30.0 MB` (enforced by `scripts/check_asset_size.py`).

---

## 2. TFLite Tensor Specification

The model is a post-training INT8 dynamic quantized `sentence-transformers/all-MiniLM-L6-v2` with internal mean pooling and L2 normalization:

### Inputs (Fixed Length = 128)
| Index | Tensor Name | Type | Shape | Description |
|---|---|---|---|---|
| `0` | `serving_default_attention_mask:0` | `int32` | `[1, 128]` | Attention mask (1 for valid token, 0 for padding) |
| `1` | `serving_default_input_ids:0` | `int32` | `[1, 128]` | Token IDs produced by WordPiece tokenizer |

### Outputs
| Index | Tensor Name | Type | Shape | Description |
|---|---|---|---|---|
| `696` | `StatefulPartitionedCall:0` | `float32` | `[1, 384]` | Mean-pooled, L2-normalized sentence embedding |

### Tokenization Conventions
- **Max sequence length:** 128 tokens
- **Special tokens:**
  - `[PAD]` = `0`
  - `[UNK]` = `100`
  - `[CLS]` = `101` (start of sentence)
  - `[SEP]` = `102` (end of sentence)
- **Padding:** Right-padded with `0` up to length 128
- **Pooling:** Mean pooling is computed inside the TFLite graph over attended tokens (`attention_mask == 1`).

---

## 3. Evaluation & Cosine Parity vs Float32

Evaluated across 200 diverse sentences (conversational, urgent protest safety alerts, harassment, benign chat, and edge cases):

- **Embedding Dimension:** 384
- **Mean Cosine Similarity vs FP32 Reference:** `0.9676`
- **Min Cosine Similarity:** `0.9325`
- **Sentences with Cosine >= 0.95:** `95.0%`
- **Desktop CPU Inference Latency:** Avg `17.46 ms`, p95 `20.24 ms`

---

## 4. Reproducibility Route

To regenerate or verify assets from source:

```powershell
uv run python scripts/prepare_model.py
uv run python scripts/build_toxic_vectors.py
uv run python scripts/calibrate_filter.py
```

---

## 5. Quality Bar & Benchmark Verification (T3.12, §8 Quality Bar)

Measured per T3.12 across 300 diverse conversational messages, urgent safety alerts, and adversarial inputs:

### Latency & Performance (§8 Quality Bar)
| Metric | Bar | Measured Result | Verdict |
|---|---|---|---|
| **Cold Model Load Time** | — | `~14.8 ms` | — |
| **Inference Latency (p50)** | — | `17.5 ms` | — |
| **Inference + Scoring Latency (p95)** | `< 35 ms` | **`20.2 ms`** | **PASS** |
| **1,000-Vector Dot-Product Scan (avg)** | `< 2 ms` | **`0.41 ms`** | **PASS** |
| **1,000-Vector Dot-Product Scan (p95)** | `< 2 ms` | **`0.48 ms`** | **PASS** |
| **Total Tracked AI Asset Footprint** | `< 30 MB` | **`22.73 MB`** | **PASS** |

### Sample Diagnostic Outputs (AI-17)
| Phrase | Score ($s_{\max}$) | Top Categories | Classification Verdict |
|---|---|---|---|
| `"Water distribution at north gate at 12:00"` | `0.421` | toxic (0.42), obscene (0.35), threat (0.31) | **Tier 3 (Clean)** |
| `"Police firing tear gas, move back to 5th avenue!"` | `0.518` | threat (0.52), toxic (0.47), obscene (0.38) | **Tier 3 (Clean)** |
| `"I'm going to kill you and break your neck"` | `0.842` | prototype:threat (0.84), threat (0.79), toxic (0.76) | **Tier 1 (Quarantine)** |
| `"You are an absolute idiot and nobody likes you"` | `0.724` | insult (0.72), toxic (0.71), obscene (0.64) | **Tier 2 (Warning)** |

### Quality Bar Invariants Verified
1. **On-Device Only:** Inference and scoring execute strictly post-decryption on `Dispatchers.Default` (AI-1).
2. **Deterministic Tokenization:** 100% parity across all 105 tokenizer test fixtures between Python reference and Kotlin implementation (AI-3).
3. **Asset Budget:** 23,831,700 bytes (22.73 MB) <= 31,457,280 bytes (30 MB limit) enforced by `scripts/check_asset_size.py` (AI-14).
4. **Safety Under Protest Alerts:** 0.0% Tier 1 false quarantine rate on curated 50-alert urgent protest safety dataset (D-9, AI-16).
