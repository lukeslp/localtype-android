#!/usr/bin/env python3
"""Build LocalType's opt-in word packs from pinned public-domain and CC0 sources. Luke Steuber.

Two steps, both with the Python standard library only.

``--extract DIR`` reads the raw downloads under DIR (laid out as ``distribution/source/packs/sources.json`` names
them, each checked against its SHA-256) and writes the small extracts beside that file: ``medical.tsv`` and
``politics.tsv``, one candidate word per line with its kind, a weight and where it came from. The raw downloads are
kept on Galactus, not in the repository.

The default step builds ``app/src/main/assets/pack_medical_en.txt`` and ``pack_politics_en.txt`` from the extracts and
``offices.txt`` (a short hand-written list of office and procedure words). It leaves out everyday words (the
suggestion list ``words_en.txt`` and ``words_en_modern.txt``), slurs (the digests in ``SuggestionWordRules.kt``, whole
word and each hyphenated part, as ``BundledWordListsTest`` checks), words under four letters, and drug, term and office
words one slip away from one of the commonest everyday words, so a slip on that word is still corrected while the pack
is on. ``--check`` fails when an asset differs from what the extracts make.
"""
import argparse
import csv
import hashlib
import html
import io
import json
import math
import re
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / 'distribution/source/packs'
ASSETS = ROOT / 'app/src/main/assets'
RULES = ROOT / 'engine/src/commonMain/kotlin/dev/localtype/keyboard/SuggestionWordRules.kt'

OBSERVED = '2026-11-13'
FORMAT = '# LocalType word pack v1'
MIN_LETTERS = 4
MAX_LETTERS = 30
# An everyday word scoring this or more (WordDictionary's log scale, 0..100) keeps a one-slip pack word out.
COMMON_SCORE = 60
# Letters of any script (names keep their accents: "Barragán"), with inner hyphens and apostrophes.
TOKEN = re.compile(r"[^\W\d_](?:[^\W\d_]|['’-](?=[^\W\d_]))*")
SHAPE = re.compile(r"[^\W\d_](?:[^\W\d_]|['-])*[^\W\d_]")
# FDA's four-letter suffix on a biologic's name ("infliximab-dyyb"): the name without it is the word people type.
BIOLOGIC_SUFFIX = re.compile(r"^([^\W\d_]{6,})-[a-z]{4}$")
NAME_SUFFIXES = {'jr', 'sr', 'ii', 'iii', 'iv'}


# Reading the raw downloads

def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def tokens(text: str):
    """Words of letters, with inner hyphens and apostrophes; a possessive 's is dropped ("Alzheimer's" → Alzheimer)."""
    for m in TOKEN.finditer(text):
        word = m.group(0).replace('’', "'")
        if word.lower().endswith("'s"):
            word = word[:-2]
        word = word.strip("'-")
        suffixed = BIOLOGIC_SUFFIX.match(word)
        if suffixed:
            word = suffixed.group(1)
        if len(word) >= 2:
            yield word


def rxnorm(path: Path):
    """Ingredient, precise-ingredient and brand names from RXNCONSO, the RXNORM source only, nothing suppressed."""
    out = []
    with zipfile.ZipFile(path) as z, z.open('rrf/RXNCONSO.RRF') as f:
        for line in io.TextIOWrapper(f, 'utf-8'):
            p = line.rstrip('\n').split('|')
            sab, tty, text, suppress = p[11], p[12], p[14], p[16]
            if sab == 'RXNORM' and tty in ('IN', 'PIN', 'BN') and suppress == 'N':
                out.append((tty, text))
    return out


def part_d_claims(path: Path) -> Counter:
    """Total 2024 Part D claims for every word of a drug's brand or generic name, from the overall (all-manufacturer) rows."""
    claims = Counter()
    with path.open(encoding='utf-8-sig', newline='') as f:
        for row in csv.DictReader(f):
            if row['Mftr_Name'] != 'Overall':
                continue
            n = int(float(row['Tot_Clms_2024'] or 0))
            for word in {w.lower() for w in tokens(row['Brnd_Name'] + ' ' + row['Gnrc_Name'])}:
                claims[word] += n
    return claims


def medlineplus(path: Path):
    """English health topics: words of titles, also-called names and see-references, and of the topic summaries."""
    import xml.etree.ElementTree as ET
    with zipfile.ZipFile(path) as z:
        data = z.read(z.namelist()[0])
    # A downloaded file: its DOCTYPE only names NLM's DTD (expat never fetches it); refuse any entity declaration or
    # internal subset, so nothing is ever expanded.
    doctype = re.search(rb'<!DOCTYPE[^>]*>', data[:4096])
    if b'<!ENTITY' in data or (doctype and b'[' in doctype.group(0)):
        raise SystemExit(f'{path.name}: unexpected entity declaration')
    root = ET.fromstring(data)
    named, summary, forms = Counter(), Counter(), defaultdict(Counter)
    for topic in root.findall('health-topic'):
        if topic.get('language') != 'English':
            continue
        names = [topic.get('title', '')] + [e.text or '' for e in topic.findall('also-called')] + \
            [e.text or '' for e in topic.findall('see-reference')]
        for word in {w for n in names for w in tokens(n)}:
            named[word.lower()] += 1
        text = re.sub(r'<[^>]+>', ' ', html.unescape(topic.findtext('full-summary') or ''))
        seen = set()
        for sentence in re.split(r'(?<=[.!?])\s+', text):
            for i, word in enumerate(tokens(sentence)):
                # A capital that only opens a sentence says nothing about the word's own spelling.
                if i > 0 or word.islower():
                    forms[word.lower()][word] += 1
                seen.add(word.lower())
        for word in seen:
            summary[word] += 1
    return named, summary, forms


def senate_glossary(path: Path):
    """Headwords and definition words of the Senate's glossary page."""
    page = path.read_text(encoding='utf-8')
    heads = [html.unescape(re.sub(r'<[^>]+>', '', h)) for h in
             re.findall(r'<span style="font-weight:bold; color: #2f7faa">(.*?)</span>', page, re.S)]
    defs = [html.unescape(re.sub(r'<[^>]+>', ' ', d)) for d in re.findall(r'<definition>(.*?)</definition>', page, re.S)]
    return heads, defs


def legislators(path: Path):
    """Names of every current member of Congress (unitedstates/congress-legislators, legislators-current)."""
    people = json.loads(path.read_text(encoding='utf-8'))
    return [' '.join(filter(None, [p['name'].get('official_full'), p['name'].get('first'), p['name'].get('last'),
                                   p['name'].get('nickname')])) for p in people]


def wikidata(path: Path):
    """Names of the current human holders of the executive, cabinet, Supreme Court and governor positions queried."""
    rows = json.loads(path.read_text(encoding='utf-8'))['results']['bindings']
    names = set()
    for row in rows:
        label = row['personLabel']['value']
        if re.fullmatch(r'Q\d+', label):
            label = ' '.join(row.get(k, {}).get('value', '') for k in ('given', 'family'))
        names.add(label)
    return sorted(names)


def extract(raw: Path):
    pins = json.loads((SOURCES / 'sources.json').read_text())
    files = {}
    for key, source in pins['sources'].items():
        if 'file' not in source:
            continue
        path = raw / source['file']
        if sha256(path) != source['sha256']:
            raise SystemExit(f'{source["file"]}: SHA-256 differs from sources.json; review the new download before rebuilding')
        files[key] = path

    # Medical: drug names from RxNorm (weighted by Part D claims), terms from MedlinePlus topics.
    claims = part_d_claims(files['cms-part-d'])
    medical = {}
    for tty, text in rxnorm(files['rxnorm']):
        words = list(tokens(text))
        for i, word in enumerate(words):
            key = word.lower()
            # A name of one word, a brand's first word, or a word of a longer name that Part D has claims for:
            # chemical fragments of long ingredient names ("alkyldimethylamine") stay out.
            if not (len(words) == 1 or (tty == 'BN' and i == 0) or claims.get(key, 0) > 0):
                continue
            if tty == 'BN':
                display = word if not word.islower() else word.capitalize()
                origin = 'rxnorm-bn'
            else:
                display, origin = key, 'rxnorm-' + tty.lower()
            current = medical.get(key)
            # An ingredient's lowercase spelling wins over a brand's capitals for the same word.
            if current is None or (current[3] == 'rxnorm-bn' and origin != 'rxnorm-bn'):
                medical[key] = (display, 'drug', claims.get(key, 0), origin)
    named, summary, forms = medlineplus(files['medlineplus'])
    for key in set(named) | {w for w, n in summary.items() if n >= 3}:
        if key in medical:
            continue
        # Lowercase unless the summaries only ever capitalize it, three times or more ("Tourette", "Lyme").
        spelled = forms.get(key)
        display = key if not spelled or key in spelled or spelled.most_common(1)[0][1] < 3 else spelled.most_common(1)[0][0]
        medical[key] = (display, 'term', named.get(key, 0) * 4 + summary.get(key, 0), 'medlineplus')

    # Politics: glossary words, members of Congress, and the other officeholders.
    politics = {}
    heads, defs = senate_glossary(files['senate-glossary'])
    head_words = Counter(w.lower() for h in heads for w in tokens(h))
    def_words = Counter(w.lower() for d in defs for w in set(tokens(d)) if w.islower())
    for key in set(head_words) | {w for w, n in def_words.items() if n >= 2}:
        politics[key] = (key, 'office', head_words[key] * 4 + def_words[key], 'glossary')
    people = [(n, 'congress-legislators') for n in legislators(files['congress-legislators'])] + \
        [(n, 'wikidata') for n in wikidata(files['wikidata'])]
    for name, origin in people:
        for word in tokens(name):
            key = word.lower()
            if key in NAME_SUFFIXES or len(word) < 2 or (key in politics and politics[key][1] == 'office'):
                continue
            # Names keep their capitals ("McConnell", "DeSantis", "Ocasio-Cortez").
            if key not in politics:
                politics[key] = (word, 'officeholder', 1, origin)
            else:
                d, k, n, o = politics[key]
                politics[key] = (d, k, n + 1, o)

    for name, rows in (('medical', medical), ('politics', politics)):
        with (SOURCES / f'{name}.tsv').open('w', encoding='utf-8') as f:
            f.write(f'# Extract for pack_{name}_en.txt, made by scripts/build-word-packs.py --extract from the files in sources.json.\n')
            f.write('# key\tdisplay\tkind\tweight\torigin\n')
            for key in sorted(rows):
                display, kind, weight, origin = rows[key]
                f.write(f'{key}\t{display}\t{kind}\t{weight}\t{origin}\n')
        print(f'{name}.tsv: {len(rows)} candidates')


# Building the packs

def digests() -> set:
    source = RULES.read_text(encoding='utf-8')
    out = set()
    for block in re.finditer(r'EXCLUDED_HASHES\w*: Set<String> = """(.*?)"""', source, re.S):
        out |= set(block.group(1).split())
    if not out:
        raise SystemExit(f'no digests found in {RULES}')
    return out


def slur(word: str, hashes: set) -> bool:
    return any(hashlib.sha256(p.encode()).hexdigest() in hashes for p in {word, *word.split('-')} if p)


def everyday():
    """The suggestion list's words and their WordDictionary scores (ln(count + 1) / ln(max + 1) × 100), modern list included."""
    counts = {}
    for name in ('words_en.txt', 'words_en_modern.txt'):
        for line in (ASSETS / name).read_text(encoding='utf-8').splitlines():
            if not line or line.startswith('#') or '\t' not in line:
                continue
            word, count = line.split('\t', 1)
            counts.setdefault(word.lower(), int(count.strip()))
    top = math.log(max(counts.values()) + 1)
    return {w: round(math.log(n + 1) / top * 100) for w, n in counts.items()}


def one_slip(word: str):
    """Every spelling one deletion, insertion, substitution or swap of neighbors away."""
    letters = 'abcdefghijklmnopqrstuvwxyz'
    for i in range(len(word) + 1):
        if i < len(word):
            yield word[:i] + word[i + 1:]
            if i + 1 < len(word):
                yield word[:i] + word[i + 1] + word[i] + word[i + 2:]
            for c in letters:
                if c != word[i]:
                    yield word[:i] + c + word[i + 1:]
        for c in letters:
            yield word[:i] + c + word[i:]


def ordinary(word: str, common: dict) -> bool:
    """An everyday word with an ending ("committees", "behaviors", "limiting"), or a compound of everyday words ("long-term")."""
    for suffix, stem in (('ies', 'y'), ('es', ''), ('s', ''), ('ed', ''), ('ed', 'e'), ('ing', ''), ('ing', 'e'), ('ly', '')):
        if word.endswith(suffix) and len(word) > len(suffix) + 2:
            base = word[:-len(suffix)] + stem
            if base in common or (len(base) > 3 and base[-1] == base[-2] and base[:-1] in common):
                return True
    parts = word.split('-')
    return len(parts) > 1 and all(p in common for p in parts)


def score(kind: str, weight: int) -> int:
    """Editorial scores on the shared vocabulary's 0..60 scale, below the commonest everyday words."""
    if kind == 'drug':
        # Ten million Part D claims a year → 50; a thousand or none → 34. Claims order drugs; they aren't usage counts of text.
        return max(34, min(52, round(22 + 4 * math.log10(weight)))) if weight > 0 else 32
    if kind == 'term':
        return max(34, min(44, 32 + weight // 2))
    if kind == 'office':
        return max(34, min(44, 32 + weight))
    return 40  # officeholder


def header(pack: str, pins: dict) -> str:
    meta = pins['packs'][pack]
    prefixes = ','.join(f'{kind}:{n}' for kind, n in meta['minPrefix'].items())
    lines = [FORMAT, f'# id={pack}', '# language=en', f'# name={meta["name"]}', f'# observed={OBSERVED}',
             f'# min-prefix={prefixes}',
             '# key<TAB>display<TAB>score<TAB>kind. Scores are editorial (0..60), not measured frequencies.',
             '# min-prefix: letters typed before a kind is completed' + (' (drug names from five, after ECRI/ISMP guidance).' if 'drug' in meta['minPrefix'] else '.'),
             '# Officeholders fade from completion 60 days after observed (half) and stop at 120; they stay recognized.',
             '# Built by scripts/build-word-packs.py from distribution/source/packs/. Sources (license page; date read):']
    for key in meta['sources']:
        s = pins['sources'][key]
        lines.append(f'# - {s["name"]}: {s["url"]} ({s["license"]}; {s["licensePage"]}; read {s["read"]})')
    for note in meta.get('notes', []):
        lines.append('# ' + note)
    return '\n'.join(lines) + '\n'


def build():
    pins = json.loads((SOURCES / 'sources.json').read_text())
    hashes = digests()
    common = everyday()
    shadow = set()
    for word, s in common.items():
        if s >= COMMON_SCORE:
            shadow.update(one_slip(word))
    outputs, report = {}, {}
    for pack in ('medical', 'politics'):
        rows = []
        for line in (SOURCES / f'{pack}.tsv').read_text(encoding='utf-8').splitlines():
            if line and not line.startswith('#'):
                key, display, kind, weight, origin = line.split('\t')
                rows.append((key, display, kind, int(weight), origin))
        if pack == 'politics':
            for line in (SOURCES / 'offices.txt').read_text(encoding='utf-8').splitlines():
                if line and not line.startswith('#'):
                    rows.append((line.lower(), line, 'office', 6, 'offices'))
        kept, dropped = {}, Counter()
        for key, display, kind, weight, origin in rows:
            letters = sum(c.isalpha() for c in key)
            if not SHAPE.fullmatch(key) or key != key.lower() or not MIN_LETTERS <= letters <= MAX_LETTERS:
                dropped['shape'] += 1
            elif key in common:
                dropped['everyday'] += 1
            elif kind in ('term', 'office') and origin != 'offices' and ordinary(key, common):
                dropped['everyday word with an ending'] += 1
            elif slur(key, hashes):
                dropped['excluded'] += 1
            elif kind != 'officeholder' and key in shadow:
                dropped['one slip from a common word'] += 1
            elif display.lower() != key:
                dropped['display'] += 1
            else:
                old = kept.get(key)
                entry = (display, score(kind, weight), kind)
                if old is None or (old[1], old[2] != 'officeholder') < (entry[1], kind != 'officeholder'):
                    kept[key] = entry
        body = ''.join(f'{k}\t{d}\t{s}\t{kind}\n' for k, (d, s, kind) in sorted(kept.items()))
        outputs[f'pack_{pack}_en.txt'] = (header(pack, pins) + body).encode('utf-8')
        report[pack] = (len(kept), Counter(v[2] for v in kept.values()), dropped)
    return outputs, report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--extract', type=Path, metavar='DIR', help='rebuild the extracts from the raw downloads in DIR')
    parser.add_argument('--check', action='store_true', help='fail when an asset differs from what the extracts make')
    args = parser.parse_args()
    if args.extract:
        extract(args.extract)
    outputs, report = build()
    for pack, (n, kinds, dropped) in report.items():
        print(f'{pack}: {n} words {dict(kinds)}; left out {dict(dropped)}')
    if args.check:
        stale = [name for name, data in outputs.items() if (ASSETS / name).read_bytes() != data]
        if stale:
            sys.exit('Packs differ from their pinned sources: ' + ', '.join(stale))
        print('Packs match their pinned sources.')
    else:
        for name, data in outputs.items():
            (ASSETS / name).write_bytes(data)
