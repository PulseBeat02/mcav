# Examples

Here is an example of how to use the `VLCPlayer`.

```java
import me.brandonli.mcav.json.ytdlp.YTDLPParser;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy;
import me.brandonli.mcav.json.ytdlp.strategy.StrategySelector;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.video.FPSFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.uri.UriSource;

import javax.swing.*;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URI;

@SuppressWarnings("all")
public final class MultiplexerInputExample {

  public static void main(final String[] args) throws Exception {
    final MCAVApi api = MCAV.api();
    api.install();

    final JFrame video = new JFrame("Video Player");
    video.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    video.setSize(1920, 1080);
    video.getContentPane().setBackground(Color.GREEN);

    final JLabel videoLabel = new JLabel();
    videoLabel.setPreferredSize(new Dimension(1800, 1000));
    videoLabel.setHorizontalAlignment(JLabel.CENTER);
    videoLabel.setVerticalAlignment(JLabel.CENTER);
    videoLabel.setBorder(new LineBorder(Color.BLACK, 3));

    video.setLayout(new BorderLayout());
    video.add(videoLabel, BorderLayout.CENTER);
    video.setVisible(true);

    final UriSource source = UriSource.uri(URI.create("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
    final YTDLPParser parser = YTDLPParser.simple();
    final URLParseDump dump = parser.parse(source);
    final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
    final UriSource videoFormat = selector.getVideoSource(dump).toUriSource();
    final UriSource audioFormat = selector.getAudioSource(dump).toUriSource();

    BufferedImage bufferedImage;
    ImageIcon icon;
    final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of((samples, metadata) -> {});
    final VideoPipelineStep videoPipelineStep = PipelineBuilder.video()
            .then(new FPSFilter())
            .then((samples, metadata) -> videoLabel.setIcon(new ImageIcon(samples.toBufferedImage())))
            .build();

    final VideoPlayerMultiplexer multiplexer = VideoPlayer.ffmpeg();

    final VideoAttachableCallback videoCallback = multiplexer.getVideoAttachableCallback();
    videoCallback.attach(videoPipelineStep);

    final AudioAttachableCallback audioCallback = multiplexer.getAudioAttachableCallback();
    audioCallback.attach(audioPipelineStep);

    multiplexer.start(videoFormat, audioFormat);

    Runtime.getRuntime()
            .addShutdownHook(
                    new Thread(() -> {
                      multiplexer.release();
                      api.release();
                    })
            );
  }
}

```