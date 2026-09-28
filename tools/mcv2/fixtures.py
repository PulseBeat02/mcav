"""Regenerate the MCV2 test fixtures of mcav-bukkit with the reference in tools/mcv2-reference.

    python tools/mcv2/fixtures.py <fixture root> [conformance|edge|pages|encoder|all] [--source RGB]

The fixture root is mcav-bukkit/src/test/resources/me/brandonli/mcav/bukkit/media/mcv2. Run it with a Python that has
numpy (tools/mcv2-reference/requirements.txt). Every fixture the Java tests read is written by the reference itself:

  conformance/  the twelve round-19 sample streams and the two shipped 1080p30 streams of the research repository at
                the pinned commit, a stream over 1,000,000 bytes cut to its longest whole-frame prefix within that size,
                which starts with the stream's keyframe. The streams are the test vectors and are kept as committed: the
                research data they were cut from is not part of mcav. digests.json holds the reference decoder's
                per-frame SHA-256 of the RGB output of each stream, recomputed here, and the frame count and size of
                the full stream each was cut from, carried over.
  edge/         edge-case streams from tools/mcv2/edge_streams.py, with their digests and the rejected syntax.
  pages.json    the reference's map pages (stream id 7, 6, 7 and 8 bits) of four frames: the SHA-256 of every page's
                symbols, their lengths and the wire model with and without whole maps.
  encoder/      a 320x180 crop of four frames of the 1080p30 source at (1472, 360), kept as committed unless --source
                names that source (raw 1920x1080 RGB), and the reference encoder's streams of it at both shipped
                lambdas.

Rerunning it on the committed fixtures reproduces them byte for byte.
"""

import argparse
import hashlib
import json
import struct
import subprocess
import sys
from pathlib import Path

PARSER = argparse.ArgumentParser()
PARSER.add_argument("root", type=Path)
PARSER.add_argument("what", nargs="?", default="all", choices=("conformance", "edge", "pages", "encoder", "all"))
PARSER.add_argument("--source", type=Path, help="the raw 1920x1080 RGB source to cut the encoder crop from")
ARGS = PARSER.parse_args()
ROOT, WHAT, SOURCE = ARGS.root, ARGS.what, ARGS.source
REPOSITORY = Path(__file__).resolve().parents[2]
REFERENCE = REPOSITORY / "tools/mcv2-reference"
sys.path.insert(0, str(REFERENCE))

import numpy as np  # noqa: E402
from mcvideo import format as fmt  # noqa: E402
from mcvideo.decoder import Decoder  # noqa: E402
from mcvideo.transport import make_pages, wire_bytes  # noqa: E402

PREFIX_LIMIT = 1_000_000
SHIPPED = ("p30r19-compact_final-65p255994", "p30r19-compact_final-137p730758")
LAMBDAS = {"ship": 65.255994022, "low": 137.730758207}
BOOKS = REPOSITORY / "mcav-bukkit/src/main/resources/me/brandonli/mcav/bukkit/media/mcv2/residual_books.bin"


def frames(data):
    offset = 0
    while offset < len(data):
        length = struct.unpack_from("<I", data, offset)[0]
        yield data[offset + 4 : offset + 4 + length]
        offset += 4 + length


def archive(chunks):
    return b"".join(struct.pack("<I", len(chunk)) + chunk for chunk in chunks)


def digests(data):
    decoder = Decoder()
    return [hashlib.sha256(decoder.accept(frame).tobytes()).hexdigest() for frame in frames(data)]


def write_json(path, value):
    path.write_text(json.dumps(value, indent=1) + "\n")


def conformance():
    out = ROOT / "conformance"
    committed = json.loads((out / "digests.json").read_text())
    names = sorted(path.name for path in out.glob("round19-*.mcs")) + [name + ".mcs" for name in SHIPPED]
    table = {}
    for name in names:
        data = (out / name).read_bytes()
        kept = list(frames(data))
        if len(data) > PREFIX_LIMIT or not fmt.parse_frame(kept[0]).flags & fmt.KEYFRAME:
            raise ValueError(name + " is not a prefix within the limit that starts with a keyframe")
        table[name] = {
            "frames": len(kept),
            "of": committed[name]["of"],
            "bytes": len(data),
            "full_stream_bytes": committed[name]["full_stream_bytes"],
            "sha256_per_frame": digests(data),
        }
        print(name, len(kept), "frames", file=sys.stderr)
    write_json(out / "digests.json", table)


def edge():
    script = Path(__file__).with_name("edge_streams.py")
    subprocess.run([sys.executable, str(script), str(ROOT / "edge")], check=True)


def pages():
    cases = [
        ("conformance/p30r19-compact_final-65p255994.mcs", 0),
        ("conformance/p30r19-compact_final-65p255994.mcs", 1),
        ("edge/edge-tiny.mcs", 0),
        ("edge/edge-wide-fallback.mcs", 0),
    ]
    table = {}
    for stream, index in cases:
        frame = list(frames((ROOT / stream).read_bytes()))[index]
        for bits in (6, 7, 8):
            symbols = make_pages(frame, 7, bits)
            table[f"{stream}#{index}@{bits}"] = {
                "pages": [hashlib.sha256(page).hexdigest() for page in symbols],
                "lengths": [len(page) for page in symbols],
                "wire": wire_bytes(symbols),
                "wire_full": wire_bytes(symbols, full_maps=True),
            }
    write_json(ROOT / "conformance/pages.json", table)


def encoder():
    from mcvideo.encoder import Settings
    from mcvideo.v2 import TreeEncoder, TreeSettings

    out = ROOT / "encoder"
    out.mkdir(parents=True, exist_ok=True)
    if SOURCE is not None:
        source = np.fromfile(SOURCE, np.uint8).reshape(-1, 1080, 1920, 3)
        (out / "crop-320x180x4.rgb").write_bytes(np.ascontiguousarray(source[:4, 360:540, 1472:1792]).tobytes())
    crop = np.fromfile(out / "crop-320x180x4.rgb", np.uint8).reshape(4, 180, 320, 3)
    for name, lam in LAMBDAS.items():
        # the round-19 settings of the shipped 1080p30 profiles; only the lambda differs between them
        settings = Settings(
            block_size=32, lambda_value=lam, motion_range=24, half_pixel=True, global_motion=True, compare_global=True,
            palette=True, reduced_chroma=True, sparse=True, default_solid=True, grids=(1, 2, 4, 8), key_interval=60,
            scene_threshold=45.0,
        )
        tree = TreeSettings(
            adaptive=True, short_index=True, palette_patterns=True, pattern_rdo=True, sparse_children=True,
            residual_classes=(0, 1, 2, 3, 4, 8), wire_rdo=False, symbol_bits=6, perceptual=False, immediate_motion=True,
            derived_directory=True, derived_offsets=True, matched_cost_model=True, packed_symbols=True,
            two_level_walk=True, endpoint_table=True, selector_table=True, endpoint_565=True,
        )
        coder = TreeEncoder(settings, tree)
        (out / f"crop-{name}.mcs").write_bytes(archive(coder.encode(frame, frame_number) for frame_number, frame in enumerate(crop)))


# the reference reads its residual books from its own folder; mcav's decoder reads the resource, and both must agree
if (REFERENCE / "research_artifacts/residual_books.bin").read_bytes() != BOOKS.read_bytes():
    raise ValueError("the residual books of tools/mcv2-reference differ from " + str(BOOKS))
STEPS = {"conformance": conformance, "edge": edge, "pages": pages, "encoder": encoder}
for step in STEPS if WHAT == "all" else [WHAT]:
    STEPS[step]()
