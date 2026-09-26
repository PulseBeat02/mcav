# MCV2 tools

Scripts that tie mcav's MCV2 port to the gpu-codec research repository it comes from. None of them runs during the
build: the Java tests read only the fixtures committed under
`mcav-common/src/test/resources/me/brandonli/mcav/media/mcv2`. They take the gpu-codec checkout as an argument, at
commit `85445433aeb9f8a35a5ce528d47d8829976d1401`, and need a Python with numpy; the shader check also needs moderngl,
the capture check Pillow.

| script | what it does |
|---|---|
| `fixtures.py <codec> <fixture root> [conformance\|edge\|pages\|encoder\|all]` | regenerates every MCV2 test fixture with the reference itself; into an empty folder it reproduces the committed fixtures byte for byte |
| `edge_streams.py <codec> <out> [seed]` | the edge-case streams: every leaf mode, compact class, motion form and index form, built with the reference serializer and decoded by the reference decoder, plus the syntax mcav refuses (`rejected.json`) |
| `shader_check.py <codec> <streams...> [--slots N] [--drop K] [--backend egl\|glx] [--pack DIR]` | runs the resource pack's post chain under OpenGL 3.3 outside Minecraft, pass for pass as its `entity_outline.json` lists them, and compares every picture with the reference decoder, frame by frame, with the persistent references carried over as in the client; a frame with more pages than slots counts as never sent. Run before every commit that touches the pack, on the Intel GPU (EGL) and on llvmpipe (GLX): the conformance and edge fixtures, the A/B/D clips and crops, ship, low_bandwidth and live streams, and `--drop 7` on A and B |
| `shader_timing.py <codec> <streams...> [--backend egl\|glx] [--rounds R] [--repeats K] [--pack DIR] [--json OUT]` | times every pass of that chain with GL_TIME_ELAPSED queries, for a frame that brings new video and for a rendered frame without new video, with a screen drawn in view; `--pack` times another pack folder, such as an older version, in the same window |
| `differential.py <codec> <mcav-common classpath> [--streams N] [--encoded N] [--mutants N] [--seed S] [--out DIR]` | the differential test of addendum 4: generates archives (random block trees through the reference serializer at random sizes and index forms, reference-encoder streams of random moving pictures, and a mutated copy of each with bytes changed or a frame cut short), decodes every frame with the reference decoder and with mcav's (`Mcv2Digests.java`), and compares the per-frame digests; the only allowed difference is syntax mcav refuses as unsupported. Writes `summary.json`, exits non-zero on a disagreement |
| `Mcv2Digests.java <archive>...` | prints, per archive, the SHA-256 of every frame mcav's receiver decodes, or `reject` / `unsupported`; run with a JDK, `java -cp <classes>:<resources>:<guava> tools/mcv2/Mcv2Digests.java` |
| `counter_video.py <clip.rgb> <w> <h> <fps> <seconds> <out.mp4>` | a test video whose frames carry their own number in the first 24 blocks of their top row (the pixels the `Mcv2Frame` flight recorder event fingerprints), played forward and backward from a raw clip |
| `latency.py <server.jfr> <capture.nut> [--json OUT]` | end-to-end numbers of a run from the server's `Mcv2Frame` events and an x11grab capture of the client's debug view: frames encoded, sent, held back and displayed, displayed fps, backlog, and the glass-to-glass latency of every displayed frame |
| `capture_check.py <reference.rgb> <w> <h> <captures> [--top ROWS] [--vmaf FFMPEG]` | compares screenshots of a client running the pack's debug view (server started with `-Dmcav.mcv2.debugView=true`) with the reference decode: which frames were seen byte for byte, and PSNR, SSIM and VMAF |

What the fixtures are:

- `conformance/` — the twelve round-19 sample streams and the two shipped 1080p30 streams (the ship and low-bandwidth
  profiles). A stream over 1,000,000 bytes is committed as its longest whole-frame prefix within that size, which starts
  with its keyframe. `digests.json` has the reference decoder's per-frame SHA-256 of the RGB output.
- `conformance/pages.json` — the reference's map pages (`make_pages`, stream id 7, 6, 7 and 8 bits) of four frames: the
  SHA-256 of every page's symbols, their lengths, and the wire model.
- `edge/` — the edge-case streams and their digests; `rejected.json` lists MCV1, the coarse palettes of round 3 and the
  motion table of round 15, which the reference accepts and mcav refuses.
- `encoder/` — a 320x180 crop of the first four frames of the frontier's 1080p30 source at (1472, 360), and the
  reference encoder's streams of it at both shipped lambdas; the Java encoder must reproduce them byte for byte.
