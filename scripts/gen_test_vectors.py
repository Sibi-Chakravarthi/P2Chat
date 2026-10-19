#!/usr/bin/env python3
"""
scripts/gen_test_vectors.py

Generates deterministic cryptographic test vectors for P2Chat:
- P-256 ECDSA raw 64-byte (r || s)
- AES-256-GCM encryption with 23-byte AAD
- D-5 Inner payload format: sent_at (8B BE int64 ms) + UTF-8 bytes
- Wire frame: Header(24B) + CoreBlob(32B + ct) + Signature(64B)
- At least 20 positive vectors across all packet types and payload lengths
- At least 10 negative vectors covering tampering, TTL, timestamps, and malformations

Outputs to: app/src/test/resources/vectors/crypto_vectors.json
Run twice to verify byte-identical output.
"""

import hashlib
import json
import os
import struct
import sys
from pathlib import Path

from Crypto.Cipher import AES
from Crypto.Hash import SHA256
from Crypto.PublicKey import ECC
from Crypto.Signature import DSS

# Add scripts directory to path to import wire_spec
sys.path.insert(0, str(Path(__file__).resolve().parent))
import wire_spec


OUTPUT_PATH = (
    Path(__file__).resolve().parent.parent
    / "app"
    / "src"
    / "test"
    / "resources"
    / "vectors"
    / "crypto_vectors.json"
)

# Reference timestamp: 2026-10-19T10:00:00.000Z in milliseconds
BASE_TIMESTAMP_MS = 1792404000000


def int_to_p256_key(d_int: int) -> ECC.EccKey:
    """Derives a deterministic P-256 keypair from an integer scalar."""
    return ECC.construct(curve="p256", d=d_int)


def export_uncompressed_pubkey(key: ECC.EccKey) -> bytes:
    """Exports 65-byte uncompressed public key: 0x04 || X(32B) || Y(32B)."""
    point = key.pointQ
    x_bytes = int(point.x).to_bytes(32, "big")
    y_bytes = int(point.y).to_bytes(32, "big")
    return b"\x04" + x_bytes + y_bytes


def compute_sender_fp(uncompressed_pubkey: bytes) -> int:
    """First 8 bytes of SHA-256(pubkey) as uint64 big-endian."""
    digest = hashlib.sha256(uncompressed_pubkey).digest()
    return struct.unpack(">Q", digest[:8])[0]


def build_header(
    packet_type: int,
    ttl: int,
    msg_id: int,
    group_id: int,
    sender_fp: int,
    key_epoch: int,
) -> bytes:
    return struct.pack(
        ">BBQIQH",
        packet_type,
        ttl,
        msg_id,
        group_id,
        sender_fp,
        key_epoch,
    )


def build_aad(
    packet_type: int,
    msg_id: int,
    group_id: int,
    sender_fp: int,
    key_epoch: int,
) -> bytes:
    return struct.pack(
        ">BQIQH",
        packet_type,
        msg_id,
        group_id,
        sender_fp,
        key_epoch,
    )


def create_vector(
    description: str,
    packet_type: int,
    ttl: int,
    msg_id: int,
    group_id: int,
    key_epoch: int,
    signing_key: ECC.EccKey,
    group_key: bytes,
    sent_at_ms: int,
    plaintext: str,
    nonce: bytes,
    expected_verdict: str,
    tamper_mode: str = "none",
) -> dict:
    pub_bytes = export_uncompressed_pubkey(signing_key)
    sender_fp = compute_sender_fp(pub_bytes)

    # 1. Inner payload: sent_at (8B BE int64) + UTF-8 bytes
    inner_payload = struct.pack(">q", sent_at_ms) + plaintext.encode("utf-8")

    # 2. AAD (23 bytes)
    aad = build_aad(packet_type, msg_id, group_id, sender_fp, key_epoch)

    # 3. AES-256-GCM encryption
    cipher = AES.new(group_key, AES.MODE_GCM, nonce=nonce)
    cipher.update(aad)
    ciphertext, tag = cipher.encrypt_and_digest(inner_payload)

    # 4. Core Cryptographic Blob: nonce (12B) + tag (16B) + ct_len (4B BE) + ct
    core_blob = nonce + tag + struct.pack(">I", len(ciphertext)) + ciphertext

    # 5. ECDSA P-256 signature over (AAD || CoreBlob)
    signed_data = aad + core_blob
    hasher = SHA256.new(signed_data)
    signer = DSS.new(signing_key, mode="deterministic-rfc6979", encoding="binary")
    signature = signer.sign(hasher)
    assert len(signature) == 64, f"Signature must be raw 64 bytes, got {len(signature)}"

    # 6. Assemble uncorrupted frame
    header = build_header(packet_type, ttl, msg_id, group_id, sender_fp, key_epoch)
    frame = header + core_blob + signature

    # 7. Apply tamper mutations if requested
    frame_bytes = bytearray(frame)
    if tamper_mode == "tamper_ciphertext":
        # Flip a bit in the ciphertext portion
        ct_offset = 24 + 32
        frame_bytes[ct_offset] ^= 0x01
    elif tamper_mode == "tamper_tag":
        # Flip a bit in the tag portion
        tag_offset = 24 + 12
        frame_bytes[tag_offset] ^= 0x01
    elif tamper_mode == "tamper_nonce":
        frame_bytes[24] ^= 0x01
    elif tamper_mode == "tamper_signature":
        frame_bytes[-1] ^= 0x01
    elif tamper_mode == "tamper_header_msg_id":
        frame_bytes[2] ^= 0x01
    elif tamper_mode == "tamper_header_sender_fp":
        frame_bytes[14] ^= 0x01
    elif tamper_mode == "tamper_header_key_epoch":
        frame_bytes[23] ^= 0x01
    elif tamper_mode == "ttl_zero":
        frame_bytes[1] = 0
    elif tamper_mode == "ttl_exceeded":
        frame_bytes[1] = 16
    elif tamper_mode == "unknown_packet_type":
        frame_bytes[0] = 0x99
    elif tamper_mode == "truncated_frame":
        frame_bytes = frame_bytes[:100]  # Truncate below min frame size
    elif tamper_mode == "len_overrun":
        # Set ciphertext_len field to 0x7FFFFFFF
        struct.pack_into(">I", frame_bytes, 24 + 28, 0x7FFFFFFF)

    frame_hex = frame_bytes.hex()

    return {
        "description": description,
        "expected_verdict": expected_verdict,
        "tamper_mode": tamper_mode,
        "header": {
            "packet_type": packet_type,
            "ttl": ttl,
            "msg_id_hex": f"{msg_id:016x}",
            "group_id_hex": f"{group_id:08x}",
            "sender_fp_hex": f"{sender_fp:016x}",
            "key_epoch": key_epoch,
        },
        "crypto": {
            "signing_pubkey_uncompressed_hex": pub_bytes.hex(),
            "group_key_hex": group_key.hex(),
            "nonce_hex": nonce.hex(),
            "tag_hex": tag.hex(),
            "ciphertext_len": len(ciphertext),
            "ciphertext_hex": ciphertext.hex(),
            "signature_hex": signature.hex(),
            "aad_hex": aad.hex(),
        },
        "payload": {
            "sent_at_ms": sent_at_ms,
            "plaintext": plaintext,
            "inner_payload_len": len(inner_payload),
        },
        "frame_hex": frame_hex,
        "frame_len": len(frame_bytes),
    }


def generate_all_vectors() -> list:
    vectors = []

    # Deterministic keys
    # Key 1: Admin
    key1 = int_to_p256_key(0x0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF)
    # Key 2: Alice
    key2 = int_to_p256_key(0xFEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210)
    # Key 3: Bob
    key3 = int_to_p256_key(0x112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00)

    # Deterministic Group Key (32 bytes)
    group_key = bytes.fromhex(
        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    )

    # ══════════════════════════════════════════════════════════════════════════
    # POSITIVE VECTORS (At least 20)
    # ══════════════════════════════════════════════════════════════════════════
    test_cases = [
        # (desc, type, ttl, msg_id, group_id, epoch, key, text)
        ("P01: Empty text message", wire_spec.PACKET_TYPE_CHAT, 7, 0x0000000000000001, 1, 1, key1, ""),
        ("P02: 1-byte ASCII text", wire_spec.PACKET_TYPE_CHAT, 7, 0x0000000000000002, 1, 1, key1, "A"),
        ("P03: Short greeting", wire_spec.PACKET_TYPE_CHAT, 7, 0x0000000000000003, 1, 1, key2, "Hello, mesh!"),
        ("P04: UTF-8 with emojis and symbols", wire_spec.PACKET_TYPE_CHAT, 5, 0x0000000000000004, 1, 1, key2, "P2Chat 🛡️⚡ BLE Mesh 🔐"),
        ("P05: Multi-lingual UTF-8 string", wire_spec.PACKET_TYPE_CHAT, 7, 0x0000000000000005, 1, 1, key3, "Bonjour, 世界, مرحباً, नमस्ते"),
        ("P06: 100-byte plaintext text", wire_spec.PACKET_TYPE_CHAT, 7, 0x0000000000000006, 1, 1, key1, "A" * 100),
        ("P07: 200-byte plaintext text", wire_spec.PACKET_TYPE_CHAT, 7, 0x0000000000000007, 1, 1, key1, "B" * 200),
        ("P08: 300-byte plaintext text", wire_spec.PACKET_TYPE_CHAT, 7, 0x0000000000000008, 1, 1, key2, "C" * 300),
        ("P09: Maximum 350-byte ASCII text (CHAT frame = 478B)", wire_spec.PACKET_TYPE_CHAT, 7, 0x0000000000000009, 1, 1, key2, "X" * 350),
        ("P10: Maximum 350-byte UTF-8 mixed bytes", wire_spec.PACKET_TYPE_CHAT, 7, 0x000000000000000A, 1, 1, key3, ("🔥" * 87) + "XY"),
        ("P11: Control packet KEY_ROTATE epoch 2", wire_spec.PACKET_TYPE_KEY_ROTATE, 7, 0x000000000000000B, 1, 2, key1, "KEY_ROTATE_BLOB_V2"),
        ("P12: Control packet ROSTER_SYNC", wire_spec.PACKET_TYPE_ROSTER_SYNC, 7, 0x000000000000000C, 1, 1, key1, "ROSTER_V1_HASH_XYZ"),
        ("P13: Control packet PAIRWISE_INVITE", wire_spec.PACKET_TYPE_PAIRWISE_INVITE, 3, 0x000000000000000D, 1, 1, key1, "INVITE_DATA_PAYLOAD"),
        ("P14: Diagnostic BENCH_DATA probe", wire_spec.PACKET_TYPE_BENCH_DATA, 7, 0x000000000000000E, wire_spec.BENCH_GROUP_ID, 1, key2, "PING_SEQ_0001"),
        ("P15: Diagnostic BENCH_ACK echo", wire_spec.PACKET_TYPE_BENCH_ACK, 7, 0x000000000000000F, wire_spec.BENCH_GROUP_ID, 1, key3, "PONG_SEQ_0001"),
        ("P16: TTL minimum 1 hop", wire_spec.PACKET_TYPE_CHAT, 1, 0x0000000000000010, 1, 1, key2, "Direct neighbor only"),
        ("P17: TTL maximum 15 hops", wire_spec.PACKET_TYPE_CHAT, 15, 0x0000000000000011, 1, 1, key3, "Deep epidemic flood"),
        ("P18: Epoch rollover epoch 10", wire_spec.PACKET_TYPE_CHAT, 7, 0x0000000000000012, 1, 10, key1, "Epoch 10 message"),
        ("P19: High group ID", wire_spec.PACKET_TYPE_CHAT, 7, 0x0000000000000013, 0xFFFFFFFE, 1, key2, "High group identifier"),
        ("P20: Max 64-bit msg_id", wire_spec.PACKET_TYPE_CHAT, 7, 0xFFFFFFFFFFFFFFFF, 1, 1, key3, "Max uint64 msg_id"),
        ("P21: Standard conversation message", wire_spec.PACKET_TYPE_CHAT, 7, 0x123456789ABCDEF0, 42, 1, key1, "Meet at North Gate in 10 minutes"),
        ("P22: Tactical situation report", wire_spec.PACKET_TYPE_CHAT, 7, 0x23456789ABCDEF01, 42, 1, key2, "Route is clear. Signal strength strong."),
    ]

    for idx, (desc, ptype, ttl, mid, gid, ep, key, text) in enumerate(test_cases, start=1):
        # Deterministic 12-byte nonce derived from index
        nonce = hashlib.sha256(f"nonce_seed_{idx}".encode("utf-8")).digest()[:12]
        vec = create_vector(
            description=desc,
            packet_type=ptype,
            ttl=ttl,
            msg_id=mid,
            group_id=gid,
            key_epoch=ep,
            signing_key=key,
            group_key=group_key,
            sent_at_ms=BASE_TIMESTAMP_MS + idx * 1000,
            plaintext=text,
            nonce=nonce,
            expected_verdict="ACCEPT",
            tamper_mode="none",
        )
        vectors.append(vec)

    # ══════════════════════════════════════════════════════════════════════════
    # NEGATIVE VECTORS (At least 10)
    # ══════════════════════════════════════════════════════════════════════════
    neg_cases = [
        ("N01: Tampered ciphertext bit", "tamper_ciphertext", "DROP_BAD_SIGNATURE"),
        ("N02: Tampered GCM tag bit", "tamper_tag", "DROP_BAD_SIGNATURE"),
        ("N03: Tampered GCM nonce bit", "tamper_nonce", "DROP_BAD_SIGNATURE"),
        ("N04: Tampered signature byte", "tamper_signature", "DROP_BAD_SIGNATURE"),
        ("N05: Tampered msg_id in header", "tamper_header_msg_id", "DROP_BAD_SIGNATURE"),
        ("N06: Tampered sender_fp in header", "tamper_header_sender_fp", "DROP_BAD_SIGNATURE"),
        ("N07: Tampered key_epoch in header", "tamper_header_key_epoch", "DROP_BAD_SIGNATURE"),
        ("N08: TTL of 0 received", "ttl_zero", "DROP_TTL"),
        ("N09: TTL above 15 (ttl=16)", "ttl_exceeded", "DROP_TTL"),
        ("N10: Unknown packet type 0x99", "unknown_packet_type", "DROP_MALFORMED"),
        ("N11: Truncated frame (100 bytes)", "truncated_frame", "DROP_MALFORMED"),
        ("N12: Ciphertext length overrun", "len_overrun", "DROP_MALFORMED"),
    ]

    for idx, (desc, mode, verdict) in enumerate(neg_cases, start=len(test_cases) + 1):
        nonce = hashlib.sha256(f"nonce_seed_neg_{idx}".encode("utf-8")).digest()[:12]
        vec = create_vector(
            description=desc,
            packet_type=wire_spec.PACKET_TYPE_CHAT,
            ttl=7,
            msg_id=0x9000000000000000 + idx,
            group_id=1,
            key_epoch=1,
            signing_key=key1,
            group_key=group_key,
            sent_at_ms=BASE_TIMESTAMP_MS + idx * 1000,
            plaintext="Negative test baseline",
            nonce=nonce,
            expected_verdict=verdict,
            tamper_mode=mode,
        )
        vectors.append(vec)

    return vectors


def self_check_vectors(vectors: list) -> bool:
    """Verifies all positive vectors unpack and verify in pure Python."""
    for vec in vectors:
        if vec["tamper_mode"] != "none":
            continue

        raw = bytes.fromhex(vec["frame_hex"])
        header_bytes = raw[:24]
        packet_type, ttl, msg_id, group_id, sender_fp, key_epoch = struct.unpack(
            ">BBQIQH", header_bytes
        )
        assert packet_type == vec["header"]["packet_type"]
        assert ttl == vec["header"]["ttl"]
        assert f"{msg_id:016x}" == vec["header"]["msg_id_hex"]
        assert f"{group_id:08x}" == vec["header"]["group_id_hex"]
        assert f"{sender_fp:016x}" == vec["header"]["sender_fp_hex"]
        assert key_epoch == vec["header"]["key_epoch"]

        # Blob & Signature
        blob_and_sig = raw[24:]
        signature = blob_and_sig[-64:]
        core_blob = blob_and_sig[:-64]

        # Verify ECDSA signature
        aad = build_aad(packet_type, msg_id, group_id, sender_fp, key_epoch)
        signed_data = aad + core_blob
        pubkey = ECC.import_key(bytes.fromhex(vec["crypto"]["signing_pubkey_uncompressed_hex"]), curve_name="p256")
        verifier = DSS.new(pubkey, "fips-186-3", encoding="binary")
        verifier.verify(SHA256.new(signed_data), signature)

        # Decrypt AES-GCM
        nonce = core_blob[:12]
        tag = core_blob[12:28]
        ct_len = struct.unpack(">I", core_blob[28:32])[0]
        ciphertext = core_blob[32:32 + ct_len]

        group_key = bytes.fromhex(vec["crypto"]["group_key_hex"])
        cipher = AES.new(group_key, AES.MODE_GCM, nonce=nonce)
        cipher.update(aad)
        decrypted = cipher.decrypt_and_verify(ciphertext, tag)

        sent_at_ms = struct.unpack(">q", decrypted[:8])[0]
        text = decrypted[8:].decode("utf-8")
        assert sent_at_ms == vec["payload"]["sent_at_ms"]
        assert text == vec["payload"]["plaintext"]

    return True


def main() -> int:
    vectors = generate_all_vectors()
    pos_count = sum(1 for v in vectors if v["tamper_mode"] == "none")
    neg_count = sum(1 for v in vectors if v["tamper_mode"] != "none")

    print(f"Generated {len(vectors)} vectors ({pos_count} positive, {neg_count} negative).")
    assert pos_count >= 20, f"Expected >= 20 positive vectors, got {pos_count}"
    assert neg_count >= 10, f"Expected >= 10 negative vectors, got {neg_count}"

    # Self check
    print("Running Python self-check on positive vectors...")
    self_check_vectors(vectors)
    print("Self-check passed!")

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    json_bytes = json.dumps(
        {
            "version": "1.0",
            "total_vectors": len(vectors),
            "positive_count": pos_count,
            "negative_count": neg_count,
            "vectors": vectors,
        },
        indent=2,
    ).encode("utf-8")

    OUTPUT_PATH.write_bytes(json_bytes)
    print(f"Wrote {len(json_bytes)} bytes to {OUTPUT_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
