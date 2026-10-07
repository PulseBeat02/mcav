(mcv2-transport)=
# Pages in Map Packets

A map is 128x128 pixels, and every pixel is one byte {cite}`mcwikimap,mcwikimapformat`: the id of one of the 248 map colours of Minecraft 26.3 (62 base
colours in four shades each; the four shades of the first are transparent). The client uploads the bytes of every map
it knows into a 128x128 texture and draws it wherever the map is shown, and a map-data packet can replace any rectangle
of those bytes. MCV2 uses exactly that and nothing else to reach the client. The layout of every field is in the
[specification, section 6](format.md#6-transport).

```{figure} figures/transport.png
:name: mcv2-transport-flow
:alt: Flow chart from frame bytes to pages, symbols, map colours, map packets, per-viewer backpressure and compression, and on the client back to checked bytes.

A frame's way to the decoder. The CRC32 is computed by the server over each page's header and slice, and checked by
the client's post chain before anything is decoded. The labels in brackets are the fields of the map rate: the six-bit
expansion, row rounding, page headers and the packet envelope are all charged to every MCV2 rate these pages quote.
```

## Pages

A frame is cut into **pages** of 12,256 bytes, the last one shorter, and each gets a 32-byte header: the magic `MCP1`,
a version, the symbol width (6), the frame type, a stream id, the frame id, the page number and page count, the
reference id, the frame length and a **CRC32** (IEEE, as zlib computes it) over the header with its CRC field zero,
followed by the page's slice. The CRC detects damage; it is not authentication. A page carries its frame's identity,
so the client can tell a complete frame from pages of two different frames.

## Six-Bit Symbols

A page's 12,288 bytes are read least significant bit first as one bit stream and cut into 16,384 six-bit symbols,
exactly one 128x128 map. Symbol `s` is sent as map colour `s + 4`: the colour ids 4 to 67, the four shades of base
colours 1 to 16, whose 64 RGB values are all different, so the client's texture tells them apart exactly. A shorter
last page is padded to whole rows of 128 colours with colour 4.

Six bits per map byte is the price of using maps: 244 opaque colours would hold almost eight bits, but a symbol must
be recovered from the RGB the client draws, so the alphabet is a power of two of distinct colours. The four map bytes
that carry three payload bytes are `symbol_expansion` in the cost audit: **24.8% of the map rate** at the 5 Mbps rung.

## Map Packets

The server writes page `n` of a frame into the top rows of page map `page_map + n`, as one map-data packet, and sends
all pages of a frame in **one bundle**, so they arrive together. The page maps belong to hidden item frames behind the
wall (the page frames) and are never shown to the player as maps.

The **map rate**, which every MCV2 figure in these pages quotes, charges everything a page costs on the wire: its
header, the six-bit expansion, the rows it is rounded up to, and an 18-byte envelope for the packet (length, id, map
id, scale, lock and decoration flags, the rectangle and the colour array's length), per page:
`rows x 128 + 18` bytes. It leaves out TCP, IP and TLS, and it leaves out compression.

## The Game's Compression

At the default 256-byte compression threshold, Minecraft compresses larger packets with zlib {cite}`rfc1950`. Map colours that carry six-bit symbols use only 64
of 256 byte values, so compression takes back a large part of the expansion. Measured on the packets MCAV sends (every
page as its map-data packet, deflated at the game's default level) ([design doc §7](../mcv2-integration.md#7-transport-and-wire-accounting)):

| Stream | Map packets | After zlib | Saving |
|---|---:|---:|---:|
| `ship`, 1080p30 proxy | 3.40 Mbit/s | 2.19 Mbit/s | -35.5% |
| `live` (the first live profile), 1080p60 proxy | 5.38 Mbit/s | 3.50 Mbit/s | -34.9% |
| `live` as shipped, 1080p30 proxy | 2.80 Mbit/s | 1.83 Mbit/s | -34.6% |
| `live` as shipped, 30 fps gameplay | 13.04 Mbit/s | 8.29 Mbit/s | -36.4% |

The last two rows are the final `live` preset of the [1080p30 gate](results.md#the-live-presets) (600 frames). The TCP
payload measured at a real client connection agreed (3.4 to 3.6 Mbit/s for the 1080p60 stream). Deflating
costs the server 2.0% of a core per viewer for the 1080p60 stream and 1.3% for `ship` at 30 fps, and inflating costs
the client 55 to 76 microseconds per frame. MCAV therefore leaves compression on: skipping it would give up a third of
the bandwidth to save 1 to 2% of a core per viewer. The [codec comparison](quality.md#four-codecs-on-one-chart) shows
MCV2 at both rates.

## One Stream, Many Viewers

A screen's stream is encoded once, and every viewer receives it through a link of its own (`Mcv2Link`), which sends a
frame only when that viewer can decode it (a keyframe, or a P frame whose reference is the last frame or keyframe that
viewer was sent) and only while the viewer's unwritten video is under 128 KiB (twice that for a keyframe). A viewer
whose connection falls behind skips to the next frame it can decode, while the others are not held back. This limits
queued video, but cannot take back bytes already in flight: a connection slower than the stream can still delay game
packets ([server and network](server.md#far-viewers)).

## On the Client

The client draws a page map like any map, as text-rendered quads; the pack's `core/text.vsh` recognises a page map by
its `MCP1` header in its first symbols and moves its quad to an exact strip of rows on the screen, one slot per page,
and `text.fsh` writes the symbols through unchanged, four symbols into the three bytes of one pixel, with alpha 1 so
the blend keeps them. The post chain reads the strip back into bytes, recomputes every page's CRC and checks its
header, and only a frame whose pages are all present and valid is decoded ([Decoding in the Client](decoding.md)).
