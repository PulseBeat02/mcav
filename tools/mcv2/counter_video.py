"""Make a test video whose every frame carries its own number, for end-to-end latency measurements.

    python tools/mcv2/counter_video.py <clip.rgb> <width> <height> <fps> <seconds> <out.mp4|out.rgb> [--ffmpeg FFMPEG]

The clip (raw RGB frames of the given size) is played forward and backward until the video is long enough, so motion
never jumps. The first 24 blocks of 32x32 pixels of every frame's top row are overwritten with the frame's number: blocks
0 to 19 are its bits, least significant first, white for 1 and black for 0, and blocks 20 to 23 are white, black, white,
black, a pattern that tells a readable number from a picture that is not one. The block centres are the pixels
mcav's Mcv2Frame flight recorder event fingerprints (row 16, x = 16 + 32 i), so a frame's number is known on the server
from its event and on the client from a capture of the picture. The output is H.264 at a high quality (CRF 12,
keyframe every two seconds), so the blocks survive the player's decoder unchanged; an output named .rgb is the raw
frames instead, for encoding offline into a stream the sandbox's /mcav mcv2 stream plays.
"""

import argparse
import subprocess
import sys

import numpy as np

BITS = 20
BLOCK = 32
SYNC = (1, 0, 1, 0)


def stamp(frame, number):
    """Writes a frame's number into the blocks of its top row."""
    values = [(number >> bit) & 1 for bit in range(BITS)] + list(SYNC)
    for i, value in enumerate(values):
        frame[0:BLOCK, i * BLOCK:(i + 1) * BLOCK] = 255 if value else 0


def read(luma):
    """The number a row of 24 block-centre lumas carries, or None when the sync blocks do not match."""
    bits = [1 if value >= 128 else 0 for value in luma]
    if len(bits) < BITS + len(SYNC) or tuple(bits[BITS:BITS + len(SYNC)]) != SYNC:
        return None
    return sum(bit << i for i, bit in enumerate(bits[:BITS]))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("clip")
    parser.add_argument("width", type=int)
    parser.add_argument("height", type=int)
    parser.add_argument("fps", type=int)
    parser.add_argument("seconds", type=float)
    parser.add_argument("out")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    arguments = parser.parse_args()
    width, height = arguments.width, arguments.height
    size = width * height * 3
    clip = np.memmap(arguments.clip, dtype=np.uint8, mode="r")
    count = clip.size // size
    if count < 1 or width < BLOCK * (BITS + len(SYNC)) or height < BLOCK:
        sys.exit("the clip must have a frame at least %d pixels wide and %d high" % (BLOCK * (BITS + len(SYNC)), BLOCK))
    frames = int(arguments.seconds * arguments.fps)
    raw = arguments.out.endswith(".rgb")
    encoder = None if raw else subprocess.Popen(
        [arguments.ffmpeg, "-nostdin", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
         "-s", "%dx%d" % (width, height), "-r", str(arguments.fps), "-i", "-", "-c:v", "libx264", "-preset", "veryfast",
         "-crf", "12", "-g", str(2 * arguments.fps), "-pix_fmt", "yuv420p", arguments.out],
        stdin=subprocess.PIPE,
    )
    sink = open(arguments.out, "wb") if raw else encoder.stdin
    period = max(1, 2 * (count - 1))
    for number in range(frames):
        m = number % period
        index = m if m < count else period - m
        frame = np.array(clip[index * size:(index + 1) * size]).reshape(height, width, 3)
        stamp(frame, number)
        sink.write(frame.tobytes())
    sink.close()
    sys.exit(0 if raw else encoder.wait())


if __name__ == "__main__":
    main()
