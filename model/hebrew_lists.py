#!/usr/bin/env python3
"""Hebrew word lists for the keyboard, straight from FrequencyWords (no Galactus build needed).

Hebrew has no corpus prior or Leipzig build yet, so this skips ``model/language_build.py`` and writes the three
lists the keyboard reads from hermitdave/FrequencyWords' Hebrew subtitle counts alone:

    words_he.txt            suggestions, ``word<TAB>count``, the 20,000 commonest, without slurs or swear words
    words_he_profanity.txt  the swear words taken out of it, offered only with Suggest swear words
    words_known_he.txt      recognized words (count at least 20), without slurs

A word is the 22 letters and five final forms only (U+05D0 to U+05EA), two letters or more: Hebrew's one-letter
words are prefixes written onto the next word. Slurs are dropped by their SHA-256 in ``SuggestionWordRules`` (every
language's and ``EXCLUDED_HASHES_HE``). Experimental: the lists have not been read by a native speaker.

Usage, from the repository root, with he_full.txt downloaded from
https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/he/he_full.txt

    python3 model/hebrew_lists.py path/to/he_full.txt
"""

from __future__ import annotations

import hashlib
import re
import sys
import unicodedata
from pathlib import Path

RULES = Path("engine/src/commonMain/kotlin/dev/localtype/keyboard/SuggestionWordRules.kt")
ASSETS = Path("app/src/main/assets")
WORDS = 20_000
KNOWN_FLOOR = 20
WORD = re.compile("[א-ת]{2,}")
SOURCE = ("hermitdave/FrequencyWords, content/2018/he (OpenSubtitles 2018) at commit "
          "525f9b560de45753a5ea01069454e72e9aa541c6, CC BY-SA 4.0")

# Swear words as strong as the English list's. Words that are also ordinary ("כוס", a glass; "תחת", under;
# "כלבה", a female dog; "זבל", rubbish) stay ordinary.
SWEARS = set(
    "זונה זונות שרמוטה שרמוטות זין זיון לזיין מזדיין מזדיינת מזדיינים תזדיין זיינו חרא חארות מניאק מניאקים "
    "מניאקית שמוק כוסית כוסיות כוסאמק כוסעמק כוסאמא שיט פאק פאקינג מזוין מזוינת".split())


def digests(source: str, name: str) -> set[str]:
    block = re.search(name + r': Set<String> = """(.*?)"""', source, re.S)
    if not block:
        raise SystemExit(f"{name} not found in {RULES}")
    return set(block.group(1).split())


def main(argv: list[str]) -> None:
    if len(argv) != 1:
        raise SystemExit(__doc__)
    source = RULES.read_text(encoding="utf-8")
    hashes = digests(source, "EXCLUDED_HASHES") | digests(source, "EXCLUDED_HASHES_HE")
    counts: dict[str, int] = {}
    with open(argv[0], encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").rsplit(" ", 1)
            if len(parts) != 2 or not parts[1].isdigit():
                continue
            word = unicodedata.normalize("NFC", parts[0])
            if WORD.fullmatch(word):
                counts[word] = counts.get(word, 0) + int(parts[1])
    ranked = sorted(counts.items(), key=lambda item: (-item[1], item[0]))
    clean = [(w, n) for w, n in ranked if hashlib.sha256(w.encode()).hexdigest() not in hashes]
    suggestions = [(w, n) for w, n in clean if w not in SWEARS][:WORDS]
    profanity = [(w, n) for w, n in clean if w in SWEARS]
    known = sorted(w for w, n in clean if n >= KNOWN_FLOOR)
    note = "Experimental: not read by a native speaker; slurs removed by digest"

    def write(name: str, header: str, lines: list[str]) -> None:
        (ASSETS / name).write_text(f"# {header}\n" + "".join(line + "\n" for line in lines), encoding="utf-8")

    write("words_he.txt", f"Hebrew word frequencies derived from {SOURCE}. {note}, swear words in words_he_profanity.txt.",
          [f"{w}\t{n}" for w, n in suggestions])
    write("words_he_profanity.txt", f"Common Hebrew swear words, counts from {SOURCE}. Slurs are deliberately excluded.",
          [f"{w}\t{n}" for w, n in profanity])
    write("words_known_he.txt", f"Recognized Hebrew words (not suggestions) from {SOURCE}, count at least {KNOWN_FLOOR}. {note}.",
          known)
    print(f"{len(suggestions)} suggestions, {len(profanity)} swear words, {len(known)} known; "
          f"{len(ranked) - len(clean)} slurs dropped")


if __name__ == "__main__":
    main(sys.argv[1:])
