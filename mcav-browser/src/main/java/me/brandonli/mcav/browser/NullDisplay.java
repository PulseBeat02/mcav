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

/**
 * An X display that shows nothing, for JCEF on Linux.
 *
 * <p>When CEF has started, JCEF creates a window of one pixel on the X display of CEF (its {@code TempWindowX11}), and
 * aborts the helper if there is no display, although CEF itself draws off-screen on Chromium's headless Ozone platform
 * and never uses X. So the helper answers the X connections itself, instead of an X server: the connection setup, with
 * one TrueColor screen and no extensions, atoms, empty properties, an empty keyboard, and the geometry of the screen.
 * Every other request that has no reply is discarded, and one that needs a reply gets an error. Nothing is drawn.
 *
 * <p>The display listens on the loopback interface, on a port of at least 6000, so the display number is the port minus
 * 6000 (X11 over TCP), and it lets in only clients that present the random cookie of the authority file it writes.
 * Requests are bounded in size, and so are the connections and the atoms.
 */
final class NullDisplay implements AutoCloseable {

  /**
   * The port of display 0 of X11 over TCP.
   */
  static final int X11_BASE_PORT = 6000;

  /**
   * The name of the X authority file in the folder of a session, which the helper writes for its display.
   */
  static final String AUTHORITY_FILE = "Xauthority";

  /**
   * The largest request a client may send, in units of four bytes, as the connection setup announces it.
   */
  static final int MAX_REQUEST_UNITS = 16_384;

  /**
   * The most connections the display serves at once.
   */
  static final int MAX_CONNECTIONS = 16;

  /**
   * The most clients that have not introduced themselves yet; a new one ends the connection of the oldest, so clients
   * without the cookie can never keep one that has it out.
   */
  static final int MAX_PENDING = 256;

  /**
   * How long a client may take to introduce itself, in milliseconds, so a client without the cookie cannot hold one
   * of the {@value #MAX_CONNECTIONS} connections; one that introduced itself may stay quiet as long as it likes.
   */
  static final int SETUP_TIMEOUT_MILLIS = 10_000;

  /**
   * The most atoms a display names, the predefined ones included.
   */
  static final int MAX_ATOMS = 4_096;

  /**
   * The most bytes the names of the atoms of a display hold together.
   */
  static final int MAX_ATOM_BYTES = 1 << 20;

  /**
   * The size of the cookie, in bytes.
   */
  static final int COOKIE_BYTES = 16;

  // the X11 codes of requests, replies and errors this display uses
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

  // the core requests that have a reply, which a client waits for (X Window System Protocol, chapter 9)
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
  // the predefined atoms of the core protocol, numbered from 1 (X Window System Protocol, appendix B)
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
  private static final int MAX_AUTHORIZATION_BYTES = 256;
  private static final int ROOT = 0x100;
  private static final int COLORMAP = 0x20;
  private static final int VISUAL = 0x21;
  private static final int SCREEN_WIDTH = 1920;
  private static final int SCREEN_HEIGHT = 1080;
  private static final int MIN_KEYCODE = 8;
  private static final int MAX_KEYCODE = 255;
  private static final byte[] VENDOR = "mcav null display".getBytes(StandardCharsets.US_ASCII);

  private final ServerSocket server;
  private final byte[] cookie;
  private final Atoms atoms;
  private final Semaphore slots;
  private final Set<Socket> clients;
  private final Deque<Socket> pending;
  private final int setupTimeoutMillis;

  private NullDisplay(final ServerSocket server, final byte[] cookie, final int setupTimeoutMillis) {
    this.server = server;
    this.setupTimeoutMillis = setupTimeoutMillis;
    this.cookie = cookie.clone();
    this.atoms = new Atoms();
    this.slots = new Semaphore(MAX_CONNECTIONS);
    this.clients = ConcurrentHashMap.newKeySet();
    this.pending = new ArrayDeque<>();
  }

  /**
   * Starts a display and writes its authority file, which clients find through {@code XAUTHORITY}.
   *
   * @param authority the authority file to write, which must not exist yet
   * @return the running display
   * @throws IOException if no port is free or the file cannot be written
   */
  static NullDisplay start(final Path authority) throws IOException {
    return start(authority, SETUP_TIMEOUT_MILLIS);
  }

  /**
   * Starts a display with another time for the setup, so tests need not wait for it.
   *
   * @param authority          the authority file to write
   * @param setupTimeoutMillis how long a client may take to introduce itself
   * @return the running display
   * @throws IOException if the display cannot listen or the file cannot be written
   */
  @VisibleForTesting
  static NullDisplay start(final Path authority, final int setupTimeoutMillis) throws IOException {
    final ServerSocket server = bind(NullDisplay::isDisplayPort);
    try {
      final byte[] cookie = createCookie();
      writeAuthority(authority, cookie);
      final NullDisplay display = new NullDisplay(server, cookie, setupTimeoutMillis);
      final Thread thread = new Thread(display::acceptConnections, "mcav-browser-null-display");
      thread.setDaemon(true);
      thread.start();
      return display;
    } catch (final IOException | RuntimeException exception) {
      server.close();
      throw exception;
    }
  }

  /**
   * Binds a port on the IPv4 loopback interface that a display number can name. The system picks ephemeral ports far
   * above {@value #X11_BASE_PORT}; a port it may not use is kept bound until the search ends, so it is not picked again.
   *
   * @param usable whether a display can use a port
   * @return the listening socket
   * @throws IOException if no usable port is bound after 64 tries
   */
  @VisibleForTesting
  static ServerSocket bind(final IntPredicate usable) throws IOException {
    // the name of a display holds an IPv4 address or a host name, never an IPv6 address
    final InetAddress loopback = InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 });
    final List<ServerSocket> unusable = new ArrayList<>();
    try {
      while (unusable.size() < 64) {
        // this constructor closes the socket itself when it cannot bind
        final ServerSocket candidate = new ServerSocket(0, MAX_CONNECTIONS, loopback);
        if (usable.test(candidate.getLocalPort())) {
          return candidate;
        }
        unusable.add(candidate);
      }
      throw new IOException("The system gives out no port of at least " + X11_BASE_PORT + " for the display");
    } finally {
      for (final ServerSocket socket : unusable) {
        socket.close();
      }
    }
  }

  /**
   * Checks whether a display number can name a port: X11 over TCP connects to 6000 plus the number.
   *
   * @param port the port
   * @return true if it is at least {@value #X11_BASE_PORT}
   */
  @VisibleForTesting
  static boolean isDisplayPort(final int port) {
    return port >= X11_BASE_PORT;
  }

  /**
   * Creates the secret a client of the display must present.
   *
   * @return {@value #COOKIE_BYTES} random bytes
   */
  @VisibleForTesting
  static byte[] createCookie() {
    final SecureRandom random = new SecureRandom();
    final byte[] cookie = new byte[COOKIE_BYTES];
    random.nextBytes(cookie);
    return cookie;
  }

  /**
   * Writes an X authority file with one cookie that matches every display, readable by the owner only where the file
   * system has POSIX permissions.
   *
   * @param authority the file, which must not exist yet
   * @param cookie    the cookie
   * @throws IOException if the file cannot be written
   */
  @VisibleForTesting
  static void writeAuthority(final Path authority, final byte[] cookie) throws IOException {
    final byte[] entry = createAuthorityEntry(cookie);
    final FileSystem fileSystem = authority.getFileSystem();
    final boolean posix = fileSystem.supportedFileAttributeViews().contains("posix");
    if (posix) {
      Files.createFile(authority, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } else {
      Files.createFile(authority);
    }
    Files.write(authority, entry);
  }

  /**
   * Encodes one entry of an X authority file: family, address, display number, method name and cookie, each counted
   * with a big-endian 16-bit length. The family matches every host and the empty display number every display.
   *
   * @param cookie the cookie
   * @return the entry
   */
  @VisibleForTesting
  static byte[] createAuthorityEntry(final byte[] cookie) {
    final byte[] name = COOKIE_NAME.getBytes(StandardCharsets.US_ASCII);
    final ByteBuffer entry = ByteBuffer.allocate(2 + 2 + 2 + 2 + name.length + 2 + cookie.length);
    entry.putShort((short) FAMILY_WILD);
    entry.putShort((short) 0);
    entry.putShort((short) 0);
    entry.putShort((short) name.length);
    entry.put(name);
    entry.putShort((short) cookie.length);
    entry.put(cookie);
    return entry.array();
  }

  /**
   * Gets the name of the display, for CEF's {@code --display} switch.
   *
   * @return the name, such as {@code 127.0.0.1:26768}
   */
  String getDisplay() {
    final InetAddress address = this.server.getInetAddress();
    final String host = address.getHostAddress();
    return host + ":" + (this.server.getLocalPort() - X11_BASE_PORT);
  }

  /**
   * Counts the clients that are connected now.
   *
   * @return the number of clients
   */
  @VisibleForTesting
  int countClients() {
    return this.clients.size();
  }

  /**
   * Gets the name of a display, for Chromium's {@code --display} switch.
   *
   * @param display the display, or null if there is none
   * @return the name, or null without a display
   */
  static @Nullable String nameOf(final @Nullable NullDisplay display) {
    return display == null ? null : display.getDisplay();
  }

  private void acceptConnections() {
    while (true) {
      final Socket client;
      try {
        client = this.server.accept();
      } catch (final IOException exception) {
        // the display was closed
        return;
      }
      this.clients.add(client);
      final Socket evicted = this.admit(client);
      if (evicted != null) {
        closeQuietly(evicted);
      }
      // a client waits in a read most of its time, so each gets a virtual thread
      Thread.ofVirtual().name("mcav-browser-null-display-client").start(() -> this.serve(client));
    }
  }

  private @Nullable Socket admit(final Socket client) {
    synchronized (this.pending) {
      this.pending.addLast(client);
      return this.pending.size() > MAX_PENDING ? this.pending.removeFirst() : null;
    }
  }

  private void introduced(final Socket client) {
    synchronized (this.pending) {
      this.pending.remove(client);
    }
  }

  private void serve(final Socket client) {
    boolean slot = false;
    try (client) {
      client.setSoTimeout(this.setupTimeoutMillis);
      final DataInputStream in = new DataInputStream(new BufferedInputStream(client.getInputStream()));
      final OutputStream out = new BufferedOutputStream(client.getOutputStream());
      final ByteOrder order = readSetup(in, out, this.cookie);
      this.introduced(client);
      // only a client with the cookie takes a slot, and one more than the slots ends here
      slot = this.slots.tryAcquire();
      if (slot) {
        client.setSoTimeout(0);
        serveRequests(in, out, order, this.atoms);
      }
    } catch (final IOException exception) {
      // the client went away or broke the protocol; either way its connection ends
    } finally {
      this.introduced(client);
      this.clients.remove(client);
      if (slot) {
        this.slots.release();
      }
    }
  }

  /**
   * Serves one connection until it ends: the setup, then every request.
   *
   * @param input  the stream from the client
   * @param output the stream to the client, flushed after every answer
   * @param cookie the cookie a client must present
   * @param atoms  the atoms of the display
   * @throws IOException if the connection fails, the client breaks the protocol or presents another cookie
   */
  @VisibleForTesting
  static void serve(final InputStream input, final OutputStream output, final byte[] cookie, final Atoms atoms) throws IOException {
    final DataInputStream in = new DataInputStream(input);
    final ByteOrder order = readSetup(in, output, cookie);
    serveRequests(in, output, order, atoms);
  }

  private static void serveRequests(final DataInputStream in, final OutputStream output, final ByteOrder order, final Atoms atoms)
    throws IOException {
    int sequence = 0;
    while (true) {
      final int opcode = in.read();
      if (opcode < 0) {
        return;
      }
      final int data = in.readUnsignedByte();
      final byte[] lengthBytes = new byte[2];
      in.readFully(lengthBytes);
      final int units = ByteBuffer.wrap(lengthBytes).order(order).getShort() & 0xFFFF;
      if (units == 0 || units > MAX_REQUEST_UNITS) {
        // no extension is announced, so neither are the long requests of BIG-REQUESTS
        throw new ProtocolException("A request of " + units + " units is outside 1 to " + MAX_REQUEST_UNITS);
      }
      final byte[] body = new byte[units * 4 - 4];
      in.readFully(body);
      sequence = (sequence + 1) & 0xFFFF;
      final ByteBuffer request = ByteBuffer.wrap(body).order(order);
      final ByteBuffer answer = answer(opcode, data, request, sequence, order, atoms);
      if (answer != null) {
        send(output, answer);
      }
    }
  }

  /**
   * Reads the setup of a connection and answers it: a screen if the client presented the cookie, a refusal otherwise.
   *
   * @return the byte order of the client
   */
  private static ByteOrder readSetup(final DataInputStream in, final OutputStream output, final byte[] cookie) throws IOException {
    final int orderByte = in.readUnsignedByte();
    final ByteOrder order =
      switch (orderByte) {
        case 'l' -> ByteOrder.LITTLE_ENDIAN;
        case 'B' -> ByteOrder.BIG_ENDIAN;
        default -> throw new ProtocolException("The connection setup names the byte order " + orderByte);
      };
    final byte[] head = new byte[11];
    in.readFully(head);
    final ByteBuffer setup = ByteBuffer.wrap(head).order(order);
    setup.get();
    final int major = setup.getShort() & 0xFFFF;
    setup.getShort();
    final int nameLength = setup.getShort() & 0xFFFF;
    final int dataLength = setup.getShort() & 0xFFFF;
    if (nameLength > MAX_AUTHORIZATION_BYTES || dataLength > MAX_AUTHORIZATION_BYTES) {
      throw new ProtocolException("The authorization of the connection setup is longer than " + MAX_AUTHORIZATION_BYTES + " bytes");
    }
    final byte[] name = readPadded(in, nameLength);
    final byte[] presented = readPadded(in, dataLength);
    final boolean named = COOKIE_NAME.equals(new String(name, StandardCharsets.US_ASCII));
    // compared in constant time, as a client that guesses learns nothing from how long the answer takes
    final boolean matches = MessageDigest.isEqual(presented, cookie);
    if (major != 11 || !named || !matches) {
      final String reason = major != 11 ? "only X11 is spoken here" : "the cookie of the display is missing";
      writeRefusal(output, order, reason);
      throw new ProtocolException("A client was refused: " + reason);
    }
    final ByteBuffer accepted = createSetupReply(order);
    send(output, accepted);
    return order;
  }

  private static void send(final OutputStream output, final ByteBuffer message) throws IOException {
    output.write(message.array(), message.arrayOffset(), message.limit());
    output.flush();
  }

  private static byte[] readPadded(final DataInputStream in, final int length) throws IOException {
    final byte[] value = new byte[length];
    in.readFully(value);
    in.readFully(new byte[pad(length)]);
    return value;
  }

  private static int pad(final int length) {
    return (4 - (length % 4)) % 4;
  }

  private static void writeRefusal(final OutputStream output, final ByteOrder order, final String reason) throws IOException {
    final byte[] text = reason.getBytes(StandardCharsets.US_ASCII);
    final int padded = text.length + pad(text.length);
    final ByteBuffer refusal = ByteBuffer.allocate(8 + padded).order(order);
    refusal.put((byte) 0);
    refusal.put((byte) text.length);
    refusal.putShort((short) 11);
    refusal.putShort((short) 0);
    refusal.putShort((short) (padded / 4));
    refusal.put(text);
    output.write(refusal.array());
    output.flush();
  }

  /**
   * Describes the display: one screen of {@value #SCREEN_WIDTH}x{@value #SCREEN_HEIGHT} with a 24-bit TrueColor visual.
   *
   * @param order the byte order of the client
   * @return the reply to the connection setup
   */
  @VisibleForTesting
  static ByteBuffer createSetupReply(final ByteOrder order) {
    final int vendorPadded = VENDOR.length + pad(VENDOR.length);
    final int formats = 3 * 8;
    final int screen = 40 + 8 + 24 + 8;
    final int additional = 32 + vendorPadded + formats + screen;
    final ByteBuffer reply = ByteBuffer.allocate(8 + additional).order(order);
    reply.put((byte) 1);
    reply.put((byte) 0);
    reply.putShort((short) 11);
    reply.putShort((short) 0);
    reply.putShort((short) (additional / 4));
    // release, resource base and mask, motion buffer, vendor length, request length, screens, formats, image order,
    // bitmap order, scanline unit and pad, keycodes
    reply.putInt(1);
    reply.putInt(0x00400000);
    reply.putInt(0x001FFFFF);
    reply.putInt(0);
    reply.putShort((short) VENDOR.length);
    reply.putShort((short) MAX_REQUEST_UNITS);
    reply.put((byte) 1);
    reply.put((byte) 3);
    reply.put((byte) (order == ByteOrder.LITTLE_ENDIAN ? 0 : 1));
    reply.put((byte) 0);
    reply.put((byte) 32);
    reply.put((byte) 32);
    reply.put((byte) MIN_KEYCODE);
    reply.put((byte) MAX_KEYCODE);
    reply.putInt(0);
    reply.put(VENDOR);
    reply.put(new byte[vendorPadded - VENDOR.length]);
    for (final int[] format : new int[][] { { 1, 1 }, { 24, 32 }, { 32, 32 } }) {
      reply.put((byte) format[0]);
      reply.put((byte) format[1]);
      reply.put((byte) 32);
      reply.put(new byte[5]);
    }
    // the screen: root, colormap, white and black pixels, input masks, size in pixels and millimetres, installed
    // colormaps, root visual, backing stores, save unders, root depth and two depths
    reply.putInt(ROOT);
    reply.putInt(COLORMAP);
    reply.putInt(0xFFFFFF);
    reply.putInt(0);
    reply.putInt(0);
    reply.putShort((short) SCREEN_WIDTH);
    reply.putShort((short) SCREEN_HEIGHT);
    reply.putShort((short) 508);
    reply.putShort((short) 286);
    reply.putShort((short) 1);
    reply.putShort((short) 1);
    reply.putInt(VISUAL);
    reply.put((byte) 0);
    reply.put((byte) 0);
    reply.put((byte) 24);
    reply.put((byte) 2);
    // depth 24 with one TrueColor visual, then depth 32 without visuals
    reply.put((byte) 24);
    reply.put((byte) 0);
    reply.putShort((short) 1);
    reply.putInt(0);
    reply.putInt(VISUAL);
    reply.put((byte) 4);
    reply.put((byte) 8);
    reply.putShort((short) 256);
    reply.putInt(0xFF0000);
    reply.putInt(0x00FF00);
    reply.putInt(0x0000FF);
    reply.putInt(0);
    reply.put((byte) 32);
    reply.put((byte) 0);
    reply.putShort((short) 0);
    reply.putInt(0);
    reply.flip();
    return reply;
  }

  /**
   * Answers one request.
   *
   * @param opcode   the major opcode
   * @param data     the second byte of the request
   * @param request  the rest of the request after its length
   * @param sequence the sequence number of the request
   * @param order    the byte order of the client
   * @param atoms    the atoms of the display
   * @return the reply or the error, or null for a request without a reply
   */
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
      case INTERN_ATOM -> internAtom(data != 0, request, sequence, order, atoms);
      case GET_ATOM_NAME -> atomName(request, sequence, order, atoms);
      // no window has properties, so every property is missing: type None, format 0, nothing after, no value
      case GET_PROPERTY -> reply(sequence, order, 0, 0);
      // no extension is there: present, major opcode, first event and first error all zero
      case QUERY_EXTENSION -> reply(sequence, order, 0, 0);
      case LIST_EXTENSIONS -> reply(sequence, order, 0, 0);
      // the focus is None, reverting to None
      case GET_INPUT_FOCUS -> reply(sequence, order, 0, 0);
      case GET_KEYBOARD_MAPPING -> keyboardMapping(request, sequence, order);
      // one key code per modifier, none of them set
      case GET_MODIFIER_MAPPING -> reply(sequence, order, 1, 8);
      case GET_WINDOW_ATTRIBUTES -> windowAttributes(sequence, order);
      case GET_GEOMETRY -> geometry(sequence, order);
      case QUERY_TREE -> tree(sequence, order);
      case QUERY_POINTER -> pointer(sequence, order);
      default -> unanswered(opcode, sequence, order);
    };
  }

  /**
   * Answers a request this display does not handle: an error if the client waits for a reply, nothing otherwise.
   */
  private static @Nullable ByteBuffer unanswered(final int opcode, final int sequence, final ByteOrder order) {
    if (opcode >= 128) {
      // an extension, and none was announced
      return error(BAD_REQUEST, opcode, sequence, order, 0);
    }
    if (REPLYING.contains(opcode)) {
      // a core request with a reply that is not answered here would wait forever, so it fails
      return error(BAD_IMPLEMENTATION, opcode, sequence, order, 0);
    }
    return null;
  }

  private static ByteBuffer internAtom(
    final boolean onlyIfExists,
    final ByteBuffer request,
    final int sequence,
    final ByteOrder order,
    final Atoms atoms
  ) {
    if (request.remaining() < 4) {
      return error(BAD_LENGTH, INTERN_ATOM, sequence, order, 0);
    }
    final int length = request.getShort() & 0xFFFF;
    request.getShort();
    if (length > request.remaining()) {
      return error(BAD_LENGTH, INTERN_ATOM, sequence, order, 0);
    }
    final byte[] name = new byte[length];
    request.get(name);
    final String text = new String(name, StandardCharsets.ISO_8859_1);
    // a client that only asks whether an atom exists gets None for a new name, which is not interned
    final int atom = onlyIfExists ? atoms.find(text) : atoms.intern(text);
    if (atom == 0 && !onlyIfExists) {
      return error(BAD_ALLOC, INTERN_ATOM, sequence, order, 0);
    }
    final ByteBuffer reply = reply(sequence, order, 0, 0);
    reply.putInt(8, atom);
    return reply;
  }

  private static ByteBuffer atomName(final ByteBuffer request, final int sequence, final ByteOrder order, final Atoms atoms) {
    if (request.remaining() < 4) {
      return error(BAD_LENGTH, GET_ATOM_NAME, sequence, order, 0);
    }
    final int atom = request.getInt();
    final String name = atoms.name(atom);
    if (name == null) {
      return error(BAD_ATOM, GET_ATOM_NAME, sequence, order, atom);
    }
    final byte[] bytes = name.getBytes(StandardCharsets.ISO_8859_1);
    final ByteBuffer reply = reply(sequence, order, 0, bytes.length + pad(bytes.length));
    reply.putShort(8, (short) bytes.length);
    reply.put(32, bytes);
    return reply;
  }

  private static ByteBuffer keyboardMapping(final ByteBuffer request, final int sequence, final ByteOrder order) {
    if (request.remaining() < 2) {
      return error(BAD_LENGTH, GET_KEYBOARD_MAPPING, sequence, order, 0);
    }
    request.get();
    final int count = request.get() & 0xFF;
    // one key symbol per key code, and every one is NoSymbol
    return reply(sequence, order, 1, 4 * count);
  }

  private static ByteBuffer windowAttributes(final int sequence, final ByteOrder order) {
    // backing store NotUseful, the visual, class InputOutput, gravities, planes, pixel, save under, map installed,
    // map state Unmapped, override redirect, colormap and event masks
    final ByteBuffer reply = reply(sequence, order, 0, 12);
    reply.putInt(8, VISUAL);
    reply.putShort(12, (short) 1);
    reply.putInt(28, COLORMAP);
    return reply;
  }

  private static ByteBuffer geometry(final int sequence, final ByteOrder order) {
    // the depth in the second byte, then the root, the position, the size and the border width
    final ByteBuffer reply = reply(sequence, order, 24, 0);
    reply.putInt(8, ROOT);
    reply.putShort(16, (short) SCREEN_WIDTH);
    reply.putShort(18, (short) SCREEN_HEIGHT);
    return reply;
  }

  private static ByteBuffer tree(final int sequence, final ByteOrder order) {
    // the root, no parent and no children
    final ByteBuffer reply = reply(sequence, order, 0, 0);
    reply.putInt(8, ROOT);
    return reply;
  }

  private static ByteBuffer pointer(final int sequence, final ByteOrder order) {
    // on this screen, over the root at the origin, with no button held
    final ByteBuffer reply = reply(sequence, order, 1, 0);
    reply.putInt(8, ROOT);
    return reply;
  }

  /**
   * Creates a reply: 32 bytes and the extra bytes, all zero but its header.
   *
   * @param sequence the sequence number of the request
   * @param order    the byte order of the client
   * @param data     the second byte
   * @param extra    the bytes after the first 32, a multiple of four
   * @return the reply, positioned at its start
   */
  private static ByteBuffer reply(final int sequence, final ByteOrder order, final int data, final int extra) {
    final ByteBuffer reply = ByteBuffer.allocate(32 + extra).order(order);
    reply.put(0, (byte) 1);
    reply.put(1, (byte) data);
    reply.putShort(2, (short) sequence);
    reply.putInt(4, extra / 4);
    return reply;
  }

  private static ByteBuffer error(final int code, final int opcode, final int sequence, final ByteOrder order, final int value) {
    final ByteBuffer error = ByteBuffer.allocate(32).order(order);
    error.put(0, (byte) 0);
    error.put(1, (byte) code);
    error.putShort(2, (short) sequence);
    error.putInt(4, value);
    error.put(10, (byte) opcode);
    return error;
  }

  /**
   * Closes the display and every connection to it.
   */
  @Override
  public void close() {
    closeQuietly(this.server);
    for (final Socket client : this.clients) {
      closeQuietly(client);
    }
  }

  private static void closeQuietly(final Closeable closeable) {
    // the helper runs without mcav's other modules, so not HelperSession's, which needs them
    NetworkGuard.closeQuietly(closeable);
  }

  /**
   * The atoms of a display: the predefined ones, and every name a client interns, up to {@value #MAX_ATOMS} atoms and
   * {@value #MAX_ATOM_BYTES} bytes of names.
   */
  static final class Atoms {

    private final Map<String, Integer> ids = new ConcurrentHashMap<>();
    private final List<String> names = new ArrayList<>();
    private long bytes;

    Atoms() {
      for (final String name : PREDEFINED_ATOMS) {
        this.intern(name);
      }
    }

    /**
     * Finds the atom of a name that was interned before.
     *
     * @param name the name
     * @return the atom, or 0 (None) if the name has none
     */
    synchronized int find(final String name) {
      return this.ids.getOrDefault(name, 0);
    }

    /**
     * Gets the atom of a name, naming it if it is new.
     *
     * @param name the name
     * @return the atom, or 0 if the display has named as many atoms as it may
     */
    synchronized int intern(final String name) {
      final Integer known = this.ids.get(name);
      if (known != null) {
        return known;
      }
      if (this.names.size() >= MAX_ATOMS || this.bytes + name.length() > MAX_ATOM_BYTES) {
        return 0;
      }
      this.bytes += name.length();
      this.names.add(name);
      final int atom = this.names.size();
      this.ids.put(name, atom);
      return atom;
    }

    /**
     * Gets the name of an atom.
     *
     * @param atom the atom
     * @return the name, or null if the atom was never named
     */
    synchronized @Nullable String name(final int atom) {
      if (atom < 1 || atom > this.names.size()) {
        return null;
      }
      return this.names.get(atom - 1);
    }
  }
}
