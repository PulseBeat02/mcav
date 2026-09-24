# Examples

This example resolves a YouTube video with yt-dlp, plays its separate video and audio streams in sync with the FFmpeg
player, shows the frames in a window, and plays the audio through the speakers. More examples live in the `src/test`
folders of every module.

```java
import java.awt.BorderLayout;
import java.awt.image.BufferedImage;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.capability.Capability;
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

  public static void main(final String[] args) throws Exception {
    final MCAVApi api = MCAV.api();
    try {
      api.install();
      final CompletableFuture<Boolean> ready = api.whenCapabilityReady(Capability.YT_DLP);
      final boolean available = ready.join(); // this application thread may block; never do this on a server or UI thread
      if (!available) {
        throw new IllegalStateException("yt-dlp is unavailable");
      }
      play();
    } finally {
      api.release();
    }
  }

  private static void play() throws Exception {
    final CountDownLatch closed = new CountDownLatch(1);
    final FutureTask<View> createWindow = new FutureTask<>(() -> createView(closed));
    SwingUtilities.invokeLater(createWindow);
    final View view = createWindow.get();
    final VideoPlayerMultiplexer player = VideoPlayer.ffmpeg();
    final DirectAudioOutput speakers = new DirectAudioOutput();
    try {
      final URI page = URI.create("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
      final UriSource source = UriSource.uri(page);
      final YTDLPParser parser = YTDLPParser.simple();
      final URLParseDump dump = parser.parse(source);
      final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
      final Format videoFormat = selector.getVideoSource(dump);
      final Format audioFormat = selector.getAudioSource(dump);
      final UriSource videoSource = videoFormat.toUriSource();
      final UriSource audioSource = audioFormat.toUriSource();

      speakers.start();
      final AudioPipelineStep audioPipeline = AudioPipelineStep.of(speakers);
      final FPSFilter fpsFilter = new FPSFilter();
      final VideoPipelineStepBuilder videoBuilder = PipelineBuilder.video();
      videoBuilder.then(fpsFilter);
      videoBuilder.then((image, metadata) -> {
        final BufferedImage frameImage = image.toBufferedImage();
        final ImageIcon icon = new ImageIcon(frameImage);
        final JLabel label = view.getLabel();
        SwingUtilities.invokeLater(() -> label.setIcon(icon));
        return false; // the frame is only read, not changed
      });
      final VideoPipelineStep videoPipeline = videoBuilder.build();
      final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
      videoCallback.attach(videoPipeline);
      final AudioAttachableCallback audioCallback = player.getAudioAttachableCallback();
      audioCallback.attach(audioPipeline);
      final boolean started = player.start(videoSource, audioSource);
      if (!started) {
        throw new IllegalStateException("Playback did not start");
      }
      closed.await(); // close the window to end this example
    } finally {
      try {
        player.release();
      } finally {
        try {
          speakers.release();
        } finally {
          final JFrame frame = view.getFrame();
          SwingUtilities.invokeLater(frame::dispose);
        }
      }
    }
  }

  // Called only on the Swing event-dispatch thread.
  private static View createView(final CountDownLatch closed) {
    final JFrame frame = new JFrame("Video Player");
    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    frame.setSize(1280, 720);
    final JLabel label = new JLabel();
    final BorderLayout layout = new BorderLayout();
    frame.setLayout(layout);
    frame.add(label, BorderLayout.CENTER);
    frame.addWindowListener(new WindowAdapter() {
      @Override
      public void windowClosed(final WindowEvent event) {
        closed.countDown();
      }
    });
    frame.setVisible(true);
    return new View(frame, label);
  }

  private static final class View {
    private final JFrame frame;

    private final JLabel label;

    private View(final JFrame frame, final JLabel label) {
      this.frame = frame;
      this.label = label;
    }

    private JFrame getFrame() {
      return this.frame;
    }

    private JLabel getLabel() {
      return this.label;
    }
  }
}
```
