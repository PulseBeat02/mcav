"""Generate MCV2 edge-case conformance streams with the reference serializer and decoder.

Run with the gpu-codec reference checkout on the path (the research repository mcav ports MCV2 from):

    python tools/mcv2/edge_streams.py <gpu-codec checkout> <output directory> [seed]

Every stream is built with the reference's own `v2.pack_frame`, from random block trees that cover every leaf mode,
every compact class and motion form, quantizers up to 7, frames whose edges crop partial blocks, and every index form
(derived offsets with all tables, stored short and wide indexes, sparse children, immediate motion, the wide
fallback). The reference decoder decodes each stream; the per-frame SHA-256 of its RGB output goes into
`digests.json` next to the streams. A second group of frames uses syntax mcav deliberately does not implement (MCV1,
the coarse palettes of round 3, the motion table of round 15): the reference accepts them and `rejected.json` lists
them so the Java tests can prove they are rejected. Streams are research archives: u32 length, then the frame.
"""

import hashlib
import json
import random
import struct
import sys
from pathlib import Path

sys.path.insert(0, sys.argv[1])

import numpy as np  # noqa: E402
from mcvideo import format as fmt  # noqa: E402
from mcvideo.compact import BODY_BYTES, COMPACT  # noqa: E402
from mcvideo.decoder import Decoder  # noqa: E402
from mcvideo.pattern import PATTERN_PALETTE  # noqa: E402
from mcvideo.v2 import SPLIT, Node, pack_frame  # noqa: E402

OUT = Path(sys.argv[2])
SEED = int(sys.argv[3]) if len(sys.argv) > 3 else 20260925
RANDOM = random.Random(SEED)


def rbytes(n, low=0, high=255):
    return bytes(RANDOM.randint(low, high) for _ in range(n))


def compact_record(q):
    kind = RANDOM.choice(range(9))
    if kind == 7:
        q = 0
    form = RANDOM.choice((0, 1, 2))
    prefix = b"" if form == 0 else (rbytes(1) if form == 1 else bytes([RANDOM.randint(0, 255), RANDOM.randint(0, 255)]))
    body = bytearray(rbytes(BODY_BYTES[kind]))
    if kind == 5:
        body[-1] &= 63
    if kind == 6:
        body[-1] &= 15
    if kind == 7:
        body[0] = RANDOM.randint(0, 64) - 32 & 255
    return q, bytes([kind | form << 4]) + prefix + bytes(body)


def safe565(pair):
    """Endpoints whose low bits replicate their high bits, so they survive the RGB565 round trip exactly."""
    return bytes(c & 0xF8 | c >> 5 if i % 3 != 1 else c & 0xFC | c >> 6 for i, c in enumerate(pair))


WORDS = {size: [bytes([RANDOM.randint(0, 1)]) + rbytes(size // 8) for _ in range(3)] for size in (8, 16, 32)}


def pattern_record(size):
    word = RANDOM.choice(WORDS[size]) if RANDOM.random() < 0.8 else bytes([RANDOM.randint(0, 1)]) + rbytes(size // 8)
    return rbytes(6) + word


BIAS = {"skip": 0.0, "pattern": 0.0}


def leaf(size, key, pattern_endpoints=None, coarse=False):
    """A random valid leaf of this size; temporal modes only on P frames."""
    if not key and RANDOM.random() < BIAS["skip"]:
        return Node(0)
    if RANDOM.random() < BIAS["pattern"]:
        return Node(PATTERN_PALETTE, 0, pattern_record(size) if not coarse else safe565(rbytes(6)) + pattern_record(size)[6:])
    modes = [2, 3, 4, 5, 6, 7, 12, 14, PATTERN_PALETTE]
    if not key:
        modes += [0, 1, 8, 9, 10, 11, 13, 15, COMPACT, COMPACT, COMPACT]
    mode = RANDOM.choice(modes)
    q = RANDOM.randint(0, 7) if fmt.is_residual(mode) else 0
    if mode == COMPACT:
        q, record = compact_record(RANDOM.randint(0, 7))
        return Node(mode, q, record)
    if mode == PATTERN_PALETTE:
        record = pattern_record(size)
        if pattern_endpoints and RANDOM.random() < 0.7:
            record = RANDOM.choice(pattern_endpoints) + record[6:]
        if coarse:
            record = safe565(record[:6]) + record[6:]
        return Node(mode, 0, record)
    if mode == 0:
        return Node(0)
    return Node(mode, q, rbytes(fmt.record_size(mode, size)))


def tree(size, key, split_chance, endpoints, coarse):
    if size > 8 and RANDOM.random() < split_chance:
        return Node(SPLIT, children=tuple(tree(size // 2, key, split_chance * 0.7, endpoints, coarse) for _ in range(4)))
    return leaf(size, key, endpoints, coarse)


FAILURES = []


def frame(width, height, frame_id, reference_id, key, split_chance, options, motion=None):
    """A random frame; a tree the reference serializer cannot write is recorded and drawn again."""
    for _ in range(100):
        try:
            return frame_once(width, height, frame_id, reference_id, key, split_chance, options, motion)
        except ValueError as error:
            FAILURES.append(str(error))
    raise RuntimeError("no writable tree found")


def frame_once(width, height, frame_id, reference_id, key, split_chance, options, motion=None):
    coarse = bool(options.get("endpoint_565"))
    endpoints = [rbytes(6) for _ in range(3)]
    roots = [
        tree(32, key, split_chance, endpoints, coarse)
        for _ in range(((width + 31) // 32) * ((height + 31) // 32))
    ]
    if motion is None:
        motion = (0, 0) if key else (RANDOM.randint(-40, 40), RANDOM.randint(-40, 40))
    return pack_frame(width, height, frame_id, reference_id, key, motion, roots, **options)


DERIVED = dict(short_index=True, sparse_children=True, immediate_motion=True, derived_directory=True,
               derived_offsets=True, packed_symbols=True, two_level_walk=True, endpoint_table=True, selector_table=True)
STREAMS = {
    "edge-derived-patterns.mcs": (160, 96, 0.7, dict(DERIVED, endpoint_565=True), dict(pattern=0.6)),
    "edge-stored-short-sparse.mcs": (160, 96, 0.4, dict(short_index=True, sparse_children=True, immediate_motion=True, derived_directory=True), dict(skip=0.6)),
    "edge-stored-short-groups.mcs": (160, 96, 0.4, dict(short_index=True, immediate_motion=True), dict(skip=0.6)),
    "edge-stored-wide-sparse.mcs": (160, 96, 0.4, dict(immediate_motion=True), dict(skip=0.6)),
    "edge-stored-wide-directory-sparse.mcs": (160, 96, 0.4, dict(derived_directory=True), dict(skip=0.6)),
    "edge-derived-tables.mcs": (72, 40, 0.5, DERIVED, {}),
    "edge-derived-565.mcs": (72, 40, 0.5, dict(DERIVED, endpoint_565=True), {}),
    "edge-derived-plain.mcs": (97, 65, 0.6, dict(derived_directory=True, derived_offsets=True), {}),
    "edge-stored-short.mcs": (72, 40, 0.5, dict(short_index=True, sparse_children=True, immediate_motion=True, derived_directory=True), {}),
    "edge-stored-short-dense.mcs": (40, 33, 0.9, dict(short_index=True), {}),
    "edge-stored-wide.mcs": (72, 40, 0.5, dict(immediate_motion=True), {}),
    "edge-stored-wide-directory.mcs": (100, 70, 0.3, dict(derived_directory=True), {}),
    "edge-tiny.mcs": (1, 1, 0.0, DERIVED, {}),
}


def write_archive(path, frames):
    path.write_bytes(b"".join(struct.pack("<I", len(f)) + f for f in frames))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    digests = {}
    for name, (width, height, split, options, bias) in STREAMS.items():
        BIAS.update(skip=bias.get("skip", 0.0), pattern=bias.get("pattern", 0.0))
        frames = []
        for frame_id in range(6):
            key = frame_id in (0, 4)
            reference = frame_id if key else frame_id - 1
            frames.append(frame(width, height, frame_id, reference, key, split, options))
        decoder = Decoder()
        digests[name] = [hashlib.sha256(decoder.accept(f).tobytes()).hexdigest() for f in frames]
        write_archive(OUT / name, frames)
    # a frame too large for the derived form and the short index: the reference falls back to wide descriptors
    grid8 = Node(SPLIT, children=tuple(Node(SPLIT, children=tuple(Node(7, 0, rbytes(192)) for _ in range(4))) for _ in range(4)))
    big = pack_frame(256, 256, 0, 0, True, (0, 0), [grid8] * 64, **DERIVED)
    assert not (fmt.parse_frame(big).flags & 8), "expected the wide fallback"
    sizes = [big]
    decoder = Decoder()
    digests["edge-wide-fallback.mcs"] = [hashlib.sha256(decoder.accept(big).tobytes()).hexdigest()]
    write_archive(OUT / "edge-wide-fallback.mcs", sizes)
    (OUT / "digests.json").write_text(json.dumps(digests, indent=1) + "\n")
    rejected = {}
    mcv1 = bytes.fromhex(
        "4d435631" "01020100" "01000100" "09000000" "09000000" "00000000"
        "01000000" "34000000" "37000000" "00000000" "00000000" "00000000"
        "34000002" "102030"
    )
    rejected["mcv1-hand-example"] = mcv1.hex()
    coarse = pack_frame(64, 32, 0, 0, True, (0, 0), [Node(21, 0, rbytes(6 + 32)), Node(22, 0, rbytes(6 + 8))])
    rejected["round3-coarse-palettes"] = coarse.hex()
    motion_roots = [Node(SPLIT, children=tuple(Node(1, 0, bytes([2, 254])) for _ in range(4))) for _ in range(2)]
    table = pack_frame(64, 32, 1, 0, False, (0, 0), motion_roots, derived_directory=True, derived_offsets=True, motion_table=True)
    rejected["round15-motion-table"] = table.hex()
    for name, value in rejected.items():
        fmt.parse_frame(bytes.fromhex(value))  # the reference accepts every one of them
    (OUT / "rejected.json").write_text(json.dumps(rejected, indent=1) + "\n")
    print(json.dumps({k: len(v) for k, v in digests.items()}))
    print("reference serializer failures (redrawn):", sorted(set(FAILURES)), len(FAILURES))


if __name__ == "__main__":
    main()
