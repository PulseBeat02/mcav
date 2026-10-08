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
import struct
import unittest

from rejection_cases import changed, rejected_frames
from mcvideo import format as fmt
from mcvideo.v3 import Node, expand_endpoints, pack_frame, parse_frame


class FormatTest(unittest.TestCase):
    def test_literal_frame_layout(self):
        expected = bytes.fromhex(
            '4d43563203010000 01000100 09000000 09000000 3d000000 40000000 10203000'
            '01000000 00000000 01000000 00000000 00000000 02 00000000 00000000 abcdef')
        actual = pack_frame(1, 1, 9, 9, True, (16, 32, 48), {0: Node(fmt.SOLID, record=b'\xab\xcd\xef')})
        self.assertEqual(expected, actual)
        frame = parse_frame(expected)
        self.assertEqual((1, 1, 9, 9, True, (16, 32, 48), 61, 64),
                         (frame.width, frame.height, frame.frame_id, frame.reference_id, frame.keyframe,
                          frame.default_color, frame.payload_start, frame.total))
        self.assertEqual((0, 0, 32, fmt.SOLID, 0, 61),
                         (frame.leaves[0].x, frame.leaves[0].y, frame.leaves[0].size,
                          frame.leaves[0].mode, frame.leaves[0].q, frame.leaves[0].offset))

    def test_every_validation_rule(self):
        cases = rejected_frames()
        self.assertEqual({f'9.{i}' for i in range(1, 7)}, {case['rule'] for case in cases.values()})
        for name, case in cases.items():
            with self.subTest(name=name, rule=case['rule']):
                with self.assertRaisesRegex(ValueError, case['reason']):
                    parse_frame(bytes.fromhex(case['frame']))

    def test_level_order_coordinates_and_offsets(self):
        fine = Node(fmt.SPLIT, children=tuple(Node(fmt.SOLID, record=bytes([i, i, i])) for i in range(4)))
        roots = {1: Node(fmt.SPLIT, children=(Node(fmt.SKIP), fine, Node(fmt.SKIP), Node(fmt.SKIP))),
                 0: Node(fmt.SOLID, record=b'abc')}
        frame = parse_frame(pack_frame(65, 17, 1, 1, True, (1, 2, 3), roots))
        self.assertEqual((2, 4, 4), frame.level_counts)
        self.assertEqual(bytes([2, 6, 0, 6, 0, 0, 2, 2, 2, 2]), frame.descriptors)
        self.assertEqual((0, 9 | 2 << 17), frame.walk)
        self.assertEqual([(0, 0, 32), (32, 0, 16), (32, 16, 16), (48, 16, 16),
                          (48, 0, 8), (56, 0, 8), (48, 8, 8), (56, 8, 8), (64, 0, 32)],
                         [(leaf.x, leaf.y, leaf.size) for leaf in frame.leaves])
        self.assertEqual(roots, frame.roots)

    def test_serializer_round_trips_with_every_table_combination(self):
        randomizer = random.Random(1541)
        pairs = (bytes.fromhex('00f8e007'), bytes.fromhex('1f00ffff'))
        for table_mask in range(16):
            selectors = {size: (bytes([size % 2]) + bytes([0xA5] * (size // 8)),) for size in (8, 16, 32)}
            def tree(size):
                if size > 8 and randomizer.random() < 0.8:
                    return Node(fmt.SPLIT, children=tuple(tree(size // 2) for _ in range(4)))
                return Node(fmt.PATTERN, record=expand_endpoints(randomizer.choice(pairs)) + selectors[size][0])
            roots = {0: tree(32), 2: tree(32), 3: Node(fmt.SKIP)}
            tables = {size: selectors[size] for index, size in enumerate((8, 16, 32)) if table_mask >> (index + 1) & 1}
            endpoints = pairs if table_mask & 1 else ()
            data = pack_frame(51, 35, 44, 44, True, (9, 8, 7), roots, endpoints, tables)
            frame = parse_frame(data)
            self.assertEqual(roots, frame.roots)
            self.assertEqual(endpoints, frame.endpoint_table)
            self.assertEqual(data, pack_frame(frame.width, frame.height, frame.frame_id, frame.reference_id,
                                             frame.keyframe, frame.default_color, frame.roots,
                                             frame.endpoint_table, frame.selector_tables))

    def test_unused_tables_present_skips_and_nonminimal_motion_are_valid(self):
        for form in range(3):
            frame = parse_frame(pack_frame(1, 1, 1, 0, False, (0, 0, 0),
                                {0: Node(fmt.COMPACT, 7, bytes([form << 4]) + bytes(form + 1))},
                                [bytes(4)], {8: [bytes(2)], 16: [bytes(3)], 32: [bytes(5)]}))
            self.assertEqual((1, 1, 1, 1), frame.table_counts)
        frame = parse_frame(pack_frame(1, 1, 0, 0, True, (0, 0, 0), {0: Node(fmt.SKIP)}))
        self.assertEqual({0: Node(fmt.SKIP)}, frame.roots)

    def test_directory_and_maximum_dimensions(self):
        roots = {i: Node(fmt.SKIP) for i in (0, 31, 32, 255, 256, 16383)}
        frame = parse_frame(pack_frame(4096, 4096, 0, 0, True, (0, 0, 0), roots))
        self.assertEqual((0, 4, 5), frame.directory[:3])
        self.assertEqual(roots, frame.roots)
        self.assertEqual(16384, len(frame.leaves))

    def test_inclusive_frame_length_limit(self):
        roots = {i: Node(fmt.PALETTE, record=bytes(134)) for i in range(965)}
        roots[965] = Node(fmt.SOLID, record=bytes(3))
        data = pack_frame(1024, 1024, 0, 0, True, (0, 0, 0), roots,
                          endpoint_table=[struct.pack('<I', i) for i in range(29)])
        self.assertEqual(131071, len(data))
        self.assertEqual(131071, parse_frame(data).total)
        with self.assertRaisesRegex(ValueError, 'frame length'):
            parse_frame(data + b'\0')
        with self.assertRaisesRegex(ValueError, 'length limit'):
            pack_frame(1024, 1024, 0, 0, True, (0, 0, 0), roots,
                       endpoint_table=[struct.pack('<I', i) for i in range(30)])

    def test_serializer_refuses_invalid_trees_and_tables(self):
        bad = [Node(fmt.SPLIT), Node(fmt.SKIP, 1), Node(fmt.SKIP, record=b'x'), Node(fmt.SOLID, record=b'x'),
               Node(fmt.COMPACT, record=b'\0'), Node(fmt.PATTERN, record=bytes(2)),
               Node(fmt.SKIP, children=(Node(fmt.SKIP),)), Node(31)]
        split8 = Node(fmt.SPLIT, children=(Node(fmt.SKIP),) * 4)
        for _ in range(2):
            split8 = Node(fmt.SPLIT, children=(split8,) * 4)
        bad.append(split8)
        for node in bad:
            with self.subTest(node=node), self.assertRaises(ValueError):
                pack_frame(32, 32, 1, 0, False, (0, 0, 0), {0: node})
        for options in ({'endpoint_table': [bytes(4)] * 2}, {'endpoint_table': [bytes(3)]},
                        {'selector_tables': {8: [bytes(2)] * 2}}, {'selector_tables': {16: [b'\2\0\0']}},
                        {'selector_tables': {4: []}}, {'endpoint_table': [bytes(4)] * 256}):
            with self.subTest(options=options), self.assertRaises(ValueError):
                pack_frame(1, 1, 0, 0, True, (0, 0, 0), {}, **options)
        with self.assertRaisesRegex(ValueError, 'endpoints missing'):
            pack_frame(1, 1, 0, 0, True, (0, 0, 0), {0: Node(fmt.PATTERN, record=b'abcdef' + bytes(5))}, [bytes(4)])
        with self.assertRaisesRegex(ValueError, 'selector missing'):
            pack_frame(1, 1, 0, 0, True, (0, 0, 0), {0: Node(fmt.PATTERN, record=bytes(11))},
                       selector_tables={32: [b'\1' + bytes(4)]})


if __name__ == '__main__':
    unittest.main()
