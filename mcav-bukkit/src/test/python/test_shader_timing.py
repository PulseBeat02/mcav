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


"""Checks mcv2_tools shader_timing without a GPU: its main() runs on a fake GL context, with a fake post chain
that decodes as each test says, so what the run counts as a failure is tested on any machine.

    python -m unittest discover -s mcav-bukkit/src/test/python -p test_shader_timing.py

Needs numpy, as shader_timing does; moderngl and the reference's make_pages are replaced.
"""

import json
import struct
import sys
import tempfile
import types
import unittest
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
from pathlib import Path
from typing import Any
from unittest import mock

import mcv2_reference
import mcv2_tools

PROGRAM: str = "mcv2_tools.py shader_timing"
STREAM_NAME: str = "stream.mcs"
TIMINGS_NAME: str = "timings.json"
BASELINE_NAME: str = "baseline.json"
JSON_OPTION: str = "--json"

WIDTH: int = 64
HEIGHT: int = 32
KEYFRAME_ID: int = 1
PREDICTED_FRAME_ID: int = 2

VERSION_TWO: int = 2
VERSION_TWO_HEADER_BYTES: int = 32
VERSION_TWO_FLAGS_OFFSET: int = 6
DIMENSIONS = struct.Struct("<HH")

DECODE_MILLISECONDS: float = 1.0
SCREEN_MILLISECONDS: float = 2.0

MODERNGL_MODULE: str = "moderngl"
GL_VERSION: str = "3.3"

STREAMS_KEY: str = "streams"
NEW_KEYFRAME_KEY: str = "new_keyframe"
NEW_PREDICTED_KEY: str = "new_p"
TOTAL_KEY: str = "total"
COUNT_KEY: str = "n"

PACKAGE: str = "mcvideo"
PACKAGE_INIT: str = "__init__.py"
TRANSPORT_MODULE: str = "transport.py"


class ExitCodeTest(unittest.TestCase):
    def test_should_pass_a_run_whose_frames_all_decode_once_to_the_same_pictures(self) -> None:
        status: str | int | None = run()
        self.assertEqual(0, status)

    def test_should_fail_the_run_when_the_pages_of_a_frame_do_not_decode_it(self) -> None:
        status: str | int | None = run(missed={(1, 1)})
        self.assertEqual(1, status)

    def test_should_fail_the_run_when_a_frame_decodes_again_from_the_same_pages(self) -> None:
        status: str | int | None = run(again={(0, 0)})
        self.assertEqual(1, status)

    def test_should_fail_the_run_when_a_later_round_decodes_another_picture(self) -> None:
        status: str | int | None = run(changed={(2, 1)})
        self.assertEqual(1, status)

    def test_should_refuse_a_run_that_would_time_nothing(self) -> None:
        # the first round only warms the GPU up
        for options in (("--rounds", "1"), ("--repeats", "0"), ("--slots", "0")):
            with self.subTest(options=options):
                status: str | int | None = run(*options)
                self.assertEqual(2, status)


class TimerAccountingTest(unittest.TestCase):
    def test_should_count_each_copy_and_not_time_the_vanilla_outline_blit(self) -> None:
        steps: list[tuple[str, str, dict[Any, Any], str]] = [
            ("mcv2_copy", "copy", {}, "mcav:mcv2_previous_0"),
            ("mcv2_copy", "copy", {}, "mcav:mcv2_state_0"),
            ("blit swap -> entity_outline", "vanilla", {}, "minecraft:entity_outline"),
        ]
        chain: mcv2_tools.TimedShaderChain = object.__new__(mcv2_tools.TimedShaderChain)
        chain.context = types.SimpleNamespace(query=lambda **_: FakeQuery())
        chain.queries = {}
        chain.warm = lambda: None
        chain.steps = lambda: steps
        drawn: list[Any | str] = []
        chain.draw = lambda program, inputs, output: drawn.append(output)
        chain.target = lambda name: types.SimpleNamespace(read=lambda: b"\x01\x00\x00\x00")
        decoded, times = chain.timed_frame(5)
        self.assertTrue(decoded)

        self.assertEqual({"mcv2_copy -> previous_0": 2.0, "mcv2_copy -> state_0": 2.0}, times)

        total: float | int = sum(times.values())
        self.assertEqual(4.0, total)

        self.assertEqual(["mcav:mcv2_previous_0", "mcav:mcv2_state_0", "minecraft:entity_outline"], drawn)

    def test_should_classify_keyframes_and_predicted_frames_separately_by_their_version_three_ids(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path: Path = Path(directory) / TIMINGS_NAME
            status: str | int | None = run(JSON_OPTION, str(path))
            self.assertEqual(0, status)

            stream: Any = json.loads(path.read_text())[STREAMS_KEY][STREAM_NAME]
            keyframes: Any = stream[NEW_KEYFRAME_KEY][TOTAL_KEY][COUNT_KEY]
            self.assertEqual(2, keyframes)

            predicted_frames: Any = stream[NEW_PREDICTED_KEY][TOTAL_KEY][COUNT_KEY]
            self.assertEqual(2, predicted_frames)

            idle: Any = stream["idle"][TOTAL_KEY][COUNT_KEY]
            self.assertEqual(4, idle)

    def test_should_still_classify_frames_by_the_flags_of_the_archived_version_two_baseline(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path: Path = Path(directory) / BASELINE_NAME
            status: str | int | None = run(JSON_OPTION, str(path), version=VERSION_TWO)
            self.assertEqual(0, status)

            stream: Any = json.loads(path.read_text())[STREAMS_KEY][STREAM_NAME]
            keyframes: Any = stream[NEW_KEYFRAME_KEY][TOTAL_KEY][COUNT_KEY]
            self.assertEqual(2, keyframes)

            predicted_frames: Any = stream[NEW_PREDICTED_KEY][TOTAL_KEY][COUNT_KEY]
            self.assertEqual(2, predicted_frames)

    def test_should_import_the_transport_of_the_reference_option_from_the_archived_package_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            package: Path = Path(directory) / PACKAGE
            package.mkdir()
            (package / PACKAGE_INIT).write_text("")
            (package / TRANSPORT_MODULE).write_text(
                "def make_pages(data, stream_id, symbol_bits):\n"
                "    return [b'archived-v2:' + bytes((stream_id, symbol_bits)) + data]\n"
            )
            received: list[Any] = []
            with mock.patch.object(sys, "path", sys.path.copy()), mock.patch.dict(sys.modules):
                sys.modules.pop(PACKAGE, None)
                sys.modules.pop(f"{PACKAGE}.transport", None)
                status: str | int | None = run("--reference", directory, version=VERSION_TWO, received=received)
                self.assertEqual(0, status)

            keyframe_pages: list[bytes] = [b"archived-v2:\x07\x06" + keyframe(VERSION_TWO)]
            predicted_pages: list[bytes] = [b"archived-v2:\x07\x06" + predicted_frame(VERSION_TWO)]
            expected: list[list[bytes]] = [keyframe_pages, keyframe_pages, predicted_pages, predicted_pages]
            self.assertEqual(expected * 3, received)


def run(
    *options,
    missed: set[tuple[int, int]] | tuple[()] = (),
    again: set[tuple[int, int]] | tuple[()] = (),
    changed: set[tuple[int, int]] | tuple[()] = (),
    version: int = mcv2_reference.VERSION,
    received: None = None
) -> str | int | None:
    """Runs main() on a stream of a keyframe and a P frame, and returns its exit code; 0 when it returns."""
    context: types.SimpleNamespace = types.SimpleNamespace(info={"GL_RENDERER": "fake", "GL_VERSION": GL_VERSION})
    moderngl: types.ModuleType = types.ModuleType(MODERNGL_MODULE)
    moderngl.create_standalone_context = lambda **_: context
    chain: type = type(
        "Chain",
        (FakeChain,),
        dict(missed=frozenset(missed), again=frozenset(again), changed=frozenset(changed), received=received),
    )
    argv: list[Any] = [PROGRAM, STREAM_NAME, *options]
    with (
        mock.patch.dict(sys.modules, {MODERNGL_MODULE: moderngl}),
        mock.patch.object(sys, "argv", argv),
        mock.patch.object(mcv2_tools, "make_pages", lambda data, stream_id, symbol_bits: [data]),
        mock.patch.object(mcv2_tools, "TimedShaderChain", chain),
        mock.patch.object(
            mcv2_tools, "read_archive", lambda stream: [keyframe(version), predicted_frame(version)]
        ),
        redirect_stdout(StringIO()),
        redirect_stderr(StringIO()),
    ):
        try:
            mcv2_tools.shader_timing_main()
        except SystemExit as exception:
            return exception.code

    return 0


class FakeChain:
    """A post chain that decodes every frame once, to a picture of its own, except where a test says otherwise. The
    sets hold (round, frame) pairs."""

    missed: frozenset[Any] = frozenset()
    again: frozenset[Any] = frozenset()
    changed: frozenset[Any] = frozenset()
    received: None = None

    def __init__(self, context: types.SimpleNamespace, width: int, height: int, slots: int) -> None:
        self.round = -1
        self.index = 0
        self.first = True

    def reset(self) -> None:
        self.round += 1
        self.index = 0
        self.first = True

    def show(self, pages: list[bytes]) -> None:
        if self.received is not None:
            self.received.append(pages)

    def timed_frame(self, repeats: int) -> tuple[bool, dict[str, float]]:
        # each frame is shown twice: with its new pages, which decode it, then with the same pages again
        key: tuple[int, int] = (self.round, self.index)
        times: dict[str, float] = {"mcv2_decode": DECODE_MILLISECONDS, "mcv2_screen": SCREEN_MILLISECONDS}
        if self.first:
            self.first = False
            return key not in self.missed, times

        self.first = True

        return key in self.again, times

    def target(self, name: str) -> types.SimpleNamespace:
        key: tuple[int, int] = (self.round, self.index)
        self.index += 1
        picture: bytes = b"another picture" if key in self.changed else b"picture %d" % key[1]
        return types.SimpleNamespace(read=lambda: picture)


def keyframe(version: int) -> bytes:
    return frame(KEYFRAME_ID, version)


def frame(frame_id: int, version: int) -> bytes:
    """The header of a 64x32 frame, which is all shader_timing reads of a frame itself."""
    if version == mcv2_reference.VERSION:
        return mcv2_reference.HEADER.pack(mcv2_reference.MAGIC, mcv2_reference.VERSION, WIDTH, HEIGHT, frame_id, KEYFRAME_ID)

    data: bytearray = bytearray(VERSION_TWO_HEADER_BYTES)
    data[:len(mcv2_reference.MAGIC)] = mcv2_reference.MAGIC
    data[mcv2_reference.VERSION_OFFSET] = version
    data[VERSION_TWO_FLAGS_OFFSET] = int(frame_id == KEYFRAME_ID)
    DIMENSIONS.pack_into(data, mcv2_reference.WIDTH_OFFSET, WIDTH, HEIGHT)
    return bytes(data)


def predicted_frame(version: int) -> bytes:
    return frame(PREDICTED_FRAME_ID, version)


class FakeQuery:
    elapsed: int = 2_000_000

    def __enter__(self) -> "FakeQuery":
        return self

    def __exit__(self, *unused) -> None:
        pass


if __name__ == "__main__":
    unittest.main()
