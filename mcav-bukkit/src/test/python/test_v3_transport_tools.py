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

"""Six-bit strip extraction."""

import unittest

import numpy

from mcv2_reference import make_pages
from mcv2_reference import pack_frame

import mcv2_tools


class StripTest(unittest.TestCase):
    def test_rgb_strip_returns_the_exact_six_bit_symbols(self):
        frame = pack_frame(1, 1, 7, 7, {})
        symbols = make_pages(frame, 5)[0]
        padded = numpy.zeros(16384, numpy.uint32)
        padded[:len(symbols)] = numpy.frombuffer(symbols, numpy.uint8)
        groups = padded.reshape(-1, 4)
        words = groups[:, 0] | groups[:, 1] << 6 | groups[:, 2] << 12 | groups[:, 3] << 18
        image = numpy.stack((words & 255, words >> 8 & 255, words >> 16), axis=1).astype(numpy.uint8).reshape(32, 128, 3)
        actual = mcv2_tools.strip_check_page_symbols(image, 0, 32)
        self.assertEqual(padded.astype(numpy.uint8).tobytes(), actual.tobytes())
        page = mcv2_tools.strip_check_read_strip_page(actual)
        self.assertEqual(frame, page.payload)
        self.assertEqual((5, 7, 6), (page.stream_id, page.frame_id, page.symbol_bits))

    def test_invalid_and_truncated_strip_pages_are_rejected(self):
        for symbols in (numpy.zeros(0, numpy.uint8), numpy.zeros(42, numpy.uint8), numpy.zeros(16384, numpy.uint8)):
            with self.assertRaises(ValueError):
                mcv2_tools.strip_check_read_strip_page(symbols)
        frame = pack_frame(1, 1, 0, 0, {})
        damaged = numpy.frombuffer(make_pages(frame)[0], numpy.uint8).copy()
        damaged[50] ^= 1
        with self.assertRaisesRegex(ValueError, 'CRC'):
            mcv2_tools.strip_check_read_strip_page(damaged)


if __name__ == '__main__':
    unittest.main()
