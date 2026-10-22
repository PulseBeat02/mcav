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

import struct
from typing import Any

import mcv2_reference
from mcv2_reference import Node, pack_frame, parse_frame

BYTE_ORDER: str = '<'
BYTE_CODE: str = 'B'
HALF_WORD_CODE: str = 'H'
WORD_CODE: str = 'I'
WORD: struct.Struct = struct.Struct(BYTE_ORDER + WORD_CODE)

FRAME_KEY: str = 'frame'

HEADER_RULE: str = '9.1'
INDEX_RULE: str = '9.3'
WALK_RULE: str = '9.4'
DESCRIPTOR_RULE: str = '9.5'
END_RULE: str = '9.6'

FRAME_LENGTH_REASON: str = 'frame length'
NOT_MCV2_REASON: str = 'not an MCV2'
DIRECTORY_PREFIX_REASON: str = 'directory prefix'
TRUNCATED_DESCRIPTORS_REASON: str = 'truncated descriptors'
WALK_CHECKPOINT_REASON: str = 'walk checkpoint'
PATTERN_ORIENTATION_REASON: str = 'PATTERN orientation'

# Index offsets of a frame with a single presence mask.
MASK_OFFSET: int = mcv2_reference.HEADER_BYTES
DIRECTORY_OFFSET: int = MASK_OFFSET + mcv2_reference.WORD_BYTES
COUNTS_OFFSET: int = DIRECTORY_OFFSET + mcv2_reference.WORD_BYTES
DESCRIPTORS_OFFSET: int = COUNTS_OFFSET + mcv2_reference.LEVEL_COUNTS.size

TWO_LEVEL_DESCRIPTORS: int = 1 + mcv2_reference.SPLIT_CHILDREN + mcv2_reference.SPLIT_CHILDREN * mcv2_reference.SPLIT_CHILDREN
TWO_LEVEL_WALK_OFFSET: int = DESCRIPTORS_OFFSET + TWO_LEVEL_DESCRIPTORS

WALK_SPLIT: int = 1 << mcv2_reference.WALK_CURSOR_BITS
QUANTIZER_STEP: int = 1 << mcv2_reference.QUANTIZER_SHIFT
QUANTIZER_VALUES: int = 1 << (mcv2_reference.BITS_PER_BYTE - mcv2_reference.QUANTIZER_SHIFT)
INVALID_ORIENTATION: int = mcv2_reference.HORIZONTAL_STRIPES + 1


def rejected_frames() -> dict[str, dict[str, str]]:
    cases: dict[Any, Any] = {}
    _add_header_cases(cases)
    _add_index_cases(cases)
    _add_walk_cases(cases)
    _add_descriptor_cases(cases)
    _add(cases, 'payload-gap', _solid_frame() + b'\0', END_RULE, 'records do not end')

    return cases


def _add_header_cases(cases: dict[Any, Any]) -> None:
    empty: bytes = _frame()
    _add(cases, 'short-frame', bytes(mcv2_reference.MINIMUM_FRAME_BYTES - 1), HEADER_RULE, FRAME_LENGTH_REASON)
    _add(cases, 'oversize-frame', bytes(mcv2_reference.MAXIMUM_FRAME_BYTES + 1), HEADER_RULE, FRAME_LENGTH_REASON)
    _add(cases, 'bad-magic', b'ABCD' + empty[len(mcv2_reference.MAGIC):], HEADER_RULE, NOT_MCV2_REASON)
    unknown_version: bytes = changed(empty, mcv2_reference.VERSION_OFFSET, mcv2_reference.VERSION + 1)
    _add(cases, 'unknown-version', unknown_version, HEADER_RULE, NOT_MCV2_REASON)
    for offset in range(mcv2_reference.VERSION_OFFSET + 1, mcv2_reference.WIDTH_OFFSET):
        _add(cases, f'version-byte-{offset}', changed(empty, offset, 1), HEADER_RULE, NOT_MCV2_REASON)
    _add_retired_version_cases(cases)
    for offset in (mcv2_reference.WIDTH_OFFSET, mcv2_reference.HEIGHT_OFFSET):
        for value in (0, mcv2_reference.MAXIMUM_DIMENSION + 1):
            _add(cases, f'dimension-{offset}-{value}', changed(empty, offset, value, HALF_WORD_CODE), HEADER_RULE, 'dimensions')


def _frame(node: Node | None = None) -> bytes:
    return pack_frame(1, 1, 0, 0, {} if node is None else {0: node})


def _add(cases: dict[Any, Any] | Any | dict[str, dict[str, str]], name: str, data: bytes, rule: str, reason: str) -> None:
    cases[name] = {FRAME_KEY: data.hex(), 'rule': rule, 'reason': reason}


def changed(data: bytes, offset: int, value: int, code: str = BYTE_CODE) -> bytes:
    result: bytearray = bytearray(data)
    struct.pack_into(BYTE_ORDER + code, result, offset, value)
    return bytes(result)


def _add_retired_version_cases(cases: dict[str, dict[str, str]]) -> None:
    version_1: bytes = bytes.fromhex(
        '4d4356310102010001000100090000000900000000000000010000003400000037000000'
        '00000000000000000000000034000002102030')
    _add(cases, 'mcv1', version_1, HEADER_RULE, 'version 1 is no longer supported')
    version_2: bytes = bytes.fromhex(
        '4d4356320205730001000100000000000000000000000000010000004500000075000000000000000000000000000000010000000000000001000000'
        '0000010600000000003224e3e9bafb4931c49e41fca6fb71c25d4744121177650106704ecf2c3e8fa04eaa462d163cd0d18bc59cbc0fbaead8')
    _add(cases, 'version-2', version_2, HEADER_RULE, 'version 2 is no longer supported')


def _add_index_cases(cases: dict[str, dict[str, str]]) -> None:
    _add_presence_cases(cases)
    _add_count_cases(cases)
    _add(cases, 'truncated-descriptor-walk', _solid_frame()[:44], INDEX_RULE, TRUNCATED_DESCRIPTORS_REASON)


def _add_presence_cases(cases: dict[str, dict[str, str]]) -> None:
    empty: bytes = _frame()
    for length in (20, 24, 28, 32, 36, 39):
        _add(cases, f'truncated-index-{length}', empty[:length], INDEX_RULE, 'truncated index')
    _add(cases, 'unused-mask-bit', changed(empty, MASK_OFFSET, 2, WORD_CODE), INDEX_RULE, 'presence mask')
    _add(cases, 'directory-zero', changed(empty, DIRECTORY_OFFSET, 1, WORD_CODE), INDEX_RULE, DIRECTORY_PREFIX_REASON)

    wide: bytes = pack_frame(4096, 65, 0, 0, {0: Node(mcv2_reference.SKIP), 256: Node(mcv2_reference.SKIP)})
    later_prefix: int = MASK_OFFSET + 12 * mcv2_reference.WORD_BYTES + mcv2_reference.WORD_BYTES
    _add(cases, 'directory-later-prefix', changed(wide, later_prefix, 0, WORD_CODE), INDEX_RULE, DIRECTORY_PREFIX_REASON)


def _add_count_cases(cases: dict[str, dict[str, str]]) -> None:
    _add(cases, 'n0-popcount', changed(_solid_frame(), COUNTS_OFFSET, 0, WORD_CODE), INDEX_RULE, 'level 0 count')
    level_one_count: int = COUNTS_OFFSET + mcv2_reference.WORD_BYTES
    count_overflow: bytes = changed(_frame(), level_one_count, 0xFFFFFFFF, WORD_CODE)
    _add(cases, 'count-overflow', count_overflow, INDEX_RULE, TRUNCATED_DESCRIPTORS_REASON)

    split: Node = _skip_split()
    branch: bytes = _frame(split)
    _add(cases, 'n1-children', changed(branch, DESCRIPTORS_OFFSET, mcv2_reference.SKIP), INDEX_RULE, 'level 1 count')

    deep: bytes = _frame(Node(mcv2_reference.SPLIT, children=(split,) * mcv2_reference.SPLIT_CHILDREN))
    _add(cases, 'n2-children', _without_level_one_split(deep), INDEX_RULE, 'level 2 count')
    _add(cases, 'split-at-level-2', changed(deep, DESCRIPTORS_OFFSET + 5, mcv2_reference.SPLIT), INDEX_RULE, 'SPLIT in level 2')


def _solid_frame() -> bytes:
    return _frame(Node(mcv2_reference.SOLID, record=b'\x10\x20\x30'))


def _skip_split() -> Node:
    return Node(mcv2_reference.SPLIT, children=(Node(mcv2_reference.SKIP),) * mcv2_reference.SPLIT_CHILDREN)


def _without_level_one_split(deep: bytes) -> bytes:
    data: bytearray = bytearray(changed(deep, DESCRIPTORS_OFFSET + 1, mcv2_reference.SKIP))
    # Removing that split also changes the later walk counts; repair those so this isolates n2.
    for offset in (TWO_LEVEL_WALK_OFFSET + mcv2_reference.WORD_BYTES, TWO_LEVEL_WALK_OFFSET + 2 * mcv2_reference.WORD_BYTES):
        value: Any = WORD.unpack_from(data, offset)[0]
        WORD.pack_into(data, offset, value - WALK_SPLIT)

    return bytes(data)


def _add_walk_cases(cases: dict[str, dict[str, str]]) -> None:
    _add(cases, 'walk-zero', changed(_solid_frame(), DESCRIPTORS_OFFSET + 1, 1, WORD_CODE), WALK_RULE, WALK_CHECKPOINT_REASON)
    solid_leaves: tuple[Node, ...] = (Node(mcv2_reference.SOLID, record=bytes(mcv2_reference.SOLID_BYTES)),) * mcv2_reference.SPLIT_CHILDREN
    solid_split: Node = Node(mcv2_reference.SPLIT, children=solid_leaves)
    long: bytes = _frame(Node(mcv2_reference.SPLIT, children=(solid_split,) * mcv2_reference.SPLIT_CHILDREN))
    offset: int = TWO_LEVEL_WALK_OFFSET + mcv2_reference.WORD_BYTES
    checkpoint: Any = WORD.unpack_from(long, offset)[0]
    _add(cases, 'walk-cursor', changed(long, offset, checkpoint + 1, WORD_CODE), WALK_RULE, WALK_CHECKPOINT_REASON)
    _add(cases, 'walk-splits', changed(long, offset, checkpoint + WALK_SPLIT, WORD_CODE), WALK_RULE, WALK_CHECKPOINT_REASON)


def _add_descriptor_cases(cases: dict[str, dict[str, str]]) -> None:
    _add_mode_cases(cases)
    _add_quantizer_cases(cases)
    _add_keyframe_mode_cases(cases)
    _add_truncated_record_cases(cases)
    _add_orientation_cases(cases)


def _add_mode_cases(cases: dict[str, dict[str, str]]) -> None:
    solid: bytes = _solid_frame()
    for mode in range(mcv2_reference.SPLIT + 1, mcv2_reference.MODE_MASK + 1):
        _add(cases, f'invalid-mode-{mode}', changed(solid, DESCRIPTORS_OFFSET, mode), DESCRIPTOR_RULE, 'descriptor mode')


def _add_quantizer_cases(cases: dict[str, dict[str, str]]) -> None:
    solid: bytes = _solid_frame()
    for mode in (
        mcv2_reference.SKIP,
        mcv2_reference.MOTION,
        mcv2_reference.SOLID,
        mcv2_reference.PALETTE,
        mcv2_reference.PATTERN,
        mcv2_reference.SPLIT,
    ):
        _add(cases, f'quantizer-mode-{mode}', changed(solid, DESCRIPTORS_OFFSET, mode | QUANTIZER_STEP), DESCRIPTOR_RULE, 'quantizer')

    compact: bytes = _predicted_frame(Node(mcv2_reference.COMPACT, record=bytes(mcv2_reference.COMPACT_BYTES)))
    for quantizer in range(mcv2_reference.MAXIMUM_QUANTIZER + 1, QUANTIZER_VALUES):
        descriptor: int = mcv2_reference.COMPACT | quantizer << mcv2_reference.QUANTIZER_SHIFT
        data: bytes = changed(compact, DESCRIPTORS_OFFSET, descriptor)
        _add(cases, f'compact-quantizer-{quantizer}', data, DESCRIPTOR_RULE, 'COMPACT quantizer above 2')


def _predicted_frame(node: Node) -> bytes:
    return pack_frame(1, 1, 1, 0, {0: node})


def _add_keyframe_mode_cases(cases: dict[str, dict[str, str]]) -> None:
    solid: bytes = _solid_frame()
    for mode in (mcv2_reference.MOTION, mcv2_reference.COMPACT):
        _add(cases, f'keyframe-mode-{mode}', changed(solid, DESCRIPTORS_OFFSET, mode), DESCRIPTOR_RULE, 'temporal mode')


def _add_truncated_record_cases(cases: dict[str, dict[str, str]]) -> None:
    nodes: list[Node] = [Node(mcv2_reference.MOTION, record=bytes(mcv2_reference.MOTION_BYTES)),
             Node(mcv2_reference.SOLID, record=bytes(mcv2_reference.SOLID_BYTES)),
             Node(mcv2_reference.PALETTE, record=bytes(134)), Node(mcv2_reference.PATTERN, record=bytes(11)),
             Node(mcv2_reference.COMPACT, record=bytes(mcv2_reference.COMPACT_BYTES))]
    for node in nodes:
        data: bytes = _predicted_frame(node)
        _add(cases, f'truncated-record-{node.mode}', data[:-1], DESCRIPTOR_RULE, 'record exceeds')


def _add_orientation_cases(cases: dict[str, dict[str, str]]) -> None:
    pattern: bytes = _frame(Node(mcv2_reference.PATTERN, record=bytes(11)))
    _add(cases, 'pattern-orientation', _with_invalid_orientation(pattern, INVALID_ORIENTATION), DESCRIPTOR_RULE, PATTERN_ORIENTATION_REASON)

    offscreen_children: tuple[Node, Node, Node, Node] = (
        Node(mcv2_reference.SKIP),
        Node(mcv2_reference.SKIP),
        Node(mcv2_reference.SKIP),
        Node(mcv2_reference.PATTERN, record=bytes(9)),
    )
    offscreen: bytes = _frame(Node(mcv2_reference.SPLIT, children=offscreen_children))
    offscreen_orientation: bytes = _with_invalid_orientation(offscreen, INVALID_ORIENTATION)
    _add(cases, 'off-picture-orientation', offscreen_orientation, DESCRIPTOR_RULE, PATTERN_ORIENTATION_REASON)

    skips: tuple[Node, Node, Node] = (Node(mcv2_reference.SKIP),) * 3
    corner: Node = Node(mcv2_reference.SPLIT, children=(Node(mcv2_reference.PATTERN, record=bytes(8)),) + skips)
    eight: bytes = _frame(Node(mcv2_reference.SPLIT, children=(corner,) + skips))
    _add(cases, 'pattern-orientation-8', _with_invalid_orientation(eight, 255), DESCRIPTOR_RULE, PATTERN_ORIENTATION_REASON)


def _with_invalid_orientation(data: bytes, orientation: int) -> bytes:
    offset: int = parse_frame(data).payload_start + mcv2_reference.PATTERN_ORIENTATION_INDEX
    return changed(data, offset, orientation)
