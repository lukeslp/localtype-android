#!/usr/bin/env python3
"""Rebuild the MPL-2.0 TLD asset without changing recognized hosts. Luke Steuber."""
import argparse
import hashlib
import json
from pathlib import Path
import re


def generate(source, exclusions, pin):
    if hashlib.sha256(source).hexdigest() != pin['sha256']:
        raise ValueError('PSL source hash changed; review the pin before rebuilding')
    text = source.decode('utf-8')
    if 'Mozilla Public' not in text[:250] or pin['license'] != 'MPL-2.0':
        raise ValueError('Missing PSL license declaration')
    body = text.split('// ===BEGIN ICANN DOMAINS===')[1].split('// ===END ICANN DOMAINS===')[0]
    roots = set()
    for line in body.splitlines():
        rule = line.strip()
        if not rule or rule.startswith('//'):
            continue
        root = rule.lstrip('!*.').split('.')[-1].encode('idna').decode('ascii').lower()
        if not re.fullmatch('[a-z0-9-]{1,63}', root):
            raise ValueError('Invalid root label')
        roots.add(root)
    excluded = {line.strip() for line in exclusions.splitlines() if line.strip() and not line.startswith('#')}
    if excluded - roots:
        raise ValueError('Stale compatibility exclusion')
    rows = '\n'.join(sorted(roots - excluded)) + '\n'
    if len(roots - excluded) != pin['entries'] or hashlib.sha256(rows.encode()).hexdigest() != pin['membershipSha256']:
        raise ValueError('Recognized TLD membership changed')
    return ('# SPDX-License-Identifier: MPL-2.0\n'
            '# Derived from the Public Suffix List ICANN section; see licenses/tlds-NOTICE.txt.\n'
            '# Source commit: ' + pin['commit'] + '\n'
            '# Compatibility exclusions preserve LocalType 0.5.22 membership.\n' + rows).encode()


def build(root):
    inputs = root / 'distribution/source/tlds'
    return generate((inputs / 'public_suffix_list.dat').read_bytes(),
                    (inputs / 'exclusions.txt').read_text(),
                    json.loads((inputs / 'source.json').read_text()))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    data = build(args.root)
    target = args.root / 'app/src/main/assets/tlds.txt'
    if args.check:
        if target.read_bytes() != data:
            parser.exit(1, 'TLD asset differs from its pinned licensed source.\n')
        print('TLD asset matches its pinned MPL-2.0 source; 1437 entries unchanged.')
    else:
        target.write_bytes(data)
