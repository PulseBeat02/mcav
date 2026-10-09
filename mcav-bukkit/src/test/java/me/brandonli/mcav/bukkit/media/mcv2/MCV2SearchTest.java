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
import java.lang.reflect.Proxy;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;
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

final class MCV2SearchTest {

  private static final ThreadLocal<LongSupplier> SEARCH_TIME = new ThreadLocal<>();

  static long searchTime() {
    final LongSupplier fixed = SEARCH_TIME.get();
    return fixed == null ? System.nanoTime() : fixed.getAsLong();
  }

  private static ClassFileTransformer searchClock(final Class<?> encoderType, final boolean includeBegin) {
    return new ClassFileTransformer() {
      @Override
      public byte @Nullable [] transform(
        final ClassLoader loader,
        final String className,
        final Class<?> redefined,
        final ProtectionDomain domain,
        final byte[] original
      ) {
        if (redefined != encoderType) {
          return null;
        }
        // The incoming definition retains the mutation installed by PIT.
        final ClassReader reader = new ClassReader(original);
        final ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(
          new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
              final int access,
              final String methodName,
              final String descriptor,
              final String signature,
              final String[] exceptions
            ) {
              final MethodVisitor visitor = super.visitMethod(access, methodName, descriptor, signature, exceptions);
              if (!methodName.startsWith("lambda$search$") && !(includeBegin && methodName.equals("begin"))) {
                return visitor;
              }
              return new MethodVisitor(Opcodes.ASM9, visitor) {
                @Override
                public void visitMethodInsn(
                  final int opcode,
                  final String owner,
                  final String methodName,
                  final String descriptor,
                  final boolean isInterface
                ) {
                  if (owner.equals("java/lang/System") && methodName.equals("nanoTime")) {
                    super.visitMethodInsn(opcode, MCV2SearchTest.class.getName().replace('.', '/'), "searchTime", descriptor, isInterface);
                  } else {
                    super.visitMethodInsn(opcode, owner, methodName, descriptor, isInterface);
                  }
                }
              };
            }
          },
          0
        );
        return writer.toByteArray();
      }
    };
  }

  @Test
  void usesMeanSolidsAtTheBudgetDeadlineAndSearchesBeforeIt() throws UnmodifiableClassException {
    final Class<?> encoderType = MCV2.class;
    final Instrumentation instrumentation = PremainAttachAccess.getInstrumentation();
    final ClassFileTransformer clock = searchClock(encoderType, false);
    instrumentation.addTransformer(clock, true);
    try {
      instrumentation.retransformClasses(encoderType);
      final byte[] source = new byte[32 * 32 * 3];
      for (int row = 0; row < 32; row++) {
        for (int column = 0; column < 32; column++) {
          source[(row * 32 + column) * 3] = (byte) (column < 16 ? 10 : 200);
        }
      }
      for (final long instant : new long[] { 99, 100, 101 }) {
        SEARCH_TIME.set(() -> instant);
        final Mcv2BlockState state = new Mcv2BlockState(source, new byte[0], 32, 32, true, false, 72, null);
        final Class<?> frameType = Mcv2Internals.nested("FrameState");
        final Object frame = Mcv2Internals.field(Mcv2BlockState.class, state, "frame");
        try (final ForkJoinPool pool = new ForkJoinPool(1)) {
          final MCV2 encoder = new MCV2(MCV2.Settings.DEFAULT, pool, 1, false);
          encoder.setFrameBudget(100);
          final Object result = Mcv2Internals.invoke(
            encoderType,
            encoder,
            "search",
            new Class<?>[] { frameType, boolean[].class, long.class, boolean.class },
            frame,
            new boolean[0],
            0L,
            false
          );
          final List<?> roots = (List<?>) Mcv2Internals.field(result.getClass(), result, "roots");
          assertEquals(1, roots.size());
          final Node root = new Node(roots.getFirst());
          assertEquals(instant >= 100 ? Mcv2Decoder.MODE_SOLID : Mcv2Decoder.MODE_PATTERN, root.getMode());
          final byte[] expected = source.clone();
          if (instant >= 100) {
            for (int pixel = 0; pixel < 32 * 32; pixel++) {
              expected[pixel * 3] = 105;
            }
            assertArrayEquals(new byte[] { 105, 0, 0 }, root.getRecord());
          }
          assertArrayEquals(expected, (byte[]) Mcv2Internals.field(frameType, frame, "picture"));
        }
      }
    } finally {
      SEARCH_TIME.remove();
      instrumentation.removeTransformer(clock);
      instrumentation.retransformClasses(encoderType);
    }
  }

  @Test
  void fallsBackDirectlyAtZeroLambdaWhenTheFrameExceedsItsLimit() throws UnmodifiableClassException, Mcv2Exception {
    final Class<?> encoderType = MCV2.class;
    final Instrumentation instrumentation = PremainAttachAccess.getInstrumentation();
    final ClassFileTransformer clock = searchClock(encoderType, true);
    instrumentation.addTransformer(clock, true);
    try {
      instrumentation.retransformClasses(encoderType);
      final long[] instants = { 0, 0, 0, 0, 100 };
      final AtomicInteger readings = new AtomicInteger();
      SEARCH_TIME.set(() -> instants[Math.min(readings.getAndIncrement(), instants.length - 1)]);
      final byte[] source = new byte[64 * 32 * 3];
      final byte[] expected = new byte[source.length];
      for (int row = 0; row < 32; row++) {
        for (int column = 0; column < 64; column++) {
          final int pixel = (row * 64 + column) * 3;
          source[pixel] = (byte) (column < 32 ? (column < 16 ? 10 : 200) : column < 48 ? 20 : 180);
          expected[pixel] = (byte) (column < 32 ? 105 : 100);
        }
      }
      try (final ForkJoinPool pool = new ForkJoinPool(1)) {
        final MCV2 encoder = new MCV2(MCV2.Settings.DEFAULT.withLambda(0), pool, 1, true);
        encoder.setFrameBudget(100);
        encoder.setFrameLimit(60);
        final byte[] data = encoder.encode(source, 64, 32, 0);
        assertEquals(52, data.length);
        assertArrayEquals(expected, Mcv2Decoder.decode(data, null, 0));
        assertArrayEquals(expected, encoder.getReference());
      }
    } finally {
      SEARCH_TIME.remove();
      instrumentation.removeTransformer(clock);
      instrumentation.retransformClasses(encoderType);
    }
  }

  private static int vector(final int motionX, final int motionY) {
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
      MCV2.class,
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
  void findsEachLeafsVector() {
    final Node[] nodes = {
      Node.skip(),
      Mcv2Trees.solid(1, 2, 3),
      Mcv2Trees.motion(3, -4),
      Node.leaf(Mcv2Decoder.MODE_COMPACT, 0, new byte[] { 0, 0, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11 }),
      Node.leaf(Mcv2Decoder.MODE_COMPACT, 0, new byte[] { 2, -2, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11 }),
      Node.leaf(Mcv2Decoder.MODE_COMPACT, 0, new byte[] { 20, -30, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11 }),
    };
    final int[] expected = { 0, 0, vector(3, -4), 0, vector(2, -2), vector(20, -30) };
    for (int index = 0; index < nodes.length; index++) {
      final int[] field = new int[1];
      fillMotion(field, 8, 8, nodes[index], 0, 0, 8);
      assertEquals(expected[index], field[0]);
    }
  }

  @Test
  void keepsOneVectorPerEightPixelCellOfTheLeaves() {
    final Node split = Node.split(Mcv2Trees.motion(4, 0), Node.skip(), Node.skip(), Node.skip());
    final int[] field = new int[5 * 3];
    fillMotion(field, 40, 20, split, 0, 0, 32);
    fillMotion(field, 40, 20, Mcv2Trees.solid(0, 0, 0), 32, 0, 32);
    assertArrayEquals(new int[] { vector(4, 0), vector(4, 0), 0, 0, 0, vector(4, 0), vector(4, 0), 0, 0, 0, 0, 0, 0, 0, 0 }, field);
  }

  @Test
  void seedsTheLocalSearchFromThePreviousFrame() {
    final byte[] reference = Mcv2Pictures.scene(64, 64, 0, 0);
    final byte[] source = Mcv2Pictures.scene(64, 64, 2, 3);
    final int[] field = new int[8 * 8];
    Arrays.fill(field, vector(6, 0));
    final Mcv2BlockState job = new Mcv2BlockState(source, reference, 64, 64, false, false, 72, field);
    assertEquals(vector(6, 0), job.previousMotion(-5, 100));
    final Mcv2BlockState fresh = new Mcv2BlockState(source, reference, 64, 64, false, false, 72, null);
    assertEquals(0, fresh.previousMotion(10, 10));
    job.code(16, 1, 0, 0, 0, vector(6, 0));
    assertTrue(job.cost(1, 0) < Double.POSITIVE_INFINITY);
    for (int row = 0; row < 8; row++) {
      for (int column = 0; column < 8; column++) {
        field[row * 8 + column] = vector(2 * column, -2 * row);
      }
    }
    for (final int parent : new int[] { Mcv2BlockState.NO_VECTOR, vector(-6, -4) }) {
      final Mcv2BlockState seeded = new Mcv2BlockState(source, reference, 64, 64, false, false, 0, field);
      final Object coder = seeded.code(16, 1, 5, 16, 16, parent);
      assertArrayEquals(
        new int[] {
          vector(3, -3),
          vector(1, -3),
          vector(4, -3),
          vector(3, -1),
          vector(3, -4),
          parent == Mcv2BlockState.NO_VECTOR ? vector(3, -3) : vector(-3, -2),
        },
        Mcv2BlockState.seeds(coder, "halfSeeds")
      );
    }
  }

  @Test
  void skipsEarlyExactlyAtTheThresholdAndMonotonicallyInLambda() {
    final byte[] reference = Mcv2Pictures.scene(32, 32, 0, 0);
    for (final boolean fast : new boolean[] { false, true }) {
      for (int amplitude = 0; amplitude <= 12; amplitude += 2) {
        final Random random = new Random(amplitude);
        final byte[] source = reference.clone();
        for (int index = 0; index < source.length; index++) {
          source[index] = (byte) Math.clamp((source[index] & 255) + random.nextInt(2 * amplitude + 1) - amplitude, 0, 255);
        }
        final long distortion = MCV2SearchPropertyTest.distortion(source, reference);
        boolean skippedBefore = false;
        for (final double lambda : new double[] { 10, 30, 65.255994022, 150, 400, 1200 }) {
          final Mcv2BlockState job = new Mcv2BlockState(source, reference, 32, 32, false, fast, lambda, null);
          final Object coder = job.code(32, 0, 0, 0, 0, Mcv2BlockState.NO_VECTOR);
          final boolean skipped = Mcv2BlockState.skipped(coder);
          assertEquals(distortion / 96.0 + lambda <= (fast ? 60 : 28) * lambda, skipped);
          assertTrue(!skippedBefore || skipped, "monotonic in lambda");
          skippedBefore = skipped;
          if (skipped) {
            assertEquals(Mcv2Decoder.MODE_SKIP, job.mode(0, 0));
          }
        }
      }
    }
  }

  @Test
  void keepsFastSkipAtExactlyItsSixtyBitBound() {
    final byte[] source = new byte[32 * 32 * 3];
    for (int pixel = 0; pixel < 32 * 32; pixel++) {
      source[pixel * 3] = 3;
    }
    final double lambda = 864.0 / 59;
    final Mcv2BlockState state = new Mcv2BlockState(source, new byte[source.length], 32, 32, false, true, lambda, null);
    final Object coder = state.code(32, 0, 0, 0, 0, Mcv2BlockState.NO_VECTOR);
    assertEquals(60 * lambda, state.cost(0, 0));
    assertTrue(Mcv2BlockState.skipped(coder));
    assertEquals(Mcv2Decoder.MODE_SKIP, state.mode(0, 0));
    assertArrayEquals(new byte[0], state.record(0, 0));
  }

  @Test
  void predictsANewChildVectorFromTheReference() throws ReflectiveOperationException {
    final byte[] reference = new byte[32 * 32 * 3];
    for (int row = 0; row < 32; row++) {
      for (int column = 0; column < 32; column++) {
        final int pixel = (row * 32 + column) * 3;
        reference[pixel] = (byte) column;
        reference[pixel + 1] = (byte) row;
        reference[pixel + 2] = (byte) (row + column);
      }
    }
    final Mcv2BlockState state = new Mcv2BlockState(reference, reference, 32, 32, false, false, 72, null);
    final Object root = state.code(32, 0, 0, 0, 0, Mcv2BlockState.NO_VECTOR);
    final Object child = state.code(16, 1, 0, 16, 16, Mcv2BlockState.NO_VECTOR);
    final Class<?> coderType = Mcv2Internals.nested("BlockCoder");
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
    Mcv2Internals.call(coderType, child, "predict", new Class<?>[] { int.class, int[].class }, vector(1, 0), actual);
    assertArrayEquals(expected, actual);
  }

  private static Object descend(final Mcv2BlockState state, final int level, final int left, final int top, final double threshold) {
    final Class<?> frameType = Mcv2Internals.nested("FrameState");
    final Class<?> coderType = Mcv2Internals.nested("BlockCoder");
    final Class<?> kernelType = Mcv2Internals.nested("Kernels");
    final Object frame = Mcv2Internals.field(Mcv2BlockState.class, state, "frame");
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
    return Mcv2Internals.invoke(
      MCV2.class,
      null,
      "descend",
      new Class<?>[] { frameType, coders.getClass(), int.class, int.class, int.class, int.class, double.class, double.class },
      frame,
      coders,
      level,
      left,
      top,
      Mcv2BlockState.NO_VECTOR,
      threshold,
      0.0
    );
  }

  @Test
  void representsCompletelyOutsideChildrenAsBlackWithNoVisibleLeaves() {
    final byte[] source = new byte[16 * 16 * 3];
    for (int pixel = 0; pixel < 16 * 16; pixel++) {
      source[pixel * 3] = 10;
      source[pixel * 3 + 1] = 20;
      source[pixel * 3 + 2] = 30;
    }
    final Mcv2BlockState state = new Mcv2BlockState(source, new byte[0], 16, 16, true, false, 2, null);
    for (final int[] position : new int[][] { { 16, 0 }, { 0, 16 }, { 16, 16 } }) {
      final Object choice = descend(state, 1, position[0], position[1], 0);
      final Node node = new Node(Mcv2Internals.field(choice.getClass(), choice, "node"));
      assertEquals(Mcv2Trees.solid(0, 0, 0), node);
      assertEquals(0, Mcv2Internals.field(choice.getClass(), choice, "leaves"));
      assertEquals(112.0, Mcv2Internals.field(choice.getClass(), choice, "cost"));
    }
  }

  @Test
  void splitsOnlyAboveTheThresholdWhenChildrenAreCheaper() {
    final byte[] source = new byte[32 * 32 * 3];
    for (int row = 0; row < 32; row++) {
      for (int column = 0; column < 32; column++) {
        source[(row * 32 + column) * 3 + row / 16] = (byte) (column < 16 ? 40 : 80);
      }
    }
    final Mcv2BlockState state = new Mcv2BlockState(source, new byte[0], 32, 32, true, false, 1, null);
    state.code(32, 0, 0, 0, 0, Mcv2BlockState.NO_VECTOR);
    final double cost = state.cost(0, 0);
    final Object equal = descend(state, 0, 0, 0, cost);
    assertFalse(new Node(Mcv2Internals.field(equal.getClass(), equal, "node")).isSplit());
    final Object cheaper = descend(state, 0, 0, 0, Math.nextDown(cost));
    final Node split = new Node(Mcv2Internals.field(cheaper.getClass(), cheaper, "node"));
    assertTrue(split.isSplit());
    assertEquals(Mcv2Trees.solid(40, 0, 0), split.getChild(0));
    assertEquals(Mcv2Trees.solid(80, 0, 0), split.getChild(1));
    assertEquals(Mcv2Trees.solid(0, 40, 0), split.getChild(2));
    assertEquals(Mcv2Trees.solid(0, 80, 0), split.getChild(3));
  }

  @Test
  void reusesCodersWithoutRetainingBusyEntries() {
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      final MCV2 encoder = new MCV2(MCV2.Settings.DEFAULT, pool, 1, true);
      final Collection<?> idle = (Collection<?>) Mcv2Internals.field(MCV2.class, encoder, "idleCoders");
      final Collection<?> busy = (Collection<?>) Mcv2Internals.field(MCV2.class, encoder, "busyCoders");
      encoder.encode(new byte[3], 1, 1, 0);
      final Object coders = idle.iterator().next();
      for (int frame = 1; frame < 4; frame++) {
        encoder.encode(new byte[3], 1, 1, frame);
        assertTrue(busy.isEmpty());
        assertEquals(1, idle.size());
        assertEquals(coders, idle.iterator().next());
      }
    }
  }

  @Test
  void storesOneMotionVectorPerCellAndOnlyTheReducedPictures() {
    try (final ForkJoinPool pool = new ForkJoinPool(1)) {
      for (final int[] dimensions : new int[][] { { 65, 37, 33, 19, 17, 10 }, { 71, 39, 36, 20, 18, 10 } }) {
        final int width = dimensions[0];
        final int height = dimensions[1];
        final int halfWidth = dimensions[2];
        final int halfHeight = dimensions[3];
        final int quarterWidth = dimensions[4];
        final int quarterHeight = dimensions[5];
        final MCV2 encoder = new MCV2(MCV2.Settings.DEFAULT, pool, 1, true);
        final byte[] source = Mcv2Pictures.scene(width, height, 0, 0);
        encoder.encode(source, width, height, 0);
        final byte[] reference = encoder.getReference();
        encoder.encode(source, width, height, 1);
        assertEquals(9 * 5, ((int[]) Mcv2Internals.field(MCV2.class, encoder, "motion")).length);
        final Class<?> buffersType = Mcv2Internals.nested("Buffers");
        final Object buffers = Mcv2Internals.field(MCV2.class, encoder, "buffers");
        final byte[] half = (byte[]) Mcv2Internals.field(buffersType, buffers, "half");
        final byte[] quarter = (byte[]) Mcv2Internals.field(buffersType, buffers, "quarter");
        assertEquals(halfWidth * halfHeight * 3, half.length);
        assertEquals(quarterWidth * quarterHeight * 3, quarter.length);
        assertArrayEquals(Mcv2BlockState.half(reference, width, height, new byte[half.length]), half);
        assertArrayEquals(Mcv2BlockState.half(half, halfWidth, halfHeight, new byte[quarter.length]), quarter);
        final Collection<?> idle = (Collection<?>) Mcv2Internals.field(MCV2.class, encoder, "idleCoders");
        final Object coders = idle.iterator().next();
        final Class<?> coderType = Mcv2Internals.nested("BlockCoder");
        for (int level = 0; level < 3; level++) {
          final Object coder = Array.get(coders, level);
          final int size = 32 >> level;
          assertEquals((size / 2) * (size / 2) * 3, ((int[]) Mcv2Internals.field(coderType, coder, "halfSource")).length);
          assertEquals((size / 4) * (size / 4) * 3, ((int[]) Mcv2Internals.field(coderType, coder, "quarterSource")).length);
        }
      }
    }
  }

  @Test
  void keepsASkippedRootBelowTheNormalSplitThresholdEvenWhenOneChildFitsMoreClosely() throws Mcv2Exception {
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
      final MCV2 encoder = new MCV2(MCV2.Settings.DEFAULT.withLambda(2), pool, 1, true);
      assertEquals(4, Mcv2Decoder.parse(encoder.encode(reference, 32, 32, 0)).getLeafCount());
      assertArrayEquals(reference, encoder.getReference());
      final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(encoder.encode(source, 32, 32, 1));
      assertEquals(1, frame.getLeafCount());
      assertEquals(32, frame.getLeaf(0).size());
      assertEquals(Mcv2Decoder.MODE_SKIP, frame.getLeaf(0).mode());
      assertArrayEquals(reference, Mcv2Decoder.decode(frame, reference, 0));
      assertArrayEquals(reference, encoder.getReference());
    }
  }

  @Test
  void scalesTheMotionRangeToEachPyramidLevel() {
    for (final boolean fast : new boolean[] { false, true }) {
      final Mcv2BlockState state = new Mcv2BlockState(new byte[32 * 32 * 3], new byte[32 * 32 * 3], 32, 32, false, fast, 72, null);
      final Object frame = Mcv2Internals.field(Mcv2BlockState.class, state, "frame");
      final Class<?> kernelType = Mcv2Internals.nested("Kernels");
      final List<Integer> ranges = new ArrayList<>();
      final Object kernels = Proxy.newProxyInstance(kernelType.getClassLoader(), new Class<?>[] { kernelType }, (_, method, arguments) -> {
        if (method.getName().equals("seeded")) {
          ranges.add((Integer) arguments[7]);
          return 0;
        }
        assertEquals("halve", method.getName());
        return null;
      });
      final Class<?> coderType = Mcv2Internals.nested("BlockCoder");
      final Object coder = Mcv2Internals.construct(
        coderType,
        new Class<?>[] { Mcv2Internals.nested("FrameState"), int.class, kernelType },
        frame,
        32,
        kernels
      );
      assertEquals(0, Mcv2Internals.invoke(coderType, coder, "coarseMotion", new Class<?>[] { int.class }, 2));
      assertEquals(fast ? List.of(12) : List.of(6, 12), ranges);
    }
  }

  @Test
  void retainsSixteenPixelLeavesBelowTheNormalFineSplitThreshold() throws Mcv2Exception {
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
      final MCV2 encoder = new MCV2(MCV2.Settings.DEFAULT.withLambda(2), pool, 1, true);
      final Mcv2Decoder.Frame frame = Mcv2Decoder.parse(encoder.encode(source, 32, 32, 0));
      assertEquals(4, frame.getLeafCount());
      for (int index = 0; index < frame.getLeafCount(); index++) {
        final Mcv2Decoder.Leaf leaf = frame.getLeaf(index);
        assertEquals(16, leaf.size());
        assertEquals(Mcv2Decoder.MODE_SOLID, leaf.mode());
      }
      final byte[] expected = new byte[source.length];
      for (int row = 0; row < 32; row++) {
        for (int column = 0; column < 32; column++) {
          final int pixel = (row * 32 + column) * 3;
          expected[pixel] = 3;
          expected[pixel + 1] = 3;
          expected[pixel + 2] = source[pixel + 2];
        }
      }
      assertArrayEquals(expected, Mcv2Decoder.decode(frame, null, 0));
    }
  }

  @Test
  void halvesAPictureWithItsOddLastRowAndColumnStandingAlone() {
    final int[] red = { 0, 4, 9, 8, 12, 20, 100, 200, 255 };
    final byte[] picture = new byte[3 * 3 * 3];
    for (int index = 0; index < red.length; index++) {
      picture[index * 3] = (byte) red[index];
      picture[index * 3 + 1] = (byte) index;
    }
    assertArrayEquals(
      new byte[] { 6, 2, 0, 15, 4, 0, (byte) 150, 7, 0, (byte) 255, 8, 0 },
      Mcv2BlockState.half(picture, 3, 3, new byte[2 * 2 * 3])
    );
  }
}
