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

"""Validated v3 frames and the canonical block-tree serializer (specification §§3-10)."""

import struct
from collections.abc import Mapping
from dataclasses import dataclass

from . import format as fmt


@dataclass(frozen=True)
class Node:
    """A leaf with its record, or a SPLIT with four children in quadrant order."""

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
    payload_start: int
    total: int
    leaves: tuple[Leaf, ...]
    masks: tuple[int, ...]
    directory: tuple[int, ...]
    level_counts: tuple[int, int, int]
    descriptors: bytes
    walk: tuple[int, ...]
    roots: dict[int, Node]

    @property
    def flags(self) -> int:
        return int(self.keyframe)


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def _record_length(mode: int, size: int) -> int:
    if mode in (fmt.SKIP, fmt.SPLIT):
        return 0
    if mode == fmt.MOTION:
        return 2
    if mode == fmt.SOLID:
        return 3
    if mode == fmt.PALETTE:
        return 6 + size * size // 8
    if mode == fmt.PATTERN:
        return 7 + size // 8
    return fmt.COMPACT_BYTES


def parse_frame(data: bytes) -> Frame:
    """Accept exactly the syntax of §9; never read a field before checking its extent."""
    if data[:4] == b"MCV1":
        raise ValueError("MCV1 version 1 is no longer supported; re-encode")
    if data[:5] == b"MCV2\x02":
        raise ValueError("MCV2 version 2 is no longer supported; re-encode")
    total = len(data)
    _require(fmt.MIN_FRAME_BYTES <= total <= fmt.MAX_FRAME_BYTES, "invalid frame length")
    magic, version, width, height, frame_id, reference_id = fmt.HEADER.unpack_from(data)
    _require(magic == fmt.MAGIC and version == fmt.VERSION, "not an MCV2 version 3 frame")
    _require(1 <= width <= fmt.MAX_DIMENSION and 1 <= height <= fmt.MAX_DIMENSION, "invalid picture dimensions")
    keyframe = frame_id == reference_id

    columns = (width + 31) // 32
    root_count = columns * ((height + 31) // 32)
    groups = (root_count + 31) // 32
    checkpoints = (groups + 7) // 8
    counts_offset = fmt.HEADER_BYTES + 4 * groups + 4 * checkpoints
    _require(counts_offset + 12 <= total, "truncated index")
    masks = struct.unpack_from(f"<{groups}I", data, fmt.HEADER_BYTES)
    directory = struct.unpack_from(f"<{checkpoints}I", data, fmt.HEADER_BYTES + 4 * groups)
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
    payload = descriptor_offset + count + 4 * walk_count
    _require(payload <= total, "truncated descriptors or walk")
    descriptors = data[descriptor_offset:descriptor_offset + count]
    walk = struct.unpack_from(f"<{walk_count}I", data, descriptor_offset + count)

    offsets, records = [], []
    cursor = splits = splits0 = splits1 = 0
    for index, descriptor in enumerate(descriptors):
        if index % 8 == 0:
            _require(walk[index // 8] == cursor | splits << 17, "walk checkpoint mismatch")
        size = 32 if index < n0 else 16 if index < n0 + n1 else 8
        mode, q = descriptor & 31, descriptor >> 5
        _require(mode <= fmt.SPLIT, "invalid descriptor mode")
        _require(mode == fmt.COMPACT or q == 0, "non-COMPACT quantizer must be zero")
        _require(q <= fmt.MAX_QUANTIZER, "COMPACT quantizer above 2")
        _require(not keyframe or mode not in (fmt.MOTION, fmt.COMPACT), "temporal mode in keyframe")
        _require(mode != fmt.SPLIT or size != 8, "SPLIT in level 2")
        offset = payload + cursor
        length = _record_length(mode, size)
        _require(offset + length <= total, "record exceeds the frame")
        record = data[offset:offset + length]
        if mode == fmt.PATTERN:
            _require(record[6] <= 1, "invalid PATTERN orientation")
        offsets.append(offset)
        records.append(record)
        cursor += length
        if mode == fmt.SPLIT:
            splits += 1
            splits0 += index < n0
            splits1 += n0 <= index < n0 + n1
    _require(n1 == 4 * splits0, "level 1 count differs from SPLIT children")
    _require(n2 == 4 * splits1, "level 2 count differs from SPLIT children")
    _require(payload + cursor == total, "records do not end at the end of the frame")

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
            nodes[index] = Node(mode, q, records[index])
    roots = {root: nodes[index] for index, root in enumerate(root_indexes)}
    return Frame(width, height, frame_id, reference_id, keyframe, payload, total, tuple(leaves),
                 masks, directory, levels, descriptors, walk, roots)


def pack_frame(width: int, height: int, frame_id: int, reference_id: int, roots: Mapping[int, Node]) -> bytes:
    """Serialize a tree canonically. Absent roots stay absent; a frame is a keyframe when its ids are equal."""
    _require(1 <= width <= fmt.MAX_DIMENSION and 1 <= height <= fmt.MAX_DIMENSION, "invalid picture dimensions")
    _require(0 <= frame_id <= fmt.ID_MASK and 0 <= reference_id <= fmt.ID_MASK, "ids must be u32")
    keyframe = frame_id == reference_id
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
            _require(0 <= node.mode <= fmt.SPLIT and 0 <= node.q <= fmt.MAX_QUANTIZER, "invalid node descriptor")
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
                _require(len(record) == 7 + size // 8 and record[6] <= 1, "invalid PATTERN record")
            length = _record_length(node.mode, size)
            _require(len(record) == length, "invalid leaf record length")
            records.extend(record)
        current = following
        _require(len(records) + len(descriptors) <= fmt.MAX_FRAME_BYTES, "frame exceeds length limit")
    index = (struct.pack(f"<{len(masks)}I", *masks) + struct.pack(f"<{len(directory)}I", *directory)
             + struct.pack("<III", *levels) + descriptors + struct.pack(f"<{len(walk)}I", *walk))
    total = fmt.HEADER_BYTES + len(index) + len(records)
    _require(total <= fmt.MAX_FRAME_BYTES, "frame exceeds length limit")
    return fmt.HEADER.pack(fmt.MAGIC, fmt.VERSION, width, height, frame_id, reference_id) + index + records
