# AI Assets Specification & Verification (T3.2, AI-4)

This document records the exact specifications, tensor contracts, quantization details, cryptographic digests, and baseline evaluations for on-device AI assets in P2Chat.

---

## 1. Asset Inventory & Hashes

| Asset | Path | Size | SHA-256 | Purpose |
|---|---|---|---|---|
| **Quantized Model** | `app/src/main/assets/all-MiniLM-L6-v2-quant.tflite` | ~21.74 MB | `5d464cae0e38e59f08ffeaf86b2a72ec459c74b3518425126eaf571ebc6c9367` | 384-d semantic sentence embedder |
| **WordPiece Vocab** | `app/src/main/assets/vocab.txt` | 231,508 bytes | `07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3` | BERT uncased 30,522-token vocabulary |
| **Toxic Centroids** | `app/src/main/assets/toxic_vectors.bin` | ~798 KB | *(generated in T3.4)* | 520 L2-normalized 384-d centroids |
| **Category Meta** | `app/src/main/assets/toxic_vectors_meta.json` | ~15 KB | *(generated in T3.4)* | Centroid category tags & prototypes |

**Budget Check:** Total combined assets: `~22.8 MB / 30.0 MB` (enforced by `scripts/check_asset_size.py`).

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
```

The script downloads the pinned `all-MiniLM-L6-v2` assets from Hugging Face Hub, validates the input/output tensor contracts, verifies cosine parity against FP32 ONNX, and checks asset budget constraints.
