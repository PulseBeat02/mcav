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

"""Malformed frames shared by the validation tests and the public rejected corpus."""

import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from mcvideo import format as fmt
from mcvideo.v3 import Node, pack_frame, parse_frame


def changed(data, offset, value, code='B'):
    result = bytearray(data)
    struct.pack_into('<' + code, result, offset, value)
    return bytes(result)


def rejected_frames():
    cases = {}

    def add(name, data, rule, reason):
        cases[name] = {'frame': data.hex(), 'rule': rule, 'reason': reason}

    def frame(node=None, key=True):
        return pack_frame(1, 1, 0 if key else 1, 0, {} if node is None else {0: node})

    empty = frame()
    solid = frame(Node(fmt.SOLID, record=b'\x10\x20\x30'))
    add('short-frame', bytes(19), '9.1', 'frame length')
    add('oversize-frame', bytes(131072), '9.1', 'frame length')
    add('bad-magic', b'ABCD' + empty[4:], '9.1', 'not an MCV2')
    add('unknown-version', changed(empty, 4, 4), '9.1', 'not an MCV2')
    for offset in (5, 6, 7):
        add(f'version-byte-{offset}', changed(empty, offset, 1), '9.1', 'not an MCV2')
    add('mcv1', bytes.fromhex(
        '4d4356310102010001000100090000000900000000000000010000003400000037000000'
        '00000000000000000000000034000002102030'), '9.1', 'version 1 is no longer supported')
    add('version-2', bytes.fromhex(
        '4d4356320205730001000100000000000000000000000000010000004500000075000000000000000000000000000000010000000000000001000000'
        '0000010600000000003224e3e9bafb4931c49e41fca6fb71c25d4744121177650106704ecf2c3e8fa04eaa462d163cd0d18bc59cbc0fbaead8'), '9.1', 'version 2 is no longer supported')
    for offset in (8, 10):
        for value in (0, 4097):
            add(f'dimension-{offset}-{value}', changed(empty, offset, value, 'H'), '9.1', 'dimensions')
    for length in (20, 24, 28, 32, 36, 39):
        add(f'truncated-index-{length}', empty[:length], '9.3', 'truncated index')
    add('unused-mask-bit', changed(empty, 20, 2, 'I'), '9.3', 'presence mask')
    add('directory-zero', changed(empty, 24, 1, 'I'), '9.3', 'directory prefix')
    wide = pack_frame(4096, 65, 0, 0, {0: Node(fmt.SKIP), 256: Node(fmt.SKIP)})
    add('directory-later-prefix', changed(wide, 20 + 12 * 4 + 4, 0, 'I'), '9.3', 'directory prefix')
    add('n0-popcount', changed(solid, 28, 0, 'I'), '9.3', 'level 0 count')
    add('count-overflow', changed(empty, 32, 0xFFFFFFFF, 'I'), '9.3', 'truncated descriptors')
    split = Node(fmt.SPLIT, children=(Node(fmt.SKIP),) * 4)
    branch = frame(split)
    add('n1-children', changed(branch, 40, fmt.SKIP), '9.3', 'level 1 count')
    deep = frame(Node(fmt.SPLIT, children=(split,) * 4))
    add('n2-children', changed(deep, 41, fmt.SKIP), '9.3', 'level 2 count')
    # Removing that split also changes the later walk counts; repair those so this isolates n2.
    data = bytearray(bytes.fromhex(cases['n2-children']['frame']))
    for at in (40 + 21 + 4, 40 + 21 + 8):
        value = struct.unpack_from('<I', data, at)[0]
        struct.pack_into('<I', data, at, value - (1 << 17))
    cases['n2-children']['frame'] = data.hex()
    add('split-at-level-2', changed(deep, 45, fmt.SPLIT), '9.3', 'SPLIT in level 2')
    add('truncated-descriptor-walk', solid[:44], '9.3', 'truncated descriptors')
    add('walk-zero', changed(solid, 41, 1, 'I'), '9.4', 'walk checkpoint')
    long = frame(Node(fmt.SPLIT, children=(Node(fmt.SPLIT, children=(Node(fmt.SOLID, record=bytes(3)),) * 4),) * 4))
    at = 40 + 21 + 4
    checkpoint = struct.unpack_from('<I', long, at)[0]
    add('walk-cursor', changed(long, at, checkpoint + 1, 'I'), '9.4', 'walk checkpoint')
    add('walk-splits', changed(long, at, checkpoint + (1 << 17), 'I'), '9.4', 'walk checkpoint')
    for mode in range(7, 32):
        add(f'invalid-mode-{mode}', changed(solid, 40, mode), '9.5', 'descriptor mode')
    for mode in (fmt.SKIP, fmt.MOTION, fmt.SOLID, fmt.PALETTE, fmt.PATTERN, fmt.SPLIT):
        add(f'quantizer-mode-{mode}', changed(solid, 40, mode | 32), '9.5', 'quantizer')
    compact = frame(Node(fmt.COMPACT, record=bytes(10)), key=False)
    for q in range(3, 8):
        add(f'compact-quantizer-{q}', changed(compact, 40, fmt.COMPACT | q << 5), '9.5', 'COMPACT quantizer above 2')
    for mode in (fmt.MOTION, fmt.COMPACT):
        add(f'keyframe-mode-{mode}', changed(solid, 40, mode), '9.5', 'temporal mode')
    nodes = [Node(fmt.MOTION, record=bytes(2)), Node(fmt.SOLID, record=bytes(3)),
             Node(fmt.PALETTE, record=bytes(134)), Node(fmt.PATTERN, record=bytes(11)),
             Node(fmt.COMPACT, record=bytes(10))]
    for node in nodes:
        data = frame(node, key=False)
        add(f'truncated-record-{node.mode}', data[:-1], '9.5', 'record exceeds')
    pattern = frame(Node(fmt.PATTERN, record=bytes(11)))
    add('pattern-orientation', changed(pattern, parse_frame(pattern).payload_start + 6, 2), '9.5', 'PATTERN orientation')
    offscreen = frame(Node(fmt.SPLIT, children=(Node(fmt.SKIP), Node(fmt.SKIP), Node(fmt.SKIP), Node(fmt.PATTERN, record=bytes(9)))))
    add('off-picture-orientation', changed(offscreen, parse_frame(offscreen).payload_start + 6, 2), '9.5', 'PATTERN orientation')
    eight = frame(Node(fmt.SPLIT, children=(Node(fmt.SPLIT, children=(Node(fmt.PATTERN, record=bytes(8)),)
                                                     + (Node(fmt.SKIP),) * 3),) + (Node(fmt.SKIP),) * 3))
    add('pattern-orientation-8', changed(eight, parse_frame(eight).payload_start + 6, 255), '9.5', 'PATTERN orientation')
    add('payload-gap', solid + b'\0', '9.6', 'records do not end')
    return cases
