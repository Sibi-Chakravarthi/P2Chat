#!/usr/bin/env python3
"""
P2Chat-Core · BLE Scanner / Receiver (Bleak)
─────────────────────────────────────────────
Scans for the P2Chat BLE Service UUID, connects, reads the encrypted
characteristic payload, and decrypts it via AES-256-GCM.

Stack
─────
  • Bleak         – cross-platform BLE client
  • PyCryptodome  – AES-256-GCM decryption

Usage
─────
  # Terminal 2 (after starting sender.py in Terminal 1):
  python receiver.py

  # With a real BLE adapter:
  python receiver.py --scan-seconds 10
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import struct
import sys

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
#  BLE scan → connect → read → decrypt
# ═══════════════════════════════════════════════════════════════════════════

async def run_receiver(scan_seconds: float) -> None:
    """Full receive pipeline."""

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

    log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    log.info("  📨  Decrypted Message : %s", plaintext)
    log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")


# ═══════════════════════════════════════════════════════════════════════════
#  CLI entry-point
# ═══════════════════════════════════════════════════════════════════════════

def main() -> None:
    parser = argparse.ArgumentParser(
        description="P2Chat BLE Receiver — scans, connects, and decrypts incoming messages",
    )
    parser.add_argument(
        "--scan-seconds", "-s",
        type=float,
        default=15.0,
        help="How long to scan for P2Chat advertisers (default: 15 s)",
    )
    args = parser.parse_args()
    asyncio.run(run_receiver(args.scan_seconds))


if __name__ == "__main__":
    main()
