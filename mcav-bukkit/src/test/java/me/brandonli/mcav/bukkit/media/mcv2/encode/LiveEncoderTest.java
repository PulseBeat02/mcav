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
package me.brandonli.mcav.bukkit.media.mcv2.encode;

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_COMPACT;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_MOTION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_PALETTE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_PATTERN;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_RESIDUAL;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_SKIP;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_SOLID;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.bukkit.media.mcv2.CompactRecord;
import me.brandonli.mcav.bukkit.media.mcv2.FrameParser;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Frame;
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
  static final class Client {

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
      split,
      fine,
      good,
      0,
      modes,
      modes,
      keyModes,
      LiveSearch.ALL_CLASSES,
      quantizers,
      seeded,
      searchBlock,
      coarse,
      fast,
      0,
      false
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
    // compact luma grids as the only YCoCg candidates of the P frames: their chroma is never converted
    final int luma = (1 << MODE_MOTION) | (1 << MODE_PALETTE) | (1 << MODE_COMPACT) | (1 << MODE_PATTERN);
    play(
      EncoderSettings.LIVE.withLive(
        new LiveSearch(
          8,
          LiveSearch.EXACT_SKIP,
          150,
          450,
          300,
          0,
          0,
          luma,
          luma,
          ALL,
          1 << CompactRecord.GRID4_N4_Y,
          LiveSearch.FROM_LAMBDA,
          true,
          16,
          true,
          LiveSearch.FAST_GRIDS | LiveSearch.FAST_PALETTES | LiveSearch.ONE_PREDICTION,
          0,
          false
        )
      ),
      100,
      70,
      6,
      2
    );
    // quarters of split blocks that SKIP or local motion already code for their share stop there
    play(
      base.withLive(
        new LiveSearch(
          8,
          LiveSearch.EXACT_SKIP,
          0,
          0,
          0,
          0,
          1.0,
          ALL,
          ALL,
          ALL,
          LiveSearch.ALL_CLASSES,
          LiveSearch.FROM_LAMBDA,
          true,
          16,
          true,
          11,
          0,
          false
        )
      ),
      100,
      70,
      4,
      2
    );
    // the split blocks try local motion alone, the root every mode
    play(
      base.withLive(
        new LiveSearch(
          8,
          LiveSearch.EXACT_SKIP,
          0,
          0,
          0,
          0,
          0,
          ALL,
          1 << MODE_MOTION,
          ALL,
          LiveSearch.ALL_CLASSES,
          LiveSearch.FROM_LAMBDA,
          true,
          16,
          true,
          0,
          0,
          false
        )
      ),
      64,
      64,
      3,
      2
    );
    // a superblock the previous frame coded whole is split only above the steady threshold, one it split above zero
    play(
      base.withLive(
        new LiveSearch(
          8,
          LiveSearch.EXACT_SKIP,
          0,
          1e9,
          0,
          0,
          0,
          ALL,
          ALL,
          ALL,
          LiveSearch.ALL_CLASSES,
          LiveSearch.FROM_LAMBDA,
          true,
          16,
          true,
          0,
          0,
          false
        )
      ),
      100,
      70,
      5,
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

  /** The live profile's search with another split threshold, shortcuts and lambda. */
  private static LiveSearch live(final double splitAbove, final int shortcuts, final boolean motionLambda) {
    final LiveSearch l = LiveSearch.LIVE;
    return new LiveSearch(
      l.smallestBlock(),
      l.skipThreshold(),
      l.splitThreshold(),
      l.steadySplitThreshold(),
      l.fineThreshold(),
      l.goodThreshold(),
      l.childGate(),
      l.modes(),
      l.smallModes(),
      l.keyModes(),
      l.compactClasses(),
      l.quantizers(),
      l.seededMotion(),
      l.searchBlock(),
      l.coarseEndpoints(),
      shortcuts,
      splitAbove,
      motionLambda
    );
  }

  @Test
  void decodesEveryFrameOfTheCellFits() throws Mcv2Exception {
    final EncoderSettings base = EncoderSettings.SHIP.withKeyInterval(4);
    // every mode and class: the reduced grids, intra and residual, and every compact grid class from cell means
    final LiveSearch all = new LiveSearch(
      8,
      LiveSearch.EXACT_SKIP,
      52.5,
      52.5,
      52.5,
      0,
      0,
      ALL,
      ALL,
      ALL,
      LiveSearch.ALL_CLASSES,
      LiveSearch.FROM_LAMBDA,
      true,
      16,
      true,
      LiveSearch.CELL_FITS,
      0,
      false
    );
    play(base.withLive(all), 100, 70, 5, 2);
    play(EncoderSettings.LIVE.withLive(live(0, LiveSearch.LIVE.shortcuts() | LiveSearch.CELL_FITS, false)), 100, 70, 4, 2);
  }

  /** A flat picture whose colour moves by a step every frame, which neither SKIP nor motion codes. */
  private static byte[] flat(final int width, final int height, final int frame) {
    final byte[] rgb = new byte[width * height * 3];
    for (int i = 0; i < rgb.length; i += 3) {
      rgb[i] = (byte) (60 + 12 * frame);
      rgb[i + 1] = (byte) (90 + 12 * frame);
      rgb[i + 2] = (byte) (40 + 12 * frame);
    }
    return rgb;
  }

  /** Encodes the flat scene, checking every frame, and counts the P frames' 32-pixel leaves that are not SKIP or motion. */
  private static int wholeLeaves(final LiveSearch live) throws Mcv2Exception {
    final Mcv2Encoder encoder = new Mcv2Encoder(EncoderSettings.LIVE.withLive(live), POOL, 2, true);
    final Client client = new Client();
    int whole = 0;
    for (int i = 0; i < 5; i++) {
      final byte[] data = encoder.encode(flat(64, 64, i), 64, 64, i);
      assertArrayEquals(client.decode(data), encoder.getReference());
      final Mcv2Frame frame = FrameParser.parse(data);
      for (int k = 0; k < frame.getLeafCount() && !frame.isKeyframe(); k++) {
        final Mcv2Frame.Leaf leaf = frame.getLeaf(k);
        if (leaf.size() == 32 && leaf.mode() != MODE_SKIP && leaf.mode() != MODE_MOTION) {
          whole++;
        }
      }
    }
    return whole;
  }

  @Test
  void splitsTheSuperblocksAboveTheThresholdWithoutTryingTheirLeaves() throws Mcv2Exception {
    // a new flat colour is coded whole by a 32-pixel leaf, unless the superblock is split before its leaves are tried
    assertTrue(wholeLeaves(LiveSearch.LIVE) > 0);
    assertEquals(0, wholeLeaves(live(1e-9, LiveSearch.LIVE.shortcuts(), true)));
    // a threshold above what SKIP costs there leaves the decision alone
    assertEquals(wholeLeaves(LiveSearch.LIVE), wholeLeaves(live(1e9, LiveSearch.LIVE.shortcuts(), true)));
  }

  @Test
  void raisesTheLambdaOfFastMotionAndStartsOverAtASceneCut() throws Mcv2Exception {
    final EncoderSettings settings = EncoderSettings.LIVE;
    // noise that changes from frame to frame but no movement keeps the profile's lambda, which is all a search without
    // the motion's lambda uses
    assertEquals(72, play(settings, 64, 64, 4, 0).getStats().lambda());
    assertEquals(
      72,
      play(EncoderSettings.LIVE.withLive(live(0, LiveSearch.LIVE.shortcuts(), false)), 100, 70, 6, 9)
        .getStats()
        .lambda()
    );
    // a fast pan raises it
    final Mcv2Encoder pan = play(settings, 100, 70, 6, 9);
    assertTrue(pan.getStats().lambda() > 72);
    // the frame after a scene cut is back at the profile's lambda
    final byte[] inverted = scene(100, 70, 6, 9);
    for (int i = 0; i < inverted.length; i++) {
      inverted[i] = (byte) (255 - (inverted[i] & 0xFF));
    }
    pan.encode(inverted, 100, 70, 6);
    assertTrue(pan.getStats().keyframe());
    pan.encode(inverted, 100, 70, 7);
    assertEquals(72, pan.getStats().lambda());
  }

  @Test
  void decodesEveryFrameOfTheQuarterResolutionSearch() throws Mcv2Exception {
    final int shortcuts = LiveSearch.LIVE.shortcuts() | LiveSearch.QUARTER_MOTION;
    play(EncoderSettings.LIVE.withLive(live(0, shortcuts, false)), 100, 70, 5, 5);
    play(EncoderSettings.LIVE.withLive(live(0, shortcuts, false)), 64, 64, 4, 2);
    // without the half-resolution search there is no quarter-resolution one either
    play(EncoderSettings.LIVE.withLive(live(0, LiveSearch.QUARTER_MOTION, false)), 100, 70, 4, 3);
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
    // each superblock's task fills its own cells
    final List<TreeNode> roots = List.of(split, TreeNode.leaf(MODE_SOLID, 0, new byte[3]));
    final int[] field = new int[5 * 3];
    for (int i = 0; i < roots.size(); i++) {
      Mcv2Encoder.fillMotion(field, 40, 20, roots.get(i), i, 2, 0);
    }
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
    Arrays.fill(field, 6 << 16);
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

  @Test
  void endsAFrameSoonAfterItsBudgetWithTheCheapestChoices() throws Mcv2Exception {
    final Mcv2Encoder hurried = new Mcv2Encoder(EncoderSettings.LIVE, POOL, 2, true);
    assertThrows(IllegalArgumentException.class, () -> hurried.setFrameBudget(-1));
    // a budget every superblock starts past: a keyframe of one solid colour per superblock, SKIP where the colour is
    // the frame's commonest, and a P frame of SKIP everywhere, each decoding to the encoder's own picture
    hurried.setFrameBudget(1);
    final Client client = new Client();
    final Mcv2Frame keyframe = FrameParser.parse(hurried.encode(scene(96, 64, 0, 3), 96, 64, 0));
    assertTrue(keyframe.isKeyframe());
    assertEquals(6, keyframe.getLeafCount());
    for (int i = 0; i < keyframe.getLeafCount(); i++) {
      final Mcv2Frame.Leaf leaf = keyframe.getLeaf(i);
      assertEquals(32, leaf.size());
      assertTrue(leaf.mode() == MODE_SOLID || leaf.mode() == MODE_SKIP, "mode " + leaf.mode());
    }
    assertArrayEquals(hurried.getReference(), client.decode(keyframe.getData()));
    final byte[] next = hurried.encode(scene(96, 64, 1, 3), 96, 64, 1);
    final Mcv2Frame frame = FrameParser.parse(next);
    assertEquals(6, frame.getLeafCount());
    for (int i = 0; i < frame.getLeafCount(); i++) {
      assertEquals(MODE_SKIP, frame.getLeaf(i).mode());
    }
    assertArrayEquals(hurried.getReference(), client.decode(next));
    // a budget no frame reaches changes nothing, and none is the default
    final Mcv2Encoder patient = new Mcv2Encoder(EncoderSettings.LIVE, POOL, 2, true);
    patient.setFrameBudget(TimeUnit.HOURS.toNanos(1));
    final Mcv2Encoder unbounded = new Mcv2Encoder(EncoderSettings.LIVE, POOL, 2, true);
    for (int i = 0; i < 4; i++) {
      final byte[] picture = scene(96, 64, i, 3);
      assertArrayEquals(unbounded.encode(picture, 96, 64, i), patient.encode(picture, 96, 64, i));
    }
    patient.setFrameBudget(0);
    assertArrayEquals(unbounded.encode(scene(96, 64, 4, 3), 96, 64, 4), patient.encode(scene(96, 64, 4, 3), 96, 64, 4));
  }

  /**
   * The output of each live profile on a small crop of a synthetic scene, pinned: a change to a live search's decisions
   * or to the bytes it writes shows here, and has to be made on purpose, with the report's ladder measured again.
   */
  @Test
  void pinsTheOutputOfTheLiveProfiles() throws NoSuchAlgorithmException {
    assertEquals(LIVE_DIGEST, digest(new Mcv2Encoder(EncoderSettings.LIVE, POOL, 2, false)));
    assertEquals(LIVE_FAST_DIGEST, digest(new Mcv2Encoder(EncoderSettings.LIVE_FAST, POOL, 2, false)));
    // the scene pans too slowly to count as motion: the adaptive profile codes it as the live one does; panning fast,
    // it switches to the live-fast search, and its stream is neither profile's
    assertEquals(LIVE_DIGEST, digest(new Mcv2Encoder(EncoderSettings.LIVE_ADAPTIVE, POOL, 2, false)));
    final String moving = digest(new Mcv2Encoder(EncoderSettings.LIVE_ADAPTIVE, POOL, 2, false), FAST_PAN, 16);
    assertEquals(ADAPTIVE_MOVING_DIGEST, moving);
    assertNotEquals(digest(new Mcv2Encoder(EncoderSettings.LIVE, POOL, 2, false), FAST_PAN, 16), moving);
    assertNotEquals(digest(new Mcv2Encoder(EncoderSettings.LIVE_FAST, POOL, 2, false), FAST_PAN, 16), moving);
  }

  /**
   * A live encoder switches to another live profile between frames without a keyframe: the frames after the switch are
   * P frames of the new search and lambda, and a client decodes the whole stream to the encoder's pictures. Only live
   * profiles switch; a search that does not measure the source's motion stops the lambda rising with it.
   */
  @Test
  void switchesLiveProfilesWithoutAKeyframe() throws Mcv2Exception {
    final Mcv2Encoder encoder = new Mcv2Encoder(EncoderSettings.LIVE, POOL, 2, true);
    final Client client = new Client();
    for (int i = 0; i < 8; i++) {
      if (i == 3) {
        encoder.switchTo(EncoderSettings.LIVE_FAST);
        assertEquals(EncoderSettings.LIVE_FAST, encoder.getSettings());
      } else if (i == 6) {
        encoder.switchTo(EncoderSettings.LIVE);
      }
      final byte[] data = encoder.encode(scene(96, 64, i, 3), 96, 64, i);
      assertEquals(i == 0, Objects.requireNonNull(encoder.getStats()).keyframe(), "frame " + i);
      assertArrayEquals(client.decode(data), encoder.getReference(), "frame " + i);
    }
    // a search that does not measure the motion codes at the profile's own lambda
    final LiveSearch f = LiveSearch.LIVE_FAST;
    final EncoderSettings still = EncoderSettings.LIVE_FAST.withLive(
      new LiveSearch(
        f.smallestBlock(),
        f.skipThreshold(),
        f.splitThreshold(),
        f.steadySplitThreshold(),
        f.fineThreshold(),
        f.goodThreshold(),
        f.childGate(),
        f.modes(),
        f.smallModes(),
        f.keyModes(),
        f.compactClasses(),
        f.quantizers(),
        f.seededMotion(),
        f.searchBlock(),
        f.coarseEndpoints(),
        f.shortcuts(),
        f.splitAbove(),
        false
      )
    );
    encoder.switchTo(still);
    encoder.encode(scene(96, 64, 8, 3), 96, 64, 8);
    assertEquals(55, Objects.requireNonNull(encoder.getStats()).lambda());
    encoder.switchTo(EncoderSettings.LIVE_FAST);
    encoder.encode(scene(96, 64, 9, 3), 96, 64, 9);
    assertThrows(IllegalArgumentException.class, () -> encoder.switchTo(EncoderSettings.SHIP));
    assertThrows(IllegalArgumentException.class, () ->
      new Mcv2Encoder(EncoderSettings.SHIP, POOL, 2, false).switchTo(EncoderSettings.LIVE)
    );
    assertThrows(NullPointerException.class, () -> encoder.switchTo(null));
  }

  /**
   * A live frame over the encoder's byte bound is searched again at twice the lambda until it fits, at most four
   * times: a keyframe comes out as the keyframe of the first lambda of 72, 144, ... 1152 whose frame fits, byte for
   * byte, and the last one's when none fits; a P frame over the bound is searched again from the same history, and a
   * client decodes every frame to the encoder's picture.
   */
  @Test
  void searchesAFrameOverItsByteBoundAgainAtAHigherLambda() throws Mcv2Exception {
    final byte[] picture = noise(96, 64, 5);
    final byte[][] keyframes = new byte[LIMIT_TRIES][];
    for (int i = 0; i < LIMIT_TRIES; i++) {
      keyframes[i] = new Mcv2Encoder(EncoderSettings.LIVE.withLambda(72 << i), POOL, 2, false).encode(picture, 96, 64, 0);
    }
    for (int bound : new int[] { keyframes[0].length, keyframes[0].length - 1, keyframes[2].length, 1 }) {
      int expected = 0;
      while (expected < LIMIT_TRIES - 1 && keyframes[expected].length > bound) {
        expected++;
      }
      final Mcv2Encoder bounded = new Mcv2Encoder(EncoderSettings.LIVE, POOL, 2, true);
      bounded.setFrameLimit(bound);
      assertArrayEquals(keyframes[expected], bounded.encode(picture, 96, 64, 0), "bound " + bound);
      assertEquals(72 << expected, Objects.requireNonNull(bounded.getStats()).lambda());
      // the P frame after it: within the bound when some lambda gets it there, and decoded as the encoder chose it
      final Client client = new Client();
      client.decode(keyframes[expected]);
      final byte[] next = bounded.encode(noise(96, 64, 6), 96, 64, 1);
      assertArrayEquals(client.decode(next), bounded.getReference(), "bound " + bound);
    }
    // no bound: every frame is searched once
    final Mcv2Encoder unbounded = new Mcv2Encoder(EncoderSettings.LIVE, POOL, 2, false);
    unbounded.setFrameLimit(0);
    assertArrayEquals(keyframes[0], unbounded.encode(picture, 96, 64, 0));
    assertThrows(IllegalArgumentException.class, () -> unbounded.setFrameLimit(-1));
  }

  /** The searches a live frame over its bound can have: the first and {@link Mcv2Encoder#LIMIT_RETRIES} more. */
  private static final int LIMIT_TRIES = Mcv2Encoder.LIMIT_RETRIES + 1;

  /** A picture of random pixels, whose frames shrink as the lambda rises. */
  private static byte[] noise(final int width, final int height, final long seed) {
    final byte[] rgb = new byte[width * height * 3];
    new Random(seed).nextBytes(rgb);
    return rgb;
  }

  /**
   * An adaptive profile codes a frame with its second search once the source's average motion is above entering: at
   * thresholds no motion reaches it is the live profile, byte for byte, and at thresholds of zero it is the live profile
   * until the second frame has been measured and then exactly an encoder switched to the live-fast profile there.
   */
  @Test
  void codesAnAdaptiveProfileAsTheSearchOfItsMotion() {
    final EncoderSettings never = EncoderSettings.LIVE.withAdaptive(new EncoderSettings.Adaptive(LiveSearch.LIVE_FAST, 55, 1000, 1000));
    final EncoderSettings always = EncoderSettings.LIVE.withAdaptive(new EncoderSettings.Adaptive(LiveSearch.LIVE_FAST, 55, 0, 0));
    final Mcv2Encoder calm = new Mcv2Encoder(never, POOL, 2, true);
    final Mcv2Encoder live = new Mcv2Encoder(EncoderSettings.LIVE, POOL, 2, true);
    final Mcv2Encoder moving = new Mcv2Encoder(always, POOL, 2, true);
    final Mcv2Encoder switched = new Mcv2Encoder(EncoderSettings.LIVE, POOL, 2, true);
    for (int i = 0; i < 6; i++) {
      final byte[] picture = scene(96, 64, i, 5);
      assertArrayEquals(live.encode(picture, 96, 64, i), calm.encode(picture, 96, 64, i), "frame " + i);
      if (i == 2) {
        switched.switchTo(EncoderSettings.LIVE_FAST);
      }
      assertArrayEquals(switched.encode(picture, 96, 64, i), moving.encode(picture, 96, 64, i), "frame " + i);
    }
  }

  /**
   * Encodes eight frames of a small crop of a synthetic scene.
   *
   * @param encoder a new encoder of a profile
   * @return the SHA-256 of the frames, in hex
   */
  static String digest(final Mcv2Encoder encoder) throws NoSuchAlgorithmException {
    return digest(encoder, 2, 8);
  }

  /**
   * Encodes frames of a small crop of a synthetic scene panning at a speed.
   *
   * @param encoder a new encoder of a profile
   * @param dx      the pan, in pixels a frame
   * @param frames  how many frames
   * @return the SHA-256 of the frames, in hex
   */
  static String digest(final Mcv2Encoder encoder, final int dx, final int frames) throws NoSuchAlgorithmException {
    final MessageDigest digest = MessageDigest.getInstance("SHA-256");
    for (int i = 0; i < frames; i++) {
      digest.update(encoder.encode(scene(96, 64, i, dx), 96, 64, i));
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /** A pan fast enough for the adaptive profile to count as motion. */
  private static final int FAST_PAN = 13;

  /** The SHA-256 of the eight frames {@link #digest} encodes with {@link EncoderSettings#LIVE}. */
  static final String LIVE_DIGEST = "1d1c43930cb66846be5ae90c62f9ba29fe35feeb5a5da1cb02fadeabc4e9e521";

  /** The SHA-256 of the eight frames {@link #digest} encodes with {@link EncoderSettings#LIVE_FAST}. */
  static final String LIVE_FAST_DIGEST = "f5b514f9a30ebe998717b066f6b8518534acfde641c1de2e3802b0a4b4ad903b";

  /** The SHA-256 of sixteen frames of the fast pan {@link EncoderSettings#LIVE_ADAPTIVE} encodes. */
  private static final String ADAPTIVE_MOVING_DIGEST = "d479705d8744472c22ff2cead78009c1fdde57dceaaa5aaaff63e9a1b065538e";
}
