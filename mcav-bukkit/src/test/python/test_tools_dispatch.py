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

import functools
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from types import SimpleNamespace
from typing import Any
from unittest.mock import patch

import numpy

import mcv2_tools

PROGRAM: str = "mcv2_tools.py"
STRIP_FIT_CHECK: str = "strip_fit_check"
CHARTS: str = "charts"

RGBA_CHANNELS: int = 4

FAILURES_KEY: str = "failures"
CHECKS_KEY: str = "checks"
CHECK_COUNT: int = 7

MATPLOTLIB_VERSION: str = "3.11.2"
CHART_FONT: str = "Liberation Sans"
COMMITTED_CHARTS: Path = Path(__file__).resolve().parents[4] / "mcav-docs/images/mcv2"
CHART_SUFFIX: str = ".png"


class HelpCommandTest(unittest.TestCase):
    def test_should_name_each_subcommand_and_describe_its_exit_status_in_the_usage(self) -> None:
        commands: tuple[str, str, str, str, str, str, str, str, str, str, str, str, str, str, str] = (
            "bd_rate",
            "codec_curves",
            "counter_video",
            "capture_check",
            "edge_streams",
            "fixtures",
            "differential",
            "rate_quality",
            "shader_check",
            "shader_timing",
            "strip_check",
            STRIP_FIT_CHECK,
            "latency",
            CHARTS,
            "samples",
        )
        for command in commands:
            with self.subTest(command=command):
                code, usage = self.run_help(command)
                self.assertEqual(0, code)

                self.assertIn(f"usage: {PROGRAM} {command} ", usage)
                self.assertIn("Exit 1", usage)

    def run_help(self, command: str) -> tuple[str | int | None, str]:
        output: StringIO = StringIO()
        with (
            patch.object(sys, "argv", [PROGRAM, command, "--help"]),
            redirect_stdout(output),
            self.assertRaises(SystemExit) as status,
        ):
            mcv2_tools.main()

        return status.exception.code, output.getvalue()


class StripCommandTest(unittest.TestCase):
    def test_should_fail_the_subcommand_when_a_scene_pixel_changes(self) -> None:
        status, result = self.run_command(((160, 90), 0))
        self.assertEqual(1, status)

        failures: Any = result[FAILURES_KEY]
        self.assertEqual(
            ["160x90, where the strip does not fit: 1 pixels of the scene changed"], failures
        )

        checks: Any = result[CHECKS_KEY]
        self.assertEqual(CHECK_COUNT, checks)

    def test_should_fail_the_subcommand_when_the_second_screen_changes_the_pixels_of_the_first(self) -> None:
        status, result = self.run_command(((854, 480), 1))
        self.assertEqual(1, status)

        failures: Any = result[FAILURES_KEY]
        self.assertEqual(
            ["854x480, second screen: the screen pass changed 1 pixels the first screen's pass drew"],
            failures,
        )

        checks: Any = result[CHECKS_KEY]
        self.assertEqual(CHECK_COUNT, checks)

    def test_should_pass_the_subcommand_when_the_scene_is_unchanged(self) -> None:
        status, result = self.run_command(None)
        self.assertEqual(0, status)

        failures: Any = result[FAILURES_KEY]
        self.assertEqual([], failures)

        checks: Any = result[CHECKS_KEY]
        self.assertEqual(CHECK_COUNT, checks)

    def run_command(self, damaged: tuple[tuple[int, int], int] | None) -> tuple[int | None, Any]:
        context: SimpleNamespace = SimpleNamespace(info={"GL_RENDERER": "fake"})
        moderngl: SimpleNamespace = SimpleNamespace(create_standalone_context=lambda **options: context)
        frame: functools.partial[tuple[numpy.ndarray, numpy.ndarray, bool]] = functools.partial(fake_strip_frame, damaged)
        output: StringIO = StringIO()
        with (
            patch.dict(sys.modules, {"moderngl": moderngl}),
            patch.object(sys, "argv", [PROGRAM, STRIP_FIT_CHECK, "--backend", "egl"]),
            patch.object(mcv2_tools, "strip_fit_check_frame", side_effect=frame),
            redirect_stdout(output),
        ):
            status: int | None = mcv2_tools.main()

        result: Any = json.loads(output.getvalue().splitlines()[-1])
        return status, result


class ChartCommandTest(unittest.TestCase):
    def test_should_reproduce_the_committed_chart_pixels(self) -> None:
        self.skip_without_the_pinned_chart_tools()
        from PIL import Image

        output: Path = Path(self.enterContext(tempfile.TemporaryDirectory()))
        with patch.object(sys, "argv", [PROGRAM, CHARTS]), patch.object(mcv2_tools, "IMAGES", output):
            mcv2_tools.main()

        for name in ("codecs", "features"):
            with (
                self.subTest(figure=name),
                Image.open(COMMITTED_CHARTS / (name + CHART_SUFFIX)) as expected,
                Image.open(output / (name + CHART_SUFFIX)) as actual,
            ):
                pixels: numpy.ndarray = numpy.array(actual)
                numpy.testing.assert_array_equal(numpy.array(expected), pixels)

    def skip_without_the_pinned_chart_tools(self) -> None:
        try:
            import matplotlib
            from matplotlib import font_manager
        except ImportError:
            message: str = f"Matplotlib is missing, but the chart pixels need matplotlib {MATPLOTLIB_VERSION} and the {CHART_FONT} font!"
            self.skipTest(message)

        font_path: str = font_manager.findfont(font_manager.FontProperties(family=mcv2_tools.CHARTS_FONT))
        font: str = font_manager.FontProperties(fname=font_path).get_name()
        if matplotlib.__version__ != MATPLOTLIB_VERSION or font != CHART_FONT:
            version: str = matplotlib.__version__
            needed: str = f"matplotlib {MATPLOTLIB_VERSION} and {CHART_FONT}"
            message = f"Found matplotlib {version} and font {font}, but the chart pixels need {needed}!"
            self.skipTest(message)


def fake_strip_frame(
    damaged: tuple[tuple[int, int], int] | None, context: SimpleNamespace, screen: tuple[int, int], spirv: None, screen_index: int = 0
) -> tuple[numpy.ndarray, numpy.ndarray, bool]:
    scene: numpy.ndarray = numpy.zeros((screen[1], screen[0], RGBA_CHANNELS), numpy.uint8)
    after: numpy.ndarray = scene.copy()
    if (screen, screen_index) == damaged:
        after[0, 0, 0] = 1

    return scene, after, False


if __name__ == "__main__":
    unittest.main()
