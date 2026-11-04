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

import java.nio.ByteBuffer;
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
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.metadata.OriginalAudioMetadata;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.VideoFilter;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.ffmpeg.FfmpegDirectSource;
import me.brandonli.mcav.media.source.file.FileSource;
import me.brandonli.mcav.testing.TestMedia;
import me.brandonli.mcav.utils.immutable.Dimension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FfmpegPlayerTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(15L);
  private static final double NANOSECONDS_PER_SECOND = 1e9;

  private static final String SIZE_SEPARATOR = "/";

  private static final int SCALED_WIDTH = 160;

  private static final double AUDIO_SECONDS = 2.0;

  private static final String MISSING_NAME = "missing.mp4";

  private static final String FRAMES_ARRIVED_MESSAGE = "frames arrived";

  @TempDir
  private Path directory;

  private static void attach(final FfmpegPlayer player, final VideoFilter video, final AudioFilter audio) {
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

  private static void awaitEnd(final AbstractVideoPlayerCv player) throws InterruptedException {
    await("playback finished", () -> !player.isPlaying());
  }

  private static Source file(final Path path) {
    return FileSource.path(path);
  }

  private static double secondsBetween(final long startNanoseconds, final long endNanoseconds) {
    return (endNanoseconds - startNanoseconds) / NANOSECONDS_PER_SECOND;
  }

  private static VideoFilter recordingFrames(final AtomicInteger frames, final List<String> sizes, final List<Long> frameTimes) {
    return (image, metadata) -> recordFrame(image, metadata, frames, sizes, frameTimes);
  }

  private static boolean recordFrame(
    final ImageBuffer image,
    final OriginalVideoMetadata metadata,
    final AtomicInteger frames,
    final List<String> sizes,
    final List<Long> frameTimes
  ) {
    final int width = image.getWidth();
    final int originalWidth = metadata.getVideoWidth();
    sizes.add(width + SIZE_SEPARATOR + originalWidth);
    final long now = System.nanoTime();
    frameTimes.add(now);
    return frames.incrementAndGet() > 0;
  }

  private static AudioFilter countingOutputSamples(final AtomicLong audioBytes) {
    return (samples, metadata) -> countOutputSamples(samples, metadata, audioBytes);
  }

  private static boolean countOutputSamples(final ByteBuffer samples, final OriginalAudioMetadata metadata, final AtomicLong audioBytes) {
    final int sampleRate = metadata.getAudioSampleRate();
    final int remaining = samples.remaining();
    if (sampleRate == AudioFilter.SAMPLE_RATE) {
      audioBytes.addAndGet(remaining);
    }

    return true;
  }

  private static VideoFilter recordingWidth(final AtomicInteger frames, final AtomicInteger width) {
    return (image, _) -> recordWidth(image, frames, width);
  }

  private static boolean recordWidth(final ImageBuffer image, final AtomicInteger frames, final AtomicInteger width) {
    final int frameWidth = image.getWidth();
    width.set(frameWidth);
    return frames.incrementAndGet() > 0;
  }

  private static AudioFilter countingBytes(final AtomicLong audioBytes) {
    return (samples, _) -> countBytes(samples, audioBytes);
  }

  private static boolean countBytes(final ByteBuffer samples, final AtomicLong audioBytes) {
    final int remaining = samples.remaining();
    return audioBytes.addAndGet(remaining) > 0;
  }

  // Creates a player that never drops a frame as late, so how many frames a test sees does not depend on the load of
  // the machine.
  private static FfmpegPlayer neverDroppingPlayer() {
    final FfmpegPlayer player = new FfmpegPlayer();
    player.neverDropLateFrames();
    return player;
  }

  @Test
  void shouldPlayTheWholeFileInRealTimeWithAudio() throws Exception {
    final PlaybackRecording recording = new PlaybackRecording();
    final FfmpegPlayer player = neverDroppingPlayer();
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

  @Test
  void shouldPauseResumeSeekAndScale() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    final AtomicInteger width = new AtomicInteger();
    final FfmpegPlayer player = neverDroppingPlayer();
    final DimensionAttachableCallback dimension = player.getDimensionAttachableCallback();
    final Dimension size = Dimension.of(SCALED_WIDTH, 120);
    dimension.attach(size);
    final VideoFilter video = recordingWidth(frames, width);
    attach(player, video, AudioFilter.NO_OP);

    final Path videoFile = TestMedia.video();
    final Source source = file(videoFile);
    try {
      final boolean started = player.start(source);
      assertTrue(started);

      await(FRAMES_ARRIVED_MESSAGE, () -> frames.get() > 10);
      assertPausingHoldsTheFrames(player, frames);
      assertResumingAndSeekingPlayOn(player, frames);

      awaitEnd(player);
      assertScaledAndStartsAgainOnlyWhenStartedAgain(player, source, width);
    } finally {
      player.release();
    }
  }

  private static void assertScaledAndStartsAgainOnlyWhenStartedAgain(
    final FfmpegPlayer player,
    final Source source,
    final AtomicInteger width
  ) {
    final int lastWidth = width.get();
    final boolean resumedAfterEnd = player.resume();
    final boolean restarted = player.start(source);
    final boolean playingAgain = player.isPlaying();
    final boolean released = player.release();
    final boolean playingAfterRelease = player.isPlaying();
    assertEquals(SCALED_WIDTH, lastWidth);
    assertFalse(resumedAfterEnd, "resuming does not start the media over after its end");
    assertTrue(restarted, "starting the source again plays it again");
    assertTrue(playingAgain);
    assertTrue(released);
    assertFalse(playingAfterRelease);
  }

  private static void assertPausingHoldsTheFrames(final FfmpegPlayer player, final AtomicInteger frames) throws InterruptedException {
    final boolean paused = player.pause();
    assertTrue(paused);

    Thread.sleep(200);
    final int pausedAt = frames.get();
    Thread.sleep(500);
    final int stillPaused = frames.get();
    assertEquals(pausedAt, stillPaused, "no frames while paused");
  }

  private static void assertResumingAndSeekingPlayOn(final FfmpegPlayer player, final AtomicInteger frames) throws InterruptedException {
    final int pausedAt = frames.get();
    final boolean resumed = player.resume();
    assertTrue(resumed);

    await("frames resumed", () -> frames.get() > pausedAt + 10);

    final boolean seeked = player.seek(4_000L);
    assertTrue(seeked);

    await("the position jumped near the end", () -> player.getPositionMilliseconds() >= 3_900L);
  }

  @Test
  void shouldTakeTheAudioFromASeparateSource() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    final AtomicLong audioBytes = new AtomicLong();
    final FfmpegPlayer player = neverDroppingPlayer();
    final AudioFilter audioCounter = countingBytes(audioBytes);
    attach(player, (_, _) -> frames.incrementAndGet() > 0, audioCounter);

    final Path silent = TestMedia.silentVideo();
    final Path sound = TestMedia.audio(AUDIO_SECONDS);
    final Source video = file(silent);
    final Source audio = file(sound);
    try {
      final boolean started = player.start(video, audio);
      assertTrue(started);

      await(FRAMES_ARRIVED_MESSAGE, () -> frames.get() > 20);
      await("audio of the separate source arrived", () -> audioBytes.get() > 150_000);
    } finally {
      player.release();
    }
  }

  @Test
  void shouldPlayAudioFilesWithoutAPicture() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    final AtomicLong audioBytes = new AtomicLong();
    final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    final FfmpegPlayer player = neverDroppingPlayer();
    player.setExceptionHandler((message, error) -> errors.add(message + ": " + error));
    final AudioFilter audioCounter = countingBytes(audioBytes);
    attach(player, (_, _) -> frames.incrementAndGet() > 0, audioCounter);

    final Path sound = TestMedia.audio(AUDIO_SECONDS);
    final Source source = file(sound);
    try {
      final boolean started = player.start(source);
      awaitEnd(player);
      final int frameCount = frames.get();
      final long bytes = audioBytes.get();
      final boolean noErrors = errors.isEmpty();
      assertTrue(started);
      assertEquals(0, frameCount);

      final boolean audioPlayed = bytes > 300_000;
      assertTrue(audioPlayed, "audio " + bytes);
      assertTrue(noErrors, "audio files must play: " + errors);
    } finally {
      player.release();
    }
  }

  @Test
  void shouldPlayFfmpegInputsWithTheirFormat() throws Exception {
    final AtomicInteger frames = new AtomicInteger();
    final AtomicInteger width = new AtomicInteger();
    final FfmpegPlayer player = neverDroppingPlayer();
    final VideoFilter video = recordingWidth(frames, width);
    attach(player, video, AudioFilter.NO_OP);

    final FfmpegDirectSource generator = FfmpegDirectSource.mrl("testsrc=size=64x48:rate=30:duration=1", "lavfi");
    try {
      final boolean started = player.start(generator);
      awaitEnd(player);
      final int frameCount = frames.get();
      final int lastWidth = width.get();
      assertTrue(started);

      final boolean framesPlayed = frameCount > 20;
      assertTrue(framesPlayed, "frames " + frameCount);
      assertEquals(64, lastWidth);
    } finally {
      player.release();
    }
  }

  @Test
  void shouldReportFilesThatCannotBeOpened() {
    final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
    final FfmpegPlayer player = neverDroppingPlayer();
    player.setExceptionHandler((_, error) -> errors.add(error));
    final Path missing = this.directory.resolve(MISSING_NAME);
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

  private static final class PlaybackRecording {

    private final AtomicInteger frames = new AtomicInteger();
    private final AtomicLong audioBytes = new AtomicLong();
    private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> frameTimes = Collections.synchronizedList(new ArrayList<>());
    private final List<String> sizes = Collections.synchronizedList(new ArrayList<>());

    private void attachTo(final FfmpegPlayer player) {
      player.setExceptionHandler((_, error) -> this.errors.add(error));
      final VideoFilter video = recordingFrames(this.frames, this.sizes, this.frameTimes);
      final AudioFilter audio = countingOutputSamples(this.audioBytes);
      attach(player, video, audio);
    }

    private void assertPlayedTheWholeFileInRealTime(final boolean started, final double seconds) {
      final int frameCount = this.frames.get();
      final long bytes = this.audioBytes.get();
      final long firstFrame = this.frameTimes.getFirst();
      final long lastFrame = this.frameTimes.getLast();
      final double frameSpan = secondsBetween(firstFrame, lastFrame);
      final double frameRate = (frameCount - 1) / frameSpan;
      final boolean noErrors = this.errors.isEmpty();
      final Set<String> distinctSizes = Set.copyOf(this.sizes);
      final Set<String> expectedSizes = Set.of(TestMedia.VIDEO_WIDTH + SIZE_SEPARATOR + TestMedia.VIDEO_WIDTH);

      assertTrue(started);
      assertTrue(noErrors, "errors: " + this.errors);
      assertEquals(150, frameCount, "neverDropLateFrames must deliver every frame of the generated 5-second 30-fps file");

      final boolean pacedAtThirty = frameRate > 24.0 && frameRate < 36.0;
      assertTrue(pacedAtThirty, "the frames are paced at about 30 per second, got " + frameRate);

      final boolean realTime = seconds > 4.5;
      assertTrue(realTime, "playback runs in real time, not faster, took " + seconds);

      final boolean fiveSecondsOfAudio = bytes > 900_000 && bytes < 1_000_000;
      assertTrue(fiveSecondsOfAudio, "five seconds of 48 kHz stereo, got " + bytes);
      assertEquals(expectedSizes, distinctSizes, "every frame has the size of the video");
    }
  }
}
