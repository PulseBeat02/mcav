"""Differential test of mcav's MCV2 decoder against the reference decoder, on generated streams.

    python tools/mcv2/differential.py <mcav-bukkit classpath> [--streams N] [--encoded N] [--mutants N] [--seed S]
        [--out DIR] [--java JAVA]

The reference is the one in tools/mcv2-reference. The classpath holds mcav-bukkit's classes and resources (the residual books) and Guava, for example
`mcav-bukkit/build/classes/java/main:mcav-bukkit/build/resources/main:<guava jar>` after `./gradlew :mcav-bukkit:jar`.

The committed conformance corpus proves what was already measured; this finds what was not. It generates three kinds of
research archives (u32 length, then the frame) and decodes each with both decoders, frame by frame:

- `tree`: random block trees written by the reference serializer (`v2.pack_frame`), like `edge_streams.py`, at random
  sizes (1x1 to 200x130, edges crossing blocks), with random index forms and tables, every leaf mode, compact class,
  quantizer up to 7 and motion, and keyframes at random places;
- `encoded`: the reference encoder (`TreeEncoder`, the shipped round-19 settings at a random lambda, sometimes with the
  palette, patterns or residual classes off) on random moving pictures up to 96x64;
- `mutant`: a copy of every generated archive with one to four bytes of random frames changed, or a frame cut short.

The reference decodes with `Decoder.accept` (commit a valid, newer frame; otherwise raise and keep the state); mcav with
`Mcv2Receiver.accept`, through `tools/mcv2/Mcv2Digests.java`. Per frame, both must give the same SHA-256 of the RGB
picture, or both refuse the frame. The only allowed difference is syntax mcav deliberately does not implement (MCV1, the
coarse palettes of round 3, the motion table of round 15, nonzero derived split quantizers): mcav refuses it as unsupported where the reference may
accept it, and from that frame on the two decoders hold different pictures, so the rest of that archive is not
compared. Writes `summary.json` into the output folder and exits non-zero on any disagreement.
"""

import argparse
import hashlib
import json
import random
import struct
import subprocess
import sys
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
parser.add_argument("classpath")
parser.add_argument("--streams", type=int, default=200, help="random-tree archives")
parser.add_argument("--encoded", type=int, default=40, help="reference-encoder archives")
parser.add_argument("--mutants", type=int, default=1, help="mutated copies of every archive")
parser.add_argument("--seed", type=int, default=20260926)
parser.add_argument("--out", type=Path, default=Path("build/mcv2-differential"))
parser.add_argument("--java", default="java")
ARGS = parser.parse_args()
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "mcv2-reference"))

import numpy as np  # noqa: E402
from mcvideo import format as fmt  # noqa: E402
from mcvideo.compact import BODY_BYTES, COMPACT  # noqa: E402
from mcvideo.decoder import Decoder  # noqa: E402
from mcvideo.encoder import Settings  # noqa: E402
from mcvideo.pattern import PATTERN_PALETTE  # noqa: E402
from mcvideo.v2 import SPLIT, Node, TreeEncoder, TreeSettings, pack_frame, parse_frame, symbol_width  # noqa: E402

RANDOM = random.Random(ARGS.seed)


def rbytes(count):
    return bytes(RANDOM.randrange(256) for _ in range(count))


def compact_record(quantizer):
    kind = RANDOM.randrange(9)
    if kind == 7:
        quantizer = 0
    form = RANDOM.choice((0, 1, 2))
    prefix = rbytes(form)
    body = bytearray(rbytes(BODY_BYTES[kind]))
    if kind == 5:
        body[-1] &= 63
    if kind == 6:
        body[-1] &= 15
    if kind == 7:
        body[0] = RANDOM.randint(0, 64) - 32 & 255
    return quantizer, bytes([kind | form << 4]) + prefix + bytes(body)


def safe565(pair):
    """Endpoints whose low bits replicate their high bits, so they survive the RGB565 round trip exactly."""
    return bytes(value & 0xF8 | value >> 5 if index % 3 != 1 else value & 0xFC | value >> 6 for index, value in enumerate(pair))


def leaf(size, key, words, endpoints, coarse, skip):
    if not key and RANDOM.random() < skip:
        return Node(0)
    modes = [2, 3, 4, 5, 6, 7, 12, 14, PATTERN_PALETTE]
    if not key:
        modes += [0, 1, 8, 9, 10, 11, 13, 15, COMPACT, COMPACT]
    mode = RANDOM.choice(modes)
    if mode == 0:
        return Node(0)
    if mode == COMPACT:
        quantizer, record = compact_record(RANDOM.randint(0, 7))
        return Node(mode, quantizer, record)
    if mode == PATTERN_PALETTE:
        word = RANDOM.choice(words[size]) if RANDOM.random() < 0.7 else bytes([RANDOM.randint(0, 1)]) + rbytes(size // 8)
        pair = RANDOM.choice(endpoints) if RANDOM.random() < 0.7 else rbytes(6)
        return Node(mode, 0, (safe565(pair) if coarse else pair) + word)
    quantizer = RANDOM.randint(0, 7) if fmt.is_residual(mode) else 0
    return Node(mode, quantizer, rbytes(fmt.record_size(mode, size)))


def tree(size, key, split, words, endpoints, coarse, skip):
    if size > 8 and RANDOM.random() < split:
        return Node(SPLIT, children=tuple(tree(size // 2, key, split * 0.7, words, endpoints, coarse, skip) for _ in range(4)))
    return leaf(size, key, words, endpoints, coarse, skip)


DERIVED = dict(short_index=True, sparse_children=True, immediate_motion=True, derived_directory=True, derived_offsets=True,
               packed_symbols=True, two_level_walk=True, endpoint_table=True, selector_table=True)
FORMS = [
    DERIVED,
    dict(DERIVED, endpoint_565=True),
    dict(derived_directory=True, derived_offsets=True),
    dict(short_index=True, sparse_children=True, immediate_motion=True, derived_directory=True),
    dict(short_index=True, immediate_motion=True),
    dict(short_index=True),
    dict(immediate_motion=True),
    dict(derived_directory=True),
    {},
]


def tree_archive():
    width, height = RANDOM.randint(1, 200), RANDOM.randint(1, 130)
    options = dict(RANDOM.choice(FORMS))
    if RANDOM.random() < 0.5:
        options.pop("two_level_walk", None)
    if RANDOM.random() < 0.3:
        options.pop("packed_symbols", None)
    split, skip = RANDOM.uniform(0, 0.9), RANDOM.choice((0.0, 0.3, 0.7))
    coarse = bool(options.get("endpoint_565"))
    frames, key_at = [], {0} | {candidate for candidate in range(1, 6) if RANDOM.random() < 0.2}
    for frame_id in range(6):
        key = frame_id in key_at
        words = {size: [bytes([RANDOM.randint(0, 1)]) + rbytes(size // 8) for _ in range(3)] for size in (8, 16, 32)}
        endpoints = [rbytes(6) for _ in range(3)]
        for _ in range(100):
            roots = [tree(32, key, split, words, endpoints, coarse, skip)
                     for _ in range(((width + 31) // 32) * ((height + 31) // 32))]
            motion = (0, 0) if key else (RANDOM.randint(-40, 40), RANDOM.randint(-40, 40))
            try:
                frames.append(pack_frame(width, height, frame_id, frame_id if key else frame_id - 1, key, motion, roots, **options))
                break
            except ValueError:
                continue
        else:
            raise RuntimeError("no writable tree found")
    return frames


def moving_pictures(width, height, count):
    """A textured background panning by a random vector, with a few flat and striped rectangles moving over it."""
    rng = np.random.default_rng(RANDOM.randrange(1 << 30))
    base = rng.integers(0, 256, (height + 64, width + 64, 3), dtype=np.uint8)
    base = ((base.astype(np.int32) + np.roll(base, 1, 0) + np.roll(base, 1, 1)) // 3).astype(np.uint8)
    pan_x, pan_y = RANDOM.randint(-3, 3), RANDOM.randint(-3, 3)
    shapes = [(RANDOM.randrange(width), RANDOM.randrange(height), RANDOM.randint(4, 40), RANDOM.randint(4, 30),
               rng.integers(0, 256, 3), RANDOM.randint(-4, 4), RANDOM.randint(-4, 4), RANDOM.random() < 0.5)
              for _ in range(RANDOM.randint(0, 4))]
    pictures = []
    for frame_number in range(count):
        offset_x, offset_y = 32 + max(-32, min(32, pan_x * frame_number)), 32 + max(-32, min(32, pan_y * frame_number))
        picture = base[offset_y:offset_y + height, offset_x:offset_x + width].copy()
        for start_left, start_top, shape_width, shape_height, color, step_x, step_y, striped in shapes:
            shape_left, shape_top = (start_left + step_x * frame_number) % width, (start_top + step_y * frame_number) % height
            region = picture[shape_top:shape_top + shape_height, shape_left:shape_left + shape_width]
            region[:] = color
            if striped:
                region[::2] = 255 - color
        if RANDOM.random() < 0.15:
            picture = rng.integers(0, 256, picture.shape, dtype=np.uint8)
        pictures.append(np.ascontiguousarray(picture))
    return pictures


def encoded_archive():
    width, height = RANDOM.randint(16, 96), RANDOM.randint(16, 64)
    settings = Settings(
        block_size=32, lambda_value=RANDOM.uniform(20, 250), motion_range=24, half_pixel=True, global_motion=True,
        compare_global=RANDOM.random() < 0.7, palette=RANDOM.random() < 0.9, reduced_chroma=True, sparse=True,
        default_solid=True, grids=(1, 2, 4, 8), key_interval=RANDOM.choice((2, 3, 60)), scene_threshold=45.0,
    )
    classes = (0, 1, 2, 3, 4, 8) if RANDOM.random() < 0.8 else ()
    # the endpoint and selector tables index pattern records, so the reference allows them only with patterns
    patterns = RANDOM.random() < 0.9
    tree_settings = TreeSettings(
        adaptive=True, short_index=True, palette_patterns=patterns, pattern_rdo=patterns, sparse_children=True,
        residual_classes=classes, wire_rdo=False, symbol_bits=6, perceptual=False, immediate_motion=True,
        derived_directory=True, derived_offsets=True, matched_cost_model=True, packed_symbols=True, two_level_walk=True,
        endpoint_table=patterns, selector_table=patterns, endpoint_565=patterns and RANDOM.random() < 0.8,
    )
    coder = TreeEncoder(settings, tree_settings)
    return [coder.encode(picture, frame_number) for frame_number, picture in enumerate(moving_pictures(width, height, 4))]


def mutant(frames):
    frames = [bytearray(frame) for frame in frames]
    if RANDOM.random() < 0.15:
        victim = RANDOM.randrange(len(frames))
        frames[victim] = frames[victim][:RANDOM.randrange(len(frames[victim]))]
    else:
        for _ in range(RANDOM.randint(1, 4)):
            victim = frames[RANDOM.randrange(len(frames))]
            # most flips land in the header and index, where the checks are
            at = RANDOM.randrange(min(len(victim), 256)) if RANDOM.random() < 0.7 else RANDOM.randrange(len(victim))
            victim[at] ^= RANDOM.randint(1, 255)
    return [bytes(frame) for frame in frames]


def write_archive(path, frames):
    path.write_bytes(b"".join(struct.pack("<I", len(frame)) + frame for frame in frames))


def reference_tokens(frames):
    decoder, tokens = Decoder(), []
    for frame in frames:
        try:
            tokens.append(hashlib.sha256(decoder.accept(frame).tobytes()).hexdigest())
        except Exception:  # the reference raises ValueError for invalid frames, and whatever it raises refuses the frame
            tokens.append("reject")
    return tokens


def deliberately_unsupported(frame):
    """Only exempt syntax present in the frame, independently of the Java result token."""
    if frame[:4] == b"MCV1":
        return True
    try:
        parsed = parse_frame(frame)
    except ValueError:
        return False
    if parsed.flags & fmt.MOTION_TABLE:
        return True
    if any(leaf[3] in (fmt.COARSE_PALETTE_2, fmt.COARSE_PALETTE_4, fmt.MODE_INDEXED_MOTION) for leaf in parsed.leaves):
        return True
    if not parsed.flags & fmt.DERIVED_OFFSETS:
        return False
    root_count = struct.unpack_from("<I", frame, 24)[0]
    groups = (root_count + 31) // 32
    header = 48 + groups * 4 + ((groups + 7) // 8) * 4
    descriptors = sum(struct.unpack_from("<HHH", frame, header))
    plane = header + 6
    if parsed.flags & fmt.PACKED_SYMBOLS:
        size = frame[plane]
        table = frame[plane + 1:plane + 1 + size]
        plane += 1 + size
        width = symbol_width(size)
        symbols = []
        for index in range(descriptors):
            position = index * width
            offset = plane + position // 8
            word = int.from_bytes(frame[offset:offset + 2], "little")
            symbols.append(table[(word >> (position % 8)) & ((1 << width) - 1)])
    else:
        symbols = frame[plane:plane + descriptors]
    return any(symbol & 31 == SPLIT and symbol >> 5 != 0 for symbol in symbols)


def main():
    out = ARGS.out
    (out / "archives").mkdir(parents=True, exist_ok=True)
    archives = {}
    for archive_index in range(ARGS.streams):
        archives[f"tree-{archive_index:04d}"] = tree_archive()
    for archive_index in range(ARGS.encoded):
        archives[f"encoded-{archive_index:04d}"] = encoded_archive()
    for name in list(archives):
        for mutant_index in range(ARGS.mutants):
            archives[f"{name}-mutant-{mutant_index}"] = mutant(archives[name])
    paths = {}
    for name, frames in archives.items():
        paths[name] = out / "archives" / f"{name}.mcs"
        write_archive(paths[name], frames)
    expected = {name: reference_tokens(frames) for name, frames in archives.items()}
    java = subprocess.run(
        [ARGS.java, "-cp", ARGS.classpath, str(Path(__file__).with_name("Mcv2Digests.java"))]
        + [str(archive_path) for archive_path in paths.values()],
        capture_output=True, text=True,
    )
    if java.returncode != 0:
        # an exception out of mcav's decoder other than its declared one is itself a finding
        print(java.stderr, file=sys.stderr)
        sys.exit(2)
    actual = {}
    for line in java.stdout.splitlines():
        path, *tokens = line.split(" ")
        actual[Path(path).stem] = tokens
    counts = dict(archives=len(archives), frames=0, decoded=0, refused=0, unsupported_skipped=0, disagreements=0)
    disagreements = []
    for name, frames in archives.items():
        mine, theirs = actual[name], expected[name]
        if len(mine) != len(theirs):
            disagreements.append(dict(archive=name, reason="frame count", mcav=len(mine), reference=len(theirs)))
            continue
        for index, (mcav_token, reference_token) in enumerate(zip(mine, theirs)):
            counts["frames"] += 1
            if mcav_token == "unsupported":
                if not deliberately_unsupported(frames[index]):
                    disagreements.append(dict(archive=name, frame=index, reason="unsupported syntax exemption not justified",
                                              mcav=mcav_token, reference=reference_token))
                    break
                # the reference may accept syntax mcav refuses; the two decoders hold different pictures from here on
                counts["unsupported_skipped"] += len(mine) - index
                break
            if mcav_token != reference_token:
                disagreements.append(dict(archive=name, frame=index, mcav=mcav_token, reference=reference_token))
                break
            counts["decoded" if mcav_token != "reject" else "refused"] += 1
    counts["disagreements"] = len(disagreements)
    summary = dict(seed=ARGS.seed, counts=counts, disagreements=disagreements[:50])
    (out / "summary.json").write_text(json.dumps(summary, indent=1) + "\n")
    print(json.dumps(counts))
    sys.exit(1 if disagreements else 0)


if __name__ == "__main__":
    main()
