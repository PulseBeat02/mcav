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

import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from unittest.mock import patch

import mcv2_tools

PROGRAM: str = "mcv2_tools.py bd_rate"

REFERENCE_FILE: str = "reference.json"
TEST_FILE: str = "test.json"

RATE_KEY: str = "map_mbps"
METRIC_KEY: str = "vmaf_mean"

BD_RATE_PERCENT_KEY: str = "bd_rate_percent"
QUALITY_LOW_KEY: str = "quality_low"
QUALITY_HIGH_KEY: str = "quality_high"

REFERENCE_CURVE: list[dict[str, float]] = [
    {RATE_KEY: 1.0, METRIC_KEY: 60.0},
    {RATE_KEY: 2.0, METRIC_KEY: 70.0},
    {RATE_KEY: 4.0, METRIC_KEY: 80.0},
    {RATE_KEY: 8.0, METRIC_KEY: 90.0},
]
DOUBLED_RATE_CURVE: list[dict[str, float]] = [
    {RATE_KEY: 4.0, METRIC_KEY: 70.0},
    {RATE_KEY: 8.0, METRIC_KEY: 80.0},
    {RATE_KEY: 16.0, METRIC_KEY: 90.0},
    {RATE_KEY: 32.0, METRIC_KEY: 100.0},
]


class BdRateTest(unittest.TestCase):
    def test_should_need_no_extra_rate_for_the_same_curve(self) -> None:
        result: dict[str, float | str] = self.run_bd_rate(REFERENCE_CURVE, REFERENCE_CURVE)

        bd_rate_percent: float | str = result[BD_RATE_PERCENT_KEY]
        self.assertEqual(0.0, bd_rate_percent)

    def test_should_measure_the_extra_rate_over_the_shared_quality_range(self) -> None:
        result: dict[str, float | str] = self.run_bd_rate(REFERENCE_CURVE, DOUBLED_RATE_CURVE)

        bd_rate_percent: float | str = result[BD_RATE_PERCENT_KEY]
        self.assertEqual(100.0, bd_rate_percent)

        quality_low: float | str = result[QUALITY_LOW_KEY]
        quality_high: float | str = result[QUALITY_HIGH_KEY]
        self.assertEqual(70.0, quality_low)
        self.assertEqual(90.0, quality_high)

    def run_bd_rate(self, reference: list[dict[str, float]], test: list[dict[str, float]]) -> dict[str, float | str]:
        output: StringIO = StringIO()
        with tempfile.TemporaryDirectory() as directory:
            reference_path: Path = Path(directory) / REFERENCE_FILE
            test_path: Path = Path(directory) / TEST_FILE
            reference_text: str = json.dumps(reference)
            test_text: str = json.dumps(test)
            reference_path.write_text(reference_text)
            test_path.write_text(test_text)
            arguments: list[str] = [PROGRAM, str(reference_path), str(test_path)]
            with patch.object(sys, "argv", arguments), redirect_stdout(output):
                mcv2_tools.bd_rate_main()

        text: str = output.getvalue()
        return json.loads(text)


if __name__ == "__main__":
    unittest.main()
