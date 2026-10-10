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

"""The subcommands of mcv2_tools: a usage naming them, their exit statuses and figures equal to the committed ones."""

import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import numpy

import mcv2_tools


class HelpCommandTest(unittest.TestCase):
    def test_usage_names_each_subcommand_and_describes_its_exit_status(self):
        commands = (
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
            "strip_fit_check",
            "latency",
            "charts",
            "samples",
        )
        for command in commands:
            with self.subTest(command=command):
                output = StringIO()
                with patch.object(sys, "argv", ["mcv2_tools.py", command, "--help"]), redirect_stdout(output):
                    with self.assertRaises(SystemExit) as status:
                        mcv2_tools.main()
                self.assertEqual(0, status.exception.code)
                self.assertIn(f"usage: mcv2_tools.py {command} ", output.getvalue())
                self.assertIn("Exit 1", output.getvalue())
                self.assertNotIn(mcv2_tools.__doc__, output.getvalue())


class StripCommandTest(unittest.TestCase):
    def run_command(self, damaged):
        def frame(context, screen, spirv, screen_index=0):
            scene = numpy.zeros((screen[1], screen[0], 4), numpy.uint8)
            after = scene.copy()
            if (screen, screen_index) == damaged:
                after[0, 0, 0] = 1
            return scene, after, False

        context = SimpleNamespace(info={"GL_RENDERER": "fake"})
        moderngl = SimpleNamespace(create_standalone_context=lambda **options: context)
        output = StringIO()
        with (
            patch.dict(sys.modules, {"moderngl": moderngl}),
            patch.object(sys, "argv", ["mcv2_tools.py", "strip_fit_check", "--backend", "egl"]),
            patch.object(mcv2_tools, "strip_fit_check_frame", side_effect=frame),
            redirect_stdout(output),
        ):
            status = mcv2_tools.main()
        return status, json.loads(output.getvalue().splitlines()[-1])

    def test_changed_scene_pixel_fails_the_subcommand(self):
        status, result = self.run_command(((160, 90), 0))
        self.assertEqual(1, status)
        self.assertEqual(
            ["160x90, where the strip does not fit: 1 pixels of the scene changed"], result["failures"]
        )
        self.assertEqual(7, result["checks"])

    def test_second_screen_changing_the_first_screens_pixels_fails_the_subcommand(self):
        status, result = self.run_command(((854, 480), 1))
        self.assertEqual(1, status)
        self.assertEqual(
            ["854x480, second screen: the screen pass changed 1 pixels the first screen's pass drew"],
            result["failures"],
        )
        self.assertEqual(7, result["checks"])

    def test_unchanged_scene_passes_the_subcommand(self):
        status, result = self.run_command(None)
        self.assertEqual(0, status)
        self.assertEqual([], result["failures"])
        self.assertEqual(7, result["checks"])


class ChartCommandTest(unittest.TestCase):
    def test_chart_command_reproduces_the_committed_pixels(self):
        try:
            import matplotlib
            from matplotlib import font_manager
        except ImportError:
            self.skipTest(
                "Chart pixels require matplotlib 3.11.2 and the resolved Liberation Sans font; matplotlib is unavailable"
            )
        font_path = font_manager.findfont(font_manager.FontProperties(family=mcv2_tools.charts_FONT))
        font = font_manager.FontProperties(fname=font_path).get_name()
        if matplotlib.__version__ != "3.11.2" or font != "Liberation Sans":
            self.skipTest(
                f"Chart pixels require matplotlib 3.11.2 and the resolved Liberation Sans font; "
                f"found matplotlib {matplotlib.__version__} and {font}"
            )
        from PIL import Image

        root = Path(__file__).resolve().parents[4]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with (
                patch.object(sys, "argv", ["mcv2_tools.py", "charts"]),
                patch.object(mcv2_tools, "IMAGES", output),
            ):
                mcv2_tools.main()
            for name in ("codecs", "features"):
                with (
                    self.subTest(figure=name),
                    Image.open(root / "mcav-docs/images/mcv2" / f"{name}.png") as expected,
                    Image.open(output / f"{name}.png") as actual,
                ):
                    numpy.testing.assert_array_equal(numpy.array(expected), numpy.array(actual))


if __name__ == "__main__":
    unittest.main()
