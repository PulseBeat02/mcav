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
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.browser.testing.OpenFiles;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NullDisplayTest {

  private static final String MISSING_COOKIE_MESSAGE = "Because the cookie of the display is missing, a client was refused!";
  private static final String SEQUENCE_NUMBER_MESSAGE = "the sequence number";
  private static final String MISSING_COOKIE_REASON = "the cookie of the display is missing";
  private static final String REPLY_MESSAGE = "a reply";
  private static final String CLIENT_GONE_MESSAGE = "the client is gone";
  private static final String SERVED_CLIENT_MESSAGE = "the client with the cookie is served";
  private static final String OLDEST_WAITING_CLIENT_MESSAGE = "the oldest client still waits";

  private static final String ZIPPED_AUTHORITY_FILE = "/Xauthority";
  private static final String PLAIN_ARCHIVE_FILE = "plain.zip";
  private static final String FIRST_DISPLAY_FOLDER = "first";
  private static final String SECOND_DISPLAY_FOLDER = "second";

  private static final String PRIMARY_ATOM_NAME = "PRIMARY";
  private static final String CLIPBOARD_ATOM_NAME = "CLIPBOARD";

  private static final int UNSIGNED_SHORT_MASK = 0xFFFF;
  private static final int CHANNEL_MASK = 0xFF;

  private static final int ROOT_WINDOW = 0x100;

  private static final int SCREEN_WIDTH = 1920;
  private static final int SCREEN_HEIGHT = 1080;

  private static final int LAST_PREDEFINED_ATOM = 68;
  private static final int FIRST_DYNAMIC_ATOM = 69;

  private static final long CLIENT_POLL_PAUSE_MILLISECONDS = 200L;
  private static final int WAITING_CLIENT_TIMEOUT_MILLISECONDS = 300;

  private static final int PROTOCOL_MAJOR_VERSION = 11;

  private static final int REPLY_BYTES = 32;

  private static final int SETUP_HEADER_BYTES = 12;

  private static final int ROOT_DEPTH = 24;

  private static final int TWO_REPLIES_BYTES = 64;

  private static final int LOOPBACK_FIRST_OCTET = 127;

  private static final String LONG_NAME_CHARACTER = "x";

  private static final String POSIX_ATTRIBUTE_VIEW = "posix";

  private static final byte[] COOKIE = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 };
  private static final String COOKIE_NAME = "MIT-MAGIC-COOKIE-1";

  private static final String AUTHORITY_FILE = "Xauthority";

  private static final String DISPLAY_PREFIX = "127.0.0.1:";
  private static final Pattern DISPLAY_ADDRESS = Pattern.compile("127\\.0\\.0\\.1:(\\d+)");

  private static final int READ_TIMEOUT_MILLISECONDS = 10_000;

  private static final int PREDEFINED_ATOM_BYTES = 698;

  // core requests with a reply (X Window System Protocol, chapter 9)
  private static final Set<Integer> REPLYING_REQUESTS = Set.of(
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

  private static final int FIRST_EXTENSION_OPCODE = 128;
  private static final int OPCODE_COUNT = 256;

  @TempDir
  private Path folder;

  static byte[] setup(final ByteOrder order, final int major, final String name, final byte[] data) {
    final byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
    final ByteBuffer setup = ByteBuffer.allocate(SETUP_HEADER_BYTES + padded(nameBytes.length) + padded(data.length)).order(order);
    setup.put((byte) (order == ByteOrder.LITTLE_ENDIAN ? 'l' : 'B'));
    setup.put((byte) 0);
    setup.putShort((short) major);
    setup.putShort((short) 0);
    setup.putShort((short) nameBytes.length);
    setup.putShort((short) data.length);
    setup.putShort((short) 0);
    setup.put(nameBytes);
    setup.position(SETUP_HEADER_BYTES + padded(nameBytes.length));
    setup.put(data);

    return setup.array();
  }

  static byte[] setup(final byte[] cookie) {
    return setup(ByteOrder.LITTLE_ENDIAN, PROTOCOL_MAJOR_VERSION, COOKIE_NAME, cookie);
  }

  // opcode, data byte, length in four-byte units, then the body padded to four bytes
  static byte[] request(final ByteOrder order, final int opcode, final int data, final byte[] body) {
    final int length = 4 + padded(body.length);
    final ByteBuffer request = ByteBuffer.allocate(length).order(order);
    request.put((byte) opcode);
    request.put((byte) data);
    request.putShort((short) (length / 4));
    request.put(body);

    return request.array();
  }

  private byte[] internAtom(final ByteOrder order, final String name) {
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

  private byte[] concat(final byte[]... parts) {
    final ByteArrayOutputStream all = new ByteArrayOutputStream();
    for (final byte[] part : parts) {
      all.writeBytes(part);
    }

    return all.toByteArray();
  }

  // returns what the display answered after its setup reply
  private ByteBuffer converse(final ByteOrder order, final NullDisplay.Atoms atoms, final byte[]... requests) throws IOException {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    NullDisplay.serve(
      new ByteArrayInputStream(this.concat(setup(order, PROTOCOL_MAJOR_VERSION, COOKIE_NAME, COOKIE), this.concat(requests))),
      output,
      COOKIE,
      atoms
    );
    final byte[] all = output.toByteArray();
    final int setupBytes = NullDisplay.createSetupReply(order).limit();
    return ByteBuffer.wrap(Arrays.copyOfRange(all, setupBytes, all.length)).order(order);
  }

  private ByteBuffer converse(final byte[]... requests) throws IOException {
    return this.converse(ByteOrder.LITTLE_ENDIAN, new NullDisplay.Atoms(), requests);
  }

  @Test
  void shouldGiveAClientWithTheCookieOneTrueColorScreen() throws IOException {
    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    NullDisplay.serve(new ByteArrayInputStream(setup(COOKIE)), output, COOKIE, new NullDisplay.Atoms());
    final ByteBuffer reply = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
    final byte status = reply.get(0);
    assertEquals(1, status, "success");

    final short protocolMajorVersion = reply.getShort(2);
    assertEquals(PROTOCOL_MAJOR_VERSION, protocolMajorVersion);

    final int additionalDataBytes = (reply.getShort(6) & UNSIGNED_SHORT_MASK) * 4;
    final int expectedAdditionalDataBytes = reply.limit() - 8;
    assertEquals(expectedAdditionalDataBytes, additionalDataBytes, "the length in units counts the rest");

    final int vendorLength = reply.getShort(24);
    final String vendor = new String(output.toByteArray(), 40, vendorLength, StandardCharsets.US_ASCII);
    assertEquals("mcav null display", vendor);

    final int maximumRequestLength = reply.getShort(26) & UNSIGNED_SHORT_MASK;
    assertEquals(NullDisplay.MAXIMUM_REQUEST_UNITS, maximumRequestLength);

    final byte screenCount = reply.get(28);
    assertEquals(1, screenCount, "one screen");

    final byte pixmapFormatCount = reply.get(29);
    assertEquals(3, pixmapFormatCount, "three pixmap formats");

    final int screen = 40 + 20 + 3 * 8;
    final int rootWindow = reply.getInt(screen);
    assertEquals(ROOT_WINDOW, rootWindow, "the root window");

    final short screenWidth = reply.getShort(screen + 20);
    assertEquals(SCREEN_WIDTH, screenWidth);

    final short screenHeight = reply.getShort(screen + 22);
    assertEquals(SCREEN_HEIGHT, screenHeight);

    final byte rootDepth = reply.get(screen + 38);
    assertEquals(ROOT_DEPTH, rootDepth, "the root depth");

    final byte visualClass = reply.get(screen + 40 + 8 + 4);
    assertEquals(4, visualClass, "a TrueColor visual");
  }

  @Test
  void shouldAnswerABigEndianClientInItsOwnOrder() throws IOException {
    final ByteBuffer answers = this.converse(
      ByteOrder.BIG_ENDIAN,
      new NullDisplay.Atoms(),
      this.internAtom(ByteOrder.BIG_ENDIAN, PRIMARY_ATOM_NAME)
    );
    final short sequenceNumber = answers.getShort(2);
    assertEquals(1, sequenceNumber, SEQUENCE_NUMBER_MESSAGE);

    final int primaryAtom = answers.getInt(8);
    assertEquals(1, primaryAtom, "PRIMARY is atom 1");

    final byte imageByteOrder = NullDisplay.createSetupReply(ByteOrder.BIG_ENDIAN).get(30);
    assertEquals(1, imageByteOrder, "most significant byte first");
  }

  @Test
  void shouldListThreePixmapFormatsAndTwoDepthsWhoseTrueColorVisualIsTheRootVisual() {
    final ByteBuffer reply = NullDisplay.createSetupReply(ByteOrder.LITTLE_ENDIAN);
    final List<List<Integer>> formats = List.of(this.formatAt(reply, 60), this.formatAt(reply, 68), this.formatAt(reply, 76));
    final List<List<Integer>> expectedFormats = List.of(List.of(1, 1, 32), List.of(24, 32, 32), List.of(32, 32, 32));
    assertEquals(expectedFormats, formats, "depth, bits per pixel and scanline pad of each format");

    final int screen = 84;
    final int colormap = reply.getInt(screen + 4);
    assertEquals(0x20, colormap, "the default colormap");

    final int whitePixel = reply.getInt(screen + 8);
    assertEquals(0xFFFFFF, whitePixel);

    final int blackPixel = reply.getInt(screen + 12);
    assertEquals(0, blackPixel);

    final int rootVisual = reply.getInt(screen + 32);
    final int visual = reply.getInt(screen + 48);
    assertEquals(0x21, rootVisual, "the root visual");
    assertEquals(rootVisual, visual, "the visual of depth 24 is the root visual");

    final byte depthCount = reply.get(screen + 39);
    assertEquals(2, depthCount);

    final short visualCount = reply.getShort(screen + 42);
    assertEquals(1, visualCount, "one visual of depth 24");

    final List<Integer> masks = List.of(reply.getInt(screen + 56), reply.getInt(screen + 60), reply.getInt(screen + 64));
    final List<Integer> expectedMasks = List.of(0xFF0000, 0x00FF00, 0x0000FF);
    assertEquals(expectedMasks, masks, "red, green and blue");

    final int lastDepth = screen + 72;
    final byte lastDepthBits = reply.get(lastDepth);
    assertEquals(32, lastDepthBits);

    final short lastDepthVisualCount = reply.getShort(lastDepth + 2);
    assertEquals(0, lastDepthVisualCount, "no visual of depth 32");

    final int replyBytes = reply.limit();
    final int expectedReplyBytes = lastDepth + 8;
    assertEquals(expectedReplyBytes, replyBytes, "the last depth ends the reply");
  }

  private List<Integer> formatAt(final ByteBuffer reply, final int offset) {
    final int depth = reply.get(offset);
    final int bitsPerPixel = reply.get(offset + 1);
    final int scanlinePad = reply.get(offset + 2);
    return List.of(depth, bitsPerPixel, scanlinePad);
  }

  @Test
  void shouldRefuseAClientWithoutTheCookieWithAReason() {
    for (final byte[] wrong : new byte[][] {
      setup(ByteOrder.LITTLE_ENDIAN, PROTOCOL_MAJOR_VERSION, COOKIE_NAME, new byte[16]),
      setup(ByteOrder.LITTLE_ENDIAN, PROTOCOL_MAJOR_VERSION, "XDM-AUTHORIZATION-1", COOKIE),
      setup(ByteOrder.LITTLE_ENDIAN, PROTOCOL_MAJOR_VERSION, "", new byte[0]),
    }) {
      final ByteArrayOutputStream output = new ByteArrayOutputStream();
      final ProtocolException refused = assertThrows(ProtocolException.class, () -> this.serveSetup(wrong, output));
      final String wrongCookieMessage = refused.getMessage();
      assertEquals(MISSING_COOKIE_MESSAGE, wrongCookieMessage);

      final byte[] answer = output.toByteArray();
      final byte status = answer[0];
      assertEquals(0, status, "failed");

      final String reason = new String(answer, 8, answer[1], StandardCharsets.US_ASCII);
      assertEquals(MISSING_COOKIE_REASON, reason);

      final short reasonUnits = ByteBuffer.wrap(answer).order(ByteOrder.LITTLE_ENDIAN).getShort(6);
      final int expectedReasonUnits = (answer.length - 8) / 4;
      assertEquals(expectedReasonUnits, reasonUnits, "the reason's length in units");
    }

    final ByteArrayOutputStream output = new ByteArrayOutputStream();
    final byte[] tenPointZero = setup(ByteOrder.LITTLE_ENDIAN, 10, COOKIE_NAME, COOKIE);
    final ProtocolException oldProtocol = assertThrows(ProtocolException.class, () -> this.serveSetup(tenPointZero, output));
    final String oldProtocolMessage = oldProtocol.getMessage();
    assertEquals("Because only X11 is spoken here, a client was refused!", oldProtocolMessage);
  }

  private void serveSetup(final byte[] setup, final OutputStream output) throws IOException {
    NullDisplay.serve(new ByteArrayInputStream(setup), output, COOKIE, new NullDisplay.Atoms());
  }

  @Test
  void shouldEndTheConnectionOnASetupThatIsNoSetup() {
    final byte[] unknownOrder = setup(COOKIE);
    unknownOrder[0] = 'x';
    final ProtocolException order = assertThrows(ProtocolException.class, () -> this.serveSetup(unknownOrder, new ByteArrayOutputStream()));
    final String orderMessage = order.getMessage();
    assertEquals("Byte order 120 of the connection setup is unknown!", orderMessage);

    final byte[] longName = setup(ByteOrder.LITTLE_ENDIAN, PROTOCOL_MAJOR_VERSION, LONG_NAME_CHARACTER.repeat(257), new byte[0]);
    final ProtocolException name = assertThrows(ProtocolException.class, () -> this.serveSetup(longName, new ByteArrayOutputStream()));
    final String nameMessage = name.getMessage();
    assertEquals("The authorization of the connection setup is longer than 256 bytes!", nameMessage);

    final byte[] longData = setup(ByteOrder.LITTLE_ENDIAN, PROTOCOL_MAJOR_VERSION, COOKIE_NAME, new byte[257]);
    assertThrows(ProtocolException.class, () -> this.serveSetup(longData, new ByteArrayOutputStream()));

    final byte[] longest = setup(ByteOrder.LITTLE_ENDIAN, PROTOCOL_MAJOR_VERSION, LONG_NAME_CHARACTER.repeat(256), new byte[256]);
    final ProtocolException value = assertThrows(ProtocolException.class, () -> this.serveSetup(longest, new ByteArrayOutputStream()));
    final String valueMessage = value.getMessage();
    assertEquals(MISSING_COOKIE_MESSAGE, valueMessage);
  }

  @Test
  void shouldInternAtomsOnceAndNameThem() throws IOException {
    final NullDisplay.Atoms atoms = new NullDisplay.Atoms();
    final ByteBuffer answers = this.converse(
      ByteOrder.LITTLE_ENDIAN,
      atoms,
      this.internAtom(ByteOrder.LITTLE_ENDIAN, "_NET_SUPPORTED"),
      this.internAtom(ByteOrder.LITTLE_ENDIAN, "_NET_SUPPORTED"),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_ATOM_NAME, 0, new byte[] { FIRST_DYNAMIC_ATOM, 0, 0, 0 }),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_ATOM_NAME, 0, new byte[] { 0x0F, 0x27, 0, 0 })
    );
    final int firstAtom = answers.getInt(8);
    assertEquals(FIRST_DYNAMIC_ATOM, firstAtom, "the first atom after the 68 predefined ones");

    final int reinternedAtom = answers.getInt(REPLY_BYTES + 8);
    assertEquals(FIRST_DYNAMIC_ATOM, reinternedAtom, "an atom is interned once");

    final int name = TWO_REPLIES_BYTES;
    final byte nameResponseType = answers.get(name);
    assertEquals(1, nameResponseType);

    final short nameSequenceNumber = answers.getShort(name + 2);
    assertEquals(3, nameSequenceNumber, SEQUENCE_NUMBER_MESSAGE);

    final int nameReplyLength = answers.getInt(name + 4);
    assertEquals(4, nameReplyLength, "14 bytes of name, padded, in units");

    final short nameLength = answers.getShort(name + 8);
    assertEquals(14, nameLength);

    final byte[] text = new byte[14];
    answers.get(name + REPLY_BYTES, text);
    final String atomName = new String(text, StandardCharsets.ISO_8859_1);
    assertEquals("_NET_SUPPORTED", atomName);

    final int unknown = name + 48;
    final byte unknownResponseType = answers.get(unknown);
    assertEquals(0, unknownResponseType, "an error");

    final byte errorCode = answers.get(unknown + 1);
    assertEquals(NullDisplay.BAD_ATOM, errorCode);

    final int badValue = answers.getInt(unknown + 4);
    assertEquals(9999, badValue);

    final byte majorOpcode = answers.get(unknown + 10);
    assertEquals(NullDisplay.GET_ATOM_NAME, majorOpcode);

    final @Nullable String primaryName = atoms.name(1);
    assertEquals(PRIMARY_ATOM_NAME, primaryName);

    final @Nullable String lastPredefinedName = atoms.name(LAST_PREDEFINED_ATOM);
    assertEquals("WM_TRANSIENT_FOR", lastPredefinedName);

    final @Nullable String noneName = atoms.name(0);
    assertNull(noneName);
  }

  @Test
  void shouldGiveNoWindowPropertiesAndOfferNoExtension() throws IOException {
    final ByteBuffer answers = this.converse(
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_PROPERTY, 0, new byte[20]),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.QUERY_EXTENSION, 0, new byte[] { 3, 0, 0, 0, 'G', 'L', 'X', 0 }),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.LIST_EXTENSIONS, 0, new byte[0]),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_INPUT_FOCUS, 0, new byte[0])
    );
    final int answerBytes = answers.limit();
    final int expectedAnswerBytes = 4 * REPLY_BYTES;
    assertEquals(expectedAnswerBytes, answerBytes);

    for (int index = 0; index < 4; index++) {
      final byte responseType = answers.get(index * REPLY_BYTES);
      assertEquals(1, responseType, REPLY_MESSAGE);

      final short sequenceNumber = answers.getShort(index * REPLY_BYTES + 2);
      final int expectedSequenceNumber = index + 1;
      assertEquals(expectedSequenceNumber, sequenceNumber);

      final int replyLength = answers.getInt(index * REPLY_BYTES + 4);
      assertEquals(0, replyLength, "no extra bytes");

      final int replyContent = answers.getInt(index * REPLY_BYTES + 8);
      assertEquals(0, replyContent, "nothing: no type, not present, no focus");
    }
  }

  @Test
  void shouldGiveTheKeyboardNoSymbolsAndNoModifiers() throws IOException {
    final ByteBuffer answers = this.converse(
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_KEYBOARD_MAPPING, 0, new byte[] { 8, (byte) 248, 0, 0 }),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_MODIFIER_MAPPING, 0, new byte[0])
    );
    final byte keySymbolsPerKeyCode = answers.get(1);
    assertEquals(1, keySymbolsPerKeyCode, "one key symbol per key code");

    final int keyboardReplyLength = answers.getInt(4);
    assertEquals(248, keyboardReplyLength, "248 key codes, one unit each");

    final int modifiers = REPLY_BYTES + 248 * 4;
    final byte keyCodesPerModifier = answers.get(modifiers + 1);
    assertEquals(1, keyCodesPerModifier, "one key code per modifier");

    final int modifierReplyLength = answers.getInt(modifiers + 4);
    assertEquals(2, modifierReplyLength);

    final int answerBytes = answers.limit();
    final int expectedAnswerBytes = modifiers + REPLY_BYTES + 8;
    assertEquals(expectedAnswerBytes, answerBytes);
  }

  @Test
  void shouldGiveWindowsAttributesAGeometryATreeAndAPointer() throws IOException {
    final byte[] window = { 0, 1, 0, 0 };
    final ByteBuffer answers = this.converse(
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_WINDOW_ATTRIBUTES, 0, window),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_GEOMETRY, 0, window),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.QUERY_TREE, 0, window),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.QUERY_POINTER, 0, window)
    );
    final int attributesReplyLength = answers.getInt(4);
    assertEquals(3, attributesReplyLength, "44 bytes of attributes");

    final int visual = answers.getInt(8);
    assertEquals(0x21, visual, "the visual");

    final short windowClass = answers.getShort(12);
    assertEquals(1, windowClass, "InputOutput");

    final int colormap = answers.getInt(28);
    assertEquals(0x20, colormap, "the colormap of the screen");

    final int geometry = 44;
    final byte depth = answers.get(geometry + 1);
    assertEquals(ROOT_DEPTH, depth, "the depth");

    final int geometryRoot = answers.getInt(geometry + 8);
    assertEquals(ROOT_WINDOW, geometryRoot);

    final short width = answers.getShort(geometry + 16);
    assertEquals(SCREEN_WIDTH, width);

    final short height = answers.getShort(geometry + 18);
    assertEquals(SCREEN_HEIGHT, height);

    final int tree = geometry + REPLY_BYTES;
    final int treeRoot = answers.getInt(tree + 8);
    assertEquals(ROOT_WINDOW, treeRoot);

    final short childCount = answers.getShort(tree + 16);
    assertEquals(0, childCount, "no children");

    final int pointer = tree + REPLY_BYTES;
    final byte sameScreen = answers.get(pointer + 1);
    assertEquals(1, sameScreen, "on this screen");

    final int pointerRoot = answers.getInt(pointer + 8);
    assertEquals(ROOT_WINDOW, pointerRoot);

    final int answerBytes = answers.limit();
    final int expectedAnswerBytes = pointer + REPLY_BYTES;
    assertEquals(expectedAnswerBytes, answerBytes);
  }

  @Test
  void shouldDiscardRequestsWithoutAReplyAndFailOthers() throws IOException {
    final ByteBuffer answers = this.converse(
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.CREATE_WINDOW, ROOT_DEPTH, new byte[28]),
      request(ByteOrder.LITTLE_ENDIAN, 4, 0, new byte[4]),
      request(ByteOrder.LITTLE_ENDIAN, 21, 0, new byte[4]),
      request(ByteOrder.LITTLE_ENDIAN, 140, 0, new byte[4])
    );
    final int answerBytes = answers.limit();
    assertEquals(TWO_REPLIES_BYTES, answerBytes, "no answer to a created or destroyed window");

    final byte firstResponseType = answers.get(0);
    assertEquals(0, firstResponseType);

    final byte firstErrorCode = answers.get(1);
    assertEquals(NullDisplay.BAD_IMPLEMENTATION, firstErrorCode, "ListProperties has a reply this display does not give");

    final short firstSequenceNumber = answers.getShort(2);
    assertEquals(3, firstSequenceNumber);

    final byte firstMajorOpcode = answers.get(10);
    assertEquals(21, firstMajorOpcode);

    final byte secondErrorCode = answers.get(REPLY_BYTES + 1);
    assertEquals(NullDisplay.BAD_REQUEST, secondErrorCode, "no extension was announced");

    final short secondSequenceNumber = answers.getShort(REPLY_BYTES + 2);
    assertEquals(4, secondSequenceNumber);

    final int secondMajorOpcode = answers.get(REPLY_BYTES + 10) & CHANNEL_MASK;
    assertEquals(140, secondMajorOpcode);
  }

  @Test
  void shouldSkipARequestOfNoOpcodeAllowAnAtomWithAnEmptyNameAndFailTheFirstExtensionOpcode() throws IOException {
    final ByteBuffer answers = this.converse(
      request(ByteOrder.LITTLE_ENDIAN, 0, 0, new byte[0]),
      this.internAtom(ByteOrder.LITTLE_ENDIAN, ""),
      request(ByteOrder.LITTLE_ENDIAN, 128, 0, new byte[0])
    );
    final int answerBytes = answers.limit();
    assertEquals(TWO_REPLIES_BYTES, answerBytes, "the request of no opcode has no answer, and the connection goes on");

    final byte firstResponseType = answers.get(0);
    assertEquals(1, firstResponseType, "a reply for the atom of the empty name");

    final short firstSequenceNumber = answers.getShort(2);
    assertEquals(2, firstSequenceNumber);

    final byte secondResponseType = answers.get(REPLY_BYTES);
    assertEquals(0, secondResponseType, "an error for the first opcode of an extension");

    final byte secondErrorCode = answers.get(REPLY_BYTES + 1);
    assertEquals(NullDisplay.BAD_REQUEST, secondErrorCode);

    final short secondSequenceNumber = answers.getShort(REPLY_BYTES + 2);
    assertEquals(3, secondSequenceNumber);

    final int secondMajorOpcode = answers.get(REPLY_BYTES + 10) & CHANNEL_MASK;
    assertEquals(128, secondMajorOpcode);
  }

  @Test
  void shouldAnswerEveryCoreRequestThatHasAReplyAndEveryExtensionRequestAndNoOtherRequest() {
    final List<Integer> wronglyAnswered = new ArrayList<>();
    for (int opcode = 0; opcode < OPCODE_COUNT; opcode++) {
      final boolean answered = this.isAnswered(opcode);
      final boolean expectsAnswer = REPLYING_REQUESTS.contains(opcode) || opcode >= FIRST_EXTENSION_OPCODE;
      if (answered != expectsAnswer) {
        wronglyAnswered.add(opcode);
      }
    }

    assertEquals(List.of(), wronglyAnswered, "requests answered although no client waits, or not answered although one does");
  }

  private boolean isAnswered(final int opcode) {
    final ByteBuffer body = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    final @Nullable ByteBuffer answer = NullDisplay.answer(opcode, 0, body, 1, ByteOrder.LITTLE_ENDIAN, new NullDisplay.Atoms());
    return answer != null;
  }

  @Test
  void shouldServeOnADaemonThreadAndCountItsClients() throws IOException {
    final Path authority = this.folder.resolve(AUTHORITY_FILE);
    try (final NullDisplay display = NullDisplay.start(authority)) {
      final List<Thread> threads = Thread.getAllStackTraces()
        .keySet()
        .stream()
        .filter(thread -> thread.getName().equals("mcav-browser-null-display"))
        .toList();
      final boolean empty = threads.isEmpty();
      assertFalse(empty);

      final boolean allDaemons = threads.stream().allMatch(Thread::isDaemon);
      assertTrue(allDaemons, "the display never keeps the helper alive");

      final int port = NullDisplay.X11_BASE_PORT + Integer.parseInt(display.getDisplay().substring(DISPLAY_PREFIX.length()));
      try (
        final Socket client = this.introduce(
          InetAddress.getByAddress(new byte[] { LOOPBACK_FIRST_OCTET, 0, 0, 1 }),
          port,
          this.cookieOf(authority)
        )
      ) {
        Await.until("the client is counted", () -> display.countClients() == 1);
        final boolean atomInterned = this.internsAnAtom(client);
        assertTrue(atomInterned);
      }
      Await.until(CLIENT_GONE_MESSAGE, () -> display.countClients() == 0);
    }
  }

  @Test
  void shouldFreeThePlaceOfAClientThatLeavesForTheNextOne() throws IOException, InterruptedException {
    final Path authority = this.folder.resolve(AUTHORITY_FILE);
    final List<Socket> clients = new ArrayList<>();
    try (final NullDisplay display = NullDisplay.start(authority)) {
      final int port = NullDisplay.X11_BASE_PORT + Integer.parseInt(display.getDisplay().substring(DISPLAY_PREFIX.length()));
      final InetAddress loopback = InetAddress.getByAddress(new byte[] { LOOPBACK_FIRST_OCTET, 0, 0, 1 });
      final byte[] cookie = this.cookieOf(authority);
      for (int count = 0; count < NullDisplay.MAXIMUM_CONNECTIONS; count++) {
        clients.add(this.introduce(loopback, port, cookie));
      }
      clients.removeFirst().close();
      Await.until("the client that left is gone", () -> display.countClients() == NullDisplay.MAXIMUM_CONNECTIONS - 1);
      Thread.sleep(CLIENT_POLL_PAUSE_MILLISECONDS);
      try (final Socket next = this.introduce(loopback, port, cookie)) {
        final boolean atomInterned = this.internsAnAtom(next);
        assertTrue(atomInterned, "one try is enough, with no client refused before");
      }
    } finally {
      for (final Socket client : clients) {
        client.close();
      }
    }
  }

  @Test
  void shouldFailRequestsTooShortForTheirFieldsWithALengthError() throws IOException {
    final byte[] longName = new byte[] { 50, 0, 0, 0, 'a', 'b', 'c', 'd' };
    final ByteBuffer answers = this.converse(
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.INTERN_ATOM, 0, new byte[0]),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.INTERN_ATOM, 0, longName),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_ATOM_NAME, 0, new byte[0]),
      request(ByteOrder.LITTLE_ENDIAN, NullDisplay.GET_KEYBOARD_MAPPING, 0, new byte[0])
    );
    final int[] opcodes = { NullDisplay.INTERN_ATOM, NullDisplay.INTERN_ATOM, NullDisplay.GET_ATOM_NAME, NullDisplay.GET_KEYBOARD_MAPPING };
    final int answerBytes = answers.limit();
    final int expectedAnswerBytes = opcodes.length * REPLY_BYTES;
    assertEquals(expectedAnswerBytes, answerBytes);

    for (int index = 0; index < opcodes.length; index++) {
      final byte responseType = answers.get(index * REPLY_BYTES);
      assertEquals(0, responseType);

      final byte errorCode = answers.get(index * REPLY_BYTES + 1);
      assertEquals(NullDisplay.BAD_LENGTH, errorCode);

      final byte majorOpcode = answers.get(index * REPLY_BYTES + 10);
      final int expectedMajorOpcode = opcodes[index];
      assertEquals(expectedMajorOpcode, majorOpcode);
    }
  }

  @Test
  void shouldEndTheConnectionOnARequestOfNoUnitsOrMoreThanTheLimit() {
    final byte[] empty = { 43, 0, 0, 0 };
    final ProtocolException zero = assertThrows(ProtocolException.class, () -> this.converse(empty));
    final String zeroMessage = zero.getMessage();
    assertEquals("A request of 0 units must have 1 to 16384 units!", zeroMessage);

    final byte[] huge = { 43, 0, 1, 64 };
    final ProtocolException tooLong = assertThrows(ProtocolException.class, () -> this.converse(huge));
    final String tooLongMessage = tooLong.getMessage();
    assertEquals("A request of 16385 units must have 1 to 16384 units!", tooLongMessage);

    final byte[] longest = new byte[NullDisplay.MAXIMUM_REQUEST_UNITS * 4];
    longest[0] = 127;
    longest[2] = 0;
    longest[3] = 64;
    final int answerBytes = this.assertDoesNotFail(() -> this.converse(longest)).limit();
    assertEquals(0, answerBytes);
  }

  private ByteBuffer assertDoesNotFail(final IoSupplier supplier) {
    try {
      return supplier.get();
    } catch (final IOException exception) {
      throw new AssertionError(exception);
    }
  }

  @Test
  void shouldBoundTheAtomsOfADisplayInNumberAndBytes() {
    final NullDisplay.Atoms many = new NullDisplay.Atoms();
    int count = 0;
    while (many.intern("atom-" + count) != 0) {
      count++;
    }

    final int expectedAtomCount = NullDisplay.MAXIMUM_ATOMS - LAST_PREDEFINED_ATOM;
    assertEquals(expectedAtomCount, count, "the 68 predefined atoms count too");

    final int knownAtom = many.intern("atom-0");
    assertEquals(FIRST_DYNAMIC_ATOM, knownAtom, "a known atom is still found");

    final ByteBuffer full = NullDisplay.answer(
      NullDisplay.INTERN_ATOM,
      0,
      ByteBuffer.wrap(new byte[] { 3, 0, 0, 0, 'n', 'e', 'w', 0 }).order(ByteOrder.LITTLE_ENDIAN),
      7,
      ByteOrder.LITTLE_ENDIAN,
      many
    );
    final byte errorCode = full.get(1);
    assertEquals(NullDisplay.BAD_ALLOC, errorCode);

    final NullDisplay.Atoms large = new NullDisplay.Atoms();
    final String name = LONG_NAME_CHARACTER.repeat(60_000);
    int named = 0;
    while (large.intern(name + named) != 0) {
      named++;
    }

    final boolean namedWithinLimit = named * 60_000L <= NullDisplay.MAXIMUM_ATOM_BYTES;
    assertTrue(namedWithinLimit, named + " names of 60000 bytes");

    final boolean anotherNameBeyondLimit = (named + 1) * 60_000L > NullDisplay.MAXIMUM_ATOM_BYTES - 1_000;
    assertTrue(anotherNameBeyondLimit);
  }

  @Test
  void shouldInternANameThatFillsTheBytesOfTheAtomsExactly() {
    final NullDisplay.Atoms atoms = new NullDisplay.Atoms();
    final String filling = LONG_NAME_CHARACTER.repeat(NullDisplay.MAXIMUM_ATOM_BYTES - PREDEFINED_ATOM_BYTES);
    final int fillingAtom = atoms.intern(filling);
    assertEquals(FIRST_DYNAMIC_ATOM, fillingAtom, "a name that fills the bytes left is interned");

    final int overflowingAtom = atoms.intern("y");
    assertEquals(0, overflowingAtom, "no byte is left");
  }

  @Test
  void shouldServeClientsThatReadTheAuthorityFile() throws IOException {
    final Path authority = this.folder.resolve(AUTHORITY_FILE);
    final NullDisplay display = NullDisplay.start(authority);
    try {
      if (authority.getFileSystem().supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW)) {
        final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(authority);
        final Set<PosixFilePermission> expectedPermissions = PosixFilePermissions.fromString("rw-------");
        assertEquals(expectedPermissions, permissions);
      }
      final byte[] entry = Files.readAllBytes(authority);
      final byte[] cookie = Arrays.copyOfRange(entry, entry.length - NullDisplay.COOKIE_BYTES, entry.length);
      final byte[] expectedEntry = NullDisplay.createAuthorityEntry(cookie);
      assertArrayEquals(expectedEntry, entry);

      final String name = display.getDisplay();
      final Matcher matcher = DISPLAY_ADDRESS.matcher(name);
      final boolean displayNameMatches = matcher.matches();
      assertTrue(displayNameMatches, name);

      final int port = NullDisplay.X11_BASE_PORT + Integer.parseInt(matcher.group(1));
      try (final Socket client = new Socket(InetAddress.getByAddress(new byte[] { LOOPBACK_FIRST_OCTET, 0, 0, 1 }), port)) {
        client.setSoTimeout(READ_TIMEOUT_MILLISECONDS);
        final OutputStream output = client.getOutputStream();
        output.write(this.concat(setup(cookie), this.internAtom(ByteOrder.LITTLE_ENDIAN, CLIPBOARD_ATOM_NAME)));
        output.flush();
        final DataInputStream input = new DataInputStream(client.getInputStream());
        final byte[] accepted = new byte[NullDisplay.createSetupReply(ByteOrder.LITTLE_ENDIAN).limit()];
        input.readFully(accepted);
        final byte status = accepted[0];
        assertEquals(1, status);

        final byte[] atom = new byte[REPLY_BYTES];
        input.readFully(atom);
        final int internedAtom = ByteBuffer.wrap(atom).order(ByteOrder.LITTLE_ENDIAN).getInt(8);
        assertEquals(FIRST_DYNAMIC_ATOM, internedAtom);

        display.close();
        this.assertEnded(client.getInputStream());
        final InetAddress loopback = InetAddress.getByAddress(new byte[] { LOOPBACK_FIRST_OCTET, 0, 0, 1 });
        // the closed socket listens on until the blocked accept call of the display wakes up
        Await.until("the display stops listening", () -> this.refusesConnections(loopback, port));
      }
    } finally {
      display.close();
    }
  }

  private void assertEnded(final InputStream input) throws IOException {
    try {
      final int nextByte = input.read();
      assertEquals(-1, nextByte);
    } catch (final SocketException exception) {
      // a closed connection may be reset instead
    }
  }

  private boolean refusesConnections(final InetAddress address, final int port) {
    try (final Socket _ = new Socket(address, port)) {
      return false;
    } catch (final ConnectException exception) {
      return true;
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private byte[] cookieOf(final Path authority) throws IOException {
    final byte[] entry = Files.readAllBytes(authority);
    return Arrays.copyOfRange(entry, entry.length - NullDisplay.COOKIE_BYTES, entry.length);
  }

  private Socket introduce(final InetAddress loopback, final int port, final byte[] cookie) throws IOException {
    final Socket client = new Socket(loopback, port);
    client.setSoTimeout(READ_TIMEOUT_MILLISECONDS);
    client.getOutputStream().write(setup(cookie));
    new DataInputStream(client.getInputStream()).readFully(new byte[NullDisplay.createSetupReply(ByteOrder.LITTLE_ENDIAN).limit()]);

    return client;
  }

  private boolean internsAnAtom(final Socket client) throws IOException {
    client.getOutputStream().write(this.internAtom(ByteOrder.LITTLE_ENDIAN, PRIMARY_ATOM_NAME));
    final byte[] reply = new byte[REPLY_BYTES];
    new DataInputStream(client.getInputStream()).readFully(reply);

    return reply[0] == 1;
  }

  private boolean isServed(final Socket client) {
    try {
      return this.internsAnAtom(client);
    } catch (final IOException exception) {
      return false;
    }
  }

  // A setup is answered before its client's thread takes a slot, so any client may be ended; with one over the limit, exactly one ends.
  @Test
  void shouldEndClientsWithTheCookieBeyondTheLimitAndFreeThePlaceOfOneThatLeaves() throws IOException {
    final Path authority = this.folder.resolve(AUTHORITY_FILE);
    final List<Socket> clients = new ArrayList<>();
    try (final NullDisplay display = NullDisplay.start(authority)) {
      final int port = NullDisplay.X11_BASE_PORT + Integer.parseInt(display.getDisplay().substring(DISPLAY_PREFIX.length()));
      final InetAddress loopback = InetAddress.getByAddress(new byte[] { LOOPBACK_FIRST_OCTET, 0, 0, 1 });
      final byte[] cookie = this.cookieOf(authority);
      for (int count = 0; count <= NullDisplay.MAXIMUM_CONNECTIONS; count++) {
        clients.add(this.introduce(loopback, port, cookie));
      }
      final List<Socket> served = this.servedClients(clients);
      final int servedCount = served.size();
      assertEquals(NullDisplay.MAXIMUM_CONNECTIONS, servedCount, "one client more than the limit is ended");

      served.getFirst().close();
      Await.until("a place for another client", () -> this.isANewClientServed(loopback, port, cookie));
    } finally {
      for (final Socket client : clients) {
        client.close();
      }
    }
  }

  private List<Socket> servedClients(final List<Socket> clients) {
    final List<Socket> served = new ArrayList<>();
    for (final Socket client : clients) {
      if (this.isServed(client)) {
        served.add(client);
      }
    }

    return served;
  }

  private boolean isANewClientServed(final InetAddress loopback, final int port, final byte[] cookie) {
    try (final Socket next = this.introduce(loopback, port, cookie)) {
      return this.internsAnAtom(next);
    } catch (final IOException exception) {
      return false;
    }
  }

  @Test
  void shouldNotLetClientsWithoutTheCookieKeepOneThatHasItOut() throws IOException {
    final Path authority = this.folder.resolve(AUTHORITY_FILE);
    final List<Socket> silent = new ArrayList<>();
    // no setup timeout: on a busy machine, the default ten seconds can pass while the clients connect
    try (final NullDisplay display = NullDisplay.start(authority, 0)) {
      final int port = NullDisplay.X11_BASE_PORT + Integer.parseInt(display.getDisplay().substring(DISPLAY_PREFIX.length()));
      final InetAddress loopback = InetAddress.getByAddress(new byte[] { LOOPBACK_FIRST_OCTET, 0, 0, 1 });
      for (int count = 0; count < NullDisplay.MAXIMUM_PENDING; count++) {
        final Socket client = new Socket(loopback, port);
        client.setSoTimeout(READ_TIMEOUT_MILLISECONDS);
        silent.add(client);
      }
      try (final Socket introduced = this.introduce(loopback, port, this.cookieOf(authority))) {
        final boolean atomInterned = this.internsAnAtom(introduced);
        assertTrue(atomInterned, SERVED_CLIENT_MESSAGE);
      }
      this.assertEnded(silent.getFirst().getInputStream());
      final Socket second = silent.get(1);
      second.setSoTimeout(WAITING_CLIENT_TIMEOUT_MILLISECONDS);
      assertThrows(SocketTimeoutException.class, () -> second.getInputStream().read(), "the second client still waits");
    } finally {
      for (final Socket client : silent) {
        client.close();
      }
    }
  }

  @Test
  void shouldRemoveAClientRefusedInItsSetupFromTheQueueOfWaitingClients() throws IOException {
    final Path authority = this.folder.resolve(AUTHORITY_FILE);
    final List<Socket> silent = new ArrayList<>();
    // no setup timeout, as above: only a refusal or an eviction ends a client here
    try (final NullDisplay display = NullDisplay.start(authority, 0)) {
      final int port = NullDisplay.X11_BASE_PORT + Integer.parseInt(display.getDisplay().substring(DISPLAY_PREFIX.length()));
      final InetAddress loopback = InetAddress.getByAddress(new byte[] { LOOPBACK_FIRST_OCTET, 0, 0, 1 });
      for (int count = 1; count < NullDisplay.MAXIMUM_PENDING; count++) {
        final Socket client = new Socket(loopback, port);
        client.setSoTimeout(READ_TIMEOUT_MILLISECONDS);
        silent.add(client);
      }
      try (final Socket refused = new Socket(loopback, port)) {
        refused.setSoTimeout(READ_TIMEOUT_MILLISECONDS);
        refused.getOutputStream().write(setup(new byte[NullDisplay.COOKIE_BYTES]));
        final byte[] answer = refused.getInputStream().readAllBytes();
        final boolean refusalArrived = answer.length >= 8;
        assertTrue(refusalArrived, "the refusal arrives");

        final byte status = answer[0];
        assertEquals(0, status, "an X11 setup that failed");

        final boolean namesTheMissingCookie = new String(answer, StandardCharsets.US_ASCII).contains(MISSING_COOKIE_REASON);
        assertTrue(namesTheMissingCookie);
      }
      Await.until("the refused client is gone", () -> display.countClients() == NullDisplay.MAXIMUM_PENDING - 1);
      try (final Socket introduced = this.introduce(loopback, port, this.cookieOf(authority))) {
        final boolean atomInterned = this.internsAnAtom(introduced);
        assertTrue(atomInterned, SERVED_CLIENT_MESSAGE);
      }
      final Socket oldest = silent.getFirst();
      oldest.setSoTimeout(WAITING_CLIENT_TIMEOUT_MILLISECONDS);
      assertThrows(SocketTimeoutException.class, () -> oldest.getInputStream().read(), OLDEST_WAITING_CLIENT_MESSAGE);
    } finally {
      for (final Socket client : silent) {
        client.close();
      }
    }
  }

  @Test
  void shouldRemoveAServedClientFromTheQueueOfWaitingClients() throws IOException {
    final Path authority = this.folder.resolve(AUTHORITY_FILE);
    final List<Socket> silent = new ArrayList<>();
    // no setup timeout, as above: only an eviction ends a client here
    try (final NullDisplay display = NullDisplay.start(authority, 0)) {
      final int port = NullDisplay.X11_BASE_PORT + Integer.parseInt(display.getDisplay().substring(DISPLAY_PREFIX.length()));
      final InetAddress loopback = InetAddress.getByAddress(new byte[] { LOOPBACK_FIRST_OCTET, 0, 0, 1 });
      for (int count = 2; count < NullDisplay.MAXIMUM_PENDING; count++) {
        final Socket client = new Socket(loopback, port);
        client.setSoTimeout(READ_TIMEOUT_MILLISECONDS);
        silent.add(client);
      }
      try (final Socket served = this.introduce(loopback, port, this.cookieOf(authority))) {
        final boolean servedInternsAtom = this.internsAnAtom(served);
        assertTrue(servedInternsAtom, "the client with the cookie is served, and stays");

        final Socket newcomer = new Socket(loopback, port);
        newcomer.setSoTimeout(READ_TIMEOUT_MILLISECONDS);
        silent.add(newcomer);
        this.assertASecondClientIsServed(loopback, port, authority);
      }
      final Socket oldest = silent.getFirst();
      oldest.setSoTimeout(WAITING_CLIENT_TIMEOUT_MILLISECONDS);
      assertThrows(SocketTimeoutException.class, () -> oldest.getInputStream().read(), OLDEST_WAITING_CLIENT_MESSAGE);
    } finally {
      for (final Socket client : silent) {
        client.close();
      }
    }
  }

  private void assertASecondClientIsServed(final InetAddress loopback, final int port, final Path authority) throws IOException {
    try (final Socket second = this.introduce(loopback, port, this.cookieOf(authority))) {
      final boolean secondInternsAtom = this.internsAnAtom(second);
      assertTrue(secondInternsAtom, "the second client with the cookie is served");
    }
  }

  @Test
  void shouldCreateNoAtomForAClientThatOnlyAsksWhetherItExists() {
    final NullDisplay.Atoms atoms = new NullDisplay.Atoms();
    final ByteBuffer asked = NullDisplay.answer(
      16,
      1,
      this.requestOf(this.internAtom(ByteOrder.LITTLE_ENDIAN, "MCAV_NEW")),
      1,
      ByteOrder.LITTLE_ENDIAN,
      atoms
    );
    final int askedAtom = asked.getInt(8);
    assertEquals(0, askedAtom, "None for a name nobody interned");

    final int foundMissingAtom = atoms.find("MCAV_NEW");
    assertEquals(0, foundMissingAtom, "and nothing interned");

    final ByteBuffer interned = NullDisplay.answer(
      16,
      0,
      this.requestOf(this.internAtom(ByteOrder.LITTLE_ENDIAN, "MCAV_NEW")),
      2,
      ByteOrder.LITTLE_ENDIAN,
      atoms
    );
    final int atom = interned.getInt(8);
    final int existingAtom = NullDisplay.answer(
      16,
      1,
      this.requestOf(this.internAtom(ByteOrder.LITTLE_ENDIAN, "MCAV_NEW")),
      3,
      ByteOrder.LITTLE_ENDIAN,
      atoms
    ).getInt(8);
    assertEquals(atom, existingAtom);

    final int foundExistingAtom = atoms.find("MCAV_NEW");
    assertEquals(atom, foundExistingAtom);
  }

  // skips the opcode, data byte and length
  private ByteBuffer requestOf(final byte[] request) {
    return ByteBuffer.wrap(Arrays.copyOfRange(request, 4, request.length)).order(ByteOrder.LITTLE_ENDIAN);
  }

  @Test
  void shouldNotOverwriteAnAuthorityFileThatExistsAndLeaveNoPortOpen() throws IOException {
    final Path authority = Files.writeString(this.folder.resolve(AUTHORITY_FILE), "someone else's");
    OpenFiles.assertLeavesNoneOpen("a display that cannot write its file", () -> this.assertStartFails(authority));
    final String authorityText = Files.readString(authority);
    assertEquals("someone else's", authorityText);
  }

  private void assertStartFails(final Path authority) {
    assertThrows(IOException.class, () -> NullDisplay.start(authority));
  }

  @Test
  void shouldOnlyUsePortsThatADisplayNumberCanName() throws IOException {
    final boolean belowBaseIsDisplayPort = NullDisplay.isDisplayPort(NullDisplay.X11_BASE_PORT - 1);
    assertFalse(belowBaseIsDisplayPort);

    final boolean baseIsDisplayPort = NullDisplay.isDisplayPort(NullDisplay.X11_BASE_PORT);
    assertTrue(baseIsDisplayPort);

    final AtomicInteger asked = new AtomicInteger();
    final IOException none = assertThrows(IOException.class, () -> NullDisplay.bind(_ -> this.countAndRefuse(asked)));
    final String message = none.getMessage();
    assertEquals("The system gives out no port of at least 6000 for the display!", message);

    final int askedPorts = asked.get();
    assertEquals(64, askedPorts, "64 ports are tried");

    final AtomicInteger tries = new AtomicInteger();
    final List<Integer> refused = new ArrayList<>();
    try (final ServerSocket third = NullDisplay.bind(port -> this.refuseTheFirstTwoPorts(tries, refused, port))) {
      final int tryCount = tries.get();
      assertEquals(3, tryCount);

      final boolean thirdPortRefused = refused.contains(third.getLocalPort());
      assertFalse(thirdPortRefused, "a refused port is kept until the search ends");

      final InetAddress boundAddress = third.getInetAddress();
      final InetAddress expectedBoundAddress = InetAddress.getByAddress(new byte[] { LOOPBACK_FIRST_OCTET, 0, 0, 1 });
      assertEquals(expectedBoundAddress, boundAddress);
    }

    for (final int port : refused) {
      final ServerSocket again = new ServerSocket(port, 1, InetAddress.getByAddress(new byte[] { LOOPBACK_FIRST_OCTET, 0, 0, 1 }));
      again.close();
    }
  }

  private boolean countAndRefuse(final AtomicInteger asked) {
    asked.incrementAndGet();

    return false;
  }

  private boolean refuseTheFirstTwoPorts(final AtomicInteger tries, final List<Integer> refused, final int port) {
    if (tries.incrementAndGet() < 3) {
      refused.add(port);

      return false;
    }

    return true;
  }

  @Test
  void shouldGiveEveryDisplayARandomCookieOfItsOwn() {
    final byte[] first = NullDisplay.createCookie();
    assertEquals(NullDisplay.COOKIE_BYTES, first.length);

    final boolean sameAsAnotherCookie = Arrays.equals(first, NullDisplay.createCookie());
    assertFalse(sameAsAnotherCookie, "two displays got the same cookie");

    final boolean allZeros = Arrays.equals(new byte[NullDisplay.COOKIE_BYTES], first);
    assertFalse(allZeros, "the cookie is all zeros");
  }

  @Test
  void shouldMatchEveryDisplayWithOneCookieInTheAuthorityEntry() {
    final byte[] entry = NullDisplay.createAuthorityEntry(COOKIE);
    final ByteBuffer read = ByteBuffer.wrap(entry);
    final short family = read.getShort();
    assertEquals((short) 0xFFFF, family, "FamilyWild");

    final short addressLength = read.getShort();
    assertEquals(0, addressLength, "no address");

    final short displayNumberLength = read.getShort();
    assertEquals(0, displayNumberLength, "no display number");

    final byte[] name = new byte[read.getShort()];
    read.get(name);
    final String authorizationName = new String(name, StandardCharsets.US_ASCII);
    assertEquals(COOKIE_NAME, authorizationName);

    final byte[] cookie = new byte[read.getShort()];
    read.get(cookie);
    assertArrayEquals(COOKIE, cookie);

    final boolean hasTrailingBytes = read.hasRemaining();
    assertFalse(hasTrailingBytes);
  }

  @Test
  void shouldWriteTheAuthorityFileOnFileSystemsWithoutPosixPermissions() throws IOException {
    final Path archive = this.folder.resolve(PLAIN_ARCHIVE_FILE);
    try (final FileSystem zip = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
      final boolean supportsPosix = zip.supportedFileAttributeViews().contains(POSIX_ATTRIBUTE_VIEW);
      assertFalse(supportsPosix);

      final Path authority = zip.getPath(ZIPPED_AUTHORITY_FILE);
      NullDisplay.writeAuthority(authority, COOKIE);
      final byte[] authorityEntry = Files.readAllBytes(authority);
      final byte[] expectedAuthorityEntry = NullDisplay.createAuthorityEntry(COOKIE);
      assertArrayEquals(expectedAuthorityEntry, authorityEntry);
    }
  }

  @Test
  void shouldGiveOutAnotherPortForEachNewDisplay() throws IOException {
    try (
      final NullDisplay first = NullDisplay.start(this.folder.resolve(FIRST_DISPLAY_FOLDER));
      final NullDisplay second = NullDisplay.start(this.folder.resolve(SECOND_DISPLAY_FOLDER))
    ) {
      final String secondDisplayName = second.getDisplay();
      final String firstDisplayName = first.getDisplay();
      assertNotEquals(firstDisplayName, secondDisplayName);
    }
  }

  @Test
  void shouldNameADisplayForChromiumAndGiveNoDisplayNoName(@TempDir final Path directory) throws IOException {
    final @Nullable String nameOfNone = NullDisplay.nameOf(null);
    assertNull(nameOfNone);

    try (final NullDisplay display = NullDisplay.start(directory.resolve(AUTHORITY_FILE))) {
      final @Nullable String displayName = NullDisplay.nameOf(display);
      final String expectedDisplayName = display.getDisplay();
      assertEquals(expectedDisplayName, displayName);
    }
  }

  @Test
  void shouldEndTheConnectionOfAClientThatBreaksTheProtocolAndKeepTheDisplayForOthers(@TempDir final Path directory) throws IOException {
    try (final NullDisplay display = NullDisplay.start(directory.resolve(AUTHORITY_FILE))) {
      final int port = NullDisplay.X11_BASE_PORT + Integer.parseInt(display.getDisplay().substring(DISPLAY_PREFIX.length()));
      try (final Socket broken = new Socket(InetAddress.getLoopbackAddress(), port)) {
        broken.setSoTimeout(READ_TIMEOUT_MILLISECONDS);
        broken.getOutputStream().write(new byte[] { 'X', 0, PROTOCOL_MAJOR_VERSION, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
        final int nextByte = broken.getInputStream().read();
        assertEquals(-1, nextByte, "the display ends the connection");
      }
      final byte[] entry = Files.readAllBytes(directory.resolve(AUTHORITY_FILE));
      final byte[] cookie = Arrays.copyOfRange(entry, entry.length - NullDisplay.COOKIE_BYTES, entry.length);
      try (final Socket next = new Socket(InetAddress.getLoopbackAddress(), port)) {
        next.setSoTimeout(READ_TIMEOUT_MILLISECONDS);
        next.getOutputStream().write(setup(cookie));
        final byte[] accepted = new byte[NullDisplay.createSetupReply(ByteOrder.LITTLE_ENDIAN).limit()];
        new DataInputStream(next.getInputStream()).readFully(accepted);
        final byte status = accepted[0];
        assertEquals(1, status, "the display still takes clients");
      }
      Await.until(CLIENT_GONE_MESSAGE, () -> display.countClients() == 0);
    }
  }

  @Test
  void shouldEndTheConnectionOfAClientThatDoesNotIntroduceItselfInTimeAndLetOneThatDidIdle(@TempDir final Path directory)
    throws IOException, InterruptedException {
    final Path authority = directory.resolve(AUTHORITY_FILE);
    try (final NullDisplay display = NullDisplay.start(authority, 200)) {
      final int port = NullDisplay.X11_BASE_PORT + Integer.parseInt(display.getDisplay().substring(DISPLAY_PREFIX.length()));
      final byte[] entry = Files.readAllBytes(authority);
      final byte[] cookie = Arrays.copyOfRange(entry, entry.length - NullDisplay.COOKIE_BYTES, entry.length);
      try (
        final Socket silent = new Socket(InetAddress.getLoopbackAddress(), port);
        final Socket introduced = new Socket(InetAddress.getLoopbackAddress(), port)
      ) {
        silent.setSoTimeout(READ_TIMEOUT_MILLISECONDS);
        introduced.setSoTimeout(READ_TIMEOUT_MILLISECONDS);
        introduced.getOutputStream().write(setup(cookie));
        final DataInputStream input = new DataInputStream(introduced.getInputStream());
        input.readFully(new byte[NullDisplay.createSetupReply(ByteOrder.LITTLE_ENDIAN).limit()]);
        final int nextByte = silent.getInputStream().read();
        assertEquals(-1, nextByte, "the display ends a connection that never introduced itself");

        Thread.sleep(600L);
        introduced.getOutputStream().write(this.internAtom(ByteOrder.LITTLE_ENDIAN, CLIPBOARD_ATOM_NAME));
        final byte[] atom = new byte[REPLY_BYTES];
        input.readFully(atom);
        final byte responseType = atom[0];
        assertEquals(1, responseType, REPLY_MESSAGE);
      }
    }
  }

  @FunctionalInterface
  private interface IoSupplier {
    ByteBuffer get() throws IOException;
  }
}
