(mcv2-decoding)=
# Decoding in the Client

The client is an unmodified Minecraft 26.3 with one resource pack. A resource pack may replace the game's **core
shaders** {cite}`mcwikishaders`, which draw everything, and its **post effects**, chains of full-screen passes with render targets of their
own. MCV2's decoder is built from exactly those two things, in GLSL 330, the language every core shader is written in:
no client mod, no shader loader, nothing installed. How it was proven on the real client, step by step, is in the
[design doc, section 5](../mcv2-integration.md#5-client-shader-architecture-resource-pack).

```{figure} figures/decode.png
:name: mcv2-decode-flow
:alt: Flow chart of the client: map packets, the glowing page frame that triggers the post chain, the twelve passes, the persistent reference targets, and what happens to a lost page.

What the client does with a frame. The two copies into persistent targets are how a P frame finds its reference on
the next rendered frame. A page that is lost or damaged stops only its own frame and the frames that predict from it;
the client keeps showing the last picture it decoded, and never draws a guess.
```

```{warning}
The chart describes the path proven on the **vanilla client with its OpenGL backend**: in-game captures on Minecraft
26.3 equal the reference decoder byte for byte, and the chain runs bit-exactly on an Intel UHD 630 and on Mesa's
llvmpipe. The dashed box is not proven: the Vulkan backend (only tried on a software renderer, where compiling the
pack's shaders took over ten minutes), NVIDIA, AMD and Apple GPUs, and Windows and macOS clients. The later 26.3
client matrix passed with Sodium 0.9.2 and Iris 1.11.7 with shaders disabled on Fabric and NeoForge, using llvmpipe.
Active Iris shader packs and improved transparency can bypass the decoder; see [compatibility](using.md#troubleshooting).
```

## The Hook: a Glowing Entity

The game runs the post effect `post_effect/entity_outline.json` after the main pass whenever a glowing entity was drawn,
and that chain may read and write the game's main target. Its render targets may be declared `"persistent": true` with
a fixed size, which the game keeps from one frame to the next and only drops on a resource reload. These two facts are
the whole foundation: the first gives the decoder a place to run on every frame, the second a place to keep the
previous picture.

So every MCV2 screen hides **page frames** two blocks behind its wall: invisible, invulnerable item frames holding the
page maps, glowing on a team of their own, shown only to players whose pack is loaded. While one is in view, the chain
runs. Their outline colour (dark purple by default) is removed from the outline target by the last pass, so no glow is
ever seen. Black cannot be used: an outline colour of 0 means "no outline", and the chain would never run.

## The Strip and the Anchors

The pack's `core/text.vsh` sees every map the client draws. A **page map**, recognised by its `MCP1` header, has its quad
moved to an exact strip of rows at the top of the screen: slot `p` starts `p x R` rows down, where `R` is the number of
screen rows a page needs (three at 1920 pixels wide). `core/text.fsh` writes its symbols through unchanged, four to a
pixel. Every other map, sign, name and interface text is drawn exactly as vanilla draws it.

The screen's own maps carry small **anchor** patches in their top rows: an eight-symbol signature, the frame's column
and row, the wall's size and facing, and a checksum. The vertex shader of any visible anchor writes the wall's corner,
its right and down vectors in view space and the projection matrix into its screen's descriptor row, which follows
the slots of every screen of the pack. That is how
the post chain learns where the wall is, with no marker colour that anything in the world could imitate.

## Twelve Passes

1. `mcv2_bytes`: the strip back into the frame's bytes.
2. `mcv2_crc`: the CRC of every 192-byte chunk of every slot, one fragment per chunk.
3. `mcv2_pages`: every slot's header, and its CRC32 chained from the chunks and compared.
4. `mcv2_status`: the decision for this rendered frame.
5. `mcv2_resolve`: one fragment per 8x8 cell finds the cell's leaf.
6. `mcv2_decode`: one fragment per pixel reconstructs the pixel.
7. Copy into the persistent target `mcv2_previous`.
8. On a keyframe, copy into the persistent target `mcv2_key`.
9. `mcv2_state`, persistent: the shown flag, the last id, the key id and a decoded-frame counter.
10. `mcv2_view`: the anchors' 28 floats and the box of pixels the wall can cover, once per frame.
11. `mcv2_screen`: every pixel in the box is ray-cast onto the wall's plane and depth-tested against the scene; hits
    take the picture's pixel, and the strip is covered with the scene row below it.
12. `mcv2_outline`: the page frames' colour is removed from the outline target. Vanilla's outline passes follow,
    unchanged, so every other glowing entity keeps its outline.

The decode proper (passes 5 to 9) runs once per video frame, on the first rendered frame whose strip carries a complete
new frame. On every other rendered frame, pass 4 decides there is nothing new, and a copy stands in for the decode.

## One Fragment, One Pixel

The format exists so that this can work: every output pixel of the decode pass is one fragment shader invocation, run
in parallel with all the others, which may read any texel of its inputs but cannot see what another fragment computes
and cannot scan the frame from its start. For one pixel `(x, y)` of the picture it does this:

1. **Find the leaf** (pass 5, per 8x8 cell): from the frame's bytes, the walk of
   [the block tree page](block-tree.md#descriptors-and-addressing): the superblock from `(x / 32, y / 32)`, its
   presence bit, its descriptor by checkpoint and popcounts, at most two descents through splits by counting splits,
   and the record's offset by the walk checkpoint and at most seven record lengths. Leaves are at least 8x8 and
   aligned, so all 64 pixels of a cell share this answer; MCAV's resolve pass computes it once per cell and stores the
   leaf's descriptor word and size in a texture (the research's single-pass decoder did it at every pixel).
2. **Short path.** On a P frame, a SKIP leaf is the reference at the global vector, and a MOTION leaf the reference at
   the global vector plus the two motion bytes: one prediction, averaging one, two or four reference pixels for half
   pixel positions.
3. **Reconstruct.** Every other leaf reads only its own record, and the tables it names: a SOLID colour; a PALETTE
   colour chosen by the pixel's selector bit; a PATTERN colour chosen by the bit of the pixel's column or row, its
   endpoints and selector word looked up by index when the frame has tables; a bilinear grid value from the four nodes
   around the pixel; for residual and compact leaves, the prediction plus the residual scaled by `2^q`, converted from
   YCoCg.
4. **Quantize** to RGB8, `floor(clamp(v, 0, 255) + 0.5)` per channel, in the reference's order of float operations,
   so the picture is bit-exact with the Java decoder and the reference.

No step loops over other pixels, and every loop has a constant bound, so a malformed frame cannot make a fragment do
unbounded work or read outside its textures. Everything the decoder needs from the previous frame is one texture.

## Two Reference Frames, One Pipeline

A P frame predicts from the previous decoded frame, but core shaders keep no state from one frame to the next. The
mcv2 stage settled how a vanilla client can hold a reference anyway, and measured the choices on the 30 frames of the
1080p30 proxy at the ship lambda ([design doc §4](../mcv2-integration.md#4-the-reference-frame-problem)):

| Model | The client keeps | Rate at VMAF 77.93 | Robustness |
|---|---|---|---|
| **A**, previous frame (the default) | one persistent target | 3.458 map Mbit/s | every P frame must be decoded, in order; a missed frame is repaired by the next keyframe |
| **B**, last keyframe | the decoded keyframe | about 5.97 (+73%) | any render rate works: every P frame decodes on its own against the keyframe |
| **C**, raw reference frames sent as maps | nothing | about 33 Mbit/s at one refresh per 2 s | far over any budget |
| **D**, all intra | nothing | about 8.43 (+144%) | no state at all |

Both council members of that stage rejected A as the default for its robustness: a client that renders fewer frames
than the video has, looks away, or reloads its resources loses frames and with them the chain. MCAV ships A anyway,
because B and D cost 73% and 144% more rate for the same picture, and the pack decodes all three: it keeps **two
persistent references**, the previous decoded frame and the last decoded keyframe, and a P frame's reference id picks
which one it predicts from. So the server can choose per screen: A by default, B for viewers whose clients draw fewer
frames than the video (`LIVE_KEYFRAME` and `KEYFRAME` in the plugin), D (`INTRA`) where no state may be assumed.

A frame is decoded when every page is valid, it is newer than the last decoded frame, and it is a keyframe or predicts
from a reference the client holds. A keyframe is accepted whenever its id differs from the last one, so a stream that
starts over (a loop, a server restart) is not refused as older.

## Lost, Late and Damaged Pages

TCP does not lose packets, but a client can still miss a frame: it renders fewer frames than the video has and the next
frame's pages overwrite the slots first, it looks away (the page frames are culled), or it reloads its resources. A
page that fails its CRC or is missing means the frame is not decoded. Under model A the next P frames predict from the
frame the client does not have, so they are not decoded either; the client keeps showing the last picture it decoded
until the next keyframe, at most the key interval: 2 seconds for `ship`, 4 seconds for `live` at 30 fps. Offline, the
pack's chain run with every seventh frame dropped waited for a frame it could decode and never decoded a wrong one
(`tools/mcv2/shader_check.py --drop 7`). The server helps: a viewer who starts watching, or whose connection fell behind,
is sent nothing until the next keyframe, and every frame from it.

## The Resource Pack

The pack is built on the server by `Mcv2Pack` and served by `Mcv2PackServer`, for pack format 97 (Minecraft 26.3) {cite}`mcwikipackformat`. Its
shader sources are fixed and ship in the plugin; what depends on the server is generated into it: the video sizes, page
slots and stream ids of the screens, the page frames' outline colour, the transport alphabet (the RGB of map colours 4
to 67, from the server's own map colour table, which is the client's) and the residual books, from the same bytes the
Java decoder uses. Its id is derived from its SHA-1, so an unchanged pack is never sent twice.

One pack decodes **every MCV2 screen of the server**, up to eight at once, each in a slot with its own video size,
stream id and page maps. A screen takes a free slot of its size, so screens of the sizes the pack already has start and
stop without any client reloading; only a screen of a new size changes the pack. It is **optional and additive**: it is
offered with `required: false`, replaces no other pack, and a player who declines keeps the dithered maps. Loading it
reloads the client's resources, a hitch that took 1.1 to 3.1 seconds from the offer in the lab. Where it is served from
(the game's own port, an HTTP port of its own, or an upload) is a setting ([Using MCV2](using.md#the-resource-pack)).

```{note}
The pack replaces vanilla's `core/text.vsh`, `core/text.fsh` and `post_effect/entity_outline.json`. A pack loaded after
it that replaces the same files wins, and MCV2 screens then show nothing; a server pack sent at join is loaded first,
so MCV2 wins over it. Its copies are vanilla's plus the decoder, so a server that needs its own text or outline shaders
can merge them into MCV2's (`mcav/mcv2/pack`, and `mcav/mcv2/chain.json` for the outline chain, in the
`mcav-bukkit` jar, which the plugin downloads into the server's `libraries/mcav` folder).
```

Minecraft 26.3 compiles every shader through shaderc into SPIR-V and, on OpenGL, back into GLSL 330 through
SPIRV-Cross. The pack was ported to that path (`#include`, explicit stage locations, `gl_VertexIndex`, guarded includes)
and proven bit-exact again in the real 26.3 client; it copies its own persistent targets with a texel fetch instead of
26.3's `post/blit`, which loses a level of bright values on every rendered frame
([design doc §5.2](../mcv2-integration.md#52-minecraft-263-2026-09-27)).

## Limits

- **Lighting.** The picture is drawn at full brightness, like a map in a glow item frame, because the post chain has
  no light level for the wall.
- **Resource reloads** drop the persistent targets; the picture returns with the next keyframe.
- **Iris shader packs and improved transparency** can bypass the decoder. Sodium and Iris with shaders disabled
  passed the 26.3 software-rendered client matrix. With improved transparency the text shaders draw into its targets,
  where the pack discards its fragments, so the screen shows nothing new. Vulkan and hardware-driver coverage remain
  unverified; see [compatibility](using.md#troubleshooting).
- **A client that renders fewer frames than the video** decodes at most one video frame per rendered frame; under
  model A a missed frame costs the picture until the next keyframe.
