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

import unittest
import zlib
from collections.abc import Callable
from typing import Any

from rejection_cases import BYTE_CODE, HALF_WORD_CODE, WORD_CODE, changed
import mcv2_reference
from mcv2_reference import Assembler, MapCharge, PAGE_HEADER, from_symbols, make_pages, page_capacity, read_page, to_symbols, wire_bytes
from mcv2_reference import Node, pack_frame

LARGE_WIDTH: int = 3200
LARGE_LEAVES: int = 100
PALETTE_BYTES: int = 134

PAGE_MAGIC_OFFSET: int = 0
PAGE_VERSION_OFFSET: int = 4
SYMBOL_BITS_OFFSET: int = 5
FLAGS_OFFSET: int = 6
FRAME_ID_OFFSET: int = 12
NUMBER_OFFSET: int = 16
COUNT_OFFSET: int = 18
REFERENCE_ID_OFFSET: int = 20
FRAME_BYTES_OFFSET: int = 24


class TransportTest(unittest.TestCase):
    def test_should_split_six_bit_symbols_in_least_significant_bit_order_and_check_their_extent_and_padding(self) -> None:
        # 0xdbd2bf, split at bit positions 0, 6, 12 and 18.
        symbols: bytes = to_symbols(bytes.fromhex('bfd2db'))
        self.assertEqual(bytes([0x3F, 0x0A, 0x3D, 0x36]), symbols)

        joined: bytes = from_symbols(bytes([63, 3]), mcv2_reference.SYMBOL_BITS, 1)
        self.assertEqual(b'\xff', joined)

        for values in (b'\x3f\x07', b'\x3f', b'\x40\0', b'\x3f\x03\0'):
            with self.assertRaises(ValueError):
                from_symbols(values, mcv2_reference.SYMBOL_BITS, 1)

        calls: list[
            tuple[
                int,
                Callable[..., int]
                | Callable[..., list[bytes]]
                | Callable[..., mcv2_reference.Page]
                | type[Assembler]
                | Callable[..., bytes]
                | Callable[[bytes, int, int], bytes],
                tuple[int] | tuple[bytes, int, int] | tuple[bytes, int] | tuple[int, int],
            ]
        ] = [(width, function, arguments) for width in (0, 5, 7, 8) for function, arguments in symbol_width_calls(width)]
        for width, function, arguments in calls:
            with self.subTest(function=function.__name__, width=width), self.assertRaises(ValueError):
                function(*arguments)

    def test_should_write_the_page_header_and_crc_and_count_wire_bytes(self) -> None:
        frame: bytes = pack_frame(1, 1, 9, 9, {0: Node(mcv2_reference.SOLID, record=b'\xab\xcd\xef')})
        pages: list[bytes] = make_pages(frame, 7)
        capacity: int = page_capacity()
        self.assertEqual(12256, capacity)

        header_size: int = PAGE_HEADER.size
        self.assertEqual(32, header_size)

        page_lengths: list[int] = [len(page) for page in pages]
        self.assertEqual([107], page_lengths)

        raw: bytes = page_bytes(pages[0])
        expected_header: bytes = bytes.fromhex('4d43503101060100 07000000 09000000 00000100 09000000 30000000 00000000')
        header: bytes = raw[:mcv2_reference.PAGE_CRC_OFFSET]
        self.assertEqual(expected_header[:mcv2_reference.PAGE_CRC_OFFSET], header)

        crc: Any = mcv2_reference.PAGE_CRC.unpack_from(raw, mcv2_reference.PAGE_CRC_OFFSET)[0]
        self.assertEqual(zlib.crc32(expected_header + frame), crc)

        payload: bytes = raw[mcv2_reference.PAGE_HEADER_BYTES:]
        self.assertEqual(frame, payload)

        fields: tuple[Any, ...] = tuple(getattr(read_page(pages[0]), field) for field in
                       ('stream_id', 'frame_id', 'number', 'count', 'reference_id', 'frame_bytes', 'flags', 'symbol_bits'))
        self.assertEqual((7, 9, 0, 1, 9, 48, 1, 6), fields)

        page_wire_bytes: int = wire_bytes(pages)
        self.assertEqual(146, page_wire_bytes)

        full_map_wire_bytes: int = wire_bytes(pages, MapCharge.FULL_MAPS)
        self.assertEqual(16402, full_map_wire_bytes)

        overhead_wire_bytes: int = wire_bytes([bytes(129)], packet_overhead=5)
        self.assertEqual(261, overhead_wire_bytes)

    def test_should_round_trip_through_reordering_duplicates_and_crc_checks(self) -> None:
        frame: bytes = large_frame()
        pages: list[bytes] = make_pages(frame)
        page_count: int = len(pages)
        self.assertEqual(2, page_count)

        first_length: int = len(pages[0])
        self.assertEqual(16384, first_length)

        assembler: Assembler = Assembler()
        early: bytes | None = assembler.push(pages[1])
        self.assertIsNone(early)

        duplicate: bytes | None = assembler.push(pages[1])
        self.assertIsNone(duplicate)

        assembled: bytes | None = assembler.push(pages[0])
        self.assertEqual(frame, assembled)

        pending: dict[int, dict[int, mcv2_reference.Page]] = assembler.pending
        self.assertEqual({}, pending)

        damaged: bytearray = bytearray(pages[0])
        damaged[100] ^= 1
        with self.assertRaisesRegex(ValueError, 'CRC'):
            assembler.push(bytes(damaged))

        pending = assembler.pending
        self.assertEqual({}, pending)

    def test_should_reject_bad_metadata_alphabet_and_padding(self) -> None:
        raw: bytes = page_bytes(make_pages(large_frame())[0])
        mutations: list[tuple[int, int, str]] = [
            (PAGE_MAGIC_OFFSET, ord("X"), BYTE_CODE),
            (PAGE_VERSION_OFFSET, 2, BYTE_CODE),
            (SYMBOL_BITS_OFFSET, 7, BYTE_CODE),
            (FLAGS_OFFSET, 2, HALF_WORD_CODE),
            (NUMBER_OFFSET, 2, HALF_WORD_CODE),
            (COUNT_OFFSET, 3, HALF_WORD_CODE),
            (FRAME_BYTES_OFFSET, mcv2_reference.MINIMUM_FRAME_BYTES - 1, WORD_CODE),
            (FRAME_BYTES_OFFSET, mcv2_reference.MAXIMUM_FRAME_BYTES + 1, WORD_CODE),
        ]
        for offset, value, code in mutations:
            with self.subTest(offset=offset), self.assertRaises(ValueError):
                read_page(to_symbols(with_crc(changed(raw, offset, value, code))))

        for symbols in (b'', bytes(42), bytes(mcv2_reference.PAGE_SYMBOLS + 1), make_pages(large_frame())[0][:-1]):
            with self.assertRaises(ValueError):
                read_page(symbols)

        # 80 bytes are 106 symbols and 4 bits: the last symbol has two padding bits.
        symbols = bytearray(make_pages(pack_frame(1, 1, 0, 0, {0: Node(mcv2_reference.SOLID, record=bytes(3))}))[0])
        symbols[-1] |= 32
        with self.assertRaisesRegex(ValueError, 'padding'):
            read_page(bytes(symbols))

        symbols[0] |= mcv2_reference.ALPHABET_SIZE
        with self.assertRaisesRegex(ValueError, 'alphabet'):
            read_page(bytes(symbols))

    def test_should_keep_the_pending_limit_and_reject_wrong_streams_and_conflicts(self) -> None:
        assembler: Assembler = Assembler()
        for frame_id in range(5):
            assembled: bytes | None = assembler.push(make_pages(large_frame(frame_id))[0])
            self.assertIsNone(assembled)

        pending: list[int] = list(assembler.pending)
        self.assertEqual([1, 2, 3, 4], pending)

        with self.assertRaisesRegex(ValueError, 'wrong stream'):
            assembler.push(make_pages(large_frame(5), 2)[0])

        pending = list(assembler.pending)
        self.assertEqual([1, 2, 3, 4], pending)

        first, last = make_pages(large_frame(4))
        conflicting: bytes = with_crc(changed(page_bytes(first), 40, 1))
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            assembler.push(to_symbols(conflicting))

        self.assertNotIn(4, assembler.pending)

        assembler.push(first)
        disagreement: bytes = with_crc(changed(page_bytes(last), REFERENCE_ID_OFFSET, 17, WORD_CODE))
        with self.assertRaisesRegex(ValueError, 'inconsistent'):
            assembler.push(to_symbols(disagreement))

        self.assertNotIn(4, assembler.pending)

    def test_should_validate_complete_frames_and_their_identity(self) -> None:
        frame: bytes = pack_frame(1, 1, 0, 0, {})
        raw: bytes = page_bytes(make_pages(frame)[0])
        for offset, value in ((FRAME_ID_OFFSET, 1), (REFERENCE_ID_OFFSET, 1), (FLAGS_OFFSET, 0)):
            with self.subTest(offset=offset), self.assertRaisesRegex(ValueError, 'identity mismatch'):
                Assembler().push(to_symbols(with_crc(changed(raw, offset, value))))

        invalid: bytes = with_crc(changed(raw, mcv2_reference.PAGE_HEADER_BYTES + mcv2_reference.VERSION_OFFSET + 1, 1))
        with self.assertRaisesRegex(ValueError, 'not an MCV2 version 3'):
            Assembler().push(to_symbols(invalid))

        # A page may hold 20 bytes even though those bytes cannot hold a complete v3 index.
        header: bytes = PAGE_HEADER.pack(mcv2_reference.PAGE_MAGIC, mcv2_reference.PAGE_VERSION, mcv2_reference.SYMBOL_BITS,
                                  1, 1, 0, 0, 1, 0, 20, 0)
        symbols: bytes = to_symbols(with_crc(header + bytes(20)))
        frame_bytes: int = read_page(symbols).frame_bytes
        self.assertEqual(20, frame_bytes)

        with self.assertRaises(ValueError):
            Assembler().push(symbols)


def symbol_width_calls(
    width: int,
) -> tuple[
    tuple[Callable[..., int], tuple[int]],
    tuple[Callable[..., list[bytes]], tuple[bytes, int, int]],
    tuple[Callable[..., mcv2_reference.Page], tuple[bytes, int]],
    tuple[type[Assembler], tuple[int, int]],
    tuple[Callable[..., bytes], tuple[bytes, int]],
    tuple[Callable[[bytes, int, int], bytes], tuple[bytes, int, int]],
]:
    return ((page_capacity, (width,)), (make_pages, (large_frame(), 1, width)), (read_page, (b'', width)),
            (Assembler, (1, width)), (to_symbols, (b'a', width)), (from_symbols, (b'', width, 0)))


def large_frame(frame_id: int = 0) -> bytes:
    roots: dict[int, Node] = {index: Node(mcv2_reference.PALETTE, record=bytes(PALETTE_BYTES)) for index in range(LARGE_LEAVES)}
    return pack_frame(LARGE_WIDTH, mcv2_reference.SUPERBLOCK_SIZE, frame_id, frame_id, roots)


def page_bytes(symbols: bytes) -> bytes:
    return from_symbols(symbols, mcv2_reference.SYMBOL_BITS, len(symbols) * mcv2_reference.SYMBOL_BITS // mcv2_reference.BITS_PER_BYTE)


def with_crc(raw: bytes) -> bytes:
    header: bytes = raw[:mcv2_reference.PAGE_CRC_OFFSET]
    payload: bytes = raw[mcv2_reference.PAGE_HEADER_BYTES:]
    crc: int = zlib.crc32(header + bytes(mcv2_reference.PAGE_CRC.size) + payload)
    return header + mcv2_reference.PAGE_CRC.pack(crc) + payload


if __name__ == '__main__':
    unittest.main()
