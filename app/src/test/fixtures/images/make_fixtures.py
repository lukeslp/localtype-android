#!/usr/bin/env python3
"""Writes the image fixtures for ImageMetadataTest: tiny invented pictures carrying invented metadata.

Needs ImageMagick (`magick`), `cwebp`, `img2webp` and `webpmux` on PATH (Homebrew has them). Run from this
folder: `python3 make_fixtures.py`. Every location, camera name and comment here is made up.
"""
import struct
import subprocess
import tempfile
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent


def run(*args):
    subprocess.run(args, check=True)


def tiff(orientation, little=False):
    """TIFF with IFD0 (Make, Model, Orientation, DateTime, GPS pointer) and a GPS IFD near Greenwich."""
    e = '<' if little else '>'
    entries0 = []  # (tag, type, count, value bytes or offset placeholder)
    make = b'FixtureCam\0'
    model = b'Invented One\0'
    date = b'2026:11:25 09:30:00\0'
    gps_entries = 5
    ifd0_count = 5
    ifd0_size = 2 + 12 * ifd0_count + 4
    data_at = 8 + ifd0_size
    blobs = b''

    def blob(data):
        nonlocal blobs
        at = data_at + len(blobs)
        blobs += data + (b'\0' if len(data) % 2 else b'')
        return at

    make_at, model_at, date_at = blob(make), blob(model), blob(date)
    gps_ifd_at = data_at + len(blobs)
    gps_size = 2 + 12 * gps_entries + 4
    rationals_at = gps_ifd_at + gps_size
    lat = struct.pack(e + '6I', 51, 1, 28, 1, 3881, 100)
    lon = struct.pack(e + '6I', 0, 1, 0, 1, 1, 100)
    alt = struct.pack(e + '2I', 46, 1)

    def entry(tag, typ, count, value):
        return struct.pack(e + 'HHI', tag, typ, count) + value

    def short(v):
        return struct.pack(e + 'HH', v, 0)

    def long_(v):
        return struct.pack(e + 'I', v)

    ifd0 = struct.pack(e + 'H', ifd0_count)
    ifd0 += entry(0x010F, 2, len(make), long_(make_at))
    ifd0 += entry(0x0110, 2, len(model), long_(model_at))
    ifd0 += entry(0x0112, 3, 1, short(orientation))
    ifd0 += entry(0x0132, 2, len(date), long_(date_at))
    ifd0 += entry(0x8825, 4, 1, long_(gps_ifd_at))
    ifd0 += long_(0)
    gps = struct.pack(e + 'H', gps_entries)
    gps += entry(0x0001, 2, 2, b'N\0\0\0')
    gps += entry(0x0002, 5, 3, long_(rationals_at))
    gps += entry(0x0003, 2, 2, b'W\0\0\0')
    gps += entry(0x0004, 5, 3, long_(rationals_at + 24))
    gps += entry(0x0006, 5, 1, long_(rationals_at + 48))
    gps += long_(0)
    header = (b'II' if little else b'MM') + struct.pack(e + 'HI', 42, 8)
    return header + ifd0 + blobs + gps + lat + lon + alt


XMP = (b'<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
       b'<rdf:Description xmlns:exif="http://ns.adobe.com/exif/1.0/" exif:GPSLatitude="51,28.6467N" '
       b'exif:GPSLongitude="0,0.0100W"/></rdf:RDF></x:xmpmeta>')


def jpeg_segment(marker, payload):
    return bytes([0xFF, marker]) + struct.pack('>H', len(payload) + 2) + payload


def make_jpeg(base_png, out):
    with tempfile.TemporaryDirectory() as tmp:
        plain = Path(tmp) / 'plain.jpg'
        run('magick', str(base_png), '-strip', '-quality', '90', str(plain))
        b = plain.read_bytes()
    assert b[:2] == b'\xff\xd8'
    pos = 2
    # Keep magick's JFIF APP0 first, if it wrote one.
    if b[2:4] == b'\xff\xe0':
        pos = 4 + struct.unpack('>H', b[4:6])[0]
    meta = jpeg_segment(0xE1, b'Exif\0\0' + tiff(6))
    meta += jpeg_segment(0xE1, b'http://ns.adobe.com/xap/1.0/\0' + XMP)
    iptc = b'\x1c\x02\x5a' + struct.pack('>H', 6) + b'London'  # 2:90 City
    meta += jpeg_segment(0xED, b'Photoshop 3.0\0' + b'8BIM' + struct.pack('>H', 0x0404) + b'\0\0' + struct.pack('>I', len(iptc)) + iptc + b'\0')
    meta += jpeg_segment(0xFE, b'Invented comment: taken at the fixture office')
    out.write_bytes(b[:pos] + meta + b[pos:] + b'TRAILER after EOI with invented data')


def png_chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xFFFFFFFF)


def make_png(base_png, out):
    with tempfile.TemporaryDirectory() as tmp:
        plain = Path(tmp) / 'plain.png'
        run('magick', str(base_png), '-strip', '-define', 'png:exclude-chunks=date,time', f'PNG24:{plain}')
        b = plain.read_bytes()
    chunks = []
    pos = 8
    while pos < len(b):
        n = struct.unpack('>I', b[pos:pos + 4])[0]
        chunks.append((b[pos + 4:pos + 8], b[pos:pos + 12 + n]))
        pos += 12 + n
    extra = png_chunk(b'tEXt', b'Comment\0Invented comment near 51.4775N')
    extra += png_chunk(b'zTXt', b'Author\0\0' + zlib.compress(b'Invented Author'))
    extra += png_chunk(b'iTXt', b'XML:com.adobe.xmp\0\0\0\0\0' + XMP)
    extra += png_chunk(b'tIME', struct.pack('>HBBBBB', 2026, 11, 25, 9, 30, 0))
    extra += png_chunk(b'eXIf', tiff(3, little=True))
    out_bytes = b[:8]
    for kind, raw in chunks:
        if kind == b'IDAT' and extra:
            out_bytes += extra
            extra = b''
        out_bytes += raw
    out.write_bytes(out_bytes + b'trailing data after IEND')


def make_gif(out):
    with tempfile.TemporaryDirectory() as tmp:
        plain = Path(tmp) / 'anim.gif'
        run('magick', '-delay', '20', '-size', '8x8', 'xc:red', 'xc:blue', 'xc:lime', '-loop', '0', str(plain))
        b = plain.read_bytes()
    packed = b[10]
    pos = 13 + (3 * (1 << ((packed & 7) + 1)) if packed & 0x80 else 0)
    text = b'Invented comment in the GIF'
    comment = b'\x21\xfe' + bytes([len(text)]) + text + b'\0'
    xmp = b'\x21\xff\x0bXMP DataXMP' + bytes([len(XMP[:200])]) + XMP[:200] + b'\0'
    out.write_bytes(b[:pos] + comment + xmp + b[pos:] + b'after the trailer')


def make_webp(base_png, frames, out_still, out_anim):
    with tempfile.TemporaryDirectory() as tmp:
        t = Path(tmp)
        (t / 'exif.bin').write_bytes(tiff(6, little=True))
        (t / 'xmp.xml').write_bytes(XMP)
        run('cwebp', '-quiet', '-q', '80', str(base_png), '-o', str(t / 'base.webp'))
        run('webpmux', '-set', 'exif', str(t / 'exif.bin'), str(t / 'base.webp'), '-o', str(t / 'e.webp'))
        run('webpmux', '-set', 'xmp', str(t / 'xmp.xml'), str(t / 'e.webp'), '-o', str(out_still))
        run('img2webp', '-loop', '0', '-lossless', '-d', '200', *map(str, frames), '-o', str(t / 'anim.webp'))
        run('webpmux', '-set', 'exif', str(t / 'exif.bin'), str(t / 'anim.webp'), '-o', str(out_anim))


def main():
    with tempfile.TemporaryDirectory() as tmp:
        t = Path(tmp)
        base = t / 'base.png'
        run('magick', '-size', '16x12', 'gradient:#c03020-#2040c0', str(base))
        frames = []
        for i, color in enumerate(['red', 'blue', 'lime']):
            f = t / f'f{i}.png'
            run('magick', '-size', '8x8', f'xc:{color}', str(f))
            frames.append(f)
        make_jpeg(base, HERE / 'gps_orientation.jpg')
        make_png(base, HERE / 'text_chunks.png')
        make_gif(HERE / 'animated_comment.gif')
        make_webp(base, frames, HERE / 'exif_xmp.webp', HERE / 'animated_exif.webp')


if __name__ == '__main__':
    main()
