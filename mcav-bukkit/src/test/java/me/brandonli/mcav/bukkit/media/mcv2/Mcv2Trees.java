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
    public static Node leaf(final int mode, final int quantizer, final byte[] record) {
      return new Node(invoke(TREE, null, "leaf", new Class<?>[] { int.class, int.class, byte[].class }, mode, quantizer, record));
    }

    public static Node skip() {
      return new Node(invoke(TREE, null, "skip", new Class<?>[0]));
    }

    public static Node split(final Node a, final Node b, final Node c, final Node d) {
      return new Node(invoke(TREE, null, "split", new Class<?>[] { TREE, TREE, TREE, TREE }, a.value, b.value, c.value, d.value));
    }

    public int getMode() {
      return (int) invoke(TREE, this.value, "getMode", new Class<?>[0]);
    }

    public int getQ() {
      return (int) invoke(TREE, this.value, "getQ", new Class<?>[0]);
    }

    public byte[] getRecord() {
      return ((byte[]) invoke(TREE, this.value, "record", new Class<?>[0])).clone();
    }

    public boolean isSplit() {
      return (boolean) invoke(TREE, this.value, "isSplit", new Class<?>[0]);
    }

    public Node getChild(final int index) {
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

  public static Node solid(final int red, final int green, final int blue) {
    return Node.leaf(Mcv2Decoder.MODE_SOLID, 0, new byte[] { (byte) red, (byte) green, (byte) blue });
  }

  public static Node motion(final int dx, final int dy) {
    return Node.leaf(Mcv2Decoder.MODE_MOTION, 0, new byte[] { (byte) dx, (byte) dy });
  }

  public static Node split(final Node node) {
    return Node.split(node, node, node, node);
  }

  public static Node pattern(final int size, final byte[] endpoints, final int orientation, final int axisByte) {
    final byte[] record = Arrays.copyOf(endpoints, 7 + size / 8);
    record[6] = (byte) orientation;
    Arrays.fill(record, 7, record.length, (byte) axisByte);
    return Node.leaf(Mcv2Decoder.MODE_PATTERN, 0, record);
  }

  public static byte[] keyframe(final int width, final int height, final Node... roots) {
    return write(width, height, 0, 0, true, List.of(roots));
  }

  public static byte[] predicted(final int width, final int height, final Node... roots) {
    return write(width, height, 1, 0, false, List.of(roots));
  }

  public static Node[] repeat(final Node node, final int count) {
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

  private static long position(final int x, final int y, final int size) {
    return ((long) size << 32) | ((long) y << 16) | x;
  }

  public static List<Node> read(final Mcv2Decoder.Frame frame) {
    final Map<Long, Node> leaves = new HashMap<>();
    final byte[] data = frame.getData();
    for (int index = 0; index < frame.getLeafCount(); index++) {
      final Mcv2Decoder.Leaf leaf = frame.getLeaf(index);
      final int mode = leaf.mode();
      final int at = leaf.offset();
      final Node node;
      if (mode == Mcv2Decoder.MODE_SKIP && frame.isKeyframe()) {
        final int color = frame.getDefaultColor();
        node = solid(color >> 16, color >> 8, color);
      } else if (mode == Mcv2Decoder.MODE_PATTERN) {
        final byte[] pairs = frame.getEndpointTable();
        final byte[] words = frame.getSelectorTable(leaf.size());
        final byte[] record = new byte[7 + leaf.size() / 8];
        if (pairs.length == 0) {
          System.arraycopy(data, at, record, 0, 6);
        } else {
          final int entry = (data[at] & 255) * 4;
          for (int endpoint = 0; endpoint < 2; endpoint++) {
            final int color = Mcv2Decoder.unpack565(pairs[entry + endpoint * 2], pairs[entry + endpoint * 2 + 1]);
            record[endpoint * 3] = (byte) (color >> 16);
            record[endpoint * 3 + 1] = (byte) (color >> 8);
            record[endpoint * 3 + 2] = (byte) color;
          }
        }
        final int selector = at + (pairs.length == 0 ? 6 : 1);
        final int wordBytes = 1 + leaf.size() / 8;
        if (words.length == 0) {
          System.arraycopy(data, selector, record, 6, wordBytes);
        } else {
          System.arraycopy(words, (data[selector] & 255) * wordBytes, record, 6, wordBytes);
        }
        node = Node.leaf(mode, 0, record);
      } else {
        final int length;
        if (mode == Mcv2Decoder.MODE_COMPACT) {
          final int control = data[at] & 255;
          length =
            1 +
            (control >> 4) +
            switch (control & 15) {
              case 0 -> 1;
              case 1 -> 10;
              default -> 8;
            };
        } else {
          length = Mcv2Decoder.recordSize(mode, leaf.size());
        }
        node = Node.leaf(mode, leaf.quantizer(), Arrays.copyOfRange(data, at, at + length));
      }
      leaves.put(position(leaf.left(), leaf.top(), leaf.size()), node);
    }
    final List<Node> roots = new ArrayList<>();
    for (int y = 0; y < frame.getHeight(); y += 32) {
      for (int x = 0; x < frame.getWidth(); x += 32) {
        roots.add(read(leaves, x, y, 32));
      }
    }
    return roots;
  }

  private static Node read(final Map<Long, Node> leaves, final int x, final int y, final int size) {
    final Node leaf = leaves.get(position(x, y, size));
    if (leaf != null) {
      return leaf;
    }
    if (size == 8) {
      throw new AssertionError("Missing terminal leaf");
    }
    final int half = size / 2;
    return Node.split(
      read(leaves, x, y, half),
      read(leaves, x + half, y, half),
      read(leaves, x, y + half, half),
      read(leaves, x + half, y + half, half)
    );
  }
}
