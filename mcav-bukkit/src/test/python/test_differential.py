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

"""Strict differential comparisons, with no exemptions or hidden parser failures."""

import hashlib
import json
import random
import tempfile
import unittest
from contextlib import redirect_stdout, redirect_stderr
from io import StringIO
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import mcv2_reference
import mcv2_tools
from mcv2_reference import Node, pack_frame
from mcv2_tools import archive_bytes as archive


class DifferentialTest(unittest.TestCase):
    def report(self, frames, tokens):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            arguments = SimpleNamespace(out=output, java='unused', classpath='unused', seed=42)
            response = SimpleNamespace(returncode=0, stderr='', stdout=str(output / 'archives/tree-0000.mcs') + ' ' + ' '.join(tokens))
            with patch.object(mcv2_tools, 'differential_build_archives', return_value={'tree-0000': frames}),\
                    patch.object(mcv2_tools, 'differential_reference_tokens', return_value=['0' * 64] * len(frames)),\
                    patch.object(mcv2_tools.subprocess, 'run', return_value=response) as launch, redirect_stdout(StringIO()):
                status = mcv2_tools.differential_run(arguments)
            self.assertEqual(['unused', '-cp', 'unused', 'me.brandonli.mcav.bukkit.media.mcv2.Mcv2Tools', 'digests',
                              str(output / 'archives/tree-0000.mcs')], launch.call_args.args[0])
            return status, json.loads((output / 'summary.json').read_text())

    def test_supported_frames_cannot_be_exempted_as_unsupported(self):
        status, result = self.report([b'frame'], ['unsupported'])
        self.assertEqual(1, status)
        self.assertEqual(1, result['counts']['disagreements'])
        self.assertNotIn('unsupported_skipped', result['counts'])
        self.assertEqual(0, result['counts']['decoded'])

    def test_a_supported_control_is_actually_compared(self):
        status, result = self.report([b'frame'], ['0' * 64])
        self.assertEqual(0, status)
        self.assertEqual(1, result['counts']['decoded'])

    def test_all_frames_after_a_disagreement_are_still_compared(self):
        status, result = self.report([b'one', b'two', b'three'], ['unsupported', 'reject', '0' * 64])
        self.assertEqual(1, status)
        self.assertEqual(3, result['counts']['frames'])
        self.assertEqual(2, result['counts']['disagreements'])
        self.assertEqual(1, result['counts']['decoded'])
        self.assertEqual([0, 1], [entry['frame'] for entry in result['disagreements']])

    def test_rejections_pixels_missing_and_extra_results(self):
        counts, failures = mcv2_tools.differential_compare({'same': ['reject', 'a' * 64, 'b' * 64], 'missing': ['reject']},
                                                {'same': ['reject', 'a' * 64, 'c' * 64], 'extra': ['reject']})
        self.assertEqual(4, counts['frames'])
        self.assertEqual(1, counts['decoded'])
        self.assertEqual(1, counts['refused'])
        self.assertEqual(4, counts['disagreements'])
        self.assertEqual('b' * 64, failures[0]['reference'])

    def test_reference_only_treats_value_error_as_rejection(self):
        first = pack_frame(1, 1, 0, 0, {0: Node(mcv2_reference.SOLID, record=b'\1\2\3')})
        skipped = pack_frame(1, 1, 2, 1, {})
        recovery = pack_frame(1, 1, 3, 3, {0: Node(mcv2_reference.SOLID, record=b'\4\5\6')})
        self.assertEqual([hashlib.sha256(b'\1\2\3').hexdigest(), 'reject', 'reject', hashlib.sha256(b'\4\5\6').hexdigest()],
                         mcv2_tools.differential_reference_tokens([first, b'MCV1', skipped, recovery]))
        with patch.object(mcv2_tools.Decoder, 'accept', side_effect=RuntimeError('bug')):
            with self.assertRaisesRegex(RuntimeError, 'bug'):
                mcv2_tools.differential_reference_tokens([first])

    def test_random_and_committed_inputs_both_get_mutants(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = pack_frame(1, 1, 0, 0, {0: Node(mcv2_reference.SOLID, record=b'\1\2\3')})
            (root / 'test.mcs').write_bytes(archive([original]))
            arguments = SimpleNamespace(seed=19, streams=2, conformance=2, mutants=2, corpus=root)
            chunks = mcv2_tools.differential_build_archives(arguments)
            self.assertEqual(12, len(chunks))
            self.assertEqual([original], chunks['conformance-0000-test'])
            self.assertNotEqual([original], chunks['conformance-0000-test-mutant-0'])
            self.assertNotEqual(chunks['tree-0000'], chunks['tree-0000-mutant-1'])
            self.assertEqual(chunks, mcv2_tools.differential_build_archives(arguments))

    def test_mutation_includes_truncation_and_byte_changes(self):
        original = [b'abcdefgh', b'ijklmnop']
        mutations = [mcv2_tools.differential_mutant(original, random.Random(seed)) for seed in range(100)]
        self.assertTrue(any(any(len(frame) < 8 for frame in chunks) for chunks in mutations))
        self.assertTrue(any(all(len(frame) == 8 for frame in chunks) for chunks in mutations))
        self.assertTrue(all(chunks != original for chunks in mutations))
        self.assertEqual([b'abcdefgh', b'ijklmnop'], original)

    def test_java_failures_are_findings(self):
        with tempfile.TemporaryDirectory() as directory:
            arguments = SimpleNamespace(out=Path(directory), java='unused', classpath='unused', seed=42)
            with patch.object(mcv2_tools, 'differential_build_archives', return_value={'tree-0000': [b'MCV1']}),\
                    patch.object(mcv2_tools.subprocess, 'run', return_value=SimpleNamespace(returncode=2, stderr='crash')),\
                    redirect_stderr(StringIO()):
                self.assertEqual(2, mcv2_tools.differential_run(arguments))


if __name__ == '__main__':
    unittest.main()
