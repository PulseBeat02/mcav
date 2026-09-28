# MCV2 reference

The Python reference encoder and decoder of MCV2, the parts of it mcav needs, kept so that mcav's MCV2 fixtures and
checks do not depend on the research repository they came from. Every file is byte for byte the file of the gpu-codec
repository at commit `85445433aeb9f8a35a5ce528d47d8829976d1401` (2026-09-25, "Final report: converged by owner
decision 6 after round 19"), the commit mcav's MCV2 port is pinned to; none is changed.

It is the normative definition of the format ([docs/mcv2/format.md](../../docs/mcv2/format.md) writes it down), the
oracle mcav's Java decoder and encoder are proven bit-exact against, and what `tools/mcv2/fixtures.py` regenerates the
test fixtures with ([docs/mcv2/conformance.md](../../docs/mcv2/conformance.md)). The tools in `tools/mcv2` put this
folder on Python's path themselves.

## What is here, and why only this

`mcvideo/` is the import closure of what the tools use - `format`, `v2` (the tree syntax and `TreeEncoder`),
`compact`, `pattern`, `decoder`, `encoder` (`Settings`), `transport` (pages) - computed with Python's `modulefinder`
from the tools' imports: those modules and the ones they import, `pixels`, `rdo`, `codebooks` and `repack`, and the
package's `__init__`. Regenerating every committed fixture with it reproduces them byte for byte, which shows nothing
else is needed. The research repository's other modules (its benchmark sources, metrics, the GPU harness, palette
experiments, the command line), its scripts, tests and 93 GB of data are research tooling and are left out.

`research_artifacts/residual_books.bin` is the immutable residual codebook (2,048 bytes: a 64x16 signed-byte VQ book,
then two 64x8 product books), which `codebooks.py` reads from this folder and checks against
`residual_books.sha256`; `residual_books.json` records how it was trained (seed 19781, procedural sources, 18,000
samples, 16 iterations). mcav's decoder reads the same bytes from
`mcav-bukkit/src/main/resources/me/brandonli/mcav/bukkit/media/mcv2/residual_books.bin`; `fixtures.py` refuses to run if
the two copies differ.

| file | bytes | SHA-256 |
|---|---:|---|
| `mcvideo/__init__.py` | 83 | `6cbea603d400f306d36dfc76b27a1daa8669288b9b99a49aab44c2d0dab7e882` |
| `mcvideo/codebooks.py` | 1,272 | `4f9742ab8775a6c689217dcf8e2488cb3a01e02d230e707f4523208804a1e4c7` |
| `mcvideo/compact.py` | 9,290 | `c180a953ed1ac31bddd72f4ce22991c4d0d999e20883047c43013f890fa278d1` |
| `mcvideo/decoder.py` | 6,187 | `6a2ba1e837b27c2fe6218d5dc173cce9f861522a36ae80c9957158f8f4a818b4` |
| `mcvideo/encoder.py` | 20,308 | `7549086195aae3c69ca0d2b01b31e155cdfddda1f154a2b10b33472e2098501e` |
| `mcvideo/format.py` | 15,702 | `8e16a9b2b23a84f5504b3d394603b1eabbadce6f2191f1bdae7c82c59390a2df` |
| `mcvideo/pattern.py` | 4,710 | `57297ba2518ef764e9b4ed932fbab4c139bb15212dc866a8618546d55a0582af` |
| `mcvideo/pixels.py` | 6,803 | `48f911950fec54cc1b02371cf488ed64d232f6e43cbfee9d4f7f6915db27b7d2` |
| `mcvideo/rdo.py` | 9,131 | `123b432dc6be62dbfb37c76daf7b996627e2c34db0a3e5991afdfd22380a5601` |
| `mcvideo/repack.py` | 4,034 | `3636eeb0fae70970798db1d79a25aa1a1358273307c896a9145614a53ec9ecf6` |
| `mcvideo/transport.py` | 7,680 | `f5bd69b854235d15071fb7a1229f788253375b4bd35ce9d3adc274e49c4dc0a0` |
| `mcvideo/v2.py` | 76,919 | `3a7681ace9cc21b42287f233bcfa03597b22c7753543f05e4160efb3676b832d` |
| `research_artifacts/residual_books.bin` | 2,048 | `1737842f5fbaa3e23777a04b6869a2bbd7d088c40efd1a8d237d756458c3e788` |
| `research_artifacts/residual_books.sha256` | 65 | `f46fdcf4269185baa4722ed653eb61b8cea4f2dde616bba5874a511109cc02df` |
| `research_artifacts/residual_books.json` | 532 | `42ad08824ff6a443ca20ef725b08aeff686d219e746920aa50cc0f2891b278ca` |

## Use

Python 3.12 or newer and `pip install -r tools/mcv2-reference/requirements.txt` (pinned: the versions the fixtures
were last regenerated with). The reference was written to be read, not to be fast: it decodes a 1080p frame in
seconds and encodes one in minutes (172 s for a frame of the shipped profile).

```
python tools/mcv2/fixtures.py mcav-bukkit/src/test/resources/me/brandonli/mcav/bukkit/media/mcv2
```

## Changing it

Do not. It is the definition mcav is proven against; a change here would change what "bit-exact" means for every
fixture. A format change starts in mcav's Java port and its spec, and a new reference, if there ever is one, replaces
this folder as a whole with its own commit named here.
