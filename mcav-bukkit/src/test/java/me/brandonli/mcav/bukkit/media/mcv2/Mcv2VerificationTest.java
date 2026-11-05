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
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

final class Mcv2VerificationTest {

  private static final int ROOT_SIZE = 32;

  private static final String PATH_SEPARATOR = "/";

  private static final String DECODER_REJECTION_MESSAGE = "The encoder wrote a frame the decoder rejects!";

  @Test
  void shouldReuseVerificationStorageUntilThePictureSizeChanges() throws ReflectiveOperationException {
    final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT, ForkJoinPool.commonPool(), 3, Mcv2.Verification.VERIFIED);
    final Field storage = Mcv2.class.getDeclaredField("verificationPicture");
    storage.setAccessible(true);
    encoder.encode(new byte[ROOT_SIZE * ROOT_SIZE * 3], ROOT_SIZE, ROOT_SIZE, 0);
    final Object first = storage.get(encoder);
    encoder.encode(Mcv2Pictures.scene(ROOT_SIZE, ROOT_SIZE, 1, 3), ROOT_SIZE, ROOT_SIZE, 1);
    final @Nullable Object reused = storage.get(encoder);
    assertSame(first, reused);

    encoder.encode(new byte[3], 1, 1, 2);
    final @Nullable Object replaced = storage.get(encoder);
    assertNotSame(first, replaced);

    final int replacedLength = ((byte[]) storage.get(encoder)).length;
    assertEquals(3, replacedLength);
  }

  @Test
  void shouldCheckTheWholeConformanceAndEdgeCorpusIncludingUnusedCompactClasses() {
    for (final String folder : List.of("conformance", "edge")) {
      for (final String stream : Mcv2Fixtures.readDigests(folder).keySet()) {
        this.compareStream(folder, stream);
      }
    }
  }

  private void compareStream(final String folder, final String stream) {
    final Map<Long, byte[]> pictures = new HashMap<>();
    for (final byte[] data : Mcv2Fixtures.splitFrames(Mcv2Fixtures.read(folder + PATH_SEPARATOR + stream))) {
      final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(data);
      final byte[] reference = frame.isKeyframe() ? new byte[0] : pictures.get(frame.getReferenceId());
      final byte[] picture = Mcv2Decoder.decode(frame, reference, frame.getReferenceId());
      compare(frame, reference, picture, new Random(20261003));
      pictures.put(frame.getFrameId(), picture);
    }
  }

  static void compareRandom(final long seed) {
    final Random random = new Random(seed);
    final int width = 1 + random.nextInt(100);
    final int height = 1 + random.nextInt(100);
    final List<Node> roots = new ArrayList<>();
    for (int root = 0; root < ((width + 31) / ROOT_SIZE) * ((height + 31) / ROOT_SIZE); root++) {
      roots.add(Mcv2TreePropertyTest.tree(random, ROOT_SIZE));
    }

    final byte[] data = Mcv2Trees.write(width, height, 2, 1, Mcv2FrameKind.PREDICTED, roots);
    final byte[] reference = new byte[width * height * 3];
    random.nextBytes(reference);
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(data);
    compare(frame, reference, Mcv2Decoder.decode(frame, reference, 1), random);
  }

  private static void compare(final Mcv2Decoder.Frame frame, final byte[] reference, final byte[] picture, final Random random) {
    assertDoesNotThrow(() -> verify(frame, reference, frame.getReferenceId(), picture));

    final byte[] wrong = picture.clone();
    wrong[random.nextInt(wrong.length)] ^= 1;
    final IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
      verify(frame, reference, frame.getReferenceId(), wrong)
    );
    final @Nullable String message = failure.getMessage();
    assertEquals("MCV2 live picture and decoded picture disagree!", message);
  }

  private static void verify(final Mcv2Decoder.Frame frame, final byte[] reference, final long referenceId, final byte[] picture) {
    final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT, ForkJoinPool.commonPool(), 4, Mcv2.Verification.VERIFIED);
    final Object pending = Mcv2Internals.construct(
      Mcv2.Pending.class,
      new Class<?>[] {
        Mcv2.class,
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
    Mcv2Internals.call(Mcv2.class, encoder, "verify", new Class<?>[] { Mcv2.Pending.class }, pending);
  }

  @Test
  void shouldPartitionLargeFramesAndDetectEveryBoundaryPixel() {
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(
      Mcv2Trees.keyframe(31, ROOT_SIZE * 70 - 3, Mcv2Trees.repeat(Mcv2Trees.solid(17, 31, 63), 70))
    );
    final byte[] picture = Mcv2Decoder.decode(frame, null, 4);
    final byte[] reference = new byte[0];
    assertDoesNotThrow(() -> verify(frame, reference, 4, picture));

    for (final int row : new int[] { 0, 31, ROOT_SIZE, 2047, 2048, frame.getHeight() - 1 }) {
      for (final int column : new int[] { 0, 30 }) {
        this.assertEveryChannelChecked(frame, reference, picture, (row * 31 + column) * 3);
      }
    }
  }

  private void assertEveryChannelChecked(final Mcv2Decoder.Frame frame, final byte[] reference, final byte[] picture, final int offset) {
    for (int channel = 0; channel < 3; channel++) {
      final byte[] wrong = picture.clone();
      wrong[offset + channel] ^= 1;
      assertThrows(IllegalStateException.class, () -> verify(frame, reference, 4, wrong));
    }
  }

  @Test
  void shouldRejectPictureAndReferenceMismatch() {
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(Mcv2Trees.predicted(8, 8, Node.skip()));
    final byte[] picture = new byte[8 * 8 * 3];
    final IllegalStateException shortReference = assertThrows(IllegalStateException.class, () -> verify(frame, new byte[1], 0, picture));
    final @Nullable Throwable shortReferenceCause = shortReference.getCause();
    assertInstanceOf(Mcv2Exception.class, shortReferenceCause);

    final IllegalStateException wrongReferenceId = assertThrows(IllegalStateException.class, () -> verify(frame, picture, 5, picture));
    final @Nullable Throwable wrongReferenceIdCause = wrongReferenceId.getCause();
    assertInstanceOf(Mcv2Exception.class, wrongReferenceIdCause);
    assertThrows(IllegalArgumentException.class, () -> verify(frame, picture, 0, new byte[1]));
  }

  @Test
  void shouldStopTheEncoderWithTheDecoderFailureOnABrokenPendingReference() throws ReflectiveOperationException {
    final Mcv2 encoder = new Mcv2(Mcv2.Settings.FAST, ForkJoinPool.commonPool(), 1, Mcv2.Verification.VERIFIED);
    final byte[] picture = Mcv2Pictures.scene(ROOT_SIZE, ROOT_SIZE, 0, 2);
    encoder.encode(picture, ROOT_SIZE, ROOT_SIZE, 0);
    final Mcv2.Pending pending = encoder.begin(picture, ROOT_SIZE, ROOT_SIZE, 1);
    final boolean keyframe = pending.isKeyframe();
    assertFalse(keyframe);

    final Field referenceId = Mcv2.Pending.class.getDeclaredField("referenceId");
    referenceId.setAccessible(true);
    referenceId.setLong(pending, -1);
    final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> encoder.finish(pending));
    final @Nullable String message = failure.getMessage();
    assertEquals(DECODER_REJECTION_MESSAGE, message);

    final @Nullable Throwable cause = failure.getCause();
    assertInstanceOf(Mcv2Exception.class, cause);

    final @Nullable String causeMessage = failure.getCause().getMessage();
    assertEquals("Reference frame mismatch!", causeMessage);

    final IllegalStateException stopped = assertThrows(IllegalStateException.class, () -> encoder.begin(picture, ROOT_SIZE, ROOT_SIZE, 2));
    final @Nullable String stoppedMessage = stopped.getMessage();
    assertEquals("The encoder stopped after a frame failed its verification!", stoppedMessage);
  }

  @Test
  void shouldReportAFrameTheParserRejects() throws ReflectiveOperationException {
    final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT, ForkJoinPool.commonPool(), 1, Mcv2.Verification.VERIFIED);
    final Mcv2.Pending pending = encoder.begin(new byte[3], 1, 1, 0);
    final Field data = Mcv2.Pending.class.getDeclaredField("data");
    data.setAccessible(true);
    ((byte[]) data.get(pending))[0] = 0;
    final IllegalStateException error = assertThrows(IllegalStateException.class, () -> encoder.finish(pending));
    final @Nullable String message = error.getMessage();
    assertEquals(DECODER_REJECTION_MESSAGE, message);

    final @Nullable Throwable cause = error.getCause();
    assertInstanceOf(Mcv2Exception.class, cause);

    final @Nullable String causeMessage = error.getCause().getMessage();
    assertEquals("Not an MCV2 frame!", causeMessage);

    assertThrows(IllegalStateException.class, () -> encoder.encode(new byte[3], 1, 1, 1));
  }
}
