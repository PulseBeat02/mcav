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
from mcvideo.v3 import Node, pack_frame, parse_frame

DEFAULT_SEED = 20261008


def rbytes(randomizer, count):
    return bytes(randomizer.randrange(256) for _ in range(count))


def compact_record(randomizer):
    return rbytes(randomizer, fmt.COMPACT_BYTES)


def pattern_record(randomizer, size):
    return rbytes(randomizer, 6) + bytes([randomizer.randrange(2)]) + rbytes(randomizer, size // 8)


def leaf(randomizer, size, keyframe, mode=None):
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
        return Node(mode, record=pattern_record(randomizer, size))
    return Node(fmt.COMPACT, randomizer.randrange(fmt.MAX_QUANTIZER + 1), compact_record(randomizer))


def tree(randomizer, size, keyframe, split_chance=0.65):
    if size > 8 and randomizer.random() < split_chance:
        return Node(fmt.SPLIT, children=tuple(tree(randomizer, size // 2, keyframe, split_chance) for _ in range(4)))
    return leaf(randomizer, size, keyframe)


def random_stream(randomizer, width=None, height=None, frame_count=6):
    width = randomizer.randint(1, 200) if width is None else width
    height = randomizer.randint(1, 130) if height is None else height
    count = ((width + 31) // 32) * ((height + 31) // 32)
    stream = []
    for frame_id in range(frame_count):
        keyframe = frame_id == 0 or randomizer.random() < 0.2
        roots = {i: tree(randomizer, 32, keyframe) for i in range(count) if randomizer.random() > 0.2}
        stream.append(pack_frame(width, height, frame_id, frame_id if keyframe else frame_id - 1, roots))
    return stream


def repeated(node, size):
    while size < 32:
        node = Node(fmt.SPLIT, children=(node,) * 4)
        size *= 2
    return node


def mode_stream(randomizer):
    nodes = []
    for size in (8, 16, 32):
        nodes.extend(repeated(leaf(randomizer, size, False, mode), size) for mode in range(fmt.COMPACT))
        for q in range(fmt.MAX_QUANTIZER + 1):
            for vector, luma in ((b'\0\0', rbytes(randomizer, 8)), (b'\x80\x7f', bytes([0x77]) * 8),
                                 (b'\x7f\x80', bytes([0x88]) * 8), (rbytes(randomizer, 2), rbytes(randomizer, 8))):
                nodes.append(repeated(Node(fmt.COMPACT, q, vector + luma), size))
    height = (len(nodes) + 15) // 16 * 32
    key_roots = {i: repeated(leaf(randomizer, size, True, mode), size)
                 for i, (size, mode) in enumerate((size, mode) for size in (8, 16, 32)
                                                   for mode in (fmt.SKIP, fmt.SOLID, fmt.PALETTE, fmt.PATTERN))}
    return [pack_frame(512, height, 0, 0, key_roots), pack_frame(512, height, 1, 0, dict(enumerate(nodes)))]


def pattern_stream(randomizer):
    """Every size, orientation and axis extreme, with endpoints no 5- or 6-bit channel could carry."""
    endpoints = (bytes([1, 254, 3, 255, 0, 129]), bytes([127, 128, 77, 2, 253, 250]))
    roots, index = {}, 0
    for size in (8, 16, 32):
        for orientation in (0, 1):
            for axis in (bytes(size // 8), bytes([255]) * (size // 8), bytes([0xA5]) * (size // 8),
                         rbytes(randomizer, size // 8)):
                roots[index] = repeated(Node(fmt.PATTERN, record=endpoints[index % 2] + bytes([orientation]) + axis), size)
                index += 1
    swapped = {i: Node(fmt.SKIP) if i % 3 == 0 else repeated(Node(fmt.PATTERN, record=pattern_record(randomizer, 8)), 8)
               for i in roots}
    return [pack_frame(256, 96, 0, 0, roots), pack_frame(256, 96, 1, 0, swapped)]


def build_streams(seed=DEFAULT_SEED):
    randomizer = random.Random(seed)
    streams = {'edge-modes.mcs': mode_stream(randomizer), 'edge-patterns.mcs': pattern_stream(randomizer)}
    for name, width, height in [('tiny', 1, 1), ('vertical', 1, 97), ('horizontal', 97, 1), ('cropped', 97, 65),
                                ('directory', 4096, 65)]:
        streams[f'edge-{name}.mcs'] = random_stream(randomizer, width, height)
    streams['edge-absent.mcs'] = [pack_frame(33, 17, 0, 0, {}), pack_frame(33, 17, 1, 0, {})]
    motion = [pack_frame(33, 17, 0, 0, {0: Node(fmt.SOLID, record=b'\xff\x80\0'), 1: Node(fmt.SOLID, record=b'\4\x32\x96')})]
    for index, vector in enumerate((b'\x80\x7f', b'\x7f\x80', b'\x80\x80', b'\x7f\x7f'), 1):
        motion.append(pack_frame(33, 17, index, index - 1,
                                 {0: Node(fmt.MOTION, record=vector),
                                  1: repeated(Node(fmt.COMPACT, 2, vector + bytes(8)), 8)}))
    streams['edge-motion.mcs'] = motion
    long_roots = {i: Node(fmt.SPLIT, children=tuple(Node(fmt.SPLIT, children=tuple(
        Node(fmt.COMPACT, (i + j) % (fmt.MAX_QUANTIZER + 1), compact_record(randomizer))
        for j in range(4))) for _ in range(4))) for i in range(440)}
    streams['edge-long-walk.mcs'] = [pack_frame(1024, 448, 0, 0, {}), pack_frame(1024, 448, 1, 0, long_roots)]
    roots = {i: repeated(Node(fmt.SKIP), 8) for i in range(4086)}
    streams['edge-max-splits.mcs'] = [pack_frame(4096, 4096, 0, 0, roots)]
    roots = {i: Node(fmt.PALETTE, record=rbytes(randomizer, 134)) for i in range(965)}
    roots[965] = Node(fmt.PATTERN, record=pattern_record(randomizer, 32))
    roots.update({i: Node(fmt.SOLID, record=rbytes(randomizer, 3)) for i in range(966, 993)})
    streams['edge-length-limit.mcs'] = [pack_frame(1024, 1024, 0, 0, roots)]
    streams['edge-wrap.mcs'] = [pack_frame(1, 1, frame_id, frame_id if index == 0 else frame_id - 1 & fmt.ID_MASK,
                                          {0: Node(fmt.SOLID, record=b'\x1b\x40\x80')} if index == 0 else {})
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
