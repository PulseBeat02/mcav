"""Rate-quality points of an MCV2 encoder configuration: encode a raw RGB source at several lambdas, score VMAF.

    python tools/mcv2/rate_quality.py --classpath CP --source SRC.rgb --frames 60 --fps 60 \\
        --lambdas 45,65.255994022,100,137.730758207 --ffmpeg ffmpeg-with-libvmaf --out points.json -- profile=live ...

For every lambda the benchmark encoder (the Java main class given by --main, run with --classpath) encodes the first
--frames frames of the source and writes the pictures a client decodes; ffmpeg's libvmaf then scores them against
the source (yuv420p, the frontier's filter), and the point records the encoder's rates (map colours and zlib, per
second at --fps), the VMAF mean and minimum, the PSNR and the encode time. The arguments after -- are passed to the
encoder for every lambda. The output is the list tools/mcv2/bd_rate.py reads.
"""

import argparse
import json
import os
import subprocess
import sys
import tempfile


def vmaf(ffmpeg, source, decoded, width, height, frames, fps):
    with tempfile.TemporaryDirectory() as folder:
        log = os.path.join(folder, "vmaf.json")
        raw = ["-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", f"{width}x{height}", "-framerate", str(fps)]
        graph = (
            "[0:v]format=yuv420p[ref];[1:v]format=yuv420p[dis];"
            f"[dis][ref]libvmaf=n_threads={os.cpu_count()}:log_fmt=json:log_path={log}"
        )
        subprocess.run(
            [ffmpeg, "-nostdin", "-v", "error", "-y", *raw, "-i", source, *raw, "-i", decoded, "-frames:v", str(frames),
             "-lavfi", graph, "-f", "null", "-"],
            check=True,
        )
        scores = [frame["metrics"]["vmaf"] for frame in json.load(open(log))["frames"]]
        return sum(scores) / len(scores), min(scores)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--main", default="LiveBench")
    parser.add_argument("--source", required=True)
    parser.add_argument("--width", type=int, default=1920)
    parser.add_argument("--height", type=int, default=1080)
    parser.add_argument("--frames", type=int, default=60)
    parser.add_argument("--fps", type=float, default=60)
    parser.add_argument("--lambdas", required=True)
    parser.add_argument("--ffmpeg", required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--out", required=True)
    parser.add_argument("encoder", nargs="*")
    arguments = parser.parse_args()
    points = []
    with tempfile.TemporaryDirectory() as folder:
        decoded = os.path.join(folder, "decoded.rgb")
        for value in arguments.lambdas.split(","):
            command = [
                arguments.java, "-Xmx8g", "-cp", arguments.classpath, arguments.main,
                f"source={arguments.source}", f"width={arguments.width}", f"height={arguments.height}",
                f"frames={arguments.frames}", f"fps={arguments.fps}", f"lambda={value}", f"decoded={decoded}",
                *arguments.encoder,
            ]
            result = subprocess.run(command, check=True, capture_output=True, text=True)
            point = json.loads(result.stdout.strip().splitlines()[-1])
            point["lambda"] = float(value)
            point["encoder"] = " ".join(arguments.encoder)
            point["vmaf_mean"], point["vmaf_min"] = vmaf(
                arguments.ffmpeg, arguments.source, decoded, arguments.width, arguments.height, arguments.frames, arguments.fps
            )
            points.append(point)
            print(json.dumps(point), file=sys.stderr)
    with open(arguments.out, "w") as out:
        json.dump(points, out, indent=1)
        out.write("\n")


if __name__ == "__main__":
    main()
