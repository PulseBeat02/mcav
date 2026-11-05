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

import com.google.common.base.Preconditions;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/*
 * Checks what a VNC server sends before the client reads it, since the client allocates whatever length a server
 * names: any length above its bound, or any message, encoding or byte the client cannot decode, ends the connection
 * with an IOException. Client messages are read only for the version, security type and pixel format they pick.
 */
final class RfbGuard {

  // The largest framebuffer side accepted: a 4K desktop fits, and the client's picture stays under 64 MiB.
  static final int MAXIMUM_SIDE = 4096;

  static final int MAXIMUM_NAME = 4096;

  static final int MAXIMUM_REASON = 4096;

  // The longest clipboard text accepted, in bytes; the client does not use it.
  static final int MAXIMUM_TEXT = 1 << 20;

  // Compressed zlib data may exceed what it decompresses to by a little; this is the room allowed.
  static final int ZLIB_SLACK = 1 << 16;

  private static final int VERSION_BYTES = 12;
  private static final String VERSION_PREFIX = "RFB 003.";
  private static final char VERSION_TERMINATOR = '\n';

  private static final int BYTE_MASK = 0xFF;

  private static final Set<Integer> SUPPORTED_BITS_PER_PIXEL = Set.of(8, 16, 32);

  private static final int UNKNOWN = -1;

  private static final int NONE = 1;
  private static final int VNC = 2;
  private static final int MS_LOGON = 113;

  private static final int MINIMUM_MINOR_WITH_TYPE_LIST = 7;
  private static final int MINIMUM_MINOR_WITH_REASONS = 8;

  private final Object lock;
  private final ServerParser server;
  private final ClientParser client;

  private int minorVersion;
  private int securityType;
  private int clientBytesPerPixel;

  RfbGuard() {
    this.lock = new Object();
    this.server = new ServerParser();
    this.client = new ClientParser();
    this.minorVersion = UNKNOWN;
    this.securityType = UNKNOWN;
    this.clientBytesPerPixel = UNKNOWN;
  }

  InputStream serverInput(final InputStream input) {
    Preconditions.checkNotNull(input, "Input must not be null!");
    return new GuardedInput(input);
  }

  OutputStream clientOutput(final OutputStream output) {
    Preconditions.checkNotNull(output, "Output must not be null!");
    return new ObservedOutput(output);
  }

  void acceptServer(final byte[] buffer, final int offset, final int length) throws IOException {
    synchronized (this.lock) {
      this.server.accept(buffer, offset, length);
    }
  }

  void acceptClient(final byte[] buffer, final int offset, final int length) {
    synchronized (this.lock) {
      this.client.accept(buffer, offset, length);
    }
  }

  private int bytesPerPixel() {
    return this.clientBytesPerPixel != UNKNOWN ? this.clientBytesPerPixel : this.server.serverBytesPerPixel;
  }

  private IOException violation(final String what) {
    final String message = "VNC server sent %s, which breaks the protocol or a bound!".formatted(what);
    return new IOException(message);
  }

  private int unsigned8(final byte[] field, final int at) {
    return field[at] & BYTE_MASK;
  }

  private int unsigned16(final byte[] field, final int at) {
    return (this.unsigned8(field, at) << Byte.SIZE) | this.unsigned8(field, at + 1);
  }

  private long unsigned32(final byte[] field, final int at) {
    return ((long) this.unsigned16(field, at) << Short.SIZE) | this.unsigned16(field, at + Short.BYTES);
  }

  private int pixelBytes(final int bitsPerPixel) throws IOException {
    if (!SUPPORTED_BITS_PER_PIXEL.contains(bitsPerPixel)) {
      throw this.violation("%s bits per pixel".formatted(bitsPerPixel));
    }

    return bitsPerPixel / Byte.SIZE;
  }

  private final class GuardedInput extends FilterInputStream {

    private static final int SKIP_BUFFER_BYTES = 8192;

    private GuardedInput(final InputStream input) {
      super(input);
    }

    @Override
    public int read() throws IOException {
      final int value = this.in.read();
      if (value >= 0) {
        final byte[] single = { (byte) value };
        RfbGuard.this.acceptServer(single, 0, single.length);
      }

      return value;
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) throws IOException {
      final int count = this.in.read(buffer, offset, length);
      if (count > 0) {
        RfbGuard.this.acceptServer(buffer, offset, count);
      }

      return count;
    }

    @Override
    public long skip(final long count) throws IOException {
      final long wanted = Math.max(count, 0);
      final long bufferBytes = Math.min(wanted, SKIP_BUFFER_BYTES);
      final byte[] buffer = new byte[(int) bufferBytes];
      final int read = this.read(buffer, 0, buffer.length);
      return Math.max(read, 0);
    }

    @Override
    public boolean markSupported() {
      return false;
    }
  }

  private final class ObservedOutput extends FilterOutputStream {

    private ObservedOutput(final OutputStream output) {
      super(output);
    }

    @Override
    public void write(final int value) throws IOException {
      final byte[] single = { (byte) value };
      RfbGuard.this.acceptClient(single, 0, single.length);
      this.out.write(value);
    }

    @Override
    public void write(final byte[] buffer, final int offset, final int length) throws IOException {
      RfbGuard.this.acceptClient(buffer, offset, length);
      this.out.write(buffer, offset, length);
    }
  }

  private enum Mode {
    FIELD,
    SKIP,
    // the server's next byte can only be framed once the client answered
    AWAIT_VERSION,
    AWAIT_CHOICE,
    CLOSED,
  }

  // A field of the server's side of the protocol, with its length in bytes.
  private enum Server {
    VERSION(VERSION_BYTES),
    SECURITY_TYPE(Integer.BYTES),
    SECURITY_COUNT(1),
    // one byte per type: the count read before it gives the real length
    SECURITY_TYPES(1),
    REASON_LENGTH(Integer.BYTES),
    CHALLENGE(16),
    // MS-Logon II: the generator, the modulus and the server's public key
    LOGON_KEYS(24),
    RESULT(Integer.BYTES),
    // the framebuffer's size, its pixel format and the length of its name
    INITIALIZATION(24),
    MESSAGE(1),
    // padding and the number of rectangles
    UPDATE(3),
    // position, size and encoding
    RECTANGLE(12),
    // the number of subrectangles, then the background pixel
    RRE(Integer.BYTES),
    TILE(1),
    SUBRECTANGLES(1),
    ZLIB_LENGTH(Integer.BYTES),
    // padding, the first color and the number of colors
    COLORS(5),
    // padding and the length of the text
    TEXT(7);

    private final int bytes;

    Server(final int bytes) {
      this.bytes = bytes;
    }
  }

  @FunctionalInterface
  private interface Step {
    void run() throws IOException;
  }

  private enum Then {
    STATE,
    RECTANGLE,
    TILE,
    CLOSE,
  }

  private final class ServerParser {

    private static final int COLOR_ENTRY = 6;
    private static final int SUBRECTANGLE_GEOMETRY = 8;
    private static final int HEXTILE_GEOMETRY = 2;
    private static final int COPY_RECTANGLE_SOURCE = 4;

    private static final int TILE_SIDE = 16;

    private static final int RAW = 0;
    private static final int COPY_RECTANGLE = 1;
    private static final int RRE_ENCODING = 2;
    private static final int HEXTILE = 5;
    private static final int ZLIB = 6;
    private static final int DESKTOP_SIZE = -223;
    private static final int CURSOR = -239;

    private static final int TILE_RAW = 1;
    private static final int TILE_BACKGROUND = 2;
    private static final int TILE_FOREGROUND = 4;
    private static final int TILE_SUBRECTANGLES = 8;
    private static final int TILE_COLORED = 16;

    private static final int FRAMEBUFFER_UPDATE = 0;
    private static final int SET_COLOR_MAP_ENTRIES = 1;
    private static final int BELL = 2;
    private static final int SERVER_CUT_TEXT = 3;

    private static final int SECURITY_TYPE_INVALID = 0;
    private static final int SECURITY_RESULT_OK = 0;

    private static final int FRAMEBUFFER_WIDTH_OFFSET = 0;
    private static final int FRAMEBUFFER_HEIGHT_OFFSET = 2;
    private static final int BITS_PER_PIXEL_OFFSET = 4;
    private static final int NAME_LENGTH_OFFSET = 20;

    private static final int RECTANGLE_COUNT_OFFSET = 1;

    private static final int RECTANGLE_LEFT_OFFSET = 0;
    private static final int RECTANGLE_TOP_OFFSET = 2;
    private static final int RECTANGLE_WIDTH_OFFSET = 4;
    private static final int RECTANGLE_HEIGHT_OFFSET = 6;
    private static final int RECTANGLE_ENCODING_OFFSET = 8;

    private static final int COLOR_COUNT_OFFSET = 3;
    private static final int TEXT_LENGTH_OFFSET = 3;

    private static final int INITIAL_BYTES_PER_PIXEL = 4;

    private final byte[] field;

    private Mode mode;
    private Server state;
    private int need;
    private int have;
    private long skip;
    private Then then;
    private Server skipTo;

    private int serverBytesPerPixel;
    private int width;
    private int height;

    private int rectangles;
    private int rectangleWidth;
    private int rectangleHeight;
    private int tile;
    private int tileFlags;

    ServerParser() {
      this.field = new byte[Server.INITIALIZATION.bytes];
      this.mode = Mode.FIELD;
      this.state = Server.VERSION;
      this.need = Server.VERSION.bytes;
      this.then = Then.STATE;
      this.skipTo = Server.MESSAGE;
      this.serverBytesPerPixel = INITIAL_BYTES_PER_PIXEL;
    }

    void accept(final byte[] buffer, final int offset, final int length) throws IOException {
      int at = offset;
      final int end = offset + length;
      while (at < end) {
        at = switch (this.mode) {
          case FIELD -> this.collect(buffer, at);
          case SKIP -> this.skipFrom(at, end);
          case AWAIT_VERSION -> this.continueAfterVersion(at);
          case AWAIT_CHOICE -> this.continueAfterChoice(at);
          case CLOSED -> throw RfbGuard.this.violation("bytes after the connection ended");
        };
      }
    }

    private int collect(final byte[] buffer, final int at) throws IOException {
      if (this.have < this.field.length) {
        this.field[this.have] = buffer[at];
      }
      this.have++;

      if (this.have == this.need) {
        this.complete();
      }

      return at + 1;
    }

    private int skipFrom(final int at, final int end) {
      final long taken = Math.min(this.skip, end - at);
      this.skip -= taken;

      if (this.skip == 0) {
        this.continueAfterSkip();
      }

      return at + (int) taken;
    }

    private void continueAfterSkip() {
      this.mode = Mode.FIELD;

      final Runnable next = switch (this.then) {
        case RECTANGLE -> this::nextRectangle;
        case TILE -> this::nextTile;
        case STATE -> () -> this.expect(this.skipTo);
        case CLOSE -> this::close;
      };
      next.run();
    }

    private void skip(final long bytes, final Then after, final Server next) {
      this.then = after;
      this.skipTo = next;

      if (bytes == 0) {
        this.continueAfterSkip();
        return;
      }

      this.skip = bytes;
      this.mode = Mode.SKIP;
    }

    private void expect(final Server next) {
      final int pixelBytes = next == Server.RRE ? RfbGuard.this.bytesPerPixel() : 0;
      this.state = next;
      this.have = 0;
      this.need = next.bytes + pixelBytes;
    }

    // the connection ends after this field: any byte after it breaks the protocol
    private void close() {
      this.mode = Mode.CLOSED;
    }

    private void complete() throws IOException {
      final Step step = switch (this.state) {
        case VERSION -> this::version;
        case SECURITY_TYPE -> this::securityType;
        case SECURITY_COUNT -> this::securityCount;
        case SECURITY_TYPES -> this::awaitChoice;
        case REASON_LENGTH -> this::reason;
        case CHALLENGE, LOGON_KEYS -> this::expectResult;
        case RESULT -> this::result;
        case INITIALIZATION -> this::initialization;
        case MESSAGE -> this::message;
        case UPDATE -> this::update;
        case RECTANGLE -> this::rectangle;
        case RRE -> this::rre;
        case TILE -> this::tile;
        case SUBRECTANGLES -> this::subrectangles;
        case ZLIB_LENGTH -> this::zlib;
        case COLORS -> this::colors;
        case TEXT -> this::text;
      };
      step.run();
    }

    private void version() throws IOException {
      final String text = new String(this.field, 0, VERSION_BYTES, StandardCharsets.US_ASCII);
      final char last = text.charAt(VERSION_BYTES - 1);
      if (!text.startsWith(VERSION_PREFIX) || last != VERSION_TERMINATOR) {
        throw RfbGuard.this.violation("a protocol version other than 3.x");
      }

      this.mode = Mode.AWAIT_VERSION;
    }

    private void securityType() throws IOException {
      final long type = this.unsigned32(0);
      if (type == SECURITY_TYPE_INVALID) {
        this.expect(Server.REASON_LENGTH);
        return;
      }

      RfbGuard.this.securityType = (int) Math.min(type, Integer.MAX_VALUE);
      this.continueAfterSecurity(RfbGuard.this.securityType);
    }

    private void continueAfterSecurity(final int type) throws IOException {
      if (type < 0) {
        throw RfbGuard.this.violation("data before the client chose a security type");
      }

      switch (type) {
        case NONE -> this.expectResultOrInitialization();
        case VNC -> this.expect(Server.CHALLENGE);
        case MS_LOGON -> this.expect(Server.LOGON_KEYS);
        default -> throw RfbGuard.this.violation("security type %s that the client does not support".formatted(type));
      }
    }

    private void expectResultOrInitialization() {
      final boolean sendsResult = RfbGuard.this.minorVersion >= MINIMUM_MINOR_WITH_REASONS;
      final Server next = sendsResult ? Server.RESULT : Server.INITIALIZATION;
      this.expect(next);
    }

    private void securityCount() {
      final int count = this.unsigned8(0);
      if (count == 0) {
        this.expect(Server.REASON_LENGTH);
        return;
      }

      this.expect(Server.SECURITY_TYPES);
      this.need = count;
    }

    private void awaitChoice() {
      this.mode = Mode.AWAIT_CHOICE;
    }

    // a failure reason, after which the server closes the connection
    private void reason() throws IOException {
      final long length = this.unsigned32(0);
      if (length > MAXIMUM_REASON) {
        throw RfbGuard.this.violation("a failure reason of %s bytes".formatted(length));
      }

      this.skip(length, Then.CLOSE, Server.VERSION);
    }

    private void expectResult() {
      this.expect(Server.RESULT);
    }

    private void result() {
      final long result = this.unsigned32(0);
      if (result == SECURITY_RESULT_OK) {
        this.expect(Server.INITIALIZATION);
        return;
      }

      if (RfbGuard.this.minorVersion >= MINIMUM_MINOR_WITH_REASONS) {
        this.expect(Server.REASON_LENGTH);
        return;
      }

      this.close();
    }

    private void initialization() throws IOException {
      final int framebufferWidth = this.unsigned16(FRAMEBUFFER_WIDTH_OFFSET);
      final int framebufferHeight = this.unsigned16(FRAMEBUFFER_HEIGHT_OFFSET);
      final int bitsPerPixel = this.unsigned8(BITS_PER_PIXEL_OFFSET);
      final long nameLength = this.unsigned32(NAME_LENGTH_OFFSET);
      this.resize(framebufferWidth, framebufferHeight);
      this.serverBytesPerPixel = RfbGuard.this.pixelBytes(bitsPerPixel);
      this.skipBounded(nameLength, MAXIMUM_NAME, "desktop name", Server.MESSAGE);
    }

    private void resize(final int newWidth, final int newHeight) throws IOException {
      if (newWidth < 1 || newHeight < 1 || newWidth > MAXIMUM_SIDE || newHeight > MAXIMUM_SIDE) {
        throw RfbGuard.this.violation("a framebuffer of %sx%s pixels".formatted(newWidth, newHeight));
      }

      this.width = newWidth;
      this.height = newHeight;
    }

    private void skipBounded(final long length, final int limit, final String what, final Server next) throws IOException {
      if (length > limit) {
        throw RfbGuard.this.violation("a %s of %s bytes".formatted(what, length));
      }

      this.skip(length, Then.STATE, next);
    }

    private void message() throws IOException {
      final int type = this.unsigned8(0);
      switch (type) {
        case FRAMEBUFFER_UPDATE -> this.expect(Server.UPDATE);
        case SET_COLOR_MAP_ENTRIES -> this.expect(Server.COLORS);
        case BELL -> this.expect(Server.MESSAGE);
        case SERVER_CUT_TEXT -> this.expect(Server.TEXT);
        default -> throw RfbGuard.this.violation("message type %s".formatted(type));
      }
    }

    private void update() {
      this.rectangles = this.unsigned16(RECTANGLE_COUNT_OFFSET);
      this.nextRectangle();
    }

    private void nextRectangle() {
      if (this.rectangles == 0) {
        this.expect(Server.MESSAGE);
        return;
      }

      this.rectangles--;
      this.expect(Server.RECTANGLE);
    }

    private void rectangle() throws IOException {
      final int left = this.unsigned16(RECTANGLE_LEFT_OFFSET);
      final int top = this.unsigned16(RECTANGLE_TOP_OFFSET);
      final int width = this.unsigned16(RECTANGLE_WIDTH_OFFSET);
      final int height = this.unsigned16(RECTANGLE_HEIGHT_OFFSET);
      final int encoding = (int) this.unsigned32(RECTANGLE_ENCODING_OFFSET);
      if (encoding == DESKTOP_SIZE) {
        this.resize(width, height);
        this.nextRectangle();
        return;
      }

      if (encoding == CURSOR) {
        this.cursor(width, height);
        return;
      }

      if (left + width > this.width || top + height > this.height) {
        throw RfbGuard.this.violation("a rectangle outside the framebuffer");
      }

      this.rectangleWidth = width;
      this.rectangleHeight = height;
      this.encodedRectangle(encoding);
    }

    private void cursor(final int width, final int height) throws IOException {
      if (width > MAXIMUM_SIDE || height > MAXIMUM_SIDE) {
        throw RfbGuard.this.violation("a cursor of %sx%s pixels".formatted(width, height));
      }

      final int pixelBytes = RfbGuard.this.bytesPerPixel();
      final int maskRow = (width + Byte.SIZE - 1) / Byte.SIZE;
      final long mask = (long) maskRow * height;
      final long image = (long) width * height * pixelBytes;
      this.skip(image + mask, Then.RECTANGLE, Server.MESSAGE);
    }

    private void encodedRectangle(final int encoding) throws IOException {
      switch (encoding) {
        case RAW -> this.raw();
        case COPY_RECTANGLE -> this.skip(COPY_RECTANGLE_SOURCE, Then.RECTANGLE, Server.MESSAGE);
        case RRE_ENCODING -> this.expect(Server.RRE);
        case HEXTILE -> this.firstTile();
        case ZLIB -> this.expect(Server.ZLIB_LENGTH);
        default -> throw RfbGuard.this.violation("encoding %s".formatted(encoding));
      }
    }

    private void raw() {
      final int pixelBytes = RfbGuard.this.bytesPerPixel();
      final long bytes = (long) this.rectangleWidth * this.rectangleHeight * pixelBytes;
      this.skip(bytes, Then.RECTANGLE, Server.MESSAGE);
    }

    private void firstTile() {
      this.tile = 0;
      this.nextTile();
    }

    private void nextTile() {
      if (this.tile == this.tiles()) {
        this.nextRectangle();
        return;
      }

      this.expect(Server.TILE);
    }

    private int tiles() {
      final int across = this.tilesAcross();
      final int down = (this.rectangleHeight + TILE_SIDE - 1) / TILE_SIDE;
      return across * down;
    }

    private int tilesAcross() {
      return (this.rectangleWidth + TILE_SIDE - 1) / TILE_SIDE;
    }

    private void rre() throws IOException {
      final long count = this.unsigned32(0);
      final long pixels = (long) this.rectangleWidth * this.rectangleHeight;
      if (count > pixels) {
        throw RfbGuard.this.violation("%s RRE subrectangles in %sx%s pixels".formatted(count, this.rectangleWidth, this.rectangleHeight));
      }

      final long each = RfbGuard.this.bytesPerPixel() + (long) SUBRECTANGLE_GEOMETRY;
      this.skip(count * each, Then.RECTANGLE, Server.MESSAGE);
    }

    private void tile() {
      final int flags = this.unsigned8(0);
      final int pixelBytes = RfbGuard.this.bytesPerPixel();
      final int across = this.tilesAcross();
      final int tileWidth = Math.min(TILE_SIDE, this.rectangleWidth - (this.tile % across) * TILE_SIDE);
      final int tileHeight = Math.min(TILE_SIDE, this.rectangleHeight - (this.tile / across) * TILE_SIDE);
      this.tile++;

      if ((flags & TILE_RAW) != 0) {
        this.skip((long) tileWidth * tileHeight * pixelBytes, Then.TILE, Server.MESSAGE);
        return;
      }

      this.tileFlags = flags;

      final int background = (flags & TILE_BACKGROUND) != 0 ? pixelBytes : 0;
      final int foreground = (flags & TILE_FOREGROUND) != 0 ? pixelBytes : 0;
      final int colors = background + foreground;
      if ((flags & TILE_SUBRECTANGLES) != 0) {
        this.skip(colors, Then.STATE, Server.SUBRECTANGLES);
        return;
      }

      this.skip(colors, Then.TILE, Server.MESSAGE);
    }

    private void subrectangles() {
      final int count = this.unsigned8(0);
      final int color = (this.tileFlags & TILE_COLORED) != 0 ? RfbGuard.this.bytesPerPixel() : 0;
      final int each = color + HEXTILE_GEOMETRY;
      this.skip((long) count * each, Then.TILE, Server.MESSAGE);
    }

    private void zlib() throws IOException {
      final long length = this.unsigned32(0);
      final long pixels = (long) this.rectangleWidth * this.rectangleHeight;
      final long limit = pixels * RfbGuard.this.bytesPerPixel() + ZLIB_SLACK;
      if (length > limit) {
        throw RfbGuard.this.violation(
          "zlib data of %s bytes for %sx%s pixels".formatted(length, this.rectangleWidth, this.rectangleHeight)
        );
      }

      this.skip(length, Then.RECTANGLE, Server.MESSAGE);
    }

    private void colors() {
      final int count = this.unsigned16(COLOR_COUNT_OFFSET);
      final long bytes = (long) COLOR_ENTRY * count;
      this.skip(bytes, Then.STATE, Server.MESSAGE);
    }

    private void text() throws IOException {
      final long length = this.unsigned32(TEXT_LENGTH_OFFSET);
      this.skipBounded(length, MAXIMUM_TEXT, "clipboard text", Server.MESSAGE);
    }

    private int continueAfterVersion(final int at) throws IOException {
      final int minor = RfbGuard.this.minorVersion;
      if (minor < 0) {
        throw RfbGuard.this.violation("security data before the client chose a version");
      }

      final Server next = minor >= MINIMUM_MINOR_WITH_TYPE_LIST ? Server.SECURITY_COUNT : Server.SECURITY_TYPE;
      this.mode = Mode.FIELD;
      this.expect(next);

      return at;
    }

    private int continueAfterChoice(final int at) throws IOException {
      this.mode = Mode.FIELD;
      this.continueAfterSecurity(RfbGuard.this.securityType);

      return at;
    }

    private int unsigned8(final int offset) {
      return RfbGuard.this.unsigned8(this.field, offset);
    }

    private int unsigned16(final int offset) {
      return RfbGuard.this.unsigned16(this.field, offset);
    }

    private long unsigned32(final int offset) {
      return RfbGuard.this.unsigned32(this.field, offset);
    }
  }

  private enum ClientMode {
    FIELD,
    SKIP,
    UNTRACKED,
  }

  // A field of the client's side of the protocol, as far as the server's framing depends on it.
  private enum Client {
    VERSION,
    CHOICE,
    HANDSHAKE,
    MESSAGE,
    BODY,
  }

  private final class ClientParser {

    private static final int VNC_RESPONSE = 16;
    private static final int LOGON_RESPONSE = 8 + 256 + 64;
    private static final int CLIENT_INITIALIZATION = 1;

    private static final int SET_PIXEL_FORMAT = 0;
    private static final int SET_ENCODINGS = 2;
    private static final int UPDATE_REQUEST = 3;
    private static final int KEY = 4;
    private static final int POINTER = 5;
    private static final int CUT_TEXT = 6;

    private static final int PIXEL_FORMAT_BODY = 19;
    private static final int ENCODINGS_BODY = 3;
    private static final int UPDATE_REQUEST_BODY = 9;
    private static final int KEY_BODY = 7;
    private static final int POINTER_BODY = 5;
    private static final int CUT_TEXT_BODY = 7;
    private static final int ENCODING_BYTES = 4;

    private static final int MESSAGE_TYPE_BYTES = 1;
    private static final int CHOICE_BYTES = 1;

    private static final int PENDING_LENGTH = 0;
    private static final int UNTRACKED_BODY = -1;

    private static final int VERSION_MINOR_OFFSET = 8;
    private static final char ZERO_DIGIT = '0';
    private static final int MAXIMUM_DIGIT = 9;
    private static final int DECIMAL_RADIX = 10;

    private static final int PIXEL_FORMAT_BITS_OFFSET = 3;
    private static final int ENCODING_COUNT_OFFSET = 1;
    private static final int CUT_TEXT_LENGTH_OFFSET = 3;

    private final byte[] field;

    private ClientMode mode;
    private Client state;
    private int need;
    private int have;
    private long skip;
    private int type;

    ClientParser() {
      this.field = new byte[PIXEL_FORMAT_BODY];
      this.mode = ClientMode.FIELD;
      this.state = Client.VERSION;
      this.need = VERSION_BYTES;
    }

    void accept(final byte[] buffer, final int offset, final int length) {
      int at = offset;
      final int end = offset + length;
      while (at < end) {
        at = switch (this.mode) {
          case FIELD -> this.collect(buffer, at);
          case SKIP -> this.skipFrom(at, end);
          case UNTRACKED -> end;
        };
      }
    }

    private int collect(final byte[] buffer, final int at) {
      if (this.state == Client.HANDSHAKE && this.need == PENDING_LENGTH) {
        this.need = this.response() + CLIENT_INITIALIZATION;
      }

      if (this.have < this.field.length) {
        this.field[this.have] = buffer[at];
      }
      this.have++;

      if (this.have == this.need) {
        this.complete();
      }

      return at + 1;
    }

    private int response() {
      return switch (RfbGuard.this.securityType) {
        case VNC -> VNC_RESPONSE;
        case MS_LOGON -> LOGON_RESPONSE;
        default -> 0;
      };
    }

    private void complete() {
      final Runnable step = switch (this.state) {
        case VERSION -> this::version;
        case CHOICE -> this::choice;
        case HANDSHAKE -> this::expectMessage;
        case MESSAGE -> this::message;
        case BODY -> this::body;
      };
      step.run();
    }

    private void version() {
      final int minor = this.parseMinorVersion();
      if (minor == UNKNOWN) {
        this.mode = ClientMode.UNTRACKED;
        return;
      }

      RfbGuard.this.minorVersion = minor;

      if (minor >= MINIMUM_MINOR_WITH_TYPE_LIST) {
        this.expect(Client.CHOICE, CHOICE_BYTES);
        return;
      }

      this.expect(Client.HANDSHAKE, PENDING_LENGTH);
    }

    private int parseMinorVersion() {
      int minor = 0;
      for (int offset = VERSION_MINOR_OFFSET; offset < VERSION_BYTES - 1; offset++) {
        final int digit = this.field[offset] - ZERO_DIGIT;
        if (digit < 0 || digit > MAXIMUM_DIGIT) {
          return UNKNOWN;
        }
        minor = minor * DECIMAL_RADIX + digit;
      }

      return minor;
    }

    private void choice() {
      RfbGuard.this.securityType = this.unsigned8(0);
      this.expect(Client.HANDSHAKE, PENDING_LENGTH);
    }

    private void message() {
      this.type = this.unsigned8(0);

      final int body = switch (this.type) {
        case SET_PIXEL_FORMAT -> PIXEL_FORMAT_BODY;
        case SET_ENCODINGS -> ENCODINGS_BODY;
        case UPDATE_REQUEST -> UPDATE_REQUEST_BODY;
        case KEY -> KEY_BODY;
        case POINTER -> POINTER_BODY;
        case CUT_TEXT -> CUT_TEXT_BODY;
        default -> UNTRACKED_BODY;
      };
      if (body == UNTRACKED_BODY) {
        this.mode = ClientMode.UNTRACKED;
        return;
      }

      this.expect(Client.BODY, body);
    }

    private void body() {
      switch (this.type) {
        case SET_PIXEL_FORMAT -> this.pixelFormat();
        case SET_ENCODINGS -> this.encodings();
        case CUT_TEXT -> this.cutText();
        default -> this.expectMessage();
      }
    }

    private void pixelFormat() {
      // three bytes of padding, then the pixel format, whose first byte is its bits per pixel
      final int bitsPerPixel = this.unsigned8(PIXEL_FORMAT_BITS_OFFSET);
      if (SUPPORTED_BITS_PER_PIXEL.contains(bitsPerPixel)) {
        RfbGuard.this.clientBytesPerPixel = bitsPerPixel / Byte.SIZE;
      }

      this.expectMessage();
    }

    private void encodings() {
      final int count = this.unsigned16(ENCODING_COUNT_OFFSET);
      final long bytes = (long) ENCODING_BYTES * count;
      this.skipThenMessage(bytes);
    }

    private void cutText() {
      final long length = this.unsigned32(CUT_TEXT_LENGTH_OFFSET);
      this.skipThenMessage(length);
    }

    private void skipThenMessage(final long bytes) {
      if (bytes == 0) {
        this.expectMessage();
        return;
      }

      this.skip = bytes;
      this.mode = ClientMode.SKIP;
    }

    private int skipFrom(final int at, final int end) {
      final long taken = Math.min(this.skip, end - at);
      this.skip -= taken;

      if (this.skip == 0) {
        this.mode = ClientMode.FIELD;
        this.expectMessage();
      }

      return at + (int) taken;
    }

    private void expectMessage() {
      this.expect(Client.MESSAGE, MESSAGE_TYPE_BYTES);
    }

    private void expect(final Client next, final int bytes) {
      this.state = next;
      this.have = 0;
      this.need = bytes;
    }

    private int unsigned8(final int offset) {
      return RfbGuard.this.unsigned8(this.field, offset);
    }

    private int unsigned16(final int offset) {
      return RfbGuard.this.unsigned16(this.field, offset);
    }

    private long unsigned32(final int offset) {
      return RfbGuard.this.unsigned32(this.field, offset);
    }
  }
}
