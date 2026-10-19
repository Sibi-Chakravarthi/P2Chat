#!/usr/bin/env python3
"""
scripts/ai_reference.py

Reference implementations for P2Chat on-device AI moderation (T3.3, AI-2, AI-3):
1. Regex Text Normalizer (AI-2):
   - Strips zero-width characters.
   - Collapses character repetitions > 2 to 2.
   - Substitutes @->a, 1/!->i, $->s, 0->o only when bounded by letters on both sides.
2. WordPiece Tokenizer (AI-3):
   - BERT uncased rules with vocab.txt from app/src/main/assets/vocab.txt.
   - Truncates to max_length=128 tokens, adds [CLS] (101) and [SEP] (102).
   - Generates input_ids and attention_mask.
"""

import re
import unicodedata
from pathlib import Path
from typing import Dict, List, Tuple

REPO_ROOT = Path(__file__).resolve().parent.parent
VOCAB_PATH = REPO_ROOT / "app" / "src" / "main" / "assets" / "vocab.txt"

# Zero-width unicode characters to strip (AI-2)
ZERO_WIDTH_CHARS = [
    "\u200B",  # Zero-width space
    "\u200C",  # Zero-width non-joiner
    "\u200D",  # Zero-width joiner
    "\u200E",  # Left-to-right mark
    "\u200F",  # Right-to-left mark
    "\u2060",  # Word joiner
    "\uFEFF",  # Zero-width no-break space / BOM
]
ZERO_WIDTH_PATTERN = re.compile("[" + "".join(ZERO_WIDTH_CHARS) + "]")

# Character runs of length > 2 collapsed to 2 (e.g., 'heeeelp' -> 'heelp')
RUNS_PATTERN = re.compile(r"(.)\1{2,}")

# Leetspeak bounded by ASCII letters on both sides
LEET_AT_PATTERN = re.compile(r"(?<=[a-zA-Z])@(?=[a-zA-Z])")
LEET_ONE_EXCL_PATTERN = re.compile(r"(?<=[a-zA-Z])[1!](?=[a-zA-Z])")
LEET_DOLLAR_PATTERN = re.compile(r"(?<=[a-zA-Z])\$(?=[a-zA-Z])")
LEET_ZERO_PATTERN = re.compile(r"(?<=[a-zA-Z])0(?=[a-zA-Z])")


def normalize_text(text: str) -> str:
    """
    Normalizes text according to P2Chat AI-2 requirements:
    1. Strip zero-width characters.
    2. Collapse character repetitions > 2 to 2.
    3. Replace leetspeak symbols (@->a, 1/!->i, $->s, 0->o) ONLY when bounded by letters.
    """
    if not text:
        return ""

    # Step 1: Strip zero-width characters
    res = ZERO_WIDTH_PATTERN.sub("", text)

    # Step 2: Collapse runs of > 2 identical characters to 2
    res = RUNS_PATTERN.sub(r"\1\1", res)

    # Step 3: Bounded leetspeak substitution (must have letter on both sides)
    res = LEET_AT_PATTERN.sub("a", res)
    res = LEET_ONE_EXCL_PATTERN.sub("i", res)
    res = LEET_DOLLAR_PATTERN.sub("s", res)
    res = LEET_ZERO_PATTERN.sub("o", res)

    return res


def is_punctuation(char: str) -> bool:
    cp = ord(char)
    if (33 <= cp <= 47) or (58 <= cp <= 64) or (91 <= cp <= 96) or (123 <= cp <= 126):
        return True
    cat = unicodedata.category(char)
    return cat.startswith("P")


def is_whitespace(char: str) -> bool:
    return char.isspace()


def is_control(char: str) -> bool:
    if char == "\t" or char == "\n" or char == "\r":
        return False
    cat = unicodedata.category(char)
    return cat.startswith("C")


class ReferenceWordPieceTokenizer:
    """
    Reference WordPiece tokenizer implementation compatible with BERT / all-MiniLM-L6-v2.
    """

    CLS_TOKEN = "[CLS]"
    SEP_TOKEN = "[SEP]"
    UNK_TOKEN = "[UNK]"
    PAD_TOKEN = "[PAD]"

    CLS_ID = 101
    SEP_ID = 102
    UNK_ID = 100
    PAD_ID = 0

    def __init__(self, vocab_file: Path = VOCAB_PATH):
        if not vocab_file.is_file():
            raise FileNotFoundError(f"Vocab file not found at {vocab_file}")

        self.vocab: Dict[str, int] = {}
        self.inv_vocab: Dict[int, str] = {}
        with open(vocab_file, "r", encoding="utf-8") as f:
            for i, line in enumerate(f):
                token = line.rstrip("\r\n")
                self.vocab[token] = i
                self.inv_vocab[i] = token

    def basic_tokenize(self, text: str) -> List[str]:
        """
        Cleans control chars, strips accents, lowercases, and splits on whitespace/punctuation.
        """
        if not text:
            return []

        # Strip control characters
        out_chars = []
        for char in text:
            if not is_control(char):
                out_chars.append(char)
        text = "".join(out_chars)

        # Standard NFD normalization and strip accents
        text = unicodedata.normalize("NFD", text)
        text = "".join(c for c in text if unicodedata.category(c) != "Mn")

        # Lowercase
        text = text.lower()

        # Split on whitespace and punctuation
        tokens = []
        current_token = []
        for char in text:
            if is_whitespace(char):
                if current_token:
                    tokens.append("".join(current_token))
                    current_token = []
            elif is_punctuation(char):
                if current_token:
                    tokens.append("".join(current_token))
                    current_token = []
                tokens.append(char)
            else:
                current_token.append(char)
        if current_token:
            tokens.append("".join(current_token))

        return tokens

    def wordpiece_tokenize(self, word: str) -> List[str]:
        """
        Greedy longest-match WordPiece tokenization for a single word.
        """
        if len(word) > 200:
            return [self.UNK_TOKEN]

        sub_tokens = []
        start = 0
        while start < len(word):
            end = len(word)
            cur_substr = None
            while start < end:
                substr = word[start:end]
                if start > 0:
                    substr = "##" + substr
                if substr in self.vocab:
                    cur_substr = substr
                    break
                end -= 1
            if cur_substr is None:
                return [self.UNK_TOKEN]
            sub_tokens.append(cur_substr)
            start = end

        return sub_tokens

    def tokenize(self, text: str) -> List[str]:
        words = self.basic_tokenize(text)
        tokens = []
        for word in words:
            tokens.extend(self.wordpiece_tokenize(word))
        return tokens

    def encode(self, text: str, max_length: int = 128) -> Tuple[List[int], List[int]]:
        """
        Encodes text into (input_ids, attention_mask) of fixed length max_length.
        Layout: [CLS] + tokens[:max_length-2] + [SEP] + padding to max_length.
        """
        raw_tokens = self.tokenize(text)
        # Leave room for [CLS] and [SEP]
        truncated = raw_tokens[: max_length - 2]
        tokens = [self.CLS_TOKEN] + truncated + [self.SEP_TOKEN]

        ids = [self.vocab.get(t, self.UNK_ID) for t in tokens]
        mask = [1] * len(ids)

        # Pad to max_length
        padding_len = max_length - len(ids)
        if padding_len > 0:
            ids.extend([self.PAD_ID] * padding_len)
            mask.extend([0] * padding_len)

        return ids, mask
