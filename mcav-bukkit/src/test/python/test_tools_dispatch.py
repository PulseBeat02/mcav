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

"""The consolidated command must preserve a failed strip check's process status."""

import json
import sys
import unittest
from contextlib import redirect_stdout
from io import StringIO
from types import SimpleNamespace
from unittest.mock import patch

import numpy

import mcv2_tools


class StripCommandTest(unittest.TestCase):
    def run_command(self, damaged):
        def frame(context, screen, spirv):
            scene = numpy.zeros((screen[1], screen[0], 4), numpy.uint8)
            after = scene.copy()
            if damaged and screen == (160, 90):
                after[0, 0, 0] = 1
            return scene, after, False

        context = SimpleNamespace(info={"GL_RENDERER": "fake"})
        moderngl = SimpleNamespace(create_standalone_context=lambda **options: context)
        output = StringIO()
        with patch.dict(sys.modules, {"moderngl": moderngl}), \
                patch.object(sys, "argv", ["mcv2_tools.py", "strip_fit_check", "--backend", "egl"]), \
                patch.object(mcv2_tools, "strip_fit_check_frame", side_effect=frame), redirect_stdout(output):
            status = mcv2_tools.main()
        return status, json.loads(output.getvalue().splitlines()[-1])

    def test_changed_scene_pixel_fails_the_subcommand(self):
        status, result = self.run_command(True)
        self.assertEqual(1, status)
        self.assertEqual(["160x90, where the strip does not fit: 1 pixels of the scene changed"], result["failures"])
        self.assertEqual(6, result["checks"])

    def test_unchanged_scene_passes_the_subcommand(self):
        status, result = self.run_command(False)
        self.assertEqual(0, status)
        self.assertEqual([], result["failures"])
        self.assertEqual(6, result["checks"])


if __name__ == "__main__":
    unittest.main()
