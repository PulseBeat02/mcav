"""Exact event matching for the MCV2 latency report, without JFR or a client."""

import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / "tools/mcv2"))
import latency


def event(frame, arrived, sent, sent_to=1):
    return dict(frame=frame, arrived=arrived, sent=sent, sent_to=sent_to, number=7,
                keyframe=True, bytes=64, colors=64, behind=0, waiting=0, backlog=0)


class LatencyTest(unittest.TestCase):

    def report(self, later):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory, "report.json")
            arguments = ["latency.py", "unused.jfr", "unused.nut", "--json", str(output)]
            with patch.object(sys, "argv", arguments), patch.object(latency, "events", return_value=[event(0, 0, 100), later]), \
                    patch.object(latency, "captures", return_value=[(200, 7), (210, 7)]), redirect_stdout(StringIO()):
                latency.main()
            return json.loads(output.read_text())

    def test_repeated_number_uses_a_frame_sent_before_capture(self):
        result = self.report(event(1, 150, 250))
        self.assertEqual(1, result["displayed"])
        for statistic in ("mean", "p50", "p95", "max"):
            self.assertEqual(200, result["glass_to_glass_ms_" + statistic])

    def test_an_unsent_occurrence_cannot_replace_the_displayed_one(self):
        result = self.report(event(1, 150, 175, sent_to=0))
        self.assertEqual(200, result["glass_to_glass_ms_mean"])

    def test_a_later_sent_occurrence_can_be_displayed(self):
        result = self.report(event(1, 150, 175))
        self.assertEqual(50, result["glass_to_glass_ms_mean"])

    def test_a_nonfinite_send_time_cannot_be_displayed(self):
        for invalid in (float("nan"), float("inf")):
            with self.subTest(invalid=invalid):
                result = self.report(event(1, 150, invalid))
                self.assertEqual(200, result["glass_to_glass_ms_mean"])


if __name__ == "__main__":
    unittest.main()
