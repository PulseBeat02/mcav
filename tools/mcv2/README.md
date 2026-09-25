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
| `shader_check.py <codec> <streams...> [--slots N] [--drop K]` | runs the resource pack's post passes under OpenGL 3.3 outside Minecraft and compares every picture with the reference decoder, frame by frame, with the persistent references carried over as in the client |
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
