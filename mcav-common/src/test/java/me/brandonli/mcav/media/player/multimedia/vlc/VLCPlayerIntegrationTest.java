/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package me.brandonli.mcav.media.player.multimedia.vlc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import me.brandonli.mcav.media.Polling;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.PlayerException;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.multimedia.VideoPlayer;
import me.brandonli.mcav.media.player.multimedia.VideoPlayerMultiplexer;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.testing.TestMedia;
import me.brandonli.mcav.utils.immutable.Dimension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plays generated media through the VLC installed on the machine with {@link VLCPlayer}. The tests are skipped when
 * VLC is not installed.
 */
final class VLCPlayerIntegrationTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  private static final long HALF_A_SECOND_OF_AUDIO_BYTES = 96_000L;

  @TempDir
  private static Path directory;

  private final VideoPlayerMultiplexer player = VideoPlayer.vlc();
  private final AtomicInteger frames = new AtomicInteger();
  private final AtomicReference<int[]> lastFrame = new AtomicReference<>();
  private final AtomicReference<OriginalVideoMetadata> videoMetadata = new AtomicReference<>();
  private final AtomicInteger frameWidth = new AtomicInteger();
  private final AtomicInteger frameHeight = new AtomicInteger();
  private final AtomicLong audioBytes = new AtomicLong();
  private final AtomicInteger misalignedChunks = new AtomicInteger();
  private final AtomicInteger loudestSample = new AtomicInteger();
  private final AtomicReference<OriginalAudioMetadata> audioMetadata = new AtomicReference<>();
  private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
  private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

  @BeforeAll
  static void loadVlc() {
    VlcTestSupport.assumeVlc();
  }

  VLCPlayerIntegrationTest() {
    this.player.setExceptionHandler((message, error) -> {
        this.messages.add(message);
        this.errors.add(error);
      });
    final VideoPipelineStep videoStep = VideoPipelineStep.of(this::recordFrame);
    final AudioPipelineStep audioStep = AudioPipelineStep.of(this::recordSamples);
    final VideoAttachableCallback videoCallback = this.player.getVideoAttachableCallback();
    final AudioAttachableCallback audioCallback = this.player.getAudioAttachableCallback();
    videoCallback.attach(videoStep);
    audioCallback.attach(audioStep);
  }

  @AfterEach
  void releasePlayer() {
    this.player.release();
  }

  private boolean recordFrame(final ImageBuffer image, final OriginalVideoMetadata metadata) {
    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] pixels = image.getPixels();
    final int[] copy = pixels.clone();
    this.frameWidth.set(width);
    this.frameHeight.set(height);
    this.lastFrame.set(copy);
    this.videoMetadata.set(metadata);
    this.frames.incrementAndGet();
    return false;
  }

  private boolean recordSamples(final ByteBuffer samples, final OriginalAudioMetadata metadata) {
    final int remaining = samples.remaining();
    if (remaining % AudioFilter.FRAME_SIZE != 0) {
      this.misalignedChunks.incrementAndGet();
    }

    final ByteBuffer view = samples.duplicate();
    view.order(ByteOrder.LITTLE_ENDIAN);
    while (view.remaining() >= Short.BYTES) {
      final short sample = view.getShort();
      final int amplitude = Math.abs(sample);
      this.loudestSample.accumulateAndGet(amplitude, Math::max);
    }
    this.audioMetadata.set(metadata);
    this.audioBytes.addAndGet(remaining);
    return false;
  }

  private long totalActivity() {
    final long frameCount = this.frames.get();
    final long audioByteCount = this.audioBytes.get();
    return frameCount + audioByteCount;
  }

  /**
   * Waits until neither frames nor samples arrived for 400 ms, and fails if they keep arriving for ten seconds.
   */
  private void awaitSilence() throws InterruptedException {
    final QuietPeriod quiet = new QuietPeriod(this::totalActivity, 400L);
    Polling.awaitCondition("neither frames nor samples arrived for 400 ms", TIMEOUT, quiet::hasElapsed);
  }

  private void awaitFrames(final int count) throws InterruptedException {
    final boolean arrived = Polling.pollUntil(TIMEOUT, () -> this.frames.get() >= count);
    final int arrivedFrames = this.frames.get();
    assertTrue(arrived, "timed out waiting for " + count + " frames, got " + arrivedFrames);
  }

  private void awaitHalfASecondOfAudio(final String description) throws InterruptedException {
    Polling.awaitCondition(description, TIMEOUT, () -> this.audioBytes.get() >= HALF_A_SECOND_OF_AUDIO_BYTES);
  }

  private boolean startTestVideo() {
    final Path path = TestMedia.video();
    return this.startFile(path);
  }

  private boolean startFile(final Path path) {
    final Source source = FileSource.path(path);
    return this.player.start(source);
  }

  private void assertFramesOfTheTestVideo() {
    final int width = this.frameWidth.get();
    final int height = this.frameHeight.get();
    final OriginalVideoMetadata video = this.videoMetadata.get();
    final int metadataWidth = video.getVideoWidth();
    final int metadataHeight = video.getVideoHeight();
    assertEquals(TestMedia.VIDEO_WIDTH, width);
    assertEquals(TestMedia.VIDEO_HEIGHT, height);
    assertEquals(TestMedia.VIDEO_WIDTH, metadataWidth);
    assertEquals(TestMedia.VIDEO_HEIGHT, metadataHeight);
  }

  private void assertAudioOfTheTestVideo() {
    final OriginalAudioMetadata audio = this.audioMetadata.get();
    final int sampleRate = audio.getAudioSampleRate();
    final int channels = audio.getAudioChannels();
    final int misaligned = this.misalignedChunks.get();
    final int loudest = this.loudestSample.get();
    assertEquals(AudioFilter.SAMPLE_RATE, sampleRate);
    assertEquals(AudioFilter.CHANNELS, channels);
    assertEquals(0, misaligned, "every chunk holds whole stereo frames");
    assertTrue(loudest > 1_000, "the sine tone is audible, loudest sample " + loudest);
  }

  @Test
  void playsTheFramesAndSamplesOfAFile() throws Exception {
    final boolean started = this.startTestVideo();
    this.awaitFrames(20);
    this.awaitHalfASecondOfAudio("half a second of audio arrived");
    assertTrue(started);
    this.assertFramesOfTheTestVideo();
    this.assertAudioOfTheTestVideo();
    final boolean noErrors = this.errors.isEmpty();
    final boolean released = this.player.release();
    assertTrue(noErrors, "errors: " + this.messages + " " + this.errors);
    assertTrue(released);
  }

  @Test
  void deliversTheColorsAndTheVisibleSizeOfTheVideo() throws Exception {
    final Path red = VlcTestSupport.solidVideo(directory, "red", 100, 60);
    final boolean started = this.startFile(red);
    assertTrue(started);
    this.awaitFrames(5);

    final int[] pixels = this.lastFrame.get();
    final int center = pixels[30 * 100 + 50];
    final int redChannel = (center >> 16) & 0xFF;
    final int greenChannel = (center >> 8) & 0xFF;
    final int blueChannel = center & 0xFF;
    final String centerHex = Integer.toHexString(center);
    final int width = this.frameWidth.get();
    final int height = this.frameHeight.get();
    assertEquals(100, width, "the visible width, not the padded decoder width");
    assertEquals(60, height, "the visible height, not the padded decoder height");
    assertTrue(redChannel > 200 && greenChannel < 60 && blueChannel < 60, "expected red but got " + centerHex);
  }

  @Test
  void pausesAndResumesPlayback() throws Exception {
    final boolean started = this.startTestVideo();
    assertTrue(started);
    this.awaitFrames(10);

    final boolean paused = this.player.pause();
    final boolean pausedAgain = this.player.pause();
    assertTrue(paused);
    assertFalse(pausedAgain, "already paused");
    // neither frames nor samples arrive while paused
    this.awaitSilence();

    final int pausedFrames = this.frames.get();
    final boolean resumed = this.player.resume();
    final boolean resumedAgain = this.player.resume();
    assertTrue(resumed);
    assertFalse(resumedAgain, "not paused anymore");
    this.awaitFrames(pausedFrames + 10);
  }

  @Test
  void resumesRightAfterPausing() throws Exception {
    final boolean started = this.startTestVideo();
    assertTrue(started);
    this.awaitFrames(5);
    final boolean paused = this.player.pause();
    final boolean resumed = this.player.resume();
    assertTrue(paused);
    assertTrue(resumed, "VLC still reports playing, but the player was paused");
    final int resumedAt = this.frames.get();
    this.awaitFrames(resumedAt + 15);
  }

  @Test
  void seeksNearTheEndAndStopsThere() throws Exception {
    final boolean started = this.startTestVideo();
    assertTrue(started);
    this.awaitFrames(5);

    final long seekedAt = System.nanoTime();
    final boolean seeked = this.player.seek(4_000L);
    assertTrue(seeked);
    // the video ends about a second after the seek, so frames stop arriving long before the remaining four seconds
    final QuietPeriod quiet = new QuietPeriod(this.frames::get, 700L);
    Polling.awaitCondition("frames stopped arriving after the seek", TIMEOUT, quiet::hasElapsed);

    final long lastFrameAt = quiet.getLastChangeNanos();
    final long playedMillis = TimeUnit.NANOSECONDS.toMillis(lastFrameAt - seekedAt);
    final boolean pausedAfterTheEnd = this.player.pause();
    final boolean seekedAfterTheEnd = this.player.seek(0L);
    assertTrue(playedMillis < 2_500L, "played " + playedMillis + " ms after seeking to 4 of 5 seconds");
    assertFalse(pausedAfterTheEnd, "nothing plays after the end");
    assertFalse(seekedAfterTheEnd, "media that has ended cannot be seeked");
  }

  @Test
  void takesTheAudioFromASeparateSource() throws Exception {
    // the video has no audio track, so every sample must come from the separate audio file
    final Path silent = TestMedia.silentVideo();
    final Path sound = TestMedia.audio(2.0);
    final Source video = FileSource.path(silent);
    final Source audio = FileSource.path(sound);
    final boolean started = this.player.start(video, audio);
    assertTrue(started);
    this.awaitFrames(10);
    this.awaitHalfASecondOfAudio("audio of the separate source arrived");

    final boolean paused = this.player.pause();
    final boolean resumed = this.player.resume();
    final boolean noErrors = this.errors.isEmpty();
    assertTrue(paused);
    assertTrue(resumed);
    assertTrue(noErrors, "errors: " + this.messages + " " + this.errors);
  }

  @Test
  void reportsFilesThatCannotBeOpened() {
    final Path missing = directory.resolve("missing.mp4");
    final boolean started = this.startFile(missing);
    final boolean noErrors = this.errors.isEmpty();
    assertFalse(started);
    assertFalse(noErrors);
    final Throwable error = this.errors.getFirst();
    final boolean released = this.player.release();
    assertInstanceOf(PlayerException.class, error);
    assertTrue(released);
  }

  @Test
  void scalesFramesToTheAttachedSize() throws Exception {
    final DimensionAttachableCallback dimension = this.player.getDimensionAttachableCallback();
    final Dimension size = Dimension.of(160, 120);
    dimension.attach(size);
    final boolean started = this.startTestVideo();
    assertTrue(started);
    this.awaitFrames(5);

    final OriginalVideoMetadata metadata = this.videoMetadata.get();
    final int width = this.frameWidth.get();
    final int height = this.frameHeight.get();
    final int metadataWidth = metadata.getVideoWidth();
    assertEquals(160, width);
    assertEquals(120, height);
    assertEquals(TestMedia.VIDEO_WIDTH, metadataWidth);
  }

  @Test
  void deliversNothingAfterRelease() throws Exception {
    final boolean started = this.startTestVideo();
    assertTrue(started);
    this.awaitFrames(5);

    final boolean released = this.player.release();
    final int releasedFrames = this.frames.get();
    final long releasedAudio = this.audioBytes.get();
    this.awaitSilence();
    final int framesAfterSilence = this.frames.get();
    final long audioAfterSilence = this.audioBytes.get();
    final boolean releasedAgain = this.player.release();
    final boolean restarted = this.startTestVideo();
    assertTrue(released);
    assertEquals(releasedFrames, framesAfterSilence, "no pipeline runs after release returned");
    assertEquals(releasedAudio, audioAfterSilence);
    assertFalse(releasedAgain);
    assertFalse(restarted, "a released player cannot start");
  }

  @Test
  void switchesToAnotherSource() throws Exception {
    final boolean started = this.startTestVideo();
    assertTrue(started);
    this.awaitFrames(5);

    final Path green = VlcTestSupport.solidVideo(directory, "lime", 64, 48);
    final boolean switched = this.startFile(green);
    assertTrue(switched);
    Polling.awaitCondition("frames of the new source arrived", TIMEOUT, () -> this.frameWidth.get() == 64);
    final int switchedAt = this.frames.get();
    this.awaitFrames(switchedAt + 5);
    final int height = this.frameHeight.get();
    assertEquals(48, height);
  }

  /**
   * Tracks when a steadily growing count last changed, to wait until it stays the same for a while. The count is only
   * read by the thread that waits.
   */
  private static final class QuietPeriod {

    private final LongSupplier activity;
    private final long quietNanos;

    private long lastActivity;
    private long lastChangeNanos;

    QuietPeriod(final LongSupplier activity, final long quietMillis) {
      this.activity = activity;
      this.quietNanos = TimeUnit.MILLISECONDS.toNanos(quietMillis);
      this.lastActivity = activity.getAsLong();
      this.lastChangeNanos = System.nanoTime();
    }

    boolean hasElapsed() {
      final long current = this.activity.getAsLong();
      final long now = System.nanoTime();
      if (current != this.lastActivity) {
        this.lastActivity = current;
        this.lastChangeNanos = now;
      }
      return now - this.lastChangeNanos >= this.quietNanos;
    }

    long getLastChangeNanos() {
      return this.lastChangeNanos;
    }
  }
}
