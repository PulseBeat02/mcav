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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Internals.Workers;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Access to the real block search without widening the encoder's API. */
final class Mcv2BlockState {

  private static final Class<?> BUFFERS = Mcv2Internals.nested("Buffers");
  private static final Class<?> FRAME = Mcv2Internals.nested("FrameState");
  private static final Class<?> CODER = Mcv2Internals.nested("BlockCoder");
  static final int NO_VECTOR = (int) Mcv2Internals.field(MCV2.class, null, "NO_VECTOR");
  private final Object buffers;

  private record Block(int level, int index) {}

  private final Map<Block, Object> coders = new HashMap<>();
  private final Object frame;

  Mcv2BlockState(
    final byte[] source,
    final byte[] reference,
    final int width,
    final int height,
    final boolean keyframe,
    final boolean fast,
    final double lambda,
    final int @Nullable [] previous
  ) {
    this.buffers = Mcv2Internals.construct(BUFFERS, new Class<?>[] { int.class, int.class }, width, height);
    if (!keyframe) {
      final byte[] half = (byte[]) Mcv2Internals.field(BUFFERS, this.buffers, "half");
      final byte[] quarter = (byte[]) Mcv2Internals.field(BUFFERS, this.buffers, "quarter");
      half(reference, width, height, half);
      half(half, (width + 1) / 2, (height + 1) / 2, quarter);
    }
    this.frame = Mcv2Internals.construct(
      FRAME,
      new Class<?>[] {
        byte[].class,
        byte[].class,
        int.class,
        int.class,
        boolean.class,
        boolean.class,
        double.class,
        int[].class,
        BUFFERS,
        byte[].class,
      },
      source,
      reference,
      width,
      height,
      keyframe,
      fast,
      lambda,
      previous == null ? new int[((width + 7) / 8) * ((height + 7) / 8)] : previous,
      this.buffers,
      new byte[source.length]
    );
  }

  static byte[] half(final byte[] picture, final int width, final int height, final byte[] out) {
    Mcv2Internals.call(
      MCV2.class,
      null,
      "half",
      new Class<?>[] { byte[].class, int.class, int.class, Mcv2Internals.nested("Workers"), byte[].class },
      picture,
      width,
      height,
      Mcv2Internals.field(Workers.class, Workers.SEQUENTIAL, "value"),
      out
    );
    return out;
  }

  Object code(final int size, final int level, final int block, final int left, final int top, final int parent) {
    final Object kernels = Mcv2Internals.construct(Mcv2Internals.nested("JavaKernels"), new Class<?>[0]);
    final Object coder = Mcv2Internals.construct(
      CODER,
      new Class<?>[] { FRAME, int.class, Mcv2Internals.nested("Kernels") },
      this.frame,
      size,
      kernels
    );
    Mcv2Internals.call(CODER, coder, "code", new Class<?>[] { int.class, int.class, int.class }, left, top, parent);
    this.coders.put(new Block(level, block), coder);
    return coder;
  }

  int previousMotion(final int left, final int top) {
    return (int) Mcv2Internals.invoke(FRAME, this.frame, "previousMotion", new Class<?>[] { int.class, int.class }, left, top);
  }

  private Object coder(final int level, final int block) {
    return this.coders.get(new Block(level, block));
  }

  int mode(final int level, final int block) {
    return (int) Mcv2Internals.field(CODER, this.coder(level, block), "mode");
  }

  int quantizer(final int level, final int block) {
    return (int) Mcv2Internals.field(CODER, this.coder(level, block), "quantizer");
  }

  long distortion(final int level, final int block) {
    final Object coder = this.coder(level, block);
    final int[] source = (int[]) Mcv2Internals.field(CODER, coder, "source");
    final int[] best = (int[]) Mcv2Internals.field(CODER, coder, "best");
    final byte[] picture = new byte[source.length];
    final byte[] reconstruction = new byte[source.length];
    for (int index = 0; index < source.length; index++) {
      picture[index] = (byte) source[index];
      reconstruction[index] = (byte) best[index];
    }
    return MCV2SearchPropertyTest.distortion(picture, reconstruction);
  }

  double cost(final int level, final int block) {
    return (double) Mcv2Internals.field(CODER, this.coder(level, block), "bestCost");
  }

  byte[] record(final int level, final int block) {
    final Object coder = this.coder(level, block);
    return Arrays.copyOf((byte[]) Mcv2Internals.field(CODER, coder, "bestRecord"), (int) Mcv2Internals.field(CODER, coder, "length"));
  }

  static int localVector(final Object coder) {
    return (int) Mcv2Internals.field(CODER, coder, "localVector");
  }

  static boolean skipped(final Object coder) {
    return (boolean) Mcv2Internals.field(CODER, coder, "skipped");
  }

  static int[] seeds(final Object coder, final String field) {
    return (int[]) Mcv2Internals.field(CODER, coder, field);
  }

  static int neededQuantizer(final float[] fit) {
    return (int) Mcv2Internals.invoke(MCV2.class, null, "neededQuantizer", new Class<?>[] { float[].class }, fit);
  }
}
