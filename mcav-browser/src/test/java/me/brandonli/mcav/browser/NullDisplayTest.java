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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.browser.testing.OpenFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NullDisplayTest {

  private static final byte[] COOKIE = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 };
  private static final String COOKIE_NAME = "MIT-MAGIC-COOKIE-1";

  @TempDir
  Path folder;

  /**
   * Encodes a connection setup.
   */
  static byte[] setup(final ByteOrder order, final int major, final String name, final byte[] data) {
    final byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
    final ByteBuffer setup = ByteBuffer.allocate(12 + padded(nameBytes.length) + padded(data.length)).order(order);
    setup.put((byte) (order == ByteOrder.LITTLE_ENDIAN ? 'l' : 'B'));
    setup.put((byte) 0);
    setup.putShort((short) major);
    setup.putShort((short) 0);
    setup.putShort((short) nameBytes.length);
    setup.putShort((short) data.length);
    setup.putShort((short) 0);
    setup.put(nameBytes);
    setup.position(12 + padded(nameBytes.length));
    setup.put(data);
    return setup.array();
  }

  static byte[] setup(final byte[] cookie) {
    return setup(ByteOrder.LITTLE_ENDIAN, 11, COOKIE_NAME, cookie);
  }

  /**
   * Encodes a request: opcode, data byte, length in units, and the body padded to four bytes.
   */
  static byte[] request(final ByteOrder order, final int opcode, final int data, final byte[] body) {
    final int length = 4 + padded(body.length);
    final ByteBuffer request = ByteBuffer.allocate(length).order(order);
    request.put((byte) opcode);
    request.put((byte) data);
    request.putShort((short) (length / 4));
    request.put(body);
    return request.array();
  }

  static byte[] internAtom(final ByteOrder order, final String name) {
    final byte[] bytes = name.getBytes(StandardCharsets.ISO_8859_1);
    final ByteBuffer body = ByteBuffer.allocate(4 + bytes.length).order(order);
    body.putShort((short) bytes.length);
    body.putShort((short) 0);
    body.put(bytes);
    return request(order, NullDisplay.INTERN_ATOM, 0, body.array());
  }

  private static int padded(final int length) {
    return ((length + 3) / 4) * 4;
  }

  private static byte[] concat(final byte[]... parts) {
    final ByteArrayOutputStream all = new ByteArrayOutputStream();
    for (final byte[] part : parts) {
      all.writeBytes(part);
    }
    return all.toByteArray();
  }

  /**
   * Serves a whole conversation and returns what the display answered after its setup reply.
   */
  private static ByteBuffer converse(final ByteOrder order, final NullDisplay.Atoms atoms, final byte[]... requests) throws IOException {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    NullDisplay.serve(new ByteArrayInputStream(concat(setup(order, 11, COOKIE_NAME, COOKIE), concat(requests))), output, COOKIE, atoms);
    final byte[] all = output.toByteArray();
    final int setupBytes = NullDisplay.createSetupReply(order).limit();
    return ByteBuffer.wrap(Arrays.copyOfRange(all, setupBytes, all.length)).order(order);
  }

  private static ByteBuffer converse(final byte[]... requests) throws IOException {
    return converse(ByteOrder.LITTLE_ENDIAN, new NullDisplay.Atoms(), requests);
  }

  @Test
  void aClientWithTheCookieGetsOneTrueColorScreen() throws IOException {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    NullDisplay.serve(new ByteArrayInputStream(setup(COOKIE)), output, COOKIE, new NullDisplay.Atoms());
    final ByteBuffer reply = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(1, reply.get(0), "success");
    assertEquals(11, reply.getShort(2));
    assertEquals(reply.limit() - 8, (reply.getShort(6) & 0xFFFF) * 4, "the length in units counts the rest");
    final int vendorLength = reply.getShort(24);
    assertEquals("mcav null display", new String(output.toByteArray(), 40, vendorLength, StandardCharsets.US_ASCII));
    assertEquals(NullDisplay.MAX_REQUEST_UNITS, reply.getShort(26) & 0xFFFF);
    assertEquals(1, reply.get(28), "one screen");
    assertEquals(3, reply.get(29), "three pixmap formats");
    final int screen = 40 + 20 + 3 * 8;
    assertEquals(0x100, reply.getInt(screen), "the root window");
    assertEquals(1920, reply.getShort(screen + 20));
    assertEquals(1080, reply.getShort(screen + 22));
    assertEquals(24, reply.get(screen + 38), "the root depth");
    assertEquals(4, reply.get(screen + 40 + 8 + 4), "a TrueColor visual");
  }

  @Test
  void aBigEndianClientIsAnsweredInItsOwnOrder() throws IOException {
    final ByteBuffer answers = converse(ByteOrder.BIG_ENDIAN, new NullDisplay.Atoms(), internAtom(ByteOrder.BIG_ENDIAN, "PRIMARY"));
    assertEquals(1, answers.getShort(2), "the sequence number");
    assertEquals(1, answers.getInt(8), "PRIMARY is atom 1");
    assertEquals(1, NullDisplay.createSetupReply(ByteOrder.BIG_ENDIAN).get(30), "most significant byte first");
  }

  @Test
  void aClientWithoutTheCookieIsRefusedWithAReason() {
    for (final byte[] wrong : new byte[][] {
      setup(ByteOrder.LITTLE_ENDIAN, 11, COOKIE_NAME, new byte[16]),
      setup(ByteOrder.LITTLE_ENDIAN, 11, "XDM-AUTHORIZATION-1", COOKIE),
      setup(ByteOrder.LITTLE_ENDIAN, 11, "", new byte[0]),
    }) {
      final ByteArrayOutputStream output = new ByteArrayOutputStream();
      final ProtocolException refused = assertThrows(ProtocolException.class, () ->
        NullDisplay.serve(new ByteArrayInputStream(wrong), output, COOKIE, new NullDisplay.Atoms())
      );
      assertEquals("A client was refused: the cookie of the display is missing", refused.getMessage());
      final byte[] answer = output.toByteArray();
      assertEquals(0, answer[0], "failed");
      final String reason = new String(answer, 8, answer[1], StandardCharsets.US_ASCII);
      assertEquals("the cookie of the display is missing", reason);
      assertEquals(
        (answer.length - 8) / 4,
        ByteBuffer.wrap(answer).order(ByteOrder.LITTLE_ENDIAN).getShort(6),
        "the reason's length in units"
      );
    }
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    final byte[] tenPointZero = setup(ByteOrder.LITTLE_ENDIAN, 10, COOKIE_NAME, COOKIE);
    final ProtocolException oldProtocol = assertThrows(ProtocolException.class, () ->
      NullDisplay.serve(new ByteArrayInputStream(tenPointZero), output, COOKIE, new NullDisplay.Atoms())
    );
    assertEquals("A client was refused: only X11 is spoken here", oldProtocol.getMessage());
  }

  @Test
  void aSetupThatIsNoSetupEndsTheConnection() {
    final byte[] unknownOrder = setup(COOKIE);
    unknownOrder[0] = 'x';
    final ProtocolException order = assertThrows(ProtocolException.class, () ->
      NullDisplay.serve(new ByteArrayInputStream(unknownOrder), new ByteArrayOutputStream(), COOKIE, new NullDisplay.Atoms())
    );
    assertEquals("The connection setup names the byte order 120", order.getMessage());
    final byte[] longName = setup(ByteOrder.LITTLE_ENDIAN, 11, "x".repeat(257), new byte[0]);
    final ProtocolException name = assertThrows(ProtocolException.class, () ->
      NullDisplay.serve(new ByteArrayInputStream(longName), new ByteArrayOutputStream(), COOKIE, new NullDisplay.Atoms())
    );
    assertEquals("The authorization of the connection setup is longer than 256 bytes", name.getMessage());
    final byte[] longData = setup(ByteOrder.LITTLE_ENDIAN, 11, COOKIE_NAME, new byte[257]);
    assertThrows(ProtocolException.class, () ->
      NullDisplay.serve(new ByteArrayInputStream(longData), new ByteArrayOutputStream(), COOKIE, new NullDisplay.Atoms())
    );
    // the longest authorization is read, and then only refused for its value
    final byte[] longest = setup(ByteOrder.LITTLE_ENDIAN, 11, "x".repeat(256), new byte[256]);
    final ProtocolException value = assertThrows(ProtocolException.class, () ->
      NullDisplay.serve(new ByteArrayInputStream(longest), new ByteArrayOutputStream(), COOKIE, new NullDisplay.Atoms())
    );
    assertEquals("A client was refused: the cookie of the display is missing", value.getMessage());
  }

  @Test
  void atomsAreInternedOnceAndCanBeNamed() throws IOException {
    final NullDisplay.Atoms atoms = new NullDisplay.Atoms();
    final ByteBuffer answers = converse(
      ByteOrder.LITTLE_ENDIAN,
      atoms,
      internAtom(ByteOrder.LITTLE_ENDIAN, "_NET_SUPPORTED"),
      internAtom(ByteOrder.LITTLE_ENDIAN, "_NET_SUPPORTED"),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_ATOM_NAME, 0, new byte[] { 69, 0, 0, 0 }),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_ATOM_NAME, 0, new byte[] { 0x0F, 0x27, 0, 0 })
    );
    assertEquals(69, answers.getInt(8), "the first atom after the 68 predefined ones");
    assertEquals(69, answers.getInt(32 + 8), "an atom is interned once");
    final int name = 64;
    assertEquals(1, answers.get(name));
    assertEquals(3, answers.getShort(name + 2), "the sequence number");
    assertEquals(4, answers.getInt(name + 4), "14 bytes of name, padded, in units");
    assertEquals(14, answers.getShort(name + 8));
    final byte[] text = new byte[14];
    answers.get(name + 32, text);
    assertEquals("_NET_SUPPORTED", new String(text, StandardCharsets.ISO_8859_1));
    final int unknown = name + 48;
    assertEquals(0, answers.get(unknown), "an error");
    assertEquals(NullDisplay.BAD_ATOM, answers.get(unknown + 1));
    assertEquals(9999, answers.getInt(unknown + 4));
    assertEquals(NullDisplay.GET_ATOM_NAME, answers.get(unknown + 10));
    assertEquals("PRIMARY", atoms.name(1));
    assertEquals("WM_TRANSIENT_FOR", atoms.name(68));
    assertNull(atoms.name(0));
  }

  @Test
  void noWindowHasPropertiesAndNoExtensionIsThere() throws IOException {
    final ByteBuffer answers = converse(
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_PROPERTY, 0, new byte[20]),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.QUERY_EXTENSION, 0, new byte[] { 3, 0, 0, 0, 'G', 'L', 'X', 0 }),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.LIST_EXTENSIONS, 0, new byte[0]),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_INPUT_FOCUS, 0, new byte[0])
    );
    assertEquals(4 * 32, answers.limit());
    for (int index = 0; index < 4; index++) {
      assertEquals(1, answers.get(index * 32), "a reply");
      assertEquals(index + 1, answers.getShort(index * 32 + 2));
      assertEquals(0, answers.getInt(index * 32 + 4), "no extra bytes");
      assertEquals(0, answers.getInt(index * 32 + 8), "nothing: no type, not present, no focus");
    }
  }

  @Test
  void theKeyboardHasNoSymbolsAndNoModifiers() throws IOException {
    final ByteBuffer answers = converse(
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_KEYBOARD_MAPPING, 0, new byte[] { 8, (byte) 248, 0, 0 }),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_MODIFIER_MAPPING, 0, new byte[0])
    );
    assertEquals(1, answers.get(1), "one key symbol per key code");
    assertEquals(248, answers.getInt(4), "248 key codes, one unit each");
    final int modifiers = 32 + 248 * 4;
    assertEquals(1, answers.get(modifiers + 1), "one key code per modifier");
    assertEquals(2, answers.getInt(modifiers + 4));
    assertEquals(modifiers + 32 + 8, answers.limit());
  }

  @Test
  void windowsHaveAttributesAGeometryATreeAndAPointer() throws IOException {
    final byte[] window = { 0, 1, 0, 0 };
    final ByteBuffer answers = converse(
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_WINDOW_ATTRIBUTES, 0, window),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_GEOMETRY, 0, window),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.QUERY_TREE, 0, window),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.QUERY_POINTER, 0, window)
    );
    assertEquals(3, answers.getInt(4), "44 bytes of attributes");
    assertEquals(0x21, answers.getInt(8), "the visual");
    assertEquals(1, answers.getShort(12), "InputOutput");
    final int geometry = 44;
    assertEquals(24, answers.get(geometry + 1), "the depth");
    assertEquals(0x100, answers.getInt(geometry + 8));
    assertEquals(1920, answers.getShort(geometry + 16));
    assertEquals(1080, answers.getShort(geometry + 18));
    final int tree = geometry + 32;
    assertEquals(0x100, answers.getInt(tree + 8));
    assertEquals(0, answers.getShort(tree + 16), "no children");
    final int pointer = tree + 32;
    assertEquals(1, answers.get(pointer + 1), "on this screen");
    assertEquals(0x100, answers.getInt(pointer + 8));
    assertEquals(pointer + 32, answers.limit());
  }

  @Test
  void requestsWithoutAReplyAreDiscardedAndOthersFail() throws IOException {
    final ByteBuffer answers = converse(
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.CREATE_WINDOW, 24, new byte[28]),
      request(ByteOrder.LITTLE_ENDIAN, 4, 0, new byte[4]),
      request(ByteOrder.LITTLE_ENDIAN, 21, 0, new byte[4]),
      request(ByteOrder.LITTLE_ENDIAN, 140, 0, new byte[4])
    );
    assertEquals(64, answers.limit(), "no answer to a created or destroyed window");
    assertEquals(0, answers.get(0));
    assertEquals(NullDisplay.BAD_IMPLEMENTATION, answers.get(1), "ListProperties has a reply this display does not give");
    assertEquals(3, answers.getShort(2));
    assertEquals(21, answers.get(10));
    assertEquals(NullDisplay.BAD_REQUEST, answers.get(32 + 1), "no extension was announced");
    assertEquals(4, answers.getShort(32 + 2));
    assertEquals(140, answers.get(32 + 10) & 0xFF);
  }

  @Test
  void aRequestOfNoOpcodeIsSkippedAnAtomMayHaveAnEmptyNameAndTheFirstExtensionOpcodeFails() throws IOException {
    final ByteBuffer answers = converse(
      request(ByteOrder.LITTLE_ENDIAN, 0, 0, new byte[0]),
      internAtom(ByteOrder.LITTLE_ENDIAN, ""),
      request(ByteOrder.LITTLE_ENDIAN, 128, 0, new byte[0])
    );
    assertEquals(64, answers.limit(), "the request of no opcode has no answer, and the connection goes on");
    assertEquals(1, answers.get(0), "a reply for the atom of the empty name");
    assertEquals(2, answers.getShort(2));
    assertEquals(0, answers.get(32), "an error for the first opcode of an extension");
    assertEquals(NullDisplay.BAD_REQUEST, answers.get(32 + 1));
    assertEquals(3, answers.getShort(32 + 2));
    assertEquals(128, answers.get(32 + 10) & 0xFF);
  }

  @Test
  void theDisplayServesOnADaemonThreadAndCountsItsClients() throws IOException {
    final Path authority = this.folder.resolve("Xauthority");
    try (final NullDisplay display = NullDisplay.start(authority)) {
      final List<Thread> threads = Thread.getAllStackTraces()
        .keySet()
        .stream()
        .filter(thread -> thread.getName().equals("mcav-browser-null-display"))
        .toList();
      assertFalse(threads.isEmpty());
      assertTrue(threads.stream().allMatch(Thread::isDaemon), "the display never keeps the helper alive");
      final int port = NullDisplay.X11_BASE_PORT + Integer.parseInt(display.getDisplay().substring("127.0.0.1:".length()));
      try (Socket client = introduce(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), port, cookieOf(authority))) {
        Await.until("the client is counted", () -> display.countClients() == 1);
        assertTrue(internsAnAtom(client));
      }
      Await.until("the client is gone", () -> display.countClients() == 0);
    }
  }

  @Test
  void aClientThatLeavesFreesItsPlaceForTheNextOne() throws IOException, InterruptedException {
    final Path authority = this.folder.resolve("Xauthority");
    final List<Socket> clients = new ArrayList<>();
    try (final NullDisplay display = NullDisplay.start(authority)) {
      final int port = NullDisplay.X11_BASE_PORT + Integer.parseInt(display.getDisplay().substring("127.0.0.1:".length()));
      final InetAddress loopback = InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 });
      final byte[] cookie = cookieOf(authority);
      for (int count = 0; count < NullDisplay.MAX_CONNECTIONS; count++) {
        clients.add(introduce(loopback, port, cookie));
      }
      clients.removeFirst().close();
      Await.until("the client that left is gone", () -> display.countClients() == NullDisplay.MAX_CONNECTIONS - 1);
      // its place is given back right after it is no longer counted
      Thread.sleep(200L);
      try (Socket next = introduce(loopback, port, cookie)) {
        assertTrue(internsAnAtom(next), "one try is enough, with no client refused before");
      }
    } finally {
      for (final Socket client : clients) {
        client.close();
      }
    }
  }

  @Test
  void requestsTooShortForTheirFieldsFailWithALengthError() throws IOException {
    final byte[] longName = new byte[] { 50, 0, 0, 0, 'a', 'b', 'c', 'd' };
    final ByteBuffer answers = converse(
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.INTERN_ATOM, 0, new byte[0]),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.INTERN_ATOM, 0, longName),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_ATOM_NAME, 0, new byte[0]),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_KEYBOARD_MAPPING, 0, new byte[0])
    );
    final int[] opcodes = { NullDisplay.INTERN_ATOM, NullDisplay.INTERN_ATOM, NullDisplay.GET_ATOM_NAME, NullDisplay.GET_KEYBOARD_MAPPING };
    assertEquals(opcodes.length * 32, answers.limit());
    for (int index = 0; index < opcodes.length; index++) {
      assertEquals(0, answers.get(index * 32));
      assertEquals(NullDisplay.BAD_LENGTH, answers.get(index * 32 + 1));
      assertEquals(opcodes[index], answers.get(index * 32 + 10));
    }
  }

  @Test
  void aRequestOfNoUnitsOrMoreThanTheLimitEndsTheConnection() {
    final byte[] empty = { 43, 0, 0, 0 };
    final ProtocolException zero = assertThrows(ProtocolException.class, () -> converse(empty));
    assertEquals("A request of 0 units is outside 1 to 16384", zero.getMessage());
    final byte[] huge = { 43, 0, 1, 64 };
    final ProtocolException tooLong = assertThrows(ProtocolException.class, () -> converse(huge));
    assertEquals("A request of 16385 units is outside 1 to 16384", tooLong.getMessage());
    // the longest request is read whole
    final byte[] longest = new byte[NullDisplay.MAX_REQUEST_UNITS * 4];
    longest[0] = 127;
    longest[2] = 0;
    longest[3] = 64;
    assertEquals(0, assertDoesNotFail(() -> converse(longest)).limit());
  }

  private static ByteBuffer assertDoesNotFail(final IoSupplier supplier) {
    try {
      return supplier.get();
    } catch (final IOException exception) {
      throw new AssertionError(exception);
    }
  }

  @FunctionalInterface
  private interface IoSupplier {
    ByteBuffer get() throws IOException;
  }

  @Test
  void theAtomsOfADisplayAreBoundedInNumberAndBytes() {
    final NullDisplay.Atoms many = new NullDisplay.Atoms();
    int count = 0;
    while (many.intern("atom-" + count) != 0) {
      count++;
    }
    assertEquals(NullDisplay.MAX_ATOMS - 68, count, "the 68 predefined atoms count too");
    assertEquals(69, many.intern("atom-0"), "a known atom is still found");
    final ByteBuffer full = NullDisplay.answer(
      NullDisplay.INTERN_ATOM,
      0,
      ByteBuffer.wrap(new byte[] { 3, 0, 0, 0, 'n', 'e', 'w', 0 }).order(ByteOrder.LITTLE_ENDIAN),
      7,
      ByteOrder.LITTLE_ENDIAN,
      many
    );
    assertEquals(NullDisplay.BAD_ALLOC, full.get(1));
    final NullDisplay.Atoms large = new NullDisplay.Atoms();
    final String name = "x".repeat(60_000);
    int named = 0;
    while (large.intern(name + named) != 0) {
      named++;
    }
    assertTrue(named * 60_000L <= NullDisplay.MAX_ATOM_BYTES, named + " names of 60000 bytes");
    assertTrue((named + 1) * 60_000L > NullDisplay.MAX_ATOM_BYTES - 1_000);
  }

  @Test
  void aDisplayServesClientsThatReadItsAuthorityFile() throws IOException {
    final Path authority = this.folder.resolve("Xauthority");
    final NullDisplay display = NullDisplay.start(authority);
    try {
      if (authority.getFileSystem().supportedFileAttributeViews().contains("posix")) {
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(authority));
      }
      final byte[] entry = Files.readAllBytes(authority);
      final byte[] cookie = Arrays.copyOfRange(entry, entry.length - NullDisplay.COOKIE_BYTES, entry.length);
      assertArrayEquals(NullDisplay.createAuthorityEntry(cookie), entry);
      final String name = display.getDisplay();
      final java.util.regex.Matcher matcher = Pattern.compile("127\\.0\\.0\\.1:(\\d+)").matcher(name);
      assertTrue(matcher.matches(), name);
      final int port = NullDisplay.X11_BASE_PORT + Integer.parseInt(matcher.group(1));
      try (final Socket client = new Socket(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), port)) {
        client.setSoTimeout(10_000);
        final OutputStream out = client.getOutputStream();
        out.write(concat(setup(cookie), internAtom(ByteOrder.LITTLE_ENDIAN, "CLIPBOARD")));
        out.flush();
        final DataInputStream in = new DataInputStream(client.getInputStream());
        final byte[] accepted = new byte[NullDisplay.createSetupReply(ByteOrder.LITTLE_ENDIAN).limit()];
        in.readFully(accepted);
        assertEquals(1, accepted[0]);
        final byte[] atom = new byte[32];
        in.readFully(atom);
        assertEquals(69, ByteBuffer.wrap(atom).order(ByteOrder.LITTLE_ENDIAN).getInt(8));
        display.close();
        assertEnded(client.getInputStream());
      }
    } finally {
      display.close();
    }
  }

  private static void assertEnded(final InputStream in) throws IOException {
    try {
      assertEquals(-1, in.read());
    } catch (final SocketException reset) {
      // a closed connection may be reset instead
    }
  }

  private static byte[] cookieOf(final Path authority) throws IOException {
    final byte[] entry = Files.readAllBytes(authority);
    return Arrays.copyOfRange(entry, entry.length - NullDisplay.COOKIE_BYTES, entry.length);
  }

  private static Socket introduce(final InetAddress loopback, final int port, final byte[] cookie) throws IOException {
    final Socket client = new Socket(loopback, port);
    client.setSoTimeout(10_000);
    client.getOutputStream().write(setup(cookie));
    new DataInputStream(client.getInputStream()).readFully(new byte[NullDisplay.createSetupReply(ByteOrder.LITTLE_ENDIAN).limit()]);
    return client;
  }

  private static boolean internsAnAtom(final Socket client) throws IOException {
    client.getOutputStream().write(internAtom(ByteOrder.LITTLE_ENDIAN, "PRIMARY"));
    final byte[] reply = new byte[32];
    new DataInputStream(client.getInputStream()).readFully(reply);
    return reply[0] == 1;
  }

  @Test
  void clientsWithTheCookieBeyondTheLimitAreEndedAndOneThatLeavesFreesItsPlace() throws IOException {
    final Path authority = this.folder.resolve("Xauthority");
    final List<Socket> clients = new ArrayList<>();
    try (final NullDisplay display = NullDisplay.start(authority)) {
      final int port = NullDisplay.X11_BASE_PORT + Integer.parseInt(display.getDisplay().substring("127.0.0.1:".length()));
      final InetAddress loopback = InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 });
      final byte[] cookie = cookieOf(authority);
      for (int count = 0; count < NullDisplay.MAX_CONNECTIONS; count++) {
        clients.add(introduce(loopback, port, cookie));
      }
      try (final Socket extra = introduce(loopback, port, cookie)) {
        assertEnded(extra.getInputStream());
      }
      // a client that leaves frees its place
      clients.removeFirst().close();
      Await.until("a place for another client", () -> {
        try (Socket next = introduce(loopback, port, cookie)) {
          return internsAnAtom(next);
        } catch (final IOException ended) {
          return false;
        }
      });
    } finally {
      for (final Socket client : clients) {
        client.close();
      }
    }
  }

  @Test
  void clientsWithoutTheCookieCannotKeepOneThatHasItOut() throws IOException {
    final Path authority = this.folder.resolve("Xauthority");
    final List<Socket> silent = new ArrayList<>();
    try (final NullDisplay display = NullDisplay.start(authority)) {
      final int port = NullDisplay.X11_BASE_PORT + Integer.parseInt(display.getDisplay().substring("127.0.0.1:".length()));
      final InetAddress loopback = InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 });
      for (int count = 0; count < NullDisplay.MAX_PENDING; count++) {
        final Socket client = new Socket(loopback, port);
        client.setSoTimeout(10_000);
        silent.add(client);
      }
      try (Socket introduced = introduce(loopback, port, cookieOf(authority))) {
        assertTrue(internsAnAtom(introduced), "the client with the cookie is served");
      }
      // it took the place of the oldest client that never introduced itself, and of that one only
      assertEnded(silent.getFirst().getInputStream());
      final Socket second = silent.get(1);
      second.setSoTimeout(300);
      assertThrows(java.net.SocketTimeoutException.class, () -> second.getInputStream().read(), "the second client still waits");
    } finally {
      for (final Socket client : silent) {
        client.close();
      }
    }
  }

  @Test
  void aClientThatOnlyAsksWhetherAnAtomExistsCreatesNone() {
    final NullDisplay.Atoms atoms = new NullDisplay.Atoms();
    final ByteBuffer asked = NullDisplay.answer(
      16,
      1,
      requestOf(internAtom(ByteOrder.LITTLE_ENDIAN, "MCAV_NEW")),
      1,
      ByteOrder.LITTLE_ENDIAN,
      atoms
    );
    assertEquals(0, asked.getInt(8), "None for a name nobody interned");
    assertEquals(0, atoms.find("MCAV_NEW"), "and nothing interned");
    final ByteBuffer interned = NullDisplay.answer(
      16,
      0,
      requestOf(internAtom(ByteOrder.LITTLE_ENDIAN, "MCAV_NEW")),
      2,
      ByteOrder.LITTLE_ENDIAN,
      atoms
    );
    final int atom = interned.getInt(8);
    assertEquals(
      atom,
      NullDisplay.answer(16, 1, requestOf(internAtom(ByteOrder.LITTLE_ENDIAN, "MCAV_NEW")), 3, ByteOrder.LITTLE_ENDIAN, atoms).getInt(8)
    );
    assertEquals(atom, atoms.find("MCAV_NEW"));
  }

  /**
   * The body of a request: what follows its opcode, data byte and length.
   */
  private static ByteBuffer requestOf(final byte[] request) {
    return ByteBuffer.wrap(Arrays.copyOfRange(request, 4, request.length)).order(ByteOrder.LITTLE_ENDIAN);
  }

  @Test
  void anAuthorityFileThatExistsIsNotOverwrittenAndNoPortIsLeftOpen() throws IOException {
    final Path authority = Files.writeString(this.folder.resolve("Xauthority"), "someone else's");
    OpenFiles.leaveNoneOpen("a display that cannot write its file", () ->
      assertThrows(IOException.class, () -> NullDisplay.start(authority))
    );
    assertEquals("someone else's", Files.readString(authority));
  }

  @Test
  void onlyPortsThatADisplayNumberCanNameAreUsed() throws IOException {
    assertFalse(NullDisplay.isDisplayPort(NullDisplay.X11_BASE_PORT - 1));
    assertTrue(NullDisplay.isDisplayPort(NullDisplay.X11_BASE_PORT));
    final AtomicInteger asked = new AtomicInteger();
    final IOException none = assertThrows(IOException.class, () ->
      NullDisplay.bind(port -> {
        asked.incrementAndGet();
        return false;
      })
    );
    assertEquals("The system gives out no port of at least 6000 for the display", none.getMessage());
    assertEquals(64, asked.get(), "64 ports are tried");
    final AtomicInteger tries = new AtomicInteger();
    final List<Integer> refused = new ArrayList<>();
    try (
      final ServerSocket third = NullDisplay.bind(port -> {
        if (tries.incrementAndGet() < 3) {
          refused.add(port);
          return false;
        }
        return true;
      })
    ) {
      assertEquals(3, tries.get());
      assertFalse(refused.contains(third.getLocalPort()), "a refused port is kept until the search ends");
      assertEquals(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), third.getInetAddress());
    }
  }

  @Test
  void everyDisplayHasARandomCookieOfItsOwn() {
    final byte[] first = NullDisplay.createCookie();
    assertEquals(NullDisplay.COOKIE_BYTES, first.length);
    assertFalse(Arrays.equals(first, NullDisplay.createCookie()), "two displays got the same cookie");
    assertFalse(Arrays.equals(new byte[NullDisplay.COOKIE_BYTES], first), "the cookie is all zeros");
  }

  @Test
  void theAuthorityEntryMatchesEveryDisplayWithOneCookie() {
    final byte[] entry = NullDisplay.createAuthorityEntry(COOKIE);
    final ByteBuffer read = ByteBuffer.wrap(entry);
    assertEquals((short) 0xFFFF, read.getShort(), "FamilyWild");
    assertEquals(0, read.getShort(), "no address");
    assertEquals(0, read.getShort(), "no display number");
    final byte[] name = new byte[read.getShort()];
    read.get(name);
    assertEquals(COOKIE_NAME, new String(name, StandardCharsets.US_ASCII));
    final byte[] cookie = new byte[read.getShort()];
    read.get(cookie);
    assertArrayEquals(COOKIE, cookie);
    assertFalse(read.hasRemaining());
  }

  @Test
  void theAuthorityFileIsWrittenOnFileSystemsWithoutPosixPermissions() throws IOException {
    final Path archive = this.folder.resolve("plain.zip");
    try (final FileSystem zip = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
      assertFalse(zip.supportedFileAttributeViews().contains("posix"));
      final Path authority = zip.getPath("/Xauthority");
      NullDisplay.writeAuthority(authority, COOKIE);
      assertArrayEquals(NullDisplay.createAuthorityEntry(COOKIE), Files.readAllBytes(authority));
    }
  }

  @Test
  void aNewDisplayGivesOutAnotherPortEachTime() throws IOException {
    try (
      final NullDisplay first = NullDisplay.start(this.folder.resolve("first"));
      final NullDisplay second = NullDisplay.start(this.folder.resolve("second"))
    ) {
      assertNotEquals(first.getDisplay(), second.getDisplay());
    }
  }

  @Test
  void aDisplayIsNamedForChromiumAndNoDisplayHasNoName(@TempDir final Path directory) throws IOException {
    assertNull(NullDisplay.nameOf(null));
    try (NullDisplay display = NullDisplay.start(directory.resolve("Xauthority"))) {
      assertEquals(display.getDisplay(), NullDisplay.nameOf(display));
    }
  }

  @Test
  void aClientThatBreaksTheProtocolLosesItsConnectionAndOthersKeepTheDisplay(@TempDir final Path directory) throws IOException {
    try (NullDisplay display = NullDisplay.start(directory.resolve("Xauthority"))) {
      final int port = NullDisplay.X11_BASE_PORT + Integer.parseInt(display.getDisplay().substring("127.0.0.1:".length()));
      try (Socket broken = new Socket(InetAddress.getLoopbackAddress(), port)) {
        broken.setSoTimeout(10_000);
        // neither byte order of X11
        broken.getOutputStream().write(new byte[] { 'X', 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
        assertEquals(-1, broken.getInputStream().read(), "the display ends the connection");
      }
      final byte[] entry = Files.readAllBytes(directory.resolve("Xauthority"));
      final byte[] cookie = Arrays.copyOfRange(entry, entry.length - NullDisplay.COOKIE_BYTES, entry.length);
      try (Socket next = new Socket(InetAddress.getLoopbackAddress(), port)) {
        next.setSoTimeout(10_000);
        next.getOutputStream().write(setup(cookie));
        final byte[] accepted = new byte[NullDisplay.createSetupReply(ByteOrder.LITTLE_ENDIAN).limit()];
        new DataInputStream(next.getInputStream()).readFully(accepted);
        assertEquals(1, accepted[0], "the display still takes clients");
      }
      // a client that leaves between requests ends its connection normally
      Await.until("the client is gone", () -> display.countClients() == 0);
    }
  }

  @Test
  void aClientThatDoesNotIntroduceItselfInTimeLosesItsConnectionAndOneThatDidMayIdle(@TempDir final Path directory)
    throws IOException, InterruptedException {
    final Path authority = directory.resolve("Xauthority");
    try (NullDisplay display = NullDisplay.start(authority, 200)) {
      final int port = NullDisplay.X11_BASE_PORT + Integer.parseInt(display.getDisplay().substring("127.0.0.1:".length()));
      final byte[] entry = Files.readAllBytes(authority);
      final byte[] cookie = Arrays.copyOfRange(entry, entry.length - NullDisplay.COOKIE_BYTES, entry.length);
      try (
        Socket silent = new Socket(InetAddress.getLoopbackAddress(), port);
        Socket introduced = new Socket(InetAddress.getLoopbackAddress(), port)
      ) {
        silent.setSoTimeout(10_000);
        introduced.setSoTimeout(10_000);
        introduced.getOutputStream().write(setup(cookie));
        final DataInputStream in = new DataInputStream(introduced.getInputStream());
        in.readFully(new byte[NullDisplay.createSetupReply(ByteOrder.LITTLE_ENDIAN).limit()]);
        assertEquals(-1, silent.getInputStream().read(), "the display ends a connection that never introduced itself");
        // three times the time of the setup, which no longer applies
        Thread.sleep(600L);
        introduced.getOutputStream().write(internAtom(ByteOrder.LITTLE_ENDIAN, "CLIPBOARD"));
        final byte[] atom = new byte[32];
        in.readFully(atom);
        assertEquals(1, atom[0], "a reply");
      }
    }
  }
}
