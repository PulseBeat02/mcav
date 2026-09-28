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

import static me.brandonli.mcav.vnc.RfbSession.MS_LOGON;
import static me.brandonli.mcav.vnc.RfbSession.NONE;
import static me.brandonli.mcav.vnc.RfbSession.VNC;
import static me.brandonli.mcav.vnc.RfbSession.concat;
import static me.brandonli.mcav.vnc.RfbSession.cutText;
import static me.brandonli.mcav.vnc.RfbSession.rectangle;
import static me.brandonli.mcav.vnc.RfbSession.serverInit;
import static me.brandonli.mcav.vnc.RfbSession.setPixelFormat;
import static me.brandonli.mcav.vnc.RfbSession.u16;
import static me.brandonli.mcav.vnc.RfbSession.u32;
import static me.brandonli.mcav.vnc.RfbSession.update;
import static me.brandonli.mcav.vnc.RfbSession.version;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests {@link RfbGuard}: every handshake the client supports passes, every message and encoding is framed so the
 * messages after it pass too, and every length or size above its bound, and every byte the protocol does not allow,
 * is refused before the client would read it.
 */
final class RfbGuardTest {

  private static final int SIDE = 64;

  private static RfbSession connected() throws IOException {
    final RfbSession session = new RfbSession();
    session.handshake(8, NONE, SIDE, SIDE);
    return session;
  }

  private static void assertRefused(final RfbSession session, final byte[] bytes, final String reason) {
    final IOException refused = assertThrows(IOException.class, () -> session.server(bytes));
    final String message = refused.getMessage();
    assertTrue(message.contains(reason), message);
  }

  /** The guard waits for a server message: a bell passes, and the byte after it is refused as a message type. */
  private static void assertAtAMessage(final RfbSession session) throws IOException {
    session.server(new byte[] { 2 });
    assertRefused(session, new byte[] { 9 }, "message type 9");
  }

  /** Data the guard only counts, of a byte that is no message type, so data read as a message is refused. */
  private static byte[] pixels(final int length) {
    final byte[] bytes = new byte[length];
    Arrays.fill(bytes, (byte) 0x55);
    return bytes;
  }

  @ParameterizedTest
  @ValueSource(ints = { 3, 7, 8 })
  void passesTheHandshakeWithoutSecurityOfEveryVersion(final int minor) throws IOException {
    final RfbSession session = new RfbSession();
    assertDoesNotThrow(() -> session.handshake(minor, NONE, SIDE, SIDE));
    assertAtAMessage(session);
  }

  @ParameterizedTest
  @ValueSource(ints = { 3, 7, 8 })
  void passesTheHandshakeWithVncAuthenticationOfEveryVersion(final int minor) throws IOException {
    final RfbSession session = new RfbSession();
    assertDoesNotThrow(() -> session.handshake(minor, VNC, SIDE, SIDE));
    assertAtAMessage(session);
  }

  @Test
  void passesTheMsLogonHandshake() throws IOException {
    final RfbSession session = new RfbSession();
    assertDoesNotThrow(() -> session.handshake(8, MS_LOGON, SIDE, SIDE));
    assertAtAMessage(session);
  }

  @Test
  void refusesSecurityDataBeforeTheClientChoseAVersion() throws IOException {
    final RfbSession session = new RfbSession();
    session.server(version(8));
    assertRefused(session, new byte[] { 1, NONE }, "before the client chose a version");
  }

  @Test
  void refusesAChallengeBeforeTheClientChoseASecurityType() throws IOException {
    final RfbSession session = new RfbSession();
    session.server(version(8));
    session.client(version(8));
    session.server(new byte[] { 1, VNC });
    assertRefused(session, new byte[16], "before the client chose a security type");
  }

  @Test
  void refusesAVersionOtherThanThree() {
    final RfbSession session = new RfbSession();
    assertRefused(session, "RFB 004.001\n".getBytes(StandardCharsets.US_ASCII), "version");
  }

  @Test
  void refusesAVersionWithoutItsNewline() {
    final RfbSession session = new RfbSession();
    assertRefused(session, "RFB 003.008 ".getBytes(StandardCharsets.US_ASCII), "version");
  }

  @Test
  void refusesASecurityTypeTheClientDoesNotSupport() throws IOException {
    final RfbSession session = new RfbSession();
    session.server(version(3));
    session.client(version(3));
    assertRefused(session, u32(19), "security type 19");
  }

  @ParameterizedTest
  @ValueSource(ints = { 3, 8 })
  void acceptsAFailureReasonAndNothingAfterIt(final int minor) throws IOException {
    final RfbSession session = new RfbSession();
    session.server(version(minor));
    session.client(version(minor));
    final byte[] refusal = minor == 3 ? u32(0) : new byte[] { 0 };
    session.server(concat(refusal, u32(4), new byte[] { 'n', 'o', 'p', 'e' }));
    assertRefused(session, new byte[] { 1 }, "after the connection ended");
  }

  @Test
  void acceptsAnEmptyFailureReason() throws IOException {
    final RfbSession session = new RfbSession();
    session.server(version(8));
    session.client(version(8));
    session.server(concat(new byte[] { 0 }, u32(0)));
    assertRefused(session, new byte[] { 1 }, "after the connection ended");
  }

  @Test
  void refusesAFailureReasonAboveItsBound() throws IOException {
    final RfbSession session = new RfbSession();
    session.server(version(8));
    session.client(version(8));
    assertRefused(session, concat(new byte[] { 0 }, u32(RfbGuard.MAX_REASON + 1)), "failure reason");
  }

  @Test
  void endsAfterAFailedAuthenticationOfVersion37() throws IOException {
    final RfbSession session = new RfbSession();
    session.server(version(7));
    session.client(version(7));
    session.server(new byte[] { 1, VNC });
    session.client(new byte[] { VNC });
    session.server(new byte[16]);
    session.client(new byte[16]);
    session.server(u32(1));
    assertRefused(session, new byte[] { 0 }, "after the connection ended");
  }

  @Test
  void readsTheReasonOfAFailedAuthenticationOfVersion38() throws IOException {
    final RfbSession session = new RfbSession();
    session.server(version(8));
    session.client(version(8));
    session.server(new byte[] { 1, VNC });
    session.client(new byte[] { VNC });
    session.server(new byte[16]);
    session.client(new byte[16]);
    session.server(concat(u32(1), u32(2), new byte[] { 'n', 'o' }));
    assertRefused(session, new byte[] { 0 }, "after the connection ended");
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, RfbGuard.MAX_SIDE + 1 })
  void refusesAFramebufferWidthOutsideItsBounds(final int width) throws IOException {
    final RfbSession session = new RfbSession();
    session.server(version(3));
    session.client(version(3));
    session.server(u32(NONE));
    session.client(new byte[] { 1 });
    assertRefused(session, serverInit(width, SIDE, 32, "desktop"), "framebuffer");
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, RfbGuard.MAX_SIDE + 1 })
  void refusesAFramebufferHeightOutsideItsBounds(final int height) throws IOException {
    final RfbSession session = new RfbSession();
    session.server(version(3));
    session.client(version(3));
    session.server(u32(NONE));
    session.client(new byte[] { 1 });
    assertRefused(session, serverInit(SIDE, height, 32, "desktop"), "framebuffer");
  }

  @Test
  void refusesPixelsOfAnUnsupportedWidth() throws IOException {
    final RfbSession session = new RfbSession();
    session.server(version(3));
    session.client(version(3));
    session.server(u32(NONE));
    session.client(new byte[] { 1 });
    assertRefused(session, serverInit(SIDE, SIDE, 24, "desktop"), "24 bits per pixel");
  }

  @Test
  void refusesADesktopNameAboveItsBoundBeforeItsFirstByte() throws IOException {
    final RfbSession session = new RfbSession();
    session.server(version(3));
    session.client(version(3));
    session.server(u32(NONE));
    session.client(new byte[] { 1 });
    // only the length arrives: the guard refuses it before the client could allocate the name
    final byte[] header = concat(u16(SIDE), u16(SIDE), RfbSession.pixelFormat(32), u32(RfbGuard.MAX_NAME + 1L));
    assertRefused(session, header, "desktop name");
  }

  @Test
  void acceptsClipboardTextWithinItsBound() throws IOException {
    final RfbSession session = connected();
    session.server(cutText(RfbGuard.MAX_TEXT));
    session.server(cutText(0));
    assertAtAMessage(session);
  }

  @Test
  void refusesClipboardTextAboveItsBound() throws IOException {
    final RfbSession session = connected();
    assertRefused(session, concat(new byte[] { 3, 0, 0, 0 }, u32(0xFFFFFFFFL)), "clipboard text");
  }

  @Test
  void framesColourMapEntries() throws IOException {
    final RfbSession session = connected();
    session.server(concat(new byte[] { 1, 0 }, u16(0), u16(2), new byte[12]));
    assertAtAMessage(session);
  }

  @Test
  void refusesAnUnknownMessageType() throws IOException {
    final RfbSession session = connected();
    assertRefused(session, new byte[] { (byte) 255 }, "message type 255");
  }

  @Test
  void framesRawRectanglesInTheClientsPixelFormat() throws IOException {
    final RfbSession session = connected();
    session.server(concat(update(2), rectangle(0, 0, 3, 2, 0), pixels(3 * 2 * 4), rectangle(1, 1, 0, 0, 0)));
    assertAtAMessage(session);
  }

  @Test
  void framesRawRectanglesInTheServersPixelFormatUntilTheClientChoseOne() throws IOException {
    final RfbSession session = new RfbSession();
    session.server(version(3));
    session.client(version(3));
    session.server(u32(NONE));
    session.client(new byte[] { 1 });
    session.server(serverInit(SIDE, SIDE, 16, "desktop"));
    session.server(concat(update(1), rectangle(0, 0, 2, 2, 0), pixels(2 * 2 * 2)));
    assertAtAMessage(session);
  }

  @Test
  void ignoresAPixelFormatOfAnUnsupportedWidthFromTheClient() throws IOException {
    final RfbSession session = new RfbSession();
    session.server(version(3));
    session.client(version(3));
    session.server(u32(NONE));
    session.client(new byte[] { 1 });
    session.server(serverInit(SIDE, SIDE, 8, "desktop"));
    session.client(setPixelFormat(24));
    session.server(concat(update(1), rectangle(0, 0, 2, 2, 0), pixels(2 * 2)));
    assertAtAMessage(session);
  }

  @Test
  void framesCopyRectangles() throws IOException {
    final RfbSession session = connected();
    session.server(concat(update(1), rectangle(0, 0, 8, 8, 1), u16(8), u16(8)));
    assertAtAMessage(session);
  }

  @Test
  void framesRreRectangles() throws IOException {
    final RfbSession session = connected();
    session.server(concat(update(1), rectangle(0, 0, 4, 4, 2), u32(2), pixels(4), pixels(2 * (4 + 8))));
    assertAtAMessage(session);
  }

  @Test
  void refusesMoreRreSubrectanglesThanPixels() throws IOException {
    final RfbSession session = connected();
    assertRefused(session, concat(update(1), rectangle(0, 0, 2, 2, 2), u32(5), new byte[4]), "RRE subrectangles");
  }

  @Test
  void framesEveryKindOfHextileTile() throws IOException {
    final RfbSession session = connected();
    // a 20x17 rectangle is four tiles: 16x16, 4x16, 16x1 and 4x1
    final byte[] raw = concat(new byte[] { 1 }, pixels(16 * 16 * 4));
    final byte[] colours = new byte[] { 2 | 4, 0, 0, 0, 0, 0, 0, 0, 0 };
    final byte[] coloured = concat(new byte[] { 8 | 16, 1 }, pixels(4 + 2));
    final byte[] plain = concat(new byte[] { 2 | 8, 0, 0, 0, 0, 1 }, pixels(2));
    session.server(concat(update(1), rectangle(0, 0, 20, 17, 5), raw, colours, coloured, plain));
    session.server(concat(update(1), rectangle(0, 0, 16, 16, 5), new byte[] { 0 }));
    assertAtAMessage(session);
  }

  @Test
  void framesZlibRectangles() throws IOException {
    final RfbSession session = connected();
    session.server(concat(update(1), rectangle(0, 0, 4, 4, 6), u32(10), pixels(10)));
    session.server(concat(update(1), rectangle(0, 0, 4, 4, 6), u32(0)));
    assertAtAMessage(session);
  }

  @Test
  void refusesZlibDataFarLargerThanItsPixels() throws IOException {
    final RfbSession session = connected();
    final long limit = 4L * 4 * 4 + RfbGuard.ZLIB_SLACK;
    assertRefused(session, concat(update(1), rectangle(0, 0, 4, 4, 6), u32(limit + 1)), "zlib data");
  }

  @Test
  void followsANewDesktopSizeWithinItsBounds() throws IOException {
    final RfbSession session = connected();
    session.server(concat(update(1), rectangle(0, 0, SIDE * 2, SIDE, -223)));
    session.server(concat(update(1), rectangle(SIDE, 0, SIDE, 1, 1), u32(0)));
    assertRefused(session, concat(update(1), rectangle(0, 0, RfbGuard.MAX_SIDE + 1, SIDE, -223)), "framebuffer");
  }

  @Test
  void framesCursors() throws IOException {
    final RfbSession session = connected();
    session.server(concat(update(1), rectangle(0, 0, 9, 2, -239), pixels(9 * 2 * 4 + 2 * 2)));
    session.server(concat(update(1), rectangle(0, 0, 0, 0, -239)));
    assertAtAMessage(session);
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, 1 })
  void refusesACursorAboveItsBound(final int tallInsteadOfWide) throws IOException {
    final RfbSession session = connected();
    final int wide = tallInsteadOfWide == 1 ? 1 : RfbGuard.MAX_SIDE + 1;
    final int tall = tallInsteadOfWide == 1 ? RfbGuard.MAX_SIDE + 1 : 1;
    assertRefused(session, concat(update(1), rectangle(0, 0, wide, tall, -239)), "cursor");
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, 1 })
  void refusesARectangleOutsideTheFramebuffer(final int belowInsteadOfRight) throws IOException {
    final RfbSession session = connected();
    final int x = belowInsteadOfRight == 1 ? 0 : SIDE - 1;
    final int y = belowInsteadOfRight == 1 ? SIDE - 1 : 0;
    assertRefused(session, concat(update(1), rectangle(x, y, 2, 2, 0)), "outside the framebuffer");
  }

  @Test
  void refusesAnEncodingTheClientCannotDecode() throws IOException {
    final RfbSession session = connected();
    assertRefused(session, concat(update(1), rectangle(0, 0, 2, 2, 16)), "encoding 16");
  }

  @Test
  void checksBytesThatArriveOneAtATime() throws IOException {
    final RfbSession session = connected();
    final byte[] update = concat(update(1), rectangle(0, 0, 2, 2, 0), pixels(2 * 2 * 4), cutText(3));
    for (final byte value : update) {
      session.server(new byte[] { value });
    }
    assertRefused(session, new byte[] { 9 }, "message type 9");
  }

  @Test
  void followsEveryMessageTheClientSends() throws IOException {
    final RfbSession session = connected();
    // a list of encodings split across writes
    session.client(concat(new byte[] { 2, 0 }, u16(2), u32(0)));
    session.client(u32(1));
    session.client(concat(new byte[] { 2, 0 }, u16(0)));
    session.client(concat(new byte[] { 3, 0 }, u16(0), u16(0), u16(SIDE), u16(SIDE)));
    session.client(concat(new byte[] { 4, 1, 0, 0 }, u32(65)));
    session.client(concat(new byte[] { 5, 0 }, u16(1), u16(1)));
    session.client(concat(new byte[] { 6, 0, 0, 0 }, u32(2), new byte[] { 'h', 'i' }));
    session.client(concat(new byte[] { 6, 0, 0, 0 }, u32(0)));
    session.client(setPixelFormat(16));
    // two bytes a pixel now
    session.server(concat(update(1), rectangle(0, 0, 2, 2, 0), pixels(2 * 2 * 2)));
    session.client(setPixelFormat(8));
    session.server(concat(update(1), rectangle(0, 0, 2, 2, 0), pixels(2 * 2)));
    assertAtAMessage(session);
  }

  @Test
  void stopsFollowingAClientThatSendsAnUnknownMessage() throws IOException {
    final RfbSession session = connected();
    session.client(new byte[] { 99, 1, 2, 3 });
    session.client(setPixelFormat(8));
    // the pixel format after the unknown message was not read: four bytes a pixel still
    session.server(concat(update(1), rectangle(0, 0, 1, 1, 0), pixels(4)));
    assertAtAMessage(session);
  }

  @ParameterizedTest
  @ValueSource(strings = { "RFB 003.0x8\n", "RFB 003.0 8\n" })
  void stopsFollowingAClientVersionThatIsNotANumber(final String clientVersion) throws IOException {
    final RfbSession session = new RfbSession();
    session.server(version(8));
    session.client(clientVersion.getBytes(StandardCharsets.US_ASCII));
    assertRefused(session, new byte[] { 1 }, "before the client chose a version");
  }

  @ParameterizedTest
  @CsvSource({ "0, 1", "3, 1", "3, 2", "7, 1", "7, 2", "8, 1", "8, 2", "8, 113", "9, 1", "10, 1" })
  void followsTheClientThroughEveryHandshakeToItsPixelFormat(final int minor, final int security) throws IOException {
    final RfbSession session = new RfbSession();
    session.handshake(minor, security, SIDE, SIDE, 8);
    session.server(concat(update(1), rectangle(0, 0, 3, 2, 0), pixels(3 * 2)));
    assertAtAMessage(session);
  }

  @Test
  void framesAListOfMoreSecurityTypesThanAFieldHolds() throws IOException {
    final RfbSession session = new RfbSession();
    session.server(version(8));
    session.client(version(8));
    final byte[] types = new byte[255];
    Arrays.fill(types, (byte) NONE);
    session.server(concat(new byte[] { (byte) types.length }, types));
    session.client(new byte[] { NONE });
    session.server(u32(0));
    session.client(new byte[] { 1 });
    session.server(serverInit(SIDE, SIDE, 32, "desktop"));
    assertAtAMessage(session);
  }

  @Test
  void followsTheClientPastEveryEncodingItLists() throws IOException {
    final RfbSession session = connected();
    session.client(concat(new byte[] { 2, 0 }, u16(3), pixels(3 * 4)));
    session.client(setPixelFormat(8));
    session.server(concat(update(1), rectangle(0, 0, 2, 2, 0), pixels(2 * 2)));
    assertAtAMessage(session);
  }

  @Test
  void followsServerBytesThatStartPartwayIntoABuffer() throws IOException {
    final RfbGuard guard = new RfbGuard();
    final byte[] version = version(8);
    guard.acceptServer(version, 0, version.length);
    guard.acceptClient(version, 0, version.length);
    guard.acceptServer(new byte[] { 9, 9, 1, NONE }, 2, 2);
    guard.acceptClient(new byte[] { NONE }, 0, 1);
    guard.acceptServer(concat(new byte[] { 9 }, u32(0)), 1, 4);
    guard.acceptClient(new byte[] { 1 }, 0, 1);
    final byte[] init = serverInit(SIDE, SIDE, 32, "desktop");
    guard.acceptServer(init, 0, init.length);
    guard.acceptServer(new byte[] { 2 }, 0, 1);
    final IOException refused = assertThrows(IOException.class, () -> guard.acceptServer(new byte[] { 9 }, 0, 1));
    assertTrue(refused.getMessage().contains("message type 9"), refused.getMessage());
  }

  @Test
  void acceptsAFailureReasonOfItsBound() throws IOException {
    final RfbSession session = new RfbSession();
    session.server(version(8));
    session.client(version(8));
    session.server(concat(new byte[] { 0 }, u32(RfbGuard.MAX_REASON), pixels(RfbGuard.MAX_REASON)));
    assertRefused(session, new byte[] { 2 }, "after the connection ended");
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, 1 })
  void acceptsAFramebufferOfOnePixelAcrossAndTheLargestSide(final int tallInsteadOfWide) throws IOException {
    final int width = tallInsteadOfWide == 1 ? 1 : RfbGuard.MAX_SIDE;
    final int height = tallInsteadOfWide == 1 ? RfbGuard.MAX_SIDE : 1;
    final RfbSession session = new RfbSession();
    session.handshake(8, NONE, width, height);
    session.server(concat(update(1), rectangle(width - 1, height - 1, 1, 1, 0), pixels(4)));
    assertAtAMessage(session);
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, 1 })
  void acceptsACursorOfTheLargestSide(final int tallInsteadOfWide) throws IOException {
    final RfbSession session = connected();
    final int wide = tallInsteadOfWide == 1 ? 1 : RfbGuard.MAX_SIDE;
    final int tall = tallInsteadOfWide == 1 ? RfbGuard.MAX_SIDE : 1;
    session.server(concat(update(1), rectangle(0, 0, wide, tall, -239), pixels(wide * tall * 4 + ((wide + 7) / 8) * tall)));
    assertAtAMessage(session);
  }

  @Test
  void acceptsZlibDataUpToItsBound() throws IOException {
    final RfbSession session = connected();
    final int limit = 16 * 16 * 4 + RfbGuard.ZLIB_SLACK;
    session.server(concat(update(1), rectangle(0, 0, 16, 16, 6), u32(limit), pixels(limit)));
    assertAtAMessage(session);
  }

  @Test
  void checksEveryByteReadOneAtATimeThroughItsStream() throws IOException {
    final RfbSession session = connected();
    final byte[] bytes = concat(update(1), rectangle(0, 0, 1, 1, 0), pixels(4), new byte[] { 9 });
    final InputStream input = session.guard().serverInput(new ByteArrayInputStream(bytes));
    for (int i = 0; i < bytes.length - 1; i++) {
      assertEquals(bytes[i] & 0xFF, input.read());
    }
    final IOException refused = assertThrows(IOException.class, input::read);
    assertTrue(refused.getMessage().contains("message type 9"), refused.getMessage());
  }

  @Test
  void passesTheBytesThroughItsStreamsUnchanged() throws IOException {
    final RfbGuard guard = new RfbGuard();
    final byte[] version = version(8);
    final ByteArrayOutputStream sent = new ByteArrayOutputStream();
    final OutputStream output = guard.clientOutput(sent);
    final InputStream input = guard.serverInput(new ByteArrayInputStream(concat(version, new byte[] { 1, NONE })));
    final byte[] read = input.readNBytes(version.length);
    output.write(version);
    output.write(NONE);
    assertArrayEquals(version, read);
    assertEquals(1, input.read());
    assertEquals(NONE, input.read());
    assertEquals(-1, input.read());
    assertEquals(-1, input.read(new byte[4], 0, 4));
    assertArrayEquals(concat(version, new byte[] { NONE }), sent.toByteArray());
    assertFalse(input.markSupported());
  }

  @Test
  void skipsByReadingThroughTheGuard() throws IOException {
    final RfbGuard guard = new RfbGuard();
    final InputStream input = guard.serverInput(new ByteArrayInputStream(concat(version(8), new byte[] { 1 })));
    assertEquals(12, input.skip(12));
    final IOException refused = assertThrows(IOException.class, () -> input.skip(1));
    assertTrue(refused.getMessage().contains("before the client chose a version"));
    assertEquals(0, input.skip(-1));
  }
}
