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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Validates a complete MCV2 frame before anything may use it, exactly as the reference decoder's
 * {@code v2.parse_frame} does: every pointer, count, table, checkpoint and record is checked against the one canonical
 * layout, so a frame is accepted here if and only if the reference accepts it, with the documented exception of the
 * syntax {@link UnsupportedSyntaxException} names.
 *
 * <p>The parser reads only inside the bytes it is given, allocates at most in proportion to their length, and never
 * trusts a count or offset it has not checked. Whatever the input, it either returns a frame or throws
 * {@link Mcv2Exception}.
 */
public final class FrameParser {

  private static final int ROOT_TABLE_LIMIT = 65535;

  private FrameParser() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Parses and validates a frame. The bytes are copied, so the caller may reuse its array.
   *
   * @param bytes the frame
   * @return the validated frame
   * @throws UnsupportedSyntaxException if the bytes are an MCV1 frame or use the syntax of a reverted round
   * @throws Mcv2Exception             if the bytes are not a valid MCV2 frame
   */
  public static Mcv2Frame parse(final byte[] bytes) throws Mcv2Exception {
    Preconditions.checkNotNull(bytes, "Frame bytes must not be null");
    if (bytes.length >= 4 && u32(bytes, 0) == MCV1_MAGIC) {
      throw new UnsupportedSyntaxException("MCV1 frames are not supported");
    }
    if (bytes.length < 4 || u32(bytes, 0) != MAGIC) {
      throw new Mcv2Exception("Not an MCV2 frame");
    }
    if (bytes.length < HEADER_BYTES || bytes.length > MAX_FRAME_BYTES) {
      throw new Mcv2Exception("Invalid frame length");
    }
    final byte[] data = bytes.clone();
    final long config = u32(data, 4);
    final long dimensions = u32(data, 8);
    final long frameId = u32(data, 12);
    final long referenceId = u32(data, 16);
    final long motion = u32(data, 20);
    final long count = u32(data, 24);
    final long start = u32(data, 28);
    final long total = u32(data, 32);
    final long color = u32(data, 36);
    final int width = (int) (dimensions & 0xFFFF);
    final int height = (int) (dimensions >>> 16);
    final int flags = (int) (config >>> 16);
    if ((config & 0xFFFF) != CONFIGURATION || (flags & ~ALL_FLAGS) != 0 || u32(data, 40) != 0 || u32(data, 44) != 0) {
      throw new Mcv2Exception("Unsupported MCV2 header");
    }
    if (width < 1 || width > MAX_DIMENSION || height < 1 || height > MAX_DIMENSION || total != data.length) {
      throw new Mcv2Exception("Invalid dimensions or total");
    }
    final int stride = (flags & SHORT_INDEX) != 0 ? 3 : 4;
    if (stride == 3 && total > ROOT_TABLE_LIMIT) {
      throw new Mcv2Exception("Short index frame exceeds the address range");
    }
    final int columns = (width + ROOT_SIZE - 1) / ROOT_SIZE;
    final int roots = columns * ((height + ROOT_SIZE - 1) / ROOT_SIZE);
    if (count != roots || start < HEADER_BYTES || start > total) {
      throw new Mcv2Exception("Invalid root table");
    }
    final boolean keyframe = (flags & KEYFRAME) != 0;
    if ((keyframe && (frameId != referenceId || motion != 0)) || (!keyframe && frameId == referenceId)) {
      throw new Mcv2Exception("Invalid reference metadata");
    }
    final boolean defaultSolid = (flags & DEFAULT_SOLID) != 0;
    if (color > 0xFFFFFF || (defaultSolid && !keyframe) || (!defaultSolid && color != 0)) {
      throw new Mcv2Exception("Invalid default color");
    }
    if ((flags & TWO_LEVEL_WALK) != 0 && (flags & DERIVED_OFFSETS) == 0) {
      throw new Mcv2Exception("Two-level walk requires derived offsets");
    }
    if ((flags & PACKED_SYMBOLS) != 0 && (flags & DERIVED_OFFSETS) == 0) {
      throw new Mcv2Exception("Packed symbols require derived offsets");
    }
    if ((flags & MOTION_TABLE) != 0) {
      throw new UnsupportedSyntaxException("The motion table of round 15 is not supported");
    }
    final int packedColor = (int) color;
    final int rgb = ((packedColor & 0xFF) << 16) | (((packedColor >> 8) & 0xFF) << 8) | ((packedColor >> 16) & 0xFF);
    final Mcv2Frame.Header header = new Mcv2Frame.Header(
      width,
      height,
      frameId,
      referenceId,
      flags,
      signed((int) (motion & 0xFFFF), 16),
      signed((int) (motion >>> 16), 16),
      (int) start,
      rgb
    );
    if ((flags & DERIVED_OFFSETS) != 0) {
      if ((flags & SPARSE) == 0 || (flags & DERIVED_DIRECTORY) == 0 || (flags & SHORT_INDEX) != 0) {
        throw new Mcv2Exception("Derived offsets require the level-ordered sparse form");
      }
      return new DerivedParser(data, header, roots, columns).parse();
    }
    return new StoredParser(data, header, roots, columns, stride).parse();
  }

  /** A growable list of leaves, six integers each. */
  static final class Leaves {

    private int[] values = new int[64 * Mcv2Frame.LEAF_INTS];
    private int size;

    void add(final int x, final int y, final int size, final int mode, final int q, final int offset) {
      if (this.size + Mcv2Frame.LEAF_INTS > this.values.length) {
        this.values = Arrays.copyOf(this.values, this.values.length * 2);
      }
      final int[] v = this.values;
      final int at = this.size;
      v[at] = x;
      v[at + 1] = y;
      v[at + 2] = size;
      v[at + 3] = mode;
      v[at + 4] = q;
      v[at + 5] = offset;
      this.size += Mcv2Frame.LEAF_INTS;
    }

    int count() {
      return this.size / Mcv2Frame.LEAF_INTS;
    }

    int get(final int leaf, final int field) {
      return this.values[leaf * Mcv2Frame.LEAF_INTS + field];
    }

    int[] toArray() {
      return Arrays.copyOf(this.values, this.size);
    }
  }

  private static void checkUnsupportedLeaf(final int mode) throws UnsupportedSyntaxException {
    if (mode == MODE_COARSE_PALETTE_2 || mode == MODE_COARSE_PALETTE_4 || mode == MODE_INDEXED_MOTION) {
      throw new UnsupportedSyntaxException("Leaf mode " + mode + " of a reverted round is not supported");
    }
  }

  /** The stored index forms: wide or short descriptors with explicit offsets, depth-first child tables. */
  private static final class StoredParser {

    private final byte[] data;
    private final Mcv2Frame.Header header;
    private final int roots;
    private final int columns;
    private final int stride;
    private final int start;
    private final int flags;
    private final Leaves leaves = new Leaves();
    private int cursor;

    StoredParser(final byte[] data, final Mcv2Frame.Header header, final int roots, final int columns, final int stride) {
      this.data = data;
      this.header = header;
      this.roots = roots;
      this.columns = columns;
      this.stride = stride;
      this.start = header.payloadStart();
      this.flags = header.flags();
    }

    private int word(final int offset) {
      if (this.stride == 3) {
        return u16(this.data, offset) | ((this.data[offset + 2] & 0xFF) << 24);
      }
      return (int) u32(this.data, offset);
    }

    Mcv2Frame parse() throws Mcv2Exception {
      final int[] words = new int[this.roots];
      if ((this.flags & DERIVED_DIRECTORY) != 0 && (this.flags & SPARSE) == 0) {
        throw new Mcv2Exception("Derived directory requires the sparse root form");
      }
      if ((this.flags & SPARSE) != 0) {
        this.readSparseRoots(words);
      } else {
        this.cursor = HEADER_BYTES + this.roots * this.stride;
        if (this.cursor > this.start) {
          throw new Mcv2Exception("Short root index");
        }
        for (int i = 0; i < this.roots; i++) {
          words[i] = this.word(HEADER_BYTES + i * this.stride);
        }
      }
      for (int i = 0; i < this.roots; i++) {
        this.walk(words[i], (i % this.columns) * ROOT_SIZE, (i / this.columns) * ROOT_SIZE, ROOT_SIZE);
      }
      if (this.cursor != this.start) {
        throw new Mcv2Exception("Noncanonical node table");
      }
      this.checkPayload();
      return new Mcv2Frame(this.data, this.header, this.leaves.toArray(), null, null);
    }

    private void readSparseRoots(final int[] words) throws Mcv2Exception {
      final int groups = (this.roots + 31) / 32;
      final boolean derived = (this.flags & DERIVED_DIRECTORY) != 0;
      final int checkpoints = (groups + CHECKPOINT_GROUPS - 1) / CHECKPOINT_GROUPS;
      this.cursor = HEADER_BYTES + (derived ? groups * 4 + checkpoints * 4 : groups * 8);
      if (this.cursor > this.start) {
        throw new Mcv2Exception("Short root directory");
      }
      int root = 0;
      for (int group = 0; group < groups; group++) {
        final long mask;
        long pointer;
        if (derived) {
          mask = u32(this.data, HEADER_BYTES + group * 4);
          pointer = this.cursor;
          if (group % CHECKPOINT_GROUPS == 0) {
            pointer = u32(this.data, HEADER_BYTES + groups * 4 + (group / CHECKPOINT_GROUPS) * 4);
          }
        } else {
          mask = u32(this.data, HEADER_BYTES + group * 8);
          pointer = u32(this.data, HEADER_BYTES + group * 8 + 4);
        }
        final int valid = Math.min(32, this.roots - group * 32);
        if (pointer != this.cursor || mask >>> valid != 0 || this.cursor + (long) Long.bitCount(mask) * this.stride > this.start) {
          throw new Mcv2Exception("Bad root directory");
        }
        for (int bit = 0; bit < valid; bit++) {
          int value = 0;
          if (((mask >>> bit) & 1) != 0) {
            value = this.word(this.cursor);
            this.cursor += this.stride;
            if (value == 0) {
              throw new Mcv2Exception("Explicit sparse skip");
            }
          }
          words[root++] = value;
        }
      }
    }

    private void walk(final int word, final int x, final int y, final int size) throws Mcv2Exception {
      final int mode = (word >>> 24) & 31;
      final int q = word >>> 29;
      final int offset = word & 0xFFFFFF;
      if (mode == MODE_SPLIT || mode == MODE_SPARSE_SPLIT) {
        if (size == 8 || q != 0 || offset != this.cursor) {
          throw new Mcv2Exception("Invalid split address or depth");
        }
        final int[] children = new int[4];
        if (mode == MODE_SPARSE_SPLIT) {
          if (this.stride != 3 || this.cursor >= this.start || (this.data[this.cursor] & 0xFF) >= 15) {
            throw new Mcv2Exception("Invalid sparse child mask or layout");
          }
          final int mask = this.data[this.cursor] & 0xFF;
          this.cursor += 1;
          if (this.cursor + Integer.bitCount(mask) * this.stride > this.start) {
            throw new Mcv2Exception("Truncated sparse children");
          }
          for (int i = 0; i < 4; i++) {
            if (((mask >> i) & 1) != 0) {
              children[i] = this.word(this.cursor);
              if (children[i] == 0) {
                throw new Mcv2Exception("Explicit sparse child skip");
              }
              this.cursor += this.stride;
            }
          }
        } else {
          if (this.cursor + 4 * this.stride > this.start) {
            throw new Mcv2Exception("Truncated children");
          }
          for (int i = 0; i < 4; i++) {
            children[i] = this.word(this.cursor + i * this.stride);
          }
          this.cursor += 4 * this.stride;
        }
        final int half = size / 2;
        for (int i = 0; i < 4; i++) {
          this.walk(children[i], x + (i % 2) * half, y + (i / 2) * half, half);
        }
        return;
      }
      checkUnsupportedLeaf(mode);
      final boolean leafMode = mode <= MODE_PATTERN || mode == MODE_IMMEDIATE_MOTION;
      if (!leafMode || (q != 0 && !isResidual(mode) && mode != MODE_COMPACT) || (mode == MODE_SKIP && word != 0)) {
        throw new Mcv2Exception("Invalid leaf descriptor");
      }
      if (mode == MODE_IMMEDIATE_MOTION && offset >> 16 != 0) {
        throw new Mcv2Exception("Invalid leaf descriptor");
      }
      if ((this.flags & KEYFRAME) != 0 && isTemporalInKeyframe(mode, this.flags)) {
        throw new Mcv2Exception("Temporal keyframe leaf");
      }
      this.leaves.add(x, y, size, mode, q, offset);
    }

    private void checkPayload() throws Mcv2Exception {
      final int count = this.leaves.count();
      int immediates = 0;
      for (int i = 0; i < count; i++) {
        if (this.leaves.get(i, 3) == MODE_IMMEDIATE_MOTION) {
          immediates++;
        }
      }
      // an immediate record is materialized beside the frame when decoding, so it is charged the address space a
      // stored record would have occupied
      if ((long) this.data.length + 2L * immediates > MAX_FRAME_BYTES) {
        throw new Mcv2Exception("Immediate records exceed the frame address range");
      }
      for (int i = 0; i < count; i++) {
        final int mode = this.leaves.get(i, 3);
        if (mode == MODE_IMMEDIATE_MOTION) {
          continue;
        }
        final int size = this.leaves.get(i, 2);
        final int q = this.leaves.get(i, 4);
        final int offset = this.leaves.get(i, 5);
        final int length;
        if (mode == MODE_PATTERN) {
          length = patternSize(size, false, false);
          PatternRecord.expand(this.data, offset, size, null, null);
        } else if (mode == MODE_COMPACT) {
          length = CompactRecord.parse(this.data, offset, q).length();
        } else {
          length = recordSize(mode, size);
        }
        if (mode != MODE_SKIP && (offset != this.cursor || length > this.data.length - offset)) {
          throw new Mcv2Exception("Invalid leaf payload");
        }
        this.cursor += length;
      }
      if (this.cursor != this.data.length) {
        throw new Mcv2Exception("Noncanonical payload length");
      }
    }
  }

  private static boolean isTemporalInKeyframe(final int mode, final int flags) {
    return (
      mode == MODE_MOTION ||
      mode == MODE_COMPACT ||
      mode == MODE_IMMEDIATE_MOTION ||
      isResidual(mode) ||
      (mode == MODE_SKIP && (flags & DEFAULT_SOLID) == 0)
    );
  }

  /** The derived form: level-ordered descriptors with no offsets, every address recovered by bounded walks. */
  private static final class DerivedParser {

    private final byte[] data;
    private final Mcv2Frame.Header header;
    private final int roots;
    private final int columns;
    private final int start;
    private final int total;
    private final int flags;
    private int base;
    private byte@Nullable[] symbols;
    private int width;

    DerivedParser(final byte[] data, final Mcv2Frame.Header header, final int roots, final int columns) {
      this.data = data;
      this.header = header;
      this.roots = roots;
      this.columns = columns;
      this.start = header.payloadStart();
      this.total = data.length;
      this.flags = header.flags();
      this.width = 8;
    }

    Mcv2Frame parse() throws Mcv2Exception {
      final int groups = (this.roots + 31) / 32;
      final int stored = (groups + CHECKPOINT_GROUPS - 1) / CHECKPOINT_GROUPS;
      final int head = HEADER_BYTES + groups * 4 + stored * 4;
      if (head + 6 > this.start) {
        throw new Mcv2Exception("Short derived index");
      }
      final int n0 = u16(this.data, head);
      final int n1 = u16(this.data, head + 2);
      final int n2 = u16(this.data, head + 4);
      this.base = head + 6;
      final int descriptors = n0 + n1 + n2;
      final int walkpoints = (descriptors + WALK_SPAN - 1) / WALK_SPAN;
      if ((this.flags & PACKED_SYMBOLS) != 0) {
        this.readSymbolTable();
      }
      final int plane = (descriptors * this.width + 7) / 8;
      final int walkBase = this.base + plane;
      int cursorBits = 0;
      int splitsBits = 0;
      final boolean twoLevel = (this.flags & TWO_LEVEL_WALK) != 0;
      if (twoLevel) {
        if (walkBase + 2 > this.start) {
          throw new Mcv2Exception("Short walk widths");
        }
        cursorBits = this.data[walkBase] & 0xFF;
        splitsBits = this.data[walkBase + 1] & 0xFF;
        if (cursorBits < 1 || cursorBits > 16 || splitsBits < 1 || splitsBits > 16 || cursorBits + splitsBits > DELTA_BITS) {
          throw new Mcv2Exception("Invalid walk delta widths");
        }
      }
      int region = walkBytes(walkpoints, twoLevel);
      final boolean endpointTable = (this.flags & ENDPOINT_TABLE) != 0;
      int pairCount = 0;
      if (endpointTable) {
        final int at = walkBase + region;
        if (at >= this.start) {
          throw new Mcv2Exception("Short endpoint table");
        }
        pairCount = this.data[at] & 0xFF;
        if (pairCount < 1) {
          throw new Mcv2Exception("Invalid endpoint table size");
        }
      }
      final int[] wordCounts = new int[3];
      final boolean selectorTable = (this.flags & SELECTOR_TABLE) != 0;
      if (selectorTable) {
        final int at = walkBase + region + (endpointTable ? 1 : 0);
        if (at + 3 > this.start) {
          throw new Mcv2Exception("Short selector table sizes");
        }
        for (int i = 0; i < 3; i++) {
          wordCounts[i] = this.data[at + i] & 0xFF;
          final boolean flagged = (this.flags & (SELECTOR_TABLE_8 << i)) != 0;
          if ((wordCounts[i] != 0) != flagged) {
            throw new Mcv2Exception("Selector table size contradicts its flag");
          }
        }
      }
      final boolean coarseEndpoints = (this.flags & ENDPOINT_565) != 0;
      final int pairEntry = coarseEndpoints ? 4 : 6;
      if (endpointTable) {
        region += 1;
      }
      if (selectorTable) {
        region += 3;
      }
      if (coarseEndpoints && !endpointTable) {
        throw new Mcv2Exception("565 endpoints without an endpoint table");
      }
      if (walkBase + region != this.start) {
        throw new Mcv2Exception("Noncanonical derived index length");
      }
      final int wordBytes = wordCounts[0] * 2 + wordCounts[1] * 3 + wordCounts[2] * 5;
      final int endpointBytes = pairCount * pairEntry;
      final byte[] book = endpointTable ? this.readEndpointTable(pairCount, pairEntry, wordBytes) : null;
      final byte[][] books = selectorTable ? this.readSelectorTables(wordCounts, endpointBytes, wordBytes) : null;
      final int[] present = this.readDirectory(groups, n0);
      final byte[] modes = new byte[descriptors];
      final byte[] quantizers = new byte[descriptors];
      for (int i = 0; i < descriptors; i++) {
        final int symbol = this.descriptor(i);
        modes[i] = (byte) (symbol & 31);
        quantizers[i] = (byte) (symbol >> 5);
      }
      if (4 * countSplits(modes, 0, n0) != n1 || 4 * countSplits(modes, n0, n0 + n1) != n2) {
        throw new Mcv2Exception("Level counts disagree with the split descriptors");
      }
      if (countSplits(modes, n0 + n1, descriptors) != 0) {
        throw new Mcv2Exception("Split below the bounded depth");
      }
      final int[] positions = new int[descriptors * 3];
      for (int index = 0; index < n0; index++) {
        final int root = present[index];
        positions[index * 3] = (root % this.columns) * ROOT_SIZE;
        positions[index * 3 + 1] = (root / this.columns) * ROOT_SIZE;
        positions[index * 3 + 2] = ROOT_SIZE;
      }
      int child = n0;
      for (int index = 0; index < n0 + n1; index++) {
        if (modes[index] != MODE_SPLIT) {
          continue;
        }
        final int x = positions[index * 3];
        final int y = positions[index * 3 + 1];
        final int half = positions[index * 3 + 2] / 2;
        for (int corner = 0; corner < 4; corner++) {
          positions[child * 3] = x + (corner % 2) * half;
          positions[child * 3 + 1] = y + (corner / 2) * half;
          positions[child * 3 + 2] = half;
          child++;
        }
      }
      final Leaves leaves = new Leaves();
      final int[] pairs = new int[walkpoints * 2];
      int splits = 0;
      int cursor = 0;
      for (int index = 0; index < descriptors; index++) {
        final int mode = modes[index];
        final int q = quantizers[index];
        if (index % WALK_SPAN == 0) {
          final int point = index / WALK_SPAN;
          final long entry = this.walkEntry(walkBase, walkpoints, twoLevel, cursorBits, splitsBits, point);
          if (entry >>> 32 != cursor || (entry & 0xFFFFFFFFL) != splits) {
            throw new Mcv2Exception("Noncanonical walk checkpoint");
          }
          pairs[point * 2] = cursor;
          pairs[point * 2 + 1] = splits;
        }
        if (mode == MODE_SPLIT) {
          splits++;
          continue;
        }
        final int size = positions[index * 3 + 2];
        final byte[] words = books == null ? null : books[Integer.numberOfTrailingZeros(size) - 3];
        final int offset = this.start + cursor;
        final int length = this.validateLeaf(mode, q, size, offset, book, words);
        leaves.add(positions[index * 3], positions[index * 3 + 1], size, mode, q, mode == MODE_SKIP ? 0 : offset);
        cursor += length;
        if (this.start + (long) cursor > this.total) {
          throw new Mcv2Exception("Invalid leaf payload");
        }
      }
      if (this.start + cursor != this.total - wordBytes - endpointBytes) {
        throw new Mcv2Exception("Noncanonical payload length");
      }
      if (twoLevel && !minimalWidths(pairs, walkpoints, cursorBits, splitsBits)) {
        throw new Mcv2Exception("Walk delta widths are not minimal");
      }
      final boolean[] listed = new boolean[this.roots];
      for (int i = 0; i < n0; i++) {
        listed[present[i]] = true;
      }
      for (int root = 0; root < this.roots; root++) {
        if (!listed[root]) {
          if ((this.flags & KEYFRAME) != 0 && (this.flags & DEFAULT_SOLID) == 0) {
            throw new Mcv2Exception("Temporal keyframe leaf");
          }
          leaves.add((root % this.columns) * ROOT_SIZE, (root / this.columns) * ROOT_SIZE, ROOT_SIZE, MODE_SKIP, 0, 0);
        }
      }
      return new Mcv2Frame(this.data, this.header, leaves.toArray(), book, books);
    }

    private void readSymbolTable() throws Mcv2Exception {
      if (this.base >= this.start) {
        throw new Mcv2Exception("Short symbol table");
      }
      final int size = this.data[this.base] & 0xFF;
      if (size < 1 || size > MAX_SYMBOLS || this.base + 1 + size > this.total) {
        throw new Mcv2Exception("Invalid symbol table size");
      }
      final byte[] table = Arrays.copyOfRange(this.data, this.base + 1, this.base + 1 + size);
      for (int i = 1; i < size; i++) {
        if ((table[i] & 0xFF) <= (table[i - 1] & 0xFF)) {
          throw new Mcv2Exception("Noncanonical symbol table");
        }
      }
      this.symbols = table;
      this.width = symbolWidth(size);
      this.base += 1 + size;
    }

    private int descriptor(final int index) throws Mcv2Exception {
      final byte[] table = this.symbols;
      if (table == null) {
        return this.data[this.base + index] & 0xFF;
      }
      final int position = index * this.width;
      final int at = this.base + (position >> 3);
      // two bytes always cover a symbol; the second can lie past the plane, in the walk region that always follows it
      final int word = (this.data[at] & 0xFF) | ((this.data[at + 1] & 0xFF) << 8);
      final int symbol = (word >> (position & 7)) & ((1 << this.width) - 1);
      if (symbol >= table.length) {
        throw new Mcv2Exception("Symbol index outside the table");
      }
      return table[symbol] & 0xFF;
    }

    private static int countSplits(final byte[] modes, final int from, final int to) {
      int splits = 0;
      for (int i = from; i < to; i++) {
        if (modes[i] == MODE_SPLIT) {
          splits++;
        }
      }
      return splits;
    }

    private byte[] readEndpointTable(final int pairCount, final int pairEntry, final int wordBytes) throws Mcv2Exception {
      final int endpointBytes = pairCount * pairEntry;
      final int pairsAt = this.total - endpointBytes;
      if (pairsAt - wordBytes < this.start) {
        throw new Mcv2Exception("Truncated endpoint table");
      }
      final byte[] book = new byte[pairCount * 6];
      for (int i = 0; i < pairCount; i++) {
        if (pairEntry == 4) {
          final int a = unpack565(this.data[pairsAt + i * 4], this.data[pairsAt + i * 4 + 1]);
          final int b = unpack565(this.data[pairsAt + i * 4 + 2], this.data[pairsAt + i * 4 + 3]);
          putRgb(book, i * 6, a);
          putRgb(book, i * 6 + 3, b);
        } else {
          System.arraycopy(this.data, pairsAt + i * 6, book, i * 6, 6);
        }
      }
      if (distinctEntries(book, 6) != pairCount) {
        throw new Mcv2Exception("Noncanonical endpoint table");
      }
      return book;
    }

    private static void putRgb(final byte[] target, final int at, final int rgb) {
      target[at] = (byte) (rgb >> 16);
      target[at + 1] = (byte) (rgb >> 8);
      target[at + 2] = (byte) rgb;
    }

    private static int distinctEntries(final byte[] table, final int entry) {
      final Set<String> seen = new HashSet<>();
      for (int i = 0; i + entry <= table.length; i += entry) {
        seen.add(Arrays.toString(Arrays.copyOfRange(table, i, i + entry)));
      }
      return seen.size();
    }

    private byte[][] readSelectorTables(final int[] wordCounts, final int endpointBytes, final int wordBytes) throws Mcv2Exception {
      int at = this.total - endpointBytes - wordBytes;
      if (at < this.start) {
        throw new Mcv2Exception("Truncated selector table");
      }
      final byte[][] books = new byte[3][];
      for (int i = 0; i < 3; i++) {
        final int entry = 1 + (8 << i) / 8;
        final int n = wordCounts[i];
        if (n != 0) {
          final byte[] chunk = Arrays.copyOfRange(this.data, at, at + n * entry);
          if (distinctEntries(chunk, entry) != n) {
            throw new Mcv2Exception("Noncanonical selector table");
          }
          books[i] = chunk;
        }
        at += n * entry;
      }
      return books;
    }

    private int[] readDirectory(final int groups, final int n0) throws Mcv2Exception {
      final int[] present = new int[this.roots];
      int seen = 0;
      for (int group = 0; group < groups; group++) {
        final long mask = u32(this.data, HEADER_BYTES + group * 4);
        final int valid = Math.min(32, this.roots - group * 32);
        if (mask >>> valid != 0) {
          throw new Mcv2Exception("Bad root directory");
        }
        if (group % CHECKPOINT_GROUPS == 0) {
          final long checkpoint = u32(this.data, HEADER_BYTES + groups * 4 + (group / CHECKPOINT_GROUPS) * 4);
          if (checkpoint != seen) {
            throw new Mcv2Exception("Noncanonical descriptor checkpoint");
          }
        }
        for (int bit = 0; bit < valid; bit++) {
          if (((mask >>> bit) & 1) != 0) {
            present[seen++] = group * 32 + bit;
          }
        }
      }
      if (seen != n0) {
        throw new Mcv2Exception("Root count differs from the level-0 count");
      }
      return present;
    }

    /** The (payload cursor, split prefix) pair of a walk checkpoint, as the high and low halves of a long. */
    private long walkEntry(
      final int walkBase,
      final int walkpoints,
      final boolean twoLevel,
      final int cursorBits,
      final int splitsBits,
      final int index
    ) throws Mcv2Exception {
      if (!twoLevel) {
        return ((long) u16(this.data, walkBase + index * 4) << 32) | u16(this.data, walkBase + index * 4 + 2);
      }
      final int anchor = walkBase + 2 + (index / WALK_STRIDE) * 4;
      final int baseCursor = u16(this.data, anchor);
      final int baseSplits = u16(this.data, anchor + 2);
      if (index % WALK_STRIDE == 0) {
        return ((long) baseCursor << 32) | baseSplits;
      }
      final int coarse = (walkpoints + WALK_STRIDE - 1) / WALK_STRIDE;
      final int at = walkBase + 2 + coarse * 4 + (index - 1 - (index - 1) / WALK_STRIDE) * 2;
      final int entry = u16(this.data, at);
      if (entry >>> (cursorBits + splitsBits) != 0) {
        throw new Mcv2Exception("Noncanonical walk delta padding");
      }
      final long cursor = (long) baseCursor + (entry & ((1 << cursorBits) - 1));
      final long splits = (long) baseSplits + (entry >>> cursorBits);
      return (cursor << 32) | splits;
    }

    private static boolean minimalWidths(final int[] pairs, final int walkpoints, final int cursorBits, final int splitsBits) {
      int cursor = 0;
      int splits = 0;
      for (int index = 0; index < walkpoints; index++) {
        final int anchor = index - (index % WALK_STRIDE);
        cursor = Math.max(cursor, pairs[index * 2] - pairs[anchor * 2]);
        splits = Math.max(splits, pairs[index * 2 + 1] - pairs[anchor * 2 + 1]);
      }
      return bitLength(cursor) == cursorBits && bitLength(splits) == splitsBits;
    }

    private static int bitLength(final int value) {
      return Math.max(1, 32 - Integer.numberOfLeadingZeros(value));
    }

    /** Validates one leaf of the derived form and returns the length of its record. */
    private int validateLeaf(
      final int mode,
      final int q,
      final int size,
      final int offset,
      final byte@Nullable[] book,
      final byte@Nullable[] words
    ) throws Mcv2Exception {
      checkUnsupportedLeaf(mode);
      // splits never get here: the walk counts them before it validates a leaf
      if (mode > MODE_PATTERN || (q != 0 && !isResidual(mode) && mode != MODE_COMPACT)) {
        throw new Mcv2Exception("Invalid leaf descriptor");
      }
      if ((this.flags & KEYFRAME) != 0 && isTemporalInKeyframe(mode, this.flags)) {
        throw new Mcv2Exception("Temporal keyframe leaf");
      }
      if (mode == MODE_PATTERN) {
        PatternRecord.expand(this.data, offset, size, book, words);
        return patternSize(size, book != null, words != null);
      }
      if (mode == MODE_COMPACT) {
        return CompactRecord.parse(this.data, offset, q).length();
      }
      return recordSize(mode, size);
    }
  }
}
