#!/usr/bin/env python3
"""
scripts/gen_tokenizer_fixtures.py

Generates 100+ deterministic test fixtures for P2Chat normalizer & tokenizer parity (T3.3, AI-2, AI-3):
- Plain English sentences
- Leetspeak variations
- Zero-width character obfuscation
- Tanglish (Tamil-English code switching)
- Emojis and unicode symbols
- Empty string and whitespace
- Over-200-token long text (testing 128-token truncation with [SEP])
- 10+ numeric/time/symbol strings that must remain 100% unchanged

Writes to: app/src/test/resources/ai/tokenizer_fixtures.json
"""

import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT))

from scripts.ai_reference import ReferenceWordPieceTokenizer, normalize_text
OUTPUT_DIR = REPO_ROOT / "app" / "src" / "test" / "resources" / "ai"
OUTPUT_FILE = OUTPUT_DIR / "tokenizer_fixtures.json"


def build_fixture_inputs() -> list[str]:
    # 1. Ten+ critical numeric, time, and symbol boundary strings that MUST remain 100% unchanged (AI-2)
    unchanged_strings = [
        "12:00",
        "1 km",
        "stop!",
        "@name",
        "room 101",
        "$500",
        "10:30 am",
        "0 degrees",
        "step 1",
        "call 911",
        "level 0",
        "25% discount!",
    ]

    # 2. Leetspeak strings (symbols bounded by letters that MUST be substituted)
    leetspeak_strings = [
        "b@d behaviour",
        "f!ne person",
        "c00l breeze",
        "t1me is running out",
        "wh@t are you doing",
        "k!ll that insect",
        "sh!t happens",
        "h@te speech test",
        "p@ssword required",
        "b!rd is flying",
        "l0ve and peace",
        "w0rd of advice",
        "m!nd your step",
        "g00d morning everyone",
        "d@rkness falls",
        "c@t and d0g",
        "b00k a ticket",
        "f00d delivery arriving",
        "l!fe is beautiful",
        "s0ng of freedom",
    ]

    # 3. Repeated character collapse strings (>2 chars collapsed to 2, e.g. heeeelp -> heelp, good/see unchanged)
    repeat_strings = [
        "heeeelp me please",
        "soooo much fun",
        "nooooo way",
        "good morning to all",
        "see you later tonight",
        "yesssss exactly",
        "stoooppp doing that",
        "whyyyy are you crying",
        "coooool idea brother",
        "byeeeee see you soon",
        "freeeeedom now",
        "pleaaase help us",
        "waaaait for me",
        "hahahaha so funnyyyy",
        "runnnnn fast",
    ]

    # 4. Zero-width character obfuscation strings
    zero_width_strings = [
        "to\u200Bxi\u200Bc message",
        "se\u200Ccr\u200Det protest meetup",
        "po\u200Elice alert\u200F here",
        "\uFEFFwelcome to p2chat mesh",
        "at\u2060tack cancelled",
        "hid\u200Bden con\u200Ctent test",
        "zero\u200Dwidth space\u200B inside",
        "in\u200Bvis\u200Cible mark\uFEFFer",
        "me\u200Deting at 12:00\u200B sharp",
        "sec\u200Bure com\u200Bms",
    ]

    # 5. Tanglish conversational sentences
    tanglish_strings = [
        "machan enna da panra",
        "romba nalla irukku bro",
        "vanakkam nanba epdi iruka",
        "kaalaile vaada seekiram",
        "anga police irukanga paathuko",
        "namma group la share pannu",
        "avanga kitta pesatha da",
        "seri da naanga varom",
        "inga romba crowd irukku",
        "ble connect aagala enaku",
    ]

    # 6. Emojis and international unicode
    emoji_strings = [
        "Emergency alert! 🚨 Move back immediately!",
        "Water station set up here 💧🚰",
        "Medical assistance needed 🚑🩹 please hurry",
        "Stay safe everyone ✌️🕊️ peaceful gathering",
        "Signal strength is full 📶 mesh working",
        "Battery at 15% 🪫 turning off scanner",
        "Great job team! 👍🎉🚀",
        "Warning: road blocked ahead ⛔🛑",
        "Sunny day in Chennai ☀️🏖️",
        "Encrypted chat locked 🔒🔑",
    ]

    # 7. Plain English chat and coordination messages
    plain_strings = [
        "Are we meeting near the central plaza?",
        "Can someone bring extra charging cables?",
        "The decentralized mesh network has six active hops.",
        "Store and forward epidemic routing protocol in action.",
        "No cellular network or internet needed.",
        "End-to-end encryption verified with P-256 ECDSA.",
        "Key rotation scheduled for epoch number two.",
        "Please keep voice levels down in the library.",
        "Verified roster member added by admin.",
        "Packet delivery ratio reached ninety eight percent.",
        "Battery consumption remains well within budget.",
        "Quarantined message successfully reviewed by user.",
        "Filter detected potential harassment and blurred message.",
        "Onboarding complete with fresh cryptographic keypair.",
        "QR code scanned and TOFU identity established.",
        "Decentralized peer discovery running on BLE central and peripheral.",
        "Relaying encrypted frame to nearby mesh participants.",
        "Bloom filter digest contains twelve hundred bytes.",
        "MTU negotiation settled on five hundred and seventeen bytes.",
        "All security invariants tested and passing.",
    ]

    # 8. Edge cases: Empty, single char, whitespace, very long text (> 200 tokens)
    long_text = " ".join([
        "This is an extraordinarily long message designed specifically to exceed the maximum tokenizer limit of one hundred and twenty eight tokens.",
        "In a peer-to-peer epidemic mesh chat system, messages are chunked, encrypted, and routed hop-by-hop across mobile handsets without servers.",
        "When a user enters a long paragraph, the WordPiece tokenizer must greedily segment every word into subwords and truncate strictly to one hundred and twenty eight tokens.",
        "The first token must always be the classification token CLS with ID 101, and the final non-padded token must always be the separation token SEP with ID 102.",
        "Even when the raw text spans multiple sentences and hundreds of vocabulary tokens, safety and stability require deterministic bounded execution.",
        "Additional filler words: mesh bluetooth central peripheral advertiser scanner gatt service characteristic uuid payload epoch rotation."
    ])

    edge_strings = [
        "",
        "   ",
        "a",
        "Z",
        "?",
        "...",
        "!@#$%^&*()",
        long_text,
    ]

    all_inputs = (
        unchanged_strings
        + leetspeak_strings
        + repeat_strings
        + zero_width_strings
        + tanglish_strings
        + emoji_strings
        + plain_strings
        + edge_strings
    )

    return all_inputs


def generate_fixtures() -> int:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    tokenizer = ReferenceWordPieceTokenizer()
    inputs = build_fixture_inputs()

    fixtures = []
    unchanged_verified_count = 0

    for idx, raw_input in enumerate(inputs, start=1):
        norm = normalize_text(raw_input)
        ids, mask = tokenizer.encode(norm, max_length=128)

        # Check unchanging property for the designated unchanged set
        if idx <= 12:
            assert raw_input == norm, f"Unchanged fixture {idx} ('{raw_input}') was corrupted to '{norm}'!"
            unchanged_verified_count += 1

        # Check token length invariant
        assert len(ids) == 128, f"Token IDs length must be 128, got {len(ids)}"
        assert len(mask) == 128, f"Attention mask length must be 128, got {len(mask)}"

        # Check CLS and SEP invariants
        assert ids[0] == ReferenceWordPieceTokenizer.CLS_ID, f"First token must be [CLS] (101)"
        num_attended = sum(mask)
        assert ids[num_attended - 1] == ReferenceWordPieceTokenizer.SEP_ID, f"Last attended token must be [SEP] (102)"

        fixtures.append({
            "id": idx,
            "input": raw_input,
            "normalized": norm,
            "token_ids": ids,
            "attention_mask": mask,
        })

    payload = {
        "generator": "scripts/gen_tokenizer_fixtures.py",
        "total_fixtures": len(fixtures),
        "max_length": 128,
        "special_tokens": {
            "PAD": ReferenceWordPieceTokenizer.PAD_ID,
            "UNK": ReferenceWordPieceTokenizer.UNK_ID,
            "CLS": ReferenceWordPieceTokenizer.CLS_ID,
            "SEP": ReferenceWordPieceTokenizer.SEP_ID,
        },
        "fixtures": fixtures,
    }

    # Deterministic JSON format with indentation and sorted keys
    content = json.dumps(payload, indent=2, ensure_ascii=False) + "\n"
    OUTPUT_FILE.write_text(content, encoding="utf-8")

    print(f"[gen_tokenizer_fixtures] Wrote {len(fixtures)} fixtures to {OUTPUT_FILE}")
    print(f"[gen_tokenizer_fixtures] Verified {unchanged_verified_count} unchanged numeric/time fixtures.")
    return len(fixtures)


def main():
    print("==================================================")
    print("P2Chat Tokenizer Fixtures Generator (T3.3)")
    print("==================================================")
    count1 = generate_fixtures()

    # Re-run to verify byte-identical reproducibility
    content1 = OUTPUT_FILE.read_bytes()
    count2 = generate_fixtures()
    content2 = OUTPUT_FILE.read_bytes()

    if content1 != content2:
        print("[gen_tokenizer_fixtures] FAILED: Consecutive runs produced non-identical output!", file=sys.stderr)
        return 1

    print(f"[gen_tokenizer_fixtures] SUCCESS: Verified byte-identical output across consecutive runs ({count1} fixtures).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
