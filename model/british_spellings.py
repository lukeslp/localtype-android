#!/usr/bin/env python3
"""American-to-British spellings for the keyboard's Spelling: British choice, from SCOWL's VarCon.

Writes ``app/src/main/assets/spellings_en_gb.txt``: one ``american<TAB>british`` pair per line ("color" and
"colour"), for words the English strip can offer. With British spelling chosen, the keyboard shows the British form
wherever it would have shown the American one; it never changes a word the person typed or taught.

A pair is kept only when all of these hold:

- VarCon gives the American word as the preferred American spelling (tag ``A``) and the other as the preferred
  British "ise" spelling (tag ``B``), so -ise, -our, -re, -ogue and doubled l all come through.
- The American word is never a British spelling in any other entry. That drops words whose meaning decides
  ("program" stays for software, "tire" is also the verb, "meter" is also the device, "check" is also the verb).
- The British word is never the preferred American spelling of something else.
- Both are plain lowercase letters and apostrophes, the American word is in the bundled suggestion lists
  (``words_en.txt`` or ``words_en_modern.txt``) and the British word is in ``words_known.txt``, so the corrector
  already accepts it.

Usage, from the repository root, with varcon.txt downloaded from
https://raw.githubusercontent.com/en-wl/wordlist/master/varcon/varcon.txt (VarCon 2020.12.07, SHA-256 below):

    python3 model/british_spellings.py path/to/varcon.txt
"""

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

ASSETS = Path("app/src/main/assets")
OUT = ASSETS / "spellings_en_gb.txt"
VARCON_SHA256 = "75af63da46ec12d7eb14b9f1ba8d3898d484dd6872755b73c921b215875a3629"
WORD = re.compile(r"[a-z]+(?:'[a-z]+)*")

# Read by hand after the first run. These are names or rare words VarCon happens to pair ("dora", "borg", "cesar"),
# or forms British writers use alongside the American one ("learned"/"learnt", "among"/"amongst",
# "inquire"/"enquire"), where swapping would push a style on the person.
DENY = set("among bel borg cesar dora pandora pe manila learned spilled spoiled inquire inquiries inquiry whirs".split())
# British in every sense, but dropped by the rule above because VarCon also lists a rare second sense
# ("gray", the unit of radiation dose).
ADD = {"gray": "grey", "mom": "mum"}


def entries(line: str) -> list[tuple[set[str], str]]:
    """The ``(tags, word)`` pairs of one VarCon line, without its trailing ``| note``."""
    body = line.split("|", 1)[0].strip()
    out = []
    for part in body.split(" / "):
        if ": " not in part:
            continue
        tags, word = part.split(": ", 1)
        out.append((set(tags.split()), word.strip()))
    return out


def words(path: Path) -> set[str]:
    out = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            out.add(line.split("\t", 1)[0])
    return out


def pairs(varcon: str, offerable: set[str], known: set[str]) -> list[tuple[str, str]]:
    candidates: dict[str, str] = {}
    british_anywhere: set[str] = set()
    american_anywhere: set[str] = set()
    for line in varcon.splitlines():
        if not line or line.startswith("#"):
            continue
        parsed = entries(line)
        for tags, word in parsed:
            if "B" in tags:
                british_anywhere.add(word)
            if "A" in tags:
                american_anywhere.add(word)
        american = [w for tags, w in parsed if "A" in tags]
        british = [w for tags, w in parsed if "B" in tags]
        if len(american) == 1 and len(british) == 1 and american[0] != british[0]:
            us, gb = american[0], british[0]
            # Two different British forms for one American word means the sense decides.
            if candidates.get(us, gb) != gb:
                candidates[us] = ""
            else:
                candidates[us] = gb
    kept = []
    for us, gb in ADD.items():
        if us in offerable and gb in known:
            kept.append((us, gb))
    for us, gb in candidates.items():
        if not gb or us in DENY or us in ADD or us in british_anywhere or gb in american_anywhere:
            continue
        if WORD.fullmatch(us) and WORD.fullmatch(gb) and us in offerable and gb in known:
            kept.append((us, gb))
    return sorted(kept)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    raw = Path(argv[1]).read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != VARCON_SHA256:
        print(f"varcon.txt SHA-256 is {digest}, expected {VARCON_SHA256}", file=sys.stderr)
        return 1
    offerable = words(ASSETS / "words_en.txt") | words(ASSETS / "words_en_modern.txt")
    known = words(ASSETS / "words_known.txt")
    kept = pairs(raw.decode("latin-1"), offerable, known)
    header = [
        "# American to British spellings, from SCOWL's VarCon 2020.12.07 (varcon.txt SHA-256 "
        f"{VARCON_SHA256}). Written by model/british_spellings.py; licence in licenses/VarCon-Copyright.txt.",
    ]
    OUT.write_text("\n".join(header + [f"{us}\t{gb}" for us, gb in kept]) + "\n", encoding="utf-8")
    print(f"{len(kept)} pairs -> {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
