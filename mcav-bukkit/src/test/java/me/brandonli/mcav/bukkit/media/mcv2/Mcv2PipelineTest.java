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
package me.brandonli.mcav.bukkit.media.mcv2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import org.junit.jupiter.api.Test;

final class Mcv2PipelineTest {

  private static final ForkJoinPool POOL = ForkJoinPool.commonPool();

  private static final String STOPPED = "The encoder stopped after a frame failed its verification!";

  private static final String KERNELS_FIELD = "kernels";
  private static final String PICTURE_FIELD = "picture";

  static List<byte[]> sequential(
    final Settings settings,
    final int width,
    final int height,
    final int frames,
    final int panPerFrame,
    final int requestEvery
  ) {
    final Mcv2 encoder = new Mcv2(settings, POOL, 2, Mcv2.Verification.VERIFIED);
    final List<byte[]> stream = new ArrayList<>();
    for (int frameNumber = 0; frameNumber < frames; frameNumber++) {
      if (requestEvery > 0 && frameNumber % requestEvery == 0) {
        encoder.requestKeyframe();
      }
      stream.add(encoder.encode(Mcv2Pictures.scene(width, height, frameNumber, panPerFrame), width, height, frameNumber));
    }

    return stream;
  }

  static List<byte[]> pipelined(
    final Settings settings,
    final int width,
    final int height,
    final int frames,
    final int panPerFrame,
    final int requestEvery
  ) throws InterruptedException, ExecutionException {
    final Mcv2 encoder = new Mcv2(settings, POOL, 2, Mcv2.Verification.VERIFIED);
    final ExecutorService verifier = Executors.newSingleThreadExecutor();
    try {
      return encodePipelined(encoder, verifier, width, height, frames, panPerFrame, requestEvery);
    } finally {
      verifier.shutdown();
    }
  }

  private static List<byte[]> encodePipelined(
    final Mcv2 encoder,
    final ExecutorService verifier,
    final int width,
    final int height,
    final int frames,
    final int panPerFrame,
    final int requestEvery
  ) throws InterruptedException, ExecutionException {
    final List<byte[]> stream = new ArrayList<>();
    Future<Mcv2.Encoded> verifying = null;
    for (int frameNumber = 0; frameNumber < frames; frameNumber++) {
      if (requestEvery > 0 && frameNumber % requestEvery == 0) {
        encoder.requestKeyframe();
      }

      final Mcv2.Pending pending = encoder.begin(Mcv2Pictures.scene(width, height, frameNumber, panPerFrame), width, height, frameNumber);
      if (verifying != null) {
        stream.add(verifying.get().getData());
      }

      verifying = verifier.submit(() -> encoder.finish(pending));
    }

    if (verifying != null) {
      stream.add(verifying.get().getData());
    }

    return stream;
  }

  @Test
  void shouldNotAdvanceMotionHistoryOnAFailedSearch() throws ReflectiveOperationException {
    final Mcv2 encoder = new Mcv2(Settings.DEFAULT, POOL, 1, Mcv2.Verification.VERIFIED);
    final Mcv2 control = new Mcv2(Settings.DEFAULT, POOL, 1, Mcv2.Verification.VERIFIED);
    final Field factory = Mcv2.class.getDeclaredField(KERNELS_FIELD);
    factory.setAccessible(true);
    final Object original = factory.get(encoder);
    factory.set(
      encoder,
      (Supplier<?>) () -> {
        throw new IllegalStateException("search failed!");
      }
    );
    final byte[] failed = new byte[32 * 32 * 3];
    Arrays.fill(failed, (byte) 255);
    assertThrows(IllegalStateException.class, () -> encoder.begin(failed, 32, 32, 0));

    factory.set(encoder, original);
    for (int frame = 0; frame < 3; frame++) {
      final byte[] source = new byte[failed.length];
      final byte[] expected = control.encode(source, 32, 32, frame);
      final byte[] encoded = encoder.encode(source, 32, 32, frame);
      assertArrayEquals(expected, encoded);
      final double expectedLambda = control.getStatistics().orElseThrow().getLambda();
      final double lambda = encoder.getStatistics().orElseThrow().getLambda();
      assertEquals(expectedLambda, lambda);
      final byte[] expectedReference = control.getReference().orElseThrow();
      final byte[] reference = encoder.getReference().orElseThrow();
      assertArrayEquals(expectedReference, reference);
    }
  }

  @Test
  void shouldRetainRequestsAndSettingsChangedDuringBegin() throws Exception {
    final Mcv2 encoder = new Mcv2(Settings.DEFAULT, POOL, 1, Mcv2.Verification.VERIFIED);
    final Field factory = Mcv2.class.getDeclaredField(KERNELS_FIELD);
    factory.setAccessible(true);
    final Supplier<?> original = (Supplier<?>) factory.get(encoder);
    final CountDownLatch searching = new CountDownLatch(1);
    final CountDownLatch resume = new CountDownLatch(1);
    factory.set(encoder, (Supplier<?>) () -> this.pauseBeforeSearching(searching, resume, original));
    try (final ExecutorService caller = Executors.newSingleThreadExecutor()) {
      final Future<Mcv2.Pending> first = caller.submit(() -> encoder.begin(new byte[3], 1, 1, 0));
      try {
        final boolean searchStarted = searching.await(30, TimeUnit.SECONDS);
        assertTrue(searchStarted);

        encoder.requestKeyframe();
        encoder.setSettings(Settings.FAST);
      } finally {
        resume.countDown();
      }

      final double firstLambda = encoder.finish(first.get()).getStatistics().getLambda();
      assertEquals(72, firstLambda);

      final Mcv2.Pending requested = encoder.begin(new byte[3], 1, 1, 1);
      final boolean keyframeRequested = requested.isKeyframe();
      assertTrue(keyframeRequested);

      final double requestedLambda = encoder.finish(requested).getStatistics().getLambda();
      assertEquals(55, requestedLambda);

      for (int frameId = 2; frameId <= 121; frameId++) {
        final Mcv2.Pending pending = encoder.begin(new byte[3], 1, 1, frameId);
        final boolean expectedKeyframe = frameId == 121;
        final boolean keyframe = pending.isKeyframe();
        assertEquals(expectedKeyframe, keyframe);

        encoder.finish(pending);
      }
    }
  }

  private Object pauseBeforeSearching(final CountDownLatch searching, final CountDownLatch resume, final Supplier<?> original) {
    searching.countDown();
    try {
      final boolean resumed = resume.await(30, TimeUnit.SECONDS);
      assertTrue(resumed);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();

      throw new AssertionError(exception);
    }

    return original.get();
  }

  @Test
  void shouldPipelineToTheStreamItEncodesOneFrameAtATime() throws InterruptedException, ExecutionException {
    final List<Settings> profiles = List.of(Settings.DEFAULT, Settings.FAST);
    for (final Settings settings : profiles) {
      for (final int requestEvery : new int[] { 0, 1, 3 }) {
        this.assertPipelinedMatchesSequential(settings, requestEvery);
      }
    }
  }

  private void assertPipelinedMatchesSequential(final Settings settings, final int requestEvery)
    throws InterruptedException, ExecutionException {
    final List<byte[]> one = sequential(settings, 100, 70, 7, 3, requestEvery);
    final List<byte[]> two = pipelined(settings, 100, 70, 7, 3, requestEvery);
    final Mcv2Receiver client = new Mcv2Receiver();
    for (int frameIndex = 0; frameIndex < one.size(); frameIndex++) {
      final byte[] expected = one.get(frameIndex);
      final byte[] pipelinedFrame = two.get(frameIndex);
      assertArrayEquals(expected, pipelinedFrame, settings + " frame " + frameIndex);

      client.accept(two.get(frameIndex));
    }
  }

  @Test
  void shouldKeepOneFrameInFlightAtMost() {
    final Mcv2 encoder = new Mcv2(Settings.FAST, POOL, 2, Mcv2.Verification.VERIFIED);
    final Mcv2.Pending first = encoder.begin(Mcv2Pictures.scene(64, 64, 0, 2), 64, 64, 0);
    encoder.begin(Mcv2Pictures.scene(64, 64, 1, 2), 64, 64, 1);
    final IllegalStateException beginException = assertThrows(IllegalStateException.class, () ->
      encoder.begin(Mcv2Pictures.scene(64, 64, 2, 2), 64, 64, 2)
    );
    final String beginMessage = beginException.getMessage();
    assertEquals("Two frames are in flight already!", beginMessage);

    encoder.finish(first);
    encoder.begin(Mcv2Pictures.scene(64, 64, 2, 2), 64, 64, 2);
    final IllegalStateException finishException = assertThrows(IllegalStateException.class, () -> encoder.finish(first));
    final String finishMessage = finishException.getMessage();
    assertEquals("The frame is finished already!", finishMessage);
    assertThrows(NullPointerException.class, () -> encoder.finish(null));
  }

  @Test
  void shouldStopAfterAFrameFailsItsVerification() throws ReflectiveOperationException {
    final Mcv2 encoder = new Mcv2(Settings.DEFAULT, POOL, 2, Mcv2.Verification.VERIFIED);
    final Mcv2.Pending frame = encoder.begin(Mcv2Pictures.scene(64, 64, 0, 2), 64, 64, 0);
    final Mcv2.Pending next = encoder.begin(Mcv2Pictures.scene(64, 64, 1, 2), 64, 64, 1);
    final var picture = Mcv2.Pending.class.getDeclaredField(PICTURE_FIELD);
    picture.setAccessible(true);
    ((byte[]) picture.get(frame))[0] ^= 1;
    final IllegalStateException finishException = assertThrows(IllegalStateException.class, () -> encoder.finish(frame));
    final String finishMessage = finishException.getMessage();
    assertEquals("MCV2 live picture and decoded picture disagree!", finishMessage);
    // the frame in flight predicts from the failed frame's picture: it is never finished, and nothing is encoded after
    final IllegalStateException nextException = assertThrows(IllegalStateException.class, () -> encoder.finish(next));
    final String nextMessage = nextException.getMessage();
    assertEquals(STOPPED, nextMessage);

    final IllegalStateException beginException = assertThrows(IllegalStateException.class, () ->
      encoder.begin(Mcv2Pictures.scene(64, 64, 2, 2), 64, 64, 2)
    );
    final String beginMessage = beginException.getMessage();
    assertEquals(STOPPED, beginMessage);
    assertThrows(IllegalStateException.class, () -> encoder.encode(Mcv2Pictures.scene(64, 64, 2, 2), 64, 64, 2));
  }

  @Test
  void shouldVerifyNothingWithoutVerification() throws ReflectiveOperationException {
    final Mcv2 encoder = new Mcv2(Settings.DEFAULT, POOL, 2, Mcv2.Verification.UNVERIFIED);
    final Mcv2.Pending frame = encoder.begin(Mcv2Pictures.scene(64, 64, 0, 2), 64, 64, 0);
    final var picture = Mcv2.Pending.class.getDeclaredField(PICTURE_FIELD);
    picture.setAccessible(true);
    ((byte[]) picture.get(frame))[0] ^= 1;
    final Mcv2.Encoded encoded = encoder.finish(frame);
    final int expectedBytes = encoded.getData().length;
    final int bytes = encoded.getStatistics().getBytes();
    assertEquals(expectedBytes, bytes);

    final boolean keyframe = encoded.getStatistics().isKeyframe();
    assertTrue(keyframe);

    final boolean timed = encoded.getStatistics().getNanoseconds() > 0;
    assertTrue(timed);
  }

  @Test
  void shouldServeAKeyframeRequestMadeWhileAFrameIsInFlightWithTheFrameAfterIt() {
    final Mcv2 encoder = new Mcv2(Settings.FAST, POOL, 2, Mcv2.Verification.VERIFIED);
    final List<Mcv2.Pending> frames = new ArrayList<>();
    final List<Boolean> keyframes = new ArrayList<>();
    for (int frameNumber = 0; frameNumber < 5; frameNumber++) {
      frames.add(encoder.begin(Mcv2Pictures.scene(64, 64, frameNumber, 1), 64, 64, frameNumber));
      if (frameNumber == 2) {
        // frame 2 is begun already: the request applies to frame 3
        encoder.requestKeyframe();
      }
      if (frameNumber > 0) {
        keyframes.add(
          encoder
            .finish(frames.get(frameNumber - 1))
            .getStatistics()
            .isKeyframe()
        );
      }
    }

    keyframes.add(encoder.finish(frames.get(4)).getStatistics().isKeyframe());
    final List<Boolean> expected = List.of(true, false, false, true, false);
    assertEquals(expected, keyframes);

    final boolean requestedKeyframe = frames.get(3).isKeyframe();
    assertTrue(requestedKeyframe);

    final boolean inFlightKeyframe = frames.get(2).isKeyframe();
    assertFalse(inFlightKeyframe);

    final Mcv2.Statistics last = encoder.getStatistics().orElseThrow();

    final boolean lastKeyframe = last.isKeyframe();
    assertFalse(lastKeyframe);
  }
}
