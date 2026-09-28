(mcv2-performance)=
# Performance

What MCV2 costs and what it gives, in four parts: the picture it delivers for a rate, how fast it encodes live, what
decoding costs a player's GPU, and what a screen costs the server and the network. Every number on these pages comes
from a measurement committed with MCAV and says which: the research's result files and the measurements of MCAV's
port are on the [results page](results.md), the design and its evidence in the [design doc](../mcv2-integration.md).

## At a Glance

| Question | Answer | Page |
|---|---|---|
| Rate and quality of the shipped profile | `ship`: 3.46 map Mbit/s (2.21 after the game's compression) for VMAF 77.9 on the 1080p30 proxy | [Rate and Quality](quality.md) |
| Against AV1 | 15.8 VMAF points behind AV1 at the same nominal budget on the 1080p60 proxy; the four-codec chart shows where MCV2 wins and where it loses | [Rate and Quality](quality.md#four-codecs-on-one-chart) |
| Live encoding | `live` encodes a 1080p30 frame in 20.1 ms (quiet content) and 31.2 ms (gameplay) at the 95th percentile on 12 threads of a 6-core CPU; 1080p60 is not met | [Live Encoding](live.md) |
| What a viewer needs | 1.83 Mbit/s after compression for `live` 1080p30 on quiet content, 8.3 Mbit/s on fast gameplay | [Server and Network](server.md#bandwidth-per-viewer) |
| Client decode | 7.9 ms per new 1080p frame, 6.6 ms per rendered frame without new video, on an Intel UHD 630 | [Decoding Cost](client.md) |
| The server's tick | 20 TPS; one or two live 1080p screens raise the tick's p95 by 0.2 to 0.4 ms | [Server and Network](server.md#one-encoder-budget-per-server) |

## The Test Content, and What It Is Worth

Most measurements use a **deterministic procedural Minecraft proxy**, 1920x1080, one second of it: 60 frames at 60 fps,
and every other of those frames for 1080p30. It is not captured gameplay: its large flat regions and hard edges suit
palette coding and penalise the transform codecs, so comparisons on it flatter MCV2. Where it mattered, the
measurements were repeated on **real Minecraft gameplay**, an excerpt of Xiph's Twitch recording `MINECRAFT.y4m`,
which is the honest test, and the pages say which content every number is from. **VMAF** was trained on natural video;
on the synthetic proxy it compares codecs with each other, it is not an absolute scale.
