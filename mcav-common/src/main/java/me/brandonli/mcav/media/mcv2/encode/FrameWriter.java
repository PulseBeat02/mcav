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
package me.brandonli.mcav.media.mcv2.encode;

import static me.brandonli.mcav.media.mcv2.Mcv2Format.*;

import com.google.common.base.Preconditions;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import me.brandonli.mcav.media.mcv2.FrameParser;
import me.brandonli.mcav.media.mcv2.Mcv2Exception;
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
      Preconditions.checkArgument(size > 8, "Split below the bounded depth");
      for (int i = 0; i < 4; i++) {
        validate(node.getChild(i), size / 2);
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
    byte@Nullable[] defaultColor = null;
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
      final byte@Nullable[] derived = Derived.pack(frame, roots, options, defaultColor);
      if (derived != null) {
        return derived;
      }
    }
    return Stored.pack(frame, roots, options, defaultColor);
  }

  private static void countSolids(final TreeNode node, final Map<Key, Integer> colors) {
    if (node.isSplit()) {
      for (int i = 0; i < 4; i++) {
        countSolids(node.getChild(i), colors);
      }
    } else if (node.getMode() == MODE_SOLID) {
      colors.merge(new Key(node.record()), 1, Integer::sum);
    }
  }

  private static TreeNode rewrite(final TreeNode node, final byte@Nullable[] defaultColor) {
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

  private static TreeNode restore(final TreeNode node, final byte@Nullable[] defaultColor) {
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
    final byte@Nullable[] defaultColor,
    final byte[] index,
    final byte[] payload
  ) {
    final int payloadStart = HEADER_BYTES + index.length;
    final byte[] data = new byte[payloadStart + payload.length];
    putU32(data, 0, MAGIC);
    putU32(data, 4, CONFIGURATION | ((long) flags << 16));
    putU32(data, 8, frame.width() | ((long) frame.height() << 16));
    putU32(data, 12, frame.frameId());
    putU32(data, 16, frame.referenceId());
    putU32(data, 20, (frame.motionX() & 0xFFFFL) | ((frame.motionY() & 0xFFFFL) << 16));
    final int columns = (frame.width() + ROOT_SIZE - 1) / ROOT_SIZE;
    putU32(data, 24, (long) columns * ((frame.height() + ROOT_SIZE - 1) / ROOT_SIZE));
    putU32(data, 28, payloadStart);
    putU32(data, 32, data.length);
    if (defaultColor != null) {
      putU32(data, 36, (defaultColor[0] & 0xFF) | ((defaultColor[1] & 0xFF) << 8) | ((long) (defaultColor[2] & 0xFF) << 16));
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

    static byte@Nullable[] pack(final Frame frame, final List<TreeNode> roots, final Options options, final byte@Nullable[] defaultColor) {
      final int count = roots.size();
      final int groups = (count + 31) / 32;
      final List<TreeNode> flat = new ArrayList<>();
      final List<Integer> sizes = new ArrayList<>();
      final int[] levels = new int[3];
      List<TreeNode> level = new ArrayList<>();
      for (final TreeNode root : roots) {
        if (root.getMode() != MODE_SKIP) {
          level.add(root);
        }
      }
      for (int depth = 0; depth < 3; depth++) {
        levels[depth] = level.size();
        final List<TreeNode> children = new ArrayList<>();
        for (final TreeNode node : level) {
          flat.add(node);
          sizes.add(ROOT_SIZE >> depth);
          if (node.isSplit()) {
            for (int i = 0; i < 4; i++) {
              children.add(node.getChild(i));
            }
          }
        }
        level = children;
      }
      // more descriptors than a 16-bit count: the stored form is used instead (every level is at most the total, so
      // this also covers a level too long to count, where the reference raises rather than falling back)
      if (flat.size() > 65535) {
        return null;
      }
      int payloadBytes = 0;
      for (int i = 0; i < flat.size(); i++) {
        final TreeNode node = flat.get(i);
        if (!node.isSplit()) {
          payloadBytes += recordLength(node, sizes.get(i));
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
      final int pairEntry = coarse ? 4 : 6;
      final List<Key> endpoints = options.endpointTable() ? endpointTable(flat, pairEntry) : List.of();
      final Map<Key, Integer> endpointOf = new LinkedHashMap<>();
      for (int i = 0; i < endpoints.size(); i++) {
        endpointOf.put(endpoints.get(i), i);
      }
      if (!endpoints.isEmpty()) {
        for (final TreeNode node : flat) {
          if (node.getMode() == MODE_PATTERN) {
            payloadBytes -= 5;
          }
        }
      }
      final List<List<Key>> words = options.selectorTable() ? selectorTables(flat, sizes) : List.of();
      final List<Map<Key, Integer>> wordOf = new ArrayList<>();
      for (final List<Key> table : words) {
        final Map<Key, Integer> of = new LinkedHashMap<>();
        for (int i = 0; i < table.size(); i++) {
          of.put(table.get(i), i);
        }
        wordOf.add(of);
      }
      final boolean anyWords = !words.isEmpty();
      if (anyWords) {
        for (int i = 0; i < flat.size(); i++) {
          final int size = sizes.get(i);
          if (flat.get(i).getMode() == MODE_PATTERN && !words.get(sizeIndex(size)).isEmpty()) {
            payloadBytes -= size / 8;
          }
        }
      }
      if (payloadBytes > 65535) {
        return null;
      }
      final ByteArrayOutputStream index = new ByteArrayOutputStream();
      final byte[] masks = new byte[groups * 4];
      final byte[] checkpoints = new byte[((groups + CHECKPOINT_GROUPS - 1) / CHECKPOINT_GROUPS) * 4];
      int seen = 0;
      for (int group = 0; group < groups; group++) {
        long mask = 0;
        for (int i = group * 32; i < Math.min(count, group * 32 + 32); i++) {
          if (roots.get(i).getMode() != MODE_SKIP) {
            mask |= 1L << (i - group * 32);
          }
        }
        putU32(masks, group * 4, mask);
        if (group % CHECKPOINT_GROUPS == 0) {
          putU32(checkpoints, (group / CHECKPOINT_GROUPS) * 4, seen);
        }
        seen += Long.bitCount(mask);
      }
      final byte[] descriptors = new byte[flat.size()];
      final ByteArrayOutputStream payload = new ByteArrayOutputStream();
      final int[] pairs = new int[((flat.size() + WALK_SPAN - 1) / WALK_SPAN) * 2];
      int splits = 0;
      int cursor = 0;
      for (int i = 0; i < flat.size(); i++) {
        final TreeNode node = flat.get(i);
        final int size = sizes.get(i);
        if (i % WALK_SPAN == 0) {
          pairs[(i / WALK_SPAN) * 2] = cursor;
          pairs[(i / WALK_SPAN) * 2 + 1] = splits;
        }
        descriptors[i] = (byte) (node.getMode() | (node.getQ() << 5));
        if (node.isSplit()) {
          splits++;
          continue;
        }
        final byte[] record = node.record();
        final boolean tabledWords = anyWords && !words.get(sizeIndex(size)).isEmpty();
        if (node.getMode() == MODE_PATTERN && (!endpoints.isEmpty() || tabledWords)) {
          final byte[] stored;
          if (endpoints.isEmpty()) {
            stored = Arrays.copyOfRange(record, 0, 6);
          } else {
            stored = new byte[] { (byte) (int) endpointOf.getOrDefault(new Key(Arrays.copyOfRange(record, 0, 6)), 0) };
          }
          payload.writeBytes(stored);
          if (tabledWords) {
            final Key word = new Key(Arrays.copyOfRange(record, 6, 6 + 1 + size / 8));
            payload.write(wordOf.get(sizeIndex(size)).getOrDefault(word, 0));
          } else {
            payload.write(record, 6, record.length - 6);
          }
          cursor += patternSize(size, !endpoints.isEmpty(), tabledWords);
          continue;
        }
        payload.writeBytes(record);
        cursor += record.length;
      }
      for (int depth = 0; depth < 3; depth++) {
        index.write(levels[depth] & 0xFF);
        index.write(levels[depth] >>> 8);
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
          final int[] lookup = new int[256];
          int position = 0;
          head.write(symbols.size());
          for (final int symbol : symbols) {
            lookup[symbol] = position++;
            head.write(symbol);
          }
          plane = new byte[(descriptors.length * width + 7) / 8];
          for (int i = 0; i < descriptors.length; i++) {
            final int bit = i * width;
            final int value = lookup[descriptors[i] & 0xFF] << (bit & 7);
            plane[bit >> 3] |= (byte) value;
            if (value >> 8 != 0) {
              plane[(bit >> 3) + 1] |= (byte) (value >> 8);
            }
          }
          packed = true;
        }
      }
      head.writeBytes(plane);
      final int walkpoints = pairs.length / 2;
      final byte@Nullable[] twoLevel = options.twoLevelWalk() ? twoLevelWalk(pairs, walkpoints) : null;
      if (twoLevel != null) {
        head.writeBytes(twoLevel);
      } else {
        for (int i = 0; i < walkpoints; i++) {
          head.write(pairs[i * 2] & 0xFF);
          head.write(pairs[i * 2] >>> 8);
          head.write(pairs[i * 2 + 1] & 0xFF);
          head.write(pairs[i * 2 + 1] >>> 8);
        }
      }
      if (!endpoints.isEmpty()) {
        head.write(endpoints.size());
      }
      if (anyWords) {
        for (int s = 0; s < 3; s++) {
          head.write(words.get(s).size());
        }
      }
      if (anyWords) {
        for (int s = 0; s < 3; s++) {
          for (final Key word : words.get(s)) {
            payload.writeBytes(word.bytes());
          }
        }
      }
      for (final Key pair : endpoints) {
        final byte[] bytes = pair.bytes();
        if (coarse) {
          payload.writeBytes(pack565(bytes, 0));
          payload.writeBytes(pack565(bytes, 3));
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
      for (int s = 0; s < 3 && anyWords; s++) {
        flags |= !words.get(s).isEmpty() ? SELECTOR_TABLE_8 << s : 0;
      }
      return finish(frame, flags, defaultColor, head.toByteArray(), payload.toByteArray());
    }

    private static int sizeIndex(final int size) {
      return Integer.numberOfTrailingZeros(size) - 3;
    }

    private static boolean survives565(final byte[] record) {
      for (int i = 0; i < 6; i += 3) {
        final byte[] packed = pack565(record, i);
        final int expanded = unpack565(packed[0], packed[1]);
        final int original = ((record[i] & 0xFF) << 16) | ((record[i + 1] & 0xFF) << 8) | (record[i + 2] & 0xFF);
        if (expanded != original) {
          return false;
        }
      }
      return true;
    }

    private static byte[] pack565(final byte[] rgb, final int at) {
      final int value = (((rgb[at] & 0xFF) >> 3) << 11) | (((rgb[at + 1] & 0xFF) >> 2) << 5) | ((rgb[at + 2] & 0xFF) >> 3);
      return new byte[] { (byte) value, (byte) (value >> 8) };
    }

    /** Distinct endpoint pairs in first-use order, when naming them saves bytes. */
    private static List<Key> endpointTable(final List<TreeNode> flat, final int entry) {
      final Map<Key, Integer> counts = new LinkedHashMap<>();
      for (final TreeNode node : flat) {
        if (node.getMode() == MODE_PATTERN) {
          counts.merge(new Key(Arrays.copyOfRange(node.record(), 0, 6)), 1, Integer::sum);
        }
      }
      if (counts.isEmpty() || counts.size() > MAX_TABLE_ENTRIES) {
        return List.of();
      }
      int saving = -counts.size() * entry - 1;
      for (final int n : counts.values()) {
        saving += n * 5;
      }
      return saving > 0 ? new ArrayList<>(counts.keySet()) : List.of();
    }

    /** One table of distinct selector words per block size, when naming them saves bytes overall. */
    private static List<List<Key>> selectorTables(final List<TreeNode> flat, final List<Integer> sizes) {
      final List<Map<Key, Integer>> counts = List.of(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
      for (int i = 0; i < flat.size(); i++) {
        final TreeNode node = flat.get(i);
        if (node.getMode() == MODE_PATTERN) {
          final int size = sizes.get(i);
          counts.get(sizeIndex(size)).merge(new Key(Arrays.copyOfRange(node.record(), 6, 6 + 1 + size / 8)), 1, Integer::sum);
        }
      }
      final List<List<Key>> tables = new ArrayList<>(List.of(List.of(), List.of(), List.of()));
      int total = -3;
      for (int s = 0; s < 3; s++) {
        final Map<Key, Integer> ctr = counts.get(s);
        if (ctr.isEmpty() || ctr.size() > MAX_TABLE_ENTRIES) {
          continue;
        }
        final int entry = 1 + (8 << s) / 8;
        int saving = -ctr.size() * entry;
        for (final int n : ctr.values()) {
          saving += n * (entry - 1);
        }
        if (saving > 0) {
          tables.set(s, new ArrayList<>(ctr.keySet()));
          total += saving;
        }
      }
      return total > 0 ? tables : List.of();
    }

    /** The two-level walk region, or null when it does not fit sixteen delta bits or is not smaller. */
    private static byte@Nullable[] twoLevelWalk(final int[] pairs, final int walkpoints) {
      int cursorDelta = 0;
      int splitsDelta = 0;
      for (int i = 0; i < walkpoints; i++) {
        final int anchor = i - (i % WALK_STRIDE);
        cursorDelta = Math.max(cursorDelta, pairs[i * 2] - pairs[anchor * 2]);
        splitsDelta = Math.max(splitsDelta, pairs[i * 2 + 1] - pairs[anchor * 2 + 1]);
      }
      final int cursorBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(cursorDelta));
      final int splitsBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(splitsDelta));
      if (cursorBits + splitsBits > DELTA_BITS) {
        return null;
      }
      final ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.write(cursorBits);
      out.write(splitsBits);
      for (int i = 0; i < walkpoints; i += WALK_STRIDE) {
        writeU16(out, pairs[i * 2]);
        writeU16(out, pairs[i * 2 + 1]);
      }
      for (int i = 0; i < walkpoints; i++) {
        if (i % WALK_STRIDE == 0) {
          continue;
        }
        final int anchor = i - (i % WALK_STRIDE);
        writeU16(out, (pairs[i * 2] - pairs[anchor * 2]) | ((pairs[i * 2 + 1] - pairs[anchor * 2 + 1]) << cursorBits));
      }
      // with too few checkpoints to amortize the two width bytes the plain form is smaller or equal: keep it then
      return out.size() < walkpoints * 4 ? out.toByteArray() : null;
    }

    private static void writeU16(final ByteArrayOutputStream out, final int value) {
      out.write(value & 0xFF);
      out.write((value >>> 8) & 0xFF);
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
      this.stride = options.shortIndex() ? 3 : 4;
    }

    static byte[] pack(final Frame frame, final List<TreeNode> roots, final Options options, final byte@Nullable[] defaultColor) {
      return new Stored(options).write(frame, roots, defaultColor);
    }

    private void putWord(final int location, final long word) {
      final int at = location - HEADER_BYTES;
      if (this.stride == 3) {
        final long shortWord = (word & 0xFFFF) | ((word >>> 24) << 16);
        this.table[at] = (byte) shortWord;
        this.table[at + 1] = (byte) (shortWord >>> 8);
        this.table[at + 2] = (byte) (shortWord >>> 16);
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

    private byte[] write(final Frame frame, final List<TreeNode> roots, final byte@Nullable[] defaultColor) {
      final int count = roots.size();
      final int groups = (count + 31) / 32;
      int active = 0;
      for (final TreeNode root : roots) {
        active += root.getMode() != MODE_SKIP ? 1 : 0;
      }
      final int checkpoints = (groups + CHECKPOINT_GROUPS - 1) / CHECKPOINT_GROUPS;
      final int directory = this.options.derivedDirectory() ? groups * 4 + checkpoints * 4 : groups * 8;
      final boolean sparse = directory + active * this.stride < count * this.stride;
      this.grow(sparse ? directory + active * this.stride : count * this.stride);
      final int[] locations = new int[count];
      int cursor = HEADER_BYTES + (sparse ? directory : 0);
      for (int group = 0; group < groups; group++) {
        final int from = group * 32;
        final int to = Math.min(count, from + 32);
        if (sparse) {
          long mask = 0;
          for (int i = from; i < to; i++) {
            mask |= roots.get(i).getMode() != MODE_SKIP ? 1L << (i - from) : 0;
          }
          if (this.options.derivedDirectory()) {
            putU32(this.table, group * 4, mask);
            if (group % CHECKPOINT_GROUPS == 0) {
              putU32(this.table, groups * 4 + (group / CHECKPOINT_GROUPS) * 4, cursor);
            }
          } else {
            putU32(this.table, group * 8, mask);
            putU32(this.table, group * 8 + 4, cursor);
          }
        }
        for (int i = from; i < to; i++) {
          if (sparse) {
            locations[i] = roots.get(i).getMode() != MODE_SKIP ? cursor : -1;
            cursor += roots.get(i).getMode() != MODE_SKIP ? this.stride : 0;
          } else {
            locations[i] = HEADER_BYTES + i * this.stride;
          }
        }
      }
      for (int i = 0; i < count; i++) {
        this.emit(roots.get(i), locations[i]);
      }
      final int payloadStart = HEADER_BYTES + this.used;
      final ByteArrayOutputStream payload = new ByteArrayOutputStream();
      for (int i = 0; i < this.leafNodes.size(); i++) {
        final TreeNode node = this.leafNodes.get(i);
        final int location = this.leafLocations.get(i);
        final byte[] record = node.record();
        // a motion leaf never has a quantizer, so every one can ride in its descriptor
        if (this.options.immediateMotion() && node.getMode() == MODE_MOTION) {
          this.putWord(location, (record[0] & 0xFF) | ((record[1] & 0xFF) << 8) | ((long) MODE_IMMEDIATE_MOTION << 24));
          continue;
        }
        final long offset = node.getMode() != MODE_SKIP ? payloadStart + payload.size() : 0;
        this.putWord(location, offset | ((long) node.getMode() << 24) | ((long) node.getQ() << 29));
        payload.writeBytes(record);
      }
      if (this.options.shortIndex() && payloadStart + payload.size() > 65535) {
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
      for (int i = 0; i < 4; i++) {
        mask |= node.getChild(i).getMode() != MODE_SKIP ? 1 << i : 0;
      }
      if (this.options.sparseChildren() && this.stride == 3 && mask != 15) {
        this.grow(1 + Integer.bitCount(mask) * this.stride);
        this.table[offset - HEADER_BYTES] = (byte) mask;
        this.putWord(location, offset | ((long) MODE_SPARSE_SPLIT << 24));
        int child = offset + 1;
        for (int i = 0; i < 4; i++) {
          final TreeNode c = node.getChild(i);
          this.emit(c, c.getMode() != MODE_SKIP ? child : -1);
          child += c.getMode() != MODE_SKIP ? this.stride : 0;
        }
      } else {
        this.grow(4 * this.stride);
        this.putWord(location, offset | ((long) MODE_SPLIT << 24));
        for (int i = 0; i < 4; i++) {
          this.emit(node.getChild(i), offset + i * this.stride);
        }
      }
    }
  }
}
