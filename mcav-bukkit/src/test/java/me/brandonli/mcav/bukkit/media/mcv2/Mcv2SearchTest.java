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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Trees.Node;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.internal.PremainAttachAccess;

final class Mcv2SearchTest {

  private static final ThreadLocal<LongSupplier> SEARCH_TIME = new ThreadLocal<>();

  private static final String BEGIN_METHOD = "begin";
  private static final String SEARCH_METHOD = "search";
  private static final String SYSTEM_CLASS = "java/lang/System";

  private static final String FRAME_STATE_CLASS = "FrameState";
  private static final String BLOCK_CODER_CLASS = "BlockCoder";
  private static final String KERNELS_CLASS = "Kernels";

  private static final String FRAME_FIELD = "frame";
  private static final String NODE_FIELD = "node";
  private static final String IDLE_CODERS_FIELD = "idleCoders";

  static long searchTime() {
    final LongSupplier fixed = SEARCH_TIME.get();
    return fixed == null ? System.nanoTime() : fixed.getAsLong();
  }

  @Test
  void shouldUseMeanSolidsAtTheBudgetDeadlineAndSearchBeforeIt() throws UnmodifiableClassException {
    final Class<?> encoderType = Mcv2.class;
    final Instrumentation instrumentation = PremainAttachAccess.getInstrumentation();
    final Set<String> clockedMethods = ConcurrentHashMap.newKeySet();
    final ClassFileTransformer clock = new SearchClock(encoderType, ClockScope.SEARCH, clockedMethods);
    instrumentation.addTransformer(clock, true);
    try {
      instrumentation.retransformClasses(encoderType);
      final Set<String> expectedClockedMethods = Set.of(SEARCH_METHOD);
      assertEquals(expectedClockedMethods, clockedMethods);

      final byte[] source = this.halvedSource();
      for (final long instant : new long[] { 99, 100, 101 }) {
        this.assertSearchAt(encoderType, source, instant);
      }
    } finally {
      SEARCH_TIME.remove();
      instrumentation.removeTransformer(clock);
      instrumentation.retransformClasses(encoderType);
    }
  }

  private byte[] halvedSource() {
    final byte[] source = new byte[32 * 32 * 3];
    for (int row = 0; row < 32; row++) {
      for (int column = 0; column < 32; column++) {
        source[(row * 32 + column) * 3] = (byte) (column < 16 ? 10 : 200);
      }
    }

    return source;
  }

  private void assertSearchAt(final Class<?> encoderType, final byte[] source, final long instant) {
    SEARCH_TIME.set(() -> instant);
    final Mcv2BlockState state = new Mcv2BlockState(
      source,
      new byte[0],
      32,
      32,
      Mcv2FrameKind.KEYFRAME,
      Mcv2BlockState.Thresholds.STANDARD,
      72,
      null
    );
    final Class<?> frameType = Mcv2Internals.nested(FRAME_STATE_CLASS);
    final Object frame = Mcv2Internals.field(Mcv2BlockState.class, state, FRAME_FIELD);
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT, pool, 1, Mcv2.Verification.UNVERIFIED);
      encoder.setFrameBudget(100);
      final Class<?> effortType = Mcv2Internals.nested("SearchEffort");
      final Object budgeted = Mcv2Internals.field(effortType, null, "BUDGETED");
      final Object result = Mcv2Internals.callForResult(
        encoderType,
        encoder,
        SEARCH_METHOD,
        new Class<?>[] { frameType, boolean[].class, long.class, effortType },
        frame,
        new boolean[0],
        0L,
        budgeted
      );
      final List<?> roots = (List<?>) Mcv2Internals.field(result.getClass(), result, "roots");
      final int rootCount = roots.size();
      assertEquals(1, rootCount);

      final Node root = new Node(roots.getFirst());
      final int expectedMode = instant >= 100 ? Mcv2Decoder.MODE_SOLID : Mcv2Decoder.MODE_PATTERN;
      final int mode = root.getMode();
      assertEquals(expectedMode, mode);

      final byte[] expected = source.clone();
      if (instant >= 100) {
        this.fillMeanSolid(expected);

        final byte[] expectedRecord = { 105, 0, 0 };
        final byte[] record = root.getRecord();
        assertArrayEquals(expectedRecord, record);
      }

      final byte[] picture = (byte[]) Mcv2Internals.field(frameType, frame, "picture");
      assertArrayEquals(expected, picture);
    }
  }

  private void fillMeanSolid(final byte[] expected) {
    for (int pixel = 0; pixel < 32 * 32; pixel++) {
      expected[pixel * 3] = 105;
    }
  }

  @Test
  void shouldFallBackDirectlyAtZeroLambdaWhenTheFrameExceedsItsLimit() throws UnmodifiableClassException {
    final Class<?> encoderType = Mcv2.class;
    final Instrumentation instrumentation = PremainAttachAccess.getInstrumentation();
    final Set<String> clockedMethods = ConcurrentHashMap.newKeySet();
    final ClassFileTransformer clock = new SearchClock(encoderType, ClockScope.SEARCH_AND_BEGIN, clockedMethods);
    instrumentation.addTransformer(clock, true);
    try {
      instrumentation.retransformClasses(encoderType);
      final Set<String> expectedClockedMethods = Set.of(BEGIN_METHOD, SEARCH_METHOD);
      assertEquals(expectedClockedMethods, clockedMethods);

      final long[] instants = { 0, 0, 0, 0, 100 };
      final AtomicInteger readings = new AtomicInteger();
      SEARCH_TIME.set(() -> instants[Math.min(readings.getAndIncrement(), instants.length - 1)]);
      final byte[] source = new byte[64 * 32 * 3];
      final byte[] expected = new byte[source.length];
      this.fillQuarters(source, expected);

      try (final ForkJoinPool pool = new ForkJoinPool(1)) {
        final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT.withLambda(0), pool, 1, Mcv2.Verification.VERIFIED);
        encoder.setFrameBudget(100);
        encoder.setFrameLimit(60);
        final byte[] data = encoder.encode(source, 64, 32, 0);
        final int length = data.length;
        assertEquals(52, length);

        final byte[] decoded = Mcv2Decoder.decode(data, null, 0);
        assertArrayEquals(expected, decoded);

        final byte[] reference = encoder.getReference().orElseThrow();
        assertArrayEquals(expected, reference);
      }
    } finally {
      SEARCH_TIME.remove();
      instrumentation.removeTransformer(clock);
      instrumentation.retransformClasses(encoderType);
    }
  }

  private void fillQuarters(final byte[] source, final byte[] expected) {
    for (int row = 0; row < 32; row++) {
      for (int column = 0; column < 64; column++) {
        final int pixel = (row * 64 + column) * 3;
        source[pixel] = (byte) (column < 32 ? (column < 16 ? 10 : 200) : column < 48 ? 20 : 180);
        expected[pixel] = (byte) (column < 32 ? 105 : 100);
      }
    }
  }

  private int vector(final int motionX, final int motionY) {
    return (motionX << 16) | (motionY & 65535);
  }

  private static void fillMotion(
    final int[] field,
    final int width,
    final int height,
    final Node node,
    final int left,
    final int top,
    final int size
  ) {
    Mcv2Internals.call(
      Mcv2.class,
      null,
      "fillMotion",
      new Class<?>[] { int[].class, int.class, int.class, Mcv2Internals.nested("TreeNode"), int.class, int.class, int.class },
      field,
      width,
      height,
      node.value(),
      left,
      top,
      size
    );
  }

  @Test
  void shouldFindEachLeafsVector() {
    final Node[] nodes = {
      Node.skip(),
      Mcv2Trees.solid(1, 2, 3),
      Mcv2Trees.motion(3, -4),
      Node.leaf(Mcv2Decoder.MODE_COMPACT, 0, new byte[] { 0, 0, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11 }),
      Node.leaf(Mcv2Decoder.MODE_COMPACT, 0, new byte[] { 2, -2, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11 }),
      Node.leaf(Mcv2Decoder.MODE_COMPACT, 0, new byte[] { 20, -30, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11 }),
    };
    final int[] expected = { 0, 0, this.vector(3, -4), 0, this.vector(2, -2), this.vector(20, -30) };
    for (int index = 0; index < nodes.length; index++) {
      final int[] field = new int[1];
      fillMotion(field, 8, 8, nodes[index], 0, 0, 8);
      final int motion = field[0];
      assertEquals(expected[index], motion);
    }
  }

  @Test
  void shouldKeepOneVectorPerEightPixelCellOfTheLeaves() {
    final Node split = Node.split(Mcv2Trees.motion(4, 0), Node.skip(), Node.skip(), Node.skip());
    final int[] field = new int[5 * 3];
    fillMotion(field, 40, 20, split, 0, 0, 32);
    fillMotion(field, 40, 20, Mcv2Trees.solid(0, 0, 0), 32, 0, 32);
    final int[] expected = { this.vector(4, 0), this.vector(4, 0), 0, 0, 0, this.vector(4, 0), this.vector(4, 0), 0, 0, 0, 0, 0, 0, 0, 0 };
    assertArrayEquals(expected, field);
  }

  @Test
  void shouldSeedTheLocalSearchFromThePreviousFrame() {
    final byte[] reference = Mcv2Pictures.scene(64, 64, 0, 0);
    final byte[] source = Mcv2Pictures.scene(64, 64, 2, 3);
    final int[] field = new int[8 * 8];
    Arrays.fill(field, this.vector(6, 0));
    final Mcv2BlockState job = new Mcv2BlockState(
      source,
      reference,
      64,
      64,
      Mcv2FrameKind.PREDICTED,
      Mcv2BlockState.Thresholds.STANDARD,
      72,
      field
    );
    final int expectedPreviousMotion = this.vector(6, 0);
    final int previousMotion = job.previousMotion(-5, 100);
    assertEquals(expectedPreviousMotion, previousMotion);

    final Mcv2BlockState fresh = new Mcv2BlockState(
      source,
      reference,
      64,
      64,
      Mcv2FrameKind.PREDICTED,
      Mcv2BlockState.Thresholds.STANDARD,
      72,
      null
    );
    final int freshPreviousMotion = fresh.previousMotion(10, 10);
    assertEquals(0, freshPreviousMotion);

    job.code(16, 1, 0, 0, 0, this.vector(6, 0));
    final boolean finiteCost = job.cost(1, 0) < Double.POSITIVE_INFINITY;
    assertTrue(finiteCost);

    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        field[row * 8 + column] = this.vector(2 * column, -2 * row);
      }
    }

    for (final int parent : new int[] { Mcv2BlockState.NO_VECTOR, this.vector(-6, -4) }) {
      final Mcv2BlockState seeded = new Mcv2BlockState(
        source,
        reference,
        64,
        64,
        Mcv2FrameKind.PREDICTED,
        Mcv2BlockState.Thresholds.STANDARD,
        0,
        field
      );
      final Object coder = seeded.code(16, 1, 5, 16, 16, parent);
      final int[] expected = {
        this.vector(3, -3),
        this.vector(1, -3),
        this.vector(4, -3),
        this.vector(3, -1),
        this.vector(3, -4),
        parent == Mcv2BlockState.NO_VECTOR ? this.vector(3, -3) : this.vector(-3, -2),
      };
      final int[] seeds = Mcv2BlockState.seeds(coder, "halfSeeds");
      assertArrayEquals(expected, seeds);
    }
  }

  @Test
  void shouldSkipEarlyExactlyAtTheThresholdAndMonotonicallyInLambda() {
    final byte[] reference = Mcv2Pictures.scene(32, 32, 0, 0);
    for (final Mcv2BlockState.Thresholds thresholds : Mcv2BlockState.Thresholds.values()) {
      for (int amplitude = 0; amplitude <= 12; amplitude += 2) {
        this.assertSkipsMonotonically(reference, thresholds, amplitude);
      }
    }
  }

  private void assertSkipsMonotonically(final byte[] reference, final Mcv2BlockState.Thresholds thresholds, final int amplitude) {
    final byte[] source = this.noisySource(reference, amplitude);
    final long distortion = Mcv2SearchPropertyTest.distortion(source, reference);
    boolean skippedBefore = false;
    for (final double lambda : new double[] { 10, 30, 65.255994022, 150, 400, 1200 }) {
      final Mcv2BlockState job = new Mcv2BlockState(source, reference, 32, 32, Mcv2FrameKind.PREDICTED, thresholds, lambda, null);
      final Object coder = job.code(32, 0, 0, 0, 0, Mcv2BlockState.NO_VECTOR);
      final boolean skipped = Mcv2BlockState.isSkipped(coder);
      final boolean expectedSkipped = distortion / 96.0 + lambda <= (thresholds == Mcv2BlockState.Thresholds.FAST ? 60 : 28) * lambda;
      assertEquals(expectedSkipped, skipped);
      final boolean monotonic = !skippedBefore || skipped;
      assertTrue(monotonic, "monotonic in lambda");
      skippedBefore = skipped;
      if (skipped) {
        final int mode = job.mode(0, 0);
        assertEquals(Mcv2Decoder.MODE_SKIP, mode);
      }
    }
  }

  private byte[] noisySource(final byte[] reference, final int amplitude) {
    final Random random = new Random(amplitude);
    final byte[] source = reference.clone();
    for (int index = 0; index < source.length; index++) {
      source[index] = (byte) Math.clamp((source[index] & 255) + random.nextInt(2 * amplitude + 1) - amplitude, 0, 255);
    }

    return source;
  }

  @Test
  void shouldKeepFastSkipAtExactlyItsSixtyBitBound() {
    final byte[] source = new byte[32 * 32 * 3];
    for (int pixel = 0; pixel < 32 * 32; pixel++) {
      source[pixel * 3] = 3;
    }

    final double lambda = 864.0 / 59;
    final Mcv2BlockState state = new Mcv2BlockState(
      source,
      new byte[source.length],
      32,
      32,
      Mcv2FrameKind.PREDICTED,
      Mcv2BlockState.Thresholds.FAST,
      lambda,
      null
    );
    final Object coder = state.code(32, 0, 0, 0, 0, Mcv2BlockState.NO_VECTOR);
    final double expectedCost = 60 * lambda;
    final double cost = state.cost(0, 0);
    assertEquals(expectedCost, cost);

    final boolean skipped = Mcv2BlockState.isSkipped(coder);
    assertTrue(skipped);

    final int mode = state.mode(0, 0);
    assertEquals(Mcv2Decoder.MODE_SKIP, mode);

    final byte[] expectedRecord = new byte[0];
    final byte[] record = state.record(0, 0);
    assertArrayEquals(expectedRecord, record);
  }

  @Test
  void shouldPredictANewChildVectorFromTheReference() throws ReflectiveOperationException {
    final byte[] reference = new byte[32 * 32 * 3];
    for (int row = 0; row < 32; row++) {
      for (int column = 0; column < 32; column++) {
        final int pixel = (row * 32 + column) * 3;
        reference[pixel] = (byte) column;
        reference[pixel + 1] = (byte) row;
        reference[pixel + 2] = (byte) (row + column);
      }
    }

    final Mcv2BlockState state = new Mcv2BlockState(
      reference,
      reference,
      32,
      32,
      Mcv2FrameKind.PREDICTED,
      Mcv2BlockState.Thresholds.STANDARD,
      72,
      null
    );
    final Object root = state.code(32, 0, 0, 0, 0, Mcv2BlockState.NO_VECTOR);
    final Object child = state.code(16, 1, 0, 16, 16, Mcv2BlockState.NO_VECTOR);
    final Class<?> coderType = Mcv2Internals.nested(BLOCK_CODER_CLASS);
    final Field parent = coderType.getDeclaredField("root");
    parent.setAccessible(true);
    parent.set(child, root);
    final int[] expected = new int[16 * 16 * 3];
    for (int row = 0; row < 16; row++) {
      for (int column = 0; column < 16; column++) {
        final int pixel = (row * 16 + column) * 3;
        expected[pixel] = Math.min(column + 17, 31);
        expected[pixel + 1] = row + 16;
        expected[pixel + 2] = expected[pixel] + expected[pixel + 1];
      }
    }

    final int[] actual = new int[expected.length];
    Mcv2Internals.call(coderType, child, "predict", new Class<?>[] { int.class, int[].class }, this.vector(1, 0), actual);
    assertArrayEquals(expected, actual);
  }

  private static Object descend(final Mcv2BlockState state, final int level, final int left, final int top, final double threshold) {
    final Class<?> frameType = Mcv2Internals.nested(FRAME_STATE_CLASS);
    final Class<?> coderType = Mcv2Internals.nested(BLOCK_CODER_CLASS);
    final Class<?> kernelType = Mcv2Internals.nested(KERNELS_CLASS);
    final Object frame = Mcv2Internals.field(Mcv2BlockState.class, state, FRAME_FIELD);
    final Object coders = Array.newInstance(coderType, 3);
    for (int depth = 0; depth < 3; depth++) {
      Array.set(
        coders,
        depth,
        Mcv2Internals.construct(
          coderType,
          new Class<?>[] { frameType, int.class, kernelType },
          frame,
          32 >> depth,
          Mcv2Internals.construct(Mcv2Internals.nested("JavaKernels"), new Class<?>[0])
        )
      );
    }

    final Class<?> descentType = Mcv2Internals.nested("Descent");
    final Object context = Mcv2Internals.construct(
      descentType,
      new Class<?>[] { frameType, coders.getClass(), double.class },
      frame,
      coders,
      0.0
    );
    return Mcv2Internals.callForResult(
      Mcv2.class,
      null,
      "descend",
      new Class<?>[] { descentType, int.class, int.class, int.class, int.class, double.class },
      context,
      level,
      left,
      top,
      Mcv2BlockState.NO_VECTOR,
      threshold
    );
  }

  @Test
  void shouldRepresentCompletelyOutsideChildrenAsBlackWithNoVisibleLeaves() {
    final byte[] source = new byte[16 * 16 * 3];
    for (int pixel = 0; pixel < 16 * 16; pixel++) {
      source[pixel * 3] = 10;
      source[pixel * 3 + 1] = 20;
      source[pixel * 3 + 2] = 30;
    }

    final Mcv2BlockState state = new Mcv2BlockState(
      source,
      new byte[0],
      16,
      16,
      Mcv2FrameKind.KEYFRAME,
      Mcv2BlockState.Thresholds.STANDARD,
      2,
      null
    );
    for (final int[] position : new int[][] { { 16, 0 }, { 0, 16 }, { 16, 16 } }) {
      final Object choice = descend(state, 1, position[0], position[1], 0);
      final Node node = new Node(Mcv2Internals.field(choice.getClass(), choice, NODE_FIELD));
      final Node expectedNode = Mcv2Trees.solid(0, 0, 0);
      assertEquals(expectedNode, node);
      final Object leaves = Mcv2Internals.field(choice.getClass(), choice, "leaves");
      assertEquals(0, leaves);
      final Object cost = Mcv2Internals.field(choice.getClass(), choice, "cost");
      assertEquals(112.0, cost);
    }
  }

  @Test
  void shouldSplitOnlyAboveTheThresholdWhenChildrenAreCheaper() {
    final byte[] source = new byte[32 * 32 * 3];
    for (int row = 0; row < 32; row++) {
      for (int column = 0; column < 32; column++) {
        source[(row * 32 + column) * 3 + row / 16] = (byte) (column < 16 ? 40 : 80);
      }
    }

    final Mcv2BlockState state = new Mcv2BlockState(
      source,
      new byte[0],
      32,
      32,
      Mcv2FrameKind.KEYFRAME,
      Mcv2BlockState.Thresholds.STANDARD,
      1,
      null
    );
    state.code(32, 0, 0, 0, 0, Mcv2BlockState.NO_VECTOR);
    final double cost = state.cost(0, 0);
    final Object equal = descend(state, 0, 0, 0, cost);
    final boolean equalSplits = new Node(Mcv2Internals.field(equal.getClass(), equal, NODE_FIELD)).isSplit();
    assertFalse(equalSplits);

    final Object cheaper = descend(state, 0, 0, 0, Math.nextDown(cost));
    final Node split = new Node(Mcv2Internals.field(cheaper.getClass(), cheaper, NODE_FIELD));
    final boolean cheaperSplits = split.isSplit();
    assertTrue(cheaperSplits);

    final Node expectedTopLeft = Mcv2Trees.solid(40, 0, 0);
    final Node topLeft = split.getChild(0);
    assertEquals(expectedTopLeft, topLeft);

    final Node expectedTopRight = Mcv2Trees.solid(80, 0, 0);
    final Node topRight = split.getChild(1);
    assertEquals(expectedTopRight, topRight);

    final Node expectedBottomLeft = Mcv2Trees.solid(0, 40, 0);
    final Node bottomLeft = split.getChild(2);
    assertEquals(expectedBottomLeft, bottomLeft);

    final Node expectedBottomRight = Mcv2Trees.solid(0, 80, 0);
    final Node bottomRight = split.getChild(3);
    assertEquals(expectedBottomRight, bottomRight);
  }

  @Test
  void shouldReuseCodersWithoutRetainingBusyEntries() {
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT, pool, 1, Mcv2.Verification.VERIFIED);
      final Collection<?> idle = (Collection<?>) Mcv2Internals.field(Mcv2.class, encoder, IDLE_CODERS_FIELD);
      final Collection<?> busy = (Collection<?>) Mcv2Internals.field(Mcv2.class, encoder, "busyCoders");
      encoder.encode(new byte[3], 1, 1, 0);
      final Object coders = idle.iterator().next();
      for (int frame = 1; frame < 4; frame++) {
        encoder.encode(new byte[3], 1, 1, frame);
        final boolean noneBusy = busy.isEmpty();
        assertTrue(noneBusy);
        final int idleCount = idle.size();
        assertEquals(1, idleCount);
        final Object idleCoders = idle.iterator().next();
        assertEquals(coders, idleCoders);
      }
    }
  }

  @Test
  void shouldStoreOneMotionVectorPerCellAndOnlyTheReducedPictures() {
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      for (final int[] dimensions : new int[][] { { 65, 37, 33, 19, 17, 10 }, { 71, 39, 36, 20, 18, 10 } }) {
        this.assertStoresReducedPictures(pool, dimensions);
      }
    }
  }

  private void assertStoresReducedPictures(final ForkJoinPool pool, final int[] dimensions) {
    final int width = dimensions[0];
    final int height = dimensions[1];
    final int halfWidth = dimensions[2];
    final int halfHeight = dimensions[3];
    final int quarterWidth = dimensions[4];
    final int quarterHeight = dimensions[5];
    final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT, pool, 1, Mcv2.Verification.VERIFIED);
    final byte[] source = Mcv2Pictures.scene(width, height, 0, 0);
    encoder.encode(source, width, height, 0);
    final byte[] reference = encoder.getReference().orElseThrow();
    encoder.encode(source, width, height, 1);
    final int expectedMotionLength = 9 * 5;
    final int motionLength = ((int[]) Mcv2Internals.field(Mcv2.class, encoder, "motion")).length;
    assertEquals(expectedMotionLength, motionLength);

    final Class<?> buffersType = Mcv2Internals.nested("Buffers");
    final Object buffers = Mcv2Internals.field(Mcv2.class, encoder, "buffers");
    final byte[] half = (byte[]) Mcv2Internals.field(buffersType, buffers, "half");
    final byte[] quarter = (byte[]) Mcv2Internals.field(buffersType, buffers, "quarter");
    final int expectedHalfLength = halfWidth * halfHeight * 3;
    final int halfLength = half.length;
    assertEquals(expectedHalfLength, halfLength);
    final int expectedQuarterLength = quarterWidth * quarterHeight * 3;
    final int quarterLength = quarter.length;
    assertEquals(expectedQuarterLength, quarterLength);
    final byte[] expectedHalf = Mcv2BlockState.half(reference, width, height, new byte[half.length]);
    assertArrayEquals(expectedHalf, half);
    final byte[] expectedQuarter = Mcv2BlockState.half(half, halfWidth, halfHeight, new byte[quarter.length]);
    assertArrayEquals(expectedQuarter, quarter);

    final Collection<?> idle = (Collection<?>) Mcv2Internals.field(Mcv2.class, encoder, IDLE_CODERS_FIELD);
    final Object coders = idle.iterator().next();
    final Class<?> coderType = Mcv2Internals.nested(BLOCK_CODER_CLASS);
    for (int level = 0; level < 3; level++) {
      final Object coder = Array.get(coders, level);
      final int size = 32 >> level;
      final int expectedHalfSourceLength = (size / 2) * (size / 2) * 3;
      final int halfSourceLength = ((int[]) Mcv2Internals.field(coderType, coder, "halfSource")).length;
      assertEquals(expectedHalfSourceLength, halfSourceLength);
      final int expectedQuarterSourceLength = (size / 4) * (size / 4) * 3;
      final int quarterSourceLength = ((int[]) Mcv2Internals.field(coderType, coder, "quarterSource")).length;
      assertEquals(expectedQuarterSourceLength, quarterSourceLength);
    }
  }

  @Test
  void shouldKeepASkippedRootBelowTheNormalSplitThresholdEvenWhenOneChildFitsMoreClosely() {
    final byte[] reference = new byte[32 * 32 * 3];
    for (int row = 0; row < 32; row++) {
      for (int column = 0; column < 32; column++) {
        reference[(row * 32 + column) * 3 + row / 16] = (byte) (column < 16 ? 40 : 80);
      }
    }

    final byte[] source = reference.clone();
    for (int row = 0; row < 16; row++) {
      for (int column = 0; column < 16; column++) {
        source[(row * 32 + column) * 3] = 43;
      }
    }

    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT.withLambda(2), pool, 1, Mcv2.Verification.VERIFIED);
      final int firstLeafCount = Mcv2Decoder.parse(encoder.encode(reference, 32, 32, 0)).getLeafCount();
      assertEquals(4, firstLeafCount);

      final byte[] firstReference = encoder.getReference().orElseThrow();
      assertArrayEquals(reference, firstReference);

      final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(encoder.encode(source, 32, 32, 1));
      final int leafCount = frame.getLeafCount();
      assertEquals(1, leafCount);

      final int leafSize = frame.getLeaf(0).size();
      assertEquals(32, leafSize);

      final int leafMode = frame.getLeaf(0).mode();
      assertEquals(Mcv2Decoder.MODE_SKIP, leafMode);

      final byte[] decoded = Mcv2Decoder.decode(frame, reference, 0);
      assertArrayEquals(reference, decoded);

      final byte[] nextReference = encoder.getReference().orElseThrow();
      assertArrayEquals(reference, nextReference);
    }
  }

  @Test
  void shouldScaleTheMotionRangeToEachPyramidLevel() {
    for (final Mcv2BlockState.Thresholds thresholds : Mcv2BlockState.Thresholds.values()) {
      final Mcv2BlockState state = new Mcv2BlockState(
        new byte[32 * 32 * 3],
        new byte[32 * 32 * 3],
        32,
        32,
        Mcv2FrameKind.PREDICTED,
        thresholds,
        72,
        null
      );
      final Object frame = Mcv2Internals.field(Mcv2BlockState.class, state, FRAME_FIELD);
      final Class<?> kernelType = Mcv2Internals.nested(KERNELS_CLASS);
      final List<Integer> ranges = new ArrayList<>();
      final Object kernels = Proxy.newProxyInstance(kernelType.getClassLoader(), new Class<?>[] { kernelType }, (_, method, arguments) ->
        this.recordSeededRange(ranges, method, arguments)
      );
      final Class<?> coderType = Mcv2Internals.nested(BLOCK_CODER_CLASS);
      final Object coder = Mcv2Internals.construct(
        coderType,
        new Class<?>[] { Mcv2Internals.nested(FRAME_STATE_CLASS), int.class, kernelType },
        frame,
        32,
        kernels
      );
      final Object coarseMotion = Mcv2Internals.callForResult(coderType, coder, "coarseMotion", new Class<?>[] { int.class }, 2);
      assertEquals(0, coarseMotion);
      final List<Integer> expectedRanges = thresholds == Mcv2BlockState.Thresholds.FAST ? List.of(12) : List.of(6, 12);
      assertEquals(expectedRanges, ranges);
    }
  }

  private @Nullable Object recordSeededRange(final List<Integer> ranges, final Method method, final Object[] arguments) {
    if (method.getName().equals("seeded")) {
      ranges.add((Integer) arguments[7]);
      return 0;
    }

    final String methodName = method.getName();
    assertEquals("halve", methodName);

    return null;
  }

  @Test
  void shouldRetainSixteenPixelLeavesBelowTheNormalFineSplitThreshold() {
    final byte[] source = new byte[32 * 32 * 3];
    for (int row = 0; row < 32; row++) {
      for (int column = 0; column < 32; column++) {
        final int pixel = (row * 32 + column) * 3;
        source[pixel] = (byte) (5 * ((column / 8) % 2));
        source[pixel + 1] = (byte) (5 * ((row / 8) % 2));
        source[pixel + 2] = (byte) (40 * ((row / 16) * 2 + column / 16));
      }
    }

    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      final Mcv2 encoder = new Mcv2(Mcv2.Settings.DEFAULT.withLambda(2), pool, 1, Mcv2.Verification.VERIFIED);
      final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(encoder.encode(source, 32, 32, 0));
      final int leafCount = frame.getLeafCount();
      assertEquals(4, leafCount);

      for (int index = 0; index < frame.getLeafCount(); index++) {
        final Mcv2Decoder.Leaf leaf = frame.getLeaf(index);
        final int leafSize = leaf.size();
        assertEquals(16, leafSize);
        final int leafMode = leaf.mode();
        assertEquals(Mcv2Decoder.MODE_SOLID, leafMode);
      }

      final byte[] expected = this.retainedPicture(source);

      final byte[] decoded = Mcv2Decoder.decode(frame, null, 0);
      assertArrayEquals(expected, decoded);
    }
  }

  private byte[] retainedPicture(final byte[] source) {
    final byte[] expected = new byte[source.length];
    for (int row = 0; row < 32; row++) {
      for (int column = 0; column < 32; column++) {
        final int pixel = (row * 32 + column) * 3;
        expected[pixel] = 3;
        expected[pixel + 1] = 3;
        expected[pixel + 2] = source[pixel + 2];
      }
    }

    return expected;
  }

  @Test
  void shouldHalveAPictureWithItsOddLastRowAndColumnStandingAlone() {
    final int[] red = { 0, 4, 9, 8, 12, 20, 100, 200, 255 };
    final byte[] picture = new byte[3 * 3 * 3];
    for (int index = 0; index < red.length; index++) {
      picture[index * 3] = (byte) red[index];
      picture[index * 3 + 1] = (byte) index;
    }

    final byte[] expected = { 6, 2, 0, 15, 4, 0, (byte) 150, 7, 0, (byte) 255, 8, 0 };
    final byte[] half = Mcv2BlockState.half(picture, 3, 3, new byte[2 * 2 * 3]);
    assertArrayEquals(expected, half);
  }

  private static final class SearchClock implements ClassFileTransformer {

    private final Class<?> encoderType;
    private final ClockScope scope;
    private final Set<String> clockedMethods;

    SearchClock(final Class<?> encoderType, final ClockScope scope, final Set<String> clockedMethods) {
      this.encoderType = encoderType;
      this.scope = scope;
      this.clockedMethods = clockedMethods;
    }

    @Override
    public byte @Nullable [] transform(
      final ClassLoader loader,
      final String className,
      final Class<?> redefined,
      final ProtectionDomain domain,
      final byte[] original
    ) {
      if (redefined != this.encoderType) {
        return null;
      }

      // The incoming definition retains the mutation installed by PIT.
      final ClassReader reader = new ClassReader(original);
      final ClassWriter writer = new ClassWriter(reader, 0);
      final ClassVisitor visitor = new ClockingClassVisitor(writer, this.scope, this.clockedMethods);
      reader.accept(visitor, 0);
      return writer.toByteArray();
    }
  }

  private static final class ClockingClassVisitor extends ClassVisitor {

    private final ClockScope scope;
    private final Set<String> clockedMethods;

    ClockingClassVisitor(final ClassWriter writer, final ClockScope scope, final Set<String> clockedMethods) {
      super(Opcodes.ASM9, writer);
      this.scope = scope;
      this.clockedMethods = clockedMethods;
    }

    @Override
    public MethodVisitor visitMethod(
      final int access,
      final String methodName,
      final String descriptor,
      final String signature,
      final String[] exceptions
    ) {
      final MethodVisitor visitor = super.visitMethod(access, methodName, descriptor, signature, exceptions);
      if (!methodName.startsWith("lambda$search$") && !(this.scope == ClockScope.SEARCH_AND_BEGIN && methodName.equals(BEGIN_METHOD))) {
        return visitor;
      }

      final String clockedMethod = methodName.equals(BEGIN_METHOD) ? BEGIN_METHOD : SEARCH_METHOD;
      return new ClockRedirector(visitor, clockedMethod, this.clockedMethods);
    }
  }

  private static final class ClockRedirector extends MethodVisitor {

    private final String clockedMethod;
    private final Set<String> clockedMethods;

    ClockRedirector(final MethodVisitor visitor, final String clockedMethod, final Set<String> clockedMethods) {
      super(Opcodes.ASM9, visitor);
      this.clockedMethod = clockedMethod;
      this.clockedMethods = clockedMethods;
    }

    @Override
    public void visitMethodInsn(
      final int opcode,
      final String owner,
      final String methodName,
      final String descriptor,
      final boolean isInterface
    ) {
      if (!owner.equals(SYSTEM_CLASS) || !methodName.equals("nanoTime")) {
        super.visitMethodInsn(opcode, owner, methodName, descriptor, isInterface);
        return;
      }

      this.clockedMethods.add(this.clockedMethod);
      super.visitMethodInsn(opcode, Mcv2SearchTest.class.getName().replace('.', '/'), "searchTime", descriptor, isInterface);
    }
  }

  private enum ClockScope {
    SEARCH,
    SEARCH_AND_BEGIN,
  }
}
