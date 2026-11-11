#!/usr/bin/env python3
"""Word lists for the keyboard's Arabic and Persian, from openly licensed text, with no Galactus build.

    ar  hermitdave/FrequencyWords ``content/2018/ar/ar_full.txt`` (OpenSubtitles 2018 counts), CC BY-SA 4.0
    fa  the same source's ``fa_full.txt``, plus one or more parts of a Persian Wikipedia ``pages-articles`` dump
        (CC BY-SA 4.0) for the spellings subtitles don't have

and writes the three lists the keyboard reads, as ``model/hebrew_lists.py`` does for Hebrew:

    words_<code>.txt            suggestions, ``word<TAB>count``, the 20,000 commonest, without slurs or swear words
    words_<code>_profanity.txt  the swear words taken out of it, offered only with Suggest swear words
    words_known_<code>.txt      recognized words, without slurs

Every spelling is normalized the way the keyboard types it. Tatweel, presentation forms and the vowel marks go, except
the tanwin fathatan at the end of a word ("شكراً", "مثلاً"), which everyday text keeps and the keyboard folds like an
accent; "ًا" is written "اً". A word is the language's own letters only (``LanguageProfile.ARABIC`` and ``PERSIAN``),
two letters or more, or the one-letter "و".

Persian has two more steps. Subtitle text mixes Arabic ي and ك with Persian ی and ک and never writes the zero-width
non-joiner (U+200C), so ي, ى and ك become the Persian letters, and the non-joiner spellings ("می‌رود", "آن‌ها") come from
Wikipedia: its counts are scaled to the subtitle total and added at half weight, since typing sits between the two
registers, and a word counts only once it is in three articles. Where a word is written both joined and with the
non-joiner, only the non-joiner spelling is suggested when Wikipedia uses it at least as often; the joined one stays
recognized, so it is never changed under anyone's fingers.

In both languages, spellings that differ only by what the keyboard folds (a hamza on alef, ه for ة, the tanwin) are one
group. Its bare spelling ("ان" beside "أن" and "إن") is recognized but not suggested when it is under a quarter of the
group's commonest, since it is then mostly the same word typed without its mark; a marked spelling is always kept,
because "أن" and "إن" are different words.

Counting a Wikipedia part takes several minutes, so its counts are kept beside it (``<part>.counts.json``) and reused.

Slurs are dropped by their SHA-256 in ``SuggestionWordRules`` (every language's lists). Experimental: no native speaker
has read the lists, and the slur and swear lists are short.

Usage, from the repository root:

    python3 model/arabic_script_lists.py ar path/to/ar_full.txt
    python3 model/arabic_script_lists.py fa path/to/fa_full.txt path/to/fawiki-20261101-pages-articles1.xml-p1p1500000.bz2
"""

from __future__ import annotations

import bz2
import json
import re
import sys
import unicodedata
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from small_language_lists import digests, is_slur, plain  # noqa: E402

RULES = Path("engine/src/commonMain/kotlin/dev/localtype/keyboard/SuggestionWordRules.kt")
ASSETS = Path("app/src/main/assets")
WORDS = 20_000
KNOWN_FLOOR = 20
ARTICLES_FLOOR = 3
WIKI_WEIGHT = 0.5
FREQUENCY_WORDS = ("hermitdave/FrequencyWords, content/2018/{code} (OpenSubtitles 2018) at commit "
                   "525f9b560de45753a5ea01069454e72e9aa541c6, CC BY-SA 4.0")

ZWNJ = "‌"
FATHATAN = "ً"
TATWEEL = "ـ"
# Every combining mark of the Arabic block but the fathatan, which is handled on its own.
MARKS = re.compile("[ؐ-ٌؚ-ٰٟۖ-ۜ۟-۪ۤۧۨ-ۭ]")
PRESENTATION = re.compile("[ﭐ-﷿ﹰ-﻿]")

LANGUAGES = {
    "ar": dict(
        name="Arabic",
        letters="ابتثجحخدذرزسشصضطظعغفقكلمنهويءآأإؤئةى",
        folds={"أ": "ا", "إ": "ا", "آ": "ا", "ؤ": "و", "ئ": "ي", "ى": "ي", "ة": "ه"},
        one_letter={"و"},
        # Strong swear words only, as in the English list; milder insults stay ordinary words.
        swears="كس كسمك كسم شرموطة شرموط شراميط عرص منيوك منيوكة متناك متناكة نيك انيك زب زبي طيز طيزك "
               "خرا خراء قحبة قحاب عاهرة عاهرات لعين",
    ),
    "fa": dict(
        name="Persian",
        letters="ابپتثجچحخدذرزژسشصضطظعغفقکگلمنوهیءآأؤئ",
        folds={"آ": "ا", "أ": "ا", "ؤ": "و", "ئ": "ی"},
        one_letter={"و"},
        to_persian={"ي": "ی", "ى": "ی", "ك": "ک"},
        joiner=True,
        swears="کیر کیری کیرم کس کص کسکش کس‌کش کصکش جنده جاکش کون گاییدم گاییدن بگا مادرجنده مادر‌جنده "
               "حرومزاده حرامزاده لاشی دیوث گوه گه ریدم ریدی",
    ),
}


def normalize(text: str, language: dict) -> str:
    """Running text or one word, as the keyboard would have typed it."""
    text = PRESENTATION.sub(lambda m: unicodedata.normalize("NFKC", m.group(0)), text)
    text = unicodedata.normalize("NFC", text).replace(TATWEEL, "")
    text = MARKS.sub("", text)
    # The tanwin is written after the alef here ("اً"); anywhere but a word's end it is a vowel mark like the others.
    text = text.replace(FATHATAN + "ا", "ا" + FATHATAN)
    text = re.sub(FATHATAN + r"(?=[\w" + ZWNJ + "])", "", text)
    for arabic, persian in language.get("to_persian", {}).items():
        text = text.replace(arabic, persian)
    if language.get("joiner"):
        text = re.sub(ZWNJ + "+", ZWNJ, text)
    return text


def word_pattern(language: dict) -> re.Pattern[str]:
    """A whole token of the language's letters, not a piece of a longer run of letters."""
    cls = re.escape(language["letters"])
    inner = f"(?:{ZWNJ}?[{cls}])*" if language.get("joiner") else f"[{cls}]*"
    return re.compile(f"(?<![^\\W\\d_{ZWNJ}])[{cls}]{inner}{FATHATAN}?(?![^\\W\\d_]|{ZWNJ}[^\\W\\d_])")


def valid(word: str, language: dict) -> bool:
    return len(word) >= 2 or word in language["one_letter"]


def subtitle_counts(path: Path, language: dict, pattern: re.Pattern[str]) -> Counter:
    counts: Counter = Counter()
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").rsplit(" ", 1)
            if len(parts) != 2 or not parts[1].isdigit():
                continue
            word = normalize(parts[0], language).strip(ZWNJ)
            if pattern.fullmatch(word) and valid(word, language):
                counts[word] += int(parts[1])
    return counts


def wikipedia_counts(paths: list[Path], language: dict, pattern: re.Pattern[str]) -> tuple[Counter, Counter]:
    total, articles = Counter(), Counter()
    for path in paths:
        cache = path.with_name(path.name + ".counts.json")
        if cache.exists():
            saved = json.loads(cache.read_text(encoding="utf-8"))
            total.update(saved["total"])
            articles.update(saved["articles"])
            continue
        part_total, part_articles = Counter(), Counter()
        with bz2.open(path, "rb") as f:
            for _, elem in ET.iterparse(f):
                if not elem.tag.endswith("}page"):
                    continue
                ns = elem.find("./{*}ns")
                text = elem.find("./{*}revision/{*}text")
                redirect = elem.find("./{*}redirect")
                if ns is not None and ns.text == "0" and redirect is None and text is not None and text.text:
                    seen = set()
                    for match in pattern.finditer(normalize(plain(text.text), language)):
                        word = match.group(0)
                        if valid(word, language):
                            part_total[word] += 1
                            seen.add(word)
                    for word in seen:
                        part_articles[word] += 1
                elem.clear()
        cache.write_text(json.dumps({"total": part_total, "articles": part_articles}, ensure_ascii=False), encoding="utf-8")
        total.update(part_total)
        articles.update(part_articles)
    return total, articles


def bare(word: str, language: dict) -> str:
    """The word as the keyboard folds it: no non-joiner or tanwin, each folded letter as its bare one."""
    folds = language["folds"]
    return "".join(folds.get(c, c) for c in word if c not in (ZWNJ, FATHATAN))


def main(argv: list[str]) -> None:
    if len(argv) < 2 or argv[0] not in LANGUAGES:
        raise SystemExit(__doc__)
    code = argv[0]
    language = LANGUAGES[code]
    pattern = word_pattern(language)
    hashes = digests(RULES.read_text(encoding="utf-8"))
    swears = {normalize(w, language) for w in language["swears"].split()}

    subtitles = subtitle_counts(Path(argv[1]), language, pattern)
    source = FREQUENCY_WORDS.format(code=code)
    counts: dict[str, float] = dict(subtitles)
    wiki_total: Counter = Counter()
    wiki_articles: Counter = Counter()
    if len(argv) > 2:
        wiki_total, wiki_articles = wikipedia_counts([Path(p) for p in argv[2:]], language, pattern)
        kept = {w: n for w, n in wiki_total.items() if wiki_articles[w] >= ARTICLES_FLOOR}
        scale = sum(subtitles.values()) / max(1, sum(kept.values())) * WIKI_WEIGHT
        for word, n in kept.items():
            counts[word] = counts.get(word, 0) + n * scale
        source += f", with {language['name']} Wikipedia ({', '.join(Path(p).name for p in argv[2:])}), CC BY-SA 4.0"

    clean = {w: n for w, n in counts.items() if not is_slur(w, hashes) and not any(is_slur(p, hashes) for p in w.split(ZWNJ))}
    dropped = len(counts) - len(clean)

    # Spellings the keyboard folds together: the weak ones of a group are recognized, not suggested.
    groups: dict[str, list[str]] = defaultdict(list)
    for word in clean:
        groups[bare(word, language)].append(word)
    not_offered: set[str] = set()
    for members in groups.values():
        if len(members) < 2:
            continue
        top = max(clean[w] for w in members)
        for word in members:
            if word == bare(word, language) and clean[word] * 4 < top:
                not_offered.add(word)
        # Joined and with the non-joiner: the non-joiner spelling is the standard one where Wikipedia uses it as often.
        joined = [w for w in members if ZWNJ not in w]
        for apart in (w for w in members if ZWNJ in w):
            for word in joined:
                if word.replace(FATHATAN, "") == apart.replace(ZWNJ, "").replace(FATHATAN, "") and \
                        wiki_articles[apart] >= ARTICLES_FLOOR and wiki_total[apart] >= wiki_total[word]:
                    not_offered.add(word)
                    not_offered.discard(apart)

    ranked = sorted(clean.items(), key=lambda item: (-item[1], item[0]))
    suggestions = [(w, n) for w, n in ranked if w not in swears and w not in not_offered and n >= 1][:WORDS]
    profanity = [(w, n) for w, n in ranked if w in swears]
    known = sorted(w for w, n in clean.items() if n >= KNOWN_FLOOR or wiki_articles[w] >= ARTICLES_FLOOR + 2)
    note = "Experimental: not read by a native speaker; slurs removed by digest"

    def write(name: str, header: str, lines: list[str]) -> None:
        (ASSETS / name).write_text(f"# {header}\n" + "".join(line + "\n" for line in lines), encoding="utf-8")

    name = language["name"]
    write(f"words_{code}.txt", f"{name} word frequencies derived from {source}. {note}, swear words in words_{code}_profanity.txt.",
          [f"{w}\t{max(1, round(n))}" for w, n in suggestions])
    write(f"words_{code}_profanity.txt", f"Common {name} swear words, counts from {source}. Slurs are deliberately excluded.",
          [f"{w}\t{max(1, round(n))}" for w, n in profanity])
    write(f"words_known_{code}.txt", f"Recognized {name} words (not suggestions) from {source}. {note}.", known)
    with_joiner = sum(1 for w, _ in suggestions if ZWNJ in w)
    print(f"{len(suggestions)} suggestions ({with_joiner} with the non-joiner), {len(profanity)} swear words, "
          f"{len(known)} known; {dropped} slurs dropped; {len(not_offered)} spellings recognized but not offered")


if __name__ == "__main__":
    main(sys.argv[1:])
