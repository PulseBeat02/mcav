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
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.SMALLEST_BLOCK;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.chromaGrid;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.isResidual;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.lumaGrid;

import com.google.common.base.Preconditions;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import me.brandonli.mcav.bukkit.media.mcv2.CompactRecord;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Frame;
import me.brandonli.mcav.bukkit.media.mcv2.PatternRecord;
import me.brandonli.mcav.bukkit.media.mcv2.Reconstruction;
import me.brandonli.mcav.bukkit.media.mcv2.Workers;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Bounded groups of validated leaves, compared directly with the encoder's chosen picture. */
final class FrameVerification {

  private static final int VALUES = 10;
  private static final int PREDICTED = 0;
  private static final int SOLID = 1;
  private static final int PALETTE = 2;
  private static final int INTRA = 3;
  private static final int RESIDUAL = 4;
  private static final int REDUCED_INTRA = 5;
  private static final int REDUCED_RESIDUAL = 6;
  private static final int COMPACT = 7;
  private static final int GROUP = 64;

  private final int width;
  private final int height;
  private final boolean keyframe;
  private final long referenceId;
  private final int[] leaves;
  private final byte[] records;
  private final List<Group> groups = new ArrayList<>();

  private record Group(int first, int count, int size, boolean nativeSupported) {}

  static final class Scratch {

    private final int[] prediction = new int[ROOT_SIZE * ROOT_SIZE * CHANNELS];
    private final int[] output = new int[ROOT_SIZE * ROOT_SIZE * CHANNELS];
    private final Reconstruction.Scratch reconstruction = new Reconstruction.Scratch();
  }

  FrameVerification(final Mcv2Frame frame) throws Mcv2Exception {
    this.width = frame.getWidth();
    this.height = frame.getHeight();
    this.keyframe = frame.isKeyframe();
    this.referenceId = frame.getReferenceId();
    this.leaves = new int[frame.getLeafCount() * VALUES];
    final byte[] data = frame.getData();
    final byte @Nullable [] endpoints = frame.getEndpointTable();
    final ByteArrayOutputStream records = new ByteArrayOutputStream(data.length);
    records.writeBytes(data);
    int next = 0;
    // A group contains one size so wide SIMD levels hand off small blocks once, outside the leaf loop.
    for (int size = SMALLEST_BLOCK; size <= ROOT_SIZE; size *= 2) {
      final byte @Nullable [] selectors = frame.getSelectorTable(size);
      final int first = next;
      for (int index = 0; index < frame.getLeafCount(); index++) {
        final Mcv2Frame.Leaf leaf = frame.getLeaf(index);
        if (leaf.size() != size) {
          continue;
        }
        final int at = next++ * VALUES;
        this.leaves[at] = leaf.x();
        this.leaves[at + 1] = leaf.y();
        this.leaves[at + 3] = leaf.q();
        this.leaves[at + 4] = leaf.offset();
        this.leaves[at + 5] = frame.getGlobalX();
        this.leaves[at + 6] = frame.getGlobalY();
        final int mode = leaf.mode();
        final int offset = leaf.offset();
        switch (mode) {
          case MODE_SKIP -> {
            this.leaves[at + 2] = this.keyframe ? SOLID : PREDICTED;
            this.leaves[at + 9] = frame.getDefaultColor();
          }
          case MODE_MOTION -> {
            this.leaves[at + 2] = PREDICTED;
            this.leaves[at + 5] += data[offset];
            this.leaves[at + 6] += data[offset + 1];
          }
          case MODE_IMMEDIATE_MOTION -> {
            this.leaves[at + 2] = PREDICTED;
            this.leaves[at + 5] += (byte) offset;
            this.leaves[at + 6] += (byte) (offset >> Byte.SIZE);
          }
          case MODE_SOLID -> {
            this.leaves[at + 2] = SOLID;
            this.leaves[at + 9] = ((data[offset] & 255) << 16) | ((data[offset + 1] & 255) << 8) | (data[offset + 2] & 255);
          }
          case MODE_PALETTE -> this.leaves[at + 2] = PALETTE;
          case MODE_PATTERN -> {
            this.leaves[at + 2] = PALETTE;
            this.leaves[at + 4] = records.size();
            records.writeBytes(TreeReader.fullPalette(PatternRecord.expand(data, offset, size, endpoints, selectors), size));
          }
          case MODE_COMPACT -> {
            final CompactRecord compact = CompactRecord.parse(data, offset, leaf.q());
            this.leaves[at + 2] = COMPACT;
            this.leaves[at + 4] = compact.bodyOffset();
            this.leaves[at + 5] += compact.dx();
            this.leaves[at + 6] += compact.dy();
            this.leaves[at + 7] = compact.kind();
          }
          default -> {
            final boolean residual = isResidual(mode);
            if (residual) {
              this.leaves[at + 4] += 2;
              this.leaves[at + 5] += data[offset];
              this.leaves[at + 6] += data[offset + 1];
            }
            if (mode >= MODE_INTRA_Y4C1) {
              this.leaves[at + 2] = residual ? REDUCED_RESIDUAL : REDUCED_INTRA;
              this.leaves[at + 7] = lumaGrid(mode);
              this.leaves[at + 8] = chromaGrid(mode);
            } else {
              this.leaves[at + 2] = residual ? RESIDUAL : INTRA;
              this.leaves[at + 7] = 1 << (mode - (residual ? MODE_RESIDUAL : MODE_INTRA));
            }
          }
        }
      }
      for (int start = first; start < next; start += GROUP) {
        final int count = Math.min(GROUP, next - start);
        boolean nativeSupported = true;
        for (int index = start; index < start + count; index++) {
          final int at = index * VALUES;
          final int kind = this.leaves[at + 7];
          if (this.leaves[at + 2] == COMPACT && kind >= CompactRecord.VQ64 && kind <= CompactRecord.GAIN_BIAS) {
            nativeSupported = false;
          }
        }
        this.groups.add(new Group(start, count, size, nativeSupported));
      }
    }
    this.records = records.toByteArray();
  }

  boolean matches(
    final byte[] reference,
    final long referenceId,
    final byte[] picture,
    final Workers workers,
    final Kernels.Factory factory
  ) throws Mcv2Exception {
    if (!this.keyframe && (reference.length != this.width * this.height * CHANNELS || this.referenceId != referenceId)) {
      throw new Mcv2Exception("Reference frame mismatch");
    }
    if (picture.length != this.width * this.height * CHANNELS) {
      return false;
    }
    this.check(reference, picture);
    final AtomicBoolean same = new AtomicBoolean(true);
    workers.forEach(this.groups.size(), factory::create, (kernels, group) -> {
      if (!kernels.verify(this, reference, picture, group)) {
        same.set(false);
      }
    });
    return same.get();
  }

  void check(final byte[] reference, final byte[] picture) {
    final int bytes = this.width * this.height * CHANNELS;
    Preconditions.checkArgument(picture.length == bytes, "Picture size mismatch");
    Preconditions.checkArgument(this.keyframe || reference.length == bytes, "Reference size mismatch");
  }

  int groups() {
    return this.groups.size();
  }

  int width() {
    return this.width;
  }

  int height() {
    return this.height;
  }

  int[] leaves() {
    return this.leaves;
  }

  byte[] records() {
    return this.records;
  }

  int first(final int group) {
    return this.groups.get(group).first();
  }

  int count(final int group) {
    return this.groups.get(group).count();
  }

  int size(final int group) {
    return this.groups.get(group).size();
  }

  boolean nativeSupported(final int group) {
    return this.groups.get(group).nativeSupported();
  }

  boolean javaMatches(final byte[] reference, final byte[] picture, final int group, final Scratch scratch) {
    this.check(reference, picture);
    final Group selected = this.groups.get(group);
    final int size = selected.size();
    for (int index = selected.first(); index < selected.first() + selected.count(); index++) {
      final int at = index * VALUES;
      final int left = this.leaves[at];
      final int top = this.leaves[at + 1];
      if (left >= this.width || top >= this.height) {
        continue;
      }
      final int mode = this.leaves[at + 2];
      final int quantizer = this.leaves[at + 3];
      final int offset = this.leaves[at + 4];
      final int firstGrid = this.leaves[at + 7];
      final int secondGrid = this.leaves[at + 8];
      final int[] out = scratch.output;
      if (mode == PREDICTED || mode == RESIDUAL || mode == REDUCED_RESIDUAL || mode == COMPACT) {
        Reconstruction.predict(
          reference,
          this.width,
          this.height,
          left,
          top,
          size,
          this.leaves[at + 5],
          this.leaves[at + 6],
          scratch.prediction
        );
      }
      switch (mode) {
        case PREDICTED -> Reconstruction.predicted(scratch.prediction, size, out);
        case SOLID -> Reconstruction.solid(this.leaves[at + 9], size, out);
        case PALETTE -> Reconstruction.palette(this.records, offset, size, out);
        case INTRA -> Reconstruction.intraGrid(this.records, offset, firstGrid, size, scratch.reconstruction, out);
        case RESIDUAL -> Reconstruction.residualGrid(
          scratch.prediction,
          this.records,
          offset,
          firstGrid,
          quantizer,
          size,
          scratch.reconstruction,
          out
        );
        case REDUCED_INTRA, REDUCED_RESIDUAL -> Reconstruction.reduced(
          mode == REDUCED_INTRA ? null : scratch.prediction,
          this.records,
          offset,
          firstGrid,
          secondGrid,
          quantizer,
          size,
          scratch.reconstruction,
          out
        );
        default -> Reconstruction.compact(
          scratch.prediction,
          this.records,
          offset,
          firstGrid,
          quantizer,
          size,
          scratch.reconstruction,
          out
        );
      }
      final int rows = Math.min(size, this.height - top);
      final int channels = Math.min(size, this.width - left) * CHANNELS;
      for (int row = 0; row < rows; row++) {
        final int from = row * size * CHANNELS;
        final int to = ((top + row) * this.width + left) * CHANNELS;
        for (int channel = 0; channel < channels; channel++) {
          if ((byte) out[from + channel] != picture[to + channel]) {
            return false;
          }
        }
      }
    }
    return true;
  }
}
