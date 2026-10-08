"""Check the resource pack's post chain on a screen too small for the transport strip.

    python tools/mcv2/strip_fit_check.py [--backend egl|glx]

The strip takes the top rows of the screen: every slot's page, then one descriptor row per screen. A pack with many
slots in a small window needs more rows than the window has (eight screens of eight slots need 456 rows at 640x360,
one screen of eight slots 209 rows at 160x90). There the text shaders leave the pages and anchors where they hang,
and the post chain must leave the scene exactly as it is: it neither covers a strip that does not fit nor reads pages
or descriptors from rows outside the screen. A screen the strip fits keeps it covered with the scene row just below
it, as before. Runs the chain as shader_check.py does, on a random scene and no pages; the summary is one JSON line,
and the exit code is 1 when a check failed.
"""

import argparse
import json
import sys

import numpy as np

import shader_check

VIDEO = (64, 64)
SLOTS = 8


def frame(context, screen):
    """One frame of the chain on a screen of a size, over a random scene: the scene, the screen after, and whether
    a frame was decoded."""
    shader_check.SCREEN = screen
    chain = shader_check.Chain(context, VIDEO[0], VIDEO[1], SLOTS)
    scene = np.random.default_rng(7).integers(0, 256, (screen[1], screen[0], 4), dtype=np.uint8)
    scene[..., 3] = 255
    chain.main.write(scene.tobytes())
    decoded, _ = chain.frame()
    after = np.frombuffer(chain.main.read(), np.uint8).reshape(screen[1], screen[0], 4)
    return scene, after, decoded


def strip_rows(width):
    """The strip's rows on a screen of a width, as mcv2_strip.glsl counts them for one screen."""
    return SLOTS * ((4096 + width - 1) // width) + 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend", choices=("egl", "glx"), default=None)
    arguments = parser.parse_args()
    import moderngl

    context = moderngl.create_standalone_context(require=330, **({"backend": "egl"} if arguments.backend == "egl" else {}))
    print(context.info["GL_RENDERER"])
    failures = []
    for screen in ((160, 90), (64, 400)):
        assert strip_rows(screen[0]) >= screen[1], screen
        scene, after, decoded = frame(context, screen)
        changed = int(np.count_nonzero(np.any(after != scene, axis=2)))
        if changed:
            failures.append("%dx%d, where the strip does not fit: %d pixels of the scene changed" % (screen + (changed,)))
        if decoded:
            failures.append("%dx%d, where the strip does not fit: a frame was decoded" % screen)
    screen = (854, 480)
    rows = strip_rows(screen[0])
    scene, after, _ = frame(context, screen)
    # rows of the targets count from the bottom: the strip is the top rows, covered with the scene row below it
    below = screen[1] - 1 - rows
    if not np.array_equal(after[: below + 1], scene[: below + 1]):
        failures.append("854x480: the scene below the strip changed")
    if not np.array_equal(after[below + 1 :], np.broadcast_to(scene[below], (rows,) + scene[below].shape)):
        failures.append("854x480: the strip is not covered with the scene row below it")
    print(json.dumps({"checks": 6, "failures": failures}))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
