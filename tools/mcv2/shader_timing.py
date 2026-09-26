"""Time every pass of the MCV2 resource pack's post chain with GPU timer queries (GL_TIME_ELAPSED).

    python tools/mcv2/shader_timing.py <gpu-codec checkout> <stream.mcs> [<stream.mcs> ...]
        [--backend egl|glx] [--slots N] [--rounds R] [--repeats K] [--json OUT] [--pack DIR]

The chain is the one the client runs on every rendered frame: the pack's post chain (entity_outline.json) pass for
pass, as shader_check runs it - mcav's passes with their own shaders and Minecraft's blits as texel copies of the same
size; Minecraft's own outline passes (sobel, two box blurs, a blit), which are not the pack's, are not timed. The
screen pass draws a 6x3 screen three blocks in front of the camera, which covers about 57% of the view, with the
scene behind it, so its ray cast runs as it does while a player watches.

Each video frame is shown twice: once when its pages arrive, which decodes it, and once more with the same pages,
which is what every rendered frame without new video costs (the decode pass then copies the previous picture). The
decode pass of a new frame is drawn K more times on the same inputs (--repeats) and the mean is reported, the way
gpu-codec's harness times a draw. The whole stream is played R times (--rounds); the first round warms the GPU clocks
and is not counted. Every picture is checked against the one the first round decoded, so a timing run is also a
determinism check. Needs numpy, moderngl and the gpu-codec checkout (for its make_pages).
"""

import argparse
import json
import struct
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import shader_check  # noqa: E402

def perspective(fov_y, aspect, near, far):
    """An OpenGL projection matrix, column-major like GLSL's mat4 constructor order."""
    f = 1.0 / np.tan(np.radians(fov_y) / 2)
    m = np.zeros((4, 4), np.float32)
    m[0, 0] = f / aspect
    m[1, 1] = f
    m[2, 2] = (far + near) / (near - far)
    m[2, 3] = -1.0
    m[3, 2] = 2 * far * near / (near - far)
    return m


def descriptor_row(width):
    """The anchor descriptor the text shader writes: magic, then 28 floats, each in two pixels."""
    top_left, right, down, cells = (-3.0, 1.5, -3.0), (1.0, 0.0, 0.0), (0.0, -1.0, 0.0), (6.0, 3.0)
    floats = [*top_left, cells[0], *right, cells[1], *down, 0.0]
    floats += list(perspective(70.0, 16 / 9, 0.05, 1000.0).reshape(-1))
    row = np.zeros((width, 4), np.uint8)
    row[:, 3] = 255
    row[0, :3] = (0x4D, 0x43, 0x56)
    row[1, 0] = 0xA1
    for i, value in enumerate(floats):
        b = struct.pack("<f", value)
        row[2 + i * 2, :3] = (b[0], b[1], b[2])
        row[3 + i * 2, 0] = b[3]
    return row


class TimedChain(shader_check.Chain):
    def __init__(self, context, width, height, slots):
        super().__init__(context, width, height, slots)
        self.queries = {}

    def show(self, pages):
        super().show(pages)
        # the anchor descriptor row after the last slot, so the screen pass casts its rays
        rows = (4096 + shader_check.SCREEN[0] - 1) // shader_check.SCREEN[0]
        row = shader_check.SCREEN[1] - 1 - self.slots * rows
        self.main.write(descriptor_row(shader_check.SCREEN[0]).tobytes(), viewport=(0, row, shader_check.SCREEN[0], 1))

    def warm(self):
        """Keeps the GPU busy for a few milliseconds, untimed, as a client drawing its world would: after the harness's
        own uploads and read-backs the GPU would otherwise start the chain at an idle clock."""
        for _ in range(20):
            self.draw(self.blit, {"In": self.main}, "mcav:mcv2_screen")

    def timed_frame(self, repeats):
        """Runs the chain once, each pass inside its timer query; the decode pass is drawn `repeats` times and its
        mean kept. Returns whether the frame was decoded and the milliseconds of every pass by name."""
        self.warm()
        times = {}
        for name, program, inputs, output in self.steps():
            query = self.queries.setdefault(name, self.context.query(time=True))
            count = repeats if name == "mcv2_decode" else 1
            with query:
                for _ in range(count):
                    self.draw(program, inputs, output)
            times[name] = query.elapsed / count / 1e6
        status = np.frombuffer(self.target("status").read(), np.uint8)
        return bool(status[0]), times


def summarize(samples, names):
    """Mean, and 95th percentile, of each pass over a list of per-frame dicts."""
    out = {}
    for name in names + ("total",):
        values = np.array([s[name] for s in samples]) if samples else np.zeros(1)
        out[name] = dict(mean=float(values.mean()), p95=float(np.percentile(values, 95)), n=len(samples))
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("codec")
    parser.add_argument("streams", nargs="+")
    parser.add_argument("--backend", choices=("egl", "glx"), default=None)
    parser.add_argument("--slots", type=int, default=4)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument("--json", type=Path)
    parser.add_argument("--pack", type=Path, help="another pack source folder (default: mcav-bukkit's)")
    arguments = parser.parse_args()
    if arguments.pack:
        shader_check.PACK = arguments.pack
    sys.path.insert(0, arguments.codec)
    import moderngl
    from mcvideo.transport import make_pages

    options = {"backend": arguments.backend} if arguments.backend == "egl" else {}
    context = moderngl.create_standalone_context(require=330, **options)
    renderer = context.info["GL_RENDERER"]
    print(renderer, context.info["GL_VERSION"])
    report = dict(renderer=renderer, version=context.info["GL_VERSION"], rounds=arguments.rounds,
                  repeats=arguments.repeats, streams={})
    for stream in arguments.streams:
        frames = list(shader_check.frames(stream))
        width, height = struct.unpack_from("<HH", frames[0], 8)
        chain = TimedChain(context, width, height, arguments.slots)
        pages = [make_pages(frame, shader_check.STREAM_ID, 6) for frame in frames]
        keyframes = [struct.unpack_from("<I", frame, 4)[0] >> 16 & 1 == 1 for frame in frames]
        first_pictures = None
        new_key, new_p, idle = [], [], []
        mismatches = 0
        for round_index in range(arguments.rounds):
            chain.reset()
            pictures = []
            for index, page_list in enumerate(pages):
                chain.show(page_list)
                decoded, times = chain.timed_frame(arguments.repeats)
                names = tuple(times)
                times["total"] = sum(times[name] for name in names)
                # the same pages again: a rendered frame without new video; the client draws the strip anew every
                # frame, and the screen pass just covered it. The picture is read back only after both, so no
                # read-back stall idles the GPU between them
                chain.show(page_list)
                shown_again, again = chain.timed_frame(1)
                again["total"] = sum(again[name] for name in names)
                pictures.append(chain.target("previous").read())
                if not decoded or shown_again:
                    print("  frame %d: decoded %s, decoded again %s" % (index, decoded, shown_again))
                if round_index > 0:
                    (new_key if keyframes[index] else new_p).append(times)
                    idle.append(again)
            if first_pictures is None:
                first_pictures = pictures
            else:
                mismatches += sum(1 for a, b in zip(first_pictures, pictures) if a != b)
        result = dict(width=width, height=height, frames=len(frames), mismatches=mismatches,
                      new_keyframe=summarize(new_key, names), new_p=summarize(new_p, names), idle=summarize(idle, names))
        report["streams"][Path(stream).name] = result
        print("%s (%dx%d, %d frames, %d rounds counted, %d picture mismatches between rounds)" % (
            Path(stream).name, width, height, len(frames), arguments.rounds - 1, mismatches))
        print("  %-24s %22s %22s %22s" % ("pass (ms)", "new P frame mean/p95", "new keyframe mean/p95", "no new video mean/p95"))
        for name in names + ("total",):
            cells = [result[kind][name] for kind in ("new_p", "new_keyframe", "idle")]
            print("  %-24s %22s %22s %22s" % (name, *("%9.3f / %9.3f" % (c["mean"], c["p95"]) for c in cells)))
    if arguments.json:
        arguments.json.write_text(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
