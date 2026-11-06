#!/usr/bin/env python3
"""Build candidate word data for a language other than English, offline (languages route A).

Route A keeps one engine: other languages run on LocalType's own dictionary, corrector and
n-gram ranker with their data dropped in (docs/languages-route-a-2026-10-17.md). This builds
that data for one language from downloads already on Galactus, and bundles nothing:

    words_<lang>.txt          candidate suggestion list, ``word<TAB>count`` like words_en.txt
    words_known_<lang>.txt    candidate recognized words (not suggestions), like words_known.txt
    folds.tsv                 every spelling group that differs only by accents, with what was kept
    corpus/                   the corpus sentences as the prior counts them: train, dev and test
                              (one line in ten held out, SPLIT_RULE), with per-line sources
    corpus_prior_<lang>.bin   candidate corpus prior, model/prior.py's format and smoothing
    report.json               sizes, coverage, hashes and licences

Everything goes to ``<root>/languages/<lang>/<name>/``, which must not exist. The inputs are
read in place from ``<root>/downloads/languages/<lang>/``; the corpus archives are never
unpacked. Nothing is fetched.

The word list follows the English one: the top entries of FrequencyWords (OpenSubtitles 2018,
CC BY-SA 4.0), filtered. A bare spelling is dropped from both lists when a spelling that
differs only by accents is ``--dominance`` times as common ("tambien" beside "también"), so
the corrector can repair it. Subtitles hold misspellings too, so below that ratio the edited
corpus text decides (``dominated``): "miercoles" goes, "papa" (potato, pope) stays. Pairs where
both spellings are common ("esta", "está") keep both. The recognized-words set is the full list above ``--known-floor``, filtered the same
way, standing in for a lexicon (Spanish Hunspell lexicons mix licences).

The prior counts the Leipzig Corpora Collection sentences (CC BY) with the keyboard's context
rule, after deleting ¿ and ¡: the Spanish profile's device rule ignores both marks when it
looks for the previous word or a sentence start, so "Hola. ¿Qué tal?" gives "qué" the
sentence start, as the keyboard will. Followers are restricted to the candidate word list;
the language's one-letter words ("y", "o", "a", "e", "u") may follow.

Nothing here reviews the lists for slurs. A native reader does that before anything ships,
and BundledWordListsTest then gets this language's digests (the English ones misfire on
ordinary words in other languages).

Usage, from the repository root with Galactus mounted:

    python -m model.language_build es --name es-candidate-1
    python -m model.language_build es --name es-words-only --no-prior
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import sys
import tarfile
import time
import unicodedata
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator, Sequence

import numpy as np

from model import prior as pr
from model import tokenize as tk

DATA_ROOT = Path(os.environ.get("LOCALTYPE_MODEL_DATA", str(Path.home() / ".local/share/localtype-model")))


@dataclass(frozen=True)
class Corpus:
    archive: str  # file name in the downloads folder
    member: str  # the sentences file inside it
    licence: str
    source: str


@dataclass(frozen=True)
class Language:
    code: str
    name: str
    letters: frozenset[str]  # every letter a word in the lists may hold, lowercase
    one_letter: tuple[str, ...]  # one-letter words the keyboard may offer
    ignored_marks: str  # marks the device context rule skips; deleted before counting
    folds: dict[str, str]  # accented letter -> its bare letter, for spelling groups
    frequency_file: str
    frequency_source: str
    corpora: tuple[Corpus, ...]


LEIPZIG = "Leipzig Corpora Collection (https://wortschatz.uni-leipzig.de/en/download), CC BY"
FREQUENCYWORDS = ("hermitdave/FrequencyWords, content/2018 (OpenSubtitles 2018) at commit "
                  "525f9b560de45753a5ea01069454e72e9aa541c6, CC BY-SA 4.0")

SPANISH = Language(
    code="es",
    name="Spanish",
    letters=frozenset("abcdefghijklmnopqrstuvwxyzáéíóúüñ"),
    one_letter=("a", "e", "o", "u", "y"),
    ignored_marks="¿¡",
    folds={"á": "a", "é": "e", "í": "i", "ó": "o", "ú": "u", "ü": "u", "ñ": "n"},
    frequency_file="es_full.txt",
    frequency_source=FREQUENCYWORDS,
    corpora=(
        Corpus("spa_web_2016_1M.tar.gz", "spa_web_2016_1M-sentences.txt", "CC BY", LEIPZIG + ", Spanish web 2016, 1M sentences"),
        Corpus("spa_news_2024_1M.tar.gz", "spa_news_2024_1M-sentences.txt", "CC BY", LEIPZIG + ", Spanish news 2024, 1M sentences"),
    ),
)

# Brazilian Portuguese first: its subtitles and a Brazilian news crawl beside the 2024 news, which mixes Brazil and
# Portugal. The keyboard follows Android's Portuguese layouts (plain QWERTY, ç first on a hold of c), so ç folds to c.
PORTUGUESE = Language(
    code="pt",
    name="Portuguese",
    letters=frozenset("abcdefghijklmnopqrstuvwxyzáàâãçéêíóôõú"),
    one_letter=("a", "e", "o", "é", "à"),
    ignored_marks="",
    folds={"á": "a", "à": "a", "â": "a", "ã": "a", "ç": "c", "é": "e", "ê": "e", "í": "i",
           "ó": "o", "ô": "o", "õ": "o", "ú": "u"},
    frequency_file="pt_br_full.txt",
    frequency_source=FREQUENCYWORDS.replace("content/2018", "content/2018/pt_br"),
    corpora=(
        Corpus("por_news_2024_1M.tar.gz", "por_news_2024_1M-sentences.txt", "CC BY", LEIPZIG + ", Portuguese news 2024, 1M sentences"),
        Corpus("por-br_newscrawl_2011_1M.tar.gz", "por-br_newscrawl_2011_1M-sentences.txt", "CC BY",
               LEIPZIG + ", Portuguese (Brazil) newscrawl 2011, 1M sentences"),
    ),
)

LANGUAGES = {SPANISH.code: SPANISH, PORTUGUESE.code: PORTUGUESE}

WORDS = 20_000  # the size of words_en.txt
KNOWN_FLOOR = 20
DOMINANCE = 20.0
CONTEXTS = pr.CONTEXTS
FOLLOWERS = pr.FOLLOWERS


# ------------------------------------------------------------------ words


def normalize(word: str) -> str:
    """The keyboard's normalization (apostrophe folded, lowercased), on NFC text."""
    return tk.normalize(unicodedata.normalize("NFC", word))


def fold(word: str, lang: Language) -> str:
    return "".join(lang.folds.get(c, c) for c in word)


def is_word(word: str, lang: Language) -> bool:
    """One keyboard word made only of the language's letters; one letter only if it is offered."""
    if not word or any(c not in lang.letters for c in word):
        return False
    if len(word) == 1:
        return word in lang.one_letter
    return pr.is_device_word(word)


def read_frequencies(path: Path, lang: Language) -> tuple[Counter, dict]:
    """FrequencyWords ``word count`` lines: counts of the normalized words that pass ``is_word``."""
    counts: Counter = Counter()
    lines = rejected = rejected_mass = total_mass = 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").rsplit(" ", 1)
            if len(parts) != 2 or not parts[1].isdigit():
                continue
            lines += 1
            n = int(parts[1])
            total_mass += n
            w = normalize(parts[0])
            if is_word(w, lang):
                counts[w] += n
            else:
                rejected += 1
                rejected_mass += n
    return counts, {"lines": lines, "kept_words": len(counts), "rejected_lines": rejected,
                    "rejected_share_of_count": round(rejected_mass / total_mass, 6) if total_mass else 0.0}


def spelling_groups(counts: Counter, lang: Language) -> dict[str, list[str]]:
    """Spellings that differ only by accents, by their bare form; only groups of two or more."""
    groups: dict[str, list[str]] = {}
    for w in counts:
        groups.setdefault(fold(w, lang), []).append(w)
    return {k: sorted(v, key=lambda w: (-counts[w], w)) for k, v in groups.items() if len(v) > 1}


def dominated(counts: Counter, lang: Language, dominance: float, edited: Counter | None = None,
              min_dominance: float = 5.0, edited_dominance: float = 50.0, edited_evidence: int = 50) -> tuple[set[str], list[dict]]:
    """Bare spellings to drop because an accented spelling dominates them.

    A bare spelling (no accented letter) goes when an accented spelling is ``dominance`` times as
    common in ``counts`` (subtitles, where people also misspell). Between ``min_dominance`` and
    ``dominance`` subtitles can't tell "miercoles" (a misspelling) from "papa" (potato, pope), so
    edited text decides: the bare form also goes when ``edited`` (news and web sentences) has the
    accented spelling at least ``edited_evidence`` times and ``edited_dominance`` times as often
    (with one added to the bare count). Edited text never decides a pair that differs only in its
    last letter: "amenazo" (I threaten) beside "amenazó" (he threatened) is two verb forms, and
    news, written in the third person and the past, would always vote for the second. Two accented
    spellings of one bare form are both kept, and so is a bare form that is the commoner spelling.
    """
    drop: set[str] = set()
    rows = []
    for bare, spellings in spelling_groups(counts, lang).items():
        top = spellings[0]
        ratio = counts[top] / counts[bare] if bare in counts and top != bare else None
        final_only = ratio is not None and len(top) == len(bare) and top[:-1] == bare[:-1]
        edited_ratio = None
        if ratio is not None and edited is not None and edited[top] >= edited_evidence:
            edited_ratio = edited[top] / (edited[bare] + 1)
        dropped = ratio is not None and (
            ratio >= dominance or (ratio >= min_dominance and not final_only
                                   and edited_ratio is not None and edited_ratio >= edited_dominance))
        if dropped:
            drop.add(bare)
        row = {"bare": bare, "spellings": [(w, counts[w]) for w in spellings],
               "ratio": round(ratio, 2) if ratio is not None else None, "bare_dropped": bool(dropped),
               "last_letter_only": bool(final_only)}
        if edited is not None and ratio is not None:
            row["edited"] = [(top, edited[top]), (bare, edited[bare])]
            row["edited_ratio"] = round(edited_ratio, 2) if edited_ratio is not None else None
        rows.append(row)
    rows.sort(key=lambda r: -sum(n for _, n in r["spellings"]))
    return drop, rows


def write_word_list(path: Path, words: Sequence[tuple[str, int]], header: str) -> None:
    with open(path, "w", encoding="utf-8") as f:
        f.write(f"# {header}\n")
        for w, n in words:
            f.write(f"{w}\t{n}\n")


# ------------------------------------------------------------------ corpus


def strip_marks(line: str, lang: Language) -> str:
    return line.translate({ord(c): None for c in lang.ignored_marks}) if lang.ignored_marks else line


def corpus_lines(downloads: Path, corpus: Corpus) -> Iterator[str]:
    """The sentences of one Leipzig archive (``id<TAB>sentence``), read from the archive in place."""
    with tarfile.open(downloads / corpus.archive, "r:gz") as tar:
        for member in tar:
            if member.isfile() and (member.name == corpus.member or member.name.endswith("/" + corpus.member)):
                f = tar.extractfile(member)
                assert f is not None
                for raw in io.TextIOWrapper(f, encoding="utf-8"):
                    parts = raw.rstrip("\n").split("\t", 1)
                    if len(parts) == 2 and parts[1].strip():
                        yield parts[1]
                return
    raise SystemExit(f"{corpus.member} is not in {corpus.archive}")


SPLIT_RULE = ("by position across the sources in order: line n goes to dev when n % 20 == 0, to test when "
              "n % 20 == 1, otherwise to train; only train is counted, and test stays unread until a bar is agreed")


def split_of(n: int) -> str:
    return "dev" if n % 20 == 0 else "test" if n % 20 == 1 else "train"


def write_splits(corpus_dir: Path, lines: Iterable[tuple[str, str]], lang: Language) -> dict[str, int]:
    """``train``, ``dev`` and ``test`` text with line-parallel ``.sources.txt``, as model/corpus.py lays a corpus out."""
    files = {s: (open(corpus_dir / f"{s}.txt", "w", encoding="utf-8"),
                 open(corpus_dir / f"{s}.sources.txt", "w", encoding="utf-8")) for s in ("train", "dev", "test")}
    sizes = dict.fromkeys(files, 0)
    try:
        for n, (source, line) in enumerate(lines):
            s = split_of(n)
            text, src = files[s]
            text.write(strip_marks(unicodedata.normalize("NFC", line).replace("\n", " "), lang) + "\n")
            src.write(source + "\n")
            sizes[s] += 1
    finally:
        for text, src in files.values():
            text.close()
            src.close()
    return sizes


def archive_members(path: Path) -> list[dict]:
    with tarfile.open(path, "r:gz") as tar:
        return [{"name": m.name, "bytes": m.size} for m in tar if m.isfile()]


def count_tokens(lines: Iterable[str], lang: Language) -> tuple[Counter, int]:
    """Counts of the corpus's word tokens that pass ``is_word``, and how many tokens did not."""
    counts: Counter = Counter()
    other = 0
    for line in lines:
        for _, _, w in pr.word_spans(unicodedata.normalize("NFC", line)):
            if is_word(w, lang):
                counts[w] += 1
            else:
                other += 1
    return counts, other


def coverage(counts: Counter, other: int, lists: dict[str, set[str]], top_missing: int = 60) -> dict:
    """Share of the corpus's word tokens each list holds, and the commonest tokens none holds."""
    tokens = sum(counts.values())
    missing = Counter({w: n for w, n in counts.items() if not any(w in words for words in lists.values())})
    return {
        "word_tokens": tokens,
        "other_tokens": other,
        "share_held": {name: round(sum(n for w, n in counts.items() if w in words) / tokens, 4) if tokens else 0.0
                       for name, words in lists.items()},
        "commonest_missing": missing.most_common(top_missing),
    }


# ------------------------------------------------------------------ build


def sha256_file(path: Path) -> str:
    return pr.sha256_file(path)


def build(args: argparse.Namespace) -> dict:
    lang = LANGUAGES[args.lang]
    downloads = Path(args.downloads)
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=False)
    t0 = time.time()
    inputs = {}

    # Edited text first, when its archives are here: it settles the accent groups subtitles can't.
    present = [c for c in lang.corpora if (downloads / c.archive).exists()]
    absent = [c.archive for c in lang.corpora if c not in present]
    corpus_counts: dict[str, tuple[Counter, int]] = {}
    for c in present:
        p = downloads / c.archive
        inputs[c.archive] = {"sha256": sha256_file(p), "bytes": p.stat().st_size, "source": c.source,
                             "licence": c.licence, "members": archive_members(p)}
        corpus_counts[c.archive] = count_tokens(corpus_lines(downloads, c), lang)
        print(f"{c.archive}: {sum(corpus_counts[c.archive][0].values()):,} word tokens", file=sys.stderr)
    edited: Counter | None = None
    if corpus_counts:
        edited = Counter()
        for cc, _ in corpus_counts.values():
            edited.update(cc)

    # Words
    freq_path = downloads / lang.frequency_file
    inputs[lang.frequency_file] = {"sha256": sha256_file(freq_path), "bytes": freq_path.stat().st_size,
                                   "source": lang.frequency_source}
    counts, freq_info = read_frequencies(freq_path, lang)
    drop, groups = dominated(counts, lang, args.dominance, edited, args.min_dominance, args.edited_dominance)
    ranked = sorted(((w, n) for w, n in counts.items() if w not in drop), key=lambda e: (-e[1], e[0]))
    suggestions = ranked[:args.words]
    known = [(w, n) for w, n in ranked if n >= args.known_floor]
    total_mass = sum(n for _, n in ranked)
    words_path = out / f"words_{lang.code}.txt"
    known_path = out / f"words_known_{lang.code}.txt"
    write_word_list(words_path, suggestions,
                    f"Candidate {lang.name} word frequencies derived from {lang.frequency_source}. "
                    "Not reviewed for slurs; not bundled.")
    with open(known_path, "w", encoding="utf-8") as f:
        f.write(f"# Candidate recognized {lang.name} words (not suggestions): {lang.frequency_source}, "
                f"count at least {args.known_floor}. Not reviewed; not bundled.\n")
        for w in sorted(w for w, _ in known):
            f.write(w + "\n")
    with open(out / "folds.tsv", "w", encoding="utf-8") as f:
        f.write("bare\tratio\tedited_ratio\tbare_dropped\tlast_letter_only\tspellings (count)\tedited (count)\n")
        for r in groups:
            f.write(f"{r['bare']}\t{r['ratio']}\t{r.get('edited_ratio')}\t{int(r['bare_dropped'])}\t{int(r['last_letter_only'])}\t"
                    + " ".join(f"{w}({n})" for w, n in r["spellings"]) + "\t"
                    + " ".join(f"{w}({n})" for w, n in r.get("edited", [])) + "\n")
    words_info = {
        "frequency_list": freq_info,
        "suggestions": {"file": words_path.name, "words": len(suggestions), "sha256": sha256_file(words_path),
                        "lowest_count": suggestions[-1][1] if suggestions else None,
                        "share_of_list_count": round(sum(n for _, n in suggestions) / total_mass, 4) if total_mass else 0.0,
                        "one_letter": [w for w, _ in suggestions if len(w) == 1],
                        "with_accent": sum(1 for w, _ in suggestions if fold(w, lang) != w)},
        "known": {"file": known_path.name, "words": len(known), "floor": args.known_floor, "sha256": sha256_file(known_path)},
        # How much typing each letter beyond a-z carries: the share of all counted words that hold it.
        "share_of_count_holding": {c: round(sum(n for w, n in ranked if c in w) / total_mass, 4) if total_mass else 0.0
                                   for c in sorted(lang.letters) if not c.isascii()},
        "accent_groups": {"groups": len(groups), "bare_dropped": len(drop), "dominance": args.dominance,
                          "min_dominance": args.min_dominance, "edited_dominance": args.edited_dominance,
                          "edited_text_used": edited is not None,
                          "both_kept_examples": [r for r in groups if not r["bare_dropped"] and r["ratio"] is not None][:40],
                          "dropped_by_edited_text": [r for r in groups if r["bare_dropped"] and r["ratio"] < args.dominance][:40],
                          "dropped_examples": [r for r in groups if r["bare_dropped"]][:25]},
    }
    print(f"words: {len(suggestions):,} suggestions, {len(known):,} known, {len(drop):,} bare spellings dropped",
          file=sys.stderr)

    report: dict = {"language": lang.code, "built": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "inputs": inputs,
                    "words": words_info, "nothing_bundled": True,
                    "not_done": ["slur and swear review by a native reader", "BundledWordListsTest digests for this language"]}

    if absent:
        report["corpora_missing"] = absent
    if present:
        lists = {"suggestions": {w for w, _ in suggestions}, "known": {w for w, _ in known}}
        report["coverage"] = {name: coverage(cc, other, lists) for name, (cc, other) in corpus_counts.items()}

    if present and not args.no_prior:
        corpus_dir = out / "corpus"
        corpus_dir.mkdir()
        split_lines = write_splits(corpus_dir, ((c.archive.split(".")[0], line) for c in present
                                                for line in corpus_lines(downloads, c)), lang)
        manifest = {"language": lang.code, "sources": [c.archive for c in present],
                    "inputs": {c.archive: inputs[c.archive]["sha256"] for c in present},
                    "marks_deleted": lang.ignored_marks, "lines": split_lines,
                    "split_rule": SPLIT_RULE}
        (corpus_dir / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        pairs = pr.count_pairs(corpus_dir, None, args.workers, 1)
        smoothed = pr.kneser_ney(pairs)
        dictionary = {w for w, _ in suggestions}
        offered = set(lang.one_letter)
        chosen = pr.select(pairs, smoothed, dictionary, args.contexts, args.followers,
                           can_offer=lambda w: len(w) >= 2 or w in offered)
        metadata = {
            "format": "LocalType corpus prior",
            "version": pr.VERSION,
            "language": lang.code,
            "candidate": True,
            "corpus": {"manifest_sha256": sha256_file(corpus_dir / "manifest.json"),
                       "train_sha256": sha256_file(corpus_dir / "train.txt"), "lines": pairs.lines, "limit": None},
            "context_rule": ("AdaptiveNgramModel's rule after deleting " + lang.ignored_marks
                             + ", which the language profile's device rule skips") if lang.ignored_marks
                            else "AdaptiveNgramModel's rule",
            "human_sources": [],
            "human_weight": 1,
            "smoothing": "interpolated modified Kneser-Ney bigram (Chen and Goodman 1998)",
            "discounts": [round(d, 6) for d in smoothed.discounts],
            "log": "natural",
            "contexts": args.contexts,
            "followers_per_context": args.followers,
            "dictionary": {"file": words_path.name, "sha256": words_info["suggestions"]["sha256"], "words": len(dictionary),
                           "swear_words_excluded": 0},
            "licence": "Derived from the Leipzig Corpora Collection " + lang.name + " downloads (CC BY); "
                       "word list from FrequencyWords (CC BY-SA 4.0)",
        }
        prior_path = out / f"corpus_prior_{lang.code}.bin"
        summary = pr.write_asset(prior_path, chosen, metadata)
        events = int(pairs.raw.sum())
        # Share of counted (context, word) events whose pair the asset holds.
        index = {w: i for i, w in enumerate(chosen.words)}
        asset_id = np.fromiter((index.get(w, -1) for w in pairs.words), dtype=np.int64, count=len(pairs.words))
        ci, wi = asset_id[pairs.ctx], asset_id[pairs.word]
        kept = np.fromiter((c << 16 | w for c, lst in chosen.contexts for w, _ in lst), dtype=np.int64)
        inside = (ci >= 0) & (wi >= 0) & np.isin(ci << 16 | wi, kept)
        held = int(pairs.raw[inside].sum())
        report["prior"] = {"file": prior_path.name, "sha256": sha256_file(prior_path), **summary,
                           "corpus_lines": pairs.lines, "pair_types": int(len(pairs.keys)), "events_with_context": events,
                           "share_of_events_in_asset": round(held / events, 4) if events else 0.0,
                           "discounts": metadata["discounts"]}
        print(f"prior: {summary['bytes']:,} bytes, {summary['contexts']:,} contexts, {summary['followers']:,} followers",
              file=sys.stderr)

    report["seconds"] = round(time.time() - t0, 1)
    (out / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return report


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("lang", choices=sorted(LANGUAGES))
    p.add_argument("--name", required=True, help="report directory under <root>/languages/<lang>/ (must not exist)")
    p.add_argument("--downloads", help="input folder (default <root>/downloads/languages/<lang>)")
    p.add_argument("--words", type=int, default=WORDS, help="suggestion list size")
    p.add_argument("--known-floor", type=int, default=KNOWN_FLOOR, help="lowest count in the recognized-words set")
    p.add_argument("--dominance", type=float, default=DOMINANCE,
                   help="drop a bare spelling when an accented spelling is this many times as common in subtitles")
    p.add_argument("--min-dominance", type=float, default=5.0,
                   help="below --dominance, the subtitle ratio from which edited text may decide")
    p.add_argument("--edited-dominance", type=float, default=50.0,
                   help="ratio in the edited corpus text that drops a bare spelling between the two subtitle ratios")
    p.add_argument("--no-prior", action="store_true", help="words and coverage only")
    p.add_argument("--contexts", type=int, default=CONTEXTS)
    p.add_argument("--followers", type=int, default=FOLLOWERS)
    p.add_argument("--workers", type=int, default=min(12, os.cpu_count() or 1))
    args = p.parse_args(argv)
    args.downloads = args.downloads or str(DATA_ROOT / "downloads" / "languages" / args.lang)
    args.out_dir = str(DATA_ROOT / "languages" / args.lang / args.name)
    if Path(args.out_dir).exists():
        p.error(f"{args.out_dir} exists; pick another --name")
    return args


def main(argv: Sequence[str] | None = None) -> None:
    report = build(parse_args(argv))
    print(json.dumps({k: report[k] for k in ("words", "coverage", "prior") if k in report}, indent=2,
                     ensure_ascii=False)[:4000], file=sys.stderr)


if __name__ == "__main__":
    main()
