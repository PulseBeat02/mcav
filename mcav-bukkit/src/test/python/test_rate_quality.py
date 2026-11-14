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
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from io import StringIO
from pathlib import Path
from typing import Any
from unittest.mock import patch

import mcv2_tools

PROGRAM: str = "rate_quality"
CURVE_FILE: str = "curve.json"

OPTIONS: list[str] = ["--classpath", "lib", mcv2_tools.SOURCE_OPTION, "clip.rgb", "--lambdas", "0.5,2", mcv2_tools.FFMPEG_OPTION, "ffmpeg"]
ENCODER_ARGUMENT: str = "profile=DEFAULT"

BENCHMARK_POINT: dict[str, Any] = {"map_mbps": 1.5}
BENCHMARK_OUTPUT: str = f"Benchmark log.\n{json.dumps(BENCHMARK_POINT)}\n"

VMAF_MEAN: float = 90.0
VMAF_MINIMUM: float = 80.0


class RateQualityTest(unittest.TestCase):

    def test_should_run_the_benchmark_once_per_lambda(self) -> None:
        commands: list[Any]
        _: Any
        commands, _ = self.run_rate_quality()
        self.assertEqual(2, len(commands))
        for command, value in zip(commands, ["0.5", "2"]):
            with self.subTest(value=value):
                self.assertIn(mcv2_tools.BENCHMARK_COMMAND, command)
                self.assertIn(f"lambda={value}", command)
                self.assertEqual(ENCODER_ARGUMENT, command[-1])

    def test_should_write_one_scored_point_per_lambda(self) -> None:
        _: list[Any]
        curve: Any
        _, curve = self.run_rate_quality()
        scores: dict[str, Any] = {mcv2_tools.VMAF_MEAN_KEY: VMAF_MEAN, mcv2_tools.VMAF_MINIMUM_KEY: VMAF_MINIMUM}
        point: dict[str, Any] = {**BENCHMARK_POINT, "encoder": ENCODER_ARGUMENT, **scores}
        expected: list[dict[str, Any]] = [{**point, "lambda": 0.5}, {**point, "lambda": 2.0}]
        self.assertEqual(expected, curve)

    def run_rate_quality(self) -> tuple[list[Any], Any]:
        benchmark: subprocess.CompletedProcess[str] = subprocess.CompletedProcess([], 0, stdout=BENCHMARK_OUTPUT, stderr="")
        with tempfile.TemporaryDirectory() as directory:
            out_path: Path = Path(directory) / CURVE_FILE
            arguments: list[str] = [PROGRAM, *OPTIONS, mcv2_tools.OUTPUT_OPTION, str(out_path), "--", ENCODER_ARGUMENT]
            with (
                patch.object(sys, "argv", arguments),
                patch.object(subprocess, "run", return_value=benchmark) as run,
                patch.object(mcv2_tools, "rate_quality_vmaf", return_value=(VMAF_MEAN, VMAF_MINIMUM)),
                redirect_stderr(StringIO()),
            ):
                mcv2_tools.rate_quality_main()

            curve: Any = json.loads(out_path.read_text())

        commands: list[Any] = [call.args[0] for call in run.call_args_list]
        return commands, curve


if __name__ == "__main__":
    unittest.main()
