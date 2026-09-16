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
package me.brandonli.mcav.media.player.multimedia.cv;

import static me.brandonli.mcav.media.ResourceAssertions.assertThrowsWhileOpening;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import me.brandonli.mcav.media.Polling;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.ffmpeg.FFmpegDirectSource;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.testing.TestMedia;
import me.brandonli.mcav.utils.immutable.Dimension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link FFmpegPlayer} and {@link PlaybackSession} end to end with generated media files.
 */
final class FFmpegPlayerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(15L);
  private static final double NANOS_PER_SECOND = 1e9;

  @TempDir
  private Path directory;

  private static void attach(final FFmpegPlayer player, final VideoFilter video, final AudioFilter audio) {
    final VideoPipelineStep videoStep = VideoPipelineStep.of(video);
    final AudioPipelineStep audioStep = AudioPipelineStep.of(audio);
    final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
    final AudioAttachableCallback audioCallback = player.getAudioAttachableCallback();
    videoCallback.attach(videoStep);
    audioCallback.attach(audioStep);
  }

  private static void await(final String description, final BooleanSupplier condition) throws InterruptedException {
    Polling.awaitCondition(description, TIMEOUT, condition);
  }

  private static void awaitEnd(final AbstractVideoPlayerCV player) throws InterruptedException {
    await("playback finished", () -> !player.isPlaying());
  }

  private static Source file(final Path path) {
    return FileSource.path(path);
  }

  private static double secondsBetween(final long startNanos, final long endNanos) {
    return (endNanos - startNanos) / NANOS_PER_SECOND;
  }

  /**
   * Creates a video filter that records the width of every frame next to the width of the original video, and the
   * time every frame arrived at.
   */
  private static VideoFilter recordingFrames(final AtomicInteger frames, final List<String> sizes, final List<Long> frameTimes) {
    return (image, metadata) -> {
      final int width = image.getWidth();
      final int originalWidth = metadata.getVideoWidth();
      sizes.add(width + "/" + originalWidth);
      final long now = System.nanoTime();
      frameTimes.add(now);
      return frames.incrementAndGet() > 0;
    };
  }

  /**
   * Creates an audio filter that counts the bytes of samples in the output format of the player.
   */
  private static AudioFilter countingOutputSamples(final AtomicLong audioBytes) {
    return (samples, metadata) -> {
      final int sampleRate = metadata.getAudioSampleRate();
      final int remaining = samples.remaining();
      if (sampleRate == AudioFilter.SAMPLE_RATE) {
        audioBytes.addAndGet(remaining);
      }
      return true;
    };
  }

  private static VideoFilter recordingWidth(final AtomicInteger frames, final AtomicInteger width) {
    return (image, _) -> {
      final int frameWidth = image.getWidth();
      width.set(frameWidth);
      return frames.incrementAndGet() > 0;
    };
  }

  private static AudioFilter countingBytes(final AtomicLong audioBytes) {
    return (samples, _) -> {
      final int remaining = samples.remaining();
      return audioBytes.addAndGet(remaining) > 0;
    };
  }

  /**
   * Creates a player that never drops a frame as late, so how many frames a test sees does not depend on the load of
   * the machine.
   */
  private static FFmpegPlayer neverDroppingPlayer() {
    final FFmpegPlayer player = new FFmpegPlayer();
    player.neverDropLateFrames();
    return player;
  }

  @Test
  void playsTheWholeFileInRealTimeWithAudio() throws Exception {
    final PlaybackRecording recording = new PlaybackRecording();
    final FFmpegPlayer player = neverDroppingPlayer();
    recording.attachTo(player);

    final Path videoFile = TestMedia.video();
    final Source source = file(videoFile);
    try {
      final long start = System.nanoTime();
      final boolean started = player.start(source);
      awaitEnd(player);
      final long end = System.nanoTime();
      final double seconds = secondsBetween(start, end);
      recording.assertPlayedTheWholeFileInRealTime(started, seconds);
    } finally {
      player.release();
    }
  }

  /**
   * What a playback delivered to its pipelines: the frames with their sizes and arrival times, the bytes of audio in
   * the output format, and the errors.
   */
  private static final class PlaybackRecording {

    private final AtomicInteger frames = new AtomicInteger();
    private final AtomicLong audioBytes = new AtomicLong();
    private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> frameTimes = Collections.synchronizedList(new ArrayList<>());
    private final List<String> sizes = Collections.synchronizedList(new ArrayList<>());

    /**
     * Records the errors of a player and attaches pipelines that record its frames and audio.
     */
    void attachTo(final FFmpegPlayer player) {
      player.setExceptionHandler((_, error) -> this.errors.add(error));
      final VideoFilter video = recordingFrames(this.frames, this.sizes, this.frameTimes);
      final AudioFilter audio = countingOutputSamples(this.audioBytes);
      attach(player, video, audio);
    }

    /**
     * Checks that the five seconds of the test video played completely, in real time, and with their audio.
     */
    void assertPlayedTheWholeFileInRealTime(final boolean started, final double seconds) {
      final int frameCount = this.frames.get();
      final long bytes = this.audioBytes.get();
      final long firstFrame = this.frameTimes.getFirst();
      final long lastFrame = this.frameTimes.getLast();
      final double frameSpan = secondsBetween(firstFrame, lastFrame);
      final double frameRate = (frameCount - 1) / frameSpan;
      final boolean noErrors = this.errors.isEmpty();
      final Set<String> distinctSizes = Set.copyOf(this.sizes);
      final Set<String> expectedSizes = Set.of(TestMedia.VIDEO_WIDTH + "/" + TestMedia.VIDEO_WIDTH);

      assertTrue(started);
      assertTrue(noErrors, "errors: " + this.errors);
      assertTrue(frameCount > 100 && frameCount <= 150, "most of the 150 frames are shown and none twice, got " + frameCount);
      assertTrue(frameRate > 24.0 && frameRate < 36.0, "the frames are paced at about 30 per second, got " + frameRate);
      assertTrue(seconds > 4.5, "playback runs in real time, not faster, took " + seconds);
      assertTrue(bytes > 900_000 && bytes < 1_000_000, "five seconds of 48 kHz stereo, got " + bytes);
      assertEquals(expectedSizes, distinctSizes, "every frame has the size of the video");
    }
  }

  @Test
  void pausesResumesSeeksAndScales() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    final AtomicInteger width = new AtomicInteger();
    final FFmpegPlayer player = neverDroppingPlayer();
    final DimensionAttachableCallback dimension = player.getDimensionAttachableCallback();
    final Dimension size = Dimension.of(160, 120);
    dimension.attach(size);
    final VideoFilter video = recordingWidth(frames, width);
    attach(player, video, AudioFilter.NO_OP);

    final Path videoFile = TestMedia.video();
    final Source source = file(videoFile);
    try {
      final boolean started = player.start(source);
      assertTrue(started);
      await("frames arrived", () -> frames.get() > 10);
      assertPausingHoldsTheFrames(player, frames);
      assertResumingAndSeekingPlayOn(player, frames);
      awaitEnd(player);
      assertScaledAndStartsAgainOnlyWhenStartedAgain(player, source, width);
    } finally {
      player.release();
    }
  }

  /**
   * Checks the width of the last scaled frame, that a player at the end of its media plays again only when started
   * again, and that releasing it stops it.
   */
  private static void assertScaledAndStartsAgainOnlyWhenStartedAgain(
    final FFmpegPlayer player,
    final Source source,
    final AtomicInteger width
  ) {
    final int lastWidth = width.get();
    final boolean resumedAfterEnd = player.resume();
    final boolean restarted = player.start(source);
    final boolean playingAgain = player.isPlaying();
    final boolean released = player.release();
    final boolean playingAfterRelease = player.isPlaying();
    assertEquals(160, lastWidth);
    assertFalse(resumedAfterEnd, "resuming does not start the media over after its end");
    assertTrue(restarted, "starting the source again plays it again");
    assertTrue(playingAgain);
    assertTrue(released);
    assertFalse(playingAfterRelease);
  }

  private static void assertPausingHoldsTheFrames(final FFmpegPlayer player, final AtomicInteger frames) throws InterruptedException {
    final boolean paused = player.pause();
    assertTrue(paused);
    // the frame that was being rendered when pausing may still arrive, so the count is taken once it settled
    Thread.sleep(200);
    final int pausedAt = frames.get();
    Thread.sleep(500);
    final int stillPaused = frames.get();
    assertEquals(pausedAt, stillPaused, "no frames while paused");
  }

  private static void assertResumingAndSeekingPlayOn(final FFmpegPlayer player, final AtomicInteger frames) throws InterruptedException {
    final int pausedAt = frames.get();
    final boolean resumed = player.resume();
    assertTrue(resumed);
    await("frames resumed", () -> frames.get() > pausedAt + 10);

    final boolean seeked = player.seek(4_000L);
    assertTrue(seeked);
    await("the position jumped near the end", () -> player.getPositionMillis() >= 3_900L);
  }

  @Test
  void takesTheAudioFromASeparateSource() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    final AtomicLong audioBytes = new AtomicLong();
    final FFmpegPlayer player = neverDroppingPlayer();
    final AudioFilter audioCounter = countingBytes(audioBytes);
    attach(player, (_, _) -> frames.incrementAndGet() > 0, audioCounter);

    // the video has no audio track, so every sample must come from the separate audio file
    final Path silent = TestMedia.silentVideo();
    final Path sound = TestMedia.audio(2.0);
    final Source video = file(silent);
    final Source audio = file(sound);
    try {
      final boolean started = player.start(video, audio);
      assertTrue(started);
      await("frames arrived", () -> frames.get() > 20);
      await("audio of the separate source arrived", () -> audioBytes.get() > 150_000);
    } finally {
      player.release();
    }
  }

  @Test
  void playsAudioFilesWithoutAPicture() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    final AtomicLong audioBytes = new AtomicLong();
    final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    final FFmpegPlayer player = neverDroppingPlayer();
    player.setExceptionHandler((message, error) -> errors.add(message + ": " + error));
    final AudioFilter audioCounter = countingBytes(audioBytes);
    attach(player, (_, _) -> frames.incrementAndGet() > 0, audioCounter);

    final Path sound = TestMedia.audio(2.0);
    final Source source = file(sound);
    try {
      final boolean started = player.start(source);
      awaitEnd(player);
      final int frameCount = frames.get();
      final long bytes = audioBytes.get();
      final boolean noErrors = errors.isEmpty();
      assertTrue(started);
      assertEquals(0, frameCount);
      assertTrue(bytes > 300_000, "audio " + bytes);
      assertTrue(noErrors, "audio files must play: " + errors);
    } finally {
      player.release();
    }
  }

  @Test
  void playsFfmpegInputsWithTheirFormat() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    final AtomicInteger width = new AtomicInteger();
    final FFmpegPlayer player = neverDroppingPlayer();
    final VideoFilter video = recordingWidth(frames, width);
    attach(player, video, AudioFilter.NO_OP);

    final FFmpegDirectSource generator = FFmpegDirectSource.mrl("testsrc=size=64x48:rate=30:duration=1", "lavfi");
    try {
      final boolean started = player.start(generator);
      awaitEnd(player);
      final int frameCount = frames.get();
      final int lastWidth = width.get();
      assertTrue(started);
      assertTrue(frameCount > 20, "frames " + frameCount);
      assertEquals(64, lastWidth);
    } finally {
      player.release();
    }
  }

  @Test
  void reportsFilesThatCannotBeOpened() {
    final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
    final FFmpegPlayer player = neverDroppingPlayer();
    player.setExceptionHandler((_, error) -> errors.add(error));
    final Path missing = this.directory.resolve("missing.mp4");
    final Source source = file(missing);
    try {
      final boolean started = player.start(source);
      final boolean reported = !errors.isEmpty();
      assertFalse(started);
      assertTrue(reported);
      assertThrowsWhileOpening(NullPointerException.class, () -> player.createFrameGrabber(null));
    } finally {
      player.release();
    }
  }
}
