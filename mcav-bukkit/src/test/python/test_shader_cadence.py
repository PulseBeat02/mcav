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

import argparse
import sys
import unittest
from contextlib import redirect_stderr
from io import StringIO
from types import SimpleNamespace
from typing import Any
from unittest.mock import patch

import numpy

import mcv2_tools

PROGRAM: str = "mcv2_tools.py"

WINDOW: tuple[int, int] = (129, 73)
VIDEO_WIDTH: int = 15
VIDEO_HEIGHT: int = 9

MIDDLE_PIXEL: tuple[int, int] = (36, 64)
MIDDLE_TEXEL: tuple[int, int] = (4, 7)

FIRST_PAGE: bytes = b"first"
SECOND_PAGE: bytes = b"second"

WHITE: tuple[int, int, int] = (255, 255, 255)

LABEL: str = "frame 1"


class ShaderCadenceTest(unittest.TestCase):
    def setUp(self) -> None:
        window: Any = patch.object(mcv2_tools, "shader_check_screen", WINDOW)
        window.start()
        self.addCleanup(window.stop)

        self.last: numpy.ndarray = numpy.zeros((VIDEO_HEIGHT, VIDEO_WIDTH, mcv2_tools.CHANNELS), numpy.uint8)
        self.current: numpy.ndarray = self.last.copy()
        self.current[MIDDLE_TEXEL] = WHITE

    def test_should_upload_a_whole_frame_before_its_first_render_by_default(self) -> None:
        schedule: list[tuple[list[bytes], bool]] = mcv2_tools.shader_check_schedule([FIRST_PAGE, SECOND_PAGE], False, 3)
        self.assertEqual([([FIRST_PAGE, SECOND_PAGE], True), ([], False), ([], False)], schedule)

    def test_should_upload_one_page_before_its_renders_and_complete_the_frame_with_the_last_page(self) -> None:
        schedule: list[tuple[list[bytes], bool]] = mcv2_tools.shader_check_schedule([FIRST_PAGE, SECOND_PAGE], True, 2)
        self.assertEqual([([FIRST_PAGE], False), ([], False), ([SECOND_PAGE], True), ([], False)], schedule)

    def test_should_draw_the_middle_of_the_picture_in_the_middle_of_the_window(self) -> None:
        positions: tuple[numpy.ndarray, ...]
        texels: tuple[numpy.ndarray, ...]
        positions, texels = mcv2_tools.shader_check_wall_texels(VIDEO_WIDTH, VIDEO_HEIGHT)
        pixels: list[tuple[int, int]] = list(zip(positions[0].tolist(), positions[1].tolist()))
        middle: int = pixels.index(MIDDLE_PIXEL)
        texel: tuple[int, int] = (int(texels[0][middle]), int(texels[1][middle]))
        self.assertEqual(MIDDLE_TEXEL, texel)

    def test_should_leave_the_pixels_beside_the_wall_out(self) -> None:
        positions: tuple[numpy.ndarray, ...]
        positions, _ = mcv2_tools.shader_check_wall_texels(VIDEO_WIDTH, VIDEO_HEIGHT)
        pixels: set[tuple[int, int]] = set(zip(positions[0].tolist(), positions[1].tolist()))
        self.assertNotIn((0, 0), pixels)

    def test_should_accept_a_wall_that_shows_the_current_picture(self) -> None:
        check: mcv2_tools.ShaderStreamCheck = self.create_check(self.current)
        check.check_wall(LABEL)
        self.assertEqual(1, check.walls)
        self.assertEqual(0, check.walls_differ)

    def test_should_count_a_wall_that_still_shows_the_last_picture(self) -> None:
        check: mcv2_tools.ShaderStreamCheck = self.create_check(self.last)
        check.check_wall(LABEL)
        self.assertEqual(1, check.walls_differ)

    def test_should_refuse_a_check_without_renders(self) -> None:
        arguments: list[str] = [PROGRAM, "shader_check", "stream.mcs", "--renders-per-frame", "0"]
        with patch.object(sys, "argv", arguments), redirect_stderr(StringIO()), self.assertRaises(SystemExit) as status:
            mcv2_tools.main()
        self.assertEqual(2, status.exception.code)

    def create_check(self, drawn: numpy.ndarray) -> mcv2_tools.ShaderStreamCheck:
        positions: tuple[numpy.ndarray, ...]
        texels: tuple[numpy.ndarray, ...]
        positions, texels = mcv2_tools.shader_check_wall_texels(VIDEO_WIDTH, VIDEO_HEIGHT)
        screen: numpy.ndarray = numpy.zeros((WINDOW[1], WINDOW[0], mcv2_tools.RGBA_CHANNELS), numpy.uint8)
        screen[positions[0], positions[1], : mcv2_tools.CHANNELS] = drawn[texels]
        check: mcv2_tools.ShaderStreamCheck = mcv2_tools.ShaderStreamCheck(None, argparse.Namespace())
        check.text = SimpleNamespace()
        check.wall = (positions, texels)
        check.chain = SimpleNamespace(main=SimpleNamespace(read=screen.tobytes))
        check.shown = self.current
        return check


if __name__ == "__main__":
    unittest.main()
