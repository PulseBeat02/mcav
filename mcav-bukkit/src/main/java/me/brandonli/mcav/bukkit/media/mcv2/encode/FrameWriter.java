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

import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.ALL_QUARTERS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.BLOCK_SIZES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.CHANNELS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.CHECKPOINT_GROUPS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.CONFIGURATION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.CONFIGURATION_OFFSET;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.DEFAULT_COLOR_OFFSET;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.DEFAULT_SOLID;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.DELTA_BITS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.DERIVED_DIRECTORY;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.DERIVED_OFFSETS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.DIMENSIONS_OFFSET;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.ENDPOINT_565;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.ENDPOINT_565_PAIR_BYTES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.ENDPOINT_PAIR_BYTES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.ENDPOINT_TABLE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.FRAME_ID_OFFSET;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.GROUP_ROOTS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.HALF_WORD;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.HALF_WORD_BITS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.HEADER_BYTES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.KEYFRAME;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MAGIC;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MAX_DIMENSION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MAX_SYMBOLS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MAX_TABLE_ENTRIES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_COMPACT;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_IMMEDIATE_MOTION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_MOTION;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_PATTERN;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_SHIFT;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_SKIP;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_SOLID;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_SPARSE_SPLIT;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MODE_SPLIT;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.MOTION_OFFSET;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.PACKED_SYMBOLS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.PALETTE_COLORS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.PAYLOAD_START_OFFSET;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.QUANTIZER_SHIFT;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.QUARTERS;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.REFERENCE_ID_OFFSET;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.ROOT_COUNT_OFFSET;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.ROOT_SIZE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.SELECTOR_TABLE_8;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.SHORT_DESCRIPTOR_BYTES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.SHORT_INDEX;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.SMALLEST_BLOCK;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.SPARSE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.STORED_GROUP_BYTES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.SYMBOL_QUANTIZER_SHIFT;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.TOTAL_OFFSET;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.TWO_LEVEL_WALK;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.WALK_PAIR_BYTES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.WALK_SPAN;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.WALK_STRIDE;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.WORD_BYTES;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.isResidual;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.patternSize;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.putU32;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.recordSize;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.sizeIndex;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.symbolWidth;
import static me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format.unpack565;

import com.google.common.base.Preconditions;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import me.brandonli.mcav.bukkit.media.mcv2.FrameParser;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Exception;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Serializes a block tree into MCV2 frame bytes, byte-identically with the reference's {@code v2.pack_frame}: the
 * level-ordered derived form with packed symbols, a two-level walk plane and the endpoint and selector tables when it
 * can address the frame, and the stored short or wide index forms otherwise.
 *
 * <p>Every frame written is parsed back before it is returned, so the writer never emits a frame the decoder would
 * reject.
 */
public final class FrameWriter {

  /**
   * Which index forms and tables a frame may use. The encoder's production form enables all of them.
   *
   * @param shortIndex       three-byte stored descriptors when the frame fits a 16-bit address
   * @param sparseChildren   stored child quartets with a mask byte and only active descriptors
   * @param immediateMotion  stored motion records carried in the descriptor's offset field
   * @param derivedDirectory a root directory of masks and one checkpoint per eight groups
   * @param derivedOffsets   the level-ordered form without stored offsets
   * @param packedSymbols    descriptors as indexes into a per-frame table of (mode, quantizer) pairs
   * @param twoLevelWalk     walk checkpoints as a coarse plane and anchor-relative deltas
   * @param endpointTable    a per-frame table of pattern palette endpoints
   * @param selectorTable    per-size tables of pattern palette selector words
   * @param endpoint565      endpoint table entries as two RGB565 colours when every pair survives the round trip
   */
  public record Options(
    boolean shortIndex,
    boolean sparseChildren,
    boolean immediateMotion,
    boolean derivedDirectory,
    boolean derivedOffsets,
    boolean packedSymbols,
    boolean twoLevelWalk,
    boolean endpointTable,
    boolean selectorTable,
    boolean endpoint565
  ) {
    /** The wide form the reference's tree encoder serializes first: no options at all. */
    public static final Options WIDE = new Options(false, false, false, false, false, false, false, false, false, false);

    /**
     * The production form of the shipped profiles.
     *
     * @param endpoint565 whether endpoint table entries may be RGB565
     * @return the options
     */
    public static Options production(final boolean endpoint565) {
      return new Options(true, true, true, true, true, true, true, true, true, endpoint565);
    }
  }

  /** Bytes compared by content, the key of the colour, endpoint and selector counts. */
  static final class Key {

    private final byte[] bytes;

    Key(final byte[] bytes) {
      this.bytes = bytes;
    }

    byte[] bytes() {
      return this.bytes;
    }

    @Override
    public boolean equals(final @Nullable Object other) {
      return other instanceof final Key key && Arrays.equals(this.bytes, key.bytes);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(this.bytes);
    }
  }

  /** The frame's identity and global motion. */
  private record Frame(int width, int height, long frameId, long referenceId, boolean keyframe, int motionX, int motionY) {}

  private FrameWriter() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Serializes a tree.
   *
   * @param width       the width in pixels
   * @param height      the height in pixels
   * @param frameId     the unsigned 32-bit frame id
   * @param referenceId the reference id; the frame id for a keyframe
   * @param keyframe    whether the frame is independent
   * @param motionX     the global horizontal motion in half pixels, zero on a keyframe
   * @param motionY     the global vertical motion in half pixels, zero on a keyframe
   * @param roots       one root per 32-pixel block, in raster order
   * @param options     the forms the frame may use
   * @return the frame bytes
   * @throws IllegalArgumentException if the tree is not a valid MCV2 tree for these dimensions
   */
  public static byte[] write(
    final int width,
    final int height,
    final long frameId,
    final long referenceId,
    final boolean keyframe,
    final int motionX,
    final int motionY,
    final List<TreeNode> roots,
    final Options options
  ) {
    Preconditions.checkNotNull(roots, "Roots must not be null");
    Preconditions.checkNotNull(options, "Options must not be null");
    Preconditions.checkArgument(width >= 1 && width <= MAX_DIMENSION && height >= 1 && height <= MAX_DIMENSION, "Invalid dimensions");
    final int columns = (width + ROOT_SIZE - 1) / ROOT_SIZE;
    Preconditions.checkArgument(roots.size() == columns * ((height + ROOT_SIZE - 1) / ROOT_SIZE), "Wrong root count");
    for (final TreeNode root : roots) {
      validate(root, ROOT_SIZE);
    }
    return pack(new Frame(width, height, frameId, referenceId, keyframe, motionX, motionY), roots, options);
  }

  /**
   * Checks that a tree splits no further than 8 pixels and that every leaf has a leaf mode, a record of the length its
   * mode and size require, and a quantizer only when its mode has one, exactly as the parser will.
   */
  private static void validate(final TreeNode node, final int size) {
    if (node.isSplit()) {
      Preconditions.checkArgument(size > SMALLEST_BLOCK, "Split below the bounded depth");
      for (int quarter = 0; quarter < QUARTERS; quarter++) {
        validate(node.getChild(quarter), size / 2);
      }
      return;
    }
    final int mode = node.getMode();
    // a leaf is never a split, so every leaf mode up to the pattern palette is legal
    Preconditions.checkArgument(mode <= MODE_PATTERN, "Illegal leaf mode %s", mode);
    Preconditions.checkArgument(node.getQ() == 0 || isResidual(mode) || mode == MODE_COMPACT, "Quantizer on a mode without one");
    Preconditions.checkArgument(node.record().length == recordLength(node, size), "Record length disagrees with its mode");
  }

  private static byte[] pack(final Frame frame, final List<TreeNode> input, final Options options) {
    // the most frequent solid colour of a keyframe becomes the implicit skip colour; ties go to the colour met first
    final Map<Key, Integer> colors = new LinkedHashMap<>();
    for (final TreeNode root : input) {
      countSolids(root, colors);
    }
    byte @Nullable [] defaultColor = null;
    if (frame.keyframe() && !colors.isEmpty()) {
      int best = -1;
      for (final Map.Entry<Key, Integer> entry : colors.entrySet()) {
        if (entry.getValue() > best) {
          best = entry.getValue();
          defaultColor = entry.getKey().bytes();
        }
      }
    }
    final List<TreeNode> roots = new ArrayList<>(input.size());
    for (final TreeNode root : input) {
      roots.add(rewrite(root, defaultColor));
    }
    if (options.derivedOffsets()) {
      final byte @Nullable [] derived = Derived.pack(frame, roots, options, defaultColor);
      if (derived != null) {
        return derived;
      }
    }
    return Stored.pack(frame, roots, options, defaultColor);
  }

  private static void countSolids(final TreeNode node, final Map<Key, Integer> colors) {
    if (node.isSplit()) {
      for (int quarter = 0; quarter < QUARTERS; quarter++) {
        countSolids(node.getChild(quarter), colors);
      }
    } else if (node.getMode() == MODE_SOLID) {
      colors.merge(new Key(node.record()), 1, Integer::sum);
    }
  }

  private static TreeNode rewrite(final TreeNode node, final byte @Nullable [] defaultColor) {
    if (node.isSplit()) {
      return TreeNode.split(
        rewrite(node.getChild(0), defaultColor),
        rewrite(node.getChild(1), defaultColor),
        rewrite(node.getChild(2), defaultColor),
        rewrite(node.getChild(3), defaultColor)
      );
    }
    if (defaultColor != null && node.getMode() == MODE_SOLID && Arrays.equals(node.record(), defaultColor)) {
      return TreeNode.skip();
    }
    return node;
  }

  private static TreeNode restore(final TreeNode node, final byte @Nullable [] defaultColor) {
    if (node.isSplit()) {
      return TreeNode.split(
        restore(node.getChild(0), defaultColor),
        restore(node.getChild(1), defaultColor),
        restore(node.getChild(2), defaultColor),
        restore(node.getChild(3), defaultColor)
      );
    }
    if (defaultColor != null && node.getMode() == MODE_SKIP) {
      return TreeNode.leaf(MODE_SOLID, 0, defaultColor);
    }
    return node;
  }

  /** The length a leaf's record occupies in the wide form, where pattern records are stored whole. */
  private static int recordLength(final TreeNode node, final int size) {
    final int mode = node.getMode();
    if (mode == MODE_PATTERN) {
      return patternSize(size, false, false);
    }
    if (mode == MODE_COMPACT) {
      return node.record().length;
    }
    return recordSize(mode, size);
  }

  private static byte[] finish(
    final Frame frame,
    final int flags,
    final byte @Nullable [] defaultColor,
    final byte[] index,
    final byte[] payload
  ) {
    final int payloadStart = HEADER_BYTES + index.length;
    final byte[] data = new byte[payloadStart + payload.length];
    putU32(data, 0, MAGIC);
    putU32(data, CONFIGURATION_OFFSET, CONFIGURATION | ((long) flags << HALF_WORD_BITS));
    putU32(data, DIMENSIONS_OFFSET, frame.width() | ((long) frame.height() << HALF_WORD_BITS));
    putU32(data, FRAME_ID_OFFSET, frame.frameId());
    putU32(data, REFERENCE_ID_OFFSET, frame.referenceId());
    putU32(data, MOTION_OFFSET, (frame.motionX() & HALF_WORD) | ((long) (frame.motionY() & HALF_WORD) << HALF_WORD_BITS));
    final int columns = (frame.width() + ROOT_SIZE - 1) / ROOT_SIZE;
    putU32(data, ROOT_COUNT_OFFSET, (long) columns * ((frame.height() + ROOT_SIZE - 1) / ROOT_SIZE));
    putU32(data, PAYLOAD_START_OFFSET, payloadStart);
    putU32(data, TOTAL_OFFSET, data.length);
    if (defaultColor != null) {
      final long color = (defaultColor[0] & 0xFF) | ((defaultColor[1] & 0xFF) << 8) | ((long) (defaultColor[2] & 0xFF) << 16);
      putU32(data, DEFAULT_COLOR_OFFSET, color);
    }
    System.arraycopy(index, 0, data, HEADER_BYTES, index.length);
    System.arraycopy(payload, 0, data, payloadStart, payload.length);
    try {
      FrameParser.parse(data);
    } catch (final Mcv2Exception exception) {
      throw new IllegalArgumentException("The tree does not serialize to a valid frame: " + exception.getMessage(), exception);
    }
    return data;
  }

  /** The level-ordered derived form of {@code v2.pack_derived}. */
  private static final class Derived {

    private Derived() {}

    static byte @Nullable [] pack(
      final Frame frame,
      final List<TreeNode> roots,
      final Options options,
      final byte @Nullable [] defaultColor
    ) {
      final int count = roots.size();
      final int groups = (count + GROUP_ROOTS - 1) / GROUP_ROOTS;
      final List<TreeNode> flat = new ArrayList<>();
      final List<Integer> sizes = new ArrayList<>();
      final int[] levels = new int[BLOCK_SIZES];
      List<TreeNode> level = new ArrayList<>();
      for (final TreeNode root : roots) {
        if (root.getMode() != MODE_SKIP) {
          level.add(root);
        }
      }
      for (int depth = 0; depth < BLOCK_SIZES; depth++) {
        levels[depth] = level.size();
        final List<TreeNode> children = new ArrayList<>();
        for (final TreeNode node : level) {
          flat.add(node);
          sizes.add(ROOT_SIZE >> depth);
          if (node.isSplit()) {
            for (int quarter = 0; quarter < QUARTERS; quarter++) {
              children.add(node.getChild(quarter));
            }
          }
        }
        level = children;
      }
      // more descriptors than a 16-bit count: the stored form is used instead (every level is at most the total, so
      // this also covers a level too long to count, where the reference raises rather than falling back)
      if (flat.size() > HALF_WORD) {
        return null;
      }
      int payloadBytes = 0;
      for (int nodeIndex = 0; nodeIndex < flat.size(); nodeIndex++) {
        final TreeNode node = flat.get(nodeIndex);
        if (!node.isSplit()) {
          payloadBytes += recordLength(node, sizes.get(nodeIndex));
        }
      }
      boolean coarse = options.endpoint565();
      if (coarse) {
        for (final TreeNode node : flat) {
          if (node.getMode() == MODE_PATTERN && !survives565(node.record())) {
            coarse = false;
            break;
          }
        }
      }
      final int pairEntry = coarse ? ENDPOINT_565_PAIR_BYTES : ENDPOINT_PAIR_BYTES;
      final List<Key> endpoints = options.endpointTable() ? endpointTable(flat, pairEntry) : List.of();
      final Map<Key, Integer> endpointOf = new LinkedHashMap<>();
      for (int endpointIndex = 0; endpointIndex < endpoints.size(); endpointIndex++) {
        endpointOf.put(endpoints.get(endpointIndex), endpointIndex);
      }
      if (!endpoints.isEmpty()) {
        for (final TreeNode node : flat) {
          if (node.getMode() == MODE_PATTERN) {
            payloadBytes -= ENDPOINT_PAIR_BYTES - 1;
          }
        }
      }
      final List<List<Key>> words = options.selectorTable() ? selectorTables(flat, sizes) : List.of();
      final List<Map<Key, Integer>> wordOf = new ArrayList<>();
      for (final List<Key> table : words) {
        final Map<Key, Integer> wordIndices = new LinkedHashMap<>();
        for (int wordIndex = 0; wordIndex < table.size(); wordIndex++) {
          wordIndices.put(table.get(wordIndex), wordIndex);
        }
        wordOf.add(wordIndices);
      }
      final boolean anyWords = !words.isEmpty();
      if (anyWords) {
        for (int nodeIndex = 0; nodeIndex < flat.size(); nodeIndex++) {
          final int size = sizes.get(nodeIndex);
          if (flat.get(nodeIndex).getMode() == MODE_PATTERN && !words.get(sizeIndex(size)).isEmpty()) {
            payloadBytes -= size / Byte.SIZE;
          }
        }
      }
      if (payloadBytes > HALF_WORD) {
        return null;
      }
      final ByteArrayOutputStream index = new ByteArrayOutputStream();
      final byte[] masks = new byte[groups * WORD_BYTES];
      final byte[] checkpoints = new byte[((groups + CHECKPOINT_GROUPS - 1) / CHECKPOINT_GROUPS) * WORD_BYTES];
      int seen = 0;
      for (int group = 0; group < groups; group++) {
        long mask = 0;
        final int from = group * GROUP_ROOTS;
        for (int rootIndex = from; rootIndex < Math.min(count, from + GROUP_ROOTS); rootIndex++) {
          if (roots.get(rootIndex).getMode() != MODE_SKIP) {
            mask |= 1L << (rootIndex - from);
          }
        }
        putU32(masks, group * WORD_BYTES, mask);
        if (group % CHECKPOINT_GROUPS == 0) {
          putU32(checkpoints, (group / CHECKPOINT_GROUPS) * WORD_BYTES, seen);
        }
        seen += Long.bitCount(mask);
      }
      final byte[] descriptors = new byte[flat.size()];
      final ByteArrayOutputStream payload = new ByteArrayOutputStream();
      final int[] pairs = new int[((flat.size() + WALK_SPAN - 1) / WALK_SPAN) * 2];
      int splits = 0;
      int cursor = 0;
      for (int nodeIndex = 0; nodeIndex < flat.size(); nodeIndex++) {
        final TreeNode node = flat.get(nodeIndex);
        final int size = sizes.get(nodeIndex);
        if (nodeIndex % WALK_SPAN == 0) {
          pairs[(nodeIndex / WALK_SPAN) * 2] = cursor;
          pairs[(nodeIndex / WALK_SPAN) * 2 + 1] = splits;
        }
        descriptors[nodeIndex] = (byte) (node.getMode() | (node.getQ() << SYMBOL_QUANTIZER_SHIFT));
        if (node.isSplit()) {
          splits++;
          continue;
        }
        final byte[] record = node.record();
        final boolean tabledWords = anyWords && !words.get(sizeIndex(size)).isEmpty();
        if (node.getMode() == MODE_PATTERN && (!endpoints.isEmpty() || tabledWords)) {
          final byte[] stored;
          if (endpoints.isEmpty()) {
            stored = Arrays.copyOfRange(record, 0, ENDPOINT_PAIR_BYTES);
          } else {
            final Key pair = new Key(Arrays.copyOfRange(record, 0, ENDPOINT_PAIR_BYTES));
            stored = new byte[] { (byte) (int) endpointOf.getOrDefault(pair, 0) };
          }
          payload.writeBytes(stored);
          if (tabledWords) {
            final Key word = new Key(Arrays.copyOfRange(record, ENDPOINT_PAIR_BYTES, ENDPOINT_PAIR_BYTES + 1 + size / Byte.SIZE));
            payload.write(wordOf.get(sizeIndex(size)).getOrDefault(word, 0));
          } else {
            payload.write(record, ENDPOINT_PAIR_BYTES, record.length - ENDPOINT_PAIR_BYTES);
          }
          cursor += patternSize(size, !endpoints.isEmpty(), tabledWords);
          continue;
        }
        payload.writeBytes(record);
        cursor += record.length;
      }
      for (int depth = 0; depth < BLOCK_SIZES; depth++) {
        writeU16(index, levels[depth]);
      }
      final ByteArrayOutputStream head = new ByteArrayOutputStream();
      head.writeBytes(masks);
      head.writeBytes(checkpoints);
      head.writeBytes(index.toByteArray());
      byte[] plane = descriptors;
      boolean packed = false;
      // a frame without descriptors (every root skipped, or a uniform keyframe) gets no symbol table: the reference
      // writes an empty one there, which its own parser rejects, so its encoder fails on such frames
      if (options.packedSymbols() && descriptors.length > 0) {
        final TreeSet<Integer> symbols = new TreeSet<>();
        for (final byte descriptor : descriptors) {
          symbols.add(descriptor & 0xFF);
        }
        if (symbols.size() <= MAX_SYMBOLS) {
          final int width = symbolWidth(symbols.size());
          final int[] lookup = new int[1 << Byte.SIZE];
          int position = 0;
          head.write(symbols.size());
          for (final int symbol : symbols) {
            lookup[symbol] = position++;
            head.write(symbol);
          }
          plane = new byte[(descriptors.length * width + Byte.SIZE - 1) / Byte.SIZE];
          for (int descriptorIndex = 0; descriptorIndex < descriptors.length; descriptorIndex++) {
            final int bit = descriptorIndex * width;
            final int value = lookup[descriptors[descriptorIndex] & 0xFF] << (bit % Byte.SIZE);
            plane[bit / Byte.SIZE] |= (byte) value;
            if (value >> Byte.SIZE != 0) {
              plane[bit / Byte.SIZE + 1] |= (byte) (value >> Byte.SIZE);
            }
          }
          packed = true;
        }
      }
      head.writeBytes(plane);
      final int walkpoints = pairs.length / 2;
      final byte @Nullable [] twoLevel = options.twoLevelWalk() ? twoLevelWalk(pairs, walkpoints) : null;
      if (twoLevel != null) {
        head.writeBytes(twoLevel);
      } else {
        for (int walkpoint = 0; walkpoint < walkpoints; walkpoint++) {
          writeU16(head, pairs[walkpoint * 2]);
          writeU16(head, pairs[walkpoint * 2 + 1]);
        }
      }
      if (!endpoints.isEmpty()) {
        head.write(endpoints.size());
      }
      if (anyWords) {
        for (int sizeIndex = 0; sizeIndex < BLOCK_SIZES; sizeIndex++) {
          head.write(words.get(sizeIndex).size());
        }
      }
      if (anyWords) {
        for (int sizeIndex = 0; sizeIndex < BLOCK_SIZES; sizeIndex++) {
          for (final Key word : words.get(sizeIndex)) {
            payload.writeBytes(word.bytes());
          }
        }
      }
      for (final Key pair : endpoints) {
        final byte[] bytes = pair.bytes();
        if (coarse) {
          payload.writeBytes(pack565(bytes, 0));
          payload.writeBytes(pack565(bytes, CHANNELS));
        } else {
          payload.writeBytes(bytes);
        }
      }
      int flags = (frame.keyframe() ? KEYFRAME : 0) | SPARSE | DERIVED_DIRECTORY | DERIVED_OFFSETS;
      flags |= defaultColor != null ? DEFAULT_SOLID : 0;
      flags |= packed ? PACKED_SYMBOLS : 0;
      flags |= twoLevel != null ? TWO_LEVEL_WALK : 0;
      flags |= !endpoints.isEmpty() ? ENDPOINT_TABLE : 0;
      flags |= !endpoints.isEmpty() && coarse ? ENDPOINT_565 : 0;
      for (int sizeIndex = 0; sizeIndex < BLOCK_SIZES && anyWords; sizeIndex++) {
        flags |= !words.get(sizeIndex).isEmpty() ? SELECTOR_TABLE_8 << sizeIndex : 0;
      }
      return finish(frame, flags, defaultColor, head.toByteArray(), payload.toByteArray());
    }

    private static boolean survives565(final byte[] record) {
      for (int offset = 0; offset < PALETTE_COLORS * CHANNELS; offset += CHANNELS) {
        final byte[] packed = pack565(record, offset);
        final int expanded = unpack565(packed[0], packed[1]);
        final int original = ((record[offset] & 0xFF) << 16) | ((record[offset + 1] & 0xFF) << 8) | (record[offset + 2] & 0xFF);
        if (expanded != original) {
          return false;
        }
      }
      return true;
    }

    private static byte[] pack565(final byte[] rgb, final int at) {
      final int value = Mcv2Format.pack565(rgb[at] & 0xFF, rgb[at + 1] & 0xFF, rgb[at + 2] & 0xFF);
      return new byte[] { (byte) value, (byte) (value >> Byte.SIZE) };
    }

    /** Distinct endpoint pairs in first-use order, when naming them saves bytes. */
    private static List<Key> endpointTable(final List<TreeNode> flat, final int entry) {
      final Map<Key, Integer> counts = new LinkedHashMap<>();
      for (final TreeNode node : flat) {
        if (node.getMode() == MODE_PATTERN) {
          counts.merge(new Key(Arrays.copyOfRange(node.record(), 0, ENDPOINT_PAIR_BYTES)), 1, Integer::sum);
        }
      }
      if (counts.isEmpty() || counts.size() > MAX_TABLE_ENTRIES) {
        return List.of();
      }
      int saving = -counts.size() * entry - 1;
      for (final int uses : counts.values()) {
        saving += uses * (ENDPOINT_PAIR_BYTES - 1);
      }
      return saving > 0 ? new ArrayList<>(counts.keySet()) : List.of();
    }

    /** One table of distinct selector words per block size, when naming them saves bytes overall. */
    private static List<List<Key>> selectorTables(final List<TreeNode> flat, final List<Integer> sizes) {
      final List<Map<Key, Integer>> counts = List.of(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
      for (int nodeIndex = 0; nodeIndex < flat.size(); nodeIndex++) {
        final TreeNode node = flat.get(nodeIndex);
        if (node.getMode() == MODE_PATTERN) {
          final int size = sizes.get(nodeIndex);
          final byte[] word = Arrays.copyOfRange(node.record(), ENDPOINT_PAIR_BYTES, ENDPOINT_PAIR_BYTES + 1 + size / Byte.SIZE);
          counts.get(sizeIndex(size)).merge(new Key(word), 1, Integer::sum);
        }
      }
      final List<List<Key>> tables = new ArrayList<>(List.of(List.of(), List.of(), List.of()));
      int total = -BLOCK_SIZES;
      for (int sizeIndex = 0; sizeIndex < BLOCK_SIZES; sizeIndex++) {
        final Map<Key, Integer> sizeCounts = counts.get(sizeIndex);
        if (sizeCounts.isEmpty() || sizeCounts.size() > MAX_TABLE_ENTRIES) {
          continue;
        }
        final int entry = 1 + (SMALLEST_BLOCK << sizeIndex) / Byte.SIZE;
        int saving = -sizeCounts.size() * entry;
        for (final int uses : sizeCounts.values()) {
          saving += uses * (entry - 1);
        }
        if (saving > 0) {
          tables.set(sizeIndex, new ArrayList<>(sizeCounts.keySet()));
          total += saving;
        }
      }
      return total > 0 ? tables : List.of();
    }

    /** The two-level walk region, or null when it does not fit sixteen delta bits or is not smaller. */
    private static byte @Nullable [] twoLevelWalk(final int[] pairs, final int walkpoints) {
      int cursorDelta = 0;
      int splitsDelta = 0;
      for (int walkpoint = 0; walkpoint < walkpoints; walkpoint++) {
        final int anchor = walkpoint - (walkpoint % WALK_STRIDE);
        cursorDelta = Math.max(cursorDelta, pairs[walkpoint * 2] - pairs[anchor * 2]);
        splitsDelta = Math.max(splitsDelta, pairs[walkpoint * 2 + 1] - pairs[anchor * 2 + 1]);
      }
      final int cursorBits = Math.max(1, Integer.SIZE - Integer.numberOfLeadingZeros(cursorDelta));
      final int splitsBits = Math.max(1, Integer.SIZE - Integer.numberOfLeadingZeros(splitsDelta));
      if (cursorBits + splitsBits > DELTA_BITS) {
        return null;
      }
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.write(cursorBits);
      out.write(splitsBits);
      for (int walkpoint = 0; walkpoint < walkpoints; walkpoint += WALK_STRIDE) {
        writeU16(out, pairs[walkpoint * 2]);
        writeU16(out, pairs[walkpoint * 2 + 1]);
      }
      for (int walkpoint = 0; walkpoint < walkpoints; walkpoint++) {
        if (walkpoint % WALK_STRIDE == 0) {
          continue;
        }
        final int anchor = walkpoint - (walkpoint % WALK_STRIDE);
        writeU16(out, (pairs[walkpoint * 2] - pairs[anchor * 2]) | ((pairs[walkpoint * 2 + 1] - pairs[anchor * 2 + 1]) << cursorBits));
      }
      // with too few checkpoints to amortize the two width bytes the plain form is smaller or equal: keep it then
      return out.size() < walkpoints * WALK_PAIR_BYTES ? out.toByteArray() : null;
    }

    private static void writeU16(final ByteArrayOutputStream out, final int value) {
      out.write(value & 0xFF);
      out.write((value >>> Byte.SIZE) & 0xFF);
    }
  }

  /** The stored index forms of {@code v2.pack_frame}: depth-first child tables with explicit offsets. */
  private static final class Stored {

    private final Options options;

    private final int stride;

    private byte[] table = new byte[0];

    private int used;

    private final List<TreeNode> leafNodes = new ArrayList<>();

    private final List<Integer> leafLocations = new ArrayList<>();

    private Stored(final Options options) {
      this.options = options;
      this.stride = options.shortIndex() ? SHORT_DESCRIPTOR_BYTES : WORD_BYTES;
    }

    static byte[] pack(final Frame frame, final List<TreeNode> roots, final Options options, final byte @Nullable [] defaultColor) {
      return new Stored(options).write(frame, roots, defaultColor);
    }

    private void putWord(final int location, final long word) {
      final int at = location - HEADER_BYTES;
      if (this.stride == SHORT_DESCRIPTOR_BYTES) {
        final long shortWord = (word & HALF_WORD) | ((word >>> MODE_SHIFT) << HALF_WORD_BITS);
        this.table[at] = (byte) shortWord;
        this.table[at + 1] = (byte) (shortWord >>> Byte.SIZE);
        this.table[at + 2] = (byte) (shortWord >>> HALF_WORD_BITS);
      } else {
        putU32(this.table, at, word);
      }
    }

    /** Appends zeroed bytes to the index, growing its capacity geometrically so large frames stay linear. */
    private void grow(final int bytes) {
      if (this.used + bytes > this.table.length) {
        this.table = Arrays.copyOf(this.table, Math.max(this.used + bytes, this.table.length * 2));
      }
      this.used += bytes;
    }

    private byte[] write(final Frame frame, final List<TreeNode> roots, final byte @Nullable [] defaultColor) {
      final int count = roots.size();
      final int groups = (count + GROUP_ROOTS - 1) / GROUP_ROOTS;
      int active = 0;
      for (final TreeNode root : roots) {
        active += root.getMode() != MODE_SKIP ? 1 : 0;
      }
      final int checkpoints = (groups + CHECKPOINT_GROUPS - 1) / CHECKPOINT_GROUPS;
      final int directory = this.options.derivedDirectory() ? (groups + checkpoints) * WORD_BYTES : groups * STORED_GROUP_BYTES;
      final boolean sparse = directory + active * this.stride < count * this.stride;
      this.grow(sparse ? directory + active * this.stride : count * this.stride);
      final int[] locations = new int[count];
      int cursor = HEADER_BYTES + (sparse ? directory : 0);
      for (int group = 0; group < groups; group++) {
        final int from = group * GROUP_ROOTS;
        final int to = Math.min(count, from + GROUP_ROOTS);
        if (sparse) {
          long mask = 0;
          for (int rootIndex = from; rootIndex < to; rootIndex++) {
            mask |= roots.get(rootIndex).getMode() != MODE_SKIP ? 1L << (rootIndex - from) : 0;
          }
          if (this.options.derivedDirectory()) {
            putU32(this.table, group * WORD_BYTES, mask);
            if (group % CHECKPOINT_GROUPS == 0) {
              putU32(this.table, (groups + group / CHECKPOINT_GROUPS) * WORD_BYTES, cursor);
            }
          } else {
            putU32(this.table, group * STORED_GROUP_BYTES, mask);
            putU32(this.table, group * STORED_GROUP_BYTES + WORD_BYTES, cursor);
          }
        }
        for (int rootIndex = from; rootIndex < to; rootIndex++) {
          if (sparse) {
            locations[rootIndex] = roots.get(rootIndex).getMode() != MODE_SKIP ? cursor : -1;
            cursor += roots.get(rootIndex).getMode() != MODE_SKIP ? this.stride : 0;
          } else {
            locations[rootIndex] = HEADER_BYTES + rootIndex * this.stride;
          }
        }
      }
      for (int rootIndex = 0; rootIndex < count; rootIndex++) {
        this.emit(roots.get(rootIndex), locations[rootIndex]);
      }
      final int payloadStart = HEADER_BYTES + this.used;
      final ByteArrayOutputStream payload = new ByteArrayOutputStream();
      for (int leafIndex = 0; leafIndex < this.leafNodes.size(); leafIndex++) {
        final TreeNode node = this.leafNodes.get(leafIndex);
        final int location = this.leafLocations.get(leafIndex);
        final byte[] record = node.record();
        // a motion leaf never has a quantizer, so every one can ride in its descriptor
        if (this.options.immediateMotion() && node.getMode() == MODE_MOTION) {
          this.putWord(location, (record[0] & 0xFF) | ((record[1] & 0xFF) << Byte.SIZE) | ((long) MODE_IMMEDIATE_MOTION << MODE_SHIFT));
          continue;
        }
        final long offset = node.getMode() != MODE_SKIP ? payloadStart + payload.size() : 0;
        this.putWord(location, offset | ((long) node.getMode() << MODE_SHIFT) | ((long) node.getQ() << QUANTIZER_SHIFT));
        payload.writeBytes(record);
      }
      if (this.options.shortIndex() && payloadStart + payload.size() > HALF_WORD) {
        // no truncated addresses: the same tree again in the wide layout, with the keyframe defaults restored
        final List<TreeNode> restored = new ArrayList<>(count);
        for (final TreeNode root : roots) {
          restored.add(restore(root, defaultColor));
        }
        final Options wide = new Options(
          false,
          false,
          this.options.immediateMotion(),
          this.options.derivedDirectory(),
          false,
          false,
          false,
          false,
          false,
          false
        );
        return FrameWriter.pack(frame, restored, wide);
      }
      int flags = (frame.keyframe() ? KEYFRAME : 0) | (sparse ? SPARSE : 0);
      flags |= defaultColor != null ? DEFAULT_SOLID : 0;
      flags |= this.options.shortIndex() ? SHORT_INDEX : 0;
      flags |= sparse && this.options.derivedDirectory() ? DERIVED_DIRECTORY : 0;
      return finish(frame, flags, defaultColor, Arrays.copyOf(this.table, this.used), payload.toByteArray());
    }

    private void emit(final TreeNode node, final int location) {
      // only skipped roots and skipped sparse children have no descriptor, and they need none
      if (location < 0) {
        return;
      }
      if (!node.isSplit()) {
        this.leafNodes.add(node);
        this.leafLocations.add(location);
        return;
      }
      final int offset = HEADER_BYTES + this.used;
      int mask = 0;
      for (int quarter = 0; quarter < QUARTERS; quarter++) {
        mask |= node.getChild(quarter).getMode() != MODE_SKIP ? 1 << quarter : 0;
      }
      if (this.options.sparseChildren() && this.stride == SHORT_DESCRIPTOR_BYTES && mask != ALL_QUARTERS) {
        this.grow(1 + Integer.bitCount(mask) * this.stride);
        this.table[offset - HEADER_BYTES] = (byte) mask;
        this.putWord(location, offset | ((long) MODE_SPARSE_SPLIT << MODE_SHIFT));
        int childLocation = offset + 1;
        for (int quarter = 0; quarter < QUARTERS; quarter++) {
          final TreeNode child = node.getChild(quarter);
          this.emit(child, child.getMode() != MODE_SKIP ? childLocation : -1);
          childLocation += child.getMode() != MODE_SKIP ? this.stride : 0;
        }
      } else {
        this.grow(QUARTERS * this.stride);
        this.putWord(location, offset | ((long) MODE_SPLIT << MODE_SHIFT));
        for (int quarter = 0; quarter < QUARTERS; quarter++) {
          this.emit(node.getChild(quarter), offset + quarter * this.stride);
        }
      }
    }
  }
}
