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

import itertools
import unittest
from typing import Any

import numpy

from rejection_cases import changed
import mcv2_reference
from mcv2_reference import Decoder, decode
from mcv2_reference import Node, pack_frame

WIDTH: int = 3
HEIGHT: int = 2
PICTURE_SHAPE: tuple[int, int, int] = (HEIGHT, WIDTH, mcv2_reference.CHANNELS)

ZERO_VECTOR: bytes = b'\0\0'


class ReconstructionTest(unittest.TestCase):
    def test_should_decode_absent_and_skipped_keyframe_blocks_black_and_solid_blocks_in_their_color(self) -> None:
        for node in (None, Node(mcv2_reference.SKIP)):
            decoded: numpy.ndarray = decode(frame(node))
            numpy.testing.assert_array_equal(numpy.zeros(PICTURE_SHAPE, numpy.uint8), decoded)

        decoded = decode(frame(Node(mcv2_reference.SOLID, record=b'\4\5\6')))
        numpy.testing.assert_array_equal(numpy.array([[[4, 5, 6]] * WIDTH] * HEIGHT, numpy.uint8), decoded)

    def test_should_read_palette_selectors_least_significant_bit_first_and_row_major_at_all_sizes(self) -> None:
        for size in mcv2_reference.LEAF_SIZES:
            selectors: bytearray = bytearray(size * size // mcv2_reference.BITS_PER_BYTE)
            selectors[0], selectors[size // mcv2_reference.BITS_PER_BYTE] = 0b00000101, 0b00000010
            node: Node = Node(mcv2_reference.PALETTE, record=bytes([1, 2, 3, 91, 92, 93]) + selectors)
            expected: numpy.ndarray = numpy.array([[[91, 92, 93], [1, 2, 3], [91, 92, 93]],
                                 [[1, 2, 3], [91, 92, 93], [1, 2, 3]]], numpy.uint8)
            decoded: numpy.ndarray = decode(frame(node, size=size))
            numpy.testing.assert_array_equal(expected, decoded)

    def test_should_keep_full_precision_on_pattern_axes_at_all_sizes(self) -> None:
        colors: bytes = bytes([9, 21, 25, 67, 122, 238])
        orientations: tuple[int, int] = (mcv2_reference.VERTICAL_STRIPES, mcv2_reference.HORIZONTAL_STRIPES)
        for size, orientation in itertools.product(mcv2_reference.LEAF_SIZES, orientations):
            other_selectors: bytes = bytes(size // mcv2_reference.BITS_PER_BYTE - 1)
            node: Node = Node(mcv2_reference.PATTERN, record=colors + bytes([orientation, 0b00000101]) + other_selectors)
            expected: numpy.ndarray = striped_picture(orientation)
            with self.subTest(size=size, orientation=orientation):
                decoded: numpy.ndarray = decode(frame(node, size=size))
                numpy.testing.assert_array_equal(expected, decoded)

    def test_should_sign_and_clamp_whole_pixel_motion(self) -> None:
        reference: numpy.ndarray = gray([[1, 2, 3], [4, 5, 6]])
        cases: list[tuple[bytes, list[list[int]]]] = [(b'\1\xff', [[2, 3, 3], [2, 3, 3]]), (b'\x80\x7f', [[4, 4, 4], [4, 4, 4]]),
                 (b'\x7f\x80', [[3, 3, 3], [3, 3, 3]]), (b'\2\0', [[3, 3, 3], [6, 6, 6]])]
        for record, expected in cases:
            decoded: numpy.ndarray = decode(frame(Node(mcv2_reference.MOTION, record=record), frame_id=1, reference_id=0), reference, 0)
            numpy.testing.assert_array_equal(gray(expected), decoded)

        decoded = decode(frame(frame_id=1, reference_id=0), reference, 0)
        numpy.testing.assert_array_equal(reference, decoded)

    def test_should_sign_and_clamp_compact_vectors(self) -> None:
        reference: numpy.ndarray = gray([[1, 2, 3], [4, 5, 6]])
        for vector, expected in [(ZERO_VECTOR, [[1, 2, 3], [4, 5, 6]]),
                                 (b'\1\xff', [[2, 3, 3], [2, 3, 3]]),
                                 (b'\xf8\x07', [[4, 4, 4], [4, 4, 4]]),
                                 (b'\x7f\x80', [[3, 3, 3], [3, 3, 3]])]:
            data: bytes = frame(Node(mcv2_reference.COMPACT, record=vector + bytes(mcv2_reference.GRID_BYTES)), frame_id=1, reference_id=0)
            decoded: numpy.ndarray = decode(data, reference, 0)
            numpy.testing.assert_array_equal(gray(expected), decoded)

    def test_should_apply_quantizers_and_clip(self) -> None:
        for nodes, expected in [(0x11, [129, 130, 132]), (0x88, [120, 112, 96])]:
            for quantizer, value in enumerate(expected):
                record: bytes = ZERO_VECTOR + bytes([nodes] * mcv2_reference.GRID_BYTES)
                data: bytes = frame(Node(mcv2_reference.COMPACT, quantizer, record), width=1, height=1, frame_id=1, reference_id=0)
                decoded: numpy.ndarray = decode(data, numpy.full((1, 1, mcv2_reference.CHANNELS), 128, numpy.uint8), 0)
                numpy.testing.assert_array_equal(numpy.array([[[value] * mcv2_reference.CHANNELS]], numpy.uint8), decoded)

        for nodes, pixel, expected in [(0x77, 250, 255), (0x88, 5, 0)]:
            record = ZERO_VECTOR + bytes([nodes] * mcv2_reference.GRID_BYTES)
            data = frame(Node(mcv2_reference.COMPACT, 2, record), width=1, height=1, frame_id=1, reference_id=0)
            decoded = decode(data, numpy.full((1, 1, mcv2_reference.CHANNELS), pixel, numpy.uint8), 0)
            numpy.testing.assert_array_equal(numpy.array([[[expected] * mcv2_reference.CHANNELS]], numpy.uint8), decoded)

    def test_should_give_exact_pixels_on_every_channel_for_a_grid_of_16_at_quantizer_2(self) -> None:
        # Every row has luma nodes -2,-1,0,1; the same luma is added to red, green and blue.
        record: bytes = ZERO_VECTOR + bytes.fromhex('fe10fe10fe10fe10')
        data: bytes = frame(Node(mcv2_reference.COMPACT, 2, record), width=16, height=16, size=16, frame_id=1, reference_id=0)
        expected_row: list[list[int]] = [[92, 112, 132], [92, 112, 132], [93, 113, 133], [94, 114, 134],
                        [95, 115, 135], [96, 116, 136], [97, 117, 137], [98, 118, 138],
                        [99, 119, 139], [100, 120, 140], [101, 121, 141], [102, 122, 142],
                        [103, 123, 143], [104, 124, 144], [104, 124, 144], [104, 124, 144]]
        reference: numpy.ndarray = numpy.broadcast_to(numpy.array([100, 120, 140], numpy.uint8), (16, 16, mcv2_reference.CHANNELS)).copy()
        decoded: numpy.ndarray = decode(data, reference, 0)
        numpy.testing.assert_array_equal(numpy.array([expected_row] * 16, numpy.uint8), decoded)

    def test_should_interpolate_the_luma_grid_bilinearly_on_both_axes_and_round_half_up(self) -> None:
        record: bytes = ZERO_VECTOR + bytes.fromhex('ed0ffe100f211032')
        data: bytes = frame(Node(mcv2_reference.COMPACT, record=record), width=8, height=8, size=8, frame_id=1, reference_id=0)
        expected: list[list[int]] = [[97, 97, 98, 98, 99, 99, 100, 100], [97, 98, 98, 99, 99, 100, 100, 100],
                    [98, 98, 99, 99, 100, 100, 101, 101], [98, 99, 99, 100, 100, 101, 101, 101],
                    [99, 99, 100, 100, 101, 101, 102, 102], [99, 100, 100, 101, 101, 102, 102, 102],
                    [100, 100, 101, 101, 102, 102, 103, 103], [100, 100, 101, 101, 102, 102, 103, 103]]
        decoded: numpy.ndarray = decode(data, numpy.full((8, 8, mcv2_reference.CHANNELS), 100, numpy.uint8), 0)
        numpy.testing.assert_array_equal(gray(expected), decoded)

        record = ZERO_VECTOR + b'\7' + bytes(mcv2_reference.GRID_BYTES - 1)
        impulse: bytes = frame(Node(mcv2_reference.COMPACT, record=record), width=8, height=8, size=8, frame_id=1, reference_id=0)
        expected = [[7, 5, 2, 0, 0, 0, 0, 0], [5, 4, 1, 0, 0, 0, 0, 0], [2, 1, 0, 0, 0, 0, 0, 0]] + [[0] * 8] * 5
        decoded = decode(impulse, numpy.zeros((8, 8, mcv2_reference.CHANNELS), numpy.uint8), 0)
        numpy.testing.assert_array_equal(gray(expected), decoded)

    def test_should_read_only_the_reference_and_crop_whole_blocks(self) -> None:
        solid: Node = Node(mcv2_reference.SOLID, record=bytes([200] * mcv2_reference.CHANNELS))
        motion: Node = Node(mcv2_reference.MOTION, record=b'\xf0\0')
        compact: Node = Node(mcv2_reference.COMPACT, record=ZERO_VECTOR + b'\x11' * mcv2_reference.GRID_BYTES)
        node: Node = Node(mcv2_reference.SPLIT, children=(solid, motion, Node(mcv2_reference.SKIP), compact))
        data: bytes = frame(node, width=17, height=17, frame_id=1, reference_id=0)
        expected: numpy.ndarray = numpy.full((17, 17, mcv2_reference.CHANNELS), 10, numpy.uint8)
        expected[:16, :16] = 200
        expected[16, 16] = 11
        reference: numpy.ndarray = numpy.full((17, 17, mcv2_reference.CHANNELS), 10, numpy.uint8)
        decoded: numpy.ndarray = decode(data, reference, 0)
        numpy.testing.assert_array_equal(expected, decoded)

        numpy.testing.assert_array_equal(numpy.full((17, 17, mcv2_reference.CHANNELS), 10, numpy.uint8), reference)

    def test_should_reject_missing_wrong_size_wrong_dtype_and_wrong_id_references(self) -> None:
        data: bytes = frame(frame_id=1, reference_id=0)
        transposed: tuple[int, int, int] = (WIDTH, HEIGHT, mcv2_reference.CHANNELS)
        for reference, reference_id in [(None, 0), (numpy.zeros(PICTURE_SHAPE, numpy.uint8), 2),
                                  (numpy.zeros(PICTURE_SHAPE, numpy.uint8), None), (numpy.zeros(transposed, numpy.uint8), 0),
                                  (numpy.zeros(PICTURE_SHAPE, numpy.int32), 0)]:
            with self.assertRaises(ValueError):
                decode(data, reference, reference_id)


class StreamTest(unittest.TestCase):
    def test_should_freeze_on_loss_recover_and_not_advance_on_failed_frames(self) -> None:
        decoder: Decoder = Decoder()
        with self.assertRaises(ValueError):
            decoder.accept(frame(frame_id=1, reference_id=0))

        first: numpy.ndarray = decoder.accept(frame(frame_id=4))
        for bad in (frame(frame_id=6, reference_id=5), changed(frame(frame_id=8), 6, 1), frame(frame_id=3)):
            with self.assertRaises(ValueError):
                decoder.accept(bad)

            frame_id: int | None = decoder.frame_id
            self.assertEqual(4, frame_id)

            reference: numpy.ndarray | None = decoder.reference
            numpy.testing.assert_array_equal(first, reference)

        frozen: numpy.ndarray = decoder.accept(frame(frame_id=10, reference_id=4))
        numpy.testing.assert_array_equal(first, frozen)

        recovered: numpy.ndarray = decoder.accept(frame(Node(mcv2_reference.SOLID, record=b'\1\2\3'), frame_id=11))
        numpy.testing.assert_array_equal(numpy.array([[[1, 2, 3]] * WIDTH] * HEIGHT, numpy.uint8), recovered)

        with self.assertRaises(ValueError):
            decoder.accept(frame(width=4, frame_id=12, reference_id=11))

        frame_id = decoder.frame_id
        self.assertEqual(11, frame_id)

        shape: tuple[Any, ...] = decoder.accept(frame(width=4, frame_id=12)).shape
        self.assertEqual((HEIGHT, 4, mcv2_reference.CHANNELS), shape)

    def test_should_wrap_around_at_half_range_and_ignore_caller_edits(self) -> None:
        decoder: Decoder = Decoder()
        first: numpy.ndarray = decoder.accept(
            frame(Node(mcv2_reference.SOLID, record=bytes([11, 22, 33])), frame_id=mcv2_reference.ID_MASK - 1)
        )
        first[:] = 0
        expected: numpy.ndarray = numpy.array([[[11, 22, 33]] * WIDTH] * HEIGHT, numpy.uint8)
        decoded: numpy.ndarray = decoder.accept(frame(frame_id=0, reference_id=mcv2_reference.ID_MASK - 1))
        numpy.testing.assert_array_equal(expected, decoded)

        for frame_id in (0, mcv2_reference.ID_HALF_RANGE, mcv2_reference.ID_MASK):
            with self.assertRaisesRegex(ValueError, 'not newer'):
                decoder.accept(frame(frame_id=frame_id))

        decoder.accept(frame(frame_id=mcv2_reference.ID_HALF_RANGE - 1))
        frame_id = decoder.frame_id
        self.assertEqual(mcv2_reference.ID_HALF_RANGE - 1, frame_id)


def frame(
    node: Node | None = None,
    width: int = WIDTH,
    height: int = HEIGHT,
    frame_id: int = 0,
    reference_id: int | None = None,
    size: int = mcv2_reference.SUPERBLOCK_SIZE,
) -> bytes:
    if reference_id is None:
        reference_id = frame_id

    return pack_frame(width, height, frame_id, reference_id, {} if node is None else {0: at_size(node, size)})


def at_size(node: Node, size: int) -> Node:
    while size < mcv2_reference.SUPERBLOCK_SIZE:
        node = Node(mcv2_reference.SPLIT, children=(node, Node(mcv2_reference.SKIP), Node(mcv2_reference.SKIP), Node(mcv2_reference.SKIP)))
        size *= mcv2_reference.QUADRANTS_PER_SIDE

    return node


def striped_picture(orientation: int) -> numpy.ndarray:
    if orientation == mcv2_reference.VERTICAL_STRIPES:
        return numpy.array([[[67, 122, 238], [9, 21, 25], [67, 122, 238]]] * HEIGHT, numpy.uint8)

    return numpy.array([[[67, 122, 238]] * WIDTH, [[9, 21, 25]] * WIDTH], numpy.uint8)


def gray(values: list[list[int]]) -> numpy.ndarray:
    return numpy.repeat(numpy.array(values, numpy.uint8)[..., None], mcv2_reference.CHANNELS, axis=-1)


if __name__ == '__main__':
    unittest.main()
