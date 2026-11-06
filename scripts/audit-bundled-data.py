#!/usr/bin/env python3
"""Check the exact data files proposed for narrow F-Droid scan exceptions. Luke Steuber."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import struct
import zlib

DATA = ('cifg_int8.bin', 'corpus_prior.bin', 'corpus_prior_es.bin', 'corpus_prior_pt.bin', 'swipe_en.bin')


def crc(data, endian):
    if zlib.crc32(data[:-4]) != struct.unpack(endian + 'I', data[-4:])[0]:
        raise ValueError('CRC mismatch')


def describe(name, data):
    """Validate documented layouts; never execute the file or train a model."""
    if name == 'cifg_int8.bin':
        magic, version, vocab, embed, hidden, n_vocab = struct.unpack_from('<4s5I', data)
        if magic != b'LTCF' or version != 1 or not (0 < vocab <= 65535 and 0 < embed <= 4096 and 0 < hidden <= 4096):
            raise ValueError('Unsupported CIFG header')
        crc(data, '<')
        size = 24
        for rows, cols in ((vocab, embed), (embed, 3 * hidden), (embed, 3 * hidden), (hidden, embed)):
            size += 4 * rows + rows * cols + (-rows * cols) % 4
        size += 12 * hidden + n_vocab + 4
        if size != len(data):
            raise ValueError('CIFG length mismatch')
        tokens = data[-4-n_vocab:-4].decode('utf-8').splitlines()
        if len(tokens) != vocab or any(not token for token in tokens):
            raise ValueError('CIFG vocabulary mismatch')
        return {'format': 'LTCF v1', 'vocabulary': vocab, 'embedding': embed, 'hidden': hidden, 'crc': 'valid'}
    if name == 'swipe_en.bin':
        magic, version, hidden, layers, points, features, classes = struct.unpack_from('<4s6i', data)
        if magic != b'LTSW' or version != 1 or not (0 < hidden <= 1024 and 0 < layers <= 8 and 2 <= points <= 512) or (features, classes) != (31, 27):
            raise ValueError('Unsupported swipe header')
        floats = hidden * features + hidden + classes * 2 * hidden + classes
        for layer in range(layers):
            inputs = hidden if layer == 0 else 2 * hidden
            floats += 2 * (3 * hidden * inputs + 3 * hidden * hidden + 4 * hidden)
        if 28 + 4 * floats != len(data) or any(not math.isfinite(x[0]) for x in struct.iter_unpack('<f', data[28:])):
            raise ValueError('Swipe weight layout mismatch')
        return {'format': 'LTSW v1', 'hidden': hidden, 'layers': layers, 'points': points, 'vocabularyEmbedded': False}
    if name in ('corpus_prior.bin', 'corpus_prior_es.bin', 'corpus_prior_pt.bin'):
        magic, version, flags, length = struct.unpack_from('>4sHHI', data)
        if (magic, version, flags) != (b'LTCP', 1, 0):
            raise ValueError('Unsupported prior header')
        crc(data, '>')
        metadata = json.loads(data[12:12+length].decode('utf-8'))
        at = 12 + length
        words = struct.unpack_from('>I', data, at)[0]; at += 4
        for _ in range(words):
            n = data[at]; at += 1
            data[at:at+n].decode('utf-8'); at += n
        at += 8 + words  # quantization scale and unigram codes
        contexts = struct.unpack_from('>I', data, at)[0]; at += 12
        followers = 0
        for _ in range(contexts):
            word, count = struct.unpack_from('>HB', data, at); at += 3
            if word >= words:
                raise ValueError('Prior context index out of bounds')
            followers += count
        if at + 3 * followers + 4 != len(data):
            raise ValueError('Prior length mismatch')
        for _ in range(followers):
            word, _ = struct.unpack_from('>HB', data, at); at += 3
            if word >= words:
                raise ValueError('Prior follower index out of bounds')
        return {'format': 'LTCP v1', 'words': words, 'contexts': contexts, 'followers': followers,
                'crc': 'valid', 'metadata': metadata}
    raise ValueError('Unreviewed data file: ' + name)


def audit(root):
    inventory = root / 'ASSET-LICENSES.json'
    if not inventory.exists():
        inventory = root / 'distribution/source/asset-licenses.json'
    assets = json.loads(inventory.read_text())
    result = {}
    for name in DATA:
        entry = assets[name]
        data = (root / 'app/src/main/assets' / name).read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        if digest != entry['sha256'] or entry['status'] not in ('existing-grant', 'reviewed-grant'):
            raise ValueError('Unreviewed or changed data: ' + name)
        notice = root / 'app/src/main/assets' / entry['notice']
        if not notice.is_file():
            raise ValueError('Missing notice: ' + name)
        result[name] = dict(sha256=digest, bytes=len(data), license=entry['license'], **describe(name, data))
    return {'data': result, 'catalogApproval': False,
            'pendingAssets': [name for name, entry in assets.items()
                              if entry['status'] not in ('existing-grant', 'reviewed-grant')]}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--require-cleared', action='store_true')
    args = parser.parse_args()
    report = audit(args.root)
    print(json.dumps(report, indent=2))
    if args.require_cleared and report['pendingAssets']:
        parser.exit(1, 'Unresolved asset rights remain; keep the catalog build disabled.\n')
