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

"""Independent MCV2 v3 format, serializer, decoder and six-bit transport."""

import struct
import zlib
from collections.abc import Mapping
from dataclasses import dataclass

import numpy

MAGIC = b"MCV2"
VERSION = 3
HEADER = struct.Struct("<4sIHHII")
HEADER_BYTES = 20
MAGIC_OFFSET = 0
VERSION_OFFSET = 4
WIDTH_OFFSET = 8
HEIGHT_OFFSET = 10
FRAME_ID_OFFSET = 12
REFERENCE_ID_OFFSET = 16
KEYFRAME = 1
MIN_DIMENSION = 1
MAX_DIMENSION = 4096
MIN_FRAME_BYTES = 20
MAX_FRAME_BYTES = 131071
ID_MASK = 0xFFFFFFFF
ID_HALF_RANGE = 0x80000000
SUPERBLOCK_SIZE = 32
LEAF_SIZES = (32, 16, 8)
MASK_BITS = 32
DIRECTORY_GROUPS = 8
WALK_STRIDE = 8
WALK_CURSOR_BITS = 17
MAX_QUANTIZER = 2
SKIP, MOTION, SOLID, PALETTE, PATTERN, COMPACT, SPLIT = range(7)
MODE_MASK = 31
QUANTIZER_SHIFT = 5
COMPACT_BYTES = 10
PAGE_MAGIC = b"MCP1"
PAGE_VERSION = 1
SYMBOL_BITS = 6
PAGE_SYMBOLS = 128 * 128
PAGE_HEADER_BYTES = 32
PAGE_CAPACITY = 12256
MAX_PENDING = 4
MAX_PAGE_SLOTS = 8
SCREEN_CAPACITY = MAX_PAGE_SLOTS * PAGE_CAPACITY


@dataclass(frozen=True)
class Node:
    """A leaf with its record, or a SPLIT with four children in quadrant order."""

    mode: int
    quantizer: int = 0
    record: bytes = b""
    children: tuple["Node", ...] = ()


@dataclass(frozen=True)
class Leaf:
    pixel_x: int
    pixel_y: int
    size: int
    mode: int
    quantizer: int
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
    if mode in (SKIP, SPLIT):
        return 0
    if mode == MOTION:
        return 2
    if mode == SOLID:
        return 3
    if mode == PALETTE:
        return 6 + size * size // 8
    if mode == PATTERN:
        return 7 + size // 8
    return COMPACT_BYTES


def parse_frame(data: bytes) -> Frame:
    """Accept exactly spec section 9 syntax; check each field extent before reading it, or raise ValueError."""
    if data[:4] == b"MCV1":
        raise ValueError("MCV1 version 1 is no longer supported; re-encode")
    if data[:5] == b"MCV2\x02":
        raise ValueError("MCV2 version 2 is no longer supported; re-encode")
    total = len(data)
    _require(MIN_FRAME_BYTES <= total <= MAX_FRAME_BYTES, "invalid frame length")
    magic, version, width, height, frame_id, reference_id = HEADER.unpack_from(data)
    _require(magic == MAGIC and version == VERSION, "not an MCV2 version 3 frame")
    _require(1 <= width <= MAX_DIMENSION and 1 <= height <= MAX_DIMENSION, "invalid picture dimensions")
    keyframe = frame_id == reference_id
    columns = (width + 31) // 32
    root_count = columns * ((height + 31) // 32)
    groups = (root_count + 31) // 32
    checkpoints = (groups + 7) // 8
    counts_offset = HEADER_BYTES + 4 * groups + 4 * checkpoints
    _require(counts_offset + 12 <= total, "truncated index")
    masks = struct.unpack_from(f"<{groups}I", data, HEADER_BYTES)
    directory = struct.unpack_from(f"<{checkpoints}I", data, HEADER_BYTES + 4 * groups)
    _require(masks[-1] >> (((root_count - 1) % 32) + 1) == 0, "presence mask has out-of-picture superblocks")
    present = 0
    for index, mask in enumerate(masks):
        if index % 8 == 0:
            _require(directory[index // 8] == present, "directory prefix mismatch")
        present += mask.bit_count()
    level_zero_count, level_one_count, level_two_count = levels = struct.unpack_from(
        "<III", data, counts_offset
    )
    _require(level_zero_count == present, "level 0 count differs from presence masks")
    count = level_zero_count + level_one_count + level_two_count
    walk_count = (count + 7) // 8
    descriptor_offset = counts_offset + 12
    payload = descriptor_offset + count + 4 * walk_count
    _require(payload <= total, "truncated descriptors or walk")
    descriptors = data[descriptor_offset : descriptor_offset + count]
    walk = struct.unpack_from(f"<{walk_count}I", data, descriptor_offset + count)
    offsets, records = ([], [])
    cursor = splits = splits0 = splits1 = 0
    for index, descriptor in enumerate(descriptors):
        if index % 8 == 0:
            _require(walk[index // 8] == (cursor | (splits << 17)), "walk checkpoint mismatch")
        size = 32 if index < level_zero_count else 16 if index < level_zero_count + level_one_count else 8
        mode, quantizer = (descriptor & 31, descriptor >> 5)
        _require(mode <= SPLIT, "invalid descriptor mode")
        _require(mode == COMPACT or quantizer == 0, "non-COMPACT quantizer must be zero")
        _require(quantizer <= MAX_QUANTIZER, "COMPACT quantizer above 2")
        _require(not keyframe or mode not in (MOTION, COMPACT), "temporal mode in keyframe")
        _require(mode != SPLIT or size != 8, "SPLIT in level 2")
        offset = payload + cursor
        length = _record_length(mode, size)
        _require(offset + length <= total, "record exceeds the frame")
        record = data[offset : offset + length]
        if mode == PATTERN:
            _require(record[6] <= 1, "invalid PATTERN orientation")
        offsets.append(offset)
        records.append(record)
        cursor += length
        if mode == SPLIT:
            splits += 1
            splits0 += index < level_zero_count
            splits1 += level_zero_count <= index < level_zero_count + level_one_count
    _require(level_one_count == 4 * splits0, "level 1 count differs from SPLIT children")
    _require(level_two_count == 4 * splits1, "level 2 count differs from SPLIT children")
    _require(payload + cursor == total, "records do not end at the end of the frame")
    root_indexes = [index for index in range(root_count) if (masks[index // 32] >> (index % 32)) & 1]
    coordinates = [(32 * (index % columns), 32 * (index // columns), 32) for index in root_indexes]
    leaves, children = ([], {})
    for index, descriptor in enumerate(descriptors):
        pixel_x, pixel_y, size = coordinates[index]
        mode, quantizer = (descriptor & 31, descriptor >> 5)
        if mode == SPLIT:
            half = size // 2
            children[index] = len(coordinates)
            coordinates.extend(
                (pixel_x + delta_x * half, pixel_y + delta_y * half, half)
                for delta_x, delta_y in ((0, 0), (1, 0), (0, 1), (1, 1))
            )
        else:
            leaves.append(
                Leaf(pixel_x, pixel_y, size, mode, quantizer, offsets[index], records[index], index)
            )
    for index in range(root_count):
        if not ((masks[index // 32] >> (index % 32)) & 1):
            leaves.append(Leaf(32 * (index % columns), 32 * (index // columns), 32, SKIP, 0, None, b"", None))
    nodes = {}
    for index in reversed(range(count)):
        descriptor = descriptors[index]
        mode, quantizer = (descriptor & 31, descriptor >> 5)
        if mode == SPLIT:
            nodes[index] = Node(
                mode, children=tuple(nodes[children[index] + entry_index] for entry_index in range(4))
            )
        else:
            nodes[index] = Node(mode, quantizer, records[index])
    roots = {root: nodes[index] for index, root in enumerate(root_indexes)}
    return Frame(
        width,
        height,
        frame_id,
        reference_id,
        keyframe,
        payload,
        total,
        tuple(leaves),
        masks,
        directory,
        levels,
        descriptors,
        walk,
        roots,
    )


def pack_frame(width: int, height: int, frame_id: int, reference_id: int, roots: Mapping[int, Node]) -> bytes:
    """Serialize a tree canonically. Absent roots stay absent; a frame is a keyframe when its ids are equal."""
    _require(1 <= width <= MAX_DIMENSION and 1 <= height <= MAX_DIMENSION, "invalid picture dimensions")
    _require(0 <= frame_id <= ID_MASK and 0 <= reference_id <= ID_MASK, "ids must be u32")
    keyframe = frame_id == reference_id
    root_count = (width + 31) // 32 * ((height + 31) // 32)
    _require(
        all(isinstance(index, int) and 0 <= index < root_count for index in roots), "root index out of range"
    )
    masks = [0] * ((root_count + 31) // 32)
    for index in roots:
        masks[index // 32] |= 1 << (index % 32)
    directory, present = ([], 0)
    for index, mask in enumerate(masks):
        if index % 8 == 0:
            directory.append(present)
        present += mask.bit_count()
    levels, descriptors, records, walk = ([], bytearray(), bytearray(), [])
    current = [roots[index] for index in sorted(roots)]
    splits = 0
    for size in LEAF_SIZES:
        levels.append(len(current))
        following = []
        for node in current:
            _require(
                0 <= node.mode <= SPLIT and 0 <= node.quantizer <= MAX_QUANTIZER, "invalid node descriptor"
            )
            _require(node.mode == COMPACT or node.quantizer == 0, "non-COMPACT quantizer must be zero")
            _require(not keyframe or node.mode not in (MOTION, COMPACT), "temporal mode in keyframe")
            if len(descriptors) % 8 == 0:
                walk.append(len(records) | (splits << 17))
            descriptors.append(node.mode | (node.quantizer << 5))
            if node.mode == SPLIT:
                _require(size > 8 and len(node.children) == 4 and (not node.record), "invalid SPLIT node")
                following.extend(node.children)
                splits += 1
                continue
            _require(not node.children, "leaf has children")
            record = node.record
            if node.mode == PATTERN:
                _require(len(record) == 7 + size // 8 and record[6] <= 1, "invalid PATTERN record")
            length = _record_length(node.mode, size)
            _require(len(record) == length, "invalid leaf record length")
            records.extend(record)
        current = following
        _require(len(records) + len(descriptors) <= MAX_FRAME_BYTES, "frame exceeds length limit")
    index = (
        struct.pack(f"<{len(masks)}I", *masks)
        + struct.pack(f"<{len(directory)}I", *directory)
        + struct.pack("<III", *levels)
        + descriptors
        + struct.pack(f"<{len(walk)}I", *walk)
    )
    total = HEADER_BYTES + len(index) + len(records)
    _require(total <= MAX_FRAME_BYTES, "frame exceeds length limit")
    return HEADER.pack(MAGIC, VERSION, width, height, frame_id, reference_id) + index + records


def _signed(value: int, bits: int = 8) -> int:
    return value - (1 << bits) if value & (1 << (bits - 1)) else value


def _prediction(
    reference: numpy.ndarray, pixel_x: int, pixel_y: int, size: int, delta_x: int, delta_y: int
) -> numpy.ndarray:
    height, width = reference.shape[:2]
    columns = numpy.clip(numpy.arange(size) + pixel_x + delta_x, 0, width - 1)
    rows = numpy.clip(numpy.arange(size) + pixel_y + delta_y, 0, height - 1)
    return reference[rows[:, None], columns]


def _grid(body: bytes, size: int) -> numpy.ndarray:
    packed = numpy.frombuffer(body[:8], numpy.uint8)
    nibbles = numpy.stack((packed & 15, packed >> 4), axis=1).astype(numpy.int32).ravel()
    nodes = numpy.where(nibbles >= 8, nibbles - 16, nibbles).reshape(4, 4)
    position = numpy.clip((numpy.arange(size) + 0.5) * 4 / size - 0.5, 0, 3)
    lower = position.astype(numpy.int32)
    upper = numpy.minimum(lower + 1, 3)
    fraction = position - lower
    horizontal = nodes[:, lower] * (1 - fraction) + nodes[:, upper] * fraction
    return horizontal[lower] * (1 - fraction[:, None]) + horizontal[upper] * fraction[:, None]


def _decode(frame: Frame, reference: numpy.ndarray | None, reference_id: int | None) -> numpy.ndarray:
    if not frame.keyframe:
        if reference is None or reference_id != frame.reference_id:
            raise ValueError("missing or mismatched reference id")
        if reference.shape != (frame.height, frame.width, 3) or reference.dtype != numpy.uint8:
            raise ValueError("reference must be a matching height x width x 3 uint8 picture")
    picture = numpy.empty((frame.height, frame.width, 3), numpy.uint8)
    for leaf in frame.leaves:
        pixel_x, pixel_y, size, mode, record = (leaf.pixel_x, leaf.pixel_y, leaf.size, leaf.mode, leaf.record)
        if mode == SKIP:
            block = (
                numpy.zeros((size, size, 3), numpy.uint8)
                if frame.keyframe
                else _prediction(reference, pixel_x, pixel_y, size, 0, 0)
            )
        elif mode == SOLID:
            block = numpy.broadcast_to(numpy.frombuffer(record, numpy.uint8), (size, size, 3))
        elif mode in (PALETTE, PATTERN):
            if mode == PATTERN:
                axis = numpy.unpackbits(numpy.frombuffer(record[7:], numpy.uint8), bitorder="little")
                selectors = numpy.broadcast_to(
                    axis[None, :] if record[6] == 0 else axis[:, None], (size, size)
                )
            else:
                selectors = numpy.unpackbits(
                    numpy.frombuffer(record[6:], numpy.uint8), bitorder="little"
                ).reshape(size, size)
            colors = numpy.frombuffer(record[:6], numpy.uint8).reshape(2, 3)
            block = colors[selectors]
        elif mode == MOTION:
            block = _prediction(reference, pixel_x, pixel_y, size, _signed(record[0]), _signed(record[1]))
        else:
            prediction = _prediction(
                reference, pixel_x, pixel_y, size, _signed(record[0]), _signed(record[1])
            ).astype(numpy.float64)
            residual = _grid(record[2:], size)[..., None]
            block = numpy.clip(
                numpy.floor(prediction + (1 << leaf.quantizer) * residual + 0.5), 0, 255
            ).astype(numpy.uint8)
        shown_width, shown_height = (min(size, frame.width - pixel_x), min(size, frame.height - pixel_y))
        if shown_width > 0 and shown_height > 0:
            picture[pixel_y : pixel_y + shown_height, pixel_x : pixel_x + shown_width] = block[
                :shown_height, :shown_width
            ]
    return picture


def decode(
    data: bytes, reference: numpy.ndarray | None = None, reference_id: int | None = None
) -> numpy.ndarray:
    """Validate and decode into a new height x width x 3 uint8 RGB picture.

    A P frame requires its matching reference shape, uint8 dtype and id. Invalid syntax or reference raises ValueError.
    """
    return _decode(parse_frame(data), reference, reference_id)


class Decoder:
    """Keep the last committed picture on every error; accept only newer u32 ids."""

    def __init__(self):
        self.reference: numpy.ndarray | None = None
        self.frame_id: int | None = None

    def accept(self, data: bytes) -> numpy.ndarray:
        frame = parse_frame(data)
        if self.frame_id is not None and (
            not 0 < ((frame.frame_id - self.frame_id) & ID_MASK) < ID_HALF_RANGE
        ):
            raise ValueError("frame id is not newer under the half-range rule")
        picture = _decode(frame, self.reference, self.frame_id)
        # The caller may mutate the returned picture without changing the next frame's committed reference.
        self.reference = picture.copy()
        self.frame_id = frame.frame_id
        return picture


PAGE_HEADER = struct.Struct("<4sBBHIIHHIII")


def page_capacity(symbol_bits: int = 6) -> int:
    if symbol_bits != SYMBOL_BITS:
        raise ValueError("only 6-bit transport symbols are supported")
    return PAGE_CAPACITY


def to_symbols(data: bytes, symbol_bits: int = 6) -> bytes:
    """Return exact, unpadded map symbol bytes (map colours are these values plus four)."""
    page_capacity(symbol_bits)
    bits = numpy.unpackbits(numpy.frombuffer(data, numpy.uint8), bitorder="little")
    bits = numpy.pad(bits, (0, -len(bits) % 6)).reshape(-1, 6)
    return (bits @ (1 << numpy.arange(6))).astype(numpy.uint8).tobytes()


def from_symbols(symbols: bytes, symbol_bits: int, byte_count: int) -> bytes:
    page_capacity(symbol_bits)
    if byte_count < 0 or len(symbols) != (byte_count * 8 + 5) // 6:
        raise ValueError("invalid symbol extent")
    values = numpy.frombuffer(symbols, numpy.uint8)
    if numpy.any(values >= 64):
        raise ValueError("out-of-alphabet symbol")
    bits = (values[:, None] >> numpy.arange(6) & 1).astype(numpy.uint8).ravel()
    if numpy.any(bits[byte_count * 8 :]):
        raise ValueError("nonzero symbol padding")
    return numpy.packbits(bits[: byte_count * 8], bitorder="little").tobytes()


def make_pages(frame_data: bytes, stream_id: int = 1, symbol_bits: int = 6) -> list[bytes]:
    capacity = page_capacity(symbol_bits)
    if not 0 <= stream_id <= 0xFFFFFFFF:
        raise ValueError("stream id must be u32")
    frame = parse_frame(frame_data)
    count = (frame.total + capacity - 1) // capacity
    pages = []
    for number in range(count):
        payload = frame_data[number * capacity : (number + 1) * capacity]
        header = PAGE_HEADER.pack(
            PAGE_MAGIC,
            1,
            6,
            frame.flags,
            stream_id,
            frame.frame_id,
            number,
            count,
            frame.reference_id,
            frame.total,
            0,
        )
        crc = zlib.crc32(header + payload)
        pages.append(to_symbols(header[:28] + struct.pack("<I", crc) + payload))
    return pages


@dataclass(frozen=True)
class Page:
    stream_id: int
    frame_id: int
    number: int
    count: int
    reference_id: int
    frame_bytes: int
    flags: int
    symbol_bits: int
    payload: bytes


def read_page(symbols: bytes, symbol_bits: int = 6) -> Page:
    page_capacity(symbol_bits)
    if not 43 <= len(symbols) <= PAGE_SYMBOLS:
        raise ValueError("invalid map page extent")
    values = numpy.frombuffer(symbols[:43], numpy.uint8)
    bits = (values[:, None] >> numpy.arange(6) & 1).astype(numpy.uint8).ravel()
    header = numpy.packbits(bits[:256], bitorder="little").tobytes()
    magic, version, width, flags, stream, frame, number, count, reference, total, crc = PAGE_HEADER.unpack(
        header
    )
    if magic != PAGE_MAGIC or version != 1 or width != 6 or (flags > 1):
        raise ValueError("unsupported page header")
    if (
        not MIN_FRAME_BYTES <= total <= MAX_FRAME_BYTES
        or count != (total + PAGE_CAPACITY - 1) // PAGE_CAPACITY
        or number >= count
    ):
        raise ValueError("invalid page metadata")
    size = min(PAGE_CAPACITY, total - number * PAGE_CAPACITY)
    raw = from_symbols(symbols, 6, PAGE_HEADER.size + size)
    if zlib.crc32(raw[:28] + bytes(4) + raw[32:]) != crc:
        raise ValueError("page CRC mismatch")
    return Page(stream, frame, number, count, reference, total, flags, width, raw[32:])


def wire_bytes(pages: list[bytes], full_maps: bool = False, packet_overhead: int = 18) -> int:
    """Charge map colour bytes rounded to 128-colour rows (or full maps), plus each page's packet allowance."""
    return sum(
        (PAGE_SYMBOLS if full_maps else (len(page) + 127) // 128 * 128) + packet_overhead for page in pages
    )


class Assembler:
    """Keep at most four pending frames; identical duplicates are safe, a conflict discards that pending frame."""

    def __init__(self, stream_id: int = 1, symbol_bits: int = 6):
        page_capacity(symbol_bits)
        self.stream_id = stream_id
        self.symbol_bits = symbol_bits
        self.pending: dict[int, dict[int, Page]] = {}

    def push(self, symbols: bytes) -> bytes | None:
        page = read_page(symbols, self.symbol_bits)
        if page.stream_id != self.stream_id:
            raise ValueError("wrong stream")
        if page.frame_id not in self.pending and len(self.pending) >= MAX_PENDING:
            del self.pending[next(iter(self.pending))]
        parts = self.pending.setdefault(page.frame_id, {})
        if parts:
            first = next(iter(parts.values()))
            if (page.count, page.reference_id, page.frame_bytes, page.flags) != (
                first.count,
                first.reference_id,
                first.frame_bytes,
                first.flags,
            ):
                del self.pending[page.frame_id]
                raise ValueError("inconsistent pages")
        if page.number in parts and parts[page.number] != page:
            del self.pending[page.frame_id]
            raise ValueError("conflicting page duplicate")
        parts[page.number] = page
        if len(parts) != page.count:
            return None
        data = b"".join(parts[number].payload for number in range(page.count))
        del self.pending[page.frame_id]
        frame = parse_frame(data)
        if (frame.frame_id, frame.reference_id, frame.flags) != (
            page.frame_id,
            page.reference_id,
            page.flags,
        ):
            raise ValueError("frame/page identity mismatch")
        return data
