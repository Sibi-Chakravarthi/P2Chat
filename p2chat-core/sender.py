#!/usr/bin/env python3
"""
P2Chat-Core · BLE GATT Sender (Bumble)
───────────────────────────────────────
Advertises a P2Chat GATT Service over BLE (or Virtual BLE over TCP)
and exposes an encrypted chat-message characteristic that any P2Chat
receiver can read.

Features
────────
  • Dual Transport : Native USB BLE dongle OR Virtual TCP (works on Wi-Fi across 2 laptops)
  • Interactive    : Type new messages in real-time to broadcast encrypted updates
  • Encryption     : AES-256-GCM payload with nonce & authentication tag

Usage
─────
  # Default: Virtual TCP Server (listens for receiver on Wi-Fi/LAN)
  python sender.py

  # Real USB BLE Dongle mode:
  python sender.py --transport usb:0
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import socket
import struct
import sys

from Crypto.Cipher import AES
from Crypto.Random import get_random_bytes

# ── Bumble imports ──────────────────────────────────────────────────────────
try:
    from bumble.core import AdvertisingData
    from bumble.device import Device, DeviceConfiguration
    from bumble.gatt import (
        GATT_CHARACTERISTIC_READ,
        GATT_CHARACTERISTIC_NOTIFY,
        Characteristic,
        Service,
    )
    from bumble.transport import open_transport
except ImportError:
    sys.exit("Bumble library is missing. Please run: pip install -r requirements.txt")

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

# ── Shared AES-256 key (must match receiver.py) ────────────────────────────
SHARED_AES_KEY = bytes.fromhex(
    "0123456789abcdef0123456789abcdef"
    "0123456789abcdef0123456789abcdef"
)
assert len(SHARED_AES_KEY) == 32, "Key must be exactly 256 bits"

DEFAULT_MESSAGE = "Hey from P2Chat! This is a private, encrypted broadcast 🔒"


# ═══════════════════════════════════════════════════════════════════════════
#  Network & Encryption Helpers
# ═══════════════════════════════════════════════════════════════════════════

def get_local_ip() -> str:
    """Find local network IP address to display to the user for receiver connection."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def encrypt_message(plaintext: str, key: bytes) -> bytes:
    """
    Encrypt *plaintext* using AES-256-GCM.

    Wire format (little-endian):
    ┌──────────┬──────────────────┬────────────────┬──────────────┐
    │ nonce    │ tag              │ ciphertext_len │ ciphertext   │
    │ 12 bytes │ 16 bytes         │ 4 bytes (u32)  │ variable     │
    └──────────┴──────────────────┴────────────────┴──────────────┘
    """
    nonce = get_random_bytes(12)
    cipher = AES.new(key, AES.MODE_GCM, nonce=nonce)
    ciphertext, tag = cipher.encrypt_and_digest(plaintext.encode("utf-8"))
    return nonce + tag + struct.pack("<I", len(ciphertext)) + ciphertext


# ═══════════════════════════════════════════════════════════════════════════
#  GATT Server & Interactive Console Loop
# ═══════════════════════════════════════════════════════════════════════════

async def interactive_chat_input(device: Device, characteristic: Characteristic) -> None:
    """Asynchronously reads lines from stdin and updates the encrypted BLE characteristic."""
    loop = asyncio.get_running_loop()
    print("\n💬 [INTERACTIVE MODE READY] Type a message and press Enter to broadcast updates:\n")
    
    while True:
        try:
            user_input = await loop.run_in_executor(None, input, "Sender > ")
            user_input = user_input.strip()
            if not user_input:
                continue
            if user_input.lower() in ("exit", "quit"):
                print("Exiting sender...")
                os._exit(0)

            encrypted_blob = encrypt_message(user_input, SHARED_AES_KEY)
            characteristic.value = encrypted_blob
            log.info("🔒 Message updated (%d bytes ciphertext): '%s'", len(user_input), user_input)
            
            try:
                await device.notify_subscribers(characteristic, encrypted_blob)
            except Exception:
                pass
        except (EOFError, KeyboardInterrupt):
            break


async def run_sender(transport_name: str, initial_message: str) -> None:
    """Starts the P2Chat GATT Server over specified transport."""

    is_tcp = transport_name.startswith("tcp")
    local_ip = get_local_ip() if is_tcp else "N/A"

    initial_blob = encrypt_message(initial_message, SHARED_AES_KEY)

    print("=" * 64)
    print("  🚀 P2CHAT BLE SENDER / BROADCASTER")
    print("=" * 64)
    if is_tcp:
        print(f"  📡 Mode         : Virtual BLE (Wi-Fi / LAN Network)")
        print(f"  🌐 Local IP     : {local_ip}")
        print(f"  🔌 Port         : 17321")
        print(f"  👉 Give your friend this command on their laptop:")
        print(f"     python receiver.py --ip {local_ip}")
    else:
        print(f"  📡 Mode         : Hardware BLE ({transport_name})")
    print(f"  🔑 Service UUID : {P2CHAT_SERVICE_UUID}")
    print(f"  📩 Char UUID    : {P2CHAT_CHARACTERISTIC_UUID}")
    print("=" * 64)
    log.info("Initial plaintext : %s", initial_message)

    log.info("Opening transport [%s]...", transport_name)

    try:
        async with await open_transport(transport_name) as (hci_source, hci_sink):
            config = DeviceConfiguration(name="P2Chat-Node")
            device = Device.from_config_with_hci(
                config=config,
                hci_source=hci_source,
                hci_sink=hci_sink,
            )

            # Build GATT Service & Characteristic
            message_char = Characteristic(
                uuid=P2CHAT_CHARACTERISTIC_UUID,
                properties=GATT_CHARACTERISTIC_READ | GATT_CHARACTERISTIC_NOTIFY,
                permissions=Characteristic.Permissions.READABLE,
                value=initial_blob,
            )

            p2chat_service = Service(
                uuid=P2CHAT_SERVICE_UUID,
                characteristics=[message_char],
            )
            device.add_service(p2chat_service)

            await device.power_on()
            await device.start_advertising(
                advertising_data=AdvertisingData([
                    (AdvertisingData.COMPLETE_LOCAL_NAME, b"P2Chat-Node"),
                    (
                        AdvertisingData.INCOMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS,
                        bytes.fromhex(P2CHAT_SERVICE_UUID.replace("-", ""))[::-1],
                    ),
                ]),
            )

            log.info("Server is live & advertising! Waiting for connections...")
            
            # Start interactive CLI input task in background
            asyncio.create_task(interactive_chat_input(device, message_char))

            await asyncio.Event().wait()
    except Exception as e:
        log.error("Transport error: %s", e)
        if is_tcp:
            log.info("Tip: Ensure port 17321 is not blocked by firewall.")


# ═══════════════════════════════════════════════════════════════════════════
#  CLI Entry Point
# ═══════════════════════════════════════════════════════════════════════════

def main() -> None:
    parser = argparse.ArgumentParser(
        description="P2Chat BLE Sender — encrypts & broadcasts messages over BLE or Wi-Fi",
    )
    parser.add_argument(
        "--transport", "-t",
        default="tcp-server:0.0.0.0:17321",
        help="Transport specification. Default listens on tcp-server:0.0.0.0:17321 (Virtual BLE). Or use 'usb:0' for physical BLE dongle.",
    )
    parser.add_argument(
        "--message", "-m",
        default=DEFAULT_MESSAGE,
        help="Initial plaintext message to encrypt and broadcast",
    )
    args = parser.parse_args()

    try:
        asyncio.run(run_sender(args.transport, args.message))
    except KeyboardInterrupt:
        print("\nSender stopped.")


if __name__ == "__main__":
    main()
