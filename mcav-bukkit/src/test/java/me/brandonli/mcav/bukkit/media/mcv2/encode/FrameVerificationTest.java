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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.FunctionDescriptor;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import me.brandonli.mcav.bukkit.media.mcv2.CompactRecord;
import me.brandonli.mcav.bukkit.media.mcv2.FrameParser;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Fixtures;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Frame;
import me.brandonli.mcav.bukkit.media.mcv2.Workers;
import org.junit.jupiter.api.Test;

final class FrameVerificationTest {

  @Test
  void checksTheWholeConformanceAndEdgeCorpusIncludingUnusedCompactClasses() throws Mcv2Exception {
    for (final String folder : List.of("conformance", "edge")) {
      for (final String stream : Mcv2Fixtures.digests(folder).keySet()) {
        final Map<Long, byte[]> pictures = new HashMap<>();
        for (final byte[] data : Mcv2Fixtures.frames(Mcv2Fixtures.read(folder + "/" + stream))) {
          final Mcv2Frame frame = FrameParser.parse(data);
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
    final int roots = ((width + 31) / 32) * ((height + 31) / 32);
    final List<TreeNode> trees = new ArrayList<>();
    for (int index = 0; index < roots; index++) {
      trees.add(tree(random, 32));
    }
    final byte[] data = FrameWriter.write(
      width,
      height,
      2,
      1,
      false,
      random.nextInt(17) - 8,
      random.nextInt(17) - 8,
      trees,
      random.nextBoolean() ? FrameWriter.Options.WIDE : FrameWriter.Options.production(random.nextBoolean())
    );
    final byte[] reference = new byte[width * height * 3];
    random.nextBytes(reference);
    final Mcv2Frame frame = FrameParser.parse(data);
    compare(frame, reference, Mcv2Decoder.decode(frame, reference, 1), random);
  }

  private static TreeNode tree(final Random random, final int size) {
    if (size > 8 && random.nextBoolean()) {
      return TreeNode.split(tree(random, size / 2), tree(random, size / 2), tree(random, size / 2), tree(random, size / 2));
    }
    int mode = random.nextInt(19);
    if (mode == Mcv2Format.MODE_SPLIT) {
      mode = Mcv2Format.MODE_SKIP;
    }
    int quantizer = Mcv2Format.isResidual(mode) || mode == Mcv2Format.MODE_COMPACT ? random.nextInt(8) : 0;
    final byte[] record;
    if (mode == Mcv2Format.MODE_COMPACT) {
      final int kind = random.nextInt(9);
      final int form = random.nextInt(3);
      record = new byte[1 + form + CompactRecord.bodyBytes(kind)];
      random.nextBytes(record);
      record[0] = (byte) (kind | (form << 4));
      if (kind == CompactRecord.VQ64) {
        record[record.length - 1] &= 63;
      } else if (kind == CompactRecord.PQ64) {
        record[record.length - 1] &= 15;
      } else if (kind == CompactRecord.GAIN_BIAS) {
        quantizer = 0;
      }
    } else if (mode == Mcv2Format.MODE_PATTERN) {
      record = new byte[Mcv2Format.patternSize(size, false, false)];
      random.nextBytes(record);
      record[6] &= 1;
    } else {
      record = new byte[Mcv2Format.recordSize(mode, size)];
      random.nextBytes(record);
    }
    return TreeNode.leaf(mode, quantizer, record);
  }

  private static void compare(final Mcv2Frame frame, final byte[] reference, final byte[] picture, final Random random)
    throws Mcv2Exception {
    final FrameVerification plan = new FrameVerification(frame);
    assertTrue(plan.matches(reference, frame.getReferenceId(), picture, Workers.SEQUENTIAL, JavaKernels.FACTORY));
    final byte[] wrong = picture.clone();
    wrong[random.nextInt(wrong.length)] ^= 1;
    assertFalse(plan.matches(reference, frame.getReferenceId(), wrong, Workers.SEQUENTIAL, JavaKernels.FACTORY));
    final JavaKernels java = new JavaKernels();
    for (final NativeKernels.Level level : NativeTesting.levels()) {
      final Kernels other = NativeTesting.kernels(level);
      for (int group = 0; group < plan.groups(); group++) {
        assertTrue(other.verify(plan, reference, picture, group), level + " group " + group);
        assertEquals(java.verify(plan, reference, wrong, group), other.verify(plan, reference, wrong, group), level + " altered picture");
      }
    }
  }

  @Test
  void partitionsLargeFramesAndDetectsEveryBoundaryPixel() throws Mcv2Exception {
    final List<TreeNode> roots = new ArrayList<>();
    for (int index = 0; index < 70; index++) {
      roots.add(TreeNode.leaf(Mcv2Format.MODE_SOLID, 0, new byte[] { 17, 31, 63 }));
    }
    final Mcv2Frame frame = FrameParser.parse(FrameWriter.write(31, 32 * 70 - 3, 4, 4, true, 0, 0, roots, FrameWriter.Options.WIDE));
    final FrameVerification plan = new FrameVerification(frame);
    assertEquals(2, plan.groups());
    final byte[] picture = Mcv2Decoder.decode(frame, null, 4);
    final byte[] reference = new byte[0];
    for (final NativeKernels.Level level : NativeTesting.levels()) {
      final Kernels other = NativeTesting.kernels(level);
      for (final int row : new int[] { 0, 2047, 2048, frame.getHeight() - 1 }) {
        for (final int column : new int[] { 0, 30 }) {
          for (int channel = 0; channel < 3; channel++) {
            final byte[] wrong = picture.clone();
            wrong[(row * 31 + column) * 3 + channel] ^= 1;
            assertFalse(other.verify(plan, reference, wrong, 0) && other.verify(plan, reference, wrong, 1));
          }
        }
      }
    }
  }

  private static MethodHandle failing(final String name, final FunctionDescriptor descriptor) {
    final MethodType type = descriptor.toMethodType();
    final MethodHandle body = MethodHandles.insertArguments(
      MethodHandles.throwException(type.returnType(), IllegalStateException.class),
      0,
      new IllegalStateException(name)
    );
    return MethodHandles.dropArguments(body, 0, type.parameterList());
  }

  @Test
  void rejectsPictureAndReferenceMismatchBeforeNativeAccessAndPropagatesNativeFailure() throws Mcv2Exception {
    final Mcv2Frame frame = FrameParser.parse(
      FrameWriter.write(8, 8, 1, 0, false, 0, 0, List.of(TreeNode.skip()), FrameWriter.Options.WIDE)
    );
    final FrameVerification plan = new FrameVerification(frame);
    final byte[] picture = new byte[8 * 8 * 3];
    final NativeKernels kernels = new NativeKernels(new NativeKernels.Binding(NativeKernels.Level.SCALAR, FrameVerificationTest::failing));
    assertThrows(IllegalArgumentException.class, () -> kernels.verify(plan, picture, new byte[1], 0));
    assertThrows(IllegalArgumentException.class, () -> kernels.verify(plan, new byte[1], picture, 0));
    assertThrows(IndexOutOfBoundsException.class, () -> kernels.verify(plan, picture, picture, -1));
    assertThrows(IndexOutOfBoundsException.class, () -> kernels.verify(plan, picture, picture, plan.groups()));
    assertThrows(Mcv2Exception.class, () -> plan.matches(new byte[1], 0, picture, Workers.SEQUENTIAL, JavaKernels.FACTORY));
    assertThrows(Mcv2Exception.class, () -> plan.matches(picture, 5, picture, Workers.SEQUENTIAL, JavaKernels.FACTORY));
    assertFalse(plan.matches(picture, 0, new byte[1], Workers.SEQUENTIAL, JavaKernels.FACTORY));
    final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> kernels.verify(plan, picture, picture, 0));
    assertEquals("MCV2 native kernel failed", failure.getMessage());
    assertEquals("verify", failure.getCause().getMessage());
  }

  @Test
  void aBrokenPendingReferenceStopsTheEncoderWithTheDecoderFailure() throws ReflectiveOperationException {
    final Mcv2Encoder encoder = new Mcv2Encoder(EncoderSettings.LIVE_FAST, ForkJoinPool.commonPool(), 1, true);
    final byte[] picture = LiveEncoderTest.scene(32, 32, 0, 2);
    encoder.encode(picture, 32, 32, 0);
    final Mcv2Encoder.Pending pending = encoder.begin(picture, 32, 32, 1);
    assertFalse(pending.isKeyframe());
    // Fault injection models an internal reference bookkeeping defect without exposing mutable pending state.
    final Field referenceId = Mcv2Encoder.Pending.class.getDeclaredField("predictFromId");
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
}
