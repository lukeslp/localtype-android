#!/usr/bin/env python3
"""Turn a candidate language build into the app's word-list assets (languages route A).

``model/language_build.py`` writes candidate lists to Galactus and bundles nothing. This takes one
of those builds and writes the three lists the keyboard reads for that language:

    words_<lang>.txt            suggestions, ``word<TAB>count``, without slurs or swear words
    words_<lang>_profanity.txt  the swear words taken out of it, offered only with Suggest swear words
    words_known_<lang>.txt      recognized words, without slurs; swear words stay, so they are
                                never corrected into something else

A word is a slur when its SHA-256 is in ``SuggestionWordRules.EXCLUDED_HASHES`` (every language;
English slurs turn up in other languages' subtitles) or the language's own set, read from the
Kotlin source so the build and the keyboard can't disagree. ``BundledWordListsTest`` checks the
result. Nothing here replaces a native speaker's reading of the lists: Spanish ships as
experimental without one (Luke, 2026-10-18).

Usage, from the repository root with Galactus mounted:

    python -m model.language_bundle es --build es-candidate-2
"""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
from pathlib import Path
from typing import Iterable, Sequence

from model import language_build as lb

RULES = Path("engine/src/commonMain/kotlin/dev/localtype/keyboard/SuggestionWordRules.kt")
ASSETS = Path("app/src/main/assets")

# Swear words as strong as the English list's (words_en_profanity.txt). Words that are also
# ordinary words in much of the Spanish-speaking world ("coger", "concha", "pito") stay ordinary.
SWEARS = {
    "es": (
        "mierda mierdas joder jodido jodida jodidos jodidas jódete jodete coño cabrón cabrones puta putas "
        "gilipollas pendejo pendejos verga follar follando follado cojones polla pollas zorra zorras "
        "mamada cagar cagado cagando cagó"
    ).split(),
    # As strong as the English list. Words that are also ordinary ("pau", stick; "pica", in Portugal a jab;
    # "piranha", the fish; "veado", deer) stay ordinary.
    "pt": (
        "porra porras caralho caralhos merda merdas foda fodas foder fodido fodida fodidos fodidas fode fodeu "
        "puta putas cacete cu cuzão buceta boceta piroca arrombado arrombada bosta cagar cagado punheta xoxota "
        "xereca vadia vagabunda babaca"
    ).split(),
}

HEADERS = {
    "es": {
        "words": "Spanish word frequencies derived from hermitdave/FrequencyWords (OpenSubtitles 2018), CC BY-SA 4.0. "
                 "Experimental: not read by a native speaker; slurs removed by digest, swear words in words_es_profanity.txt.",
        "profanity": "Common Spanish swear words, counts from hermitdave/FrequencyWords (OpenSubtitles 2018), CC BY-SA 4.0. "
                     "Slurs are deliberately excluded.",
        "known": "Recognized Spanish words (not suggestions) from hermitdave/FrequencyWords (OpenSubtitles 2018), CC BY-SA 4.0, "
                 "count at least 20. Experimental: not read by a native speaker; slurs removed by digest.",
    },
    "pt": {
        "words": "Brazilian Portuguese word frequencies derived from hermitdave/FrequencyWords (OpenSubtitles 2018, pt_br), CC BY-SA 4.0. "
                 "Experimental: not read by a native speaker; slurs removed by digest, swear words in words_pt_profanity.txt.",
        "profanity": "Common Portuguese swear words, counts from hermitdave/FrequencyWords (OpenSubtitles 2018, pt_br), CC BY-SA 4.0. "
                     "Slurs are deliberately excluded.",
        "known": "Recognized Portuguese words (not suggestions) from hermitdave/FrequencyWords (OpenSubtitles 2018, pt_br), CC BY-SA 4.0, "
                 "count at least 20. Experimental: not read by a native speaker; slurs removed by digest.",
    },
}


def digests(source: str, name: str) -> set[str]:
    """The hex digests in the Kotlin ``val <name>: Set<String> = \"\"\"…\"\"\"`` block."""
    m = re.search(rf"val {re.escape(name)}: Set<String> = \"\"\"(.*?)\"\"\"", source, re.S)
    if not m:
        raise ValueError(f"{name} not found in {RULES}")
    found = set(re.findall(r"\b[0-9a-f]{64}\b", m.group(1)))
    if not found:
        raise ValueError(f"{name} is empty")
    return found


def excluded(word: str, hashes: set[str]) -> bool:
    """The whole entry and each word of a phrase or hyphenated compound, as the Kotlin check reads them."""
    entry = word.strip().lower()
    forms = [entry] + [w for w in re.split(r"[ -]", entry) if w]
    return any(hashlib.sha256(f.encode("utf-8")).hexdigest() in hashes for f in forms)


def read_counts(path: Path) -> list[tuple[str, int]]:
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        word, count = line.split("\t")
        out.append((word, int(count)))
    return out


def read_words(path: Path) -> list[str]:
    return [line.strip() for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.startswith("#")]


def split(words: Iterable[tuple[str, int]], hashes: set[str], swears: set[str]):
    """Suggestions, swear words and the slurs dropped (counted, never returned as words)."""
    keep, swore, slurs = [], [], 0
    for word, count in words:
        if excluded(word, hashes):
            slurs += 1
        elif word in swears:
            swore.append((word, count))
        else:
            keep.append((word, count))
    return keep, swore, slurs


def bundle(lang: str, build_dir: Path, assets: Path, rules_source: str) -> dict:
    hashes = digests(rules_source, "EXCLUDED_HASHES") | digests(rules_source, f"EXCLUDED_HASHES_{lang.upper()}")
    swears = {lb.normalize(w) for w in SWEARS[lang]}
    top = read_counts(build_dir / f"words_{lang}.txt")
    keep, swore, top_slurs = split(top, hashes, swears)
    known = read_words(build_dir / f"words_known_{lang}.txt")
    known_keep = [w for w in known if not excluded(w, hashes)]
    headers = HEADERS[lang]
    lb.write_word_list(assets / f"words_{lang}.txt", keep, headers["words"])
    lb.write_word_list(assets / f"words_{lang}_profanity.txt", swore, headers["profanity"])
    with open(assets / f"words_known_{lang}.txt", "w", encoding="utf-8") as f:
        f.write(f"# {headers['known']}\n")
        for w in known_keep:
            f.write(f"{w}\n")
    return {
        "suggestions": len(keep), "swear_words": len(swore), "slurs_dropped_from_suggestions": top_slurs,
        "known": len(known_keep), "slurs_dropped_from_known": len(known) - len(known_keep),
        "swear_words_not_in_suggestions": sorted(swears - {w for w, _ in swore}),
    }


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("lang", choices=sorted(SWEARS))
    p.add_argument("--build", required=True, help="candidate build under <root>/languages/<lang>/")
    p.add_argument("--assets", default=str(ASSETS))
    return p.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> None:
    args = parse_args(argv)
    build_dir = lb.DATA_ROOT / "languages" / args.lang / args.build
    summary = bundle(args.lang, build_dir, Path(args.assets), RULES.read_text(encoding="utf-8"))
    for k, v in summary.items():
        print(f"{k}: {v}", file=sys.stderr)


if __name__ == "__main__":
    main()
