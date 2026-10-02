"""Resuming codec_curves.py into an earlier output: python -m unittest docs/mcv2/measure/test_codec_curves.py (numpy)."""

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import codec_curves  # noqa: E402

DOCUMENTED = Path(__file__).resolve().parents[1] / "data" / "codec_curves.json"


class ResumeTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.out = os.path.join(self.folder.name, "codec_curves.json")

    def tearDown(self):
        self.folder.cleanup()

    def write(self, measured):
        with open(self.out, "w") as out:
            json.dump(measured, out)

    def test_resumes_the_documented_output(self):
        documented = json.loads(DOCUMENTED.read_text())
        self.write(documented)
        identity = documented["sources"]["proxy30"]
        measured = codec_curves.resume(self.out, "proxy30", dict(identity))
        self.assertEqual(documented["codecs"], measured["codecs"])
        self.assertEqual(identity, measured["sources"]["proxy30"])

    def test_refuses_a_name_measured_from_other_content(self):
        documented = json.loads(DOCUMENTED.read_text())
        self.write(documented)
        other = dict(documented["sources"]["proxy30"], sha256="0" * 64)
        with self.assertRaisesRegex(ValueError, "proxy30 already has measurements of other content"):
            codec_curves.resume(self.out, "proxy30", other)

    def test_reads_the_points_of_the_first_runs(self):
        self.write({"sources": {}, "points": [{"source": "a", "codec": "x264", "quality": 20}]})
        measured = codec_curves.resume(self.out, "a", {"sha256": "1" * 64})
        self.assertEqual([{"source": "a", "codec": "x264", "quality": 20}], measured["codecs"])
        self.assertNotIn("points", measured)

    def test_refuses_an_output_with_both_datasets(self):
        self.write({"sources": {}, "points": [], "codecs": []})
        with self.assertRaisesRegex(ValueError, "both points and codecs"):
            codec_curves.resume(self.out, "a", {})

    def test_starts_an_output_that_does_not_exist(self):
        measured = codec_curves.resume(self.out, "a", {"sha256": "2" * 64})
        self.assertEqual({"sources": {"a": {"sha256": "2" * 64}}, "codecs": []}, measured)


if __name__ == "__main__":
    unittest.main()
