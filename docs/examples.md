## Examples

Here is an example of how to use the `VLCPlayer`.

```java
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.json.ytdlp.YTDLPParser;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy;
import me.brandonli.mcav.json.ytdlp.strategy.StrategySelector;
import me.brandonli.mcav.media.player.combined.VideoPlayer;
import me.brandonli.mcav.media.player.combined.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.combined.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.combined.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.combined.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.combined.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.UriSource;

import java.net.URI;

public final class VideoPlayer {

  public static void main(final String[] args) throws Exception {
    
    final MCAVApi api = MCAV.api();
    api.install();
    
    final UriSource source = UriSource.uri(URI.create("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
    final YTDLPParser parser = YTDLPParser.simple();
    final URLParseDump dump = parser.parse(source);
    final StrategySelector selector = StrategySelector.of(FormatStrategy.FIRST_AUDIO, FormatStrategy.FIRST_VIDEO);
    final UriSource videoFormat = selector.getVideoSource(dump).toUriSource();
    final UriSource audioFormat = selector.getAudioSource(dump).toUriSource();
    final AudioPipelineStep audioPipelineStep = AudioPipelineStep.NO_OP;
    final VideoPipelineStep videoPipelineStep = PipelineBuilder.video()
            .then(VideoFilter.GRAYSCALE)
            .then((samples, metadata) -> System.out.println("Received video samples: " + samples))
            .build();
    final VideoPlayerMultiplexer multiplexer = VideoPlayer.vlc();
    multiplexer.start(audioPipelineStep, videoPipelineStep, videoFormat, audioFormat);
    Thread.sleep(10000); // play for 10 seconds
    multiplexer.release();
    
    api.release();
  }
}
```