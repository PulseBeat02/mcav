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

"""Six-bit strip extraction and the remaining fitting-table generator."""

import sys
import unittest
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / 'tools/mcv2'))
import strip_check
import tables
from mcvideo.transport import make_pages
from mcvideo.v3 import pack_frame


class StripTest(unittest.TestCase):
    def test_rgb_strip_returns_the_exact_six_bit_symbols(self):
        frame = pack_frame(1, 1, 7, 7, True, (1, 2, 3), {})
        symbols = make_pages(frame, 5)[0]
        padded = np.zeros(16384, np.uint32)
        padded[:len(symbols)] = np.frombuffer(symbols, np.uint8)
        groups = padded.reshape(-1, 4)
        words = groups[:, 0] | groups[:, 1] << 6 | groups[:, 2] << 12 | groups[:, 3] << 18
        image = np.stack((words & 255, words >> 8 & 255, words >> 16), axis=1).astype(np.uint8).reshape(32, 128, 3)
        actual = strip_check.page_symbols(image, 0, 32)
        self.assertEqual(padded.astype(np.uint8).tobytes(), actual.tobytes())
        page = strip_check.read_strip_page(actual)
        self.assertEqual(frame, page.payload)
        self.assertEqual((5, 7, 6), (page.stream_id, page.frame_id, page.symbol_bits))

    def test_invalid_and_truncated_strip_pages_are_rejected(self):
        for symbols in (np.zeros(0, np.uint8), np.zeros(42, np.uint8), np.zeros(16384, np.uint8)):
            with self.assertRaises(ValueError):
                strip_check.read_strip_page(symbols)
        frame = pack_frame(1, 1, 0, 0, True, (0, 0, 0), {})
        damaged = np.frombuffer(make_pages(frame)[0], np.uint8).copy()
        damaged[50] ^= 1
        with self.assertRaisesRegex(ValueError, 'CRC'):
            strip_check.read_strip_page(damaged)


class FittingTablesTest(unittest.TestCase):
    def test_existing_matrix_resource_remains_byte_exact(self):
        self.assertEqual(3360, len(tables.fitting_matrices()))
        self.assertEqual((tables.RESOURCES / 'fitting_matrices.bin').read_bytes(), tables.fitting_matrices())

    def test_grid_fit_recovers_hand_computed_nodes(self):
        # A linear node ramp, with the endpoint-clamped eight-pixel interpolation of §8.
        samples = np.array([-3, -2.75, -2.25, -1.75, -1.25, -0.75, -0.25, 0], np.float32)
        np.testing.assert_allclose([-3, -2, -1, 0], tables.fitting_matrix(8, 4) @ samples, rtol=0, atol=3e-7)


if __name__ == '__main__':
    unittest.main()
