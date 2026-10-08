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

"""Six-bit map pages, CRC validation and bounded reassembly (specification §11)."""

import struct
import zlib
from dataclasses import dataclass

import numpy as np

from .format import MAX_FRAME_BYTES, MAX_PENDING, MIN_FRAME_BYTES, PAGE_CAPACITY, PAGE_MAGIC, PAGE_SYMBOLS, SYMBOL_BITS
from .v3 import parse_frame

PAGE_HEADER = struct.Struct("<4sBBHIIHHIII")


def page_capacity(symbol_bits: int = 6) -> int:
    if symbol_bits != SYMBOL_BITS:
        raise ValueError("only 6-bit transport symbols are supported")
    return PAGE_CAPACITY


def to_symbols(data: bytes, symbol_bits: int = 6) -> bytes:
    """Return exact, unpadded map symbol bytes (map colours are these values plus four)."""
    page_capacity(symbol_bits)
    bits = np.unpackbits(np.frombuffer(data, np.uint8), bitorder="little")
    bits = np.pad(bits, (0, (-len(bits)) % 6)).reshape(-1, 6)
    return (bits @ (1 << np.arange(6))).astype(np.uint8).tobytes()


def from_symbols(symbols: bytes, symbol_bits: int, byte_count: int) -> bytes:
    page_capacity(symbol_bits)
    if byte_count < 0 or len(symbols) != (byte_count * 8 + 5) // 6:
        raise ValueError("invalid symbol extent")
    values = np.frombuffer(symbols, np.uint8)
    if np.any(values >= 64):
        raise ValueError("out-of-alphabet symbol")
    bits = ((values[:, None] >> np.arange(6)) & 1).astype(np.uint8).ravel()
    if np.any(bits[byte_count * 8:]):
        raise ValueError("nonzero symbol padding")
    return np.packbits(bits[:byte_count * 8], bitorder="little").tobytes()


def make_pages(frame_data: bytes, stream_id: int = 1, symbol_bits: int = 6) -> list[bytes]:
    capacity = page_capacity(symbol_bits)
    if not 0 <= stream_id <= 0xFFFFFFFF:
        raise ValueError("stream id must be u32")
    frame = parse_frame(frame_data)
    count = (frame.total + capacity - 1) // capacity
    pages = []
    for number in range(count):
        payload = frame_data[number * capacity:(number + 1) * capacity]
        header = PAGE_HEADER.pack(PAGE_MAGIC, 1, 6, frame.flags, stream_id, frame.frame_id,
                                  number, count, frame.reference_id, frame.total, 0)
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
    values = np.frombuffer(symbols[:43], np.uint8)
    # The 43rd symbol also holds the first two payload bits; it is not header padding.
    bits = ((values[:, None] >> np.arange(6)) & 1).astype(np.uint8).ravel()
    header = np.packbits(bits[:256], bitorder="little").tobytes()
    magic, version, width, flags, stream, frame, number, count, reference, total, crc = PAGE_HEADER.unpack(header)
    if magic != PAGE_MAGIC or version != 1 or width != 6 or flags > 1:
        raise ValueError("unsupported page header")
    if not MIN_FRAME_BYTES <= total <= MAX_FRAME_BYTES or count != (total + PAGE_CAPACITY - 1) // PAGE_CAPACITY or number >= count:
        raise ValueError("invalid page metadata")
    size = min(PAGE_CAPACITY, total - number * PAGE_CAPACITY)
    raw = from_symbols(symbols, 6, PAGE_HEADER.size + size)
    if zlib.crc32(raw[:28] + bytes(4) + raw[32:]) != crc:
        raise ValueError("page CRC mismatch")
    return Page(stream, frame, number, count, reference, total, flags, width, raw[32:])


def wire_bytes(pages: list[bytes], full_maps: bool = False, packet_overhead: int = 18) -> int:
    """Map colour bytes rounded to 128-colour rows, plus the per-page packet allowance."""
    return sum((PAGE_SYMBOLS if full_maps else (len(page) + 127) // 128 * 128) + packet_overhead for page in pages)


class Assembler:
    """Keep at most four pending frames; a conflict discards that pending frame."""

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
                    first.count, first.reference_id, first.frame_bytes, first.flags):
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
        if (frame.frame_id, frame.reference_id, frame.flags) != (page.frame_id, page.reference_id, page.flags):
            raise ValueError("frame/page identity mismatch")
        return data
