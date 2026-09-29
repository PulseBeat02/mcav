(mcv2-how-it-works)=
# How MCV2 Works

MCV2 turns maps into a data channel. The server encodes every video frame into a compact block format, cuts the bytes
into pages of six-bit symbols, and sends each page as the colours of one map. The client is an unmodified Minecraft
with a resource pack: its shaders read those maps back into bytes and decode the picture on the GPU, then draw it over
the wall of maps. This page follows one frame through the whole path; the pages under it explain every step in the
order it runs, closely enough to write a decoder from them and the [specification](format.md).

```{figure} figures/overview.png
:name: mcv2-overview
:alt: The path of a frame from the server's encoder through map packets to the client's shaders.

MCV2 end to end. Everything left of the arrow runs in `mcav-bukkit` on the server; everything right of it runs in the
resource pack of a vanilla client. Nothing but map packets and one resource pack reaches the client.
```

## One Frame, Start to Finish

1. **Encode** ([Encoding a Frame](encoding.md)). The encoder decides whether the frame is a keyframe or predicts from
   the previous decoded frame, estimates one global translation for the whole frame, and then searches every 32x32,
   16x16 and 8x8 block for the cheapest way to code it: skip it, move it, paint it with a colour, a palette or a small
   grid, or correct a prediction with a residual. "Cheapest" is the error of what the decoder will reconstruct plus
   lambda times the bits it costs.
2. **Serialize** ([The Block Tree and its Modes](block-tree.md)). The chosen tree is written as an index of one-byte
   descriptors and a payload of records, in a form where the address of every record can be derived instead of
   stored. Per-frame tables name repeated pattern endpoints and selector words by one byte.
3. **Transport** ([Pages in Map Packets](transport.md)). The frame is cut into pages of up to 12,256 bytes, each with a
   32-byte header and a CRC32, turned into six-bit symbols, and each symbol into one of 64 map colours. A page fills
   one 128x128 map. All pages of a frame travel as one bundle of map packets, which the game's own zlib compresses.
4. **Decode** ([Decoding in the Client](decoding.md)). The pack's text shaders move the page maps into a strip of the
   screen, and a post chain that runs while a glowing entity is drawn checks every page's CRC, finds the leaf of every
   8x8 cell, reconstructs every pixel from its leaf and the reference frame, and keeps the result in persistent render
   targets for the next frame.
5. **Show**. The same post chain ray-casts every pixel of the wall onto the screen plane and draws the picture over
   the maps, depth-tested against the world, so blocks and players in front of the wall hide it.

## Words Used on These Pages

| Word | Meaning |
|---|---|
| keyframe, P frame | A keyframe decodes on its own; a P frame predicts from a reference, the previous decoded frame by default |
| global vector | One translation for the whole frame, in half pixels, which SKIP and every other temporal leaf start from |
| superblock, root | A 32x32 block; the frame is a grid of them in raster order |
| leaf, record | A block that is not split, and the bytes its mode needs (SKIP has none) |
| descriptor | A leaf's or a split's mode and quantizer, one byte (`mode \| q << 5`) |
| lambda | The price of a bit in units of squared error: higher lambda, fewer bits and a worse picture |
| page, slot | A slice of a frame that fits one map; a slot is a place for one page in the strip on the client's screen |
| page frame | A hidden, glowing item frame behind the wall that holds a page map, so the client draws it and runs the post chain |
| anchor | Small patches in the wall's own maps that tell the shader where the wall is on the screen |

## What the Server Needs, and What the Client Needs

The server needs nothing but the plugin: the encoder is Java, with optional native kernels for the live presets
that MCAV ships for six platforms ([live encoding](live.md)). The client needs the resource pack, which the server
offers when a player looks at an MCV2 screen, and nothing else: no mod, no shader loader, no client setting. A player
who declines the pack sees the ordinary dithered maps of the same wall.

```{note}
MCV2 draws on walls of maps only. The block, chat, entity and scoreboard displays of MCAV have no maps to carry pages
and always show their own picture.
```
