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
from unittest import mock

import mcv2_tools


def frame(keyframe, version=3):
    """The header of a 64x32 frame, which is all shader_timing reads of a frame itself."""
    if version == 3:
        return struct.pack("<4sIHHII", b"MCV2", 3, 64, 32, 1 if keyframe else 2, 1)
    data = bytearray(32)
    data[:4] = b"MCV2"
    data[4] = version
    data[6] = int(keyframe)
    struct.pack_into("<HH", data, 8, 64, 32)
    return bytes(data)


class FakeChain:
    """A post chain that decodes every frame once, to a picture of its own, except where a test says otherwise. The
    sets hold (round, frame) pairs."""

    missed = frozenset()
    again = frozenset()
    changed = frozenset()
    received = None

    def __init__(self, context, width, height, slots):
        self.round = -1
        self.index = 0
        self.first = True

    def reset(self):
        self.round += 1
        self.index = 0
        self.first = True

    def show(self, pages):
        if self.received is not None:
            self.received.append(pages)

    def timed_frame(self, repeats):
        # each frame is shown twice: with its new pages, which decode it, then with the same pages again
        key = (self.round, self.index)
        times = {"mcv2_decode": 1.0, "mcv2_screen": 2.0}
        if self.first:
            self.first = False
            return key not in self.missed, times
        self.first = True
        return key in self.again, times

    def target(self, name):
        key = (self.round, self.index)
        self.index += 1
        picture = b"another picture" if key in self.changed else b"picture %d" % key[1]
        return types.SimpleNamespace(read=lambda: picture)


def run(*options, missed=(), again=(), changed=(), version=3, received=None):
    """Runs main() on a stream of a keyframe and a P frame, and returns its exit code; 0 when it returns."""
    context = types.SimpleNamespace(info={"GL_RENDERER": "fake", "GL_VERSION": "3.3"})
    moderngl = types.ModuleType("moderngl")
    moderngl.create_standalone_context = lambda **_: context
    chain = type(
        "Chain",
        (FakeChain,),
        dict(missed=frozenset(missed), again=frozenset(again), changed=frozenset(changed), received=received),
    )
    argv = ["mcv2_tools.py shader_timing", "stream.mcs", *options]
    with (
        mock.patch.dict(sys.modules, {"moderngl": moderngl}),
        mock.patch.object(sys, "argv", argv),
        mock.patch.object(mcv2_tools, "make_pages", lambda data, stream_id, symbol_bits: [data]),
        mock.patch.object(mcv2_tools, "TimedShaderChain", chain),
        mock.patch.object(
            mcv2_tools, "read_archive", lambda stream: [frame(True, version), frame(False, version)]
        ),
        redirect_stdout(StringIO()),
        redirect_stderr(StringIO()),
    ):
        try:
            mcv2_tools.shader_timing_main()
        except SystemExit as exit:
            return exit.code
    return 0


class ExitCodeTest(unittest.TestCase):
    def test_a_run_whose_frames_all_decode_once_to_the_same_pictures_passes(self):
        self.assertEqual(0, run())

    def test_a_frame_its_pages_did_not_decode_fails_the_run(self):
        self.assertEqual(1, run(missed={(1, 1)}))

    def test_a_frame_decoded_again_from_the_same_pages_fails_the_run(self):
        self.assertEqual(1, run(again={(0, 0)}))

    def test_another_picture_in_a_later_round_fails_the_run(self):
        self.assertEqual(1, run(changed={(2, 1)}))

    def test_a_run_that_would_time_nothing_is_refused(self):
        # the first round only warms the GPU up
        for options in (("--rounds", "1"), ("--repeats", "0"), ("--slots", "0")):
            with self.subTest(options=options):
                self.assertEqual(2, run(*options))


class TimerAccountingTest(unittest.TestCase):
    def test_each_copy_is_counted_and_vanilla_outline_blit_is_not_timed(self):
        class Query:
            elapsed = 2_000_000

            def __enter__(self):
                return self

            def __exit__(self, *unused):
                pass

        chain = object.__new__(mcv2_tools.TimedShaderChain)
        chain.context = types.SimpleNamespace(query=lambda **_: Query())
        chain.queries = {}
        chain.warm = lambda: None
        chain.steps = lambda: [
            ("mcv2_copy", "copy", {}, "mcav:mcv2_previous_0"),
            ("mcv2_copy", "copy", {}, "mcav:mcv2_state_0"),
            ("blit swap -> entity_outline", "vanilla", {}, "minecraft:entity_outline"),
        ]
        drawn = []
        chain.draw = lambda program, inputs, output: drawn.append(output)
        chain.target = lambda name: types.SimpleNamespace(read=lambda: b"\x01\x00\x00\x00")
        decoded, times = chain.timed_frame(5)
        self.assertTrue(decoded)
        self.assertEqual({"mcv2_copy -> previous_0": 2.0, "mcv2_copy -> state_0": 2.0}, times)
        self.assertEqual(4.0, sum(times.values()))
        self.assertEqual(["mcav:mcv2_previous_0", "mcav:mcv2_state_0", "minecraft:entity_outline"], drawn)

    def test_version_three_ids_classify_keyframes_and_p_frames_separately(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "timings.json"
            self.assertEqual(0, run("--json", str(path)))
            stream = json.loads(path.read_text())["streams"]["stream.mcs"]
            self.assertEqual(2, stream["new_keyframe"]["total"]["n"])
            self.assertEqual(2, stream["new_p"]["total"]["n"])
            self.assertEqual(4, stream["idle"]["total"]["n"])

    def test_archived_version_two_baseline_flags_still_classify_frames(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "baseline.json"
            self.assertEqual(0, run("--json", str(path), version=2))
            stream = json.loads(path.read_text())["streams"]["stream.mcs"]
            self.assertEqual(2, stream["new_keyframe"]["total"]["n"])
            self.assertEqual(2, stream["new_p"]["total"]["n"])

    def test_reference_option_imports_transport_from_the_archived_package_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            package = Path(directory) / "mcvideo"
            package.mkdir()
            license_header = Path(__file__).read_text().split('"""', 1)[0]
            (package / "__init__.py").write_text(license_header)
            (package / "transport.py").write_text(
                license_header + "def make_pages(data, stream_id, symbol_bits):\n"
                "    return [b'archived-v2:' + bytes((stream_id, symbol_bits)) + data]\n"
            )
            received = []
            with mock.patch.object(sys, "path", sys.path.copy()), mock.patch.dict(sys.modules):
                sys.modules.pop("mcvideo", None)
                sys.modules.pop("mcvideo.transport", None)
                self.assertEqual(0, run("--reference", directory, version=2, received=received))
            expected = [
                [b"archived-v2:\x07\x06" + frame(keyframe, 2)] for keyframe in (True, True, False, False)
            ]
            self.assertEqual(expected * 3, received)


if __name__ == "__main__":
    unittest.main()
