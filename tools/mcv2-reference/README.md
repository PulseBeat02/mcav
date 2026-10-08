# MCV2 version 3 reference

This is the normative Python reference for MCV2 v3, together with the specification in
[docs/mcv2.md](../../docs/mcv2.md). It was written from the specification, independently of
mcav's Java code. It validates and decodes frames, serializes supplied block trees, and
implements six-bit transport pages. It contains no encoder or search code.

Use Python 3.12 or newer and `pip install -r tools/mcv2-reference/requirements.txt`.
The reference needs only numpy (pinned to 2.5.3); the adjacent shader and screenshot tools
also use moderngl 5.12.0 and Pillow 12.3.0. From the repository root:

```sh
python -m unittest discover -s tools/mcv2-reference/tests
python tools/mcv2/fixtures.py mcav-bukkit/src/test/resources/me/brandonli/mcav/bukkit/media/mcv2 all
```

Add `tools/mcv2-reference` to `PYTHONPATH` to import the package:

```python
from mcvideo.format import SOLID
from mcvideo.v3 import Node, pack_frame, parse_frame
from mcvideo.decoder import decode, Decoder
from mcvideo.transport import make_pages, Assembler

wire = pack_frame(1, 1, 9, 9, True, (0, 0, 0), {0: Node(SOLID, record=bytes([10, 20, 30]))})
assert decode(wire).tolist() == [[[10, 20, 30]]]
```

## Public API

- `format.py` defines header offsets, mode and compact class numbers, bounds and transport constants.
- `v3.py`: `Node(mode, q=0, record=b"", children=())`, `parse_frame(data: bytes) -> Frame` and
  `pack_frame(width, height, frame_id, reference_id, keyframe, default_color, roots) -> bytes`.
  `roots` maps superblock index to `Node`; missing entries mean SKIP. SPLIT nodes have four
  children in top-left, top-right, bottom-left, bottom-right order. Records are whole bytes;
  a PATTERN record is six RGB bytes, an orientation byte (0 or 1) and the `size / 8` axis bytes.
- `Frame` exposes header fields, `leaves`, `masks`, `directory`, `level_counts`, `descriptors`,
  `walk` and `roots`. Parsed roots can be passed back to `pack_frame` for a byte-identical round
  trip. Leaves have `x`, `y`, `size`, `mode`, `q`,
  `offset`, `record` and `descriptor_index`. Present leaves are in level order, followed by
  absent superblocks in raster order; absent leaves have `offset = descriptor_index = None`.
- `decoder.py`: `decode(data: bytes, reference: np.ndarray | None = None,
  reference_id: int | None = None) -> np.ndarray` returns height × width × 3 uint8 RGB.
  A P frame requires both the matching id and a uint8 reference of the same dimensions.
  `Decoder().accept(data) -> np.ndarray` retains one previous picture and applies the u32
  half-range rule. Invalid, old or missing-reference frames raise `ValueError` and preserve
  state; a newer keyframe restores playback. A newer P frame can reference the held picture
  across an id gap. Returned pictures can be edited without changing the held reference.
- `transport.py`: `PAGE_HEADER` is a `struct.Struct`;
  `page_capacity(symbol_bits=6) -> int`, `make_pages(frame_data, stream_id=1, symbol_bits=6)
  -> list[bytes]`, `read_page(symbols, symbol_bits=6) -> Page`,
  `wire_bytes(pages, full_maps=False, packet_overhead=18) -> int`, and
  `Assembler(stream_id=1, symbol_bits=6).push(symbols) -> bytes | None`.
  Only six-bit symbols are accepted. Page symbols are values 0–63 with exact symbol length;
  add four for map colours and pad map updates to whole 128-colour rows with colour four.
  `read_page` takes the unpadded symbol sequence. `to_symbols(data, symbol_bits=6)` and
  `from_symbols(symbols, symbol_bits, byte_count)` expose the LSB-first packing.
  Assembly validates CRCs, metadata agreement and complete frame syntax, retaining at most
  four pending frames; it does not advance decoder state.

## Tests and file hashes

The tests include literal wire bytes, hand-computed expected pixels, every validation rule,
serializer/tree round trips, frame and id bounds, and page corruption/reassembly. The malformed
vectors in `tests/rejection_cases.py` also supply the public rejected-frame fixture catalog.
All source and test files and the pinned requirements are listed below; this README is excluded
from its own manifest.

| file | bytes | SHA-256 |
|---|---:|---|
| `mcvideo/__init__.py` | 822 | `f25bfd3cdfbb172eaf31e0fd90ba062ef0f30f9aef5e04807734565d4c68e03c` |
| `mcvideo/decoder.py` | 5,689 | `598982ba28da2fa1b8c5c8f867db64480d2ec9d2021f9cdadfbbf3484a0292a1` |
| `mcvideo/format.py` | 1,819 | `4cdf26dc1c45c2af6aada9e0d0ffbd713d8f178fc6a64816b3e2df5182b22e64` |
| `mcvideo/transport.py` | 6,385 | `36d445e28a90564e17c893ec2991f55db16497c97560711facf3412ba740949b` |
| `mcvideo/v3.py` | 11,587 | `70f57ad0a8d83345233f2c9383a3bd2cc054eb21f91d86d1319bc1ff9c37a7c8` |
| `requirements.txt` | 883 | `c4f288cc61d58c292c943c07dbe3b4e233aac528db5a9d91866704469ed4ea24` |
| `tests/rejection_cases.py` | 7,582 | `a46288649f5da7d089439f60d8baaba7054fac2969a868662ebd40063efb0d53` |
| `tests/test_decoder.py` | 10,240 | `0169fc91fb7c5d2468b996a200b4981133c423b96e4b9cae2ecd5a6d4bfb40d7` |
| `tests/test_format.py` | 6,787 | `811008d6075fd61125e048e9354d984f549926a7a7fb0471feca9323dfed2ad0` |
| `tests/test_transport.py` | 7,366 | `27f1c810fd739b1e58365987d2357f220f4e28103d6b0252c3b40c591b3ad0cf` |
