# This file is part of mcav, a media playback library for Java
# Copyright (C) Brandon Li <https://brandonli.me/>
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

"""Build reproducible v3 edge streams from block trees and a §9 rejection catalog."""

import argparse
import hashlib
import json
import random
import struct
import sys
from pathlib import Path

REFERENCE = Path(__file__).resolve().parents[1] / 'mcv2-reference'
sys.path.insert(0, str(REFERENCE))

from mcvideo import format as fmt
from mcvideo.decoder import Decoder
from mcvideo.v3 import Node, expand_endpoints, pack_frame, parse_frame

DEFAULT_SEED = 20261008


def rbytes(randomizer, count):
    return bytes(randomizer.randrange(256) for _ in range(count))


def compact_record(randomizer, kind=None, form=None):
    kind = randomizer.randrange(3) if kind is None else kind
    form = randomizer.randrange(3) if form is None else form
    return bytes([kind | form << 4]) + rbytes(randomizer, form + fmt.BODY_BYTES[kind])


def tables(randomizer):
    pairs = tuple(sorted({rbytes(randomizer, 4) for _ in range(4)}))
    words = {size: tuple(sorted({bytes([orientation]) + rbytes(randomizer, size // 8)
                                for orientation in (0, 1)})) for size in (8, 16, 32)}
    return pairs, words


def leaf(randomizer, size, keyframe, pairs, words, mode=None):
    modes = (fmt.SKIP, fmt.SOLID, fmt.PALETTE, fmt.PATTERN) if keyframe else tuple(range(fmt.SPLIT))
    mode = randomizer.choice(modes) if mode is None else mode
    if mode == fmt.SKIP:
        return Node(mode)
    if mode == fmt.MOTION:
        return Node(mode, record=rbytes(randomizer, 2))
    if mode == fmt.SOLID:
        return Node(mode, record=rbytes(randomizer, 3))
    if mode == fmt.PALETTE:
        return Node(mode, record=rbytes(randomizer, 6 + size * size // 8))
    if mode == fmt.PATTERN:
        return Node(mode, record=expand_endpoints(randomizer.choice(pairs)) + randomizer.choice(words[size]))
    return Node(fmt.COMPACT, randomizer.randrange(8), compact_record(randomizer))


def tree(randomizer, size, keyframe, pairs, words, split_chance=0.65):
    if size > 8 and randomizer.random() < split_chance:
        return Node(fmt.SPLIT, children=tuple(tree(randomizer, size // 2, keyframe, pairs, words, split_chance)
                                             for _ in range(4)))
    return leaf(randomizer, size, keyframe, pairs, words)


def random_stream(randomizer, width=None, height=None, frame_count=6):
    width = randomizer.randint(1, 200) if width is None else width
    height = randomizer.randint(1, 130) if height is None else height
    count = ((width + 31) // 32) * ((height + 31) // 32)
    stream = []
    for frame_id in range(frame_count):
        keyframe = frame_id == 0 or randomizer.random() < 0.2
        pairs, words = tables(randomizer)
        table_mask = randomizer.randrange(16)
        roots = {i: tree(randomizer, 32, keyframe, pairs, words) for i in range(count) if randomizer.random() > 0.2}
        stream.append(pack_frame(width, height, frame_id, frame_id if keyframe else frame_id - 1, keyframe,
                                 tuple(rbytes(randomizer, 3)) if keyframe else (0, 0, 0), roots,
                                 pairs if table_mask & 1 else None,
                                 {size: words[size] for index, size in enumerate((8, 16, 32)) if table_mask >> (index + 1) & 1}))
    return stream


def repeated(node, size):
    while size < 32:
        node = Node(fmt.SPLIT, children=(node,) * 4)
        size *= 2
    return node


def mode_stream(randomizer):
    pairs, words = tables(randomizer)
    nodes = []
    for size in (8, 16, 32):
        nodes.extend(repeated(leaf(randomizer, size, False, pairs, words, mode), size) for mode in range(fmt.COMPACT))
        for kind in range(3):
            for form in range(3):
                for q in range(8):
                    nodes.append(repeated(Node(fmt.COMPACT, q, compact_record(randomizer, kind, form)), size))
    height = (len(nodes) + 15) // 16 * 32
    key_roots = {i: repeated(leaf(randomizer, size, True, pairs, words, mode), size)
                 for i, (size, mode) in enumerate((size, mode) for size in (8, 16, 32)
                                                   for mode in (fmt.SKIP, fmt.SOLID, fmt.PALETTE, fmt.PATTERN))}
    return [pack_frame(512, height, 0, 0, True, (47, 91, 133), key_roots),
            pack_frame(512, height, 1, 0, False, (0, 0, 0), dict(enumerate(nodes)))]


def table_stream(randomizer):
    pairs, words = tables(randomizer)
    frames = []
    for mask in range(16):
        roots = {i: repeated(Node(fmt.PATTERN, record=expand_endpoints(pairs[i % len(pairs)]) + words[size][mask % 2]), size)
                 for i, size in enumerate((8, 16, 32))}
        frames.append(pack_frame(96, 32, mask, mask, True, (0, 0, 0), roots, pairs if mask & 1 else None,
                                 {size: words[size] for index, size in enumerate((8, 16, 32)) if mask >> (index + 1) & 1}))
    pairs = tuple(struct.pack('<HH', i, 65535 - i) for i in range(255))
    words = {size: tuple(bytes([i % 2, i]) + bytes(size // 8 - 1) for i in range(255)) for size in (8, 16, 32)}
    roots = {i: repeated(Node(fmt.PATTERN, record=expand_endpoints(pairs[254]) + words[size][254]), size)
             for i, size in enumerate((8, 16, 32))}
    frames.append(pack_frame(96, 32, 16, 16, True, (0, 0, 0), roots, pairs, words))
    return frames


def build_streams(seed=DEFAULT_SEED):
    randomizer = random.Random(seed)
    streams = {'edge-modes.mcs': mode_stream(randomizer), 'edge-tables.mcs': table_stream(randomizer)}
    for name, width, height in [('tiny', 1, 1), ('vertical', 1, 97), ('horizontal', 97, 1), ('cropped', 97, 65),
                                ('directory', 4096, 65)]:
        streams[f'edge-{name}.mcs'] = random_stream(randomizer, width, height)
    streams['edge-absent.mcs'] = [pack_frame(33, 17, 0, 0, True, (21, 45, 89), {}),
                                 pack_frame(33, 17, 1, 0, False, (0, 0, 0), {})]
    motion = [pack_frame(33, 17, 0, 0, True, (4, 50, 150), {0: Node(fmt.SOLID, record=b'\xff\x80\0')})]
    for index, vector in enumerate((b'\x80\x7f', b'\x7f\x80', b'\x80\x80', b'\x7f\x7f'), 1):
        motion.append(pack_frame(33, 17, index, index - 1, False, (0, 0, 0),
                                 {0: Node(fmt.MOTION, record=vector),
                                  1: repeated(Node(fmt.COMPACT, 2, b'\x20' + vector + b'\0'), 8)}))
    streams['edge-motion.mcs'] = motion
    long_roots = {i: Node(fmt.SPLIT, children=tuple(Node(fmt.SPLIT, children=tuple(
        Node(fmt.COMPACT, (i + j) % 8, compact_record(randomizer, 1, 2 if j % 4 else j % 3))
        for j in range(4))) for _ in range(4))) for i in range(440)}
    streams['edge-long-walk.mcs'] = [pack_frame(1024, 448, 0, 0, True, (101, 123, 145), {}),
                                    pack_frame(1024, 448, 1, 0, False, (0, 0, 0), long_roots)]
    roots = {i: repeated(Node(fmt.SKIP), 8) for i in range(4086)}
    streams['edge-max-splits.mcs'] = [pack_frame(4096, 4096, 0, 0, True, (7, 11, 13), roots)]
    roots = {i: Node(fmt.PALETTE, record=rbytes(randomizer, 134)) for i in range(965)}
    roots[965] = Node(fmt.SOLID, record=b'\x24\x48\x72')
    streams['edge-length-limit.mcs'] = [pack_frame(1024, 1024, 0, 0, True, (0, 0, 0), roots,
                                                [struct.pack('<I', i) for i in range(29)])]
    streams['edge-wrap.mcs'] = [pack_frame(1, 1, frame_id, frame_id if index == 0 else frame_id - 1 & fmt.ID_MASK,
                                          index == 0, (27, 64, 128) if index == 0 else (0, 0, 0), {})
                               for index, frame_id in enumerate((0xFFFFFFFE, 0xFFFFFFFF, 0, 1))]
    return streams


def archive(frames):
    return b''.join(struct.pack('<I', len(frame)) + frame for frame in frames)


def generate(output, seed=DEFAULT_SEED):
    output.mkdir(parents=True, exist_ok=True)
    streams = build_streams(seed)
    for old in output.glob('edge-*.mcs'):
        if old.name not in streams:
            old.unlink()
    digests = {}
    for name, frames in streams.items():
        decoder = Decoder()
        digests[name] = [hashlib.sha256(decoder.accept(data).tobytes()).hexdigest() for data in frames]
        output.joinpath(name).write_bytes(archive(frames))
    output.joinpath('digests.json').write_text(json.dumps(digests, indent=1) + '\n')
    sys.path.insert(0, str(REFERENCE / 'tests'))
    from rejection_cases import rejected_frames
    rejected = rejected_frames()
    for name, case in rejected.items():
        try:
            parse_frame(bytes.fromhex(case['frame']))
        except ValueError as error:
            if case['reason'] not in str(error):
                raise ValueError(f'{name}: expected {case["reason"]!r}, got {str(error)!r}') from error
        else:
            raise ValueError(f'{name}: rejected fixture was accepted')
    output.joinpath('rejected.json').write_text(json.dumps(rejected, indent=1) + '\n')
    print(json.dumps({'streams': len(streams), 'frames': sum(map(len, streams.values())), 'rejected': len(rejected)}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('seed', nargs='?', type=int, default=DEFAULT_SEED)
    arguments = parser.parse_args()
    generate(arguments.output, arguments.seed)


if __name__ == '__main__':
    main()
