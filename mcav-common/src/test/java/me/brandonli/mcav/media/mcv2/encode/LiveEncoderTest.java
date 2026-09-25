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

import static me.brandonli.mcav.media.mcv2.Mcv2Format.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.media.mcv2.FrameParser;
import me.brandonli.mcav.media.mcv2.Mcv2Decoder;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.media.mcv2.Mcv2Frame;
import org.junit.jupiter.api.Test;

/**
 * The live search end to end: whatever it chooses, every frame decodes to the picture the encoder keeps as its
 * reference, on pictures that are not whole blocks, through pans, still frames, scene cuts, size changes and every
 * search setting; and the output of the live profile is pinned, so later speed work cannot change it silently.
 */
final class LiveEncoderTest {

  private static final ForkJoinPool POOL = ForkJoinPool.commonPool();
  private static final int ALL = LiveSearch.ALL_MODES;

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

  /** A scene: textured, with a flat band, a two-colour checkerboard and a smooth ramp, panning by dx per frame. */
  static byte[] scene(final int width, final int height, final int frame, final int dx) {
    final Random random = new Random(frame * 7919L);
    final byte[] rgb = new byte[width * height * 3];
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        final int sx = x + frame * dx;
        final int at = (y * width + x) * 3;
        final int value;
        if (y < height / 4) {
          value = 90;
        } else if (y < height / 2) {
          value = ((sx / 4 + y / 4) & 1) == 0 ? 40 : 200;
        } else if (x < width / 3) {
          value = 30 + ((sx * 3 + y) % 180);
        } else {
          value = (int) (120 + 60 * Math.sin(sx / 7.0) * Math.cos(y / 5.0)) + random.nextInt(8);
        }
        rgb[at] = (byte) value;
        rgb[at + 1] = (byte) Math.min(255, value + 20);
        rgb[at + 2] = (byte) Math.max(0, value - 30);
      }
    }
    return rgb;
  }

  private static LiveSearch search(
    final int smallest,
    final double skip,
    final double split,
    final double fine,
    final double good,
    final int modes,
    final int keyModes,
    final int quantizers,
    final boolean seeded,
    final int searchBlock,
    final boolean coarse,
    final int fast
  ) {
    return new LiveSearch(
      smallest,
      skip,
      split,
      fine,
      good,
      0,
      modes,
      keyModes,
      LiveSearch.ALL_CLASSES,
      quantizers,
      seeded,
      searchBlock,
      coarse,
      fast
    );
  }

  /** Encodes a panning scene with verification on, and checks that a client decodes the encoder's references. */
  private static Mcv2Encoder play(final EncoderSettings settings, final int width, final int height, final int frames, final int dx)
    throws Mcv2Exception {
    final Mcv2Encoder encoder = new Mcv2Encoder(settings, POOL, 3, true);
    final Client client = new Client();
    for (int i = 0; i < frames; i++) {
      final byte[] data = encoder.encode(scene(width, height, i, dx), width, height, i);
      assertArrayEquals(client.decode(data), encoder.getReference(), "frame " + i);
      final Mcv2Encoder.Stats stats = encoder.getStats();
      assertNotNull(stats);
      assertEquals(data.length, stats.bytes());
      assertEquals(0, stats.trial());
    }
    return encoder;
  }

  @Test
  void decodesEveryFrameOfTheLiveProfile() throws Mcv2Exception {
    play(EncoderSettings.LIVE, 100, 70, 6, 2);
    play(EncoderSettings.LIVE, 64, 64, 4, 0);
  }

  @Test
  void decodesEveryFrameOfTheExactSearch() throws Mcv2Exception {
    // every mode and class, the reference's own motion search, one trial from the top
    play(EncoderSettings.SHIP.withLive(LiveSearch.EXACT), 100, 70, 5, 3);
  }

  @Test
  void decodesEveryFrameWhateverTheSearchSkips() throws Mcv2Exception {
    final EncoderSettings base = EncoderSettings.SHIP.withKeyInterval(4);
    // aggressive early exits, and motion inherited from 32 pixels down
    play(base.withLive(search(8, 200, 400, 400, 300, ALL, ALL, LiveSearch.FROM_LAMBDA, true, 32, true, 0)), 100, 70, 5, 2);
    // every cheap fit, and compact records on the closer prediction only
    play(base.withLive(search(8, LiveSearch.EXACT_SKIP, 0, 0, 0, ALL, ALL, 0b11111, true, 8, false, 0b1111)), 100, 70, 4, 2);
    // no mode needs local motion: the global prediction stands in for it
    play(
      base.withLive(
        search(16, LiveSearch.EXACT_SKIP, 52.5, 52.5, 0, (1 << MODE_SOLID) | (1 << MODE_PALETTE), 1 << MODE_SOLID, 1, true, 16, false, 0)
      ),
      100,
      70,
      4,
      2
    );
    // compact records on the closer prediction only, in a search that tries none
    play(
      base.withLive(search(16, LiveSearch.EXACT_SKIP, 52.5, 52.5, 0, 1 << MODE_MOTION, ALL, 1, true, 16, false, LiveSearch.ONE_PREDICTION)),
      64,
      64,
      3,
      2
    );
    // compact records of every class as the only other leaves: their chroma is loaded for them alone
    play(
      base.withLive(search(16, LiveSearch.EXACT_SKIP, 52.5, 52.5, 0, (1 << MODE_MOTION) | (1 << MODE_COMPACT), ALL, 1, true, 16, false, 0)),
      64,
      64,
      3,
      2
    );
    // quarters of split blocks that SKIP or local motion already code for their share stop there
    play(
      base.withLive(
        new LiveSearch(8, LiveSearch.EXACT_SKIP, 0, 0, 0, 1.0, ALL, ALL, LiveSearch.ALL_CLASSES, LiveSearch.FROM_LAMBDA, true, 16, true, 11)
      ),
      100,
      70,
      4,
      2
    );
    // 32-pixel leaves only
    play(base.withLive(search(32, LiveSearch.EXACT_SKIP, 52.5, 52.5, 0, ALL, ALL, 1, false, 32, false, 0)), 64, 64, 3, 1);
    // no zero-vector candidate
    play(
      new EncoderSettings(65.255994022, 60, 24, true, false, 45.0, EncoderSettings.ReferencePolicy.PREVIOUS_FRAME, LiveSearch.LIVE),
      100,
      70,
      3,
      4
    );
  }

  @Test
  void keepsTheKeyframeUnderTheKeyframePolicy() throws Mcv2Exception {
    // the encoder checks every P frame against the decoder itself; its reference stays the keyframe's picture while
    // the P frames' pictures take turns in the other buffer
    final Mcv2Encoder encoder = new Mcv2Encoder(
      EncoderSettings.LIVE.withReference(EncoderSettings.ReferencePolicy.LAST_KEYFRAME),
      POOL,
      2,
      true
    );
    final Client client = new Client();
    final byte[] keyPicture = client.decode(encoder.encode(scene(64, 48, 0, 1), 64, 48, 7));
    for (int id = 8; id < 12; id++) {
      final byte[] frame = encoder.encode(scene(64, 48, id - 7, 1), 64, 48, id);
      assertEquals(7, FrameParser.parse(frame).getReferenceId());
      client.decode(frame);
      assertArrayEquals(keyPicture, encoder.getReference());
    }
  }

  @Test
  void startsAgainAfterASceneCutAndASizeChange() throws Mcv2Exception {
    final Mcv2Encoder encoder = new Mcv2Encoder(EncoderSettings.LIVE, POOL, 2, true);
    final Client client = new Client();
    encoder.encode(scene(64, 64, 0, 0), 64, 64, 0);
    final byte[] inverted = scene(64, 64, 0, 0);
    for (int i = 0; i < inverted.length; i++) {
      inverted[i] = (byte) (255 - (inverted[i] & 0xFF));
    }
    client.decode(encoder.encode(inverted, 64, 64, 1));
    assertTrue(encoder.getStats().keyframe());
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
  void findsEachLeafsVector() {
    final int gx = 6;
    final int gy = -2;
    assertEquals((gx << 16) | (gy & 0xFFFF), Mcv2Encoder.leafVector(TreeNode.skip(), gx, gy));
    assertEquals((gx << 16) | (gy & 0xFFFF), Mcv2Encoder.leafVector(TreeNode.leaf(MODE_SOLID, 0, new byte[3]), gx, gy));
    assertEquals(
      ((gx + 3) << 16) | ((gy - 4) & 0xFFFF),
      Mcv2Encoder.leafVector(TreeNode.leaf(MODE_MOTION, 0, new byte[] { 3, -4 }), gx, gy)
    );
    final byte[] residual = new byte[2 + 3];
    residual[0] = -5;
    residual[1] = 7;
    assertEquals(((gx - 5) << 16) | ((gy + 7) & 0xFFFF), Mcv2Encoder.leafVector(TreeNode.leaf(MODE_RESIDUAL, 1, residual), gx, gy));
    // compact records: form 0 at the global vector, form 1 with nibbles, form 2 with bytes
    assertEquals((gx << 16) | (gy & 0xFFFF), Mcv2Encoder.leafVector(TreeNode.leaf(MODE_COMPACT, 0, new byte[] { 0, 1 }), gx, gy));
    final byte[] nibbles = { 0x13, (byte) 0xE2, 0, 0, 0, 0, 0, 0, 0, 0 };
    assertEquals(((gx + 2) << 16) | ((gy - 2) & 0xFFFF), Mcv2Encoder.leafVector(TreeNode.leaf(MODE_COMPACT, 0, nibbles), gx, gy));
    final byte[] bytes = { 0x23, 20, -30, 0, 0, 0, 0, 0, 0, 0, 0 };
    assertEquals(((gx + 20) << 16) | ((gy - 30) & 0xFFFF), Mcv2Encoder.leafVector(TreeNode.leaf(MODE_COMPACT, 0, bytes), gx, gy));
  }

  @Test
  void keepsOneVectorPerEightPixelCellOfTheLeaves() {
    // a 40x20 frame: two roots; the first split into a motion leaf and three skips, the second a solid leaf
    final TreeNode split = TreeNode.split(
      TreeNode.leaf(MODE_MOTION, 0, new byte[] { 4, 0 }),
      TreeNode.skip(),
      TreeNode.skip(),
      TreeNode.skip()
    );
    final int[] field = Mcv2Encoder.motionField(List.of(split, TreeNode.leaf(MODE_SOLID, 0, new byte[3])), 40, 20, 2, 0);
    assertEquals(5 * 3, field.length);
    assertEquals(6 << 16, field[0]);
    assertEquals(6 << 16, field[1]);
    assertEquals(2 << 16, field[2]);
    assertEquals(6 << 16, field[5]);
    assertEquals(2 << 16, field[4]);
    assertEquals(2 << 16, field[14]);
  }

  /** The FrameJob of one 32-pixel P frame block with a live search, to test the search's rules one block at a time. */
  private static FrameJob job(final LiveSearch live, final double lambda, final byte[] source, final byte[] reference) {
    final EncoderSettings settings = EncoderSettings.SHIP.withLambda(lambda).withLive(live);
    return new FrameJob(settings, source, reference, 32, 32, false, new int[] { 0 }, new int[] { 0 }, null, null);
  }

  private static byte[] noisy(final byte[] picture, final int amplitude, final long seed) {
    final Random random = new Random(seed);
    final byte[] out = picture.clone();
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Math.min(255, Math.max(0, (out[i] & 0xFF) + random.nextInt(2 * amplitude + 1) - amplitude));
    }
    return out;
  }

  private static long skipDistortion(final byte[] source, final byte[] reference) {
    final int[] s = new int[source.length];
    final int[] r = new int[source.length];
    for (int i = 0; i < s.length; i++) {
      s[i] = source[i] & 0xFF;
      r[i] = reference[i] & 0xFF;
    }
    return BlockCoder.distortion(s, r);
  }

  /**
   * The early SKIP is taken exactly when SKIP costs at most the threshold, so no block that differs from its prediction
   * by more is ever skipped without a search; and a block skipped at one lambda is skipped at every larger one.
   */
  @Test
  void skipsEarlyExactlyAtTheThresholdAndMonotonicallyInLambda() {
    final byte[] reference = scene(32, 32, 0, 0);
    final LiveSearch live = search(32, 40, 52.5, 52.5, 0, ALL, ALL, 1, true, 32, false, 0);
    for (int amplitude = 0; amplitude <= 12; amplitude += 2) {
      final byte[] source = noisy(reference, amplitude, amplitude);
      final long d = skipDistortion(source, reference);
      boolean skippedBefore = false;
      for (final double lambda : new double[] { 10, 30, 65.255994022, 150, 400, 1200 }) {
        final FrameJob job = job(live, lambda, source, reference);
        final BlockCoder coder = new BlockCoder(job, 32);
        coder.code(0, 0, 0, 0);
        final boolean expected = d / 96.0 + lambda <= 40 * lambda;
        assertEquals(expected, coder.isSkipped(), "amplitude " + amplitude + " lambda " + lambda);
        assertTrue(!skippedBefore || coder.isSkipped(), "monotonic in lambda");
        skippedBefore = coder.isSkipped();
        if (coder.isSkipped()) {
          assertEquals(MODE_SKIP, job.mode(0, 0, 0));
        }
      }
    }
  }

  @Test
  void seedsTheLocalSearchFromThePreviousFrame() {
    // a live job with the previous frame's motion: the seeds are read from its 8x8 cells, clamped to the picture
    final byte[] reference = scene(64, 64, 0, 0);
    final byte[] source = scene(64, 64, 2, 3);
    final int[] field = new int[8 * 8];
    java.util.Arrays.fill(field, 6 << 16);
    final EncoderSettings settings = EncoderSettings.LIVE;
    final FrameJob job = new FrameJob(settings, source, reference, 64, 64, false, new int[] { 0 }, new int[] { 0 }, field, null);
    assertEquals(6 << 16, job.previousMotion(-5, 100));
    final FrameJob fresh = new FrameJob(settings, source, reference, 64, 64, false, new int[] { 2 }, new int[] { -2 }, null, null);
    assertEquals((2 << 16) | (-2 & 0xFFFF), fresh.previousMotion(10, 10));
    // the live job has one trial, with the profile's endpoint precision
    assertEquals(1, job.trialCount());
    assertEquals(0, job.trialVector(0));
    assertTrue(job.isCoarse(0));
    assertEquals(1, job.coarseMask(true));
    assertEquals(0, job.coarseMask(false));
    final BlockCoder coder = new BlockCoder(job, 16);
    coder.code(1, 0, 0, 0, 6 << 16);
    assertTrue(job.cost(0, 1)[0] < Double.POSITIVE_INFINITY);
  }

  /**
   * The live profile's output on a small crop of a synthetic scene, pinned: a change to the live search's decisions or
   * to the bytes it writes shows here, and has to be made on purpose, with the report's lever table measured again.
   */
  @Test
  void pinsTheOutputOfTheLiveProfile() throws NoSuchAlgorithmException {
    final Mcv2Encoder encoder = new Mcv2Encoder(EncoderSettings.LIVE, POOL, 2, false);
    final MessageDigest digest = MessageDigest.getInstance("SHA-256");
    for (int i = 0; i < 8; i++) {
      final byte[] data = encoder.encode(scene(96, 64, i, 2), 96, 64, i);
      digest.update(data);
    }
    assertEquals(LIVE_DIGEST, HexFormat.of().formatHex(digest.digest()));
  }

  /** The SHA-256 of the eight frames {@link #pinsTheOutputOfTheLiveProfile} encodes. */
  static final String LIVE_DIGEST = "52ce73353a8e62b18474845e57428796107b80bab1ad12b1ab70ea6360039f5b";
}
