#!/usr/bin/env python3
"""
P2Chat-Core · BLE Scanner / Receiver (Bleak + Bumble)
───────────────────────────────────────────────────────
Scans for and connects to the P2Chat BLE GATT Server (or Virtual BLE Server over TCP),
reads the AES-256-GCM encrypted payload, and decrypts it into plaintext.

Features
────────
  • Dual Transport : Physical Bluetooth (Bleak) OR Virtual BLE (Bumble TCP over Wi-Fi/LAN)
  • Live Watch     : Continuously monitors characteristic for live message updates
  • Decryption     : AES-256-GCM tag verification and decryption

Usage
─────
  # Interactive mode (prompts for mode & IP):
  python receiver.py

  # Connect directly to friend's laptop IP over Wi-Fi / Virtual BLE:
  python receiver.py --ip 192.168.1.5

  # Scan for physical Bluetooth hardware:
  python receiver.py --ble
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import struct
import sys

from Crypto.Cipher import AES

# ── Imports ─────────────────────────────────────────────────────────────────
try:
    from bleak import BleakClient, BleakScanner
except ImportError:
    BleakScanner = None

try:
    from bumble.core import UUID
    from bumble.device import Device, DeviceConfiguration, Peer
    from bumble.transport import open_transport
except ImportError:
    Device = None

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
#  Decryption Helper
# ═══════════════════════════════════════════════════════════════════════════

def decrypt_payload(blob: bytes, key: bytes) -> str:
    """
    Parse the wire format produced by ``sender.encrypt_message`` and return plaintext.

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


def print_message_box(plaintext: str) -> None:
    """Pretty prints decrypted chat message."""
    border = "═" * 60
    print("\n" + border)
    print(f"  📨 [P2CHAT MESSAGE RECEIVED]")
    print(f"  {plaintext}")
    print(border + "\n")


# ═══════════════════════════════════════════════════════════════════════════
#  Virtual BLE Receiver (Bumble TCP over Wi-Fi / LAN)
# ═══════════════════════════════════════════════════════════════════════════

async def run_bumble_receiver(sender_ip: str, port: int, monitor: bool) -> None:
    """Connects to sender over TCP Virtual BLE transport."""
    if Device is None:
        log.error("Bumble library not installed. Run: pip install -r requirements.txt")
        return

    transport_name = f"tcp-client:{sender_ip}:{port}"
    log.info("Connecting to Sender at Virtual BLE endpoint [%s]...", transport_name)

    try:
        async with await open_transport(transport_name) as (hci_source, hci_sink):
            config = DeviceConfiguration(name="P2Chat-Client")
            device = Device.from_config_with_hci(
                config=config,
                hci_source=hci_source,
                hci_sink=hci_sink,
            )

            await device.power_on()
            connection = await device.connect("00:00:00:00:00:00")
            log.info("Connected to P2Chat Virtual BLE Server ✔")

            peer = Peer(connection)
            last_message = None

            while True:
                try:
                    values = await peer.read_characteristics_by_uuid(
                        UUID(P2CHAT_CHARACTERISTIC_UUID)
                    )
                    if values and values[0]:
                        plaintext = decrypt_payload(values[0], SHARED_AES_KEY)
                        if plaintext != last_message:
                            last_message = plaintext
                            print_message_box(plaintext)
                except Exception as e:
                    log.warning("GATT read error: %s", e)

                if not monitor:
                    break
                await asyncio.sleep(2.0)

    except Exception as e:
        log.error("Virtual BLE Connection failed: %s", e)
        log.info("Tip: Ensure sender.py is running on %s and firewall allows port %d.", sender_ip, port)


# ═══════════════════════════════════════════════════════════════════════════
#  Physical Bluetooth Receiver (Bleak)
# ═══════════════════════════════════════════════════════════════════════════

async def run_bleak_receiver(scan_seconds: float, monitor: bool) -> None:
    """Scans and connects via native system Bluetooth hardware."""
    if BleakScanner is None:
        log.error("Bleak library not installed. Run: pip install -r requirements.txt")
        return

    log.info("Scanning for physical P2Chat BLE Service [%s] (Timeout: %.0fs)...", P2CHAT_SERVICE_UUID, scan_seconds)

    target_device = None

    def _detection_callback(device, advertising_data):
        nonlocal target_device
        service_uuids = [u.lower() for u in (advertising_data.service_uuids or [])]
        if P2CHAT_SERVICE_UUID in service_uuids:
            log.info("🎯 Found P2Chat node: %s [%s]", device.name, device.address)
            target_device = device

    scanner = BleakScanner(detection_callback=_detection_callback)
    await scanner.start()

    elapsed = 0.0
    while target_device is None and elapsed < scan_seconds:
        await asyncio.sleep(0.5)
        elapsed += 0.5

    await scanner.stop()

    if target_device is None:
        log.error("No physical P2Chat node found within %.0fs. Is sender.py running with USB dongle?", scan_seconds)
        return

    log.info("Connecting to physical device %s [%s]...", target_device.name, target_device.address)

    async with BleakClient(target_device.address) as client:
        log.info("Connected ✔")
        last_message = None

        while True:
            try:
                encrypted_blob = await client.read_gatt_char(P2CHAT_CHARACTERISTIC_UUID)
                plaintext = decrypt_payload(bytes(encrypted_blob), SHARED_AES_KEY)
                if plaintext != last_message:
                    last_message = plaintext
                    print_message_box(plaintext)
            except Exception as e:
                log.error("Error reading characteristic: %s", e)

            if not monitor:
                break
            await asyncio.sleep(2.0)


# ═══════════════════════════════════════════════════════════════════════════
#  CLI Entry Point & Interactive Menu
# ═══════════════════════════════════════════════════════════════════════════

def main() -> None:
    parser = argparse.ArgumentParser(
        description="P2Chat BLE Receiver — scans, connects, and decrypts incoming messages",
    )
    parser.add_argument(
        "--ip", "-i",
        default=None,
        help="Connect over Virtual BLE TCP to specified Sender IP address (e.g. 192.168.1.5)",
    )
    parser.add_argument(
        "--port", "-p",
        type=int,
        default=17321,
        help="Virtual BLE TCP port (default: 17321)",
    )
    parser.add_argument(
        "--ble", "-b",
        action="store_true",
        help="Force physical Bluetooth hardware scanning mode",
    )
    parser.add_argument(
        "--scan-seconds", "-s",
        type=float,
        default=15.0,
        help="Scan timeout for physical BLE mode (default: 15s)",
    )
    parser.add_argument(
        "--once",
        action="store_true",
        help="Read single message and exit instead of continuous monitoring",
    )
    args = parser.parse_args()

    monitor = not args.once

    # If neither --ip nor --ble specified, offer interactive menu
    if args.ip is None and not args.ble:
        print("=" * 60)
        print("  📡 P2CHAT BLE RECEIVER MODE SELECTION")
        print("=" * 60)
        print("  1. Virtual BLE over Wi-Fi / LAN  (Connect to Sender's IP)")
        print("  2. Physical Bluetooth Scanning  (Scan nearby BLE hardware)")
        print("=" * 60)
        try:
            choice = input("Select option [1/2] (default 1): ").strip()
            if choice == "2":
                args.ble = True
            else:
                ip_input = input("Enter Sender Laptop's IP address (default 127.0.0.1): ").strip()
                args.ip = ip_input if ip_input else "127.0.0.1"
        except (KeyboardInterrupt, EOFError):
            sys.exit(0)

    try:
        if args.ble:
            asyncio.run(run_bleak_receiver(args.scan_seconds, monitor))
        else:
            asyncio.run(run_bumble_receiver(args.ip, args.port, monitor))
    except KeyboardInterrupt:
        print("\nReceiver stopped.")


if __name__ == "__main__":
    main()
