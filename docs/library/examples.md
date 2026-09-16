# Examples

This example resolves a YouTube video with yt-dlp, plays its separate video and audio streams in sync with the FFmpeg
player, shows the frames in a window, and plays the audio through the speakers. More examples live in the `src/test`
folders of every module.

```java
import java.awt.BorderLayout;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.json.ytdlp.YTDLPParser;
import me.brandonli.mcav.json.ytdlp.format.Format;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy;
import me.brandonli.mcav.json.ytdlp.strategy.StrategySelector;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.VideoPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.audio.DirectAudioOutput;
import me.brandonli.mcav.media.player.pipeline.filter.video.FPSFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.uri.UriSource;

public final class YouTubeExample {

  public static void main(final String[] args) throws IOException {
    final MCAVApi api = MCAV.api();
    api.install();

    final JFrame frame = new JFrame("Video Player");
    frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    frame.setSize(1280, 720);
    final JLabel label = new JLabel();
    final BorderLayout layout = new BorderLayout();
    frame.setLayout(layout);
    frame.add(label, BorderLayout.CENTER);
    frame.setVisible(true);

    final URI page = URI.create("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
    final UriSource source = UriSource.uri(page);
    final YTDLPParser parser = YTDLPParser.simple();
    final URLParseDump dump = parser.parse(source);
    final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
    final Format videoFormat = selector.getVideoSource(dump);
    final Format audioFormat = selector.getAudioSource(dump);
    final UriSource videoSource = videoFormat.toUriSource();
    final UriSource audioSource = audioFormat.toUriSource();

    final DirectAudioOutput speakers = new DirectAudioOutput();
    speakers.start();
    final AudioPipelineStep audioPipeline = AudioPipelineStep.of(speakers);

    final FPSFilter fpsFilter = new FPSFilter();
    final VideoPipelineStepBuilder videoBuilder = PipelineBuilder.video();
    videoBuilder.then(fpsFilter);
    videoBuilder.then((image, metadata) -> {
      final BufferedImage frameImage = image.toBufferedImage();
      final ImageIcon icon = new ImageIcon(frameImage);
      SwingUtilities.invokeLater(() -> label.setIcon(icon));
      return false; // the frame is only read, not changed
    });
    final VideoPipelineStep videoPipeline = videoBuilder.build();

    final VideoPlayerMultiplexer player = VideoPlayer.ffmpeg();
    final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
    videoCallback.attach(videoPipeline);
    final AudioAttachableCallback audioCallback = player.getAudioAttachableCallback();
    audioCallback.attach(audioPipeline);
    player.start(videoSource, audioSource);

    final Thread shutdownHook = new Thread(() -> {
      player.release();
      speakers.release();
      api.release();
    });
    final Runtime runtime = Runtime.getRuntime();
    runtime.addShutdownHook(shutdownHook);
  }
}
```
