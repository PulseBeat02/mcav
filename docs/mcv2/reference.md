(mcv2-reference)=
# Reference

The documents behind this chapter. The chapter explains; these define and prove. None of them is duplicated here.

| Document | What it holds |
|---|---|
| [MCV2 bitstream and transport](format.md) | The specification: every field of a frame and a page, every rule the parser checks and the error it raises, the reconstruction's exact arithmetic |
| [MCV2 encoder](encoder.md) | The reference search and the live searches, the shipped settings in the reference's own terms, and every table the encoder and decoder load, with its provenance |
| [MCV2 conformance](conformance.md) | How MCAV proves its decoder, encoder, native kernels and shaders equal the reference: the test vectors, what each proves, and how to regenerate them |
| [MCV2 results](results.md) | Every measurement the chapter cites, with its method: the research frontier, the shipped profiles, the ceiling and Round T, the AV1 references, the codec comparison, and the measurements of MCAV's own port |
| [MCV2 in mcav: integration design](../mcv2-integration.md) | The design as built, with the evidence for every decision: threading, the reference-frame problem, the client shaders, Minecraft 26.3, the server integration, transport, far viewers, server viability, the live profile, the native kernels, and every wall of maps in the plugin |
| [MCV2 handover](HANDOVER.md) | For whoever maintains MCV2 next: what exists, why, the review passes, known issues and open items |
| [Testing MCV2 on your own client](../mcv2-testing.md) | The procedure for a real client and GPU, and the numbers to compare |

## Code and Data

- The codec, transport, encoder and kernels: `mcav-bukkit`, package `me.brandonli.mcav.bukkit.media.mcv2` and its
  `encode` and `transport` packages; the resource pack's sources in `mcav-bukkit/src/main/resources/mcav/mcv2/pack`;
  the native kernels' sources in `mcav-bukkit/src/main/native/mcv2`.
- The research's reference decoder and encoder, unchanged at commit `85445433aeb9f8a35a5ce528d47d8829976d1401`, in
  `tools/mcv2-reference`, and the scripts that tie MCAV to it in `tools/mcv2`.
- The research's result files, copied unchanged, in `docs/mcv2/data`, and the measurements made for this chapter in
  `docs/mcv2/data/codec_curves.json`, with the scripts that made them in `docs/mcv2/measure`.
- The chapter's figures and their sources in `docs/mcv2/figures`: every flow chart is a Graphviz file rendered by
  `render.sh`, and every chart is drawn by `charts.py` from the committed data.

## Sources

The outside sources the MCV2 pages cite.

```{bibliography}
```
