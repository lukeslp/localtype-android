"""Word-level tokenizer that mirrors the LocalType keyboard's own word rules.

The keyboard (AdaptiveNgramModel.kt) sees a word as ``[\\p{L}][\\p{L}'’-]*`` and
normalizes it with ``replace('’', '\\'').lowercase(Locale.ROOT)``. The evaluation
harness scores candidates as exactly those normalized words, so the model's
vocabulary has to be made of them, and nothing else can be a "word" here.

Token kinds, in the order they sit in a vocabulary file:

    specials   <pad> <s> </s> <unk> <num> <sym>
    punct      one token per common punctuation mark (PUNCT below); look-alike
               characters are folded onto them (’ -> ', — -> -, 。 -> .), runs of
               the same mark collapse to one, and two or more dots become …
    emoji      every emoji the keyboard can offer (app/src/main/assets/emoji_en.txt),
               each as one token, ZWJ sequences and variation selectors included
    words      lowercased words, most frequent first

``<num>`` stands for any run of digits (with . , : / inside, so 12:30 and 1,000
are one token), ``<sym>`` for any other character (currency signs, stray
combining marks, control characters, an emoji the keyboard does not know), and
``<unk>`` for a word outside the vocabulary. Whitespace is never a token.

Module-level functions work without a vocabulary; ``Tokenizer`` adds ids.
``detokenize(tokenize(text))`` is the normalized form of ``text``, and
``tokenize(detokenize(tokens)) == tokens`` for any ``tokens`` that ``tokenize``
produced (the tests check this on real corpus lines).

Import this as ``from model import tokenize`` (or ``python -m model.vocab``),
never with ``model/`` on ``sys.path`` as ``import tokenize``: that name belongs
to the standard library, which ``inspect`` and ``linecache`` import. If that
happens anyway, the guard below hands the import over to the standard library
module instead of breaking tracebacks.
"""

from __future__ import annotations

import sys

if __name__ == "tokenize":  # shadowing the standard library: hand over to it
    import importlib.util as _ilu
    import os as _os
    import sysconfig as _sysconfig

    _spec = _ilu.spec_from_file_location(
        "tokenize", _os.path.join(_sysconfig.get_paths()["stdlib"], "tokenize.py")
    )
    _stdlib = _ilu.module_from_spec(_spec)
    sys.modules["tokenize"] = _stdlib  # the import system returns what is in sys.modules
    _spec.loader.exec_module(_stdlib)
    globals().update(vars(_stdlib))
else:
    import re
    from pathlib import Path
    from typing import Iterable, Iterator, Sequence

    __all__ = [
        "PAD", "BOS", "EOS", "UNK", "NUM", "SYM", "SPECIALS", "PUNCT", "CANON",
        "WORD_RE", "PREFIX_RE", "EMOJI_RE", "START",
        "normalize", "normalize_word", "active_prefix", "last_completed_word",
        "is_sentence_start", "is_word", "is_emoji", "tokenize", "detokenize",
        "strip_presentation", "Tokenizer",
    ]

    PAD, BOS, EOS, UNK, NUM, SYM = "<pad>", "<s>", "</s>", "<unk>", "<num>", "<sym>"
    SPECIALS: tuple[str, ...] = (PAD, BOS, EOS, UNK, NUM, SYM)

    # Punctuation that gets its own token. Everything else non-word becomes <sym>.
    PUNCT: tuple[str, ...] = (
        ".", ",", "?", "!", "…", "'", '"', "-", ":", ";", "(", ")", "/", "&",
        "$", "%", "*", "+", "=", "@", "#", "~", "_", "[", "]", "<", ">", "^",
        "\\", "|", "°",
    )
    _PUNCT_SET = frozenset(PUNCT)

    # Look-alikes folded onto the tokens above (the device folds only ’ inside words;
    # outside words it does not care, so this only shapes the model's context).
    CANON: dict[str, str] = {
        "’": "'", "‘": "'", "‚": "'", "`": "'", "´": "'",
        "“": '"', "”": '"', "„": '"', "«": '"', "»": '"',
        "–": "-", "—": "-", "‐": "-", "‑": "-", "−": "-", "﹣": "-", "－": "-",
        "。": ".", "．": ".", "，": ",", "、": ",", "：": ":", "；": ";",
        "？": "?", "！": "!", "（": "(", "）": ")", "¿": "?", "¡": "!",
        "‥": "…",
    }

    # AdaptiveNgramModel.START, stored in place of a previous word at a sentence start.
    START = "\u0002"

    # Python's [^\W\d_] is the L categories plus the Nl/No number letters (Ⅻ, ², ½)
    # that Java's \p{L} excludes; _exact_word_pieces fixes the rare non-ASCII case.
    _L = r"[^\W\d_]"
    _REST = r"(?:[^\W\d_]|['’-])*"  # a negated class cannot also list characters to allow
    WORD_RE = re.compile(rf"{_L}{_REST}")
    PREFIX_RE = re.compile(rf"{_L}{_REST}\Z")

    _EMOJI_BASE = (
        r"(?:[\U0001F1E6-\U0001F1FF]{2}"                      # flag (two regional indicators)
        r"|[0-9#*]\uFE0F?\u20E3"                              # keycap
        r"|[\U0001F000-\U0001FAFF\u2600-\u27BF\u2300-\u23FF\u2B00-\u2BFF"
        r"\u3030\u303D\u3297\u3299\u00A9\u00AE\u2122\u2194-\u2199\u21A9\u21AA"
        r"\u24C2\u25AA-\u25FE\u2934\u2935])"
    )
    _EMOJI_MODS = r"[\uFE0E\uFE0F\U0001F3FB-\U0001F3FF\U000E0020-\U000E007F]*"
    EMOJI_RE = re.compile(rf"{_EMOJI_BASE}{_EMOJI_MODS}(?:\u200D{_EMOJI_BASE}{_EMOJI_MODS})*")

    _MASTER = re.compile(
        rf"(?P<w>{_L}{_REST})"
        rf"|(?P<e>{EMOJI_RE.pattern})"
        r"|(?P<n>\d+(?:[.,:/]\d+)*)"
        r"|(?P<s>\s+)"
        r"|(?P<p>.)",
        re.DOTALL,
    )

    # ------------------------------------------------------------------ device mirrors

    def normalize(word: str) -> str:
        """AdaptiveNgramModel.normalize: fold the typographic apostrophe, lowercase."""
        return word.replace("’", "'").lower()

    def _is_exact_word(token: str) -> bool:
        if token.isascii():
            return True
        return token[0].isalpha() and all(c.isalpha() or c in "'’-" for c in token)

    def _exact_word_pieces(token: str) -> Iterator[tuple[bool, str]]:
        """Split a regex word that contains Nl/No characters the device would not accept."""
        run = ""
        for c in token:
            if c.isalpha() or (run and c in "'’-"):
                run += c
            else:
                if run:
                    yield True, run
                    run = ""
                yield False, c
        if run:
            yield True, run

    def normalize_word(text: str) -> str | None:
        """AdaptiveNgramModel.normalizeWord: the first word in ``text``, normalized."""
        for m in WORD_RE.finditer(text):
            tok = m.group()
            if _is_exact_word(tok):
                return normalize(tok)
            for is_w, piece in _exact_word_pieces(tok):
                if is_w:
                    return normalize(piece)
        return None

    def active_prefix(before_cursor: str) -> str:
        """AdaptiveNgramModel.activePrefix: the raw word being typed at the cursor, or ''."""
        i = len(before_cursor)
        while i > 0 and (before_cursor[i - 1].isalpha() or before_cursor[i - 1] in "'’-"):
            i -= 1
        run = before_cursor[i:]
        for j, c in enumerate(run):  # the prefix must start with a letter
            if c.isalpha():
                return run[j:]
        return ""

    def is_sentence_start(before_cursor: str) -> bool:
        """AdaptiveNgramModel.isSentenceStart: empty field, or the word follows . ! ? or a newline."""
        tail = before_cursor[: len(before_cursor) - len(active_prefix(before_cursor))].rstrip(" \t")
        return not tail or tail[-1] in ".!?\n"

    def last_completed_word(before_cursor: str) -> str | None:
        """AdaptiveNgramModel.lastCompletedWord: the normalized word before the one being typed.

        Punctuation, numbers and sentence ends reset context, so "Done. The" gives None.
        """
        tail = before_cursor[: len(before_cursor) - len(active_prefix(before_cursor))].rstrip(" \t")
        if not tail or not tail[-1].isalpha():
            return None
        last = None
        for m in WORD_RE.finditer(tail):
            last = m.group()
        if last is None:
            return None
        if not _is_exact_word(last):
            last = [p for is_w, p in _exact_word_pieces(last) if is_w][-1]
        return normalize(last)

    # ------------------------------------------------------------------ tokenizing

    def is_word(token: str) -> bool:
        """True for a token the harness could score as a word (not special, punct or emoji)."""
        return bool(token) and token[0].isalpha() and token not in SPECIALS and token not in _PUNCT_SET \
            and not EMOJI_RE.fullmatch(token)

    def is_emoji(token: str) -> bool:
        return bool(EMOJI_RE.fullmatch(token))

    def strip_presentation(emoji: str) -> str:
        """Key for matching an emoji regardless of variation selectors (☀ and ☀️ are one)."""
        return emoji.replace("️", "").replace("︎", "")

    def _emit(out: list[str], tok: str) -> None:
        prev = out[-1] if out else None
        if tok == ".":
            if prev == ".":
                out[-1] = "…"
                return
            if prev == "…":
                return
        elif tok == prev and tok in _PUNCT_SET:
            return
        out.append(tok)

    def tokenize(text: str) -> list[str]:
        """Split ``text`` into normalized tokens: words, punct, emoji, <num> and <sym>."""
        out: list[str] = []
        for m in _MASTER.finditer(text):
            kind = m.lastgroup
            if kind == "w":
                tok = m.group()
                if _is_exact_word(tok):
                    out.append(normalize(tok))
                else:
                    for is_w, piece in _exact_word_pieces(tok):
                        if is_w:
                            out.append(normalize(piece))
                        else:
                            _emit(out, CANON.get(piece, piece) if piece in _PUNCT_SET or piece in CANON else SYM)
            elif kind == "s":
                continue
            elif kind == "e":
                out.append(m.group())
            elif kind == "n":
                out.append(NUM)
            else:
                c = m.group()
                c = CANON.get(c, c)
                _emit(out, c if c in _PUNCT_SET else SYM)
        return out

    _NO_SPACE_BEFORE = frozenset({".", ",", "?", "!", "…", ":", ";", ")", "%", "]"})
    _NO_SPACE_AFTER = frozenset({"(", "[", "$", "#", "@"})
    _RENDER = {NUM: "0", SYM: "¤"}  # both tokenize back to themselves

    def detokenize(tokens: Iterable[str]) -> str:
        """Join tokens into readable text; the inverse of ``tokenize`` on its own output."""
        parts: list[str] = []
        glue = False
        for tok in tokens:
            if tok in (BOS, EOS, PAD):
                continue
            shown = _RENDER.get(tok, tok)
            if parts and not glue and tok not in _NO_SPACE_BEFORE:
                parts.append(" ")
            parts.append(shown)
            glue = tok in _NO_SPACE_AFTER
        return "".join(parts)

    # ------------------------------------------------------------------ vocabulary

    class Tokenizer:
        """A fixed vocabulary: one token per line of ``vocab.txt``, id = line number."""

        def __init__(self, tokens: Sequence[str]):
            tokens = list(tokens)
            if tuple(tokens[: len(SPECIALS)]) != SPECIALS:
                raise ValueError(f"vocabulary must start with {SPECIALS}")
            if len(set(tokens)) != len(tokens):
                raise ValueError("vocabulary has duplicate tokens")
            self.tokens: list[str] = tokens
            self.ids: dict[str, int] = {t: i for i, t in enumerate(tokens)}
            self.pad_id, self.bos_id, self.eos_id, self.unk_id, self.num_id, self.sym_id = range(6)
            self._emoji_by_key: dict[str, str] = {}
            for t in tokens:
                if is_emoji(t):
                    self._emoji_by_key.setdefault(strip_presentation(t), t)
            self.word_ids: list[int] = [i for i, t in enumerate(tokens) if is_word(t)]
            self.emoji_ids: list[int] = [i for i, t in enumerate(tokens) if is_emoji(t)]
            self.punct_ids: list[int] = [i for i, t in enumerate(tokens) if t in _PUNCT_SET]

        @classmethod
        def load(cls, path: str | Path) -> "Tokenizer":
            with open(path, encoding="utf-8") as f:
                return cls([line.rstrip("\n") for line in f if line != "\n"])

        def save(self, path: str | Path) -> None:
            Path(path).parent.mkdir(parents=True, exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                for t in self.tokens:
                    f.write(t + "\n")

        def __len__(self) -> int:
            return len(self.tokens)

        @property
        def vocab_size(self) -> int:
            return len(self.tokens)

        def canonical(self, token: str) -> str:
            """Map an emoji typed with or without a variation selector onto the vocabulary's form."""
            if token in self.ids or not is_emoji(token):
                return token
            return self._emoji_by_key.get(strip_presentation(token), token)

        def tokens_of(self, text: str) -> list[str]:
            return [self.canonical(t) for t in tokenize(text)]

        def id_of(self, token: str) -> int:
            return self.ids.get(self.canonical(token), self.unk_id)

        def encode_tokens(self, tokens: Iterable[str], bos: bool = True, eos: bool = True) -> list[int]:
            ids = [self.id_of(t) for t in tokens]
            if bos:
                ids.insert(0, self.bos_id)
            if eos:
                ids.append(self.eos_id)
            return ids

        def encode(self, text: str, bos: bool = True, eos: bool = True) -> list[int]:
            return self.encode_tokens(tokenize(text), bos=bos, eos=eos)

        def decode(self, ids: Iterable[int]) -> list[str]:
            return [self.tokens[i] for i in ids]

        def text(self, ids: Iterable[int]) -> str:
            return detokenize(self.decode(ids))

        def is_word_id(self, i: int) -> bool:
            return is_word(self.tokens[i])
