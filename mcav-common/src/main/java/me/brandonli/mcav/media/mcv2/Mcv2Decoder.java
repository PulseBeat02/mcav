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
package me.brandonli.mcav.media.mcv2;

import static me.brandonli.mcav.media.mcv2.Mcv2Format.*;

import com.google.common.base.Preconditions;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The CPU reference decoder of MCV2, bit-exact with the Python reference decoder.
 *
 * <p>Every leaf is reconstructed independently from the frame's records and, on P frames, the previous decoded frame.
 * The arithmetic reproduces the reference's float32 and float64 operations in their order, which is what makes the
 * output bit-exact: motion prediction and grid interpolation are exact dyadic values, the YCoCg to RGB conversion and
 * the final add are rounded exactly as the reference rounds them, and every channel ends as
 * {@code floor(clamp(value, 0, 255) + 0.5)}.
 *
 * <p>Pictures are row-major RGB, three bytes per pixel. The decoder has no state and is thread-safe.
 */
public final class Mcv2Decoder {

  /** Leaves decoded by one worker at a time. */
  private static final int GROUP = 256;

  private Mcv2Decoder() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Decodes a validated frame on the calling thread.
   *
   * @param frame       the frame
   * @param reference   the previous decoded picture, required for a P frame and ignored for a keyframe
   * @param referenceId the id of that picture
   * @return the decoded picture, {@code width * height * 3} bytes
   * @throws Mcv2Exception if the frame is a P frame and the reference is missing, has the wrong size, or has another id
   */
  public static byte[] decode(final Mcv2Frame frame, final byte@Nullable[] reference, final long referenceId) throws Mcv2Exception {
    return decode(frame, reference, referenceId, Workers.SEQUENTIAL);
  }

  /**
   * Decodes a validated frame. Every leaf covers its own pixels, so groups of leaves are decoded on the workers, each
   * with its own scratch space; the picture is the same for any number of workers.
   *
   * @param frame       the frame
   * @param reference   the previous decoded picture, required for a P frame and ignored for a keyframe
   * @param referenceId the id of that picture
   * @param workers     the workers
   * @return the decoded picture, {@code width * height * 3} bytes
   * @throws Mcv2Exception if the frame is a P frame and the reference is missing, has the wrong size, or has another id
   */
  public static byte[] decode(final Mcv2Frame frame, final byte@Nullable[] reference, final long referenceId, final Workers workers)
    throws Mcv2Exception {
    return decode(frame, reference, referenceId, workers, null);
  }

  /**
   * Decodes a validated frame into a picture the caller may reuse from frame to frame, when it has the frame's size;
   * a valid frame's leaves cover every pixel, so nothing of the picture before is left.
   *
   * @param frame       the frame
   * @param reference   the previous decoded picture, required for a P frame and ignored for a keyframe
   * @param referenceId the id of that picture
   * @param workers     the workers
   * @param into        the picture to decode into, or null or one of another size for a new one
   * @return the decoded picture: {@code into} when it had the size, else a new one of {@code width * height * 3} bytes
   * @throws Mcv2Exception if the frame is a P frame and the reference is missing, has the wrong size, or has another id
   */
  public static byte[] decode(
    final Mcv2Frame frame,
    final byte@Nullable[] reference,
    final long referenceId,
    final Workers workers,
    final byte@Nullable[] into
  ) throws Mcv2Exception {
    Preconditions.checkNotNull(frame, "Frame must not be null");
    Preconditions.checkNotNull(workers, "Workers must not be null");
    final int width = frame.getWidth();
    final int height = frame.getHeight();
    final byte[] ref;
    if (frame.isKeyframe()) {
      ref = new byte[0];
    } else {
      if (reference == null || referenceId != frame.getReferenceId() || reference.length != width * height * CHANNELS) {
        throw new Mcv2Exception("Reference frame mismatch");
      }
      ref = reference;
    }
    final byte[] output = into != null && into.length == width * height * CHANNELS ? into : new byte[width * height * CHANNELS];
    final int[] leaves = frame.leafArray();
    final int count = leaves.length / Mcv2Frame.LEAF_INTS;
    final int groups = (count + GROUP - 1) / GROUP;
    final AtomicReferenceArray<@Nullable Mcv2Exception> failures = new AtomicReferenceArray<>(groups);
    workers.forEach(
      groups,
      () -> new Context(frame, ref, output),
      (context, group) -> {
        final int end = Math.min(count, (group + 1) * GROUP) * Mcv2Frame.LEAF_INTS;
        try {
          for (int i = group * GROUP * Mcv2Frame.LEAF_INTS; i < end; i += Mcv2Frame.LEAF_INTS) {
            context.leaf(
              leaves[i + Mcv2Frame.LEAF_X],
              leaves[i + Mcv2Frame.LEAF_Y],
              leaves[i + Mcv2Frame.LEAF_SIZE],
              leaves[i + Mcv2Frame.LEAF_MODE],
              leaves[i + Mcv2Frame.LEAF_Q],
              leaves[i + Mcv2Frame.LEAF_OFFSET]
            );
          }
        } catch (final Mcv2Exception exception) {
          failures.set(group, exception);
        }
      }
    );
    // the failure a sequential decode would meet first
    for (int group = 0; group < groups; group++) {
      final Mcv2Exception failure = failures.get(group);
      if (failure != null) {
        throw failure;
      }
    }
    return output;
  }

  /**
   * Parses and decodes frame bytes.
   *
   * @param data        the frame bytes
   * @param reference   the previous decoded picture, required for a P frame
   * @param referenceId the id of that picture
   * @return the decoded picture
   * @throws Mcv2Exception if the bytes are not a valid frame or the reference does not match
   */
  public static byte[] decode(final byte[] data, final byte@Nullable[] reference, final long referenceId) throws Mcv2Exception {
    return decode(FrameParser.parse(data), reference, referenceId);
  }

  /** The state of one decode: the frame, the reference and the output picture. */
  private static final class Context {

    private final Mcv2Frame frame;

    private final byte[] data;

    private final byte[] reference;

    private final byte[] output;

    private final int width;

    private final int height;

    private final Reconstruction.Scratch scratch = new Reconstruction.Scratch();

    private final int[] prediction = new int[ROOT_SIZE * ROOT_SIZE * CHANNELS];

    private final int[] block = new int[ROOT_SIZE * ROOT_SIZE * CHANNELS];

    private Context(final Mcv2Frame frame, final byte[] reference, final byte[] output) {
      this.frame = frame;
      this.data = frame.data();
      this.reference = reference;
      this.output = output;
      this.width = frame.getWidth();
      this.height = frame.getHeight();
    }

    private void predict(final int x, final int y, final int size, final int mx, final int my) {
      Reconstruction.predict(this.reference, this.width, this.height, x, y, size, mx, my, this.prediction);
    }

    void leaf(final int x, final int y, final int size, final int mode, final int q, final int offset) throws Mcv2Exception {
      if (x >= this.width || y >= this.height) {
        return;
      }
      final int gx = this.frame.getGlobalX();
      final int gy = this.frame.getGlobalY();
      final byte[] d = this.data;
      final int[] out = this.block;
      switch (mode) {
        case MODE_SKIP -> {
          if (this.frame.isKeyframe()) {
            Reconstruction.solid(this.frame.getDefaultColor(), size, out);
          } else {
            this.predict(x, y, size, gx, gy);
            Reconstruction.predicted(this.prediction, size, out);
          }
        }
        case MODE_MOTION -> {
          this.predict(x, y, size, gx + d[offset], gy + d[offset + 1]);
          Reconstruction.predicted(this.prediction, size, out);
        }
        case MODE_IMMEDIATE_MOTION -> {
          this.predict(x, y, size, gx + (byte) offset, gy + (byte) (offset >> Byte.SIZE));
          Reconstruction.predicted(this.prediction, size, out);
        }
        case MODE_SOLID -> Reconstruction.solid(
          ((d[offset] & 0xFF) << 16) | ((d[offset + 1] & 0xFF) << 8) | (d[offset + 2] & 0xFF),
          size,
          out
        );
        case MODE_PALETTE -> Reconstruction.palette(d, offset, size, out);
        case MODE_PATTERN -> Reconstruction.pattern(
          PatternRecord.expand(d, offset, size, this.frame.endpointTable(), this.frame.selectorTable(size)),
          size,
          out
        );
        case MODE_COMPACT -> {
          final CompactRecord record = CompactRecord.parse(d, offset, q);
          this.predict(x, y, size, gx + record.dx(), gy + record.dy());
          Reconstruction.compact(this.prediction, d, record.bodyOffset(), record.kind(), q, size, this.scratch, out);
        }
        default -> {
          if (mode >= MODE_INTRA_Y4C1) {
            final boolean residual = isResidual(mode);
            if (residual) {
              this.predict(x, y, size, gx + d[offset], gy + d[offset + 1]);
            }
            Reconstruction.reduced(
              residual ? this.prediction : null,
              d,
              offset + (residual ? 2 : 0),
              lumaGrid(mode),
              chromaGrid(mode),
              q,
              size,
              this.scratch,
              out
            );
          } else if (mode >= MODE_RESIDUAL) {
            this.predict(x, y, size, gx + d[offset], gy + d[offset + 1]);
            Reconstruction.residualGrid(this.prediction, d, offset + 2, 1 << (mode - MODE_RESIDUAL), q, size, this.scratch, out);
          } else {
            Reconstruction.intraGrid(d, offset, 1 << (mode - MODE_INTRA), size, this.scratch, out);
          }
        }
      }
      final int right = Math.min(x + size, this.width);
      final int bottom = Math.min(y + size, this.height);
      for (int py = y; py < bottom; py++) {
        for (int px = x; px < right; px++) {
          final int from = ((py - y) * size + (px - x)) * CHANNELS;
          final int to = (py * this.width + px) * CHANNELS;
          this.output[to] = (byte) out[from];
          this.output[to + 1] = (byte) out[from + 1];
          this.output[to + 2] = (byte) out[from + 2];
        }
      }
    }
  }
}
