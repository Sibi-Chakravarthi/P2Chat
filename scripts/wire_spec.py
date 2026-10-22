"""
P2Chat Wire Protocol Constants.

Source of truth: docs/WIRE_SPEC.md and docs/PRD.md.
Must maintain 100% parity with app/src/main/java/com/p2chat/wire/BleConstants.kt,
verified by scripts/check_wire_parity.py.
"""

# ── BLE GATT Service & Characteristics ──────────────────────────────────────
SERVICE_UUID: str = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
CHARACTERISTIC_UUID: str = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
BLE_MANUFACTURER_ID: int = 0xFFFF

# ── Packet Types (1 byte) ───────────────────────────────────────────────────
PACKET_TYPE_CHAT: int = 0x01
PACKET_TYPE_KEY_ROTATE: int = 0x02
PACKET_TYPE_ROSTER_SYNC: int = 0x03
PACKET_TYPE_PAIRWISE_INVITE: int = 0x04
PACKET_TYPE_BENCH_DATA: int = 0x05
PACKET_TYPE_BENCH_ACK: int = 0x06
PACKET_TYPE_GROUP_REMOVE_MEMBER: int = 0x07

# ── Transport Framing Header (4 bytes, D-17) ────────────────────────────────
TRANSPORT_HEADER_SIZE_BYTES: int = 4
TRANSPORT_KIND_SINGLE: int = 0x00
TRANSPORT_KIND_CHUNK: int = 0x01

# ── Fixed Wire Header (24 bytes) ────────────────────────────────────────────
# Layout: packet_type (1B) + ttl (1B) + msg_id (8B) + group_id (4B) + sender_fp (8B) + key_epoch (2B)
HEADER_SIZE_BYTES: int = 24
OFFSET_PACKET_TYPE: int = 0
OFFSET_TTL: int = 1
OFFSET_MSG_ID: int = 2
OFFSET_GROUP_ID: int = 10
OFFSET_SENDER_FP: int = 14
OFFSET_KEY_EPOCH: int = 22

# ── Cryptographic Constants ─────────────────────────────────────────────────
GCM_NONCE_SIZE_BYTES: int = 12
GCM_TAG_SIZE_BYTES: int = 16
CIPHERTEXT_LEN_SIZE_BYTES: int = 4
CORE_BLOB_PREFIX_SIZE_BYTES: int = 32  # nonce(12) + tag(16) + len(4)
ECDSA_SIGNATURE_SIZE_BYTES: int = 64   # raw (r || s), 32 bytes each

# AAD is exactly: packet_type (1B) + msg_id (8B) + group_id (4B) + sender_fp (8B) + key_epoch (2B)
AAD_SIZE_BYTES: int = 23

# HKDF-SHA256 derivation constants for pairwise key exchange
HKDF_PAIRWISE_SALT: str = "P2Chat-v1-Pairwise-Salt"
HKDF_PAIRWISE_INFO: str = "P2Chat-v1-Pairwise-AES256GCM"

# ── Protocol & Mesh Limits ──────────────────────────────────────────────────
INITIAL_KEY_EPOCH: int = 1
DEFAULT_TTL: int = 7
MAX_TTL: int = 15
MAX_PLAINTEXT_BYTES: int = 350
INNER_TIMESTAMP_SIZE_BYTES: int = 8
MAX_INNER_PAYLOAD_BYTES: int = 358  # sent_at(8) + plaintext(350)
CHAT_FRAME_SIZE_BYTES: int = 478    # 24 + 12 + 16 + 4 + 358 + 64
MAX_FRAME_SIZE_BYTES: int = 1024
MAX_ROSTER_MEMBERS: int = 16

# Bloom Filter Digest (D-3, NET-5)
BLOOM_FILTER_SIZE_BYTES: int = 1200
BLOOM_FILTER_K_HASHES: int = 7

# Reserved Benchmark Group ID (BENCH-1, ASCII 'BENC')
BENCH_GROUP_ID: int = 0x42454E43
