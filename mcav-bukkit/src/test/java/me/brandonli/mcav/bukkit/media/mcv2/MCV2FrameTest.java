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

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_SKIP;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder.MODE_SOLID;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.bukkit.media.mcv2.MCV2.Settings;
import org.junit.jupiter.api.Test;

final class MCV2FrameTest {

  private static final ForkJoinPool POOL = ForkJoinPool.commonPool();
  private static final int LIMIT_TRIES = 5;

  private static byte[] panned(final byte[] rgb, final int width, final int height, final int panX) {
    final byte[] out = new byte[rgb.length];
    for (int row = 0; row < height; row++) {
      for (int column = 0; column < width; column++) {
        System.arraycopy(rgb, (row * width + Math.floorMod(column + panX, width)) * 3, out, (row * width + column) * 3, 3);
      }
    }
    return out;
  }

  private static MCV2 encoder(final Settings settings) {
    return new MCV2(settings, POOL, 3, true);
  }

  private static byte[] scene(final int width, final int height, final int frame, final int pan) {
    return Mcv2Pictures.scene(width, height, frame, pan);
  }

  private static final class Client {

    private final Mcv2Receiver receiver = new Mcv2Receiver();

    private byte[] decode(final byte[] data) throws Mcv2Exception {
      return Objects.requireNonNull(this.receiver.accept(data));
    }
  }

  private static MCV2 play(final Settings settings, final int width, final int height, final int frames, final int pan)
    throws Mcv2Exception {
    final MCV2 encoder = encoder(settings);
    final Client client = new Client();
    for (int index = 0; index < frames; index++) {
      final byte[] data = encoder.encode(scene(width, height, index, pan), width, height, index);
      assertArrayEquals(client.decode(data), encoder.getReference());
      assertEquals(index, Mcv2Decoder.parse(data).getFrameId());
      assertEquals(data.length, Objects.requireNonNull(encoder.getStats()).bytes());
      assertEquals(index == 0, encoder.getStats().keyframe());
      assertTrue(encoder.getStats().leaves() > 0);
      assertTrue(encoder.getStats().nanoseconds() > 0);
    }
    return encoder;
  }

  static byte[] texture(final int width, final int height, final int seed) {
    final Random random = new Random(seed);
    final byte[] rgb = new byte[width * height * 3];
    for (int row = 0; row < height; row++) {
      for (int column = 0; column < width; column++) {
        final double wave = 45 * Math.sin((column + seed) / 6.0) * Math.cos(row / 5.0) + 25 * Math.sin((column - 2 * row) / 9.0);
        for (int channel = 0; channel < 3; channel++) {
          rgb[(row * width + column) * 3 + channel] = (byte) Math.min(
            255,
            Math.max(0, (int) (120 + wave + 25 * channel + random.nextInt(16)))
          );
        }
      }
    }
    return rgb;
  }

  private static boolean keyframeAfter(final MCV2 encoder, final byte[] next, final int width, final int height) {
    encoder.encode(next, width, height, 1);
    return encoder.getStats().keyframe();
  }

  private static byte[] noise(final int width, final int height, final long seed) {
    final byte[] rgb = new byte[width * height * 3];
    new Random(seed).nextBytes(rgb);
    return rgb;
  }

  @Test
  void keepsTheSettingsItWasMadeWith() {
    assertSame(Settings.FAST, encoder(Settings.FAST).getSettings());
    assertSame(Settings.DEFAULT, encoder(Settings.DEFAULT).getSettings());
  }

  @Test
  void refusesInvalidArguments() {
    assertThrows(IllegalArgumentException.class, () -> new MCV2(Settings.DEFAULT, POOL, 0, true));
    assertThrows(NullPointerException.class, () -> new MCV2(null, POOL, 1, true));
    assertThrows(NullPointerException.class, () -> new MCV2(Settings.DEFAULT, null, 1, true));
    final MCV2 encoder = encoder(Settings.DEFAULT);
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
    final MCV2 wrapping = encoder(Settings.DEFAULT);
    wrapping.encode(new byte[8 * 8 * 3], 8, 8, 0xFFFFFFFFL);
    wrapping.encode(new byte[8 * 8 * 3], 8, 8, 0);
    assertFalse(wrapping.getStats().keyframe());
  }

  @Test
  void startsWithAKeyframeAndPredictsWhatFollows() throws Mcv2Exception {
    final MCV2 encoder = encoder(Settings.DEFAULT);
    assertNull(encoder.getStats());
    assertNull(encoder.getReference());
    final Client client = new Client();
    final byte[] picture = texture(64, 64, 1);
    final byte[] first = encoder.encode(picture, 64, 64, 10);
    final MCV2.Stats key = encoder.getStats();
    assertNotNull(key);
    assertTrue(key.keyframe());
    assertEquals(first.length, key.bytes());
    assertTrue(key.nanoseconds() > 0);
    assertArrayEquals(client.decode(first), encoder.getReference());
    final byte[] second = encoder.encode(picture, 64, 64, 11);
    final MCV2.Stats still = encoder.getStats();
    assertFalse(still.keyframe());
    assertTrue(second.length < first.length);
    assertEquals(10, Mcv2Decoder.parse(second).getReferenceId());
    assertArrayEquals(client.decode(second), encoder.getReference());
    assertNotSame(encoder.getReference(), encoder.getReference());
  }

  @Test
  void startsAgainWithAKeyframeWhenPredictionCannotWork() {
    final byte[] picture = texture(64, 32, 2);
    // the key interval
    final MCV2 interval = encoder(new Settings(72, false));
    for (int id = 0; id < 120; id++) {
      interval.encode(picture, 64, 32, id);
      assertEquals(id == 0, interval.getStats().keyframe());
    }
    interval.encode(picture, 64, 32, 120);
    assertTrue(interval.getStats().keyframe());
    // a new width or height
    final MCV2 wider = encoder(Settings.DEFAULT);
    wider.encode(picture, 64, 32, 0);
    assertTrue(keyframeAfter(wider, texture(32, 64, 2), 32, 64));
    final MCV2 taller = encoder(Settings.DEFAULT);
    taller.encode(picture, 64, 32, 0);
    assertTrue(keyframeAfter(taller, texture(64, 64, 2), 64, 64));
  }

  @Test
  void startsAgainWithAKeyframeOnRequest() {
    final byte[] picture = texture(64, 32, 2);
    final MCV2 encoder = encoder(Settings.DEFAULT);
    encoder.encode(picture, 64, 32, 0);
    encoder.requestKeyframe();
    assertTrue(keyframeAfter(encoder, picture, 64, 32));
  }

  @Test
  void encodesPicturesThatAreNotWholeBlocks() throws Mcv2Exception {
    final Client client = new Client();
    final MCV2 encoder = encoder(Settings.DEFAULT);
    final byte[] picture = texture(40, 20, 5);
    assertArrayEquals(client.decode(encoder.encode(picture, 40, 20, 0)), encoder.getReference());
    assertArrayEquals(client.decode(encoder.encode(panned(picture, 40, 20, 3), 40, 20, 1)), encoder.getReference());
    // a single pixel
    final MCV2 tiny = encoder(Settings.DEFAULT);
    assertArrayEquals(new Client().decode(tiny.encode(new byte[] { 1, 2, 3 }, 1, 1, 0)), tiny.getReference());
  }

  @Test
  void writesTheSameBytesWithoutVerification() {
    final byte[] picture = texture(64, 32, 6);
    final byte[] next = panned(picture, 64, 32, 2);
    final MCV2 checked = new MCV2(Settings.DEFAULT, POOL, 1, true);
    final MCV2 unchecked = new MCV2(Settings.DEFAULT, POOL, 3, false);
    assertArrayEquals(checked.encode(picture, 64, 32, 0), unchecked.encode(picture, 64, 32, 0));
    assertArrayEquals(checked.encode(next, 64, 32, 1), unchecked.encode(next, 64, 32, 1));
  }

  @Test
  void decodesEveryFrameOfTheLiveProfile() throws Mcv2Exception {
    play(Settings.DEFAULT, 100, 70, 6, 2);
    play(Settings.DEFAULT, 64, 64, 4, 0);
  }

  @Test
  void raisesTheLambdaOfFastMotion() throws Mcv2Exception {
    final Settings settings = Settings.DEFAULT;
    // noise that changes from frame to frame but no movement keeps the profile's lambda, which is all a search without
    // the motion's lambda uses
    assertEquals(72, play(settings, 64, 64, 4, 0).getStats().lambda());
    // a fast pan raises it
    final MCV2 pan = play(settings, 100, 70, 6, 9);
    assertTrue(pan.getStats().lambda() > 72);
  }

  @Test
  void decodesEveryFrameOfTheQuarterResolutionSearch() throws Mcv2Exception {
    play(Settings.DEFAULT, 100, 70, 5, 5);
    play(Settings.DEFAULT, 64, 64, 4, 2);
    play(Settings.FAST, 100, 70, 4, 3);
  }

  @Test
  void startsAgainAfterASizeChange() throws Mcv2Exception {
    final MCV2 encoder = new MCV2(Settings.DEFAULT, POOL, 2, true);
    final Client client = new Client();
    client.decode(encoder.encode(scene(64, 64, 0, 0), 64, 64, 0));
    final byte[] inverted = scene(64, 64, 0, 0);
    for (int index = 0; index < inverted.length; index++) {
      inverted[index] = (byte) (255 - (inverted[index] & 0xFF));
    }
    assertArrayEquals(client.decode(encoder.encode(inverted, 64, 64, 1)), encoder.getReference());
    assertFalse(encoder.getStats().keyframe());
    final byte[] resized = encoder.encode(scene(96, 32, 2, 0), 96, 32, 2);
    assertArrayEquals(client.decode(resized), encoder.getReference());
    assertTrue(encoder.getStats().keyframe());
    final byte[] next = encoder.encode(scene(96, 32, 3, 0), 96, 32, 3);
    assertArrayEquals(client.decode(next), encoder.getReference());
    assertFalse(encoder.getStats().keyframe());
    // the same width and another height is another size too
    final byte[] taller = encoder.encode(scene(96, 48, 4, 0), 96, 48, 4);
    assertArrayEquals(client.decode(taller), encoder.getReference());
    assertTrue(encoder.getStats().keyframe());
  }

  @Test
  void endsAFrameSoonAfterItsBudgetWithTheCheapestChoices() throws Mcv2Exception {
    final MCV2 hurried = new MCV2(Settings.DEFAULT, POOL, 2, true);
    assertThrows(IllegalArgumentException.class, () -> hurried.setFrameBudget(-1));
    // a budget every superblock starts past: a keyframe of one solid colour per superblock, SKIP where the colour is
    // the frame's commonest, and a P frame of SKIP everywhere, each decoding to the encoder's own picture
    hurried.setFrameBudget(1);
    final Client client = new Client();
    final Mcv2Decoder.Frame keyframe = Mcv2Decoder.parse(hurried.encode(scene(96, 64, 0, 3), 96, 64, 0));
    assertTrue(keyframe.isKeyframe());
    assertEquals(6, keyframe.getLeafCount());
    for (int leafIndex = 0; leafIndex < keyframe.getLeafCount(); leafIndex++) {
      final Mcv2Decoder.Leaf leaf = keyframe.getLeaf(leafIndex);
      assertEquals(32, leaf.size());
      assertTrue(leaf.mode() == MODE_SOLID || leaf.mode() == MODE_SKIP, "mode " + leaf.mode());
    }
    assertArrayEquals(hurried.getReference(), client.decode(keyframe.getData()));
    final byte[] next = hurried.encode(scene(96, 64, 1, 3), 96, 64, 1);
    final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(next);
    assertEquals(6, frame.getLeafCount());
    for (int leafIndex = 0; leafIndex < frame.getLeafCount(); leafIndex++) {
      assertEquals(MODE_SKIP, frame.getLeaf(leafIndex).mode());
    }
    assertArrayEquals(hurried.getReference(), client.decode(next));
    // a budget no frame reaches changes nothing, and none is the default
    final MCV2 patient = new MCV2(Settings.DEFAULT, POOL, 2, true);
    patient.setFrameBudget(TimeUnit.HOURS.toNanos(1));
    final MCV2 unbounded = new MCV2(Settings.DEFAULT, POOL, 2, true);
    for (int frameNumber = 0; frameNumber < 4; frameNumber++) {
      final byte[] picture = scene(96, 64, frameNumber, 3);
      assertArrayEquals(unbounded.encode(picture, 96, 64, frameNumber), patient.encode(picture, 96, 64, frameNumber));
    }
    patient.setFrameBudget(0);
    assertArrayEquals(unbounded.encode(scene(96, 64, 4, 3), 96, 64, 4), patient.encode(scene(96, 64, 4, 3), 96, 64, 4));
  }

  @Test
  void switchesLiveProfilesWithoutAKeyframe() throws Mcv2Exception {
    final MCV2 encoder = new MCV2(Settings.DEFAULT, POOL, 2, true);
    final Client client = new Client();
    for (int frameNumber = 0; frameNumber < 8; frameNumber++) {
      if (frameNumber == 3) {
        encoder.switchTo(Settings.FAST);
        assertEquals(Settings.FAST, encoder.getSettings());
      } else if (frameNumber == 6) {
        encoder.switchTo(Settings.DEFAULT);
      }
      final byte[] data = encoder.encode(scene(96, 64, frameNumber, 3), 96, 64, frameNumber);
      assertEquals(frameNumber == 0, Objects.requireNonNull(encoder.getStats()).keyframe(), "frame " + frameNumber);
      assertArrayEquals(client.decode(data), encoder.getReference(), "frame " + frameNumber);
    }
    assertThrows(NullPointerException.class, () -> encoder.switchTo(null));
  }

  @Test
  void searchesAFrameOverItsByteBoundAgainAtAHigherLambda() throws Mcv2Exception {
    final byte[] picture = noise(96, 64, 5);
    final byte[][] keyframes = new byte[LIMIT_TRIES][];
    for (int attempt = 0; attempt < LIMIT_TRIES; attempt++) {
      keyframes[attempt] = new MCV2(Settings.DEFAULT.withLambda(72 << attempt), POOL, 2, false).encode(picture, 96, 64, 0);
    }
    for (int bound : new int[] { keyframes[0].length, keyframes[0].length - 1, keyframes[2].length, 1 }) {
      int expected = 0;
      while (expected < LIMIT_TRIES - 1 && keyframes[expected].length > bound) {
        expected++;
      }
      final MCV2 bounded = new MCV2(Settings.DEFAULT, POOL, 2, true);
      bounded.setFrameLimit(bound);
      final byte[] actual = bounded.encode(picture, 96, 64, 0);
      if (keyframes[expected].length <= bound) {
        assertArrayEquals(keyframes[expected], actual, "bound " + bound);
      } else {
        final MCV2 trivial = new MCV2(Settings.DEFAULT, POOL, 2, true);
        trivial.setFrameBudget(1);
        assertArrayEquals(trivial.encode(picture, 96, 64, 0), actual, "bound " + bound);
      }
      assertEquals(72 << expected, Objects.requireNonNull(bounded.getStats()).lambda());
      // the P frame after it: within the bound when some lambda gets it there, and decoded as the encoder chose it
      final Client client = new Client();
      client.decode(actual);
      final byte[] next = bounded.encode(noise(96, 64, 6), 96, 64, 1);
      assertArrayEquals(client.decode(next), bounded.getReference(), "bound " + bound);
    }
    // no bound: every frame is searched once
    final MCV2 unbounded = new MCV2(Settings.DEFAULT, POOL, 2, false);
    unbounded.setFrameLimit(0);
    assertArrayEquals(keyframes[0], unbounded.encode(picture, 96, 64, 0));
    assertThrows(IllegalArgumentException.class, () -> unbounded.setFrameLimit(-1));
  }
}
