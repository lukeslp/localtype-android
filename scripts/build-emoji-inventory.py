#!/usr/bin/env python3
"""Build LocalType's full emoji inventory from Unicode emoji-test.txt and CLDR English annotations.

Standard library only. Reads:
  emoji-test.txt                      (https://unicode.org/Public/emoji/latest/emoji-test.txt)
  CLDR common/annotations/en.xml      (names and keywords for single emoji and most sequences)
  CLDR common/annotationsDerived/en.xml (skin-tone variants, flags, keycaps, other derived sequences)

The sources aren't committed. To rebuild the shipped asset (Emoji 18.0, CLDR release-48-2), run
from the repo root:

  mkdir -p build/emoji-sources && cd build/emoji-sources
  curl -fsSLO https://www.unicode.org/Public/18.0.0/emoji/emoji-test.txt
  CLDR=https://raw.githubusercontent.com/unicode-org/cldr/release-48-2/common
  curl -fsSL -o annotations-en.xml $CLDR/annotations/en.xml
  curl -fsSL -o annotationsDerived-en.xml $CLDR/annotationsDerived/en.xml
  cd ../.. && python3 scripts/build-emoji-inventory.py

The same sources give the same bytes. For a newer release, fetch emoji-test.txt from
https://unicode.org/Public/emoji/latest/ and the en.xml files from the newest stable CLDR tag
(https://github.com/unicode-org/cldr/releases), pass that tag as --cldr-tag, and update the
versions named in the README and app/src/main/assets/licenses/Unicode-LICENSE.txt. CLDR names
lag new emoji by a release; those rows fall back to emoji-test.txt's name with no keywords, and
the script lists them.

Writes one UTF-8 asset:
  # comment lines (sources, licence, format)
  #groups<TAB>name0<TAB>name1...          group names in Unicode order; rows refer to them by index
  group<TAB>emoji<TAB>name<TAB>keywords<TAB>tones

  group     zero-based index into #groups
  emoji     the fully-qualified base sequence (FE0F kept exactly as emoji-test.txt has it)
  name      CLDR tts name; emoji-test.txt's name when CLDR has none
  keywords  CLDR keywords, lowercased, de-duplicated, '|' separated; empty when CLDR has none
  tones     single-tone variants in tone order light..dark (U+1F3FB..U+1F3FF), space separated

Rules: the Component group and minimally-qualified/unqualified forms are dropped; flags are kept.
A variant is a fully-qualified sequence that contains skin-tone modifiers. It belongs to the base
whose code points match once the modifiers and U+FE0F are removed. Only variants that use a
single tone (every person in the sequence the same) are kept; mixed-tone multi-person sequences
are counted and dropped.

Usage:
  python3 scripts/build-emoji-inventory.py [--emoji-test F] [--annotations F] [--derived F]
                                           [--cldr-tag TAG] [--out F]
The inputs default to build/emoji-sources/ (gitignored) as fetched above, the tag to
release-48-2, and the output to app/src/main/assets/emoji_inventory.txt.
"""

from __future__ import annotations

import argparse
import collections
import hashlib
import os
import re
import sys
import xml.etree.ElementTree as ET

TONES = [0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF]
TONE_SET = set(TONES)
VS16 = 0xFE0F
SKIPPED_GROUP = "Component"

LINE_RE = re.compile(
    r"^(?P<cps>[0-9A-F ]+?)\s*;\s*(?P<status>[a-z-]+)\s*#\s*(?P<emoji>\S+)\s+E(?P<ever>[0-9.]+)\s+(?P<name>.+)$"
)


def sha256(path: str) -> str:
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def parse_emoji_test(path: str):
    """Yield (group, subgroup, codepoints tuple, status, emoji version, name); also return the header."""
    header = {}
    rows = []
    group = subgroup = None
    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.rstrip("\n")
            if line.startswith("# Version:"):
                header["version"] = line.split(":", 1)[1].strip()
            elif line.startswith("# Date:"):
                header["date"] = line.split(":", 1)[1].strip()
            elif line.startswith("# group:"):
                group = line.split(":", 1)[1].strip()
            elif line.startswith("# subgroup:"):
                subgroup = line.split(":", 1)[1].strip()
            elif line and not line.startswith("#"):
                m = LINE_RE.match(line)
                if not m:
                    raise ValueError(f"unparsed emoji-test line: {line!r}")
                cps = tuple(int(h, 16) for h in m["cps"].split())
                emoji = "".join(map(chr, cps))
                if emoji != m["emoji"]:
                    raise ValueError(f"code points and comment disagree: {line!r}")
                rows.append((group, subgroup, cps, m["status"], m["ever"], m["name"].strip()))
    if "version" not in header:
        raise ValueError("emoji-test.txt has no '# Version:' header")
    return header, rows


def parse_annotations(*paths: str):
    """Map FE0F-stripped emoji -> (keywords list, tts name). Earlier files win on conflict."""
    keywords: dict[str, list[str]] = {}
    names: dict[str, str] = {}
    for path in paths:
        with open(path, "rb") as f:
            data = f.read()
        # Stdlib only, so no defusedxml: CLDR annotation files declare no entities, so refuse any that do.
        if b"<!ENTITY" in data:
            raise ValueError(f"{path} declares XML entities; refusing to parse")
        root = ET.fromstring(data)
        for el in root.iter("annotation"):
            cp = el.get("cp")
            text = (el.text or "").strip()
            if not cp or not text:
                continue
            key = cp.replace("\ufe0f", "")
            if el.get("type") == "tts":
                names.setdefault(key, text)
            else:
                keywords.setdefault(key, [k.strip() for k in text.split("|") if k.strip()])
    return keywords, names


def strip_key(cps) -> tuple:
    return tuple(c for c in cps if c not in TONE_SET and c != VS16)


def clean_keywords(words) -> list[str]:
    out, seen = [], set()
    for w in words:
        w = " ".join(w.lower().split())
        if not w or "|" in w or "\t" in w or w in seen:
            continue
        seen.add(w)
        out.append(w)
    return out


def main(argv=None) -> int:
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    sources = os.path.join(root, "build", "emoji-sources")
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--emoji-test", default=os.path.join(sources, "emoji-test.txt"))
    ap.add_argument("--annotations", default=os.path.join(sources, "annotations-en.xml"))
    ap.add_argument("--derived", default=os.path.join(sources, "annotationsDerived-en.xml"))
    ap.add_argument("--cldr-tag", default="release-48-2")
    ap.add_argument("--out", default=os.path.join(root, "app", "src", "main", "assets", "emoji_inventory.txt"))
    args = ap.parse_args(argv)

    header, rows = parse_emoji_test(args.emoji_test)
    kw_map, name_map = parse_annotations(args.annotations, args.derived)

    groups: list[str] = []
    bases = []  # dicts in file order
    base_by_key: dict[tuple, dict] = {}
    pending_variants = []
    stats = collections.Counter()

    for group, _sub, cps, status, ever, name in rows:
        if group == SKIPPED_GROUP:
            stats["skipped component"] += 1
            continue
        if status != "fully-qualified":
            stats[f"skipped {status}"] += 1
            continue
        tones_used = [c for c in cps if c in TONE_SET]
        if tones_used:
            pending_variants.append((group, cps, tones_used, name))
            continue
        if group not in groups:
            groups.append(group)
        key = strip_key(cps)
        if key in base_by_key:
            raise ValueError(f"two bases share a key: {name}")
        base = {"group": group, "cps": cps, "ever": ever, "test_name": name, "tones": {}}
        bases.append(base)
        base_by_key[key] = base

    orphans = []
    for group, cps, tones_used, name in pending_variants:
        if len(set(tones_used)) != 1:
            stats["dropped mixed-tone variant"] += 1
            continue
        base = base_by_key.get(strip_key(cps))
        if base is None:
            orphans.append(name)
            continue
        if base["group"] != group:
            raise ValueError(f"variant {name} is in {group}, base in {base['group']}")
        tone = tones_used[0]
        if tone in base["tones"]:
            raise ValueError(f"two {hex(tone)} variants for {base['test_name']}: {name}")
        base["tones"][tone] = "".join(map(chr, cps))
    if orphans:
        raise ValueError(f"single-tone variants with no base: {orphans[:10]} ({len(orphans)})")

    missing_name, missing_kw = [], []
    lines = []
    for b in bases:
        emoji = "".join(map(chr, b["cps"]))
        key = emoji.replace("\ufe0f", "")
        name = name_map.get(key)
        if name is None:
            name = b["test_name"]
            missing_name.append(f"E{b['ever']} {emoji} {name}")
        kws = kw_map.get(key)
        if kws is None:
            missing_kw.append(f"E{b['ever']} {emoji} {name}")
            kws = []
        tones = " ".join(b["tones"][t] for t in TONES if t in b["tones"])
        if b["tones"] and len(b["tones"]) != len(TONES):
            stats["bases with a partial tone set"] += 1
        for field in (emoji, name):
            if "\t" in field or "\n" in field:
                raise ValueError(f"tab or newline in field for {emoji}")
        lines.append(f"{groups.index(b['group'])}\t{emoji}\t{name}\t{'|'.join(clean_keywords(kws))}\t{tones}")

    total_variants = sum(len(b["tones"]) for b in bases)
    out = [
        "# LocalType emoji inventory, generated by scripts/build-emoji-inventory.py. Do not edit by hand.",
        f"# Unicode emoji-test.txt Version {header['version']} (Date: {header.get('date', '?')}), sha256 {sha256(args.emoji_test)[:16]}",
        f"# CLDR {args.cldr_tag} common/annotations/en.xml sha256 {sha256(args.annotations)[:16]},"
        f" common/annotationsDerived/en.xml sha256 {sha256(args.derived)[:16]}",
        "# Copyright Unicode, Inc. Unicode License v3 (SPDX: Unicode-3.0); see licenses/Unicode-LICENSE.txt.",
        f"# {len(bases)} base emoji, {total_variants} skin-tone variants.",
        "# Columns: group index<TAB>emoji<TAB>name<TAB>keywords (| separated)<TAB>tones (light..dark, space separated)",
        "#groups\t" + "\t".join(groups),
    ]
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(out + lines) + "\n")

    per_group = collections.Counter(b["group"] for b in bases)
    var_group = collections.Counter()
    for b in bases:
        var_group[b["group"]] += len(b["tones"])
    print(f"emoji-test Version {header['version']} ({header.get('date')}); CLDR {args.cldr_tag}")
    for i, g in enumerate(groups):
        print(f"  {i} {g:<20} bases {per_group[g]:>5}  variants {var_group[g]:>5}")
    print(f"total bases {len(bases)}, total variants {total_variants}, rows+variants {len(bases) + total_variants}")
    print(f"bases with tones {sum(1 for b in bases if b['tones'])}")
    for k, v in sorted(stats.items()):
        print(f"  {k}: {v}")
    print(f"no CLDR name ({len(missing_name)}): {missing_name}")
    print(f"no CLDR keywords ({len(missing_kw)}): {missing_kw}")
    print(f"wrote {args.out} ({os.path.getsize(args.out)} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
