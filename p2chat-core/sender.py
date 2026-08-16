#!/usr/bin/env python3
"""
P2Chat-Core · BLE GATT Sender (Bumble)
───────────────────────────────────────
Advertises a P2Chat GATT Service over BLE and exposes an encrypted
chat-message characteristic that any P2Chat receiver can read.

Stack
─────
  • Bumble          – virtual / USB BLE transport + GATT server
  • PyCryptodome    – AES-256-GCM message encryption

Usage
─────
  # Using Bumble's built-in virtual "localhost" transport (two terminals):
  python sender.py

  # Or point to a real USB BLE dongle:
  python sender.py --transport usb:0
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import struct

from Crypto.Cipher import AES
from Crypto.Random import get_random_bytes

# ── Bumble imports ──────────────────────────────────────────────────────────
from bumble.core import AdvertisingData
from bumble.device import Device
from bumble.gatt import (
    GATT_CHARACTERISTIC_READ,
    Characteristic,
    Service,
)
from bumble.transport import open_transport

# ── Logging ─────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  [SENDER]  %(levelname)-8s  %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("p2chat.sender")

# ── P2Chat BLE Constants (mirrors BleConstants.kt) ─────────────────────────
P2CHAT_SERVICE_UUID        = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
P2CHAT_CHARACTERISTIC_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"

# ── Cryptographic defaults ──────────────────────────────────────────────────
# In production these would be derived via ECDH key-exchange.
# For the PoC both sides share the same 256-bit key.
SHARED_AES_KEY = bytes.fromhex(
    "0123456789abcdef0123456789abcdef"
    "0123456789abcdef0123456789abcdef"
)
assert len(SHARED_AES_KEY) == 32, "Key must be exactly 256 bits"

DEFAULT_MESSAGE = "Hey from P2Chat! This is a private, encrypted broadcast 🔒"


# ═══════════════════════════════════════════════════════════════════════════
#  Encryption helpers
# ═══════════════════════════════════════════════════════════════════════════

def encrypt_message(plaintext: str, key: bytes) -> bytes:
    """
    Encrypt *plaintext* using AES-256-GCM and return a self-contained
    binary blob the receiver can parse independently.

    Wire format (all little-endian):
    ┌──────────┬──────────────────┬────────────────┬──────────────┐
    │ nonce    │ tag              │ ciphertext_len │ ciphertext   │
    │ 12 bytes │ 16 bytes         │ 4 bytes (u32)  │ variable     │
    └──────────┴──────────────────┴────────────────┴──────────────┘
    """
    nonce = get_random_bytes(12)
    cipher = AES.new(key, AES.MODE_GCM, nonce=nonce)
    ciphertext, tag = cipher.encrypt_and_digest(plaintext.encode("utf-8"))

    payload = nonce + tag + struct.pack("<I", len(ciphertext)) + ciphertext
    return payload


# ═══════════════════════════════════════════════════════════════════════════
#  GATT server bootstrap
# ═══════════════════════════════════════════════════════════════════════════

async def run_sender(transport_name: str, plaintext: str) -> None:
    """
    1. Encrypt the plaintext message once.
    2. Stand up a GATT server with the P2Chat Service + Message Characteristic.
    3. Start advertising and wait for readers forever.
    """

    # ── Encrypt ─────────────────────────────────────────────────────────
    encrypted_blob = encrypt_message(plaintext, SHARED_AES_KEY)
    log.info("Plaintext (%d chars):  %s", len(plaintext), plaintext)
    log.info("Encrypted payload:     %d bytes  (nonce 12 + tag 16 + len 4 + ct %d)",
             len(encrypted_blob), len(encrypted_blob) - 32)

    # ── Transport ───────────────────────────────────────────────────────
    log.info("Opening transport: %s", transport_name)
    async with await open_transport(transport_name) as (hci_source, hci_sink):

        # ── Device ──────────────────────────────────────────────────────
        device = Device.from_config_with_hci(
            name="P2Chat-Node",
            hci_source=hci_source,
            hci_sink=hci_sink,
        )

        # ── Build GATT tree ─────────────────────────────────────────────
        message_char = Characteristic(
            uuid=P2CHAT_CHARACTERISTIC_UUID,
            properties=GATT_CHARACTERISTIC_READ,
            permissions=Characteristic.Permissions(
                readable=True,
            ),
            value=encrypted_blob,
        )

        p2chat_service = Service(
            uuid=P2CHAT_SERVICE_UUID,
            characteristics=[message_char],
        )

        device.add_service(p2chat_service)

        # ── Power on & advertise ────────────────────────────────────────
        await device.power_on()

        await device.start_advertising(
            advertising_data=AdvertisingData([
                (AdvertisingData.COMPLETE_LOCAL_NAME, b"P2Chat-Node"),
                (
                    AdvertisingData.INCOMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS,
                    bytes.fromhex(
                        P2CHAT_SERVICE_UUID.replace("-", "")
                    )[::-1],   # BLE UUID byte-order is little-endian
                ),
            ]),
        )

        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        log.info("  P2Chat BLE GATT Server is LIVE")
        log.info("  Service UUID : %s", P2CHAT_SERVICE_UUID)
        log.info("  Char UUID    : %s", P2CHAT_CHARACTERISTIC_UUID)
        log.info("  Payload size : %d bytes", len(encrypted_blob))
        log.info("  Transport    : %s", transport_name)
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        log.info("Waiting for P2Chat receivers to connect and read …")

        # Keep the server alive indefinitely
        await asyncio.Event().wait()


# ═══════════════════════════════════════════════════════════════════════════
#  CLI entry-point
# ═══════════════════════════════════════════════════════════════════════════

def main() -> None:
    parser = argparse.ArgumentParser(
        description="P2Chat BLE Sender — encrypts & broadcasts a message over BLE",
    )
    parser.add_argument(
        "--transport", "-t",
        default="tcp-server:_:17321",
        help=(
            "Bumble transport string. Examples:\n"
            "  tcp-server:_:17321   (virtual, default — pair with receiver's tcp-client)\n"
            "  usb:0                (first USB BLE dongle)\n"
            "  serial:/dev/ttyUSB0  (serial HCI adapter)"
        ),
    )
    parser.add_argument(
        "--message", "-m",
        default=DEFAULT_MESSAGE,
        help="Plaintext message to encrypt and serve (default: demo greeting)",
    )
    args = parser.parse_args()
    asyncio.run(run_sender(args.transport, args.message))


if __name__ == "__main__":
    main()
