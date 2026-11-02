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

import static me.brandonli.mcav.vnc.RfbSession.BELL;
import static me.brandonli.mcav.vnc.RfbSession.COPY_RECTANGLE_ENCODING;
import static me.brandonli.mcav.vnc.RfbSession.CURSOR_ENCODING;
import static me.brandonli.mcav.vnc.RfbSession.HEXTILE_BACKGROUND;
import static me.brandonli.mcav.vnc.RfbSession.HEXTILE_COLOURED;
import static me.brandonli.mcav.vnc.RfbSession.HEXTILE_ENCODING;
import static me.brandonli.mcav.vnc.RfbSession.HEXTILE_FOREGROUND;
import static me.brandonli.mcav.vnc.RfbSession.HEXTILE_RAW;
import static me.brandonli.mcav.vnc.RfbSession.HEXTILE_SUBRECTANGLES;
import static me.brandonli.mcav.vnc.RfbSession.MINOR_VERSION_3_8;
import static me.brandonli.mcav.vnc.RfbSession.NONE;
import static me.brandonli.mcav.vnc.RfbSession.RAW_ENCODING;
import static me.brandonli.mcav.vnc.RfbSession.RRE_ENCODING;
import static me.brandonli.mcav.vnc.RfbSession.SET_COLOUR_MAP_ENTRIES;
import static me.brandonli.mcav.vnc.RfbSession.ZLIB_ENCODING;
import static me.brandonli.mcav.vnc.RfbSession.concatenate;
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

// Properties of RfbGuard on generated server streams: every message of a valid session passes whatever chunks the bytes
// arrive in, so the guard follows the protocol and not the reads; and a session with one byte changed either passes or
// is refused with an IOException, never another failure.
final class RfbGuardPropertyTest {

  private static final String SEED = "20260928";

  private static final int SIDE = 48;

  private static final int PIXEL = 4;

  private static final int TILE = 16;

  private static final int MESSAGES = 12;

  @Property(seed = SEED, tries = 300)
  void shouldPassEveryValidSessionInAnyChunks(@ForAll final long seed) throws IOException {
    final Random random = new Random(seed);
    final byte[] messages = this.messages(random);
    final RfbSession session = new RfbSession();
    session.handshake(MINOR_VERSION_3_8, NONE, SIDE, SIDE);
    int at = 0;
    while (at < messages.length) {
      final int chunk = Math.min(1 + random.nextInt(64), messages.length - at);
      final byte[] part = Arrays.copyOfRange(messages, at, at + chunk);
      assertDoesNotThrow(() -> session.sendFromServer(part));
      at += chunk;
    }
    final IOException boundary = assertThrows(IOException.class, () -> session.sendFromServer(new byte[] { (byte) 255 }));
    final String message = boundary.getMessage();
    assertEquals("VNC server sent message type 255, which breaks the protocol or a bound!", message);
  }

  @Property(seed = SEED, tries = 300)
  void shouldRefuseAChangedSessionOnlyWithAnIoException(@ForAll final long seed) throws IOException {
    final Random random = new Random(seed);
    final byte[] messages = this.messages(random);
    messages[random.nextInt(messages.length)] ^= (byte) (1 + random.nextInt(255));
    final RfbSession session = new RfbSession();
    session.handshake(MINOR_VERSION_3_8, NONE, SIDE, SIDE);
    try {
      session.sendFromServer(messages);
    } catch (final IOException exception) {
      // the one failure the guard may report
    }
  }

  // A run of valid server messages, each type and encoding the client decodes.
  private byte[] messages(final Random random) {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (int messageIndex = 0; messageIndex < MESSAGES; messageIndex++) {
      switch (random.nextInt(4)) {
        case 0 -> output.writeBytes(new byte[] { BELL });
        case 1 -> output.writeBytes(cutText(random.nextInt(300)));
        case 2 -> output.writeBytes(this.colours(random));
        default -> output.writeBytes(this.framebufferUpdate(random));
      }
    }
    output.writeBytes(new byte[] { BELL });
    return output.toByteArray();
  }

  private byte[] colours(final Random random) {
    final int count = random.nextInt(8);
    return concatenate(new byte[] { SET_COLOUR_MAP_ENTRIES, 0 }, unsigned16(random.nextInt(256)), unsigned16(count), new byte[6 * count]);
  }

  private byte[] framebufferUpdate(final Random random) {
    final int count = 1 + random.nextInt(3);
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.writeBytes(update(count));
    for (int rectangleIndex = 0; rectangleIndex < count; rectangleIndex++) {
      final int width = random.nextInt(SIDE / 2 + 1);
      final int height = random.nextInt(SIDE / 2 + 1);
      final int left = random.nextInt(SIDE - width + 1);
      final int top = random.nextInt(SIDE - height + 1);
      final int encoding = random.nextInt(6);
      switch (encoding) {
        case 0 -> output.writeBytes(concatenate(rectangle(left, top, width, height, RAW_ENCODING), new byte[width * height * PIXEL]));
        case 1 -> output.writeBytes(
          concatenate(rectangle(left, top, width, height, COPY_RECTANGLE_ENCODING), unsigned16(0), unsigned16(0))
        );
        case 2 -> {
          final int subrectangles = random.nextInt(width * height + 1);
          output.writeBytes(
            concatenate(
              rectangle(left, top, width, height, RRE_ENCODING),
              unsigned32(subrectangles),
              new byte[PIXEL],
              new byte[subrectangles * (PIXEL + 8)]
            )
          );
        }
        case 3 -> output.writeBytes(
          concatenate(rectangle(left, top, width, height, HEXTILE_ENCODING), this.hextile(random, width, height))
        );
        case 4 -> {
          final int length = random.nextInt(width * height * PIXEL + 1);
          output.writeBytes(concatenate(rectangle(left, top, width, height, ZLIB_ENCODING), unsigned32(length), new byte[length]));
        }
        default -> {
          final int cursor = random.nextInt(9);
          output.writeBytes(
            concatenate(rectangle(0, 0, cursor, cursor, CURSOR_ENCODING), new byte[cursor * cursor * PIXEL + ((cursor + 7) / 8) * cursor])
          );
        }
      }
    }
    return output.toByteArray();
  }

  private byte[] hextile(final Random random, final int width, final int height) {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (int tileTop = 0; tileTop < height; tileTop += TILE) {
      for (int tileLeft = 0; tileLeft < width; tileLeft += TILE) {
        final int tileWidth = Math.min(TILE, width - tileLeft);
        final int tileHeight = Math.min(TILE, height - tileTop);
        this.tile(random, output, tileWidth, tileHeight);
      }
    }

    return output.toByteArray();
  }

  private void tile(final Random random, final ByteArrayOutputStream output, final int tileWidth, final int tileHeight) {
    final int flags = random.nextInt(32);
    output.write(flags);
    if ((flags & HEXTILE_RAW) != 0) {
      output.writeBytes(new byte[tileWidth * tileHeight * PIXEL]);
      return;
    }

    if ((flags & HEXTILE_BACKGROUND) != 0) {
      output.writeBytes(new byte[PIXEL]);
    }

    if ((flags & HEXTILE_FOREGROUND) != 0) {
      output.writeBytes(new byte[PIXEL]);
    }

    if ((flags & HEXTILE_SUBRECTANGLES) != 0) {
      final int count = random.nextInt(6);
      final int colour = (flags & HEXTILE_COLOURED) != 0 ? PIXEL : 0;
      output.write(count);
      output.writeBytes(new byte[count * (colour + 2)]);
    }
  }
}
