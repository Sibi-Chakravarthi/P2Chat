#!/usr/bin/env python3
"""
P2Chat-Core · BLE Scanner / Receiver (Bleak) + AI Toxicity Filter
─────────────────────────────────────────────────────────────────────
Scans for the P2Chat BLE Service UUID, connects, reads the encrypted
characteristic, decrypts via AES-256-GCM, then runs the plaintext
through a TensorFlow Lite toxicity classifier.

Stack
─────
  • Bleak           – cross-platform BLE client
  • PyCryptodome    – AES-256-GCM decryption
  • tflite-runtime  – on-device toxicity scoring  (graceful fallback
                      to a keyword heuristic if no .tflite model is
                      present — mirrors the Android MobileBertClassifier)
  • NumPy           – tensor I/O

Usage
─────
  # Terminal 2 (after starting sender.py in Terminal 1):
  python receiver.py

  # With a real BLE adapter (no extra flags needed — Bleak auto-detects):
  python receiver.py --scan-seconds 10
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import struct
import sys
from pathlib import Path

import numpy as np
from Crypto.Cipher import AES

try:
    from bleak import BleakClient, BleakScanner
except ImportError:
    sys.exit("bleak is required.  pip install bleak")

# ── Logging ─────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  [RECEIVER]  %(levelname)-8s  %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("p2chat.receiver")

# ── P2Chat BLE Constants (mirrors BleConstants.kt & sender.py) ─────────────
P2CHAT_SERVICE_UUID        = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
P2CHAT_CHARACTERISTIC_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"

# ── Shared AES-256 key (must match sender.py) ──────────────────────────────
SHARED_AES_KEY = bytes.fromhex(
    "0123456789abcdef0123456789abcdef"
    "0123456789abcdef0123456789abcdef"
)


# ═══════════════════════════════════════════════════════════════════════════
#  Decryption
# ═══════════════════════════════════════════════════════════════════════════

def decrypt_payload(blob: bytes, key: bytes) -> str:
    """
    Parse the wire format produced by ``sender.encrypt_message`` and
    return the original plaintext.

    Wire format:
        nonce (12) | tag (16) | ciphertext_len (4, LE u32) | ciphertext (N)
    """
    if len(blob) < 32:
        raise ValueError(f"Payload too short ({len(blob)} bytes) — expected ≥32")

    nonce      = blob[0:12]
    tag        = blob[12:28]
    ct_len     = struct.unpack("<I", blob[28:32])[0]
    ciphertext = blob[32:32 + ct_len]

    if len(ciphertext) != ct_len:
        raise ValueError(
            f"Ciphertext length mismatch: header says {ct_len}, "
            f"but only {len(ciphertext)} bytes available"
        )

    cipher = AES.new(key, AES.MODE_GCM, nonce=nonce)
    plaintext_bytes = cipher.decrypt_and_verify(ciphertext, tag)
    return plaintext_bytes.decode("utf-8")


# ═══════════════════════════════════════════════════════════════════════════
#  AI Toxicity Filter  (TFLite or keyword fallback)
# ═══════════════════════════════════════════════════════════════════════════

class ToxicityFilter:
    """
    Mirrors the behaviour of the Android ``MobileBertClassifier``:
      • If a compatible .tflite model is found → run real inference.
      • Otherwise → fall back to a deterministic keyword heuristic
        (same word-list used in the Android scaffold).
    """

    MODEL_FILENAME  = "mobilebert_quantized.tflite"
    MAX_SEQ_LEN     = 128
    CLS_TOKEN_ID    = 101
    SEP_TOKEN_ID    = 102
    TOXICITY_THRESH = 0.50

    FLAGGED_KEYWORDS = frozenset({
        "spam", "phishing", "malware", "hate",
        "attack", "badword", "scam", "abuse",
    })

    def __init__(self, model_dir: str | None = None) -> None:
        self.interpreter = None
        self._try_load_model(model_dir or str(Path(__file__).parent))

    # ── Model loading ───────────────────────────────────────────────────
    def _try_load_model(self, search_dir: str) -> None:
        model_path = os.path.join(search_dir, self.MODEL_FILENAME)
        if not os.path.isfile(model_path):
            log.warning(
                "TFLite model '%s' not found in %s — using keyword fallback.",
                self.MODEL_FILENAME, search_dir,
            )
            return

        try:
            import tflite_runtime.interpreter as tflite
            self.interpreter = tflite.Interpreter(model_path=model_path)
            self.interpreter.allocate_tensors()
            log.info("TFLite model loaded successfully from %s", model_path)
        except Exception as exc:
            log.warning("Failed to initialise TFLite interpreter: %s", exc)
            self.interpreter = None

    # ── Stub WordPiece tokenizer (same logic as MobileBertClassifier.kt) ─
    def _tokenize(self, text: str) -> np.ndarray:
        tokens = np.zeros(self.MAX_SEQ_LEN, dtype=np.int32)
        tokens[0] = self.CLS_TOKEN_ID
        words = text.lower().split()
        idx = 1
        for w in words:
            if idx >= self.MAX_SEQ_LEN - 1:
                break
            h = abs(hash(w)) % 28_000 + 1_000
            tokens[idx] = h
            idx += 1
        if idx < self.MAX_SEQ_LEN:
            tokens[idx] = self.SEP_TOKEN_ID
        return tokens

    # ── Inference ───────────────────────────────────────────────────────
    def score(self, text: str) -> dict:
        """
        Return a dict compatible with Android's ``FilterResult``:
            { is_safe, toxicity_score, label, confidence }
        """
        if not text.strip():
            return {
                "is_safe": True,
                "toxicity_score": 0.0,
                "label": "Clean",
                "confidence": 1.0,
            }

        # ── Real TFLite path ────────────────────────────────────────────
        if self.interpreter is not None:
            try:
                token_ids = self._tokenize(text)
                input_ids   = token_ids[np.newaxis, :]
                input_mask  = (token_ids != 0).astype(np.int32)[np.newaxis, :]
                segment_ids = np.zeros_like(input_ids, dtype=np.int32)

                inp = self.interpreter.get_input_details()
                out = self.interpreter.get_output_details()

                self.interpreter.set_tensor(inp[0]["index"], input_ids)
                if len(inp) > 1:
                    self.interpreter.set_tensor(inp[1]["index"], input_mask)
                if len(inp) > 2:
                    self.interpreter.set_tensor(inp[2]["index"], segment_ids)

                self.interpreter.invoke()

                scores = self.interpreter.get_tensor(out[0]["index"])[0]
                clean_score = float(scores[0])
                toxic_score = float(scores[1])
                is_safe = toxic_score < self.TOXICITY_THRESH

                return {
                    "is_safe": is_safe,
                    "toxicity_score": round(toxic_score, 4),
                    "label": "Clean" if is_safe else "Flagged",
                    "confidence": round(max(clean_score, toxic_score), 4),
                }
            except Exception as exc:
                log.warning("TFLite inference failed, falling back: %s", exc)

        # ── Keyword fallback (identical to Android scaffold) ────────────
        words = set(text.lower().split())
        flagged = bool(words & self.FLAGGED_KEYWORDS)
        sim_score = 0.88 if flagged else 0.04

        return {
            "is_safe": not flagged,
            "toxicity_score": sim_score,
            "label": "Flagged (Scaffold)" if flagged else "Clean (Scaffold)",
            "confidence": 0.95,
        }


# ═══════════════════════════════════════════════════════════════════════════
#  BLE scan → connect → read → decrypt → filter
# ═══════════════════════════════════════════════════════════════════════════

async def run_receiver(scan_seconds: float, model_dir: str | None) -> None:
    """Full receive pipeline."""

    toxicity = ToxicityFilter(model_dir)

    # ── Scan for the P2Chat Service UUID ────────────────────────────────
    log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    log.info("  Scanning for P2Chat BLE Service …")
    log.info("  Target UUID : %s", P2CHAT_SERVICE_UUID)
    log.info("  Timeout     : %.0f s", scan_seconds)
    log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

    target_device = None

    def _detection_callback(device, advertising_data):
        nonlocal target_device
        service_uuids = [u.lower() for u in (advertising_data.service_uuids or [])]
        if P2CHAT_SERVICE_UUID in service_uuids:
            log.info("🎯 Found P2Chat node: %s  [%s]", device.name, device.address)
            target_device = device

    scanner = BleakScanner(detection_callback=_detection_callback)
    await scanner.start()

    elapsed = 0.0
    while target_device is None and elapsed < scan_seconds:
        await asyncio.sleep(0.5)
        elapsed += 0.5

    await scanner.stop()

    if target_device is None:
        log.error("No P2Chat node found within %.0f s. Is sender.py running?", scan_seconds)
        return

    # ── Connect & read the encrypted characteristic ─────────────────────
    log.info("Connecting to %s [%s] …", target_device.name, target_device.address)

    async with BleakClient(target_device.address) as client:
        log.info("Connected ✔  (MTU negotiated)")

        encrypted_blob = await client.read_gatt_char(P2CHAT_CHARACTERISTIC_UUID)
        log.info("Read encrypted payload: %d bytes", len(encrypted_blob))

    # ── Decrypt ─────────────────────────────────────────────────────────
    try:
        plaintext = decrypt_payload(bytes(encrypted_blob), SHARED_AES_KEY)
    except Exception as exc:
        log.error("Decryption FAILED: %s", exc)
        return

    log.info("Decrypted message: %s", plaintext)

    # ── AI Toxicity Filter ──────────────────────────────────────────────
    result = toxicity.score(plaintext)

    log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    log.info("  📨  Decrypted Message : %s", plaintext)
    log.info("  🛡️  AI Filter Label   : %s", result["label"])
    log.info("  📊  Toxicity Score    : %.4f", result["toxicity_score"])
    log.info("  ✅  Safe              : %s", result["is_safe"])
    log.info("  🎯  Confidence        : %.4f", result["confidence"])
    log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")


# ═══════════════════════════════════════════════════════════════════════════
#  CLI entry-point
# ═══════════════════════════════════════════════════════════════════════════

def main() -> None:
    parser = argparse.ArgumentParser(
        description="P2Chat BLE Receiver — scans, decrypts, and AI-filters incoming messages",
    )
    parser.add_argument(
        "--scan-seconds", "-s",
        type=float,
        default=15.0,
        help="How long to scan for P2Chat advertisers (default: 15 s)",
    )
    parser.add_argument(
        "--model-dir", "-d",
        default=None,
        help="Directory containing mobilebert_quantized.tflite (default: script dir)",
    )
    args = parser.parse_args()
    asyncio.run(run_receiver(args.scan_seconds, args.model_dir))


if __name__ == "__main__":
    main()
