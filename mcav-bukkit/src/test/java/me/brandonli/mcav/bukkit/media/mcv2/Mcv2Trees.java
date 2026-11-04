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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class Mcv2Trees {

  private static final Class<?> TREE = Arrays.stream(Mcv2.class.getDeclaredClasses())
    .filter(type -> type.getSimpleName().equals("TreeNode"))
    .findFirst()
    .orElseThrow();

  private static final long KEYFRAME_ID = 0L;
  private static final long PREDICTED_FRAME_ID = 1L;

  private static final int ORIENTATION_INDEX = 6;
  private static final int AXES_OFFSET = 7;

  private static final int TINY_SIZE = 4;

  private static final int TWO_PAGES_SIZE = 320;
  private static final int TWO_PAGES_SEED = 19;

  private static final int SIZE_SHIFT = 32;
  private static final int TOP_SHIFT = 16;

  private static final int CHILD_COUNT = 4;

  private Mcv2Trees() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  public static byte[] write(
    final int width,
    final int height,
    final long id,
    final long reference,
    final Mcv2FrameKind kind,
    final List<Node> roots
  ) {
    return (byte[]) Mcv2Internals.callForResult(
      Mcv2.class,
      null,
      "write",
      new Class<?>[] { int.class, int.class, Mcv2FrameKind.class, List.class, long.class, long.class },
      width,
      height,
      kind,
      roots.stream().map(Node::value).toList(),
      id,
      reference
    );
  }

  static Node solid(final int red, final int green, final int blue) {
    return Node.leaf(Mcv2Decoder.MODE_SOLID, Mcv2Decoder.NO_QUANTIZER, new byte[] { (byte) red, (byte) green, (byte) blue });
  }

  public static Node motion(final int motionX, final int motionY) {
    return Node.leaf(Mcv2Decoder.MODE_MOTION, Mcv2Decoder.NO_QUANTIZER, new byte[] { (byte) motionX, (byte) motionY });
  }

  static Node split(final Node node) {
    return Node.split(node, node, node, node);
  }

  static Node pattern(final int size, final byte[] endpoints, final int orientation, final int axisByte) {
    final byte[] record = Arrays.copyOf(endpoints, Mcv2Decoder.patternSize(size));
    record[ORIENTATION_INDEX] = (byte) orientation;
    Arrays.fill(record, AXES_OFFSET, record.length, (byte) axisByte);
    return Node.leaf(Mcv2Decoder.MODE_PATTERN, Mcv2Decoder.NO_QUANTIZER, record);
  }

  static byte[] keyframe(final int width, final int height, final Node... roots) {
    return write(width, height, KEYFRAME_ID, KEYFRAME_ID, Mcv2FrameKind.KEYFRAME, List.of(roots));
  }

  public static byte[] predicted(final int width, final int height, final Node... roots) {
    return write(width, height, PREDICTED_FRAME_ID, KEYFRAME_ID, Mcv2FrameKind.PREDICTED, List.of(roots));
  }

  static Node[] repeat(final Node node, final int count) {
    final Node[] nodes = new Node[count];
    Arrays.fill(nodes, node);
    return nodes;
  }

  public static byte[] tiny() {
    return keyframe(TINY_SIZE, TINY_SIZE, solid(1, 2, 3));
  }

  public static byte[] twoPages() {
    final int recordLength = Mcv2Decoder.recordSize(Mcv2Decoder.MODE_PALETTE, Mcv2Decoder.ROOT_SIZE);
    final byte[] record = new byte[recordLength];
    new Random(TWO_PAGES_SEED).nextBytes(record);

    final int rootsPerSide = TWO_PAGES_SIZE / Mcv2Decoder.ROOT_SIZE;
    final int rootCount = rootsPerSide * rootsPerSide;
    final Node root = Node.leaf(Mcv2Decoder.MODE_PALETTE, Mcv2Decoder.NO_QUANTIZER, record);
    return keyframe(TWO_PAGES_SIZE, TWO_PAGES_SIZE, repeat(root, rootCount));
  }

  private static long position(final int left, final int top, final int size) {
    return ((long) size << SIZE_SHIFT) | ((long) top << TOP_SHIFT) | left;
  }

  static List<Node> read(final Mcv2Decoder.Frame frame) {
    final Map<Long, Node> leaves = new HashMap<>();
    final byte[] data = frame.getData();
    for (int index = 0; index < frame.getLeafCount(); index++) {
      final Mcv2Decoder.Leaf leaf = frame.getLeaf(index);
      final int mode = leaf.mode();
      final int offset = leaf.offset();
      final int length = Mcv2Decoder.recordSize(mode, leaf.size());
      final byte[] record = length == 0 ? new byte[0] : Arrays.copyOfRange(data, offset, offset + length);
      final Node node = Node.leaf(mode, leaf.quantizer(), record);
      leaves.put(position(leaf.left(), leaf.top(), leaf.size()), node);
    }

    final List<Node> roots = new ArrayList<>();
    for (int top = 0; top < frame.getHeight(); top += Mcv2Decoder.ROOT_SIZE) {
      for (int left = 0; left < frame.getWidth(); left += Mcv2Decoder.ROOT_SIZE) {
        roots.add(read(leaves, left, top, Mcv2Decoder.ROOT_SIZE));
      }
    }

    return roots;
  }

  private static Node read(final Map<Long, Node> leaves, final int left, final int top, final int size) {
    final Node leaf = leaves.get(position(left, top, size));
    if (leaf != null) {
      return leaf;
    }

    if (size == Mcv2Decoder.SMALLEST_BLOCK) {
      final String message = "Missing terminal leaf!";
      throw new AssertionError(message);
    }

    final int half = size / 2;
    return Node.split(
      read(leaves, left, top, half),
      read(leaves, left + half, top, half),
      read(leaves, left, top + half, half),
      read(leaves, left + half, top + half, half)
    );
  }

  public record Node(Object value) {
    static Node leaf(final int mode, final int quantizer, final byte[] record) {
      return new Node(
        Mcv2Internals.callForResult(TREE, null, "leaf", new Class<?>[] { int.class, int.class, byte[].class }, mode, quantizer, record)
      );
    }

    static Node skip() {
      return new Node(Mcv2Internals.callForResult(TREE, null, "skip", new Class<?>[0]));
    }

    static Node split(final Node topLeft, final Node topRight, final Node bottomLeft, final Node bottomRight) {
      return new Node(
        Mcv2Internals.callForResult(
          TREE,
          null,
          "split",
          new Class<?>[] { TREE, TREE, TREE, TREE },
          topLeft.value,
          topRight.value,
          bottomLeft.value,
          bottomRight.value
        )
      );
    }

    private List<Node> getChildren() {
      if (!this.isSplit()) {
        return List.of();
      }

      final List<Node> children = new ArrayList<>();
      for (int child = 0; child < CHILD_COUNT; child++) {
        children.add(this.getChild(child));
      }

      return children;
    }

    int getMode() {
      return (int) Mcv2Internals.field(TREE, this.value, "mode");
    }

    int getQuantizer() {
      return (int) Mcv2Internals.field(TREE, this.value, "quantizer");
    }

    byte[] getRecord() {
      return ((byte[]) Mcv2Internals.field(TREE, this.value, "record")).clone();
    }

    boolean isSplit() {
      return (boolean) Mcv2Internals.callForResult(TREE, this.value, "isSplit", new Class<?>[0]);
    }

    Node getChild(final int index) {
      return new Node(Mcv2Internals.callForResult(TREE, this.value, "getChild", new Class<?>[] { int.class }, index));
    }

    @Override
    public boolean equals(final @Nullable Object other) {
      if (
        !(other instanceof final Node node) ||
        this.getMode() != node.getMode() ||
        this.getQuantizer() != node.getQuantizer() ||
        !Arrays.equals(this.getRecord(), node.getRecord())
      ) {
        return false;
      }

      if (!this.isSplit()) {
        return true;
      }

      for (int child = 0; child < CHILD_COUNT; child++) {
        if (!this.getChild(child).equals(node.getChild(child))) {
          return false;
        }
      }

      return true;
    }

    @Override
    public int hashCode() {
      final List<Node> children = this.getChildren();
      return Objects.hash(this.getMode(), this.getQuantizer(), Arrays.hashCode(this.getRecord()), children);
    }
  }
}
