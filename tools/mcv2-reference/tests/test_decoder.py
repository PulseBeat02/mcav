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

"""Hand-computed pictures for every reconstruction rule and stream transition."""

import unittest

import numpy as np

from rejection_cases import changed
from mcvideo import format as fmt
from mcvideo.decoder import Decoder, decode
from mcvideo.v3 import Node, pack_frame


def at_size(node, size):
    while size < 32:
        node = Node(fmt.SPLIT, children=(node, Node(fmt.SKIP), Node(fmt.SKIP), Node(fmt.SKIP)))
        size *= 2
    return node


def frame(node=None, width=3, height=2, key=True, frame_id=0, reference_id=None, size=32):
    if reference_id is None:
        reference_id = frame_id if key else (frame_id - 1) & 0xFFFFFFFF
    return pack_frame(width, height, frame_id, reference_id, {} if node is None else {0: at_size(node, size)})


def gray(values):
    return np.repeat(np.array(values, np.uint8)[..., None], 3, axis=-1)


class ReconstructionTest(unittest.TestCase):
    def test_keyframe_absent_and_skip_are_black_and_solid(self):
        for node in (None, Node(fmt.SKIP)):
            np.testing.assert_array_equal(np.zeros((2, 3, 3), np.uint8), decode(frame(node)))
        np.testing.assert_array_equal(np.array([[[4, 5, 6]] * 3] * 2, np.uint8),
                                      decode(frame(Node(fmt.SOLID, record=b'\4\5\6'))))

    def test_palette_lsb_first_and_row_major_at_all_sizes(self):
        for size in (8, 16, 32):
            selectors = bytearray(size * size // 8)
            selectors[0], selectors[size // 8] = 0b00000101, 0b00000010
            node = Node(fmt.PALETTE, record=bytes([1, 2, 3, 91, 92, 93]) + selectors)
            expected = np.array([[[91, 92, 93], [1, 2, 3], [91, 92, 93]],
                                 [[1, 2, 3], [91, 92, 93], [1, 2, 3]]], np.uint8)
            np.testing.assert_array_equal(expected, decode(frame(node, size=size)))

    def test_pattern_axes_at_all_sizes_keep_full_precision(self):
        colors = bytes([9, 21, 25, 67, 122, 238])
        for size in (8, 16, 32):
            for orientation in (0, 1):
                node = Node(fmt.PATTERN, record=colors + bytes([orientation, 0b00000101]) + bytes(size // 8 - 1))
                expected = np.array(([[[67, 122, 238], [9, 21, 25], [67, 122, 238]]] * 2
                                     if orientation == 0 else
                                     [[[67, 122, 238]] * 3, [[9, 21, 25]] * 3]), np.uint8)
                with self.subTest(size=size, orientation=orientation):
                    np.testing.assert_array_equal(expected, decode(frame(node, size=size)))

    def test_whole_pixel_motion_sign_and_clamping(self):
        reference = gray([[1, 2, 3], [4, 5, 6]])
        cases = [(b'\1\xff', [[2, 3, 3], [2, 3, 3]]), (b'\x80\x7f', [[4, 4, 4], [4, 4, 4]]),
                 (b'\x7f\x80', [[3, 3, 3], [3, 3, 3]]), (b'\2\0', [[3, 3, 3], [6, 6, 6]])]
        for record, expected in cases:
            np.testing.assert_array_equal(gray(expected), decode(frame(Node(fmt.MOTION, record=record), key=False, frame_id=1), reference, 0))
        np.testing.assert_array_equal(reference, decode(frame(key=False, frame_id=1), reference, 0))

    def test_compact_vector_sign_and_clamping(self):
        reference = gray([[1, 2, 3], [4, 5, 6]])
        for vector, expected in [(b'\0\0', [[1, 2, 3], [4, 5, 6]]),
                                 (b'\1\xff', [[2, 3, 3], [2, 3, 3]]),
                                 (b'\xf8\x07', [[4, 4, 4], [4, 4, 4]]),
                                 (b'\x7f\x80', [[3, 3, 3], [3, 3, 3]])]:
            data = frame(Node(fmt.COMPACT, record=vector + bytes(8)), key=False, frame_id=1)
            np.testing.assert_array_equal(gray(expected), decode(data, reference, 0))

    def test_quantizers_and_clipping(self):
        for nodes, expected in [(0x11, [129, 130, 132]), (0x88, [120, 112, 96])]:
            for q, value in enumerate(expected):
                data = frame(Node(fmt.COMPACT, q, b'\0\0' + bytes([nodes] * 8)), width=1, height=1, key=False, frame_id=1)
                np.testing.assert_array_equal(np.array([[[value] * 3]], np.uint8), decode(data, np.full((1, 1, 3), 128, np.uint8), 0))
        for nodes, pixel, expected in [(0x77, 250, 255), (0x88, 5, 0)]:
            data = frame(Node(fmt.COMPACT, 2, b'\0\0' + bytes([nodes] * 8)), width=1, height=1, key=False, frame_id=1)
            np.testing.assert_array_equal(np.array([[[expected] * 3]], np.uint8), decode(data, np.full((1, 1, 3), pixel, np.uint8), 0))

    def test_grid_16_q2_exact_pixels_on_every_channel(self):
        # Every row has luma nodes -2,-1,0,1; the same luma is added to red, green and blue.
        record = b'\0\0' + bytes.fromhex('fe10fe10fe10fe10')
        data = frame(Node(fmt.COMPACT, 2, record), width=16, height=16, size=16, key=False, frame_id=1)
        expected_row = [[92, 112, 132], [92, 112, 132], [93, 113, 133], [94, 114, 134],
                        [95, 115, 135], [96, 116, 136], [97, 117, 137], [98, 118, 138],
                        [99, 119, 139], [100, 120, 140], [101, 121, 141], [102, 122, 142],
                        [103, 123, 143], [104, 124, 144], [104, 124, 144], [104, 124, 144]]
        reference = np.broadcast_to(np.array([100, 120, 140], np.uint8), (16, 16, 3)).copy()
        np.testing.assert_array_equal(np.array([expected_row] * 16, np.uint8), decode(data, reference, 0))

    def test_grid_y_bilinear_both_axes_and_round_half_up(self):
        record = b'\0\0' + bytes.fromhex('ed0ffe100f211032')
        data = frame(Node(fmt.COMPACT, record=record), width=8, height=8, size=8, key=False, frame_id=1)
        expected = [[97, 97, 98, 98, 99, 99, 100, 100], [97, 98, 98, 99, 99, 100, 100, 100],
                    [98, 98, 99, 99, 100, 100, 101, 101], [98, 99, 99, 100, 100, 101, 101, 101],
                    [99, 99, 100, 100, 101, 101, 102, 102], [99, 100, 100, 101, 101, 102, 102, 102],
                    [100, 100, 101, 101, 102, 102, 103, 103], [100, 100, 101, 101, 102, 102, 103, 103]]
        np.testing.assert_array_equal(gray(expected), decode(data, np.full((8, 8, 3), 100, np.uint8), 0))
        impulse = frame(Node(fmt.COMPACT, record=b'\0\0\7' + bytes(7)), width=8, height=8, size=8, key=False, frame_id=1)
        expected = [[7, 5, 2, 0, 0, 0, 0, 0], [5, 4, 1, 0, 0, 0, 0, 0], [2, 1, 0, 0, 0, 0, 0, 0]] + [[0] * 8] * 5
        np.testing.assert_array_equal(gray(expected), decode(impulse, np.zeros((8, 8, 3), np.uint8), 0))

    def test_leaves_read_only_the_reference_and_crop_whole_blocks(self):
        node = Node(fmt.SPLIT, children=(Node(fmt.SOLID, record=bytes([200] * 3)), Node(fmt.MOTION, record=b'\xf0\0'),
                                       Node(fmt.SKIP), Node(fmt.COMPACT, record=b'\0\0' + b'\x11' * 8)))
        data = frame(node, width=17, height=17, key=False, frame_id=1)
        expected = np.full((17, 17, 3), 10, np.uint8)
        expected[:16, :16] = 200
        expected[16, 16] = 11
        reference = np.full((17, 17, 3), 10, np.uint8)
        np.testing.assert_array_equal(expected, decode(data, reference, 0))
        np.testing.assert_array_equal(np.full((17, 17, 3), 10, np.uint8), reference)

    def test_missing_wrong_size_wrong_dtype_and_wrong_id_references(self):
        data = frame(key=False, frame_id=1)
        for reference, ref_id in [(None, 0), (np.zeros((2, 3, 3), np.uint8), 2),
                                  (np.zeros((2, 3, 3), np.uint8), None), (np.zeros((3, 2, 3), np.uint8), 0),
                                  (np.zeros((2, 3, 3), np.int32), 0)]:
            with self.assertRaises(ValueError):
                decode(data, reference, ref_id)


class StreamTest(unittest.TestCase):
    def test_loss_freeze_recovery_and_failed_frames_do_not_advance_state(self):
        decoder = Decoder()
        with self.assertRaises(ValueError):
            decoder.accept(frame(key=False, frame_id=1))
        first = decoder.accept(frame(frame_id=4))
        for bad in (frame(key=False, frame_id=6), changed(frame(frame_id=8), 6, 1), frame(frame_id=3)):
            with self.assertRaises(ValueError):
                decoder.accept(bad)
            self.assertEqual(4, decoder.frame_id)
            np.testing.assert_array_equal(first, decoder.reference)
        np.testing.assert_array_equal(first, decoder.accept(frame(key=False, frame_id=10, reference_id=4)))
        recovered = decoder.accept(frame(Node(fmt.SOLID, record=b'\1\2\3'), frame_id=11))
        np.testing.assert_array_equal(np.array([[[1, 2, 3]] * 3] * 2, np.uint8), recovered)
        with self.assertRaises(ValueError):
            decoder.accept(frame(width=4, key=False, frame_id=12, reference_id=11))
        self.assertEqual(11, decoder.frame_id)
        self.assertEqual((2, 4, 3), decoder.accept(frame(width=4, frame_id=12)).shape)

    def test_half_range_wraparound_and_caller_edits(self):
        decoder = Decoder()
        first = decoder.accept(frame(Node(fmt.SOLID, record=bytes([11, 22, 33])), frame_id=0xFFFFFFFE))
        first[:] = 0
        expected = np.array([[[11, 22, 33]] * 3] * 2, np.uint8)
        np.testing.assert_array_equal(expected, decoder.accept(frame(key=False, frame_id=0, reference_id=0xFFFFFFFE)))
        for frame_id in (0, 0x80000000, 0xFFFFFFFF):
            with self.assertRaisesRegex(ValueError, 'not newer'):
                decoder.accept(frame(frame_id=frame_id))
        decoder.accept(frame(frame_id=0x7FFFFFFF))
        self.assertEqual(0x7FFFFFFF, decoder.frame_id)


if __name__ == '__main__':
    unittest.main()
