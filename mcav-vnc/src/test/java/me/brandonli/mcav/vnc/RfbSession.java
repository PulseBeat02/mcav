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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

// Drives an RfbGuard through a VNC conversation in protocol order, the way a client and a server take turns: each
// side's bytes are built with the helpers here, which write RFB's big-endian fields.
final class RfbSession {

  static final int NONE = 1;

  static final int VNC = 2;

  static final int MS_LOGON = 113;

  static final int MINOR_VERSION_3_3 = 3;
  static final int MINOR_VERSION_3_7 = 7;
  static final int MINOR_VERSION_3_8 = 8;

  static final int VNC_CHALLENGE_BYTES = 16;
  static final int VNC_RESPONSE_BYTES = 16;

  static final int SECURITY_RESULT_OK = 0;

  static final int SHARE_DESKTOP = 1;

  static final String DESKTOP_NAME = "desktop";

  static final int BITS_PER_PIXEL_32 = 32;
  static final int BITS_PER_PIXEL_16 = 16;
  static final int BITS_PER_PIXEL_8 = 8;

  static final int SET_COLOUR_MAP_ENTRIES = 1;
  static final int BELL = 2;
  static final int SERVER_CUT_TEXT = 3;

  static final int RAW_ENCODING = 0;
  static final int COPY_RECTANGLE_ENCODING = 1;
  static final int RRE_ENCODING = 2;
  static final int HEXTILE_ENCODING = 5;
  static final int ZLIB_ENCODING = 6;
  static final int DESKTOP_SIZE_ENCODING = -223;
  static final int CURSOR_ENCODING = -239;

  static final int HEXTILE_RAW = 1;
  static final int HEXTILE_BACKGROUND = 2;
  static final int HEXTILE_FOREGROUND = 4;
  static final int HEXTILE_SUBRECTANGLES = 8;
  static final int HEXTILE_COLOURED = 16;

  private final RfbGuard guard;

  RfbSession() {
    this.guard = new RfbGuard();
  }

  // The server sends bytes; the guard throws if they break the protocol or a bound.
  void server(final byte[] bytes) throws IOException {
    this.guard.acceptServer(bytes, 0, bytes.length);
  }

  void client(final byte[] bytes) {
    this.guard.acceptClient(bytes, 0, bytes.length);
  }

  // Runs the handshake up to and including the server initialisation and the client's pixel format of four bytes a
  // pixel.
  void handshake(final int minor, final int security, final int width, final int height) throws IOException {
    this.handshake(minor, security, width, height, BITS_PER_PIXEL_32);
  }

  // Runs the handshake up to and including the server initialisation and the client's pixel format.
  void handshake(final int minor, final int security, final int width, final int height, final int clientBits) throws IOException {
    this.server(version(minor));
    this.client(version(minor));
    if (minor >= MINOR_VERSION_3_7) {
      this.server(new byte[] { 1, (byte) security });
      this.client(new byte[] { (byte) security });
    } else {
      this.server(unsigned32(security));
    }
    switch (security) {
      case VNC -> {
        this.server(new byte[VNC_CHALLENGE_BYTES]);
        this.client(new byte[VNC_RESPONSE_BYTES]);
        this.server(unsigned32(SECURITY_RESULT_OK));
      }
      case MS_LOGON -> {
        this.server(new byte[24]);
        this.client(new byte[8 + 256 + 64]);
        this.server(unsigned32(SECURITY_RESULT_OK));
      }
      default -> {
        if (minor >= MINOR_VERSION_3_8) {
          this.server(unsigned32(SECURITY_RESULT_OK));
        }
      }
    }
    this.client(new byte[] { SHARE_DESKTOP });
    this.server(serverInitialization(width, height, BITS_PER_PIXEL_32, DESKTOP_NAME));
    this.client(setPixelFormat(clientBits));
  }

  static byte[] version(final int minor) {
    return String.format(Locale.ROOT, "RFB 003.%03d\n", minor).getBytes(StandardCharsets.US_ASCII);
  }

  static byte[] unsigned16(final int value) {
    return new byte[] { (byte) (value >>> 8), (byte) value };
  }

  static byte[] unsigned32(final long value) {
    return new byte[] { (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value };
  }

  static byte[] concat(final byte[]... parts) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (final byte[] part : parts) {
      out.writeBytes(part);
    }
    return out.toByteArray();
  }

  static byte[] pixelFormat(final int bitsPerPixel) {
    final byte[] format = new byte[16];
    format[0] = (byte) bitsPerPixel;
    format[1] = (byte) Math.min(bitsPerPixel, 24);
    format[3] = 1;
    return format;
  }

  static byte[] serverInitialization(final int width, final int height, final int bitsPerPixel, final String name) {
    final byte[] text = name.getBytes(StandardCharsets.UTF_8);
    return concat(unsigned16(width), unsigned16(height), pixelFormat(bitsPerPixel), unsigned32(text.length), text);
  }

  static byte[] setPixelFormat(final int bitsPerPixel) {
    return concat(new byte[] { 0, 0, 0, 0 }, pixelFormat(bitsPerPixel));
  }

  static byte[] update(final int rectangles) {
    return concat(new byte[] { 0, 0 }, unsigned16(rectangles));
  }

  static byte[] rectangle(final int left, final int top, final int width, final int height, final int encoding) {
    return concat(unsigned16(left), unsigned16(top), unsigned16(width), unsigned16(height), unsigned32(encoding));
  }

  static byte[] cutText(final int length) {
    return concat(new byte[] { SERVER_CUT_TEXT, 0, 0, 0 }, unsigned32(length), new byte[Math.max(length, 0)]);
  }

  RfbGuard guard() {
    return this.guard;
  }
}
