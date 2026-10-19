package com.p2chat.wire

/**
 * P2Chat Wire Protocol Constants.
 *
 * Source of truth: docs/WIRE_SPEC.md and docs/PRD.md.
 * Must maintain 100% parity with scripts/wire_spec.py, verified by scripts/check_wire_parity.py.
 */
object BleConstants {
    // ── BLE GATT Service & Characteristics ──────────────────────────────────
    const val SERVICE_UUID: String = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    const val CHARACTERISTIC_UUID: String = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"

    // ── Packet Types (1 byte) ───────────────────────────────────────────────
    const val PACKET_TYPE_CHAT: Byte = 0x01
    const val PACKET_TYPE_KEY_ROTATE: Byte = 0x02
    const val PACKET_TYPE_ROSTER_SYNC: Byte = 0x03
    const val PACKET_TYPE_PAIRWISE_INVITE: Byte = 0x04
    const val PACKET_TYPE_BENCH_DATA: Byte = 0x05
    const val PACKET_TYPE_BENCH_ACK: Byte = 0x06

    // ── Transport Framing Header (4 bytes, D-17) ────────────────────────────
    const val TRANSPORT_HEADER_SIZE_BYTES: Int = 4
    const val TRANSPORT_KIND_SINGLE: Byte = 0x00
    const val TRANSPORT_KIND_CHUNK: Byte = 0x01

    // ── Fixed Wire Header (24 bytes) ────────────────────────────────────────
    // Layout: packet_type (1B) + ttl (1B) + msg_id (8B) + group_id (4B) + sender_fp (8B) + key_epoch (2B)
    const val HEADER_SIZE_BYTES: Int = 24
    const val OFFSET_PACKET_TYPE: Int = 0
    const val OFFSET_TTL: Int = 1
    const val OFFSET_MSG_ID: Int = 2
    const val OFFSET_GROUP_ID: Int = 10
    const val OFFSET_SENDER_FP: Int = 14
    const val OFFSET_KEY_EPOCH: Int = 22

    // ── Cryptographic Constants ─────────────────────────────────────────────
    const val GCM_NONCE_SIZE_BYTES: Int = 12
    const val GCM_TAG_SIZE_BYTES: Int = 16
    const val CIPHERTEXT_LEN_SIZE_BYTES: Int = 4
    const val CORE_BLOB_PREFIX_SIZE_BYTES: Int = 32 // nonce(12) + tag(16) + len(4)
    const val ECDSA_SIGNATURE_SIZE_BYTES: Int = 64  // raw (r || s), 32 bytes each

    // AAD is exactly: packet_type (1B) + msg_id (8B) + group_id (4B) + sender_fp (8B) + key_epoch (2B)
    const val AAD_SIZE_BYTES: Int = 23

    // HKDF-SHA256 derivation constants for pairwise key exchange
    const val HKDF_PAIRWISE_SALT: String = "P2Chat-v1-Pairwise-Salt"
    const val HKDF_PAIRWISE_INFO: String = "P2Chat-v1-Pairwise-AES256GCM"

    // ── Protocol & Mesh Limits ──────────────────────────────────────────────
    const val INITIAL_KEY_EPOCH: Int = 1
    const val DEFAULT_TTL: Int = 7
    const val MAX_TTL: Int = 15
    const val MAX_PLAINTEXT_BYTES: Int = 350
    const val INNER_TIMESTAMP_SIZE_BYTES: Int = 8
    const val MAX_INNER_PAYLOAD_BYTES: Int = 358 // sent_at(8) + plaintext(350)
    const val CHAT_FRAME_SIZE_BYTES: Int = 478   // 24 + 12 + 16 + 4 + 358 + 64
    const val MAX_FRAME_SIZE_BYTES: Int = 1024
    const val MAX_ROSTER_MEMBERS: Int = 16

    // Bloom Filter Digest (D-3, NET-5)
    const val BLOOM_FILTER_SIZE_BYTES: Int = 1200
    const val BLOOM_FILTER_K_HASHES: Int = 7

    // Reserved Benchmark Group ID (BENCH-1, ASCII 'BENC')
    const val BENCH_GROUP_ID: Long = 0x42454E43L
}
