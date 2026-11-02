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
package me.brandonli.mcav.browser;

import com.google.common.annotations.VisibleForTesting;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.IntPredicate;
import org.checkerframework.checker.nullness.qual.Nullable;

// Answers X connections for JCEF, which aborts the helper without a display although CEF draws off-screen. Listens on
// loopback only, admits only clients with the cookie of its authority file, and bounds requests, connections and atoms.
final class NullDisplay implements AutoCloseable {

  static final int X11_BASE_PORT = 6000;

  static final String AUTHORITY_FILE = "Xauthority";

  static final int MAXIMUM_REQUEST_UNITS = 16_384;

  static final int MAXIMUM_CONNECTIONS = 16;

  // a new client ends the oldest pending one, so clients without the cookie cannot crowd out one that has it
  static final int MAXIMUM_PENDING = 256;

  // so a client without the cookie cannot hold a connection; an introduced one may stay quiet
  private static final int SETUP_TIMEOUT_MILLISECONDS = 10_000;

  static final int MAXIMUM_ATOMS = 4_096;

  static final int MAXIMUM_ATOM_BYTES = 1 << 20;

  static final int COOKIE_BYTES = 16;

  static final int CREATE_WINDOW = 1;
  static final int GET_WINDOW_ATTRIBUTES = 3;
  static final int GET_GEOMETRY = 14;
  static final int QUERY_TREE = 15;
  static final int INTERN_ATOM = 16;
  static final int GET_ATOM_NAME = 17;
  static final int GET_PROPERTY = 20;
  static final int QUERY_POINTER = 38;
  static final int GET_INPUT_FOCUS = 43;
  static final int QUERY_EXTENSION = 98;
  static final int LIST_EXTENSIONS = 99;
  static final int GET_KEYBOARD_MAPPING = 101;
  static final int GET_MODIFIER_MAPPING = 119;

  static final int BAD_REQUEST = 1;
  static final int BAD_ATOM = 5;
  static final int BAD_ALLOC = 11;
  static final int BAD_LENGTH = 16;
  static final int BAD_IMPLEMENTATION = 17;

  // core requests with a reply (X Window System Protocol, chapter 9)
  private static final Set<Integer> REPLYING = Set.of(
    3,
    14,
    15,
    16,
    17,
    20,
    21,
    23,
    26,
    31,
    38,
    39,
    40,
    43,
    44,
    47,
    48,
    49,
    50,
    52,
    73,
    83,
    84,
    85,
    86,
    87,
    91,
    92,
    97,
    98,
    99,
    101,
    103,
    106,
    108,
    110,
    116,
    117,
    118,
    119
  );

  // numbered from 1 (X Window System Protocol, appendix B)
  private static final List<String> PREDEFINED_ATOMS = List.of(
    "PRIMARY",
    "SECONDARY",
    "ARC",
    "ATOM",
    "BITMAP",
    "CARDINAL",
    "COLORMAP",
    "CURSOR",
    "CUT_BUFFER0",
    "CUT_BUFFER1",
    "CUT_BUFFER2",
    "CUT_BUFFER3",
    "CUT_BUFFER4",
    "CUT_BUFFER5",
    "CUT_BUFFER6",
    "CUT_BUFFER7",
    "DRAWABLE",
    "FONT",
    "INTEGER",
    "PIXMAP",
    "POINT",
    "RECTANGLE",
    "RESOURCE_MANAGER",
    "RGB_COLOR_MAP",
    "RGB_BEST_MAP",
    "RGB_BLUE_MAP",
    "RGB_DEFAULT_MAP",
    "RGB_GRAY_MAP",
    "RGB_GREEN_MAP",
    "RGB_RED_MAP",
    "STRING",
    "VISUALID",
    "WINDOW",
    "WM_COMMAND",
    "WM_HINTS",
    "WM_CLIENT_MACHINE",
    "WM_ICON_NAME",
    "WM_ICON_SIZE",
    "WM_NAME",
    "WM_NORMAL_HINTS",
    "WM_SIZE_HINTS",
    "WM_ZOOM_HINTS",
    "MIN_SPACE",
    "NORM_SPACE",
    "MAX_SPACE",
    "END_SPACE",
    "SUPERSCRIPT_X",
    "SUPERSCRIPT_Y",
    "SUBSCRIPT_X",
    "SUBSCRIPT_Y",
    "UNDERLINE_POSITION",
    "UNDERLINE_THICKNESS",
    "STRIKEOUT_ASCENT",
    "STRIKEOUT_DESCENT",
    "ITALIC_ANGLE",
    "X_HEIGHT",
    "QUAD_WIDTH",
    "WEIGHT",
    "POINT_SIZE",
    "RESOLUTION",
    "COPYRIGHT",
    "NOTICE",
    "FONT_NAME",
    "FAMILY_NAME",
    "FULL_NAME",
    "CAP_HEIGHT",
    "WM_CLASS",
    "WM_TRANSIENT_FOR"
  );

  private static final String COOKIE_NAME = "MIT-MAGIC-COOKIE-1";
  private static final int FAMILY_WILD = 0xFFFF;
  private static final int MAXIMUM_AUTHORIZATION_BYTES = 256;

  private static final int ROOT = 0x100;
  private static final int COLORMAP = 0x20;
  private static final int VISUAL = 0x21;
  private static final int SCREEN_WIDTH = 1920;
  private static final int SCREEN_HEIGHT = 1080;
  private static final int MINIMUM_KEY_CODE = 8;
  private static final int MAXIMUM_KEY_CODE = 255;
  private static final byte[] VENDOR = "mcav null display".getBytes(StandardCharsets.US_ASCII);

  private static final String POSIX_ATTRIBUTE_VIEW = "posix";
  private static final String OWNER_ONLY_PERMISSIONS = "rw-------";

  private static final byte[] LOOPBACK_ADDRESS = { 127, 0, 0, 1 };
  private static final int MAXIMUM_BIND_ATTEMPTS = 64;
  private static final String DISPLAY_SEPARATOR = ":";

  private static final int UNSIGNED_BYTE_MASK = 0xFF;
  private static final int UNSIGNED_SHORT_MASK = 0xFFFF;

  private static final int UNIT_BYTES = 4;
  private static final int MESSAGE_BYTES = 32;

  private static final int FIRST_EXTENSION_OPCODE = 128;

  private static final int LITTLE_ENDIAN_ORDER = 'l';
  private static final int BIG_ENDIAN_ORDER = 'B';

  private static final int SETUP_REQUEST_BYTES = 11;
  private static final int SETUP_REPLY_HEADER_BYTES = 8;
  private static final int SETUP_FIXED_BYTES = 32;

  private static final int SETUP_FAILED = 0;
  private static final int SETUP_SUCCESS = 1;

  private static final int X11_MAJOR_VERSION = 11;
  private static final int X11_MINOR_VERSION = 0;

  private static final int RELEASE_NUMBER = 1;
  private static final int RESOURCE_ID_BASE = 0x00400000;
  private static final int RESOURCE_ID_MASK = 0x001FFFFF;
  private static final int SCREEN_COUNT = 1;
  private static final int LEAST_SIGNIFICANT_FIRST = 0;
  private static final int MOST_SIGNIFICANT_FIRST = 1;
  private static final int SCANLINE_BITS = 32;

  // depth and bits per pixel of each pixmap format
  private static final int[][] PIXMAP_FORMATS = { { 1, 1 }, { 24, 32 }, { 32, 32 } };
  private static final int PIXMAP_FORMAT_BYTES = 8;
  private static final int PIXMAP_FORMAT_PADDING_BYTES = 5;

  private static final int SCREEN_BYTES = 40;
  private static final int DEPTH_BYTES = 8;
  private static final int VISUAL_BYTES = 24;
  private static final int WHITE_PIXEL = 0xFFFFFF;
  private static final int BLACK_PIXEL = 0;
  private static final int SCREEN_WIDTH_MILLIMETERS = 508;
  private static final int SCREEN_HEIGHT_MILLIMETERS = 286;
  private static final int INSTALLED_COLORMAPS = 1;
  private static final int ROOT_DEPTH = 24;
  private static final int VISUAL_LESS_DEPTH = 32;
  private static final int DEPTH_COUNT = 2;
  private static final int VISUALS_PER_ROOT_DEPTH = 1;

  private static final int TRUE_COLOR = 4;
  private static final int BITS_PER_RGB_VALUE = 8;
  private static final int COLORMAP_ENTRIES = 256;
  private static final int RED_MASK = 0xFF0000;
  private static final int GREEN_MASK = 0x00FF00;
  private static final int BLUE_MASK = 0x0000FF;

  private static final int REPLY = 1;
  private static final int ERROR = 0;
  private static final int SEQUENCE_OFFSET = 2;
  private static final int LENGTH_OFFSET = 4;
  private static final int REPLY_BODY_OFFSET = 8;
  private static final int ERROR_VALUE_OFFSET = 4;
  private static final int ERROR_OPCODE_OFFSET = 10;

  private static final int MODIFIER_COUNT = 8;
  private static final int KEY_CODES_PER_MODIFIER = 1;
  private static final int KEY_SYMBOLS_PER_KEY_CODE = 1;

  private static final int WINDOW_ATTRIBUTES_EXTRA_BYTES = 12;
  private static final int WINDOW_CLASS_OFFSET = 12;
  private static final int WINDOW_COLORMAP_OFFSET = 28;
  private static final int INPUT_OUTPUT = 1;

  private static final int GEOMETRY_WIDTH_OFFSET = 16;
  private static final int GEOMETRY_HEIGHT_OFFSET = 18;

  private static final int SAME_SCREEN = 1;

  private final ServerSocket server;
  private final byte[] cookie;
  private final Atoms atoms;
  private final Semaphore slots;
  private final Set<Socket> clients;
  private final Deque<Socket> pending;
  private final int setupTimeoutMilliseconds;

  private NullDisplay(final ServerSocket server, final byte[] cookie, final int setupTimeoutMilliseconds) {
    this.server = server;
    this.setupTimeoutMilliseconds = setupTimeoutMilliseconds;
    this.cookie = cookie.clone();
    this.atoms = new Atoms();
    this.slots = new Semaphore(MAXIMUM_CONNECTIONS);
    this.clients = ConcurrentHashMap.newKeySet();
    this.pending = new ArrayDeque<>();
  }

  static NullDisplay start(final Path authority) throws IOException {
    return start(authority, SETUP_TIMEOUT_MILLISECONDS);
  }

  @VisibleForTesting
  static NullDisplay start(final Path authority, final int setupTimeoutMilliseconds) throws IOException {
    final ServerSocket server = bind(NullDisplay::isDisplayPort);
    try {
      final byte[] cookie = createCookie();
      writeAuthority(authority, cookie);
      final NullDisplay display = new NullDisplay(server, cookie, setupTimeoutMilliseconds);
      final Thread thread = new Thread(display::acceptConnections, "mcav-browser-null-display");
      thread.setDaemon(true);
      thread.start();
      return display;
    } catch (final IOException | RuntimeException exception) {
      server.close();
      throw exception;
    }
  }

  // an unusable port stays bound until the search ends, so the system does not hand it out again
  @VisibleForTesting
  static ServerSocket bind(final IntPredicate usable) throws IOException {
    // a display name cannot hold an IPv6 address
    final InetAddress loopback = InetAddress.getByAddress(LOOPBACK_ADDRESS);
    final List<ServerSocket> unusable = new ArrayList<>();
    try {
      return bindFirstUsable(usable, loopback, unusable);
    } finally {
      for (final ServerSocket socket : unusable) {
        socket.close();
      }
    }
  }

  private static ServerSocket bindFirstUsable(final IntPredicate usable, final InetAddress loopback, final List<ServerSocket> unusable)
    throws IOException {
    while (unusable.size() < MAXIMUM_BIND_ATTEMPTS) {
      final ServerSocket candidate = new ServerSocket(0, MAXIMUM_CONNECTIONS, loopback);
      final int port = candidate.getLocalPort();
      if (usable.test(port)) {
        return candidate;
      }
      unusable.add(candidate);
    }

    final String message = "The system gives out no port of at least %s for the display!".formatted(X11_BASE_PORT);
    throw new IOException(message);
  }

  // X11 over TCP connects to 6000 plus the display number
  @VisibleForTesting
  static boolean isDisplayPort(final int port) {
    return port >= X11_BASE_PORT;
  }

  @VisibleForTesting
  static byte[] createCookie() {
    final SecureRandom random = new SecureRandom();
    final byte[] cookie = new byte[COOKIE_BYTES];
    random.nextBytes(cookie);

    return cookie;
  }

  @VisibleForTesting
  static void writeAuthority(final Path authority, final byte[] cookie) throws IOException {
    final byte[] entry = createAuthorityEntry(cookie);
    final FileSystem fileSystem = authority.getFileSystem();
    final Set<String> views = fileSystem.supportedFileAttributeViews();
    final boolean supportsPosix = views.contains(POSIX_ATTRIBUTE_VIEW);
    if (supportsPosix) {
      Files.createFile(authority, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY_PERMISSIONS)));
    } else {
      Files.createFile(authority);
    }

    Files.write(authority, entry);
  }

  // family, address, display number, method name and cookie, each after a big-endian 16-bit length; this family matches
  // every host and the empty display number every display
  @VisibleForTesting
  static byte[] createAuthorityEntry(final byte[] cookie) {
    final byte[] name = COOKIE_NAME.getBytes(StandardCharsets.US_ASCII);
    final ByteBuffer entry = ByteBuffer.allocate(
      Short.BYTES + Short.BYTES + Short.BYTES + Short.BYTES + name.length + Short.BYTES + cookie.length
    );
    entry.putShort((short) FAMILY_WILD);
    entry.putShort((short) 0);
    entry.putShort((short) 0);
    entry.putShort((short) name.length);
    entry.put(name);
    entry.putShort((short) cookie.length);
    entry.put(cookie);

    return entry.array();
  }

  @VisibleForTesting
  int countClients() {
    return this.clients.size();
  }

  static @Nullable String nameOf(final @Nullable NullDisplay display) {
    return display == null ? null : display.getDisplay();
  }

  private void acceptConnections() {
    while (true) {
      final Socket client;
      try {
        client = this.server.accept();
      } catch (final IOException exception) {
        return;
      }
      this.clients.add(client);
      final Socket evicted = this.admit(client);
      if (evicted != null) {
        this.closeQuietly(evicted);
      }
      Thread.ofVirtual()
        .name("mcav-browser-null-display-client")
        .start(() -> this.serve(client));
    }
  }

  private @Nullable Socket admit(final Socket client) {
    synchronized (this.pending) {
      this.pending.addLast(client);
      return this.pending.size() > MAXIMUM_PENDING ? this.pending.removeFirst() : null;
    }
  }

  private void removeFromPending(final Socket client) {
    synchronized (this.pending) {
      this.pending.remove(client);
    }
  }

  private void serve(final Socket client) {
    boolean slotAcquired = false;
    try (client) {
      client.setSoTimeout(this.setupTimeoutMilliseconds);
      final InputStream clientInput = client.getInputStream();
      final DataInputStream input = new DataInputStream(new BufferedInputStream(clientInput));
      final OutputStream clientOutput = client.getOutputStream();
      final OutputStream output = new BufferedOutputStream(clientOutput);
      final ByteOrder order = answerSetup(input, output, this.cookie);
      this.removeFromPending(client);
      slotAcquired = this.slots.tryAcquire();
      if (slotAcquired) {
        client.setSoTimeout(0);
        serveRequests(input, output, order, this.atoms);
      }
    } catch (final IOException exception) {
      // the client left or broke the protocol: its connection ends
    } finally {
      this.removeFromPending(client);
      this.clients.remove(client);
      if (slotAcquired) {
        this.slots.release();
      }
    }
  }

  @VisibleForTesting
  static void serve(final InputStream clientInput, final OutputStream output, final byte[] cookie, final Atoms atoms) throws IOException {
    final DataInputStream input = new DataInputStream(clientInput);
    final ByteOrder order = answerSetup(input, output, cookie);
    serveRequests(input, output, order, atoms);
  }

  private static void serveRequests(final DataInputStream input, final OutputStream output, final ByteOrder order, final Atoms atoms)
    throws IOException {
    int sequence = 0;
    while (true) {
      final int opcode = input.read();
      if (opcode < 0) {
        return;
      }
      final int data = input.readUnsignedByte();
      final byte[] lengthBytes = new byte[Short.BYTES];
      input.readFully(lengthBytes);
      final ByteBuffer lengthBuffer = ByteBuffer.wrap(lengthBytes).order(order);
      final int units = lengthBuffer.getShort() & UNSIGNED_SHORT_MASK;
      if (units == 0 || units > MAXIMUM_REQUEST_UNITS) {
        // no BIG-REQUESTS extension, so no longer requests
        final String message = "A request of %s units must have 1 to %s units!".formatted(units, MAXIMUM_REQUEST_UNITS);
        throw new ProtocolException(message);
      }
      final byte[] body = new byte[units * UNIT_BYTES - UNIT_BYTES];
      input.readFully(body);
      sequence = (sequence + 1) & UNSIGNED_SHORT_MASK;
      final ByteBuffer request = ByteBuffer.wrap(body).order(order);
      final ByteBuffer answer = answer(opcode, data, request, sequence, order, atoms);
      if (answer != null) {
        send(output, answer);
      }
    }
  }

  private static ByteOrder answerSetup(final DataInputStream input, final OutputStream output, final byte[] cookie) throws IOException {
    final int orderByte = input.readUnsignedByte();
    final ByteOrder order = switch (orderByte) {
      case LITTLE_ENDIAN_ORDER -> ByteOrder.LITTLE_ENDIAN;
      case BIG_ENDIAN_ORDER -> ByteOrder.BIG_ENDIAN;
      default -> {
        final String message = "Byte order %s of the connection setup is unknown!".formatted(orderByte);
        throw new ProtocolException(message);
      }
    };
    final byte[] head = new byte[SETUP_REQUEST_BYTES];
    input.readFully(head);
    final ByteBuffer setup = ByteBuffer.wrap(head).order(order);
    setup.get();
    final int major = setup.getShort() & UNSIGNED_SHORT_MASK;
    setup.getShort();
    final int nameLength = setup.getShort() & UNSIGNED_SHORT_MASK;
    final int dataLength = setup.getShort() & UNSIGNED_SHORT_MASK;
    if (nameLength > MAXIMUM_AUTHORIZATION_BYTES || dataLength > MAXIMUM_AUTHORIZATION_BYTES) {
      final String message = "The authorization of the connection setup is longer than %s bytes!".formatted(MAXIMUM_AUTHORIZATION_BYTES);
      throw new ProtocolException(message);
    }

    final byte[] name = readPadded(input, nameLength);
    final byte[] presented = readPadded(input, dataLength);
    final boolean named = COOKIE_NAME.equals(new String(name, StandardCharsets.US_ASCII));
    // constant time, so timing cannot reveal the cookie
    final boolean matches = MessageDigest.isEqual(presented, cookie);
    if (major != X11_MAJOR_VERSION || !named || !matches) {
      final String reason = major != X11_MAJOR_VERSION ? "only X11 is spoken here" : "the cookie of the display is missing";
      writeRefusal(output, order, reason);
      final String message = "Because %s, a client was refused!".formatted(reason);
      throw new ProtocolException(message);
    }

    final ByteBuffer accepted = createSetupReply(order);
    send(output, accepted);

    return order;
  }

  private static void send(final OutputStream output, final ByteBuffer message) throws IOException {
    output.write(message.array(), message.arrayOffset(), message.limit());
    output.flush();
  }

  private static byte[] readPadded(final DataInputStream input, final int length) throws IOException {
    final byte[] value = new byte[length];
    input.readFully(value);
    input.readFully(new byte[countPaddingBytes(length)]);

    return value;
  }

  private static int countPaddingBytes(final int length) {
    return (UNIT_BYTES - (length % UNIT_BYTES)) % UNIT_BYTES;
  }

  private static void writeRefusal(final OutputStream output, final ByteOrder order, final String reason) throws IOException {
    final byte[] text = reason.getBytes(StandardCharsets.US_ASCII);
    final int padded = text.length + countPaddingBytes(text.length);
    final ByteBuffer refusal = ByteBuffer.allocate(SETUP_REPLY_HEADER_BYTES + padded).order(order);
    refusal.put((byte) SETUP_FAILED);
    refusal.put((byte) text.length);
    refusal.putShort((short) X11_MAJOR_VERSION);
    refusal.putShort((short) X11_MINOR_VERSION);
    refusal.putShort((short) (padded / UNIT_BYTES));
    refusal.put(text);
    output.write(refusal.array());
    output.flush();
  }

  @VisibleForTesting
  static ByteBuffer createSetupReply(final ByteOrder order) {
    final int vendorPadded = VENDOR.length + countPaddingBytes(VENDOR.length);
    final int formats = PIXMAP_FORMATS.length * PIXMAP_FORMAT_BYTES;
    final int screen = SCREEN_BYTES + DEPTH_BYTES + VISUAL_BYTES + DEPTH_BYTES;
    final int additional = SETUP_FIXED_BYTES + vendorPadded + formats + screen;
    final ByteBuffer reply = ByteBuffer.allocate(SETUP_REPLY_HEADER_BYTES + additional).order(order);
    reply.put((byte) SETUP_SUCCESS);
    reply.put((byte) 0);
    reply.putShort((short) X11_MAJOR_VERSION);
    reply.putShort((short) X11_MINOR_VERSION);
    reply.putShort((short) (additional / UNIT_BYTES));
    // release, resource base and mask, motion buffer, vendor length, request length, screens, formats, image order,
    // bitmap order, scanline unit and pad, keycodes
    reply.putInt(RELEASE_NUMBER);
    reply.putInt(RESOURCE_ID_BASE);
    reply.putInt(RESOURCE_ID_MASK);
    reply.putInt(0);
    reply.putShort((short) VENDOR.length);
    reply.putShort((short) MAXIMUM_REQUEST_UNITS);
    reply.put((byte) SCREEN_COUNT);
    reply.put((byte) PIXMAP_FORMATS.length);
    reply.put((byte) (order == ByteOrder.LITTLE_ENDIAN ? LEAST_SIGNIFICANT_FIRST : MOST_SIGNIFICANT_FIRST));
    reply.put((byte) LEAST_SIGNIFICANT_FIRST);
    reply.put((byte) SCANLINE_BITS);
    reply.put((byte) SCANLINE_BITS);
    reply.put((byte) MINIMUM_KEY_CODE);
    reply.put((byte) MAXIMUM_KEY_CODE);
    reply.putInt(0);
    reply.put(VENDOR);
    reply.put(new byte[vendorPadded - VENDOR.length]);
    for (final int[] format : PIXMAP_FORMATS) {
      reply.put((byte) format[0]);
      reply.put((byte) format[1]);
      reply.put((byte) SCANLINE_BITS);
      reply.put(new byte[PIXMAP_FORMAT_PADDING_BYTES]);
    }
    // the screen: root, colormap, white and black pixels, input masks, size in pixels and millimeters, installed
    // colormaps, root visual, backing stores, save unders, root depth and two depths
    reply.putInt(ROOT);
    reply.putInt(COLORMAP);
    reply.putInt(WHITE_PIXEL);
    reply.putInt(BLACK_PIXEL);
    reply.putInt(0);
    reply.putShort((short) SCREEN_WIDTH);
    reply.putShort((short) SCREEN_HEIGHT);
    reply.putShort((short) SCREEN_WIDTH_MILLIMETERS);
    reply.putShort((short) SCREEN_HEIGHT_MILLIMETERS);
    reply.putShort((short) INSTALLED_COLORMAPS);
    reply.putShort((short) INSTALLED_COLORMAPS);
    reply.putInt(VISUAL);
    reply.put((byte) 0);
    reply.put((byte) 0);
    reply.put((byte) ROOT_DEPTH);
    reply.put((byte) DEPTH_COUNT);
    // depth 24 with one TrueColor visual, then depth 32 without visuals
    reply.put((byte) ROOT_DEPTH);
    reply.put((byte) 0);
    reply.putShort((short) VISUALS_PER_ROOT_DEPTH);
    reply.putInt(0);
    reply.putInt(VISUAL);
    reply.put((byte) TRUE_COLOR);
    reply.put((byte) BITS_PER_RGB_VALUE);
    reply.putShort((short) COLORMAP_ENTRIES);
    reply.putInt(RED_MASK);
    reply.putInt(GREEN_MASK);
    reply.putInt(BLUE_MASK);
    reply.putInt(0);
    reply.put((byte) VISUAL_LESS_DEPTH);
    reply.put((byte) 0);
    reply.putShort((short) 0);
    reply.putInt(0);
    reply.flip();

    return reply;
  }

  @VisibleForTesting
  static @Nullable ByteBuffer answer(
    final int opcode,
    final int data,
    final ByteBuffer request,
    final int sequence,
    final ByteOrder order,
    final Atoms atoms
  ) {
    return switch (opcode) {
      case INTERN_ATOM -> answerInternAtom(data, request, sequence, order, atoms);
      case GET_ATOM_NAME -> answerGetAtomName(request, sequence, order, atoms);
      // no window has properties
      case GET_PROPERTY -> reply(sequence, order, 0, 0);
      // no extension is there
      case QUERY_EXTENSION -> reply(sequence, order, 0, 0);
      case LIST_EXTENSIONS -> reply(sequence, order, 0, 0);
      // the focus is None, reverting to None
      case GET_INPUT_FOCUS -> reply(sequence, order, 0, 0);
      case GET_KEYBOARD_MAPPING -> answerGetKeyboardMapping(request, sequence, order);
      // one key code per modifier, none of them set
      case GET_MODIFIER_MAPPING -> reply(sequence, order, KEY_CODES_PER_MODIFIER, MODIFIER_COUNT * KEY_CODES_PER_MODIFIER);
      case GET_WINDOW_ATTRIBUTES -> answerGetWindowAttributes(sequence, order);
      case GET_GEOMETRY -> answerGetGeometry(sequence, order);
      case QUERY_TREE -> answerQueryTree(sequence, order);
      case QUERY_POINTER -> answerQueryPointer(sequence, order);
      default -> answerUnsupported(opcode, sequence, order);
    };
  }

  private static @Nullable ByteBuffer answerUnsupported(final int opcode, final int sequence, final ByteOrder order) {
    if (opcode >= FIRST_EXTENSION_OPCODE) {
      // an extension, and none was announced
      return error(BAD_REQUEST, opcode, sequence, order, 0);
    }

    if (REPLYING.contains(opcode)) {
      // the client would wait forever for this reply
      return error(BAD_IMPLEMENTATION, opcode, sequence, order, 0);
    }

    return null;
  }

  private static ByteBuffer answerInternAtom(
    final int data,
    final ByteBuffer request,
    final int sequence,
    final ByteOrder order,
    final Atoms atoms
  ) {
    if (request.remaining() < UNIT_BYTES) {
      return error(BAD_LENGTH, INTERN_ATOM, sequence, order, 0);
    }

    final int length = request.getShort() & UNSIGNED_SHORT_MASK;
    request.getShort();

    if (length > request.remaining()) {
      return error(BAD_LENGTH, INTERN_ATOM, sequence, order, 0);
    }

    final byte[] name = new byte[length];
    request.get(name);
    final String text = new String(name, StandardCharsets.ISO_8859_1);
    final boolean onlyIfExists = data != 0;
    final int atom = onlyIfExists ? atoms.find(text) : atoms.intern(text);
    if (atom == 0 && !onlyIfExists) {
      return error(BAD_ALLOC, INTERN_ATOM, sequence, order, 0);
    }

    final ByteBuffer reply = reply(sequence, order, 0, 0);
    reply.putInt(REPLY_BODY_OFFSET, atom);

    return reply;
  }

  private static ByteBuffer answerGetAtomName(final ByteBuffer request, final int sequence, final ByteOrder order, final Atoms atoms) {
    if (request.remaining() < UNIT_BYTES) {
      return error(BAD_LENGTH, GET_ATOM_NAME, sequence, order, 0);
    }

    final int atom = request.getInt();
    final String name = atoms.name(atom);
    if (name == null) {
      return error(BAD_ATOM, GET_ATOM_NAME, sequence, order, atom);
    }

    final byte[] bytes = name.getBytes(StandardCharsets.ISO_8859_1);
    final ByteBuffer reply = reply(sequence, order, 0, bytes.length + countPaddingBytes(bytes.length));
    reply.putShort(REPLY_BODY_OFFSET, (short) bytes.length);
    reply.put(MESSAGE_BYTES, bytes);

    return reply;
  }

  private static ByteBuffer answerGetKeyboardMapping(final ByteBuffer request, final int sequence, final ByteOrder order) {
    // the first key code, the count and two unused bytes
    if (request.remaining() < UNIT_BYTES) {
      return error(BAD_LENGTH, GET_KEYBOARD_MAPPING, sequence, order, 0);
    }

    request.get();
    final int count = request.get() & UNSIGNED_BYTE_MASK;
    // every key symbol is NoSymbol
    return reply(sequence, order, KEY_SYMBOLS_PER_KEY_CODE, Integer.BYTES * KEY_SYMBOLS_PER_KEY_CODE * count);
  }

  private static ByteBuffer answerGetWindowAttributes(final int sequence, final ByteOrder order) {
    // backing store NotUseful, the visual, class InputOutput, gravities, planes, pixel, save under, map installed,
    // map state Unmapped, override redirect, colormap and event masks
    final ByteBuffer reply = reply(sequence, order, 0, WINDOW_ATTRIBUTES_EXTRA_BYTES);
    reply.putInt(REPLY_BODY_OFFSET, VISUAL);
    reply.putShort(WINDOW_CLASS_OFFSET, (short) INPUT_OUTPUT);
    reply.putInt(WINDOW_COLORMAP_OFFSET, COLORMAP);

    return reply;
  }

  private static ByteBuffer answerGetGeometry(final int sequence, final ByteOrder order) {
    // the depth in the second byte, then the root, the position, the size and the border width
    final ByteBuffer reply = reply(sequence, order, ROOT_DEPTH, 0);
    reply.putInt(REPLY_BODY_OFFSET, ROOT);
    reply.putShort(GEOMETRY_WIDTH_OFFSET, (short) SCREEN_WIDTH);
    reply.putShort(GEOMETRY_HEIGHT_OFFSET, (short) SCREEN_HEIGHT);

    return reply;
  }

  private static ByteBuffer answerQueryTree(final int sequence, final ByteOrder order) {
    // the root, no parent and no children
    final ByteBuffer reply = reply(sequence, order, 0, 0);
    reply.putInt(REPLY_BODY_OFFSET, ROOT);

    return reply;
  }

  private static ByteBuffer answerQueryPointer(final int sequence, final ByteOrder order) {
    // on this screen, over the root at the origin, with no button held
    final ByteBuffer reply = reply(sequence, order, SAME_SCREEN, 0);
    reply.putInt(REPLY_BODY_OFFSET, ROOT);

    return reply;
  }

  private static ByteBuffer reply(final int sequence, final ByteOrder order, final int data, final int extra) {
    final ByteBuffer reply = ByteBuffer.allocate(MESSAGE_BYTES + extra).order(order);
    reply.put(0, (byte) REPLY);
    reply.put(1, (byte) data);
    reply.putShort(SEQUENCE_OFFSET, (short) sequence);
    reply.putInt(LENGTH_OFFSET, extra / UNIT_BYTES);

    return reply;
  }

  private static ByteBuffer error(final int code, final int opcode, final int sequence, final ByteOrder order, final int value) {
    final ByteBuffer error = ByteBuffer.allocate(MESSAGE_BYTES).order(order);
    error.put(0, (byte) ERROR);
    error.put(1, (byte) code);
    error.putShort(SEQUENCE_OFFSET, (short) sequence);
    error.putInt(ERROR_VALUE_OFFSET, value);
    error.put(ERROR_OPCODE_OFFSET, (byte) opcode);

    return error;
  }

  @Override
  public void close() {
    this.closeQuietly(this.server);
    for (final Socket client : this.clients) {
      this.closeQuietly(client);
    }
  }

  private void closeQuietly(final Closeable closeable) {
    // not HelperSession's: the helper runs without MCAV's other modules
    NetworkGuard.closeQuietly(closeable);
  }

  String getDisplay() {
    final InetAddress address = this.server.getInetAddress();
    final String host = address.getHostAddress();
    return host + DISPLAY_SEPARATOR + (this.server.getLocalPort() - X11_BASE_PORT);
  }

  static final class Atoms {

    private final Map<String, Integer> ids;
    private final List<String> names;

    private long bytes;

    Atoms() {
      this.ids = new ConcurrentHashMap<>();
      this.names = new ArrayList<>();
      for (final String name : PREDEFINED_ATOMS) {
        this.intern(name);
      }
    }

    synchronized int find(final String name) {
      return this.ids.getOrDefault(name, 0);
    }

    synchronized int intern(final String name) {
      final Integer known = this.ids.get(name);
      if (known != null) {
        return known;
      }

      if (this.names.size() >= MAXIMUM_ATOMS || this.bytes + name.length() > MAXIMUM_ATOM_BYTES) {
        return 0;
      }

      this.bytes += name.length();
      this.names.add(name);
      final int atom = this.names.size();
      this.ids.put(name, atom);
      return atom;
    }

    synchronized @Nullable String name(final int atom) {
      if (atom < 1 || atom > this.names.size()) {
        return null;
      }

      return this.names.get(atom - 1);
    }
  }
}
