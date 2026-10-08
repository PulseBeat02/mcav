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

"""Validated v3 frames and the canonical block-tree serializer (specification §§3–9)."""

import struct
from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from . import format as fmt


@dataclass(frozen=True)
class Node:
    """A leaf with whole PATTERN records, or a SPLIT with four children in quadrant order."""

    mode: int
    q: int = 0
    record: bytes = b""
    children: tuple["Node", ...] = ()


@dataclass(frozen=True)
class Leaf:
    x: int
    y: int
    size: int
    mode: int
    q: int
    offset: int | None
    record: bytes
    descriptor_index: int | None


@dataclass(frozen=True)
class Frame:
    width: int
    height: int
    frame_id: int
    reference_id: int
    keyframe: bool
    default_color: tuple[int, int, int]
    payload_start: int
    total: int
    leaves: tuple[Leaf, ...]
    masks: tuple[int, ...]
    directory: tuple[int, ...]
    level_counts: tuple[int, int, int]
    descriptors: bytes
    walk: tuple[int, ...]
    table_counts: tuple[int, int, int, int]
    endpoint_table: tuple[bytes, ...]
    selector_tables: dict[int, tuple[bytes, ...]]
    roots: dict[int, Node]

    @property
    def flags(self) -> int:
        return int(self.keyframe)


def expand_endpoints(pair: bytes) -> bytes:
    """Expand one four-byte RGB565 pair to six RGB bytes, without rounding."""
    colors = []
    for value in struct.unpack("<HH", pair):
        red, green, blue = value >> 11, (value >> 5) & 63, value & 31
        colors.extend((red << 3 | red >> 2, green << 2 | green >> 4, blue << 3 | blue >> 2))
    return bytes(colors)


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def _record_length(mode: int, size: int, counts: tuple, control: int = 0) -> int:
    if mode in (fmt.SKIP, fmt.SPLIT):
        return 0
    if mode == fmt.MOTION:
        return 2
    if mode == fmt.SOLID:
        return 3
    if mode == fmt.PALETTE:
        return 6 + size * size // 8
    if mode == fmt.PATTERN:
        return (1 if counts[0] else 6) + (1 if counts[{8: 1, 16: 2, 32: 3}[size]] else 1 + size // 8)
    kind, form = control & 15, control >> 4
    _require(kind <= fmt.GRID_Y, "invalid COMPACT class")
    _require(form <= fmt.MAX_MOTION_FORM, "invalid COMPACT motion form")
    return 1 + form + fmt.BODY_BYTES[kind]


def _whole_pattern(record: bytes, size: int, endpoints: tuple, selectors: dict) -> bytes:
    position = 1 if endpoints else 6
    colors = expand_endpoints(endpoints[record[0]]) if endpoints else record[:6]
    word = selectors[size][record[position]] if selectors[size] else record[position:]
    return colors + word


def parse_frame(data: bytes) -> Frame:
    """Accept exactly the syntax of §9; never read a field before checking its extent."""
    if data[:4] == b"MCV1":
        raise ValueError("MCV1 version 1 is no longer supported; re-encode")
    if data[:5] == b"MCV2\x02":
        raise ValueError("MCV2 version 2 is no longer supported; re-encode")
    total = len(data)
    _require(fmt.MIN_FRAME_BYTES <= total <= fmt.MAX_FRAME_BYTES, "invalid frame length")
    magic, version, flags, reserved, width, height, frame_id, reference_id, payload, declared, r, g, b, zero = fmt.HEADER.unpack_from(data)
    _require(magic == fmt.MAGIC and version == fmt.VERSION, "not an MCV2 version 3 frame")
    _require(flags & ~fmt.KEYFRAME == 0, "reserved flags must be zero")
    _require(reserved == 0 and zero == 0, "reserved header bytes must be zero")
    _require(declared == total, "total does not equal frame length")
    _require(1 <= width <= fmt.MAX_DIMENSION and 1 <= height <= fmt.MAX_DIMENSION, "invalid picture dimensions")
    keyframe = bool(flags & fmt.KEYFRAME)
    _require((frame_id == reference_id) == keyframe, "invalid frame/reference id relationship")
    _require(keyframe or (r, g, b) == (0, 0, 0), "P frame default colour must be zero")

    columns = (width + 31) // 32
    root_count = columns * ((height + 31) // 32)
    groups = (root_count + 31) // 32
    checkpoints = (groups + 7) // 8
    counts_offset = 32 + 4 * groups + 4 * checkpoints
    _require(counts_offset + 12 + 4 <= total, "truncated index")
    masks = struct.unpack_from(f"<{groups}I", data, 32)
    directory = struct.unpack_from(f"<{checkpoints}I", data, 32 + 4 * groups)
    _require(masks[-1] >> ((root_count - 1) % 32 + 1) == 0, "presence mask has out-of-picture superblocks")
    present = 0
    for index, mask in enumerate(masks):
        if index % 8 == 0:
            _require(directory[index // 8] == present, "directory prefix mismatch")
        present += mask.bit_count()
    n0, n1, n2 = levels = struct.unpack_from("<III", data, counts_offset)
    _require(n0 == present, "level 0 count differs from presence masks")
    count = n0 + n1 + n2
    walk_count = (count + 7) // 8
    descriptor_offset = counts_offset + 12
    expected_payload = descriptor_offset + count + 4 * walk_count + 4
    _require(expected_payload <= total, "truncated descriptors or walk")
    _require(payload == expected_payload, "payload start does not equal index end")
    descriptors = data[descriptor_offset:descriptor_offset + count]
    walk = struct.unpack_from(f"<{walk_count}I", data, descriptor_offset + count)
    table_counts = tuple(data[payload - 4:payload])
    pairs, c8, c16, c32 = table_counts
    payload_end = total - 4 * pairs - 2 * c8 - 3 * c16 - 5 * c32
    _require(payload_end >= payload, "tables overlap the index")

    selector_tables = {}
    position = payload_end
    for size, entries in ((8, c8), (16, c16), (32, c32)):
        length = 1 + size // 8
        words = tuple(data[position + j * length:position + (j + 1) * length] for j in range(entries))
        _require(all(word[0] <= 1 for word in words), "invalid selector table orientation")
        _require(len(set(words)) == entries, "duplicate selector table word")
        selector_tables[size] = words
        position += entries * length
    endpoint_table = tuple(data[position + j * 4:position + (j + 1) * 4] for j in range(pairs))
    _require(len(set(endpoint_table)) == pairs, "duplicate endpoint pair")

    offsets, records = [], []
    cursor = splits = splits0 = splits1 = 0
    for index, descriptor in enumerate(descriptors):
        if index % 8 == 0:
            _require(walk[index // 8] == cursor | splits << 17, "walk checkpoint mismatch")
        size = 32 if index < n0 else 16 if index < n0 + n1 else 8
        mode, q = descriptor & 31, descriptor >> 5
        _require(mode <= fmt.SPLIT, "invalid descriptor mode")
        _require(mode == fmt.COMPACT or q == 0, "non-COMPACT quantizer must be zero")
        _require(not keyframe or mode not in (fmt.MOTION, fmt.COMPACT), "temporal mode in keyframe")
        _require(mode != fmt.SPLIT or size != 8, "SPLIT in level 2")
        offset = payload + cursor
        if mode == fmt.COMPACT:
            _require(offset < payload_end, "truncated COMPACT control")
        length = _record_length(mode, size, table_counts, data[offset] if mode == fmt.COMPACT else 0)
        _require(offset + length <= payload_end, "record exceeds payload region")
        record = data[offset:offset + length]
        if mode == fmt.PATTERN:
            if pairs:
                _require(record[0] < pairs, "PATTERN endpoint index out of range")
            word_offset = 1 if pairs else 6
            if selector_tables[size]:
                _require(record[word_offset] < len(selector_tables[size]), "PATTERN selector index out of range")
            else:
                _require(record[word_offset] <= 1, "invalid PATTERN orientation")
        offsets.append(offset)
        records.append(record)
        cursor += length
        if mode == fmt.SPLIT:
            splits += 1
            splits0 += index < n0
            splits1 += n0 <= index < n0 + n1
    _require(n1 == 4 * splits0, "level 1 count differs from SPLIT children")
    _require(n2 == 4 * splits1, "level 2 count differs from SPLIT children")
    _require(payload + cursor == payload_end, "records do not end at selector tables")

    root_indexes = [i for i in range(root_count) if masks[i // 32] >> (i % 32) & 1]
    coordinates = [(32 * (i % columns), 32 * (i // columns), 32) for i in root_indexes]
    leaves, children = [], {}
    for index, descriptor in enumerate(descriptors):
        x, y, size = coordinates[index]
        mode, q = descriptor & 31, descriptor >> 5
        if mode == fmt.SPLIT:
            half = size // 2
            children[index] = len(coordinates)
            coordinates.extend((x + dx * half, y + dy * half, half) for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)))
        else:
            leaves.append(Leaf(x, y, size, mode, q, offsets[index], records[index], index))
    for i in range(root_count):
        if not masks[i // 32] >> (i % 32) & 1:
            leaves.append(Leaf(32 * (i % columns), 32 * (i // columns), 32, fmt.SKIP, 0, None, b"", None))
    nodes = {}
    for index in reversed(range(count)):
        descriptor = descriptors[index]
        mode, q = descriptor & 31, descriptor >> 5
        if mode == fmt.SPLIT:
            nodes[index] = Node(mode, children=tuple(nodes[children[index] + j] for j in range(4)))
        else:
            record = records[index]
            if mode == fmt.PATTERN:
                record = _whole_pattern(record, coordinates[index][2], endpoint_table, selector_tables)
            nodes[index] = Node(mode, q, record)
    roots = {root: nodes[index] for index, root in enumerate(root_indexes)}
    return Frame(width, height, frame_id, reference_id, keyframe, (r, g, b), payload, total, tuple(leaves),
                 masks, directory, levels, descriptors, walk, table_counts, endpoint_table, selector_tables, roots)


def pack_frame(width: int, height: int, frame_id: int, reference_id: int, keyframe: bool,
               default_color: Sequence[int], roots: Mapping[int, Node],
               endpoint_table: Sequence[bytes] | None = None,
               selector_tables: Mapping[int, Sequence[bytes]] | None = None) -> bytes:
    """Serialize a tree canonically. Tables contain raw four-byte pairs and whole selector words.

    PATTERN leaves always supply six RGB bytes and a whole selector word. When a table is
    supplied, each corresponding value must match an entry exactly (RGB565 after expansion).
    Table order is preserved, including unused entries. Absent roots stay absent.
    """
    _require(1 <= width <= fmt.MAX_DIMENSION and 1 <= height <= fmt.MAX_DIMENSION, "invalid picture dimensions")
    _require(0 <= frame_id <= fmt.ID_MASK and 0 <= reference_id <= fmt.ID_MASK, "ids must be u32")
    _require(len(default_color) == 3 and all(0 <= c <= 255 for c in default_color), "invalid default colour")
    endpoints = tuple(endpoint_table or ())
    selectors = {size: tuple((selector_tables or {}).get(size, ())) for size in (8, 16, 32)}
    _require(not set(selector_tables or {}) - {8, 16, 32}, "invalid selector size")
    _require(len(endpoints) <= 255 and all(len(pair) == 4 for pair in endpoints), "invalid endpoint table extent")
    _require(len(set(endpoints)) == len(endpoints), "duplicate endpoint pair")
    for size, words in selectors.items():
        _require(len(words) <= 255 and all(len(word) == 1 + size // 8 for word in words), "invalid selector table extent")
        _require(all(word[0] <= 1 for word in words), "invalid selector table orientation")
        _require(len(set(words)) == len(words), "duplicate selector table word")
    counts = (len(endpoints), *(len(selectors[size]) for size in (8, 16, 32)))
    endpoint_indexes = {expand_endpoints(pair): index for index, pair in enumerate(endpoints)}
    selector_indexes = {size: {word: index for index, word in enumerate(words)} for size, words in selectors.items()}
    root_count = ((width + 31) // 32) * ((height + 31) // 32)
    _require(all(isinstance(i, int) and 0 <= i < root_count for i in roots), "root index out of range")
    masks = [0] * ((root_count + 31) // 32)
    for i in roots:
        masks[i // 32] |= 1 << (i % 32)
    directory, present = [], 0
    for index, mask in enumerate(masks):
        if index % 8 == 0:
            directory.append(present)
        present += mask.bit_count()

    levels, descriptors, records, walk = [], bytearray(), bytearray(), []
    current = [roots[i] for i in sorted(roots)]
    splits = 0
    for size in fmt.LEAF_SIZES:
        levels.append(len(current))
        following = []
        for node in current:
            _require(0 <= node.mode <= fmt.SPLIT and 0 <= node.q <= 7, "invalid node descriptor")
            _require(node.mode == fmt.COMPACT or node.q == 0, "non-COMPACT quantizer must be zero")
            _require(not keyframe or node.mode not in (fmt.MOTION, fmt.COMPACT), "temporal mode in keyframe")
            if len(descriptors) % 8 == 0:
                walk.append(len(records) | splits << 17)
            descriptors.append(node.mode | node.q << 5)
            if node.mode == fmt.SPLIT:
                _require(size > 8 and len(node.children) == 4 and not node.record, "invalid SPLIT node")
                following.extend(node.children)
                splits += 1
                continue
            _require(not node.children, "leaf has children")
            record = node.record
            if node.mode == fmt.PATTERN:
                _require(len(record) == 7 + size // 8, "PATTERN requires a whole record")
                _require(record[6] <= 1, "invalid PATTERN orientation")
                colors, word = record[:6], record[6:]
                if endpoints:
                    _require(colors in endpoint_indexes, "PATTERN endpoints missing from table")
                    colors = bytes([endpoint_indexes[colors]])
                if selectors[size]:
                    _require(word in selector_indexes[size], "PATTERN selector missing from table")
                    word = bytes([selector_indexes[size][word]])
                record = colors + word
            _require(node.mode != fmt.COMPACT or bool(record), "truncated COMPACT control")
            length = _record_length(node.mode, size, counts, record[0] if node.mode == fmt.COMPACT else 0)
            _require(len(record) == length, "invalid leaf record length")
            records.extend(record)
        current = following
        _require(len(records) + len(descriptors) <= fmt.MAX_FRAME_BYTES, "frame exceeds length limit")
    index = (struct.pack(f"<{len(masks)}I", *masks) + struct.pack(f"<{len(directory)}I", *directory)
             + struct.pack("<III", *levels) + descriptors + struct.pack(f"<{len(walk)}I", *walk) + bytes(counts))
    tables = b"".join(word for size in (8, 16, 32) for word in selectors[size]) + b"".join(endpoints)
    payload_start = 32 + len(index)
    total = payload_start + len(records) + len(tables)
    _require(total <= fmt.MAX_FRAME_BYTES, "frame exceeds length limit")
    _require((frame_id == reference_id) == bool(keyframe), "invalid frame/reference id relationship")
    _require(keyframe or tuple(default_color) == (0, 0, 0), "P frame default colour must be zero")
    header = fmt.HEADER.pack(fmt.MAGIC, fmt.VERSION, int(bool(keyframe)), 0, width, height, frame_id,
                             reference_id, payload_start, total, *default_color, 0)
    return header + index + records + tables
