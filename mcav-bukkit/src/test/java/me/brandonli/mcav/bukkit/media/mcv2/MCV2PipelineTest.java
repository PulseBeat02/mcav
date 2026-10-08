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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
import org.junit.jupiter.api.Test;

/**
 * An encoder in two steps: a frame searched and written by {@code begin}, verified by {@code finish} while the next
 * frame is searched on another thread, gives the stream the encoder gives one frame at a time; one frame at most is in
 * flight; a frame that fails its verification stops the encoder; a keyframe asked for while a frame is in flight comes
 * with the frame after it.
 */
final class MCV2PipelineTest {

  private static final ForkJoinPool POOL = ForkJoinPool.commonPool();

  private static final String STOPPED = "The encoder stopped after a frame failed its verification";

  /** Encodes a panning scene one frame at a time. */
  static List<byte[]> sequential(final Settings settings, final int width, final int height, final int frames, final int panPerFrame) {
    final MCV2 encoder = new MCV2(settings, POOL, 2, true);
    final List<byte[]> stream = new ArrayList<>();
    for (int frameNumber = 0; frameNumber < frames; frameNumber++) {
      stream.add(encoder.encode(Mcv2Pictures.scene(width, height, frameNumber, panPerFrame), width, height, frameNumber));
    }
    return stream;
  }

  /** Encodes the same scene pipelined: frame N is verified on another thread while frame N+1 is searched. */
  static List<byte[]> pipelined(final Settings settings, final int width, final int height, final int frames, final int panPerFrame)
    throws InterruptedException, ExecutionException {
    final MCV2 encoder = new MCV2(settings, POOL, 2, true);
    final ExecutorService verifier = Executors.newSingleThreadExecutor();
    try {
      final List<byte[]> stream = new ArrayList<>();
      Future<MCV2.Encoded> verifying = null;
      for (int frameNumber = 0; frameNumber < frames; frameNumber++) {
        final MCV2.Pending pending = encoder.begin(Mcv2Pictures.scene(width, height, frameNumber, panPerFrame), width, height, frameNumber);
        if (verifying != null) {
          stream.add(verifying.get().getData());
        }
        verifying = verifier.submit(() -> encoder.finish(pending));
      }
      if (verifying != null) {
        stream.add(verifying.get().getData());
      }
      return stream;
    } finally {
      verifier.shutdown();
    }
  }

  @Test
  void pipelinesToTheStreamItEncodesOneFrameAtATime() throws InterruptedException, ExecutionException, Mcv2Exception {
    final List<Settings> profiles = List.of(
      Settings.DEFAULT,
      Settings.FAST,
      Settings.ADAPTIVE,
      new Settings(55, 3, true, false),
      new Settings(72, 1, false, false)
    );
    for (final Settings settings : profiles) {
      final List<byte[]> one = sequential(settings, 100, 70, 7, 3);
      final List<byte[]> two = pipelined(settings, 100, 70, 7, 3);
      final Mcv2Receiver client = new Mcv2Receiver();
      for (int frameIndex = 0; frameIndex < one.size(); frameIndex++) {
        assertArrayEquals(one.get(frameIndex), two.get(frameIndex), settings + " frame " + frameIndex);
        client.accept(two.get(frameIndex));
      }
    }
  }

  @Test
  void keepsOneFrameInFlightAtMost() {
    final MCV2 encoder = new MCV2(Settings.FAST, POOL, 2, true);
    final MCV2.Pending first = encoder.begin(Mcv2Pictures.scene(64, 64, 0, 2), 64, 64, 0);
    encoder.begin(Mcv2Pictures.scene(64, 64, 1, 2), 64, 64, 1);
    assertEquals(
      "Two frames are in flight already",
      assertThrows(IllegalStateException.class, () -> encoder.begin(Mcv2Pictures.scene(64, 64, 2, 2), 64, 64, 2)).getMessage()
    );
    encoder.finish(first);
    encoder.begin(Mcv2Pictures.scene(64, 64, 2, 2), 64, 64, 2);
    assertEquals("The frame is finished already", assertThrows(IllegalStateException.class, () -> encoder.finish(first)).getMessage());
    assertThrows(NullPointerException.class, () -> encoder.finish(null));
  }

  @Test
  void stopsAfterAFrameFailsItsVerification() throws ReflectiveOperationException {
    final MCV2 encoder = new MCV2(Settings.DEFAULT, POOL, 2, true);
    final MCV2.Pending frame = encoder.begin(Mcv2Pictures.scene(64, 64, 0, 2), 64, 64, 0);
    final MCV2.Pending next = encoder.begin(Mcv2Pictures.scene(64, 64, 1, 2), 64, 64, 1);
    // the picture the search assembled no longer is the one its bytes decode to
    final var picture = MCV2.Pending.class.getDeclaredField("picture");
    picture.setAccessible(true);
    ((byte[]) picture.get(frame))[0] ^= 1;
    assertEquals(
      "MCV2 live picture and decoded picture disagree",
      assertThrows(IllegalStateException.class, () -> encoder.finish(frame)).getMessage()
    );
    // the frame in flight predicts from the failed frame's picture: it is never finished, and nothing is encoded after
    assertEquals(STOPPED, assertThrows(IllegalStateException.class, () -> encoder.finish(next)).getMessage());
    assertEquals(
      STOPPED,
      assertThrows(IllegalStateException.class, () -> encoder.begin(Mcv2Pictures.scene(64, 64, 2, 2), 64, 64, 2)).getMessage()
    );
    assertThrows(IllegalStateException.class, () -> encoder.encode(Mcv2Pictures.scene(64, 64, 2, 2), 64, 64, 2));
  }

  @Test
  void verifiesNothingWithoutVerification() throws ReflectiveOperationException {
    final MCV2 encoder = new MCV2(Settings.DEFAULT, POOL, 2, false);
    final MCV2.Pending frame = encoder.begin(Mcv2Pictures.scene(64, 64, 0, 2), 64, 64, 0);
    final var picture = MCV2.Pending.class.getDeclaredField("picture");
    picture.setAccessible(true);
    ((byte[]) picture.get(frame))[0] ^= 1;
    final MCV2.Encoded encoded = encoder.finish(frame);
    assertEquals(encoded.getData().length, encoded.getStats().bytes());
    assertTrue(encoded.getStats().keyframe());
    assertTrue(encoded.getStats().nanoseconds() > 0);
  }

  @Test
  void keyframesAKeyframeRequestMadeWhileAFrameIsInFlightWithTheFrameAfterIt() {
    final MCV2 encoder = new MCV2(Settings.FAST, POOL, 2, true);
    final List<MCV2.Pending> frames = new ArrayList<>();
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
            .getStats()
            .keyframe()
        );
      }
    }
    keyframes.add(encoder.finish(frames.get(4)).getStats().keyframe());
    assertEquals(List.of(true, false, false, true, false), keyframes);
    // a frame in flight says what it is before it is verified
    assertTrue(frames.get(3).isKeyframe());
    assertFalse(frames.get(2).isKeyframe());
    final MCV2.Stats last = encoder.getStats();
    assertNotNull(last);
    assertFalse(last.keyframe());
  }
}
