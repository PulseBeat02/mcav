"""Draws the sample pictures of mcav-docs/mcv2.md from real MCV2 streams, and prints the annotated bytes of a frame.

    python tools/mcv2/figures/samples.py tree ARCHIVE --frames 0,12 --crop 896,128,576,324 [--out tree.png]
    python tools/mcv2/figures/samples.py leaves ARCHIVE SOURCE --frame 0 --crop 896,128,576,324
    python tools/mcv2/figures/samples.py quality SOURCE --frame 12 --crop 1056,200,320,180 TITLE=ARCHIVE...
    python tools/mcv2/figures/samples.py bytes ARCHIVE --frame 0

An ARCHIVE is a stream as tools/mcv2/Mcv2Bench.java writes it with out= (every frame a little-endian u32 length and its
bytes), a SOURCE the raw RGB24 video it encoded; --size gives their size when it isn't 1920x1080. Every picture is
decoded with the reference decoder in tools/mcv2-reference, frame by frame from the start of the stream. tree.png
outlines every leaf of the cropped region and colours it by mode, leaves.png blows up the biggest palette and pattern
leaves there, quality.png puts the source next to the same frame of each archive. Needs numpy and matplotlib
(mcav-docs/requirements.txt).
"""

import argparse
import logging
import struct
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
from matplotlib.patches import Patch, Rectangle  # noqa: E402

HERE = Path(__file__).resolve().parent
TOOLS = HERE.parent
IMAGES = TOOLS.parent.parent / "mcav-docs" / "images" / "mcv2"
sys.path.insert(0, str(TOOLS.parent / "mcv2-reference"))

from mcvideo import format as fmt  # noqa: E402
from mcvideo.decoder import Decoder  # noqa: E402
from mcvideo.v3 import parse_frame  # noqa: E402

sys.path.insert(0, str(HERE))
from charts import GRID, INK, SECONDARY, SURFACE, style  # noqa: E402

# one colour per mode, the charts' categorical slots first; every mode is also named in the legend
MODES = [
    (fmt.SKIP, "SKIP", "#e4e3df"),
    (fmt.MOTION, "MOTION", "#2a78d6"),
    (fmt.SOLID, "SOLID", "#1baf7a"),
    (fmt.PALETTE, "PALETTE", "#eb6834"),
    (fmt.PATTERN, "PATTERN", "#eda100"),
    (fmt.COMPACT, "COMPACT", "#8f5bd6"),
]


def frames_of(path):
    data = Path(path).read_bytes()
    at = 0
    while at < len(data):
        length = struct.unpack_from("<I", data, at)[0]
        yield data[at + 4:at + 4 + length]
        at += 4 + length


def decoded(path, wanted):
    """The parsed frames and decoded pictures of the wanted frame numbers."""
    decoder, found = Decoder(), {}
    for index, data in enumerate(frames_of(path)):
        picture = decoder.accept(data)
        if index in wanted:
            found[index] = (parse_frame(data), picture.copy())
        if len(found) == len(wanted):
            break
    return found


def source_frame(path, index, width, height):
    size = width * height * 3
    return np.fromfile(path, np.uint8, size, offset=index * size).reshape(height, width, 3)


def crop_of(text):
    x, y, w, h = (int(value) for value in text.split(","))
    return x, y, w, h


def inside(leaf, crop):
    x, y, w, h = crop
    return leaf.x < x + w and leaf.x + leaf.size > x and leaf.y < y + h and leaf.y + leaf.size > y


def finish(axis, title):
    axis.set_xticks([])
    axis.set_yticks([])
    for spine in axis.spines.values():
        spine.set_visible(False)
    axis.set_title(title, fontsize=10.5, color=INK, loc="left")


def draw_tree(arguments):
    numbers = [int(value) for value in arguments.frames.split(",")]
    frames = decoded(arguments.archive, set(numbers))
    x, y, w, h = crop = crop_of(arguments.crop)
    colours = {mode: colour for mode, _, colour in MODES}
    figure, axes = plt.subplots(2, len(numbers), figsize=(5.4 * len(numbers), 6.6), squeeze=False)
    for column, number in enumerate(numbers):
        frame, picture = frames[number]
        kind = "keyframe" if frame.keyframe else "P frame"
        top, bottom = axes[0][column], axes[1][column]
        top.imshow(picture[y:y + h, x:x + w], interpolation="nearest")
        bottom.imshow(np.full((h, w, 3), 255, np.uint8))
        for leaf in frame.leaves:
            if not inside(leaf, crop):
                continue
            corner = (leaf.x - x - 0.5, leaf.y - y - 0.5)
            top.add_patch(Rectangle(corner, leaf.size, leaf.size, fill=False, edgecolor="#ffffff", linewidth=0.5))
            bottom.add_patch(Rectangle(corner, leaf.size, leaf.size, facecolor=colours[leaf.mode], edgecolor=SURFACE,
                                       linewidth=0.5))
        for axis in (top, bottom):
            axis.set_xlim(-0.5, w - 0.5)
            axis.set_ylim(h - 0.5, -0.5)
        finish(top, f"Frame {number} ({kind}): {frame.total:,} bytes, {len(frame.leaves):,} leaves")
        finish(bottom, f"The leaves of frame {number} by mode")
    handles = [Patch(facecolor=colour, edgecolor=SECONDARY, linewidth=0.5, label=name) for _, name, colour in MODES]
    columns = min(len(MODES), 3 * len(numbers))
    figure.legend(handles=handles, loc="lower center", ncol=columns, frameon=False, fontsize=9.5)
    figure.tight_layout(rect=(0, 0.05 * len(MODES) / columns, 1, 1), h_pad=2)
    figure.savefig(IMAGES / arguments.out, dpi=120, facecolor=SURFACE)
    plt.close(figure)
    for number in numbers:
        frame = frames[number][0]
        sizes = {size: sum(1 for leaf in frame.leaves if leaf.size == size) for size in fmt.LEAF_SIZES}
        print(f"frame {number}: keyframe={frame.keyframe} bytes={frame.total} leaves={len(frame.leaves)} "
              f"by size {sizes} by mode "
              + str({name: sum(1 for leaf in frame.leaves if leaf.mode == mode) for mode, name, _ in MODES}))


def biggest(frame, mode, crop):
    candidates = [leaf for leaf in frame.leaves if leaf.mode == mode and inside(leaf, crop)]
    candidates = candidates or [leaf for leaf in frame.leaves if leaf.mode == mode]
    return max(candidates, key=lambda leaf: (leaf.size, -leaf.y, -leaf.x)) if candidates else None


def bits_of(leaf):
    record = leaf.record
    if leaf.mode == fmt.PALETTE:
        bits = np.unpackbits(np.frombuffer(record[6:], np.uint8), bitorder="little")
        return bits.reshape(leaf.size, leaf.size)
    axis = np.unpackbits(np.frombuffer(record[7:], np.uint8), bitorder="little")
    return np.broadcast_to(axis[None, :] if record[6] == 0 else axis[:, None], (leaf.size, leaf.size))


def draw_leaves(arguments):
    frame, picture = decoded(arguments.archive, {arguments.frame})[arguments.frame]
    width, height = arguments.size
    source = source_frame(arguments.source, arguments.frame, width, height)
    crop = crop_of(arguments.crop)
    leaves = [biggest(frame, fmt.PALETTE, crop), biggest(frame, fmt.PATTERN, crop)]
    figure, axes = plt.subplots(2, 4, figsize=(11, 6.2))
    for row, leaf in zip(axes, leaves):
        name = "PALETTE" if leaf.mode == fmt.PALETTE else "PATTERN"
        s = leaf.size
        block = source[leaf.y:leaf.y + s, leaf.x:leaf.x + s]
        colours = [tuple(leaf.record[0:3]), tuple(leaf.record[3:6])]
        bits = bits_of(leaf)
        row[0].imshow(block, interpolation="nearest")
        finish(row[0], f"{name} {s}x{s} at ({leaf.x}, {leaf.y}): source")
        swatch = np.array([[colours[0]], [colours[1]]], np.uint8)
        row[1].imshow(swatch, interpolation="nearest", aspect="auto")
        for index, colour in enumerate(colours):
            light = sum(colour) > 382
            row[1].text(0, index, f"colour {index}\n{colour[0]}, {colour[1]}, {colour[2]}", ha="center", va="center",
                        fontsize=9.5, color=INK if light else SURFACE)
        finish(row[1], "its two colours")
        row[2].imshow(bits, cmap="gray_r", vmin=0, vmax=1.6, interpolation="nearest")
        for edge in range(s + 1):
            row[2].axhline(edge - 0.5, color=GRID, linewidth=0.4)
            row[2].axvline(edge - 0.5, color=GRID, linewidth=0.4)
        if leaf.mode == fmt.PALETTE:
            what = f"{s * s} bits, one per pixel"
        else:
            what = f"{s} bits, one per {'column' if leaf.record[6] == 0 else 'row'}"
        finish(row[2], f"bits (grey is 1): {what}")
        row[3].imshow(picture[leaf.y:leaf.y + s, leaf.x:leaf.x + s], interpolation="nearest")
        finish(row[3], f"decoded: {len(leaf.record)} bytes")
        print(f"{name} {s}x{s} at ({leaf.x}, {leaf.y}) colours {colours} record {leaf.record.hex()}")
    figure.tight_layout()
    figure.savefig(IMAGES / "leaves.png", dpi=120, facecolor=SURFACE)
    plt.close(figure)


def draw_quality(arguments):
    width, height = arguments.size
    x, y, w, h = crop_of(arguments.crop)
    panels = [("Source", source_frame(arguments.source, arguments.frame, width, height))]
    for item in arguments.archives:
        title, path = item.split("=", 1)
        panels.append((title, decoded(path, {arguments.frame})[arguments.frame][1]))
    columns = 2
    rows = (len(panels) + columns - 1) // columns
    figure, axes = plt.subplots(rows, columns, figsize=(5.2 * columns, 3.2 * rows))
    for axis, (title, picture) in zip(axes.flat, panels):
        axis.imshow(picture[y:y + h, x:x + w], interpolation="nearest")
        finish(axis, title)
    for axis in list(axes.flat)[len(panels):]:
        axis.set_visible(False)
    figure.tight_layout()
    figure.savefig(IMAGES / "quality.png", dpi=120, facecolor=SURFACE)
    plt.close(figure)


def print_bytes(arguments):
    data = list(frames_of(arguments.archive))[arguments.frame]
    frame = parse_frame(data)
    groups = len(frame.masks)
    parts = [("header", 0, 32)]
    at = 32
    for name, length in (("presence masks", 4 * groups), ("directory", 4 * len(frame.directory)),
                         ("level counts", 12), ("descriptors", len(frame.descriptors)),
                         ("walk checkpoints", 4 * len(frame.walk))):
        parts.append((name, at, at + length))
        at += length
    parts.append(("records", at, frame.total))
    for name, start, end in parts:
        print(f"{name} ({end - start} bytes, offsets {start}-{end - 1}):")
        for line in range(start, end, 16):
            print(f"  {line:5d}  " + " ".join(f"{value:02x}" for value in data[line:min(line + 16, end)]))
    print(f"width {frame.width} height {frame.height} keyframe {frame.keyframe} frame id {frame.frame_id} "
          f"reference id {frame.reference_id} payload start {frame.payload_start} total {frame.total} "
          f"default colour {frame.default_color}")
    print(f"masks {[hex(mask) for mask in frame.masks]} directory {list(frame.directory)} levels {frame.level_counts} "
          f"walk {[(value & 0x1FFFF, value >> 17) for value in frame.walk]}")
    names = {mode: name for mode, name, _ in MODES}
    names[fmt.SPLIT] = "SPLIT"
    for index, descriptor in enumerate(frame.descriptors):
        print(f"descriptor {index}: 0x{descriptor:02x} = {names[descriptor & 31]} q={descriptor >> 5}")
    for leaf in frame.leaves:
        if leaf.offset is not None:
            print(f"leaf {leaf.size}x{leaf.size} at ({leaf.x}, {leaf.y}) {names[leaf.mode]} q={leaf.q} "
                  f"offset {leaf.offset}: {leaf.record.hex()}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--size", type=lambda text: tuple(int(v) for v in text.split("x")), default=(1920, 1080))
    commands = parser.add_subparsers(dest="command", required=True)
    tree = commands.add_parser("tree")
    tree.add_argument("archive")
    tree.add_argument("--frames", required=True)
    tree.add_argument("--crop", required=True)
    tree.add_argument("--out", default="tree.png")
    leaves = commands.add_parser("leaves")
    leaves.add_argument("archive")
    leaves.add_argument("source")
    leaves.add_argument("--frame", type=int, default=0)
    leaves.add_argument("--crop", required=True)
    quality = commands.add_parser("quality")
    quality.add_argument("source")
    quality.add_argument("archives", nargs="+")
    quality.add_argument("--frame", type=int, required=True)
    quality.add_argument("--crop", required=True)
    dump = commands.add_parser("bytes")
    dump.add_argument("archive")
    dump.add_argument("--frame", type=int, default=0)
    arguments = parser.parse_args()
    # the fonts missing on a platform are skipped; say nothing about them
    logging.getLogger("matplotlib.font_manager").setLevel(logging.ERROR)
    style()
    IMAGES.mkdir(parents=True, exist_ok=True)
    {"tree": draw_tree, "leaves": draw_leaves, "quality": draw_quality, "bytes": print_bytes}[arguments.command](arguments)


if __name__ == "__main__":
    main()
