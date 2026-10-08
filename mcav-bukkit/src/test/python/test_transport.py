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

"""Page wire examples, damaged pages and transactional bounded assembly."""

import struct
import unittest
import zlib

from rejection_cases import changed
import mcv2_reference as reference_format
from mcv2_reference import Assembler, PAGE_HEADER, from_symbols, make_pages, page_capacity, read_page, to_symbols, wire_bytes
from mcv2_reference import Node, pack_frame


def large_frame(frame_id=0):
    return pack_frame(3200, 32, frame_id, frame_id, {index: Node(reference_format.PALETTE, record=bytes(134)) for index in range(100)})


def page_bytes(symbols):
    return from_symbols(symbols, 6, len(symbols) * 6 // 8)


def with_crc(raw):
    return raw[:28] + struct.pack('<I', zlib.crc32(raw[:28] + bytes(4) + raw[32:])) + raw[32:]


class TransportTest(unittest.TestCase):
    def test_six_bit_lsb_order_extent_and_padding(self):
        # 0xdbd2bf, split at bit positions 0, 6, 12 and 18.
        self.assertEqual(bytes([0x3F, 0x0A, 0x3D, 0x36]), to_symbols(bytes.fromhex('bfd2db')))
        self.assertEqual(b'\xff', from_symbols(bytes([63, 3]), 6, 1))
        for values in (b'\x3f\x07', b'\x3f', b'\x40\0', b'\x3f\x03\0'):
            with self.assertRaises(ValueError):
                from_symbols(values, 6, 1)
        for width in (0, 5, 7, 8):
            for function, args in ((page_capacity, (width,)), (make_pages, (large_frame(), 1, width)),
                                   (read_page, (b'', width)), (Assembler, (1, width)), (to_symbols, (b'a', width)),
                                   (from_symbols, (b'', width, 0))):
                with self.subTest(function=function.__name__, width=width), self.assertRaises(ValueError):
                    function(*args)

    def test_page_header_crc_and_wire_model(self):
        frame = pack_frame(1, 1, 9, 9, {0: Node(reference_format.SOLID, record=b'\xab\xcd\xef')})
        pages = make_pages(frame, 7)
        self.assertEqual(12256, page_capacity())
        self.assertEqual(32, PAGE_HEADER.size)
        self.assertEqual([107], [len(page) for page in pages])
        raw = page_bytes(pages[0])
        expected_header = bytes.fromhex('4d43503101060100 07000000 09000000 00000100 09000000 30000000 00000000')
        self.assertEqual(expected_header[:28], raw[:28])
        self.assertEqual(zlib.crc32(expected_header + frame), struct.unpack_from('<I', raw, 28)[0])
        self.assertEqual(frame, raw[32:])
        self.assertEqual((7, 9, 0, 1, 9, 48, 1, 6), tuple(getattr(read_page(pages[0]), field) for field in
                         ('stream_id', 'frame_id', 'number', 'count', 'reference_id', 'frame_bytes', 'flags', 'symbol_bits')))
        self.assertEqual(146, wire_bytes(pages))
        self.assertEqual(16402, wire_bytes(pages, full_maps=True))
        self.assertEqual(261, wire_bytes([bytes(129)], packet_overhead=5))

    def test_round_trip_reordering_duplicates_and_crc(self):
        frame = large_frame()
        pages = make_pages(frame)
        self.assertEqual(2, len(pages))
        self.assertEqual(16384, len(pages[0]))
        assembler = Assembler()
        self.assertIsNone(assembler.push(pages[1]))
        self.assertIsNone(assembler.push(pages[1]))
        self.assertEqual(frame, assembler.push(pages[0]))
        self.assertEqual({}, assembler.pending)
        damaged = bytearray(pages[0])
        damaged[100] ^= 1
        with self.assertRaisesRegex(ValueError, 'CRC'):
            assembler.push(bytes(damaged))
        self.assertEqual({}, assembler.pending)

    def test_metadata_alphabet_and_padding_rejections(self):
        raw = page_bytes(make_pages(large_frame())[0])
        mutations = [(0, ord('X'), 'B'), (4, 2, 'B'), (5, 7, 'B'), (6, 2, 'H'), (16, 2, 'H'),
                     (18, 3, 'H'), (24, 19, 'I'), (24, 131072, 'I')]
        for offset, value, code in mutations:
            with self.subTest(offset=offset), self.assertRaises(ValueError):
                read_page(to_symbols(with_crc(changed(raw, offset, value, code))))
        for symbols in (b'', bytes(42), bytes(16385), make_pages(large_frame())[0][:-1]):
            with self.assertRaises(ValueError):
                read_page(symbols)
        # 80 bytes are 106 symbols and 4 bits: the last symbol has two padding bits.
        symbols = bytearray(make_pages(pack_frame(1, 1, 0, 0, {0: Node(reference_format.SOLID, record=bytes(3))}))[0])
        symbols[-1] |= 32
        with self.assertRaisesRegex(ValueError, 'padding'):
            read_page(bytes(symbols))
        symbols[0] |= 64
        with self.assertRaisesRegex(ValueError, 'alphabet'):
            read_page(bytes(symbols))

    def test_pending_limit_wrong_stream_and_conflicts(self):
        assembler = Assembler()
        for frame_id in range(5):
            self.assertIsNone(assembler.push(make_pages(large_frame(frame_id))[0]))
        self.assertEqual([1, 2, 3, 4], list(assembler.pending))
        with self.assertRaisesRegex(ValueError, 'wrong stream'):
            assembler.push(make_pages(large_frame(5), 2)[0])
        self.assertEqual([1, 2, 3, 4], list(assembler.pending))
        first, last = make_pages(large_frame(4))
        conflicting = with_crc(changed(page_bytes(first), 40, 1))
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            assembler.push(to_symbols(conflicting))
        self.assertNotIn(4, assembler.pending)
        assembler.push(first)
        disagreement = with_crc(changed(page_bytes(last), 20, 17, 'I'))
        with self.assertRaisesRegex(ValueError, 'inconsistent'):
            assembler.push(to_symbols(disagreement))
        self.assertNotIn(4, assembler.pending)

    def test_complete_frame_validation_and_identity(self):
        frame = pack_frame(1, 1, 0, 0, {})
        raw = page_bytes(make_pages(frame)[0])
        for offset, value in ((12, 1), (20, 1), (6, 0)):
            with self.subTest(offset=offset), self.assertRaisesRegex(ValueError, 'identity mismatch'):
                Assembler().push(to_symbols(with_crc(changed(raw, offset, value))))
        invalid = with_crc(changed(raw, 32 + 5, 1))
        with self.assertRaisesRegex(ValueError, 'not an MCV2 version 3'):
            Assembler().push(to_symbols(invalid))
        # A page may hold 20 bytes even though those bytes cannot hold a complete v3 index.
        header = PAGE_HEADER.pack(b'MCP1', 1, 6, 1, 1, 0, 0, 1, 0, 20, 0)
        symbols = to_symbols(with_crc(header + bytes(20)))
        self.assertEqual(20, read_page(symbols).frame_bytes)
        with self.assertRaises(ValueError):
            Assembler().push(symbols)


if __name__ == '__main__':
    unittest.main()
