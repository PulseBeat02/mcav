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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Exercises the encoder's private tree writer without exposing it as production API. */
public final class Mcv2Trees {

  private static final Class<?> TREE = Arrays.stream(MCV2.class.getDeclaredClasses())
    .filter(type -> type.getSimpleName().equals("TreeNode"))
    .findFirst()
    .orElseThrow();

  private Mcv2Trees() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  public record Node(Object value) {
    static Node leaf(final int mode, final int quantizer, final byte[] record) {
      return new Node(invoke(TREE, null, "leaf", new Class<?>[] { int.class, int.class, byte[].class }, mode, quantizer, record));
    }

    static Node skip() {
      return new Node(invoke(TREE, null, "skip", new Class<?>[0]));
    }

    static Node split(final Node topLeft, final Node topRight, final Node bottomLeft, final Node bottomRight) {
      return new Node(
        invoke(
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
      if (this.isSplit()) {
        for (int child = 0; child < 4; child++) {
          if (!this.getChild(child).equals(node.getChild(child))) {
            return false;
          }
        }
      }
      return true;
    }

    @Override
    public int hashCode() {
      int hash = Objects.hash(this.getMode(), this.getQuantizer(), Arrays.hashCode(this.getRecord()));
      if (this.isSplit()) {
        for (int child = 0; child < 4; child++) {
          hash = 31 * hash + this.getChild(child).hashCode();
        }
      }
      return hash;
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
      return (boolean) invoke(TREE, this.value, "isSplit", new Class<?>[0]);
    }

    Node getChild(final int index) {
      return new Node(invoke(TREE, this.value, "getChild", new Class<?>[] { int.class }, index));
    }
  }

  private static Object invoke(
    final Class<?> owner,
    final @Nullable Object target,
    final String name,
    final Class<?>[] parameters,
    final Object... arguments
  ) {
    try {
      final Method method = owner.getDeclaredMethod(name, parameters);
      method.setAccessible(true);
      return Objects.requireNonNull(method.invoke(target, arguments));
    } catch (final InvocationTargetException exception) {
      final Throwable cause = exception.getCause();
      if (cause instanceof final RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof final Error error) {
        throw error;
      }
      throw new AssertionError(cause);
    } catch (final ReflectiveOperationException exception) {
      throw new LinkageError(exception.getMessage(), exception);
    }
  }

  public static byte[] write(
    final int width,
    final int height,
    final long id,
    final long reference,
    final boolean keyframe,
    final List<Node> roots
  ) {
    return (byte[]) invoke(
      MCV2.class,
      null,
      "write",
      new Class<?>[] { int.class, int.class, boolean.class, List.class, long.class, long.class },
      width,
      height,
      keyframe,
      roots.stream().map(Node::value).toList(),
      id,
      reference
    );
  }

  static Node solid(final int red, final int green, final int blue) {
    return Node.leaf(Mcv2Decoder.MODE_SOLID, 0, new byte[] { (byte) red, (byte) green, (byte) blue });
  }

  public static Node motion(final int dx, final int dy) {
    return Node.leaf(Mcv2Decoder.MODE_MOTION, 0, new byte[] { (byte) dx, (byte) dy });
  }

  static Node split(final Node node) {
    return Node.split(node, node, node, node);
  }

  static Node pattern(final int size, final byte[] endpoints, final int orientation, final int axisByte) {
    final byte[] record = Arrays.copyOf(endpoints, 7 + size / 8);
    record[6] = (byte) orientation;
    Arrays.fill(record, 7, record.length, (byte) axisByte);
    return Node.leaf(Mcv2Decoder.MODE_PATTERN, 0, record);
  }

  static byte[] keyframe(final int width, final int height, final Node... roots) {
    return write(width, height, 0, 0, true, List.of(roots));
  }

  public static byte[] predicted(final int width, final int height, final Node... roots) {
    return write(width, height, 1, 0, false, List.of(roots));
  }

  static Node[] repeat(final Node node, final int count) {
    final Node[] nodes = new Node[count];
    Arrays.fill(nodes, node);
    return nodes;
  }

  public static byte[] tiny() {
    return keyframe(4, 4, solid(1, 2, 3));
  }

  public static byte[] twoPages() {
    final byte[] record = new byte[134];
    new Random(19).nextBytes(record);
    return keyframe(320, 320, repeat(Node.leaf(Mcv2Decoder.MODE_PALETTE, 0, record), 100));
  }

  private static long position(final int left, final int top, final int size) {
    return ((long) size << 32) | ((long) top << 16) | left;
  }

  static List<Node> read(final Mcv2Decoder.Frame frame) {
    final Map<Long, Node> leaves = new HashMap<>();
    final byte[] data = frame.getData();
    for (int index = 0; index < frame.getLeafCount(); index++) {
      final Mcv2Decoder.Leaf leaf = frame.getLeaf(index);
      final int mode = leaf.mode();
      final int at = leaf.offset();
      final Node node;
      final int length = Mcv2Decoder.recordSize(mode, leaf.size());
      node = Node.leaf(mode, leaf.quantizer(), length == 0 ? new byte[0] : Arrays.copyOfRange(data, at, at + length));
      leaves.put(position(leaf.left(), leaf.top(), leaf.size()), node);
    }
    final List<Node> roots = new ArrayList<>();
    for (int top = 0; top < frame.getHeight(); top += 32) {
      for (int left = 0; left < frame.getWidth(); left += 32) {
        roots.add(read(leaves, left, top, 32));
      }
    }
    return roots;
  }

  private static Node read(final Map<Long, Node> leaves, final int left, final int top, final int size) {
    final Node leaf = leaves.get(position(left, top, size));
    if (leaf != null) {
      return leaf;
    }
    if (size == 8) {
      throw new AssertionError("Missing terminal leaf");
    }
    final int half = size / 2;
    return Node.split(
      read(leaves, left, top, half),
      read(leaves, left + half, top, half),
      read(leaves, left, top + half, half),
      read(leaves, left + half, top + half, half)
    );
  }
}
