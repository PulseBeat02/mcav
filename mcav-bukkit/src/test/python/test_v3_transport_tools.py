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

import numpy

import mcv2_reference
from mcv2_reference import make_pages
from mcv2_reference import pack_frame

import mcv2_tools


class StripTest(unittest.TestCase):
    def test_should_return_the_exact_six_bit_symbols_of_an_rgb_strip(self) -> None:
        frame: bytes = pack_frame(1, 1, 7, 7, {})
        symbols: bytes = make_pages(frame, 5)[0]
        padded: numpy.ndarray = numpy.zeros(mcv2_reference.PAGE_SYMBOLS, numpy.uint32)
        padded[:len(symbols)] = numpy.frombuffer(symbols, numpy.uint8)
        groups: numpy.ndarray = padded.reshape(-1, 4)
        words: numpy.ndarray = groups[:, 0] | groups[:, 1] << 6 | groups[:, 2] << 12 | groups[:, 3] << 18
        image: numpy.ndarray = numpy.stack((words & 255, words >> 8 & 255, words >> 16), axis=1).astype(numpy.uint8).reshape(32, 128, 3)
        actual: numpy.ndarray = mcv2_tools.strip_check_page_symbols(image, 0, 32)
        actual_bytes: bytes = actual.tobytes()
        self.assertEqual(padded.astype(numpy.uint8).tobytes(), actual_bytes)

        page: mcv2_reference.Page = mcv2_tools.strip_check_read_strip_page(actual)
        payload: bytes = page.payload
        self.assertEqual(frame, payload)

        identifiers: tuple[int, int, int] = (page.stream_id, page.frame_id, page.symbol_bits)
        self.assertEqual((5, 7, 6), identifiers)

    def test_should_reject_invalid_and_truncated_strip_pages(self) -> None:
        for symbols in (numpy.zeros(0, numpy.uint8), numpy.zeros(42, numpy.uint8), numpy.zeros(mcv2_reference.PAGE_SYMBOLS, numpy.uint8)):
            with self.assertRaises(ValueError):
                mcv2_tools.strip_check_read_strip_page(symbols)

        frame: bytes = pack_frame(1, 1, 0, 0, {})
        damaged: numpy.ndarray = numpy.frombuffer(make_pages(frame)[0], numpy.uint8).copy()
        damaged[50] ^= 1
        with self.assertRaisesRegex(ValueError, 'CRC'):
            mcv2_tools.strip_check_read_strip_page(damaged)


if __name__ == '__main__':
    unittest.main()
