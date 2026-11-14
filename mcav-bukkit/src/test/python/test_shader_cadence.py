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

import mcv2_reference
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

MERGE_EVERY: int = 3
TWO_UNSEEN: int = 2
P_FRAME_ORDINALS: range = range(1, 8)

SLOTS: int = 8
MERGED_STREAM: list[bytes] = [
    mcv2_reference.pack_frame(VIDEO_WIDTH, VIDEO_HEIGHT, frame_id, max(frame_id - 1, 1), {}) for frame_id in range(1, 7)
]
AFTER_UNSEEN_FRAME: int = 4
RENDER_AFTER_UNSEEN: int = 3


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

    def test_should_leave_every_third_p_frame_unseen(self) -> None:
        unseen: list[int] = [ordinal for ordinal in P_FRAME_ORDINALS if mcv2_tools.shader_check_is_unseen(ordinal, MERGE_EVERY, 1)]
        self.assertEqual([3, 6], unseen)

    def test_should_leave_two_p_frames_in_a_row_unseen_from_every_third(self) -> None:
        unseen: list[int] = [
            ordinal for ordinal in P_FRAME_ORDINALS if mcv2_tools.shader_check_is_unseen(ordinal, MERGE_EVERY, TWO_UNSEEN)
        ]
        self.assertEqual([3, 4, 6, 7], unseen)

    def test_should_leave_no_frame_unseen_without_merges(self) -> None:
        unseen: list[int] = [ordinal for ordinal in P_FRAME_ORDINALS if mcv2_tools.shader_check_is_unseen(ordinal, 0, 1)]
        self.assertEqual([], unseen)

    def test_should_render_the_frame_after_an_unseen_one_over_its_pages(self) -> None:
        chain: FakeChain = FakeChain([True, True, True, True, True])
        check: mcv2_tools.ShaderStreamCheck = self.create_merging_check(chain)
        for index, frame in enumerate(MERGED_STREAM):
            check.check_frame(index, frame)

        rendered: list[bytes] = chain.rendered[RENDER_AFTER_UNSEEN]
        expected: list[bytes] = mcv2_tools.make_pages(MERGED_STREAM[AFTER_UNSEEN_FRAME], mcv2_tools.SHADER_CHECK_STREAM_ID)
        self.assertEqual(expected, rendered)
        self.assertEqual(5, check.renders)
        self.assertEqual(1, check.unseen)
        self.assertEqual(0, check.wrong)

    def test_should_count_every_p_frame_the_chain_skips_after_an_unseen_frame(self) -> None:
        chain: FakeChain = FakeChain([True, True, True, False, False])
        check: mcv2_tools.ShaderStreamCheck = self.create_merging_check(chain)
        for index, frame in enumerate(MERGED_STREAM):
            check.check_frame(index, frame)

        self.assertEqual(2, check.wrong)
        self.assertEqual(3, check.decoded)

    def test_should_expect_the_chain_to_skip_a_p_frame_whose_reference_was_dropped(self) -> None:
        chain: FakeChain = FakeChain([True, False])
        arguments: argparse.Namespace = argparse.Namespace(
            drop=2, slots=SLOTS, split_uploads=False, renders_per_frame=1, merge_uploads=0, unseen_frames=1
        )
        check: mcv2_tools.ShaderStreamCheck = mcv2_tools.ShaderStreamCheck(None, arguments)
        check.chain = chain
        for index, frame in enumerate(MERGED_STREAM[:3]):
            check.check_frame(index, frame)

        self.assertEqual(2, check.renders)
        self.assertEqual(1, check.skipped)
        self.assertEqual(0, check.wrong)

    def test_should_expect_the_chain_to_decode_a_second_frame_only_when_the_client_can(self) -> None:
        ahead: int = 1 + mcv2_reference.ID_HALF_RANGE
        far_keyframe: bytes = mcv2_reference.pack_frame(VIDEO_WIDTH, VIDEO_HEIGHT, ahead, ahead, {})
        far_p_frame: bytes = mcv2_reference.pack_frame(VIDEO_WIDTH, VIDEO_HEIGHT, ahead, 1, {})
        wider_keyframe: bytes = mcv2_reference.pack_frame(VIDEO_WIDTH + 1, VIDEO_HEIGHT, 2, 2, {})
        second_frames: tuple[tuple[str, bytes, bool], ...] = (
            ("a keyframe half the id range ahead", far_keyframe, True),
            ("the first keyframe again", MERGED_STREAM[0], False),
            ("a P frame half the id range ahead", far_p_frame, False),
            ("a keyframe of another size", wider_keyframe, False),
        )
        for name, frame, decodable in second_frames:
            with self.subTest(frame=name):
                check: mcv2_tools.ShaderStreamCheck = self.create_stream_check(FakeChain([True, decodable]), SLOTS)
                check.check_frame(0, MERGED_STREAM[0])
                check.check_frame(1, frame)

                self.assertEqual(2, check.renders)
                self.assertEqual(0, check.wrong)

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

    def test_should_count_a_decoded_picture_that_differs_from_the_reference_as_wrong(self) -> None:
        check: mcv2_tools.ShaderStreamCheck = mcv2_tools.ShaderStreamCheck(None, argparse.Namespace())
        check.check_picture(LABEL, self.current, self.last)

        self.assertEqual(1, check.wrong)

    def test_should_count_skipped_renders_and_only_a_skip_that_changed_the_shown_picture_as_wrong(self) -> None:
        check: mcv2_tools.ShaderStreamCheck = mcv2_tools.ShaderStreamCheck(None, argparse.Namespace())
        check.check_skipped(LABEL, self.current)
        check.shown = self.last
        check.check_skipped(LABEL, self.last)
        self.assertEqual(0, check.wrong)

        check.check_skipped(LABEL, self.current)
        self.assertEqual(1, check.wrong)
        self.assertEqual(3, check.skipped)

    def test_should_refuse_a_check_without_renders(self) -> None:
        arguments: list[str] = [PROGRAM, "shader_check", "stream.mcs", "--renders-per-frame", "0"]
        with patch.object(sys, "argv", arguments), redirect_stderr(StringIO()), self.assertRaises(SystemExit) as status:
            mcv2_tools.main()
        self.assertEqual(2, status.exception.code)

    def test_should_refuse_merges_without_a_rendered_p_frame_between_them(self) -> None:
        arguments: list[str] = [PROGRAM, "shader_check", "stream.mcs", "--merge-uploads", "2", "--unseen-frames", "2"]
        with patch.object(sys, "argv", arguments), redirect_stderr(StringIO()), self.assertRaises(SystemExit) as status:
            mcv2_tools.main()
        self.assertEqual(2, status.exception.code)

    def test_should_start_a_check_with_the_least_renders_unseen_frames_and_merges(self) -> None:
        for options in ((), ("--merge-uploads", "2")):
            with self.subTest(options=options), self.assertRaises(ShaderCheckStarted):
                self.start_check(*options)

    def start_check(self, *options: str) -> None:
        arguments: list[str] = [PROGRAM, "shader_check", "stream.mcs", *options]
        with (
            patch.object(sys, "argv", arguments),
            patch.object(mcv2_tools, "shader_check_screen", mcv2_tools.shader_check_screen),
            patch.object(mcv2_tools, "shader_check_create_context", side_effect=ShaderCheckStarted),
            redirect_stderr(StringIO()),
        ):
            mcv2_tools.main()

    def create_merging_check(self, chain: "FakeChain") -> mcv2_tools.ShaderStreamCheck:
        arguments: argparse.Namespace = argparse.Namespace(
            drop=0, slots=SLOTS, split_uploads=False, renders_per_frame=1, merge_uploads=MERGE_EVERY, unseen_frames=1
        )
        check: mcv2_tools.ShaderStreamCheck = mcv2_tools.ShaderStreamCheck(None, arguments)
        check.chain = chain
        return check

    def create_stream_check(self, chain: "FakeChain", slots: int) -> mcv2_tools.ShaderStreamCheck:
        arguments: argparse.Namespace = argparse.Namespace(
            drop=0, slots=slots, split_uploads=False, renders_per_frame=1, merge_uploads=0, unseen_frames=1
        )
        check: mcv2_tools.ShaderStreamCheck = mcv2_tools.ShaderStreamCheck(None, arguments)
        check.chain = chain
        return check

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


class FakeChain:
    def __init__(self, decisions: list[bool]) -> None:
        self.width: int = VIDEO_WIDTH
        self.height: int = VIDEO_HEIGHT
        self.decisions: list[bool] = decisions
        self.rendered: list[list[bytes]] = []

    def show(self, pages: list[bytes]) -> None:
        self.rendered.append(pages)

    def render_frame(self) -> tuple[bool, numpy.ndarray]:
        decoded: bool = self.decisions.pop(0)
        picture: numpy.ndarray = numpy.zeros((VIDEO_HEIGHT, VIDEO_WIDTH, mcv2_tools.CHANNELS), numpy.uint8)
        return (decoded, picture)


class ShaderCheckStarted(Exception):
    pass


if __name__ == "__main__":
    unittest.main()
