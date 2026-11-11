#!/usr/bin/env python3
"""Word lists for the keyboard's Welsh, Irish, Scottish Gaelic, Galician, Māori, Hawaiian and Navajo, from openly licensed text, with no Galactus build.

These languages have no subtitle counts in FrequencyWords, so each comes from the best open source that has real
running text (word forms, not lemmas: Irish mutates its words, so "bhí" and "mbeidh" must be in the list, not just "bí"):

    cy, ga, gd, gl, mi, nv  a Wikipedia ``pages-articles`` dump (``<code>wiki-<date>-pages-articles.xml.bz2``), CC BY-SA 4.0
    haw         dohliam/hawaiian-corpus ``data/freqlist_haw.txt`` (10.7 million words from Ulukau, modern and
                non-scriptural text), CC0

and this writes the three lists the keyboard reads, as ``model/hebrew_lists.py`` does for Hebrew:

    words_<code>.txt            suggestions, ``word<TAB>count``, the 20,000 commonest, without slurs or swear words
    words_<code>_profanity.txt  the swear words taken out of it, offered only with Suggest swear words
    words_known_<code>.txt      recognized words, without slurs

A word is the language's own letters only, starting with a letter and possibly holding an apostrophe or hyphen
(Welsh "i'r", Irish "t-uisce"), the way the keyboard reads words. Letters a language borrows only in loanwords are
left out (k, q, v, x and z in Welsh; j, k, q, v, w, x, y and z in Irish; Māori and Hawaiian keep their own short
alphabets), which also keeps out most of the English that Wikipedia text carries. From Wikipedia, a word counts only
once it appears in at least three articles, and it is offered as a suggestion only when it is written in lowercase at
least half the time, so names are recognized but not suggested. In Māori and Hawaiian, a spelling of five letters
or more without its macrons or ʻokina ("kamaaina") is recognized but not suggested when the marked spelling
("kamaʻāina") is in the text too. Every word is lowercased (the keyboard compares in
lowercase), so Irish "nÉirinn" is listed as "néirinn".

Template, table, file and Wikidata-list markup (with any SPARQL inside it) is stripped before counting, and a few
template words that still get through ("align", "thumb", "nbsp") are recognized but never offered, and so are the
200 commonest words of the keyboard's English list, except those that are also words of the language (Welsh "man",
Irish "go", Māori "take").
Navajo Wikipedia is mostly short articles written from a few templates (a species, a town, an aircraft), so the same
sentence recurs thousands of times with only a name and a number changed. For Navajo a line counts once however often
it recurs: two lines are the same when they match after their numbers, capitalized words and quoted names are taken out. Its glottal
stop is U+02BC, a letter, which the wiki writes consistently; the few apostrophes written for it become it. The high
nasal vowels are a nasal vowel and a combining acute (U+0301), which stays inside the word.

Slurs are dropped by their SHA-256 in ``SuggestionWordRules`` (every language's list), checking each hyphenated part.
Māori Wikipedia is small, so its suggestion list is about 1,700 words; a second Māori corpus would help. Experimental: no native speaker
has read the lists.

Usage, from the repository root:

    python3 model/small_language_lists.py cy path/to/cywiki-20261101-pages-articles.xml.bz2
    python3 model/small_language_lists.py haw path/to/freqlist_haw.txt
"""

from __future__ import annotations

import bz2
import hashlib
import re
import sys
import unicodedata
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

RULES = Path("engine/src/commonMain/kotlin/dev/localtype/keyboard/SuggestionWordRules.kt")
ASSETS = Path("app/src/main/assets")
WORDS = 20_000
# Wikipedia text in these languages quotes English titles and names, so the commonest English words are recognized but
# not suggested, except the ones each language lists as its own (``english_kept``). Only for the Wikipedia sources.
ENGLISH_STOP = 200
ARTICLES_FLOOR = 3

LANGUAGES = {
    "cy": dict(
        name="Welsh",
        letters="abcdefghijlmnoprstuwyâêîôûŵŷáéíóúẃýàèìòùẁỳäëïöüẅÿ",
        known_floor=3,
        # Strong swear words only; "diawl" (devil) and "uffern" (hell) are ordinary words here.
        swears="cachu cont ffwc ffycin ffwcio ffwcin coc twat bastard bastad",
        # Template and table words, and English, that survive the cleanup in Welsh letters.
        not_offered="show if then fwd noicon osd label item of sort list columns thumb align right left center ref nbsp www com org net ed pp mm",
        # Real Welsh words among the commonest English ones ("man" place, "well" better, "sir" county, "am" for).
        english_kept="a i o at am is on to do go man well sir help had",
    ),
    "ga": dict(
        name="Irish",
        letters="abcdefghilmnoprstuáéíóú",
        known_floor=3,
        swears="cac focáil focáilte fuc striapach raicleach",
        not_offered="align right left center of the nbsp www com org net ed pp mm",
        english_kept="a an as i in is do go long",
    ),
    "gd": dict(
        name="Scottish Gaelic",
        # The eighteen letters of the Gaelic alphabet, with the grave vowels and the acutes older spelling used.
        letters="abcdefghilmnoprstuàèìòùáéó",
        known_floor=3,
        swears="cac siùrsach galla",
        # Template words, and the consonant pairs left when an elided word loses its apostrophe ("th'" for "tha").
        not_offered="align right left center thumb of the nbsp www com org net ed pp mm th mh bh ch dh",
        # Real Gaelic words among the commonest English ones ("an" the, "do" to, "is" and, "air" on, "no" or, "so" this).
        english_kept="a an as am i is do e air mi sin no can so",
    ),
    "gl": dict(
        name="Galician",
        # Galician's own letters: j, k, w and y come only in loanwords.
        letters="abcdefghilmnñopqrstuvxzáéíóúïü",
        known_floor=5,
        swears="carallo merda foder fodido fodida puta putas cona cabrón cabróns",
        not_offered="align right left center thumb ref nbsp www com org net ed pp mm",
        # Real Galician words among the commonest English ones ("do" of the, "no" in the, "son" are, "can" dog, "ten" has).
        english_kept="a e o as do no me son can ten come des man non mal home has so",
    ),
    "mi": dict(name="Māori", letters="aehikmngoprtuwāēīōū", known_floor=2, swears="", prefer_marked=True,
               not_offered="an it nbsp www com org net ed pp mm water earth without woman men top",
               english_kept="a e he i o to me take here one"),
    "nv": dict(
        name="Navajo",
        # The Navajo alphabet (no f, p, q, r, u or v), its marked vowels, ł, ń, the glottal stop and the combining acute.
        letters="abcdeghijklmnostwxyzáéíóąęįǫłńʼ\u0301",
        known_floor=2,
        swears="",
        glottal="ʼ",
        dedupe=True,
        not_offered="align right left center thumb ref nbsp www com org net ed pp mm",
        english_kept="",
    ),
    "haw": dict(
        name="Hawaiian",
        letters="aeiouhklmnpwʻāēīōū",
        known_floor=2,
        swears="",
        prefer_marked=True,
        source="dohliam/hawaiian-corpus, data/freqlist_haw.txt (Ulukau, the Hawaiian Electronic Library), CC0",
    ),
}

# A long vowel's bare form, and the ʻokina, which older or hurried text leaves out.
MARKS = {"ā": "a", "ē": "e", "ī": "i", "ō": "o", "ū": "u", "ʻ": ""}
# Below this many letters a bare and a marked spelling are usually two words (Hawaiian "ka" and "kā", "o" and "ʻo").
MARKED_MIN_LETTERS = 5


def bare(word: str) -> str:
    return "".join(MARKS.get(c, c) for c in word)


def unmarked_twins(counts: Counter) -> set[str]:
    """Bare spellings of five letters or more whose marked spelling is in the text too ("kamaaina", "kamaʻāina")."""
    marked = Counter()
    for word, n in counts.items():
        if bare(word) != word:
            marked[bare(word)] += n
    return {w for w, n in counts.items()
            if bare(w) == w and len(w) >= MARKED_MIN_LETTERS and marked[w] * 20 >= n}


# ʻokina look-alikes written for it in some text.
OKINA_LOOKALIKES = "'‘’`ʼ"


def digests(source: str) -> set[str]:
    out: set[str] = set()
    for block in re.finditer(r'EXCLUDED_HASHES\w*: Set<String> = """(.*?)"""', source, re.S):
        out |= set(block.group(1).split())
    if not out:
        raise SystemExit(f"no digests found in {RULES}")
    return out


def is_slur(word: str, hashes: set[str]) -> bool:
    """The whole word or any part between hyphens, as ``BundledWordListsTest`` checks."""
    parts = {word, *word.split("-")}
    return any(hashlib.sha256(p.encode()).hexdigest() in hashes for p in parts if p)


def word_pattern(letters: str) -> re.Pattern[str]:
    """A whole token of the language's letters: no match inside a longer run of other letters ("show" in "showing")."""
    cls = re.escape(letters)
    return re.compile(f"(?<![^\\W\\d_])[{cls}](?:[{cls}]|['-](?=[{cls}]))*(?![^\\W\\d_])")


# Wikitext to plain text, roughly: enough for word counts, not for reading.
COMMENT = re.compile(r"<!--.*?-->", re.S)
REF = re.compile(r"<ref[^>/]*/>|<ref[^>]*>.*?</ref>", re.S)
TAG = re.compile(r"<[^>]+>")
TEMPLATE = re.compile(r"\{\{[^{}]*\}\}")
# Wikidata lists are generated tables, often with a SPARQL query inside the opening template.
WIKIDATA_LIST = re.compile(r"\{\{\s*Wikidata list.*?\{\{\s*Wikidata list end\s*\}\}", re.S | re.I)
SPARQL = re.compile(r"sparql\s*=.*?(?=\n\s*\||\}\})", re.S | re.I)
ATTRIBUTE = re.compile(r'\b[\w-]+\s*=\s*"[^"\n]*"')
TABLE_LINE = re.compile(r"^\s*(?:\{\||\|\}|\|-|!).*$", re.M)
FILE_LINE = re.compile(r"^\s*\[\[(?:File|Image|Delwedd|Ffeil|Comhad|Íomhá|Whakaahua)\s*:.*$", re.M | re.I)
TABLE = re.compile(r"\{\|.*?\|\}", re.S)
NAMESPACED = re.compile(r"\[\[[^\]|]*:[^\]]*\]\]")
LINK = re.compile(r"\[\[(?:[^\]|]*\|)?([^\]]*)\]\]")
EXTERNAL = re.compile(r"\[https?://\S+\s*([^\]]*)\]")
URL = re.compile(r"https?://\S+")
GALLERY = re.compile(r"<gallery[^>]*>.*?</gallery>", re.S | re.I)
MEDIA = re.compile(r"\S+\.(?:jpe?g|png|svg|gif|tiff?|webm|ogg|ogv|pdf)\b", re.I)
ENTITY = re.compile(r"&(?:[a-z]+|#\d+|#x[0-9a-f]+);", re.I)
DOMAIN = re.compile(r"\b(?:www\.)?[\w-]+(?:\.[\w-]+)*\.(?:com|org|net|edu|gov|uk|ie|cymru|wales|nz|info)\b\S*", re.I)


def plain(text: str) -> str:
    text = COMMENT.sub(" ", text)
    text = GALLERY.sub(" ", text)
    text = REF.sub(" ", text)
    text = WIKIDATA_LIST.sub(" ", text)
    text = SPARQL.sub(" ", text)
    text = FILE_LINE.sub(" ", text)
    for _ in range(8):
        text, n = TEMPLATE.subn(" ", text)
        if not n:
            break
    text = TABLE.sub(" ", text)
    text = TABLE_LINE.sub(" ", text)
    text = ATTRIBUTE.sub(" ", text)
    text = NAMESPACED.sub(" ", text)
    text = LINK.sub(r"\1", text)
    text = EXTERNAL.sub(r"\1", text)
    text = URL.sub(" ", text)
    text = DOMAIN.sub(" ", text)
    text = MEDIA.sub(" ", text)
    text = ENTITY.sub(" ", text)
    text = TAG.sub(" ", text)
    return text.replace("'''", " ").replace("''", " ")


def english_common() -> set[str]:
    """The commonest English words, from the keyboard's own English list."""
    lines = (ASSETS / "words_en.txt").read_text(encoding="utf-8").splitlines()
    return set([line.split("\t", 1)[0] for line in lines if line and not line.startswith("#")][:ENGLISH_STOP])


CAPITALIZED = re.compile(r"\b[^\W\d_a-zà-ÿąęįǫłń]\w*")
NUMBER = re.compile(r"\d[\d.,]*")
# A quoted name, such as the Latin name in a species article: lowercase words that still change line to line.
QUOTED = re.compile(r'"[^"\n]{0,80}"|“[^”\n]{0,80}”')


def new_lines(body: str, seen: set[str]) -> str:
    """The lines of [body] not yet seen with other names and numbers in them; a short line is always kept."""
    kept = []
    for line in body.split("\n"):
        if len(line) > 30:
            skeleton = " ".join(CAPITALIZED.sub("", NUMBER.sub("0", QUOTED.sub("", line))).split())
            if skeleton in seen:
                continue
            seen.add(skeleton)
        kept.append(line)
    return "\n".join(kept)


def wikipedia_counts(path: Path, pattern: re.Pattern[str], spec: dict | None = None) -> tuple[Counter, Counter, Counter]:
    spec = spec or {}
    seen_lines: set[str] = set()
    total, lower, articles = Counter(), Counter(), Counter()
    with bz2.open(path, "rb") as f:
        for _, elem in ET.iterparse(f):
            if not elem.tag.endswith("}page"):
                continue
            ns = elem.find("./{*}ns")
            text = elem.find("./{*}revision/{*}text")
            redirect = elem.find("./{*}redirect")
            if ns is not None and ns.text == "0" and redirect is None and text is not None and text.text:
                body = unicodedata.normalize("NFC", plain(text.text)).replace("’", "'")
                if spec.get("glottal"):
                    for mark in "'`‘":
                        body = body.replace(mark, spec["glottal"])
                if spec.get("dedupe"):
                    body = new_lines(body, seen_lines)
                seen = set()
                for match in pattern.finditer(body.lower()):
                    word = match.group(0)
                    total[word] += 1
                    seen.add(word)
                # Lowercase share, from the text as written.
                for match in re.finditer(pattern.pattern, body, re.I):
                    raw = match.group(0)
                    if raw[:1].islower():
                        lower[raw.lower()] += 1
                for word in seen:
                    articles[word] += 1
            elem.clear()
    return total, lower, articles


def frequency_counts(path: Path, pattern: re.Pattern[str]) -> Counter:
    counts: Counter = Counter()
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) != 2:
            continue
        number, word = (parts if parts[0].isdigit() else parts[::-1])
        if not number.isdigit():
            continue
        word = unicodedata.normalize("NFC", word).lower()
        for mark in OKINA_LOOKALIKES:
            word = word.replace(mark, "ʻ")
        if pattern.fullmatch(word):
            counts[word] += int(number)
    return counts


def main(argv: list[str]) -> None:
    if len(argv) != 2 or argv[0] not in LANGUAGES:
        raise SystemExit(__doc__)
    code, source_path = argv[0], Path(argv[1])
    spec = LANGUAGES[code]
    pattern = word_pattern(spec["letters"])
    hashes = digests(RULES.read_text(encoding="utf-8"))
    swears = set(spec["swears"].split())
    if source_path.name.endswith(".xml.bz2"):
        total, lower, articles = wikipedia_counts(source_path, pattern, spec)
        dump = source_path.name.removesuffix("-pages-articles.xml.bz2")
        source = f"Wikipedia ({dump} pages-articles dump), CC BY-SA 4.0"
        # A word never starts with a combining mark or the glottal-stop letter's look-alike left at a quotation's edge.
        counts = Counter({w: n for w, n in total.items() if articles[w] >= ARTICLES_FLOOR and unicodedata.category(w[0]) != "Mn"})
        offerable = {w for w, n in counts.items() if lower[w] * 2 >= n}
        english = english_common() - set(spec.get("english_kept", "").split())
        print(f"{code}: not offering English: {' '.join(sorted(english & offerable))}")
        offerable -= english
    else:
        counts = frequency_counts(source_path, pattern)
        source = spec["source"]
        offerable = set(counts)
    if spec.get("prefer_marked"):
        # Suggest the marked spelling, as the language is written now; the bare one stays recognized.
        offerable -= unmarked_twins(counts)
    ranked = sorted(counts.items(), key=lambda item: (-item[1], item[0]))
    clean = [(w, n) for w, n in ranked if not is_slur(w, hashes)]
    offerable -= set(spec.get("not_offered", "").split())
    suggestions = [(w, n) for w, n in clean if w not in swears and w in offerable][:WORDS]
    profanity = [(w, n) for w, n in clean if w in swears]
    known = sorted(w for w, n in clean if n >= spec["known_floor"])
    note = "Experimental: not read by a native speaker; slurs removed by digest"
    name = spec["name"]

    def write(file: str, header: str, lines: list[str]) -> None:
        (ASSETS / file).write_text(f"# {header}\n" + "".join(line + "\n" for line in lines), encoding="utf-8")

    write(f"words_{code}.txt", f"{name} word frequencies derived from {source}. {note}, swear words in "
          f"words_{code}_profanity.txt. Written by model/small_language_lists.py.", [f"{w}\t{n}" for w, n in suggestions])
    write(f"words_{code}_profanity.txt", f"Common {name} swear words, counts from {source}. Slurs are deliberately "
          "excluded.", [f"{w}\t{n}" for w, n in profanity])
    write(f"words_known_{code}.txt", f"Recognized {name} words (not suggestions) from {source}, count at least "
          f"{spec['known_floor']}. {note}.", known)
    print(f"{code}: {len(suggestions)} suggestions, {len(profanity)} swear words, {len(known)} known; "
          f"{len(ranked) - len(clean)} slurs dropped")


if __name__ == "__main__":
    main(sys.argv[1:])
