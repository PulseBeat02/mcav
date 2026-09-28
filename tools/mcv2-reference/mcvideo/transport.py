"""Map-sized symbol pages and a bounded, transactional reference receiver.

One symbol is one transmitted map palette-index byte, even at 6 or 7 useful bits.
Page CRC is transport validation, not authentication. Minecraft runs on TCP, but
map reuse/visibility and skipped renders still require explicit frame assembly.
"""

import math
import struct
import zlib
from dataclasses import dataclass

import numpy as np

from .format import MAX_FRAME_BYTES, parse_frame

PAGE_SYMBOLS = 128 * 128
PAGE_HEADER = struct.Struct("<4sBBHIIHHIII")
PAGE_MAGIC = b"MCP1"
MAX_PENDING = 4


def to_symbols(data: bytes, symbol_bits: int) -> bytes:
    """Pack an LSB-first logical byte stream into 6/7/8-bit symbols."""
    if symbol_bits not in (6, 7, 8):
        raise ValueError("unsupported symbol width")
    bits = np.unpackbits(np.frombuffer(data, np.uint8), bitorder="little")
    bits = np.pad(bits, (0, (-len(bits)) % symbol_bits)).reshape(-1, symbol_bits)
    return (bits @ (1 << np.arange(symbol_bits))).astype(np.uint8).tobytes()


def from_symbols(symbols: bytes, symbol_bits: int, byte_count: int) -> bytes:
    """Decode symbols, rejecting overflow, truncation and nonzero padding."""
    if (
        symbol_bits not in (6, 7, 8)
        or byte_count < 0
        or len(symbols) != math.ceil(byte_count * 8 / symbol_bits)
    ):
        raise ValueError("invalid symbol extent")
    values = np.frombuffer(symbols, np.uint8)
    if np.any(values >= (1 << symbol_bits)):
        raise ValueError("out-of-alphabet symbol")
    bits = ((values[:, None] >> np.arange(symbol_bits)) & 1).astype(np.uint8).ravel()
    if np.any(bits[byte_count * 8 :]):
        raise ValueError("nonzero symbol padding")
    return np.packbits(bits[: byte_count * 8], bitorder="little").tobytes()


def page_capacity(symbol_bits: int) -> int:
    """Payload capacity after the 32-byte page header, in logical bytes."""
    if symbol_bits not in (6, 7, 8):
        raise ValueError("unsupported symbol width")
    return PAGE_SYMBOLS * symbol_bits // 8 - PAGE_HEADER.size


def make_pages(
    frame_data: bytes, stream_id: int = 1, symbol_bits: int = 6
) -> list[bytes]:
    """Frame all data with stream/frame/reference IDs and per-page CRC32."""
    frame = parse_frame(frame_data)
    capacity = page_capacity(symbol_bits)
    count = math.ceil(len(frame_data) / capacity)
    pages = []
    for number in range(count):
        payload = frame_data[number * capacity : (number + 1) * capacity]
        header = PAGE_HEADER.pack(
            PAGE_MAGIC,
            1,
            symbol_bits,
            frame.flags & 1,
            stream_id,
            frame.frame_id,
            number,
            count,
            frame.reference_id,
            len(frame_data),
            0,
        )
        crc = zlib.crc32(header + payload)
        pages.append(
            to_symbols(header[:-4] + struct.pack("<I", crc) + payload, symbol_bits)
        )
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


def read_page(symbols: bytes, symbol_bits: int) -> Page:
    """Validate a page before storing it; caller supplies the negotiated alphabet."""
    if len(symbols) > PAGE_SYMBOLS:
        raise ValueError("oversize map page")
    # The last byte can be partial, so determine exact length from the header.
    header_symbols = (
        math.ceil(PAGE_HEADER.size * 8 / symbol_bits) if symbol_bits in (6, 7, 8) else 0
    )
    if not header_symbols or len(symbols) < header_symbols:
        raise ValueError("truncated page header")
    # Header is not independently padded at a symbol boundary (7-bit case).
    values = np.frombuffer(symbols[:header_symbols], np.uint8)
    bits = ((values[:, None] >> np.arange(symbol_bits)) & 1).astype(np.uint8).ravel()
    header = np.packbits(bits[: PAGE_HEADER.size * 8], bitorder="little").tobytes()
    (
        magic,
        version,
        width,
        flags,
        stream,
        frame,
        number,
        count,
        reference,
        total,
        crc,
    ) = PAGE_HEADER.unpack(header)
    capacity = page_capacity(symbol_bits)
    if magic != PAGE_MAGIC or version != 1 or width != symbol_bits or flags > 1:
        raise ValueError("unsupported page header")
    if (
        not 48 <= total <= MAX_FRAME_BYTES
        or count != math.ceil(total / capacity)
        or number >= count
    ):
        raise ValueError("invalid page metadata")
    size = min(capacity, total - number * capacity)
    raw = from_symbols(symbols, symbol_bits, PAGE_HEADER.size + size)
    if zlib.crc32(raw[:28] + b"\0\0\0\0" + raw[32:]) != crc:
        raise ValueError("page CRC mismatch")
    return Page(stream, frame, number, count, reference, total, flags, width, raw[32:])


def wire_bytes(
    pages: list[bytes], full_maps: bool = False, packet_overhead: int = 18
) -> int:
    """Conservative uncompressed Map Data model, including row rounding.

    A partial update uses complete 128-symbol rows so it is one legal rectangle.
    18 bytes/page conservatively budgets three-byte packet length, two-byte
    packet ID, three-byte map ID, scale, locked/decorations flags, four patch
    coordinates/extents, and a three-byte color-array length. Map IDs are bounded
    to 2^21-1; no decorations, no outer compression. TCP/IP/TLS
    overhead is deliberately separate and not called measured network traffic.
    """
    return sum(
        (PAGE_SYMBOLS if full_maps else math.ceil(len(page) / 128) * 128)
        + packet_overhead
        for page in pages
    )


class Assembler:
    """Bounded reordering/loss simulation; returns only complete validated frames.

    No reference is advanced here. Decoder accepts exact reference IDs; missing
    predecessors freeze display until a keyframe. Old pending frames are evicted.
    """

    def __init__(self, stream_id: int = 1, symbol_bits: int = 6):
        self.stream_id = stream_id
        self.symbol_bits = symbol_bits
        self.pending: dict[int, dict[int, Page]] = {}

    def push(self, symbols: bytes) -> bytes | None:
        """Accept one page; conflicting duplicates invalidate its entire frame."""
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
        if (frame.frame_id, frame.reference_id, frame.flags & 1) != (
            page.frame_id,
            page.reference_id,
            page.flags,
        ):
            raise ValueError("frame/page identity mismatch")
        return data
