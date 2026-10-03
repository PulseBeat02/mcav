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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import me.brandonli.mcav.media.Polling;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.attachable.AudioAttachableCallback;
import me.brandonli.mcav.media.player.attachable.DimensionAttachableCallback;
import me.brandonli.mcav.media.player.attachable.VideoAttachableCallback;
import me.brandonli.mcav.media.player.pipeline.step.AudioPipelineStep;
import me.brandonli.mcav.media.player.pipeline.step.VideoPipelineStep;
import me.brandonli.mcav.media.source.Source;
import me.brandonli.mcav.media.source.file.FileSource;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

/** Exercises callback reentry and cancellation at the session boundary; real decoding has separate integration tests. */
final class CVPlayerTransitionTest {

  @BeforeAll
  static void loadImageBuffersBeforeTheTimedCallbacks() {
    try (final ImageBuffer image = ImageBuffer.bytes(new byte[3], 1, 1)) {
      assertEquals(1, image.getWidth());
    }
  }

  private static Source source() {
    final Path path = Path.of("transition.mp4");
    return FileSource.path(path);
  }

  @Test
  void replacementLetsThePreviousRendererCallBackWithoutWaitingForThePlayerLock() throws Exception {
    final TestPlayer player = new TestPlayer();
    final Source source = source();
    try (final MockedConstruction<PlaybackSession> sessions = Mockito.mockConstruction(PlaybackSession.class)) {
      assertTrue(player.start(source));
      final List<PlaybackSession> created = sessions.constructed();
      final PlaybackSession previous = created.getFirst();
      doAnswer(_ -> {
        final CompletableFuture<Boolean> callback = CompletableFuture.supplyAsync(player::pause);
        final boolean paused = callback.get(2, TimeUnit.SECONDS);
        assertFalse(paused, "the previous playback was detached before its renderer was joined");
        final boolean competing = player.start(source);
        assertFalse(competing, "a replacement retains ownership while previous workers shut down");
        return null;
      })
        .when(previous)
        .stop();
      assertTrue(player.start(source));
      assertEquals(2, created.size());
      final PlaybackSession replacement = created.getLast();
      verify(replacement).startThreads();
      player.release();
    }
  }

  @Test
  void seekAlsoJoinsOutsideThePlayerLock() throws Exception {
    final TestPlayer player = new TestPlayer();
    final Source source = source();
    try (final MockedConstruction<PlaybackSession> sessions = Mockito.mockConstruction(PlaybackSession.class)) {
      assertTrue(player.start(source));
      final PlaybackSession previous = sessions.constructed().getFirst();
      when(previous.isSeekable()).thenReturn(true);
      doAnswer(_ -> {
        final CompletableFuture<Boolean> callback = CompletableFuture.supplyAsync(player::resume);
        assertFalse(callback.get(2, TimeUnit.SECONDS));
        return null;
      })
        .when(previous)
        .stop();
      assertTrue(player.seek(100));
      player.release();
    }
  }

  @Test
  void staleSeekCannotReplaceANewerPlayback() {
    final TestPlayer player = new TestPlayer();
    final Source source = source();
    try (final MockedConstruction<PlaybackSession> sessions = Mockito.mockConstruction(PlaybackSession.class)) {
      assertTrue(player.start(source));
      final PlaybackSession previous = sessions.constructed().getFirst();
      when(previous.isSeekable()).thenReturn(true);
      when(previous.isPaused()).thenAnswer(_ -> {
        assertTrue(player.start(source));
        return false;
      });
      assertFalse(player.seek(100), "a seek whose observed session was replaced must not replace the new one");
      assertEquals(2, sessions.constructed().size());
      player.release();
    }
  }

  @Test
  void releaseDuringPreviousShutdownCancelsAndClosesTheReplacement() throws Exception {
    final TestPlayer player = new TestPlayer();
    final Source source = source();
    try (final MockedConstruction<PlaybackSession> sessions = Mockito.mockConstruction(PlaybackSession.class)) {
      assertTrue(player.start(source));
      final PlaybackSession previous = sessions.constructed().getFirst();
      doAnswer(_ -> {
        final CompletableFuture<Boolean> callback = CompletableFuture.supplyAsync(player::release);
        assertTrue(callback.get(2, TimeUnit.SECONDS));
        return null;
      })
        .when(previous)
        .stop();
      assertFalse(player.start(source));
      final PlaybackSession replacement = sessions.constructed().getLast();
      verify(replacement, never()).startThreads();
      verify(replacement).stop();
      assertFalse(player.start(source));
      assertFalse(player.isPlaying());
    }
  }

  @Test
  void releaseReenteredFromOpeningCannotPublishPlaybackAfterRelease() throws Exception {
    final TestPlayer player = new TestPlayer();
    final Source source = source();
    try (
      final MockedConstruction<PlaybackSession> sessions = Mockito.mockConstruction(PlaybackSession.class, (session, _) -> {
        doAnswer(_ -> {
          assertFalse(player.start(source));
          assertTrue(player.release());
          return null;
        })
          .when(session)
          .open();
      })
    ) {
      assertFalse(player.start(source));
      final PlaybackSession cancelled = sessions.constructed().getFirst();
      verify(cancelled, never()).startThreads();
      verify(cancelled).stop();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void reentrantReplacementKeepsPipelineDeliverySerialAcrossSessions(final boolean audio) throws Exception {
    final int firstValue = audio ? 17 : 0xFF0000;
    final int secondValue = audio ? 34 : 0x0000FF;
    try (final Frame first = mediaFrame(audio, firstValue); final Frame second = mediaFrame(audio, secondValue)) {
      final ScriptedPlayer player = new ScriptedPlayer(ScriptedFrameGrabber.of(first), ScriptedFrameGrabber.of(second));
      player.neverDropLateFrames();
      final Source source = source();
      final CountDownLatch restarted = new CountDownLatch(1);
      final CountDownLatch releaseFirst = new CountDownLatch(1);
      final CountDownLatch secondEntered = new CountDownLatch(1);
      final CountDownLatch delivered = new CountDownLatch(2);
      final AtomicInteger active = new AtomicInteger();
      final AtomicInteger maximum = new AtomicInteger();
      final List<Integer> colors = new CopyOnWriteArrayList<>();
      final List<Throwable> failures = new CopyOnWriteArrayList<>();
      player.setExceptionHandler((_, failure) -> failures.add(failure));
      final IntConsumer record = value -> {
        colors.add(value);
        active.decrementAndGet();
        delivered.countDown();
      };
      final IntConsumer enter = value -> {
        maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
        if (value == firstValue) {
          assertTrue(player.start(source));
          restarted.countDown();
          await(releaseFirst);
        } else {
          secondEntered.countDown();
        }
      };
      if (audio) {
        final AudioPipelineStep recorder = AudioPipelineStep.of((samples, _) -> {
          record.accept(samples.get(0) & 0xFF);
          return false;
        });
        player.getAudioAttachableCallback().attach(
          AudioPipelineStep.of(recorder, (samples, _) -> {
            enter.accept(samples.get(0) & 0xFF);
            return false;
          })
        );
      } else {
        final VideoPipelineStep recorder = VideoPipelineStep.of((image, _) -> {
          record.accept(image.getPixels()[0] & 0xFFFFFF);
          return false;
        });
        player.getVideoAttachableCallback().attach(
          VideoPipelineStep.of(recorder, (image, _) -> {
            enter.accept(image.getPixels()[0] & 0xFFFFFF);
            return false;
          })
        );
      }
      try {
        assertTrue(player.start(source));
        assertTrue(restarted.await(10, TimeUnit.SECONDS));
        assertFalse(secondEntered.await(200, TimeUnit.MILLISECONDS), "the replacement entered a pipeline still processing the old session");
        releaseFirst.countDown();
        assertTrue(delivered.await(10, TimeUnit.SECONDS));
        assertEquals(1, maximum.get());
        assertEquals(List.of(firstValue, secondValue), colors, "the replacement must be delivered after the old session");
        assertEquals(List.of(), failures);
      } finally {
        releaseFirst.countDown();
        player.release();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void aStoppedSessionDiscardsAFrameWaitingForPipelineDelivery(final boolean audio) throws Exception {
    final VideoAttachableCallback video = mock(VideoAttachableCallback.class);
    final AudioAttachableCallback sound = mock(AudioAttachableCallback.class);
    final CountDownLatch retrieved = new CountDownLatch(audio ? 1 : 2);
    final AtomicInteger delivered = new AtomicInteger();
    final VideoPipelineStep picture = VideoPipelineStep.of((_, _) -> {
      delivered.incrementAndGet();
      return false;
    });
    final AudioPipelineStep samples = AudioPipelineStep.of((_, _) -> {
      delivered.incrementAndGet();
      return false;
    });
    when(video.retrieve()).thenAnswer(_ -> {
      retrieved.countDown();
      return picture;
    });
    when(sound.retrieve()).thenAnswer(_ -> {
      retrieved.countDown();
      return samples;
    });
    try (final Frame frame = mediaFrame(audio, 17)) {
      final ScriptedFrameGrabber grabber = ScriptedFrameGrabber.of(frame);
      final List<Throwable> failures = new CopyOnWriteArrayList<>();
      final PlaybackSession session = new PlaybackSession(
        () -> grabber,
        null,
        video,
        sound,
        DimensionAttachableCallback.create(),
        (_, failure) -> failures.add(failure),
        0L,
        false,
        0L,
        Long.MAX_VALUE,
        System::nanoTime
      );
      final Thread stopper = new Thread(session::stop, "stopped-pipeline-delivery");
      final Object delivery = audio ? sound : video;
      try {
        synchronized (delivery) {
          session.start();
          assertTrue(retrieved.await(10, TimeUnit.SECONDS), "the renderer must retrieve its pipeline");
          stopper.start();
          Polling.awaitCondition("the session stops", Duration.ofSeconds(10), () -> !session.isActive());
        }
        stopper.join(10_000);
        assertFalse(stopper.isAlive(), "stopping must join the released renderer");
        assertEquals(0, delivered.get(), "an ended session cannot deliver a frame that was waiting for its pipeline");
        assertEquals(List.of(), failures);
      } finally {
        session.stop();
        stopper.join(10_000);
        assertFalse(stopper.isAlive(), "the fixture must stop its control thread");
      }
    }
  }

  private static Frame mediaFrame(final boolean audio, final int value) {
    if (audio) {
      final Frame frame = ScriptedFrameGrabber.audio(0);
      final ShortBuffer samples = (ShortBuffer) frame.samples[0];
      samples.put(0, (short) value);
      return frame;
    }
    return coloredFrame(value);
  }

  private static Frame coloredFrame(final int color) {
    final Frame frame = ScriptedFrameGrabber.video(0);
    final ByteBuffer pixels = (ByteBuffer) frame.image[0];
    for (int row = 0; row < frame.imageHeight; row++) {
      for (int column = 0; column < frame.imageWidth; column++) {
        final int offset = row * frame.imageStride + column * 3;
        pixels.put(offset, (byte) color);
        pixels.put(offset + 1, (byte) (color >>> 8));
        pixels.put(offset + 2, (byte) (color >>> 16));
      }
    }
    return frame;
  }

  private static void await(final CountDownLatch latch) {
    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS), "fixture latch must be released");
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }

  private static final class ScriptedPlayer extends AbstractVideoPlayerCV {

    private final Deque<FrameGrabber> grabbers;

    private ScriptedPlayer(final FrameGrabber... grabbers) {
      this.grabbers = new ArrayDeque<>(List.of(grabbers));
    }

    @Override
    protected FrameGrabber createFrameGrabber(final String resource) {
      return this.grabbers.removeFirst();
    }
  }

  private static final class TestPlayer extends AbstractVideoPlayerCV {

    @Override
    protected FrameGrabber createFrameGrabber(final String resource) {
      throw new AssertionError("Sessions are replaced at the boundary in this fixture");
    }
  }
}
