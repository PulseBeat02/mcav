"""Capture coverage distinguishes pictures from indistinguishable frame occurrences."""

import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from unittest.mock import patch

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / "tools/mcv2"))
import capture_check


class CaptureCheckTest(unittest.TestCase):

    def report(self, references, captures):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            reference = root / "reference.rgb"
            reference.write_bytes(b"".join(picture.tobytes() for picture in references))
            for index, picture in enumerate(captures):
                Image.fromarray(picture).save(root / ("capture-%02d.png" % index))
            height, width = references[0].shape[:2]
            arguments = ["capture_check.py", str(reference), str(width), str(height), str(root), "--top", "0"]
            output = StringIO()
            with patch.object(sys, "argv", arguments), redirect_stdout(output), self.assertRaises(SystemExit) as ended:
                capture_check.main()
            return ended.exception.code, json.loads(output.getvalue().splitlines()[-1]), output.getvalue()

    def test_identical_reference_frames_do_not_create_unmatchable_missing_pictures(self):
        picture = np.full((8, 8, 3), 37, np.uint8)
        status, result, text = self.report([picture, picture], [picture, picture])
        self.assertEqual(0, status)
        self.assertEqual(2, result["exact_captures"])
        self.assertEqual(1, result["pictures_seen_exactly"])
        self.assertEqual(1, result["distinct_pictures"])
        self.assertEqual(2, result["ambiguous_reference_frames"])
        self.assertEqual(0, result["frames_seen_exactly"])
        self.assertIn("indistinguishable", text)

    def test_a_missing_distinct_picture_still_fails(self):
        first = np.full((8, 8, 3), 37, np.uint8)
        second = np.full((8, 8, 3), 92, np.uint8)
        status, result, _ = self.report([first, first, second], [first, first])
        self.assertEqual(1, status)
        self.assertEqual(2, result["exact_captures"])

    def test_visible_cropping_can_make_different_frames_indistinguishable(self):
        first = np.full((16, 8, 3), 37, np.uint8)
        second = first.copy()
        second[8:] = 92
        status, result, _ = self.report([first, second], [first[:8], second[:8]])
        self.assertEqual(0, status)
        self.assertEqual(2, result["ambiguous_reference_frames"])


if __name__ == "__main__":
    unittest.main()
