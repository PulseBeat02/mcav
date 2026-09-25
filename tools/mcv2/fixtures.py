"""Regenerate the MCV2 test fixtures of mcav-common from the gpu-codec reference checkout.

    python tools/mcv2/fixtures.py <gpu-codec checkout> <fixture root> [conformance|edge|pages|encoder|all]

The fixture root is mcav-common/src/test/resources/me/brandonli/mcav/media/mcv2; the checkout is the research
repository mcav ports MCV2 from, at commit 85445433aeb9f8a35a5ce528d47d8829976d1401. Run it with a Python that has
numpy, the reference's only dependency. Every fixture the Java tests read is written by the reference itself:

  conformance/  the twelve round-19 sample streams (samples/frontier) and the two shipped 1080p30 streams
                (data/frontier/jobs); a stream over 1,000,000 bytes is cut to its longest whole-frame prefix within that
                size, which starts with the stream's keyframe. digests.json holds the reference decoder's per-frame
                SHA-256 of the RGB output of each (prefix) stream.
  edge/         edge-case streams from tools/mcv2/edge_streams.py, with their digests and the rejected syntax.
  pages.json    the reference's map pages (stream id 7, 6, 7 and 8 bits) of four frames: the SHA-256 of every page's
                symbols, their lengths and the wire model with and without whole maps.
  encoder/      a 320x180 crop of four frames of the 1080p30 source at (1472, 360) and the reference encoder's streams
                of it at both shipped lambdas.

Rerunning it into an empty folder reproduces the committed fixtures byte for byte.
"""

import hashlib
import json
import struct
import subprocess
import sys
from pathlib import Path

CODEC = Path(sys.argv[1])
ROOT = Path(sys.argv[2])
WHAT = sys.argv[3] if len(sys.argv) > 3 else "all"
sys.path.insert(0, str(CODEC))

import numpy as np  # noqa: E402
from mcvideo.decoder import Decoder  # noqa: E402
from mcvideo.transport import make_pages, wire_bytes  # noqa: E402

PREFIX_LIMIT = 1_000_000
SHIPPED = ("p30r19-compact_final-65p255994", "p30r19-compact_final-137p730758")
LAMBDAS = {"ship": 65.255994022, "low": 137.730758207}
SOURCE = "data/frontier/av1/minecraft_proxy_1920x1080_30.rgb"


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
    out.mkdir(parents=True, exist_ok=True)
    sources = sorted((CODEC / "samples/frontier").glob("round19-*.mcs"))
    sources += [CODEC / "data/frontier/jobs" / name / (name + ".mcs") for name in SHIPPED]
    table = {}
    for source in sources:
        full = source.read_bytes()
        kept, size = [], 0
        for frame in frames(full):
            if size + 4 + len(frame) > PREFIX_LIMIT:
                break
            kept.append(frame)
            size += 4 + len(frame)
        data = archive(kept)
        (out / source.name).write_bytes(data)
        table[source.name] = {
            "frames": len(kept),
            "of": len(list(frames(full))),
            "bytes": len(data),
            "full_stream_bytes": len(full),
            "sha256_per_frame": digests(data),
        }
        print(source.name, len(kept), "frames", file=sys.stderr)
    write_json(out / "digests.json", table)


def edge():
    script = Path(__file__).with_name("edge_streams.py")
    subprocess.run([sys.executable, str(script), str(CODEC), str(ROOT / "edge")], check=True)


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
    source = np.fromfile(CODEC / SOURCE, np.uint8).reshape(-1, 1080, 1920, 3)
    crop = np.ascontiguousarray(source[:4, 360:540, 1472:1792])
    (out / "crop-320x180x4.rgb").write_bytes(crop.tobytes())
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
        (out / f"crop-{name}.mcs").write_bytes(archive(coder.encode(frame, i) for i, frame in enumerate(crop)))


STEPS = {"conformance": conformance, "edge": edge, "pages": pages, "encoder": encoder}
for step in STEPS if WHAT == "all" else [WHAT]:
    STEPS[step]()
