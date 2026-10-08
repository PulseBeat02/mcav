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

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.CHANNELS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_COMPACT;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_IMMEDIATE_MOTION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_INTRA;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_INTRA_Y4C1;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_MOTION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_PALETTE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_PATTERN;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_RESIDUAL;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_SKIP;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_SOLID;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.ROOT_SIZE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.chromaGrid;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.isResidual;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.lumaGrid;

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
 *
 * <p>The caller owns decoded arrays, reference arrays and workers. References are read synchronously and
 * never modified by the decoder; keep them stable until decoding returns. A reusable output array may be the reference
 * of a P frame itself, which the decoder then reads from a copy, but not a buffer another decode is reading. Output
 * contents may be partially overwritten if decoding fails. Custom worker pools are not shut down by decoding.
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
   * @throws NullPointerException if frame is null
   */
  public static byte[] decode(final Mcv2Frame frame, final byte @Nullable [] reference, final long referenceId) throws Mcv2Exception {
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
   * @throws NullPointerException if frame or workers is null
   */
  public static byte[] decode(final Mcv2Frame frame, final byte @Nullable [] reference, final long referenceId, final Workers workers)
    throws Mcv2Exception {
    return decode(frame, reference, referenceId, workers, null);
  }

  /**
   * Decodes a validated frame into a picture the caller may reuse from frame to frame, when it has the frame's size;
   * a valid frame's leaves cover every pixel, so nothing of the picture before is left. The picture may be the
   * reference itself, which a P frame then reads from a copy.
   *
   * @param frame       the frame
   * @param reference   the previous decoded picture, required for a P frame and ignored for a keyframe
   * @param referenceId the id of that picture
   * @param workers     the workers
   * @param into        the picture to decode into, or null or one of another size for a new one
   * @return the decoded picture: {@code into} when it had the size, else a new one of {@code width * height * 3} bytes
   * @throws Mcv2Exception if the frame is a P frame and the reference is missing, has the wrong size, or has another id
   * @throws NullPointerException if {@code frame} or {@code workers} is null
   */
  public static byte[] decode(
    final Mcv2Frame frame,
    final byte @Nullable [] reference,
    final long referenceId,
    final Workers workers,
    final byte @Nullable [] into
  ) throws Mcv2Exception {
    Preconditions.checkNotNull(frame, "Frame must not be null");
    Preconditions.checkNotNull(workers, "Workers must not be null");
    final int width = frame.getWidth();
    final int height = frame.getHeight();
    final byte[] referencePicture;
    if (frame.isKeyframe()) {
      referencePicture = new byte[0];
    } else {
      if (reference == null || referenceId != frame.getReferenceId() || reference.length != width * height * CHANNELS) {
        throw new Mcv2Exception("Reference frame mismatch");
      }
      // a caller may decode into the picture it passes as the reference, but the leaves read the reference's pixels
      // after other leaves wrote theirs, so the reference is read from a copy then
      referencePicture = isSamePicture(reference, into) ? reference.clone() : reference;
    }
    final byte[] output = into != null && into.length == width * height * CHANNELS ? into : new byte[width * height * CHANNELS];
    final int[] leaves = frame.leafArray();
    final int count = leaves.length / Mcv2Frame.LEAF_INTS;
    final int groups = (count + GROUP - 1) / GROUP;
    final AtomicReferenceArray<@Nullable Mcv2Exception> failures = new AtomicReferenceArray<>(groups);
    workers.forEach(
      groups,
      () -> new Context(frame, referencePicture, output),
      (context, group) -> {
        final int end = Math.min(count, (group + 1) * GROUP) * Mcv2Frame.LEAF_INTS;
        try {
          for (int leafOffset = group * GROUP * Mcv2Frame.LEAF_INTS; leafOffset < end; leafOffset += Mcv2Frame.LEAF_INTS) {
            context.leaf(
              leaves[leafOffset + Mcv2Frame.LEAF_X],
              leaves[leafOffset + Mcv2Frame.LEAF_Y],
              leaves[leafOffset + Mcv2Frame.LEAF_SIZE],
              leaves[leafOffset + Mcv2Frame.LEAF_MODE],
              leaves[leafOffset + Mcv2Frame.LEAF_Q],
              leaves[leafOffset + Mcv2Frame.LEAF_OFFSET]
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
   * @throws NullPointerException if the frame byte array is null
   */
  public static byte[] decode(final byte[] data, final byte @Nullable [] reference, final long referenceId) throws Mcv2Exception {
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

    private void predict(final int blockLeft, final int blockTop, final int size, final int motionX, final int motionY) {
      Reconstruction.predict(this.reference, this.width, this.height, blockLeft, blockTop, size, motionX, motionY, this.prediction);
    }

    void leaf(final int left, final int top, final int size, final int mode, final int quantizer, final int offset) throws Mcv2Exception {
      if (left >= this.width || top >= this.height) {
        return;
      }
      final int globalX = this.frame.getGlobalX();
      final int globalY = this.frame.getGlobalY();
      final byte[] data = this.data;
      final int[] out = this.block;
      switch (mode) {
        case MODE_SKIP -> {
          if (this.frame.isKeyframe()) {
            Reconstruction.solid(this.frame.getDefaultColor(), size, out);
          } else {
            this.predict(left, top, size, globalX, globalY);
            Reconstruction.predicted(this.prediction, size, out);
          }
        }
        case MODE_MOTION -> {
          this.predict(left, top, size, globalX + data[offset], globalY + data[offset + 1]);
          Reconstruction.predicted(this.prediction, size, out);
        }
        case MODE_IMMEDIATE_MOTION -> {
          this.predict(left, top, size, globalX + (byte) offset, globalY + (byte) (offset >> Byte.SIZE));
          Reconstruction.predicted(this.prediction, size, out);
        }
        case MODE_SOLID -> Reconstruction.solid(
          ((data[offset] & 0xFF) << 16) | ((data[offset + 1] & 0xFF) << 8) | (data[offset + 2] & 0xFF),
          size,
          out
        );
        case MODE_PALETTE -> Reconstruction.palette(data, offset, size, out);
        case MODE_PATTERN -> Reconstruction.pattern(
          PatternRecord.expand(data, offset, size, this.frame.endpointTable(), this.frame.selectorTable(size)),
          size,
          out
        );
        case MODE_COMPACT -> {
          final CompactRecord record = CompactRecord.parse(data, offset, quantizer);
          this.predict(left, top, size, globalX + record.dx(), globalY + record.dy());
          Reconstruction.compact(this.prediction, data, record.bodyOffset(), record.kind(), quantizer, size, this.scratch, out);
        }
        default -> {
          if (mode >= MODE_INTRA_Y4C1) {
            final boolean residual = isResidual(mode);
            if (residual) {
              this.predict(left, top, size, globalX + data[offset], globalY + data[offset + 1]);
            }
            Reconstruction.reduced(
              residual ? this.prediction : null,
              data,
              offset + (residual ? 2 : 0),
              lumaGrid(mode),
              chromaGrid(mode),
              quantizer,
              size,
              this.scratch,
              out
            );
          } else if (mode >= MODE_RESIDUAL) {
            this.predict(left, top, size, globalX + data[offset], globalY + data[offset + 1]);
            Reconstruction.residualGrid(this.prediction, data, offset + 2, 1 << (mode - MODE_RESIDUAL), quantizer, size, this.scratch, out);
          } else {
            Reconstruction.intraGrid(data, offset, 1 << (mode - MODE_INTRA), size, this.scratch, out);
          }
        }
      }
      final int right = Math.min(left + size, this.width);
      final int bottom = Math.min(top + size, this.height);
      for (int row = top; row < bottom; row++) {
        for (int column = left; column < right; column++) {
          final int from = ((row - top) * size + (column - left)) * CHANNELS;
          final int to = (row * this.width + column) * CHANNELS;
          this.output[to] = (byte) out[from];
          this.output[to + 1] = (byte) out[from + 1];
          this.output[to + 2] = (byte) out[from + 2];
        }
      }
    }
  }

  /** Whether the picture decoded into is the reference itself: identity is the point, as only then is a copy needed. */
  @SuppressWarnings("ReferenceEquality")
  private static boolean isSamePicture(final byte[] reference, final byte @Nullable [] into) {
    return reference == into;
  }
}
