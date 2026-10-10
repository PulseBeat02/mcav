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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.Node;
import org.junit.jupiter.api.Test;

final class MCV2VerificationTest {

  @Test
  void reusesVerificationStorageUntilThePictureSizeChanges() throws ReflectiveOperationException {
    final MCV2 encoder = new MCV2(MCV2.Settings.DEFAULT, ForkJoinPool.commonPool(), 3, true);
    final Field storage = MCV2.class.getDeclaredField("verificationPicture");
    storage.setAccessible(true);
    encoder.encode(new byte[32 * 32 * 3], 32, 32, 0);
    final Object first = storage.get(encoder);
    encoder.encode(Mcv2Pictures.scene(32, 32, 1, 3), 32, 32, 1);
    assertSame(first, storage.get(encoder));
    encoder.encode(new byte[3], 1, 1, 2);
    assertNotSame(first, storage.get(encoder));
    assertEquals(3, ((byte[]) storage.get(encoder)).length);
  }

  @Test
  void checksTheWholeConformanceAndEdgeCorpusIncludingUnusedCompactClasses() throws Mcv2Exception {
    for (final String folder : List.of("conformance", "edge")) {
      for (final String stream : Mcv2Fixtures.digests(folder).keySet()) {
        final Map<Long, byte[]> pictures = new HashMap<>();
        for (final byte[] data : Mcv2Fixtures.frames(Mcv2Fixtures.read(folder + "/" + stream))) {
          final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(data);
          final byte[] reference = frame.isKeyframe() ? new byte[0] : pictures.get(frame.getReferenceId());
          final byte[] picture = Mcv2Decoder.decode(frame, reference, frame.getReferenceId());
          compare(frame, reference, picture, new Random(20261003));
          pictures.put(frame.getFrameId(), picture);
        }
      }
    }
  }

  static void compareRandom(final long seed) throws Mcv2Exception {
    final Random random = new Random(seed);
    final int width = 1 + random.nextInt(100);
    final int height = 1 + random.nextInt(100);
    final List<Node> roots = new ArrayList<>();
    for (int root = 0; root < ((width + 31) / 32) * ((height + 31) / 32); root++) {
      roots.add(MCV2TreePropertyTest.tree(random, 32));
    }
    final byte[] data = Mcv2Trees.write(width, height, 2, 1, false, roots);
    final byte[] reference = new byte[width * height * 3];
    random.nextBytes(reference);
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(data);
    compare(frame, reference, Mcv2Decoder.decode(frame, reference, 1), random);
  }

  private static void compare(final Mcv2Decoder.Frame frame, final byte[] reference, final byte[] picture, final Random random) {
    assertDoesNotThrow(() -> verify(frame, reference, frame.getReferenceId(), picture));
    final byte[] wrong = picture.clone();
    wrong[random.nextInt(wrong.length)] ^= 1;
    assertEquals(
      "MCV2 live picture and decoded picture disagree",
      assertThrows(IllegalStateException.class, () -> verify(frame, reference, frame.getReferenceId(), wrong)).getMessage()
    );
  }

  private static void verify(final Mcv2Decoder.Frame frame, final byte[] reference, final long referenceId, final byte[] picture) {
    final MCV2 encoder = new MCV2(MCV2.Settings.DEFAULT, ForkJoinPool.commonPool(), 4, true);
    final Object pending = Mcv2Internals.construct(
      MCV2.Pending.class,
      new Class<?>[] {
        MCV2.class,
        byte[].class,
        byte[].class,
        byte[].class,
        long.class,
        boolean.class,
        int.class,
        double.class,
        long.class,
      },
      encoder,
      frame.getData(),
      picture,
      reference,
      referenceId,
      frame.isKeyframe(),
      frame.getLeafCount(),
      72.0,
      0L
    );
    Mcv2Internals.call(MCV2.class, encoder, "verify", new Class<?>[] { MCV2.Pending.class }, pending);
  }

  @Test
  void partitionsLargeFramesAndDetectsEveryBoundaryPixel() throws Mcv2Exception {
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(
      Mcv2Trees.keyframe(31, 32 * 70 - 3, Mcv2Trees.repeat(Mcv2Trees.solid(17, 31, 63), 70))
    );
    final byte[] picture = Mcv2Decoder.decode(frame, null, 4);
    final byte[] reference = new byte[0];
    assertDoesNotThrow(() -> verify(frame, reference, 4, picture));
    for (final int row : new int[] { 0, 31, 32, 2047, 2048, frame.getHeight() - 1 }) {
      for (final int column : new int[] { 0, 30 }) {
        for (int channel = 0; channel < 3; channel++) {
          final byte[] wrong = picture.clone();
          wrong[(row * 31 + column) * 3 + channel] ^= 1;
          assertThrows(IllegalStateException.class, () -> verify(frame, reference, 4, wrong));
        }
      }
    }
  }

  @Test
  void rejectsPictureAndReferenceMismatch() throws Mcv2Exception {
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(Mcv2Trees.predicted(8, 8, Node.skip()));
    final byte[] picture = new byte[8 * 8 * 3];
    assertInstanceOf(
      Mcv2Exception.class,
      assertThrows(IllegalStateException.class, () -> verify(frame, new byte[1], 0, picture)).getCause()
    );
    assertInstanceOf(Mcv2Exception.class, assertThrows(IllegalStateException.class, () -> verify(frame, picture, 5, picture)).getCause());
    assertThrows(IllegalArgumentException.class, () -> verify(frame, picture, 0, new byte[1]));
  }

  @Test
  void aBrokenPendingReferenceStopsTheEncoderWithTheDecoderFailure() throws ReflectiveOperationException {
    final MCV2 encoder = new MCV2(MCV2.Settings.FAST, ForkJoinPool.commonPool(), 1, true);
    final byte[] picture = Mcv2Pictures.scene(32, 32, 0, 2);
    encoder.encode(picture, 32, 32, 0);
    final MCV2.Pending pending = encoder.begin(picture, 32, 32, 1);
    assertFalse(pending.isKeyframe());
    final Field referenceId = MCV2.Pending.class.getDeclaredField("referenceId");
    referenceId.setAccessible(true);
    referenceId.setLong(pending, -1);
    final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> encoder.finish(pending));
    assertEquals("The encoder wrote a frame the decoder rejects", failure.getMessage());
    assertInstanceOf(Mcv2Exception.class, failure.getCause());
    assertEquals("Reference frame mismatch", failure.getCause().getMessage());
    assertEquals(
      "The encoder stopped after a frame failed its verification",
      assertThrows(IllegalStateException.class, () -> encoder.begin(picture, 32, 32, 2)).getMessage()
    );
  }

  @Test
  void reportsAFrameTheParserRejects() throws ReflectiveOperationException {
    final MCV2 encoder = new MCV2(MCV2.Settings.DEFAULT, ForkJoinPool.commonPool(), 1, true);
    final MCV2.Pending pending = encoder.begin(new byte[3], 1, 1, 0);
    final Field data = MCV2.Pending.class.getDeclaredField("data");
    data.setAccessible(true);
    ((byte[]) data.get(pending))[0] = 0;
    final IllegalStateException error = assertThrows(IllegalStateException.class, () -> encoder.finish(pending));
    assertEquals("The encoder wrote a frame the decoder rejects", error.getMessage());
    assertInstanceOf(Mcv2Exception.class, error.getCause());
    assertEquals("Not an MCV2 frame", error.getCause().getMessage());
    assertThrows(IllegalStateException.class, () -> encoder.encode(new byte[3], 1, 1, 1));
  }
}
