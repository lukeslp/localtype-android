#!/usr/bin/env python3
"""Build the corpus prior: word pairs counted from the licensed corpus, as a small keyboard asset.

The keyboard's ranker (AdaptiveNgramModel.kt) knows nothing about context on a
fresh install beyond a few seed phrases. This counts which words follow which in
``<corpus>/train.txt``, smooths the counts with interpolated modified
Kneser-Ney, keeps the most frequent previous words and their likeliest
followers, and writes them to ``app/src/main/assets/corpus_prior.bin`` for
``CorpusPrior.kt`` to read.

Context follows the keyboard, not a general tokenizer: a word's previous word
is the word right before it when only spaces separate them and that word ends
in a letter (``lastCompletedWord``), the sentence start ``<s>`` at the start of
a line or after ``.`` ``!`` ``?`` (``isSentenceStart``), and nothing at all
after any other punctuation or a number, which the keyboard treats as a reset.
Words are normalized as the keyboard does (typographic apostrophe folded,
lowercased). Previous words and followers are restricted to the keyboard's
dictionary (``words_en.txt`` and ``words_en_modern.txt``); the swear list is
never read except to exclude its words, and one-letter followers other than
"a" and "i" are dropped because the keyboard never offers them.

Lines from the human-written sources (NUS SMS, Taskmaster-1) count
``--human-weight`` times, 4 by default as in ``train.py``.

The asset's header says how its counts were made: the corpus hashes, the line
limit and the human weight. ``pairs.npz`` records the same, and a build from it
takes them from the file, never from the command line. Only a full count may
replace the bundled asset, so ``--limit`` and ``--from-pairs`` need ``--out``.

Usage, from the repository root:

    python -m model.prior                                    # full build into the app's assets
    python -m model.prior --limit 200000 --name smoke --out /tmp/prior.bin
    python -m model.prior --from-pairs <root>/prior/<name>/pairs.npz --name resel --contexts 5000 --out /tmp/resel.bin

The asset layout is documented on ``write_asset`` and in ``CorpusPrior.kt``.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import struct
import sys
import time
import zlib
from dataclasses import dataclass, field
from itertools import islice
from multiprocessing import Pool
from pathlib import Path
from typing import Callable, Iterator, Sequence

import numpy as np

from model import tokenize as tk

DATA_ROOT = Path(os.environ.get("LOCALTYPE_MODEL_DATA", str(Path.home() / ".local/share/localtype-model")))
REPO = Path(__file__).resolve().parents[1]
ASSETS = REPO / "app" / "src" / "main" / "assets"
DEFAULT_OUT = ASSETS / "corpus_prior.bin"
DICTIONARY_FILES = ("words_en.txt", "words_en_modern.txt")
PROFANITY_FILE = "words_en_profanity.txt"

HUMAN_SOURCES = ("nus", "taskmaster")  # the same two as train.py
HUMAN_WEIGHT = 4
CONTEXTS = 10_000
FOLLOWERS = 16

START = tk.BOS  # "<s>": the sentence-start context, also the asset's spelling of it
SENTENCE_ENDS = ".!?\n"  # AdaptiveNgramModel.isSentenceStart
MAGIC = b"LTCP"
VERSION = 1
MAX_WORD_INDEX = 0xFFFF  # word indexes are u16 in the asset
BATCH_LINES = 20_000
SHIFT = 32  # pair key = context id << SHIFT | word id


# ------------------------------------------------------------------ the keyboard's context rule


def word_spans(line: str) -> list[tuple[int, int, str]]:
    """(start, end, normalized word) for every keyboard word in ``line``.

    A word is ``[\\p{L}][\\p{L}'’-]*`` as in ``AdaptiveNgramModel.WORD_REGEX``.
    Python's ``[^\\W\\d_]`` also accepts number letters (², Ⅻ) that Java's
    ``\\p{L}`` does not, so a match holding one is split around it, as
    ``tokenize`` does.
    """
    spans = []
    for m in tk.WORD_RE.finditer(line):
        tok = m.group()
        if tok.isascii() or all(c.isalpha() or c in "'’-" for c in tok):
            spans.append((m.start(), m.end(), tk.normalize(tok)))
            continue
        run_start = None
        for j, c in enumerate(tok):
            if c.isalpha() or (run_start is not None and c in "'’-"):
                if run_start is None:
                    run_start = j
            elif run_start is not None:
                spans.append((m.start() + run_start, m.start() + j, tk.normalize(tok[run_start:j])))
                run_start = None
        if run_start is not None:
            spans.append((m.start() + run_start, m.end(), tk.normalize(tok[run_start:])))
    return spans


def context_events(line: str) -> list[tuple[str | None, str]]:
    """(context, word) for each word of ``line``, with the context the keyboard would use.

    The context is what ``predictScored`` computes from the text before the
    word: ``lastCompletedWord(text) ?: START.takeIf { isSentenceStart(text) }``.
    So it is the previous word when only spaces or tabs come between and that
    word ends in a letter, ``<s>`` when the text so far is blank or ends in
    . ! ? or a newline, and None otherwise (a comma, a number, a quote).
    This is linear in the line; the tests check it against the device mirrors
    in ``tokenize`` on real corpus lines.
    """
    out: list[tuple[str | None, str]] = []
    spans = word_spans(line)
    for i, (start, _, word) in enumerate(spans):
        t = start
        while t > 0 and line[t - 1] in " \t":
            t -= 1
        if t == 0 or line[t - 1] in SENTENCE_ENDS:
            ctx = START
        elif line[t - 1].isalpha() and i > 0 and spans[i - 1][1] == t:
            ctx = spans[i - 1][2]
        else:
            ctx = None
        out.append((ctx, word))
    return out


# ------------------------------------------------------------------ the keyboard's word lists


def read_word_list(path: Path) -> list[str]:
    """Normalized words of a keyboard list (``word<TAB>count``, ``#`` comments), in file order."""
    words = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.strip() or line.startswith("#"):
                continue
            words.append(tk.normalize(line.split("\t", 1)[0].strip()))
    return words


def is_device_word(word: str) -> bool:
    """One whole keyboard word, as ``word_spans`` would find it."""
    spans = word_spans(word)
    return len(spans) == 1 and spans[0][0] == 0 and spans[0][1] == len(word) and spans[0][2] == word


def offerable(word: str) -> bool:
    """AdaptiveNgramModel.predict drops one-letter words other than a and i."""
    return len(word) >= 2 or word in ("a", "i")


def load_dictionary(assets: Path) -> tuple[set[str], dict]:
    """Words the prior may hold: the keyboard dictionary minus every swear word."""
    words: set[str] = set()
    files = {}
    for name in DICTIONARY_FILES:
        path = assets / name
        words.update(w for w in read_word_list(path) if is_device_word(w))
        files[name] = sha256_file(path)
    swear_path = assets / PROFANITY_FILE
    swears = set(read_word_list(swear_path)) if swear_path.exists() else set()
    if swear_path.exists():
        files[PROFANITY_FILE] = sha256_file(swear_path)
    words -= swears
    return words, {"files": files, "words": len(words), "swear_words_excluded": len(swears)}


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


# ------------------------------------------------------------------ counting


def count_batch(batch: tuple[list[str], list[int]]) -> tuple[list[str], np.ndarray, np.ndarray, np.ndarray]:
    """Pair counts for one batch: (local words, unique local keys, weighted counts, raw counts)."""
    lines, weights = batch
    index: dict[str, int] = {}
    keys: list[int] = []
    ws: list[int] = []
    for line, w in zip(lines, weights):
        for ctx, word in context_events(line):
            if ctx is None:
                continue
            c = index.setdefault(ctx, len(index))
            n = index.setdefault(word, len(index))
            keys.append(c << SHIFT | n)
            ws.append(w)
    if not keys:
        empty = np.zeros(0, np.int64)
        return list(index), empty, empty, empty
    uniq, inv = np.unique(np.asarray(keys, dtype=np.int64), return_inverse=True)
    weighted = np.bincount(inv, weights=np.asarray(ws, dtype=np.float64)).astype(np.int64)
    return list(index), uniq, weighted, np.bincount(inv).astype(np.int64)


def batches(corpus: Path, limit: int | None, human_weight: int) -> Iterator[tuple[list[str], list[int]]]:
    """Lines of ``train.txt`` with each line's weight, ``BATCH_LINES`` at a time.

    Weighting needs the line-parallel ``train.sources.txt``: without it, or
    when its length differs from ``train.txt``, this stops rather than count
    every line once under a header that says otherwise.
    """
    src_path = corpus / "train.sources.txt"
    if human_weight != 1 and not src_path.exists():
        raise SystemExit(f"{src_path} is missing, so --human-weight {human_weight} can't be applied; pass --human-weight 1")
    sources = open(src_path, encoding="utf-8") if src_path.exists() else None
    done = 0
    with open(corpus / "train.txt", encoding="utf-8") as text:
        try:
            while True:
                take = BATCH_LINES if limit is None else min(BATCH_LINES, limit - done)
                if take <= 0:
                    return
                lines = [line.rstrip("\n") for line in islice(text, take)]
                if not lines:
                    if sources and sources.readline():
                        raise SystemExit(f"{src_path} is longer than train.txt")
                    return
                srcs = [s.strip() for s in islice(sources, len(lines))] if sources else [""] * len(lines)
                if len(srcs) != len(lines):
                    raise SystemExit(f"{src_path} is shorter than train.txt")
                done += len(lines)
                yield lines, [human_weight if s in HUMAN_SOURCES else 1 for s in srcs]
        finally:
            if sources:
                sources.close()


def merge(keys: Sequence[np.ndarray], *counts: Sequence[np.ndarray]) -> tuple[np.ndarray, ...]:
    """Sum aligned count arrays over equal keys: (unique keys, *summed counts)."""
    uniq, inv = np.unique(np.concatenate(keys), return_inverse=True)
    sums = (np.bincount(inv, weights=np.concatenate(c).astype(np.float64), minlength=len(uniq)).astype(np.int64)
            for c in counts)
    return (uniq, *sums)


@dataclass
class Pairs:
    """Every (context, word) pair that has a keyboard context, with weighted and raw counts."""

    words: list[str]  # global id -> word ("<s>" included)
    keys: np.ndarray  # sorted unique int64, context id << SHIFT | word id
    counts: np.ndarray  # int64 weighted counts (human-written lines x human_weight), aligned with keys
    raw: np.ndarray  # int64 plain occurrence counts, aligned with keys
    lines: int = 0
    weighted_lines: int = 0
    # How the counts were made, as the asset header records it: SOURCE_KEYS. Empty for a file saved before
    # pairs.npz recorded it; build() then reads the build.json saved beside it.
    source: dict = field(default_factory=dict)

    @property
    def ctx(self) -> np.ndarray:
        return self.keys >> SHIFT

    @property
    def word(self) -> np.ndarray:
        return self.keys & ((1 << SHIFT) - 1)

    def save(self, path: Path) -> None:
        # Words and the source go in as UTF-8 blobs (no word holds a newline), so loading needs no pickle.
        blob = np.frombuffer("\n".join(self.words).encode("utf-8"), dtype=np.uint8)
        source = np.frombuffer(json.dumps(self.source, sort_keys=True).encode("utf-8"), dtype=np.uint8)
        np.savez(path, keys=self.keys, counts=self.counts, raw=self.raw, words=blob,
                 lines=self.lines, weighted_lines=self.weighted_lines, source=source)

    @classmethod
    def load(cls, path: Path) -> "Pairs":
        z = np.load(path, allow_pickle=False)
        words = z["words"].tobytes().decode("utf-8").split("\n")
        source = json.loads(z["source"].tobytes().decode("utf-8")) if "source" in z.files else {}
        return cls(words, z["keys"], z["counts"], z["raw"], int(z["lines"]), int(z["weighted_lines"]), source)


SOURCE_KEYS = ("manifest_sha256", "train_sha256", "limit", "human_weight")


def corpus_source(corpus: Path, limit: int | None, human_weight: int) -> dict:
    """What the header records about a count of ``corpus``: SOURCE_KEYS."""
    manifest = corpus / "manifest.json"
    return {
        "manifest_sha256": sha256_file(manifest) if manifest.exists() else None,
        "train_sha256": sha256_file(corpus / "train.txt"),
        "limit": limit,
        "human_weight": human_weight,
    }


def count_pairs(corpus: Path, limit: int | None, workers: int, human_weight: int) -> Pairs:
    source = corpus_source(corpus, limit, human_weight)
    ids: dict[str, int] = {}
    pending: list[tuple[np.ndarray, np.ndarray, np.ndarray]] = []
    keys = counts = raw = np.zeros(0, np.int64)
    lines = weighted = 0
    t0 = time.time()

    def counted(stream):
        nonlocal lines, weighted
        for b in stream:
            lines += len(b[0])
            weighted += sum(b[1])
            yield b

    def fold():
        nonlocal keys, counts, raw, pending
        keys, counts, raw = merge([keys, *(p[0] for p in pending)], [counts, *(p[1] for p in pending)],
                                  [raw, *(p[2] for p in pending)])
        pending = []

    stream = counted(batches(corpus, limit, human_weight))
    pool = Pool(workers) if workers > 1 else None
    try:
        for local, uk, uw, ur in (pool.imap(count_batch, stream) if pool else map(count_batch, stream)):
            remap = np.fromiter((ids.setdefault(w, len(ids)) for w in local), dtype=np.int64, count=len(local))
            pending.append((remap[uk >> SHIFT] << SHIFT | remap[uk & ((1 << SHIFT) - 1)], uw, ur))
            if len(pending) >= 32:
                fold()
                print(f"  {lines:,} lines, {len(keys):,} pairs, {time.time() - t0:.0f}s", file=sys.stderr, flush=True)
    finally:
        if pool:
            pool.close()
            pool.join()
    fold()
    if len(ids) >= 1 << SHIFT:
        raise SystemExit("too many word types for the pair key")
    words = [""] * len(ids)
    for w, i in ids.items():
        words[i] = w
    return Pairs(words, keys, counts, raw, lines, weighted, source)


# ------------------------------------------------------------------ smoothing


def count_of_counts(raw: np.ndarray) -> list[int]:
    """n1..n4: how many pair types occur exactly 1, 2, 3 and 4 times."""
    return [int((raw == k).sum()) for k in (1, 2, 3, 4)]


def estimate_discounts(raw: np.ndarray) -> tuple[float, float, float]:
    """Modified Kneser-Ney discounts D1, D2, D3+ from the count-of-counts (Chen and Goodman 1998).

    With n_k pair types seen exactly k times and Y = n1 / (n1 + 2 n2):
    D1 = 1 - 2Y n2/n1, D2 = 2 - 3Y n3/n2, D3+ = 3 - 4Y n4/n3. The estimate
    uses plain occurrence counts: weighting the human-written lines turns
    their singletons into fours, which says nothing about how sparse the
    pairs really are.
    """
    n = count_of_counts(raw)
    if min(n) == 0:
        raise ValueError(f"count-of-counts {n} has a zero; pass --discounts for a corpus this small")
    y = n[0] / (n[0] + 2 * n[1])
    d = (1 - 2 * y * n[1] / n[0], 2 - 3 * y * n[2] / n[1], 3 - 4 * y * n[3] / n[2])
    if not all(0 < d[i] <= i + 1 for i in range(3)):
        raise ValueError(f"discounts {d} out of range for count-of-counts {n}")
    return d


@dataclass
class Smoothed:
    """Interpolated modified Kneser-Ney bigram probabilities for every observed pair."""

    log_prob: np.ndarray  # ln P_KN(word | ctx), aligned with Pairs.keys
    context_total: np.ndarray  # weighted count of each id as a context
    log_unigram: np.ndarray  # ln of each id's share of the weighted words that have a context (-inf if none)
    gamma: np.ndarray  # per id as a context: the mass its discounts free up
    continuation: np.ndarray  # per id as a word: P_cont, its share of distinct contexts
    discounts: tuple[float, float, float]
    count_of_counts: list[int] = field(default_factory=list)


def kneser_ney(pairs: Pairs, discounts: tuple[float, float, float] | None = None) -> Smoothed:
    """P_KN(w | v) = (c(v,w) - D(c)) / c(v) + gamma(v) * P_cont(w), over the weighted counts.

    D(c) is D1, D2 or D3+ by the weighted count, gamma(v) = (D1 N1(v) +
    D2 N2(v) + D3+ N3+(v)) / c(v) is the mass the discounts free up, and
    P_cont(w) = N1+(. w) / N1+(. .) is the share of distinct contexts that
    ``w`` follows, the Kneser-Ney lower order. Every weighted count is at
    least 1 and every discount at most its bucket's count, so no term goes
    negative. Over every word type the distribution sums to one for each
    context; the tests check that.
    """
    c = pairs.counts
    d = discounts or estimate_discounts(pairs.raw)
    n_ids = len(pairs.words)
    ctx, word = pairs.ctx, pairs.word
    disc = np.where(c == 1, d[0], np.where(c == 2, d[1], d[2]))
    total = np.bincount(ctx, weights=c.astype(np.float64), minlength=n_ids)
    gamma = np.bincount(ctx, weights=disc, minlength=n_ids) / np.maximum(total, 1)
    cont = np.bincount(word, minlength=n_ids) / len(c)
    p = (c - disc) / total[ctx] + gamma[ctx] * cont[word]
    occurrences = np.bincount(word, weights=c.astype(np.float64), minlength=n_ids)
    with np.errstate(divide="ignore"):
        unigram = np.log(occurrences / occurrences.sum())
    return Smoothed(np.log(p), total, unigram, gamma, cont, tuple(float(x) for x in d), count_of_counts(pairs.raw))


# ------------------------------------------------------------------ selection


@dataclass
class Prior:
    """What goes in the asset: a sorted word list, and followers per context, best first."""

    words: list[str]  # sorted by UTF-16 code units, the order Kotlin's String.compareTo uses
    log_unigram: list[float]  # per word; 0.0 for "<s>", which never follows anything
    contexts: list[tuple[int, list[tuple[int, float]]]]  # (word index, [(word index, ln P)]) by word index


def utf16_key(word: str) -> bytes:
    return word.encode("utf-16-be")


def select(pairs: Pairs, smoothed: Smoothed, dictionary: set[str], contexts: int, followers: int,
           can_offer: Callable[[str], bool] = offerable) -> Prior:
    """The ``contexts`` most frequent dictionary words as contexts (plus ``<s>``), each with its
    ``followers`` likeliest dictionary followers; a swear or a word ``can_offer`` refuses (by default
    a one-letter word other than a and i) is never a follower, and only followers seen after the
    context are kept. Another language passes its own one-letter words (model/language_build.py)."""
    words = pairs.words
    gid = {w: i for i, w in enumerate(words)}
    is_dict = np.fromiter((w in dictionary for w in words), dtype=bool, count=len(words))
    can_follow = np.fromiter((w in dictionary and can_offer(w) for w in words), dtype=bool, count=len(words))
    total = smoothed.context_total
    candidates = [int(i) for i in np.nonzero(is_dict & (total > 0))[0]]
    candidates.sort(key=lambda i: (-total[i], utf16_key(words[i])))
    chosen = set(candidates[:contexts])
    if START in gid and total[gid[START]] > 0:
        chosen.add(gid[START])

    ctx, word = pairs.ctx, pairs.word
    keep = np.isin(ctx, np.fromiter(chosen, dtype=np.int64, count=len(chosen))) & can_follow[word]
    idx = np.nonzero(keep)[0]
    # By context, then likeliest first, then by word for ties.
    rank = np.empty(len(words), np.int64)
    rank[sorted(range(len(words)), key=lambda i: utf16_key(words[i]))] = np.arange(len(words))
    order = np.lexsort((rank[word[idx]], -smoothed.log_prob[idx], ctx[idx]))
    idx = idx[order]
    per_ctx: dict[int, list[tuple[int, float]]] = {}
    for i in idx:
        lst = per_ctx.setdefault(int(ctx[i]), [])
        if len(lst) < followers:
            lst.append((int(word[i]), float(smoothed.log_prob[i])))

    used = set(per_ctx)
    for lst in per_ctx.values():
        used.update(w for w, _ in lst)
    asset_words = sorted((words[i] for i in used), key=utf16_key)
    if len(asset_words) > MAX_WORD_INDEX + 1:
        raise SystemExit(f"{len(asset_words)} words do not fit u16 indexes")
    position = {w: n for n, w in enumerate(asset_words)}
    # A context word that never itself followed a context has no share; give it the smallest one seen.
    shares = [float(smoothed.log_unigram[gid[w]]) for w in asset_words if w != START]
    floor = min((s for s in shares if math.isfinite(s)), default=0.0)
    unigram = [0.0 if w == START else max(float(smoothed.log_unigram[gid[w]]), floor) for w in asset_words]
    entries = sorted(
        ((position[words[c]], [(position[words[w]], lp) for w, lp in lst]) for c, lst in per_ctx.items()),
        key=lambda e: e[0],
    )
    return Prior(asset_words, unigram, entries)


# ------------------------------------------------------------------ the asset


def quantize(values: Sequence[float]) -> tuple[np.ndarray, float, float]:
    """8-bit codes for ``values``: q = round((v - lo) / step), v' = lo + q * step in float32."""
    v = np.asarray(values, dtype=np.float64)
    if len(v) == 0:
        return np.zeros(0, np.uint8), 0.0, 0.0
    lo = float(np.float32(v.min()))
    hi = float(v.max())
    step = float(np.float32((hi - lo) / 255)) if hi > lo else 0.0
    q = np.zeros(len(v), np.uint8) if step == 0 else np.clip(np.rint((v - lo) / step), 0, 255).astype(np.uint8)
    return q, lo, step


def dequantize(q: np.ndarray, lo: float, step: float) -> np.ndarray:
    """What ``CorpusPrior.kt`` computes: ``lo + q * step`` in float32."""
    return (np.float32(lo) + q.astype(np.float32) * np.float32(step)).astype(np.float32)


def write_asset(path: Path, prior: Prior, metadata: dict) -> dict:
    """Write the asset and return a summary of it.

    Big-endian throughout (Java's DataInput order). Byte layout, version 1:

        4   magic "LTCP"
        2   version (1)
        2   flags (0)
        4   metadata length M
        M   metadata: UTF-8 JSON (corpus manifest hash, build parameters, counts)
        4   word count W (at most 65,536)
        W x word: u8 byte length, then that many UTF-8 bytes; strictly ascending
            by UTF-16 code units; "<s>" is the sentence-start context
        4+4 unigram scale: float32 lo, float32 step
        W   u8 per word: ln of its share of words with a context, lo + q * step (0 for "<s>")
        4   context count C
        4+4 follower scale: float32 lo, float32 step
        C x context: u16 word index, u8 follower count n (1..255); ascending word index
        F x follower: u16 word index, u8 code for ln P(word | context); the followers of each
            context in context order, likeliest first; F is the sum of the counts
        4   CRC-32 of every byte before it
    """
    meta = json.dumps(metadata, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    uq, ulo, ustep = quantize(prior.log_unigram)
    all_lp = [lp for _, lst in prior.contexts for _, lp in lst]
    fq, flo, fstep = quantize(all_lp)
    out = bytearray()
    out += MAGIC + struct.pack(">HHI", VERSION, 0, len(meta)) + meta
    out += struct.pack(">I", len(prior.words))
    for w in prior.words:
        b = w.encode("utf-8")
        if len(b) > 255:
            raise ValueError(f"word too long for the asset: {w!r}")
        out += struct.pack(">B", len(b)) + b
    out += struct.pack(">ff", ulo, ustep) + uq.tobytes()
    out += struct.pack(">I", len(prior.contexts)) + struct.pack(">ff", flo, fstep)
    for c, lst in prior.contexts:
        out += struct.pack(">HB", c, len(lst))
    k = 0
    for _, lst in prior.contexts:
        for w, _ in lst:
            out += struct.pack(">HB", w, int(fq[k]))
            k += 1
    out += struct.pack(">I", zlib.crc32(out) & 0xFFFFFFFF)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_bytes(bytes(out))
    os.replace(tmp, path)
    err = np.abs(dequantize(fq, flo, fstep).astype(np.float64) - np.asarray(all_lp)) if all_lp else np.zeros(1)
    return {
        "bytes": len(out),
        "sha256": hashlib.sha256(out).hexdigest(),
        "words": len(prior.words),
        "contexts": len(prior.contexts),
        "followers": len(all_lp),
        "follower_scale": {"lo": flo, "step": fstep, "max_abs_error": float(err.max())},
        "unigram_scale": {"lo": ulo, "step": ustep},
    }


@dataclass
class Asset:
    """A parsed asset, dequantized the way the keyboard does it."""

    metadata: dict
    words: list[str]
    log_unigram: np.ndarray
    followers: dict[str, list[tuple[str, float]]]


def read_asset(path: Path) -> Asset:
    data = Path(path).read_bytes()
    if data[:4] != MAGIC:
        raise ValueError("not a corpus prior")
    if zlib.crc32(data[:-4]) & 0xFFFFFFFF != struct.unpack(">I", data[-4:])[0]:
        raise ValueError("checksum mismatch")
    version, _, mlen = struct.unpack_from(">HHI", data, 4)
    if version != VERSION:
        raise ValueError(f"unknown version {version}")
    pos = 12
    meta = json.loads(data[pos : pos + mlen].decode("utf-8"))
    pos += mlen
    (n_words,) = struct.unpack_from(">I", data, pos)
    pos += 4
    words = []
    for _ in range(n_words):
        n = data[pos]
        words.append(data[pos + 1 : pos + 1 + n].decode("utf-8"))
        pos += 1 + n
    ulo, ustep = struct.unpack_from(">ff", data, pos)
    pos += 8
    uni = dequantize(np.frombuffer(data, np.uint8, n_words, pos), ulo, ustep)
    pos += n_words
    n_ctx, flo, fstep = struct.unpack_from(">Iff", data, pos)
    pos += 12
    heads = [struct.unpack_from(">HB", data, pos + 3 * i) for i in range(n_ctx)]
    pos += 3 * n_ctx
    followers = {}
    for c, n in heads:
        lst = []
        for _ in range(n):
            w, q = struct.unpack_from(">HB", data, pos)
            pos += 3
            lst.append((words[w], float(dequantize(np.array([q], np.uint8), flo, fstep)[0])))
        followers[words[c]] = lst
    if pos != len(data) - 4:
        raise ValueError("trailing bytes")
    return Asset(meta, words, uni, followers)


# ------------------------------------------------------------------ build


def saved_source(path: Path, pairs: Pairs) -> dict:
    """How the counts in a saved ``pairs.npz`` were made (SOURCE_KEYS).

    A file saved before ``pairs.npz`` recorded this falls back to the
    ``build.json`` its build wrote beside it, once that report's line and pair
    counts show it describes these counts.
    """
    if pairs.source:
        return pairs.source
    report_path = path.parent / "build.json"
    if not report_path.exists():
        raise SystemExit(f"{path} doesn't record how it was counted and has no build.json beside it; count again")
    report = json.loads(report_path.read_text(encoding="utf-8"))
    meta, counted = report["metadata"], report["pairs"]
    if (meta["corpus"]["lines"], counted["types"], counted["weighted_lines"]) != (pairs.lines, len(pairs.keys), pairs.weighted_lines):
        raise SystemExit(f"{report_path} doesn't describe the counts in {path}")
    if meta["corpus"]["train_sha256"] is None:
        raise SystemExit(f"{report_path} has no train.txt hash, so it may describe a reselection, not the count; count again")
    return {"manifest_sha256": meta["corpus"]["manifest_sha256"], "train_sha256": meta["corpus"]["train_sha256"],
            "limit": meta["corpus"]["limit"], "human_weight": meta["human_weight"]}


def build(args: argparse.Namespace) -> dict:
    t0 = time.time()
    dictionary, dict_info = load_dictionary(Path(args.assets))
    if args.from_pairs:
        pairs = Pairs.load(Path(args.from_pairs))
        pairs.source = saved_source(Path(args.from_pairs), pairs)
        print(f"loaded {len(pairs.keys):,} pairs from {args.from_pairs}", file=sys.stderr)
    else:
        human_weight = HUMAN_WEIGHT if args.human_weight is None else args.human_weight
        pairs = count_pairs(Path(args.corpus), args.limit, args.workers, human_weight)
    source = pairs.source
    # The header describes the counts, so a flag that says otherwise is a mistake, not an override.
    for flag, key in (("--human-weight", "human_weight"), ("--limit", "limit"), ("--train-sha256", "train_sha256")):
        given = getattr(args, key)
        if given is not None and given != source[key]:
            raise SystemExit(f"{flag} {given} disagrees with the counts, which have {key} {source[key]}")
    if source["limit"] is not None and Path(args.out).resolve() == DEFAULT_OUT.resolve():
        raise SystemExit(f"counts limited to {source['limit']:,} lines can't replace the bundled asset; pass another --out")
    counted = time.time() - t0
    smoothed = kneser_ney(pairs, tuple(args.discounts) if args.discounts else None)
    prior = select(pairs, smoothed, dictionary, args.contexts, args.followers)

    metadata = {
        "format": "LocalType corpus prior",
        "version": VERSION,
        "corpus": {
            "manifest_sha256": source["manifest_sha256"],
            "train_sha256": source["train_sha256"],
            "lines": pairs.lines,
            "limit": source["limit"],
        },
        "context_rule": "AdaptiveNgramModel: previous word when only spaces separate it and it ends in a letter; "
                        "<s> at the start or after . ! ? or a newline; anything else resets",
        "human_sources": list(HUMAN_SOURCES),
        "human_weight": source["human_weight"],
        "smoothing": "interpolated modified Kneser-Ney bigram (Chen and Goodman 1998)",
        "discounts": [round(d, 6) for d in smoothed.discounts],
        "log": "natural",
        "contexts": args.contexts,
        "followers_per_context": args.followers,
        "dictionary": dict_info,
        "licence": "Derived from NUS SMS Corpus, Taskmaster-1 and SODA, each CC BY 4.0; "
                   "see licenses/corpus-prior-NOTICE.txt",
    }
    summary = write_asset(Path(args.out), prior, metadata)

    report = {
        "metadata": metadata,
        "asset": {"path": str(args.out), **summary},
        "pairs": {"types": int(len(pairs.keys)), "word_types": len(pairs.words),
                  "weighted_events": int(pairs.counts.sum()), "events": int(pairs.raw.sum()), "count_of_counts_1_to_4": smoothed.count_of_counts,
                  "weighted_lines": pairs.weighted_lines},
        "seconds": {"count": round(counted, 1), "total": round(time.time() - t0, 1)},
        "from_pairs": args.from_pairs,
    }
    if args.report_dir:
        rd = Path(args.report_dir)
        rd.mkdir(parents=True, exist_ok=False)
        (rd / "build.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        with open(rd / "followers.tsv", "w", encoding="utf-8") as f:
            f.write("context\tfollower\tln_p\n")
            for c, lst in prior.contexts:
                for w, lp in lst:
                    f.write(f"{prior.words[c]}\t{prior.words[w]}\t{lp:.4f}\n")
        if args.save_pairs:
            pairs.save(rd / "pairs.npz")
    return report


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--corpus", help="directory with train.txt and train.sources.txt (default <root>/corpus)")
    p.add_argument("--assets", default=str(ASSETS), help="directory with the keyboard word lists")
    p.add_argument("--out", help="asset to write (default: the bundled asset; required with --limit or --from-pairs)")
    p.add_argument("--name", help="report directory name under <root>/prior/ (must not exist)")
    p.add_argument("--no-report", action="store_true", help="write the asset only")
    p.add_argument("--save-pairs", action="store_true", help="also save every counted pair as pairs.npz in the report directory")
    p.add_argument("--from-pairs", help="reuse a saved pairs.npz instead of counting the corpus again; "
                                        "the corpus hashes, limit and human weight come from the file")
    p.add_argument("--train-sha256", help="stop unless the counts come from a train.txt with this hash")
    p.add_argument("--limit", type=int, help="read only the first N training lines (smoke runs)")
    p.add_argument("--human-weight", type=int, help=f"count of each line from {', '.join(HUMAN_SOURCES)} "
                                                    f"(default {HUMAN_WEIGHT}; 1 = off)")
    p.add_argument("--contexts", type=int, default=CONTEXTS, help="previous words kept, besides <s>")
    p.add_argument("--followers", type=int, default=FOLLOWERS, help="followers kept per previous word")
    p.add_argument("--discounts", type=float, nargs=3, metavar=("D1", "D2", "D3"), help="fixed discounts instead of the estimate")
    p.add_argument("--workers", type=int, default=min(12, os.cpu_count() or 1))
    args = p.parse_args(argv)
    if not 1 <= args.followers <= 255:
        p.error("--followers must be 1..255")
    if args.from_pairs and args.corpus:
        p.error("--from-pairs takes the corpus from the pairs file; drop --corpus")
    if args.corpus is None:
        args.corpus = str(DATA_ROOT / "corpus")
    if args.out is None:
        if args.limit is not None or args.from_pairs:
            p.error("--limit and --from-pairs need --out, so a partial or reselected build never replaces the bundled asset by default")
        args.out = str(DEFAULT_OUT)
    if args.limit is not None and Path(args.out).resolve() == DEFAULT_OUT.resolve():
        p.error("--limit can't write the bundled asset; pass another --out")
    if args.no_report:
        args.report_dir = None
    else:
        name = args.name or time.strftime("prior-%Y-%m-%d")
        args.report_dir = str(DATA_ROOT / "prior" / name)
        if Path(args.report_dir).exists():
            p.error(f"{args.report_dir} exists; pick another --name")
    return args


def main(argv: Sequence[str] | None = None) -> None:
    report = build(parse_args(argv))
    a = report["asset"]
    print(f"{a['path']}: {a['bytes']:,} bytes, {a['words']:,} words, {a['contexts']:,} contexts, "
          f"{a['followers']:,} followers, discounts {report['metadata']['discounts']}, "
          f"{report['seconds']['total']}s", file=sys.stderr)


if __name__ == "__main__":
    main()
