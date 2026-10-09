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

"""V3 fixture coverage, archive framing and Java-owned fixture preservation."""

import hashlib
import json
import shutil
import random
import tempfile
import unittest
from contextlib import redirect_stderr
from io import StringIO
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
import mcv2_reference
import mcv2_tools
from mcv2_reference import Node, pack_frame, parse_frame


class FixtureCoverageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.streams = mcv2_tools.edge_streams_build_streams()

    def test_every_leaf_size_quantizer_vector_extreme_and_pattern_axis(self):
        modes, compacts, vectors, dimensions = set(), set(), set(), set()
        max_cursor = max_splits = max_length = 0
        for frames in self.streams.values():
            for data in frames:
                frame = parse_frame(data)
                dimensions.add((frame.width, frame.height))
                max_length = max(max_length, frame.total)
                for checkpoint in frame.walk:
                    max_cursor = max(max_cursor, checkpoint & 131071)
                    max_splits = max(max_splits, checkpoint >> 17)
                for leaf in frame.leaves:
                    modes.add((leaf.mode, leaf.size))
                    if leaf.mode == mcv2_reference.COMPACT:
                        compacts.add((leaf.size, leaf.quantizer))
                        vectors.add(leaf.record[:2])
        self.assertEqual({(mode, size) for mode in range(6) for size in (8, 16, 32)}, modes)
        self.assertEqual({(size, quantizer) for size in (8, 16, 32) for quantizer in range(3)}, compacts)
        self.assertTrue({b'\0\0', b'\x80\x7f', b'\x7f\x80', b'\x80\x80', b'\x7f\x7f'} <= vectors)
        self.assertTrue({(1, 1), (1, 97), (97, 1), (97, 65), (4096, 4096)} <= dimensions)
        self.assertGreater(max_cursor, 128000)
        self.assertGreater(max_splits, 20000)
        self.assertEqual(131071, max_length)
        patterns = {(leaf.size, leaf.record[6], leaf.record[7:]) for leaf in
                    parse_frame(self.streams['edge-patterns.mcs'][0]).leaves}
        for size in (8, 16, 32):
            for orientation in (0, 1):
                for axis in (0, 255, 0xA5):
                    self.assertIn((size, orientation, bytes([axis]) * (size // 8)), patterns)

    def test_committed_edge_streams_equal_the_deterministic_serializer_output(self):
        root = ROOT / 'mcav-bukkit/src/test/resources/mcv2/edge'
        self.assertEqual(set(self.streams), {path.name for path in root.glob('*.mcs')})
        for name, frames in self.streams.items():
            with self.subTest(stream=name):
                self.assertEqual((root / name).read_bytes(), mcv2_tools.archive_bytes(frames))

    def test_random_streams_are_reproducible_and_decodable(self):
        left = mcv2_tools.edge_streams_random_stream(random.Random(57))
        right = mcv2_tools.edge_streams_random_stream(random.Random(57))
        self.assertEqual(left, right)
        self.assertEqual(6, len(mcv2_tools.fixtures_digests(mcv2_tools.archive_bytes(left))))


class FixtureToolTest(unittest.TestCase):
    def test_archive_round_trip_and_truncations(self):
        values = [b'abcd', b'', b'MCV2']
        self.assertEqual(values, list(mcv2_tools.archive_frames(mcv2_tools.archive_bytes(values))))
        for raw in (b'\1', b'\1\0', b'\1\0\0', b'\2\0\0\0a'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                list(mcv2_tools.archive_frames(raw))

    def test_conformance_skips_v2_and_checks_v3_without_touching_streams(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / 'conformance'
            output.mkdir()
            old_stream = mcv2_tools.archive_bytes([b'MCV2\2' + bytes(27)])
            old_digests = '{"old.mcs": {"unchanged": true}}\n'
            (output / 'old.mcs').write_bytes(old_stream)
            (output / 'digests.json').write_text(old_digests)
            messages = StringIO()
            with redirect_stderr(messages):
                mcv2_tools.fixtures_conformance(root)
            self.assertIn('skip non-v3 stream:', messages.getvalue())
            self.assertEqual(old_digests, (output / 'digests.json').read_text())
            data = pack_frame(1, 1, 3, 3, {0: Node(mcv2_reference.SOLID, record=bytes([10, 20, 30]))})
            archive = mcv2_tools.archive_bytes([data])
            (output / 'new.mcs').write_bytes(archive)
            with redirect_stderr(StringIO()):
                mcv2_tools.fixtures_conformance(root)
                mcv2_tools.fixtures_pages(root)
            result = json.loads((output / 'digests.json').read_text())
            self.assertEqual({'unchanged': True}, result['old.mcs'])
            self.assertEqual([hashlib.sha256(bytes([10, 20, 30])).hexdigest()], result['new.mcs']['sha256_per_frame'])
            self.assertEqual(old_stream, (output / 'old.mcs').read_bytes())
            self.assertEqual(archive, (output / 'new.mcs').read_bytes())
            pages = json.loads((output / 'pages.json').read_text())
            self.assertEqual('committed v3 conformance streams', pages['source'])
            self.assertEqual(6, pages['symbol_bits'])
            self.assertEqual(4, len(pages['frames']))
            self.assertEqual({'conformance/new.mcs'}, {entry['stream'] for entry in pages['frames']})

    def test_encoder_only_decodes_and_never_rewrites(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / 'encoder'
            output.mkdir()
            data = mcv2_tools.archive_bytes([pack_frame(1, 1, 0, 0, {0: Node(mcv2_reference.SOLID, record=bytes([1, 2, 3]))})])
            golden = output / 'golden.mcs'
            golden.write_bytes(data)
            with redirect_stderr(StringIO()):
                mcv2_tools.fixtures_encoder(root)
            self.assertEqual(data, golden.read_bytes())
            golden.write_bytes(mcv2_tools.archive_bytes([pack_frame(1, 1, 1, 0, {})]))
            with self.assertRaisesRegex(ValueError, 'reference'):
                mcv2_tools.fixtures_encoder(root)

    def test_committed_pages_cover_conformance_and_multi_page_edges(self):
        root = ROOT / 'mcav-bukkit/src/test/resources/mcv2'
        table = json.loads((root / 'conformance/pages.json').read_text())
        self.assertEqual(6, table['symbol_bits'])
        self.assertEqual(6, len(table['frames']))
        self.assertEqual({1, 2, 11}, {len(entry['pages']) for entry in table['frames']})
        self.assertEqual({'conformance', 'edge'}, {entry['stream'].split('/')[0] for entry in table['frames']})


    def test_regenerated_pages_match_all_committed_vectors_byte_for_byte(self):
        root = ROOT / 'mcav-bukkit/src/test/resources/mcv2'
        expected = (root / 'conformance/pages.json').read_bytes()
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            for name in ('conformance', 'edge'):
                shutil.copytree(root / name, output / name)
            mcv2_tools.fixtures_pages(output)
            self.assertEqual(expected, (output / 'conformance/pages.json').read_bytes())


if __name__ == '__main__':
    unittest.main()
