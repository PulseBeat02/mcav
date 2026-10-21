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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import me.brandonli.mcav.MCAV;
import me.brandonli.mcav.MCAVApi;
import me.brandonli.mcav.capability.Capability;
import me.brandonli.mcav.json.ytdlp.YtdlpParser;
import me.brandonli.mcav.json.ytdlp.format.Format;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import me.brandonli.mcav.json.ytdlp.strategy.FormatStrategy;
import me.brandonli.mcav.json.ytdlp.strategy.StrategySelector;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.builder.PipelineBuilder;
import me.brandonli.mcav.media.player.pipeline.builder.VideoPipelineStepBuilder;
import me.brandonli.mcav.media.player.pipeline.filter.audio.DirectAudioOutput;
import me.brandonli.mcav.media.player.pipeline.filter.video.FramesPerSecondFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.uri.UriSource;

public final class YouTubeExample {

  private static final String VIDEO_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

  private static final int WINDOW_WIDTH = 1280;
  private static final int WINDOW_HEIGHT = 720;

  public static void main(final String[] args) throws InterruptedException, ExecutionException {
    final MCAVApi api = MCAV.api();
    try {
      api.install();

      final YouTubeExample example = new YouTubeExample();
      example.play(api);
    } finally {
      api.release();
    }
  }

  private void play(final MCAVApi api) throws InterruptedException, ExecutionException {
    final CompletableFuture<Boolean> ready = api.whenCapabilityReady(Capability.YT_DLP);
    final boolean available = ready.join(); // this application thread may block; never do this on a server or UI thread
    if (!available) {
      final String message = "yt-dlp is unavailable!";
      throw new IllegalStateException(message);
    }

    final CountDownLatch closed = new CountDownLatch(1);
    final View view = this.openView(closed);
    try {
      this.playThroughSpeakers(view, closed);
    } finally {
      view.close();
    }
  }

  private View openView(final CountDownLatch closed) throws InterruptedException, ExecutionException {
    final FutureTask<View> createView = new FutureTask<>(() -> this.createView(closed));
    SwingUtilities.invokeLater(createView);
    return createView.get();
  }

  // Called only on the Swing event-dispatch thread.
  private View createView(final CountDownLatch closed) {
    final JFrame frame = new JFrame("Video Player");
    frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    frame.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
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

  private void playThroughSpeakers(final View view, final CountDownLatch closed) throws InterruptedException {
    final DirectAudioOutput speakers = new DirectAudioOutput();
    try {
      speakers.start();
      this.playUntilClosed(speakers, view, closed);
    } finally {
      speakers.release();
    }
  }

  private void playUntilClosed(final DirectAudioOutput speakers, final View view, final CountDownLatch closed) throws InterruptedException {
    final VideoPlayerMultiplexer player = VideoPlayer.ffmpeg();
    try {
      this.attachPipelines(player, speakers, view);
      this.startPlayback(player);
      closed.await(); // close the window to end this example
    } finally {
      player.release();
    }
  }

  private void attachPipelines(final VideoPlayerMultiplexer player, final DirectAudioOutput speakers, final View view) {
    final AudioPipelineStep audioPipeline = AudioPipelineStep.of(speakers);
    final AudioAttachableCallback audioCallback = player.getAudioAttachableCallback();
    audioCallback.attach(audioPipeline);

    final FramesPerSecondFilter framesPerSecondFilter = new FramesPerSecondFilter();
    final VideoPipelineStepBuilder videoBuilder = PipelineBuilder.video();
    videoBuilder.then(framesPerSecondFilter);
    videoBuilder.then(view::showFrame);
    final VideoPipelineStep videoPipeline = videoBuilder.build();
    final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
    videoCallback.attach(videoPipeline);
  }

  private void startPlayback(final VideoPlayerMultiplexer player) {
    final URI page = URI.create(VIDEO_URL);
    final UriSource source = UriSource.uri(page);
    final YtdlpParser parser = YtdlpParser.simple();
    final UrlParseDump dump = parser.parse(source);
    final StrategySelector selector = StrategySelector.of(FormatStrategy.BEST_QUALITY_AUDIO, FormatStrategy.BEST_QUALITY_VIDEO);
    final Format videoFormat = selector.getVideoSource(dump);
    final Format audioFormat = selector.getAudioSource(dump);
    final UriSource videoSource = videoFormat.toUriSource();
    final UriSource audioSource = audioFormat.toUriSource();
    final boolean started = player.start(videoSource, audioSource);

    if (!started) {
      final String message = "Playback did not start!";
      throw new IllegalStateException(message);
    }
  }

  private static final class View {

    private final JFrame frame;
    private final JLabel label;

    private View(final JFrame frame, final JLabel label) {
      this.frame = frame;
      this.label = label;
    }

    private boolean showFrame(final ImageBuffer image, final OriginalVideoMetadata metadata) {
      final BufferedImage frameImage = image.toBufferedImage();
      final ImageIcon icon = new ImageIcon(frameImage);
      SwingUtilities.invokeLater(() -> this.label.setIcon(icon));

      return false; // the frame is only read, not changed
    }

    private void close() {
      SwingUtilities.invokeLater(this.frame::dispose);
    }
  }
}
```
