"""Rate-VMAF points of H.264, VP9 and AV1 on a raw RGB source, scored the way the MCV2 frontier scores MCV2.

    python tools/mcv2/codec_curves.py --ffmpeg "$FFMPEG" --source "$SRC" --name proxy30 \\
        --width 1920 --height 1080 --frames 30 --fps 30 --qualities 20 28 36 --out tools/mcv2/data/codec_curves.json

For every codec and quality setting the source is encoded from raw RGB24 into the codec's 4:2:0, decoded back to raw
RGB24 first (`-fps_mode passthrough`, so no frame is dropped or repeated), and only then scored raw against raw with
the frontier's libvmaf filter (both inputs converted to yuv420p, the default vmaf_v0.6.1 model, the pooled mean and the
per-frame minimum) and the frontier's RGB PSNR. A compressed file is never fed to libvmaf. The rate is the container's
size over the clip's duration: it charges none of the map transport that an MCV2 map rate charges.

The measurements of docs/mcv2.md used ffmpeg 7.0.2 (a static build with libvmaf, libx264, libvpx-vp9 and libaom),
the ffmpeg the frontier scored with. The output records every command with the placeholders $FFMPEG, $SRC, ENCODED
and DECODED instead of local paths. Points already in the output file are kept, so a run can be resumed; a run that
names a source measured before with other content is refused, as its points would mix with the earlier ones.
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
import time

import numpy as np

# the settings of every curve: the codec's constant-quality mode, one preset per codec, the pixel format the codec
# is used with in practice
CODECS = {
    "x264": {
        "container": "mkv",
        "arguments": ["-c:v", "libx264", "-preset", "veryslow", "-crf", "{q}", "-pix_fmt", "yuv420p"],
    },
    "vp9": {
        "container": "webm",
        "arguments": [
            "-c:v", "libvpx-vp9", "-crf", "{q}", "-b:v", "0", "-deadline", "good", "-cpu-used", "0",
            "-row-mt", "1", "-pix_fmt", "yuv420p",
        ],
    },
    "av1": {
        "container": "mkv",
        "arguments": [
            "-c:v", "libaom-av1", "-crf", "{q}", "-b:v", "0", "-cpu-used", "6", "-row-mt", "1", "-pix_fmt", "yuv420p",
        ],
    },
}

# the frontier's filter (the research's frontier_quality.py): both pictures to yuv420p, the distorted one first
VMAF_FILTER = (
    "[0:v]format=yuv420p[ref];[1:v]format=yuv420p[dis];"
    "[dis][ref]libvmaf=n_threads=8:log_fmt=json:log_path={log}"
)

VERSION_PATTERNS = {
    "x264": re.compile(rb"x264 - core \d+ r\d+ \w+"),
    "vp9": re.compile(r"\[libvpx-vp9 @ [^\]]+\] (v\d[^\s]*)"),
    "av1": re.compile(r"\[libaom-av1 @ [^\]]+\] (\d+\.\d+\.\d+[^\s]*)"),
}


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for chunk in iter(lambda: source.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def raw(path, width, height, fps):
    return ["-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", f"{width}x{height}", "-framerate", str(fps),
            "-i", path]


def placeholders(command, replacements):
    text = []
    for part in command:
        for local, name in replacements.items():
            part = part.replace(local, name)
        text.append(part)
    return text


def run(command):
    started = time.perf_counter()
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"{command[0]} failed: {result.stderr[-2000:]}")
    return result, time.perf_counter() - started


def rgb_psnr(source, decoded, frames, width, height):
    # the frontier's RGB PSNR: the mean of the per-frame squared errors over the clip, then PSNR
    shape = (frames, height, width, 3)
    reference = np.memmap(source, dtype=np.uint8, mode="r", shape=shape)
    picture = np.memmap(decoded, dtype=np.uint8, mode="r", shape=shape)
    errors = []
    for a, b in zip(reference, picture, strict=True):
        difference = a.astype(np.float64) - b.astype(np.float64)
        errors.append(float(np.mean(difference * difference)))
    mse = float(np.mean(errors))
    return 99.0 if mse == 0 else float(10 * np.log10(255**2 / mse))


def point(arguments, codec, quality, folder):
    settings = CODECS[codec]
    encoded = os.path.join(folder, f"{codec}-{quality}.{settings['container']}")
    decoded = os.path.join(folder, f"{codec}-{quality}.rgb")
    log = os.path.join(folder, f"{codec}-{quality}-vmaf.json")
    size = (arguments.width, arguments.height, arguments.fps)
    encode = [arguments.ffmpeg, "-hide_banner", "-nostdin", "-v", "verbose", "-y", *raw(arguments.source, *size),
              "-frames:v", str(arguments.frames), "-an",
              *[part.replace("{q}", str(quality)) for part in settings["arguments"]], encoded]
    result, encode_seconds = run(encode)
    decode = [arguments.ffmpeg, "-hide_banner", "-nostdin", "-v", "error", "-y", "-i", encoded,
              "-fps_mode", "passthrough", "-pix_fmt", "rgb24", "-f", "rawvideo", decoded]
    run(decode)
    frame_bytes = arguments.width * arguments.height * 3
    decoded_frames, remainder = divmod(os.path.getsize(decoded), frame_bytes)
    if remainder or decoded_frames != arguments.frames:
        raise RuntimeError(f"{codec} {quality}: decoded {decoded_frames} frames, expected {arguments.frames}")
    score = [arguments.ffmpeg, "-hide_banner", "-nostdin", "-v", "error", "-y", *raw(arguments.source, *size),
             *raw(decoded, *size), "-lavfi", VMAF_FILTER.format(log=log), "-f", "null", "-"]
    run(score)
    vmaf = json.load(open(log))
    frames = [frame["metrics"]["vmaf"] for frame in vmaf["frames"]]
    if len(frames) != arguments.frames:
        raise RuntimeError(f"{codec} {quality}: libvmaf scored {len(frames)} frames")
    container_bytes = os.path.getsize(encoded)
    seconds = arguments.frames / arguments.fps
    version = VERSION_PATTERNS[codec]
    if codec == "x264":
        found = version.search(open(encoded, "rb").read())
        library = found.group(0).decode() if found else None
    else:
        found = version.search(result.stderr)
        library = found.group(1) if found else None
    names = {arguments.source: "$SRC", encoded: "ENCODED", decoded: "DECODED", log: "VMAF.json",
             arguments.ffmpeg: "$FFMPEG"}
    return {
        "source": arguments.name,
        "codec": codec,
        "library": library,
        "quality": quality,
        "container_bytes": container_bytes,
        "container_mbps": round(container_bytes * 8 / seconds / 1e6, 6),
        "vmaf_mean": round(vmaf["pooled_metrics"]["vmaf"]["mean"], 6),
        "vmaf_min": round(min(frames), 6),
        "rgb_psnr": round(rgb_psnr(arguments.source, decoded, arguments.frames, arguments.width, arguments.height), 4),
        "encode_seconds": round(encode_seconds, 1),
        "commands": {
            "encode": placeholders(encode, names),
            "decode": placeholders(decode, names),
            "score": placeholders(score, names),
        },
    }


def resume(out, name, identity):
    """The measurements already in the output, with the identity of this run's source recorded under its name.

    The curves are kept under "codecs", as in tools/mcv2/data/codec_curves.json; the first runs wrote them under "points",
    which is read too. A name whose recorded source is other content is refused: its points would mix with this run's.
    """
    measured = json.load(open(out)) if os.path.exists(out) else {"sources": {}, "codecs": []}
    if "points" in measured:
        if "codecs" in measured:
            raise ValueError("%s holds both points and codecs; resolve the two datasets first" % out)
        measured["codecs"] = measured.pop("points")
    previous = measured["sources"].get(name)
    if previous is not None and any(previous.get(key) != value for key, value in identity.items()):
        raise ValueError("%s already has measurements of other content; use another --name" % name)
    measured["sources"][name] = identity
    return measured


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ffmpeg", required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    parser.add_argument("--frames", type=int, required=True)
    parser.add_argument("--fps", type=float, required=True)
    parser.add_argument("--codecs", nargs="+", choices=sorted(CODECS), default=sorted(CODECS))
    parser.add_argument("--qualities", nargs="+", type=int, required=True, help="the CRF values to encode at")
    parser.add_argument("--out", required=True)
    arguments = parser.parse_args()
    identity = {
        "width": arguments.width,
        "height": arguments.height,
        "frames": arguments.frames,
        "fps": arguments.fps,
        "sha256": sha256(arguments.source),
    }
    try:
        measured = resume(arguments.out, arguments.name, identity)
    except ValueError as error:
        parser.error(str(error))
    done = {(p["source"], p["codec"], p["quality"]) for p in measured["codecs"]}
    version = subprocess.run([arguments.ffmpeg, "-version"], capture_output=True, text=True).stdout.splitlines()[0]
    measured["ffmpeg"] = version
    with tempfile.TemporaryDirectory() as folder:
        for codec in arguments.codecs:
            for quality in arguments.qualities:
                if (arguments.name, codec, quality) in done:
                    continue
                measured["codecs"].append(point(arguments, codec, quality, folder))
                print(json.dumps(measured["codecs"][-1]["codec"]), quality, measured["codecs"][-1]["container_mbps"],
                      measured["codecs"][-1]["vmaf_mean"], flush=True)
                measured["codecs"].sort(key=lambda p: (p["source"], p["codec"], p["quality"]))
                temporary = arguments.out + ".partial"
                with open(temporary, "w") as out:
                    json.dump(measured, out, indent=1)
                    out.write("\n")
                os.replace(temporary, arguments.out)


if __name__ == "__main__":
    main()
