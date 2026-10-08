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

"""Specification validation and serializer tests, including literal wire examples."""

import random
import unittest

from rejection_cases import changed, rejected_frames
import mcv2_reference as reference_format
from mcv2_reference import Node, pack_frame, parse_frame


class FormatTest(unittest.TestCase):
    def test_literal_frame_layout(self):
        expected = bytes.fromhex(
            '4d435632 03000000 0100 0100 09000000 09000000'
            '01000000 00000000 01000000 00000000 00000000 02 00000000 abcdef')
        actual = pack_frame(1, 1, 9, 9, {0: Node(reference_format.SOLID, record=b'\xab\xcd\xef')})
        self.assertEqual(expected, actual)
        frame = parse_frame(expected)
        self.assertEqual((1, 1, 9, 9, True, 45, 48),
                         (frame.width, frame.height, frame.frame_id, frame.reference_id, frame.keyframe,
                          frame.payload_start, frame.total))
        self.assertEqual((0, 0, 32, reference_format.SOLID, 0, 45),
                         (frame.leaves[0].pixel_x, frame.leaves[0].pixel_y, frame.leaves[0].size,
                          frame.leaves[0].mode, frame.leaves[0].quantizer, frame.leaves[0].offset))

    def test_every_validation_rule(self):
        cases = rejected_frames()
        self.assertEqual({'9.1', '9.3', '9.4', '9.5', '9.6'}, {case['rule'] for case in cases.values()})
        for name, case in cases.items():
            with self.subTest(name=name, rule=case['rule']):
                with self.assertRaisesRegex(ValueError, case['reason']):
                    parse_frame(bytes.fromhex(case['frame']))

    def test_level_order_coordinates_and_offsets(self):
        fine = Node(reference_format.SPLIT, children=tuple(Node(reference_format.SOLID, record=bytes([index, index, index])) for index in range(4)))
        roots = {1: Node(reference_format.SPLIT, children=(Node(reference_format.SKIP), fine, Node(reference_format.SKIP), Node(reference_format.SKIP))),
                 0: Node(reference_format.SOLID, record=b'abc')}
        frame = parse_frame(pack_frame(65, 17, 1, 1, roots))
        self.assertEqual((2, 4, 4), frame.level_counts)
        self.assertEqual(bytes([2, 6, 0, 6, 0, 0, 2, 2, 2, 2]), frame.descriptors)
        self.assertEqual((0, 9 | 2 << 17), frame.walk)
        self.assertEqual([(0, 0, 32), (32, 0, 16), (32, 16, 16), (48, 16, 16),
                          (48, 0, 8), (56, 0, 8), (48, 8, 8), (56, 8, 8), (64, 0, 32)],
                         [(leaf.pixel_x, leaf.pixel_y, leaf.size) for leaf in frame.leaves])
        self.assertEqual(roots, frame.roots)

    def test_serializer_round_trips_whole_patterns_at_every_size(self):
        randomizer = random.Random(1541)
        def leaf(size):
            return Node(reference_format.PATTERN, record=bytes(randomizer.randrange(256) for _ in range(6))
                        + bytes([randomizer.randrange(2)]) + bytes(randomizer.randrange(256) for _ in range(size // 8)))
        def tree(size):
            if size > 8 and randomizer.random() < 0.8:
                return Node(reference_format.SPLIT, children=tuple(tree(size // 2) for _ in range(4)))
            return leaf(size)
        for _ in range(16):
            roots = {0: tree(32), 1: leaf(32), 2: tree(32), 3: Node(reference_format.SKIP)}
            data = pack_frame(51, 35, 44, 44, roots)
            frame = parse_frame(data)
            self.assertEqual(roots, frame.roots)
            self.assertEqual(len(data), frame.payload_start + sum(len(leaf.record) for leaf in frame.leaves))
            self.assertEqual(data, pack_frame(frame.width, frame.height, frame.frame_id, frame.reference_id, frame.roots))

    def test_present_skips_and_every_compact_quantizer_are_valid(self):
        for quantizer in range(3):
            frame = parse_frame(pack_frame(1, 1, 1, 0, {0: Node(reference_format.COMPACT, quantizer, bytes(10))}))
            self.assertEqual((quantizer, 10), (frame.leaves[0].quantizer, len(frame.leaves[0].record)))
        frame = parse_frame(pack_frame(1, 1, 0, 0, {0: Node(reference_format.SKIP)}))
        self.assertEqual({0: Node(reference_format.SKIP)}, frame.roots)

    def test_directory_and_maximum_dimensions(self):
        roots = {index: Node(reference_format.SKIP) for index in (0, 31, 32, 255, 256, 16383)}
        frame = parse_frame(pack_frame(4096, 4096, 0, 0, roots))
        self.assertEqual((0, 4, 5), frame.directory[:3])
        self.assertEqual(roots, frame.roots)
        self.assertEqual(16384, len(frame.leaves))

    def test_inclusive_frame_length_limit(self):
        roots = {index: Node(reference_format.PALETTE, record=bytes(134)) for index in range(965)}
        roots[965] = Node(reference_format.PATTERN, record=bytes(11))
        roots.update({index: Node(reference_format.SOLID, record=bytes(3)) for index in range(966, 993)})
        data = pack_frame(1024, 1024, 0, 0, roots)
        self.assertEqual(131071, len(data))
        self.assertEqual(131071, parse_frame(data).total)
        with self.assertRaisesRegex(ValueError, 'frame length'):
            parse_frame(data + b'\0')
        roots[993] = Node(reference_format.SOLID, record=bytes(3))
        with self.assertRaisesRegex(ValueError, 'length limit'):
            pack_frame(1024, 1024, 0, 0, roots)

    def test_serializer_refuses_invalid_trees(self):
        bad = [Node(reference_format.SPLIT), Node(reference_format.SKIP, 1), Node(reference_format.SKIP, record=b'x'), Node(reference_format.SOLID, record=b'x'),
               Node(reference_format.COMPACT, record=b'\0'), Node(reference_format.COMPACT, record=bytes(11)), Node(reference_format.COMPACT, 3, bytes(10)),
               Node(reference_format.SOLID, 1, bytes(3)), Node(reference_format.PATTERN, record=bytes(2)), Node(reference_format.PATTERN, record=bytes(12)),
               Node(reference_format.PATTERN, record=bytes(6) + b'\2' + bytes(4)),
               Node(reference_format.SKIP, children=(Node(reference_format.SKIP),)), Node(31)]
        split8 = Node(reference_format.SPLIT, children=(Node(reference_format.SKIP),) * 4)
        for _ in range(2):
            split8 = Node(reference_format.SPLIT, children=(split8,) * 4)
        bad.append(split8)
        for node in bad:
            with self.subTest(node=node), self.assertRaises(ValueError):
                pack_frame(32, 32, 1, 0, {0: node})

if __name__ == '__main__':
    unittest.main()
