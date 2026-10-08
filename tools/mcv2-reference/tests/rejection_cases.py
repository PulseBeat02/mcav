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


def resized(data):
    return changed(data, 24, len(data), 'I')


def rejected_frames():
    cases = {}

    def add(name, data, rule, reason):
        cases[name] = {'frame': data.hex(), 'rule': rule, 'reason': reason}

    def frame(node=None, key=True, **options):
        return pack_frame(1, 1, 0 if key else 1, 0, key, (0, 0, 0), {} if node is None else {0: node}, **options)

    empty = frame()
    solid = frame(Node(fmt.SOLID, record=b'\x10\x20\x30'))
    add('short-frame', bytes(31), '9.1', 'frame length')
    add('oversize-frame', bytes(131072), '9.1', 'frame length')
    add('bad-magic', b'ABCD' + empty[4:], '9.1', 'not an MCV2')
    add('unknown-version', changed(empty, 4, 4), '9.1', 'not an MCV2')
    add('mcv1', bytes.fromhex(
        '4d4356310102010001000100090000000900000000000000010000003400000037000000'
        '00000000000000000000000034000002102030'), '9.1', 'version 1 is no longer supported')
    add('version-2', bytes.fromhex(
        '4d4356320205730001000100000000000000000000000000010000004500000075000000000000000000000000000000010000000000000001000000'
        '0000010600000000003224e3e9bafb4931c49e41fca6fb71c25d4744121177650106704ecf2c3e8fa04eaa462d163cd0d18bc59cbc0fbaead8'), '9.1', 'version 2 is no longer supported')
    for bit in range(1, 8):
        add(f'reserved-flag-{bit}', changed(empty, 5, 1 | 1 << bit), '9.1', 'reserved flags')
    for offset in (6, 7, 31):
        add(f'reserved-byte-{offset}', changed(empty, offset, 1), '9.1', 'reserved header')
    for value in (len(empty) - 1, len(empty) + 1):
        add(f'wrong-total-{value}', changed(empty, 24, value, 'I'), '9.1', 'total does not equal')
    for offset in (8, 10):
        for value in (0, 4097):
            add(f'dimension-{offset}-{value}', changed(empty, offset, value, 'H'), '9.1', 'dimensions')
    add('keyframe-reference', changed(empty, 16, 1, 'I'), '9.2', 'id relationship')
    add('pframe-self-reference', changed(empty, 5, 0), '9.2', 'id relationship')
    pframe = frame(key=False)
    for channel in range(3):
        add(f'pframe-default-{channel}', changed(pframe, 28 + channel, 1), '9.2', 'default colour')
    for length in (32, 36, 40, 44, 52, 55):
        add(f'truncated-index-{length}', resized(empty[:length]), '9.3', 'truncated index')
    for delta in (-1, 1):
        add(f'payload-start-{delta}', changed(solid, 20, parse_frame(solid).payload_start + delta, 'I'), '9.3', 'payload start')
    add('unused-mask-bit', changed(empty, 32, 2, 'I'), '9.3', 'presence mask')
    add('directory-zero', changed(empty, 36, 1, 'I'), '9.3', 'directory prefix')
    wide = pack_frame(4096, 65, 0, 0, True, (0, 0, 0), {0: Node(fmt.SKIP), 256: Node(fmt.SKIP)})
    add('directory-later-prefix', changed(wide, 32 + 12 * 4 + 4, 0, 'I'), '9.3', 'directory prefix')
    add('n0-popcount', changed(solid, 40, 0, 'I'), '9.3', 'level 0 count')
    add('count-overflow', changed(empty, 44, 0xFFFFFFFF, 'I'), '9.3', 'truncated descriptors')
    split = Node(fmt.SPLIT, children=(Node(fmt.SKIP),) * 4)
    branch = frame(split)
    add('n1-children', changed(branch, 52, fmt.SKIP), '9.3', 'level 1 count')
    deep = frame(Node(fmt.SPLIT, children=(split,) * 4))
    add('n2-children', changed(deep, 53, fmt.SKIP), '9.3', 'level 2 count')
    # Removing that split also changes the later walk counts; repair those so this isolates n2.
    data = bytearray(bytes.fromhex(cases['n2-children']['frame']))
    for at in (52 + 21 + 4, 52 + 21 + 8):
        value = struct.unpack_from('<I', data, at)[0]
        struct.pack_into('<I', data, at, value - (1 << 17))
    cases['n2-children']['frame'] = data.hex()
    add('split-at-level-2', changed(deep, 57, fmt.SPLIT), '9.3', 'SPLIT in level 2')
    add('truncated-descriptor-walk', resized(solid[:58]), '9.3', 'truncated descriptors')
    add('walk-zero', changed(solid, 53, 1, 'I'), '9.4', 'walk checkpoint')
    long = frame(Node(fmt.SPLIT, children=(Node(fmt.SPLIT, children=(Node(fmt.SOLID, record=bytes(3)),) * 4),) * 4))
    at = 52 + 21 + 4
    checkpoint = struct.unpack_from('<I', long, at)[0]
    add('walk-cursor', changed(long, at, checkpoint + 1, 'I'), '9.4', 'walk checkpoint')
    add('walk-splits', changed(long, at, checkpoint + (1 << 17), 'I'), '9.4', 'walk checkpoint')
    for mode in range(7, 32):
        add(f'invalid-mode-{mode}', changed(solid, 52, mode), '9.5', 'descriptor mode')
    for mode in (fmt.SKIP, fmt.MOTION, fmt.SOLID, fmt.PALETTE, fmt.PATTERN, fmt.SPLIT):
        add(f'quantizer-mode-{mode}', changed(solid, 52, mode | 32), '9.5', 'quantizer')
    for mode in (fmt.MOTION, fmt.COMPACT):
        add(f'keyframe-mode-{mode}', changed(solid, 52, mode), '9.5', 'temporal mode')
    nodes = [Node(fmt.MOTION, record=bytes(2)), Node(fmt.SOLID, record=bytes(3)),
             Node(fmt.PALETTE, record=bytes(134)), Node(fmt.PATTERN, record=bytes(11)),
             Node(fmt.COMPACT, record=b'\x22' + bytes(10))]
    for node in nodes:
        data = frame(node, key=False)
        add(f'truncated-record-{node.mode}', resized(data[:-1]), '9.5', 'record exceeds')
    compact = frame(Node(fmt.COMPACT, record=b'\x00\x00'), key=False)
    offset = parse_frame(compact).payload_start
    add('missing-compact-control', resized(compact[:offset]), '9.5', 'COMPACT control')
    for kind in range(3, 16):
        add(f'compact-class-{kind}', changed(compact, offset, kind), '9.5', 'COMPACT class')
    for form in range(3, 16):
        add(f'compact-form-{form}', changed(compact, offset, form << 4), '9.5', 'motion form')
    pattern = frame(Node(fmt.PATTERN, record=bytes(11)))
    add('pattern-orientation', changed(pattern, parse_frame(pattern).payload_start + 6, 2), '9.5', 'PATTERN orientation')
    tabled = frame(Node(fmt.PATTERN, record=bytes(11)), endpoint_table=[bytes(4)], selector_tables={32: [bytes(5)]})
    offset = parse_frame(tabled).payload_start
    add('endpoint-index', changed(tabled, offset, 1), '9.5', 'endpoint index')
    add('selector-index', changed(tabled, offset + 1, 1), '9.5', 'selector index')
    for size in (8, 16, 32):
        table = frame(selector_tables={size: [bytes(1 + size // 8)]})
        add(f'table-orientation-{size}', changed(table, parse_frame(table).payload_start, 2), '9.5', 'table orientation')
    offscreen = frame(Node(fmt.SPLIT, children=(Node(fmt.SKIP), Node(fmt.SKIP), Node(fmt.SKIP), Node(fmt.PATTERN, record=bytes(9)))))
    add('off-picture-orientation', changed(offscreen, parse_frame(offscreen).payload_start + 6, 2), '9.5', 'PATTERN orientation')
    add('payload-gap', resized(solid + b'\0'), '9.6', 'records do not end')
    add('tables-overlap-index', changed(empty, 52, 255), '9.6', 'tables overlap')
    add('truncated-endpoint-tail', resized(tabled[:-1]), '9.6', 'record exceeds')
    add('gap-before-tables', resized(tabled[:offset + 2] + b'\0' + tabled[offset + 2:]), '9.6', 'records do not end')
    table = frame(endpoint_table=[bytes(4), b'\1\0\0\0'])
    add('duplicate-endpoints', table[:-4] + bytes(4), '9.6', 'duplicate endpoint')
    for size in (8, 16, 32):
        length = 1 + size // 8
        table = frame(selector_tables={size: [bytes(length), b'\1' + bytes(length - 1)]})
        add(f'duplicate-selector-{size}', table[:-length] + bytes(length), '9.6', 'duplicate selector')
    return cases
