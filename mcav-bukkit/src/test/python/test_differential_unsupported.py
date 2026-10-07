"""Unsupported exemptions must name syntax the supplied frame actually contains."""

import importlib.util
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[4]
with patch.object(sys, "argv", ["differential.py", "unused"]):
    spec = importlib.util.spec_from_file_location("differential", ROOT / "tools/mcv2/differential.py")
    differential = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(differential)


class DifferentialTest(unittest.TestCase):

    def solid(self):
        return differential.pack_frame(8, 8, 0, 0, True, (0, 0), [differential.Node(2, record=b"\x11\x22\x33")])

    def report(self, frames, tokens):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            arguments = SimpleNamespace(out=output, streams=1, encoded=0, mutants=0, java="unused", classpath="unused", seed=42)
            response = SimpleNamespace(returncode=0, stderr="", stdout=str(output / "archives/tree-0000.mcs") + " " + " ".join(tokens))
            with patch.object(differential, "ARGS", arguments), patch.object(differential, "tree_archive", return_value=frames), \
                    patch.object(differential, "reference_tokens", return_value=["0" * 64] * len(frames)), \
                    patch.object(differential.subprocess, "run", return_value=response), redirect_stdout(StringIO()), \
                    self.assertRaises(SystemExit) as ended:
                differential.main()
            return ended.exception.code, json.loads((output / "summary.json").read_text())

    def test_supported_frames_cannot_be_exempted_as_unsupported(self):
        status, result = self.report([self.solid()], ["unsupported"])
        self.assertEqual(1, status)
        self.assertEqual(1, result["counts"]["disagreements"])
        self.assertEqual(0, result["counts"]["unsupported_skipped"])

    def test_a_supported_control_is_actually_compared(self):
        status, result = self.report([self.solid()], ["0" * 64])
        self.assertEqual(0, status)
        self.assertEqual(1, result["counts"]["decoded"])

    def test_reverted_palette_syntax_is_an_explicit_exemption(self):
        for mode in (differential.fmt.COARSE_PALETTE_2, differential.fmt.COARSE_PALETTE_4):
            with self.subTest(mode=mode):
                record = bytes(differential.fmt.record_size(mode, 32))
                frame = differential.pack_frame(8, 8, 0, 0, True, (0, 0), [differential.Node(mode, record=record)])
                status, result = self.report([self.solid(), frame, self.solid()], ["0" * 64, "unsupported", "reject"])
                self.assertEqual(0, status)
                self.assertEqual(1, result["counts"]["decoded"])
                self.assertEqual(2, result["counts"]["unsupported_skipped"])

    def test_legacy_magic_is_an_explicit_exemption(self):
        status, result = self.report([b"MCV1"], ["unsupported"])
        self.assertEqual(0, status)
        self.assertEqual(1, result["counts"]["unsupported_skipped"])


if __name__ == "__main__":
    unittest.main()
