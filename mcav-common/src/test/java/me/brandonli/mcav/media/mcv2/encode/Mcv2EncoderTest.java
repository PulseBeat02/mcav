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
package me.brandonli.mcav.media.mcv2.encode;

import static me.brandonli.mcav.media.mcv2.Mcv2Frames.DERIVED;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.keyframe;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.predicted;
import static me.brandonli.mcav.media.mcv2.Mcv2Frames.solid;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.mcv2.FrameParser;
import me.brandonli.mcav.media.mcv2.Mcv2Decoder;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.media.mcv2.Mcv2Format;
import me.brandonli.mcav.media.mcv2.Mcv2Frame;
import me.brandonli.mcav.media.mcv2.Workers;
import me.brandonli.mcav.media.mcv2.encode.EncoderSettings.ReferencePolicy;
import org.junit.jupiter.api.Test;

/**
 * The encoder's frame decisions (keyframes, global motion trials, the reference it keeps) and its self-checks; that it
 * writes the reference encoder's bytes is {@link EncoderConformanceTest}.
 */
final class Mcv2EncoderTest {

  private static final ForkJoinPool POOL = ForkJoinPool.commonPool();

  /** A textured picture: smooth waves plus seeded noise. */
  static byte[] texture(final int width, final int height, final int seed) {
    final Random random = new Random(seed);
    final byte[] rgb = new byte[width * height * 3];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        final double wave = 45 * Math.sin((x + seed) / 6.0) * Math.cos(y / 5.0) + 25 * Math.sin((x - 2 * y) / 9.0);
        for (int c = 0; c < 3; c++) {
          rgb[(y * width + x) * 3 + c] = (byte) Math.min(255, Math.max(0, (int) (120 + wave + 25 * c + random.nextInt(16))));
        }
      }
    }
    return rgb;
  }

  /** The picture whose pixel (x, y) is the given one's (x + dx, y), wrapping around. */
  private static byte[] panned(final byte[] rgb, final int width, final int height, final int dx) {
    final byte[] out = new byte[rgb.length];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        System.arraycopy(rgb, (y * width + Math.floorMod(x + dx, width)) * 3, out, (y * width + x) * 3, 3);
      }
    }
    return out;
  }

  /** A client: decodes frames against the pictures of the frames it has decoded, by id. */
  private static final class Client {

    private final Map<Long, byte[]> pictures = new HashMap<>();

    byte[] decode(final byte[] data) throws Mcv2Exception {
      final Mcv2Frame frame = FrameParser.parse(data);
      final byte[] picture = Mcv2Decoder.decode(
        frame,
        frame.isKeyframe() ? null : this.pictures.get(frame.getReferenceId()),
        frame.getReferenceId()
      );
      this.pictures.put(frame.getFrameId(), picture);
      return picture;
    }
  }

  private static Mcv2Encoder encoder(final EncoderSettings settings) {
    return new Mcv2Encoder(settings, POOL, 2, true);
  }

  @Test
  void refusesInvalidArguments() {
    assertThrows(IllegalArgumentException.class, () -> new Mcv2Encoder(EncoderSettings.SHIP, POOL, 0, true));
    assertThrows(NullPointerException.class, () -> new Mcv2Encoder(null, POOL, 1, true));
    assertThrows(NullPointerException.class, () -> new Mcv2Encoder(EncoderSettings.SHIP, null, 1, true));
    final Mcv2Encoder encoder = encoder(EncoderSettings.SHIP);
    assertThrows(NullPointerException.class, () -> encoder.encode(null, 8, 8, 0));
    for (final int[] size : new int[][] { { 0, 8 }, { 4097, 8 }, { 8, 0 }, { 8, 4097 } }) {
      assertThrows(IllegalArgumentException.class, () -> encoder.encode(new byte[Math.max(0, size[0] * size[1] * 3)], size[0], size[1], 0));
    }
    assertThrows(IllegalArgumentException.class, () -> encoder.encode(new byte[8 * 8 * 3 - 1], 8, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> encoder.encode(new byte[8 * 8 * 3], 8, 8, -1));
    assertThrows(IllegalArgumentException.class, () -> encoder.encode(new byte[8 * 8 * 3], 8, 8, 1L << 32));
    encoder.encode(new byte[8 * 8 * 3], 8, 8, 5);
    // the same id again, an older one, and one so far ahead that it could be older
    assertThrows(IllegalArgumentException.class, () -> encoder.encode(new byte[8 * 8 * 3], 8, 8, 5));
    assertThrows(IllegalArgumentException.class, () -> encoder.encode(new byte[8 * 8 * 3], 8, 8, 4));
    assertThrows(IllegalArgumentException.class, () -> encoder.encode(new byte[8 * 8 * 3], 8, 8, 5 + 0x80000000L));
    // ids wrap around
    final Mcv2Encoder wrapping = encoder(EncoderSettings.SHIP);
    wrapping.encode(new byte[8 * 8 * 3], 8, 8, 0xFFFFFFFFL);
    wrapping.encode(new byte[8 * 8 * 3], 8, 8, 0);
    assertFalse(wrapping.getStats().keyframe());
  }

  @Test
  void startsWithAKeyframeAndPredictsWhatFollows() throws Mcv2Exception {
    final Mcv2Encoder encoder = encoder(EncoderSettings.SHIP);
    assertNull(encoder.getStats());
    assertNull(encoder.getReference());
    final Client client = new Client();
    final byte[] picture = texture(64, 64, 1);
    final byte[] first = encoder.encode(picture, 64, 64, 10);
    final Mcv2Encoder.Stats key = encoder.getStats();
    assertNotNull(key);
    assertTrue(key.keyframe());
    assertEquals(first.length, key.bytes());
    assertTrue(key.nanoseconds() > 0);
    assertArrayEquals(client.decode(first), encoder.getReference());
    final byte[] second = encoder.encode(picture, 64, 64, 11);
    final Mcv2Encoder.Stats still = encoder.getStats();
    assertFalse(still.keyframe());
    assertEquals(0, still.globalX());
    assertEquals(0, still.globalY());
    assertTrue(second.length < first.length);
    assertEquals(10, FrameParser.parse(second).getReferenceId());
    assertArrayEquals(client.decode(second), encoder.getReference());
    assertNotSame(encoder.getReference(), encoder.getReference());
  }

  private static boolean keyframeAfter(final Mcv2Encoder encoder, final byte[] next, final int width, final int height) {
    encoder.encode(next, width, height, 1);
    return encoder.getStats().keyframe();
  }

  @Test
  void startsAgainWithAKeyframeWhenPredictionCannotWork() {
    final byte[] picture = texture(64, 32, 2);
    // the key interval
    final Mcv2Encoder interval = encoder(EncoderSettings.SHIP.withKeyInterval(1));
    interval.encode(picture, 64, 32, 0);
    assertTrue(keyframeAfter(interval, picture, 64, 32));
    // a new width or height
    final Mcv2Encoder wider = encoder(EncoderSettings.SHIP);
    wider.encode(picture, 64, 32, 0);
    assertTrue(keyframeAfter(wider, texture(32, 64, 2), 32, 64));
    final Mcv2Encoder taller = encoder(EncoderSettings.SHIP);
    taller.encode(picture, 64, 32, 0);
    assertTrue(keyframeAfter(taller, texture(64, 64, 2), 64, 64));
    // a scene cut: the mean luma change after prediction is far above the threshold
    final Mcv2Encoder cut = encoder(EncoderSettings.SHIP);
    cut.encode(picture, 64, 32, 0);
    final byte[] white = new byte[picture.length];
    Arrays.fill(white, (byte) 255);
    assertTrue(keyframeAfter(cut, white, 64, 32));
    // the same change stays a P frame under a threshold above it
    final Mcv2Encoder tolerant = encoder(new EncoderSettings(65.255994022, 60, 24, true, true, 255.0, ReferencePolicy.PREVIOUS_FRAME));
    tolerant.encode(picture, 64, 32, 0);
    assertFalse(keyframeAfter(tolerant, white, 64, 32));
  }

  @Test
  void startsAgainWithAKeyframeOnRequest() {
    final byte[] picture = texture(64, 32, 2);
    final Mcv2Encoder encoder = encoder(EncoderSettings.SHIP);
    encoder.encode(picture, 64, 32, 0);
    encoder.requestKeyframe();
    assertTrue(keyframeAfter(encoder, picture, 64, 32));
  }

  @Test
  void followsAPanWithTheGlobalVector() throws Mcv2Exception {
    final byte[] picture = texture(128, 64, 3);
    final byte[] next = panned(picture, 128, 64, 8);
    for (final boolean compare : new boolean[] { true, false }) {
      final Mcv2Encoder encoder = encoder(new EncoderSettings(65.255994022, 60, 24, true, compare, 45.0, ReferencePolicy.PREVIOUS_FRAME));
      final Client client = new Client();
      client.decode(encoder.encode(picture, 128, 64, 0));
      final byte[] frame = encoder.encode(next, 128, 64, 1);
      final Mcv2Encoder.Stats stats = encoder.getStats();
      assertFalse(stats.keyframe());
      assertEquals(16, stats.globalX());
      assertEquals(0, stats.globalY());
      // with the zero vector compared there are four trials and the pan's are the last two
      assertEquals(compare ? 2 : 0, stats.trial() & 2);
      assertEquals(16, FrameParser.parse(frame).getGlobalX());
      assertArrayEquals(client.decode(frame), encoder.getReference());
    }
  }

  @Test
  void keepsTheKeyframeAsTheReferenceUnderTheKeyframePolicy() throws Mcv2Exception {
    final Mcv2Encoder encoder = encoder(EncoderSettings.SHIP.withReference(ReferencePolicy.LAST_KEYFRAME));
    final Client client = new Client();
    final byte[] picture = texture(64, 64, 4);
    final byte[] keyPicture = client.decode(encoder.encode(picture, 64, 64, 7));
    for (int id = 8; id < 11; id++) {
      final byte[] frame = encoder.encode(panned(picture, 64, 64, id - 7), 64, 64, id);
      assertEquals(7, FrameParser.parse(frame).getReferenceId());
      client.decode(frame);
      assertArrayEquals(keyPicture, encoder.getReference());
    }
  }

  @Test
  void encodesPicturesThatAreNotWholeBlocks() throws Mcv2Exception {
    final Client client = new Client();
    final Mcv2Encoder encoder = encoder(EncoderSettings.SHIP);
    final byte[] picture = texture(40, 20, 5);
    assertArrayEquals(client.decode(encoder.encode(picture, 40, 20, 0)), encoder.getReference());
    assertArrayEquals(client.decode(encoder.encode(panned(picture, 40, 20, 3), 40, 20, 1)), encoder.getReference());
    // a single pixel
    final Mcv2Encoder tiny = encoder(EncoderSettings.SHIP);
    assertArrayEquals(new Client().decode(tiny.encode(new byte[] { 1, 2, 3 }, 1, 1, 0)), tiny.getReference());
  }

  @Test
  void writesTheSameBytesWithoutVerification() {
    final byte[] picture = texture(64, 32, 6);
    final byte[] next = panned(picture, 64, 32, 2);
    final Mcv2Encoder checked = new Mcv2Encoder(EncoderSettings.SHIP, POOL, 1, true);
    final Mcv2Encoder unchecked = new Mcv2Encoder(EncoderSettings.SHIP, POOL, 3, false);
    assertArrayEquals(checked.encode(picture, 64, 32, 0), unchecked.encode(picture, 64, 32, 0));
    assertArrayEquals(checked.encode(next, 64, 32, 1), unchecked.encode(next, 64, 32, 1));
  }

  @Test
  void reportsAFrameTheParserRejects() {
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
      Mcv2Encoder.decodeChosen(new byte[48], new byte[0], 0, Workers.SEQUENTIAL)
    );
    assertEquals("The encoder wrote a frame the parser rejects", exception.getMessage());
  }

  @Test
  void reportsAParsedFrameTheDecoderRejects() throws Mcv2Exception {
    // a P frame the parser accepts, against a reference of another size: the live verify parses once, then decodes
    final Mcv2Frame frame = FrameParser.parse(predicted(8, 8, 0, 0, DERIVED, solid(1, 2, 3)));
    final IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
      Mcv2Encoder.decodeChosen(frame, new byte[3], frame.getReferenceId(), Workers.SEQUENTIAL, null)
    );
    assertEquals("The encoder wrote a frame the decoder rejects", exception.getMessage());
  }

  @Test
  void checksTheTreeAndEveryLeafOfTheKeptFrame() {
    final byte[] source = new byte[8 * 8 * 3];
    for (int i = 0; i < source.length; i += 3) {
      source[i] = 1;
      source[i + 1] = 2;
      source[i + 2] = 3;
    }
    final FrameJob job = new FrameJob(EncoderSettings.SHIP, source, new byte[0], 8, 8, true, new int[] { 0 }, new int[] { 0 }, null, null);
    final List<TreeNode> roots = List.of(solid(1, 2, 3));
    final byte[] data = keyframe(8, 8, DERIVED, solid(1, 2, 3));
    final byte[] picture = Mcv2Encoder.decodeChosen(data, new byte[0], 0, Workers.SEQUENTIAL);
    final List<Mcv2Encoder.Leaf> leaves = List.of(new Mcv2Encoder.Leaf(0, 0, 8, 2, 0));
    Mcv2Encoder.check(job, 0, data, picture, leaves, roots, Workers.SEQUENTIAL);
    assertEquals(
      "MCV2 encoder and serializer disagree about the tree",
      assertThrows(IllegalStateException.class, () ->
        Mcv2Encoder.check(job, 0, data, picture, leaves, List.of(solid(1, 2, 4)), Workers.SEQUENTIAL)
      ).getMessage()
    );
    assertEquals(
      "The encoder wrote a frame the parser rejects",
      assertThrows(IllegalStateException.class, () -> Mcv2Encoder.check(job, 0, new byte[48], picture, leaves, roots, Workers.SEQUENTIAL)
      ).getMessage()
    );
    // a pattern the chosen tree holds is expanded before the comparison, so an invalid one is a rejected frame too
    final TreeNode invalid = TreeNode.leaf(Mcv2Format.MODE_PATTERN, 0, new byte[] { 1, 2, 3, 4, 5, 6, 2, 0, 0, 0, 0 });
    assertThrows(IllegalStateException.class, () -> Mcv2Encoder.check(job, 0, data, picture, leaves, List.of(invalid), Workers.SEQUENTIAL));
    // the leaves are measured on the workers, and the first that disagrees in the list's order is the one reported
    final List<Mcv2Encoder.Leaf> many = new ArrayList<>();
    many.add(new Mcv2Encoder.Leaf(8, 0, 8, 2, 1));
    for (int i = 0; i < 64; i++) {
      many.add(new Mcv2Encoder.Leaf(0, 0, 8, 2, 0));
    }
    final Workers parallel = new Workers(POOL, 4);
    Mcv2Encoder.check(job, 0, data, picture, many, roots, parallel);
    job.set(0, 2, 0, 0, Mcv2Format.MODE_SOLID, 0, new byte[] { 1, 2, 3 }, 3, 5);
    assertEquals(
      "MCV2 encoder/decoder disagreement at 0,0 size 8",
      assertThrows(IllegalStateException.class, () -> Mcv2Encoder.check(job, 0, data, picture, leaves, roots, Workers.SEQUENTIAL)
      ).getMessage()
    );
    assertEquals(
      "MCV2 encoder/decoder disagreement at 0,0 size 8",
      assertThrows(IllegalStateException.class, () -> Mcv2Encoder.check(job, 0, data, picture, many, roots, parallel)).getMessage()
    );
  }

  @Test
  void halvesAPictureWithItsOddLastRowAndColumnStandingAlone() {
    // a 3x3 picture: the right column and the bottom row have no neighbour to share a square with
    final int[] red = { 0, 4, 9, 8, 12, 20, 100, 200, 255 };
    final byte[] picture = new byte[3 * 3 * 3];
    for (int i = 0; i < red.length; i++) {
      picture[i * 3] = (byte) red[i];
      picture[i * 3 + 1] = (byte) i;
    }
    final byte[] half = Mcv2Encoder.half(picture, 3, 3, Workers.SEQUENTIAL);
    assertArrayEquals(new byte[] { 6, 2, 0, 15, 4, 0, (byte) 150, 7, 0, (byte) 255, 8, 0 }, half);
  }
}
