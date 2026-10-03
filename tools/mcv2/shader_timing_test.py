"""Checks the exit codes of shader_timing.py without a GPU: its main() runs on a fake GL context, with a fake post chain
that decodes as each test says, so what the run counts as a failure is tested on any machine.

    python tools/mcv2/shader_timing_test.py

Needs numpy, as shader_timing does; moderngl and the reference's make_pages are replaced.
"""

import struct
import sys
import types
import unittest
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import shader_timing  # noqa: E402


def frame(keyframe):
    """The header of a 64x32 frame, which is all shader_timing reads of a frame itself."""
    data = bytearray(12)
    struct.pack_into("<I", data, 4, 1 << 16 if keyframe else 0)
    struct.pack_into("<HH", data, 8, 64, 32)
    return bytes(data)


class FakeChain:
    """A post chain that decodes every frame once, to a picture of its own, except where a test says otherwise. The
    sets hold (round, frame) pairs."""

    missed = frozenset()
    again = frozenset()
    changed = frozenset()

    def __init__(self, context, width, height, slots):
        self.round = -1
        self.index = 0
        self.first = True

    def reset(self):
        self.round += 1
        self.index = 0
        self.first = True

    def show(self, pages):
        pass

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


def run(*options, missed=(), again=(), changed=()):
    """Runs main() on a stream of a keyframe and a P frame, and returns its exit code; 0 when it returns."""
    context = types.SimpleNamespace(info={"GL_RENDERER": "fake", "GL_VERSION": "3.3"})
    moderngl = types.ModuleType("moderngl")
    moderngl.create_standalone_context = lambda **_: context
    transport = types.ModuleType("mcvideo.transport")
    transport.make_pages = lambda data, stream_id, rows: [data]
    reference = types.ModuleType("mcvideo")
    reference.transport = transport
    modules = {"moderngl": moderngl, "mcvideo": reference, "mcvideo.transport": transport}
    chain = type("Chain", (FakeChain,), dict(missed=frozenset(missed), again=frozenset(again), changed=frozenset(changed)))
    argv = ["shader_timing.py", "stream.mcs", *options]
    with mock.patch.dict(sys.modules, modules), mock.patch.object(sys, "argv", argv), \
            mock.patch.object(shader_timing, "TimedChain", chain), \
            mock.patch.object(shader_timing.shader_check, "frames", lambda stream: [frame(True), frame(False)]), \
            redirect_stdout(StringIO()), redirect_stderr(StringIO()):
        try:
            shader_timing.main()
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


if __name__ == "__main__":
    unittest.main()
