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
package me.brandonli.mcav.vnc;

import static me.brandonli.mcav.vnc.RfbSession.NONE;
import static me.brandonli.mcav.vnc.RfbSession.concat;
import static me.brandonli.mcav.vnc.RfbSession.cutText;
import static me.brandonli.mcav.vnc.RfbSession.rectangle;
import static me.brandonli.mcav.vnc.RfbSession.unsigned16;
import static me.brandonli.mcav.vnc.RfbSession.unsigned32;
import static me.brandonli.mcav.vnc.RfbSession.update;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

/**
 * Properties of {@link RfbGuard} on generated server streams: every message of a valid session passes whatever
 * chunks the bytes arrive in, so the guard follows the protocol and not the reads; and a session with one byte
 * changed either passes or is refused with an {@link IOException}, never another failure.
 */
final class RfbGuardPropertyTest {

  private static final String SEED = "20260928";

  private static final int SIDE = 48;

  private static final int PIXEL = 4;

  private static final int TILE = 16;

  private static final int MESSAGES = 12;

  @Property(seed = SEED, tries = 300)
  void passesEveryValidSessionInAnyChunks(@ForAll final long seed) throws IOException {
    final Random random = new Random(seed);
    final byte[] messages = messages(random);
    final RfbSession session = new RfbSession();
    session.handshake(8, NONE, SIDE, SIDE);
    int at = 0;
    while (at < messages.length) {
      final int chunk = Math.min(1 + random.nextInt(64), messages.length - at);
      final byte[] part = Arrays.copyOfRange(messages, at, at + chunk);
      assertDoesNotThrow(() -> session.server(part));
      at += chunk;
    }
    final IOException boundary = assertThrows(IOException.class, () -> session.server(new byte[] { (byte) 255 }));
    assertEquals("The VNC server broke the protocol or a bound: message type 255", boundary.getMessage());
  }

  @Property(seed = SEED, tries = 300)
  void refusesAChangedSessionOnlyWithAnIoException(@ForAll final long seed) throws IOException {
    final Random random = new Random(seed);
    final byte[] messages = messages(random);
    messages[random.nextInt(messages.length)] ^= (byte) (1 + random.nextInt(255));
    final RfbSession session = new RfbSession();
    session.handshake(8, NONE, SIDE, SIDE);
    try {
      session.server(messages);
    } catch (final IOException refused) {
      // the one failure the guard may report
    }
  }

  /** A run of valid server messages, each type and encoding the client decodes. */
  private static byte[] messages(final Random random) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int messageIndex = 0; messageIndex < MESSAGES; messageIndex++) {
      switch (random.nextInt(4)) {
        case 0 -> out.writeBytes(new byte[] { 2 });
        case 1 -> out.writeBytes(cutText(random.nextInt(300)));
        case 2 -> out.writeBytes(colours(random));
        default -> out.writeBytes(framebufferUpdate(random));
      }
    }
    out.writeBytes(new byte[] { 2 });
    return out.toByteArray();
  }

  private static byte[] colours(final Random random) {
    final int count = random.nextInt(8);
    return concat(new byte[] { 1, 0 }, unsigned16(random.nextInt(256)), unsigned16(count), new byte[6 * count]);
  }

  private static byte[] framebufferUpdate(final Random random) {
    final int count = 1 + random.nextInt(3);
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(update(count));
    for (int rectangleIndex = 0; rectangleIndex < count; rectangleIndex++) {
      final int width = random.nextInt(SIDE / 2 + 1);
      final int height = random.nextInt(SIDE / 2 + 1);
      final int left = random.nextInt(SIDE - width + 1);
      final int top = random.nextInt(SIDE - height + 1);
      final int encoding = random.nextInt(6);
      switch (encoding) {
        case 0 -> out.writeBytes(concat(rectangle(left, top, width, height, 0), new byte[width * height * PIXEL]));
        case 1 -> out.writeBytes(concat(rectangle(left, top, width, height, 1), unsigned16(0), unsigned16(0)));
        case 2 -> {
          final int subrectangles = random.nextInt(width * height + 1);
          out.writeBytes(
            concat(
              rectangle(left, top, width, height, 2),
              unsigned32(subrectangles),
              new byte[PIXEL],
              new byte[subrectangles * (PIXEL + 8)]
            )
          );
        }
        case 3 -> out.writeBytes(concat(rectangle(left, top, width, height, 5), hextile(random, width, height)));
        case 4 -> {
          final int length = random.nextInt(width * height * PIXEL + 1);
          out.writeBytes(concat(rectangle(left, top, width, height, 6), unsigned32(length), new byte[length]));
        }
        default -> {
          final int cursor = random.nextInt(9);
          out.writeBytes(concat(rectangle(0, 0, cursor, cursor, -239), new byte[cursor * cursor * PIXEL + ((cursor + 7) / 8) * cursor]));
        }
      }
    }
    return out.toByteArray();
  }

  private static byte[] hextile(final Random random, final int width, final int height) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int tileTop = 0; tileTop < height; tileTop += TILE) {
      for (int tileLeft = 0; tileLeft < width; tileLeft += TILE) {
        final int tileWidth = Math.min(TILE, width - tileLeft);
        final int tileHeight = Math.min(TILE, height - tileTop);
        final int flags = random.nextInt(32);
        out.write(flags);
        if ((flags & 1) != 0) {
          out.writeBytes(new byte[tileWidth * tileHeight * PIXEL]);
          continue;
        }
        if ((flags & 2) != 0) {
          out.writeBytes(new byte[PIXEL]);
        }
        if ((flags & 4) != 0) {
          out.writeBytes(new byte[PIXEL]);
        }
        if ((flags & 8) != 0) {
          final int count = random.nextInt(6);
          out.write(count);
          out.writeBytes(new byte[count * (((flags & 16) != 0 ? PIXEL : 0) + 2)]);
        }
      }
    }
    return out.toByteArray();
  }
}
