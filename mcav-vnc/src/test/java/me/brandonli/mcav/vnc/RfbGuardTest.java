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
import static me.brandonli.mcav.vnc.RfbSession.BITS_PER_PIXEL_16;
import static me.brandonli.mcav.vnc.RfbSession.BITS_PER_PIXEL_32;
import static me.brandonli.mcav.vnc.RfbSession.BITS_PER_PIXEL_8;
import static me.brandonli.mcav.vnc.RfbSession.COPY_RECTANGLE_ENCODING;
import static me.brandonli.mcav.vnc.RfbSession.CURSOR_ENCODING;
import static me.brandonli.mcav.vnc.RfbSession.DESKTOP_NAME;
import static me.brandonli.mcav.vnc.RfbSession.DESKTOP_SIZE_ENCODING;
import static me.brandonli.mcav.vnc.RfbSession.HEXTILE_BACKGROUND;
import static me.brandonli.mcav.vnc.RfbSession.HEXTILE_COLORED;
import static me.brandonli.mcav.vnc.RfbSession.HEXTILE_ENCODING;
import static me.brandonli.mcav.vnc.RfbSession.HEXTILE_FOREGROUND;
import static me.brandonli.mcav.vnc.RfbSession.HEXTILE_RAW;
import static me.brandonli.mcav.vnc.RfbSession.HEXTILE_SUBRECTANGLES;
import static me.brandonli.mcav.vnc.RfbSession.MINOR_VERSION_3_3;
import static me.brandonli.mcav.vnc.RfbSession.MINOR_VERSION_3_8;
import static me.brandonli.mcav.vnc.RfbSession.MS_LOGON;
import static me.brandonli.mcav.vnc.RfbSession.NONE;
import static me.brandonli.mcav.vnc.RfbSession.RAW_ENCODING;
import static me.brandonli.mcav.vnc.RfbSession.RRE_ENCODING;
import static me.brandonli.mcav.vnc.RfbSession.SERVER_CUT_TEXT;
import static me.brandonli.mcav.vnc.RfbSession.SET_COLOR_MAP_ENTRIES;
import static me.brandonli.mcav.vnc.RfbSession.SHARE_DESKTOP;
import static me.brandonli.mcav.vnc.RfbSession.VNC;
import static me.brandonli.mcav.vnc.RfbSession.ZLIB_ENCODING;
import static me.brandonli.mcav.vnc.RfbSession.concatenate;
import static me.brandonli.mcav.vnc.RfbSession.cutText;
import static me.brandonli.mcav.vnc.RfbSession.rectangle;
import static me.brandonli.mcav.vnc.RfbSession.serverInitialization;
import static me.brandonli.mcav.vnc.RfbSession.setPixelFormat;
import static me.brandonli.mcav.vnc.RfbSession.unsigned16;
import static me.brandonli.mcav.vnc.RfbSession.unsigned32;
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

// Tests RfbGuard: every handshake the client supports passes, every message and encoding is framed so the messages
// after it pass too, and every length or size above its bound, and every byte the protocol does not allow, is refused
// before the client would read it.
final class RfbGuardTest {

  private static final int SIDE = 64;

  private static final int TILE_SIDE = 16;
  private static final int PIXEL_BYTES = 4;

  private static final int CHALLENGE_BYTES = 16;

  private static final int UNSUPPORTED_BITS_PER_PIXEL = 24;

  private static final String UNKNOWN_MESSAGE_REASON = "message type 9";
  private static final String VERSION_REASON = "version";
  private static final String BEFORE_VERSION_REASON = "before the client chose a version";
  private static final String FRAMEBUFFER_REASON = "framebuffer";
  private static final String ENDED_REASON = "after the connection ended";

  private RfbSession connected() throws IOException {
    final RfbSession session = new RfbSession();
    session.handshake(MINOR_VERSION_3_8, NONE, SIDE, SIDE);
    return session;
  }

  private void assertRefused(final RfbSession session, final byte[] bytes, final String reason) {
    final IOException refused = assertThrows(IOException.class, () -> session.sendFromServer(bytes));
    final String message = refused.getMessage();
    final boolean named = message.contains(reason);
    assertTrue(named, message);
  }

  // The guard waits for a server message: a bell passes, and the byte after it is refused as a message type.
  private void assertAtAMessage(final RfbSession session) throws IOException {
    session.sendFromServer(new byte[] { BELL });
    this.assertRefused(session, new byte[] { 9 }, UNKNOWN_MESSAGE_REASON);
  }

  // Data the guard only counts, of a byte that is no message type, so data read as a message is refused.
  private byte[] pixels(final int length) {
    final byte[] bytes = new byte[length];
    Arrays.fill(bytes, (byte) 0x55);
    return bytes;
  }

  @ParameterizedTest
  @ValueSource(ints = { 3, 7, 8 })
  void shouldPassTheHandshakeWithoutSecurityOfEveryVersion(final int minor) throws IOException {
    final RfbSession session = new RfbSession();
    assertDoesNotThrow(() -> session.handshake(minor, NONE, SIDE, SIDE));
    this.assertAtAMessage(session);
  }

  @ParameterizedTest
  @ValueSource(ints = { 3, 7, 8 })
  void shouldPassTheHandshakeWithVncAuthenticationOfEveryVersion(final int minor) throws IOException {
    final RfbSession session = new RfbSession();
    assertDoesNotThrow(() -> session.handshake(minor, VNC, SIDE, SIDE));
    this.assertAtAMessage(session);
  }

  @Test
  void shouldPassTheMsLogonHandshake() throws IOException {
    final RfbSession session = new RfbSession();
    assertDoesNotThrow(() -> session.handshake(MINOR_VERSION_3_8, MS_LOGON, SIDE, SIDE));
    this.assertAtAMessage(session);
  }

  @Test
  void shouldRefuseSecurityDataBeforeTheClientChoseAVersion() throws IOException {
    final RfbSession session = new RfbSession();
    session.sendFromServer(version(MINOR_VERSION_3_8));
    this.assertRefused(session, new byte[] { 1, NONE }, BEFORE_VERSION_REASON);
  }

  @Test
  void shouldRefuseAChallengeBeforeTheClientChoseASecurityType() throws IOException {
    final RfbSession session = new RfbSession();
    session.sendFromServer(version(MINOR_VERSION_3_8));
    session.sendFromClient(version(MINOR_VERSION_3_8));
    session.sendFromServer(new byte[] { 1, VNC });
    this.assertRefused(session, new byte[CHALLENGE_BYTES], "before the client chose a security type");
  }

  @Test
  void shouldRefuseAVersionOtherThanThree() {
    final RfbSession session = new RfbSession();
    this.assertRefused(session, "RFB 004.001\n".getBytes(StandardCharsets.US_ASCII), VERSION_REASON);
  }

  @Test
  void shouldRefuseAVersionWithoutItsNewline() {
    final RfbSession session = new RfbSession();
    this.assertRefused(session, "RFB 003.008 ".getBytes(StandardCharsets.US_ASCII), VERSION_REASON);
  }

  @Test
  void shouldRefuseASecurityTypeTheClientDoesNotSupport() throws IOException {
    final RfbSession session = new RfbSession();
    session.sendFromServer(version(3));
    session.sendFromClient(version(3));
    this.assertRefused(session, unsigned32(19), "security type 19");
  }

  @Test
  void shouldRefuseTheInvalidSecurityTypeAsOneTheClientChose() throws IOException {
    final RfbSession session = new RfbSession();
    session.sendFromServer(version(MINOR_VERSION_3_8));
    session.sendFromClient(version(MINOR_VERSION_3_8));
    session.sendFromServer(new byte[] { 1, NONE });
    session.sendFromClient(new byte[] { 0 });
    this.assertRefused(session, unsigned32(0), "security type 0");
  }

  @ParameterizedTest
  @ValueSource(ints = { 3, 8 })
  void shouldAcceptAFailureReasonAndNothingAfterIt(final int minor) throws IOException {
    final RfbSession session = new RfbSession();
    session.sendFromServer(version(minor));
    session.sendFromClient(version(minor));
    final byte[] refusal = minor == MINOR_VERSION_3_3 ? unsigned32(0) : new byte[] { 0 };
    session.sendFromServer(concatenate(refusal, unsigned32(4), new byte[] { 'n', 'o', 'p', 'e' }));
    this.assertRefused(session, new byte[] { 1 }, ENDED_REASON);
  }

  @Test
  void shouldAcceptAnEmptyFailureReason() throws IOException {
    final RfbSession session = new RfbSession();
    session.sendFromServer(version(MINOR_VERSION_3_8));
    session.sendFromClient(version(MINOR_VERSION_3_8));
    session.sendFromServer(concatenate(new byte[] { 0 }, unsigned32(0)));
    this.assertRefused(session, new byte[] { 1 }, ENDED_REASON);
  }

  @Test
  void shouldRefuseAFailureReasonAboveItsBound() throws IOException {
    final RfbSession session = new RfbSession();
    session.sendFromServer(version(MINOR_VERSION_3_8));
    session.sendFromClient(version(MINOR_VERSION_3_8));
    this.assertRefused(session, concatenate(new byte[] { 0 }, unsigned32(RfbGuard.MAXIMUM_REASON + 1)), "failure reason");
  }

  @Test
  void shouldEndAfterAFailedAuthenticationOfVersion37() throws IOException {
    final RfbSession session = new RfbSession();
    session.sendFromServer(version(7));
    session.sendFromClient(version(7));
    session.sendFromServer(new byte[] { 1, VNC });
    session.sendFromClient(new byte[] { VNC });
    session.sendFromServer(new byte[CHALLENGE_BYTES]);
    session.sendFromClient(new byte[CHALLENGE_BYTES]);
    session.sendFromServer(unsigned32(1));
    this.assertRefused(session, new byte[] { 0 }, ENDED_REASON);
  }

  @Test
  void shouldReadTheReasonOfAFailedAuthenticationOfVersion38() throws IOException {
    final RfbSession session = new RfbSession();
    session.sendFromServer(version(MINOR_VERSION_3_8));
    session.sendFromClient(version(MINOR_VERSION_3_8));
    session.sendFromServer(new byte[] { 1, VNC });
    session.sendFromClient(new byte[] { VNC });
    session.sendFromServer(new byte[CHALLENGE_BYTES]);
    session.sendFromClient(new byte[CHALLENGE_BYTES]);
    session.sendFromServer(concatenate(unsigned32(1), unsigned32(2), new byte[] { 'n', 'o' }));
    this.assertRefused(session, new byte[] { 0 }, ENDED_REASON);
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, RfbGuard.MAXIMUM_SIDE + 1 })
  void shouldRefuseAFramebufferWidthOutsideItsBounds(final int width) throws IOException {
    final RfbSession session = new RfbSession();
    session.sendFromServer(version(3));
    session.sendFromClient(version(3));
    session.sendFromServer(unsigned32(NONE));
    session.sendFromClient(new byte[] { SHARE_DESKTOP });
    this.assertRefused(session, serverInitialization(width, SIDE, BITS_PER_PIXEL_32, DESKTOP_NAME), FRAMEBUFFER_REASON);
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, RfbGuard.MAXIMUM_SIDE + 1 })
  void shouldRefuseAFramebufferHeightOutsideItsBounds(final int height) throws IOException {
    final RfbSession session = new RfbSession();
    session.sendFromServer(version(3));
    session.sendFromClient(version(3));
    session.sendFromServer(unsigned32(NONE));
    session.sendFromClient(new byte[] { SHARE_DESKTOP });
    this.assertRefused(session, serverInitialization(SIDE, height, BITS_PER_PIXEL_32, DESKTOP_NAME), FRAMEBUFFER_REASON);
  }

  @Test
  void shouldRefusePixelsOfAnUnsupportedWidth() throws IOException {
    final RfbSession session = new RfbSession();
    session.sendFromServer(version(3));
    session.sendFromClient(version(3));
    session.sendFromServer(unsigned32(NONE));
    session.sendFromClient(new byte[] { SHARE_DESKTOP });
    this.assertRefused(session, serverInitialization(SIDE, SIDE, UNSUPPORTED_BITS_PER_PIXEL, DESKTOP_NAME), "24 bits per pixel");
  }

  @Test
  void shouldRefuseADesktopNameAboveItsBoundBeforeItsFirstByte() throws IOException {
    final RfbSession session = new RfbSession();
    session.sendFromServer(version(3));
    session.sendFromClient(version(3));
    session.sendFromServer(unsigned32(NONE));
    session.sendFromClient(new byte[] { SHARE_DESKTOP });
    final byte[] header = concatenate(
      unsigned16(SIDE),
      unsigned16(SIDE),
      RfbSession.pixelFormat(32),
      unsigned32(RfbGuard.MAXIMUM_NAME + 1L)
    );
    this.assertRefused(session, header, "desktop name");
  }

  @Test
  void shouldAcceptClipboardTextWithinItsBound() throws IOException {
    final RfbSession session = this.connected();
    session.sendFromServer(cutText(RfbGuard.MAXIMUM_TEXT));
    session.sendFromServer(cutText(0));
    this.assertAtAMessage(session);
  }

  @Test
  void shouldRefuseClipboardTextAboveItsBound() throws IOException {
    final RfbSession session = this.connected();
    this.assertRefused(session, concatenate(new byte[] { SERVER_CUT_TEXT, 0, 0, 0 }, unsigned32(0xFFFFFFFFL)), "clipboard text");
  }

  @Test
  void shouldFrameColorMapEntries() throws IOException {
    final RfbSession session = this.connected();
    session.sendFromServer(concatenate(new byte[] { SET_COLOR_MAP_ENTRIES, 0 }, unsigned16(0), unsigned16(2), new byte[12]));
    this.assertAtAMessage(session);
  }

  @Test
  void shouldRefuseAnUnknownMessageType() throws IOException {
    final RfbSession session = this.connected();
    this.assertRefused(session, new byte[] { (byte) 255 }, "message type 255");
  }

  @Test
  void shouldFrameRawRectanglesInTheClientsPixelFormat() throws IOException {
    final RfbSession session = this.connected();
    session.sendFromServer(
      concatenate(update(2), rectangle(0, 0, 3, 2, RAW_ENCODING), this.pixels(3 * 2 * 4), rectangle(1, 1, 0, 0, RAW_ENCODING))
    );
    this.assertAtAMessage(session);
  }

  @Test
  void shouldFrameRawRectanglesInTheServersPixelFormatUntilTheClientChoseOne() throws IOException {
    final RfbSession session = new RfbSession();
    session.sendFromServer(version(3));
    session.sendFromClient(version(3));
    session.sendFromServer(unsigned32(NONE));
    session.sendFromClient(new byte[] { SHARE_DESKTOP });
    session.sendFromServer(serverInitialization(SIDE, SIDE, BITS_PER_PIXEL_16, DESKTOP_NAME));
    session.sendFromServer(concatenate(update(1), rectangle(0, 0, 2, 2, RAW_ENCODING), this.pixels(2 * 2 * 2)));
    this.assertAtAMessage(session);
  }

  @Test
  void shouldIgnoreAPixelFormatOfAnUnsupportedWidthFromTheClient() throws IOException {
    final RfbSession session = new RfbSession();
    session.sendFromServer(version(3));
    session.sendFromClient(version(3));
    session.sendFromServer(unsigned32(NONE));
    session.sendFromClient(new byte[] { SHARE_DESKTOP });
    session.sendFromServer(serverInitialization(SIDE, SIDE, BITS_PER_PIXEL_8, DESKTOP_NAME));
    session.sendFromClient(setPixelFormat(UNSUPPORTED_BITS_PER_PIXEL));
    session.sendFromServer(concatenate(update(1), rectangle(0, 0, 2, 2, RAW_ENCODING), this.pixels(2 * 2)));
    this.assertAtAMessage(session);
  }

  @Test
  void shouldFrameCopyRectangles() throws IOException {
    final RfbSession session = this.connected();
    session.sendFromServer(concatenate(update(1), rectangle(0, 0, 8, 8, COPY_RECTANGLE_ENCODING), unsigned16(8), unsigned16(8)));
    this.assertAtAMessage(session);
  }

  @Test
  void shouldFrameRreRectangles() throws IOException {
    final RfbSession session = this.connected();
    session.sendFromServer(
      concatenate(update(1), rectangle(0, 0, 4, 4, RRE_ENCODING), unsigned32(2), this.pixels(4), this.pixels(2 * (4 + 8)))
    );
    this.assertAtAMessage(session);
  }

  @Test
  void shouldRefuseMoreRreSubrectanglesThanPixels() throws IOException {
    final RfbSession session = this.connected();
    this.assertRefused(
      session,
      concatenate(update(1), rectangle(0, 0, 2, 2, RRE_ENCODING), unsigned32(5), new byte[4]),
      "RRE subrectangles"
    );
  }

  @Test
  void shouldFrameEveryKindOfHextileTile() throws IOException {
    final RfbSession session = this.connected();
    final byte[] raw = concatenate(new byte[] { HEXTILE_RAW }, this.pixels(TILE_SIDE * TILE_SIDE * PIXEL_BYTES));
    final byte[] colors = new byte[] { HEXTILE_BACKGROUND | HEXTILE_FOREGROUND, 0, 0, 0, 0, 0, 0, 0, 0 };
    final byte[] colored = concatenate(new byte[] { HEXTILE_SUBRECTANGLES | HEXTILE_COLORED, 1 }, this.pixels(4 + 2));
    final byte[] plain = concatenate(new byte[] { HEXTILE_BACKGROUND | HEXTILE_SUBRECTANGLES, 0, 0, 0, 0, 1 }, this.pixels(2));
    session.sendFromServer(concatenate(update(1), rectangle(0, 0, 20, 17, HEXTILE_ENCODING), raw, colors, colored, plain));
    session.sendFromServer(concatenate(update(1), rectangle(0, 0, TILE_SIDE, TILE_SIDE, HEXTILE_ENCODING), new byte[] { 0 }));
    this.assertAtAMessage(session);
  }

  @Test
  void shouldFrameZlibRectangles() throws IOException {
    final RfbSession session = this.connected();
    session.sendFromServer(concatenate(update(1), rectangle(0, 0, 4, 4, ZLIB_ENCODING), unsigned32(10), this.pixels(10)));
    session.sendFromServer(concatenate(update(1), rectangle(0, 0, 4, 4, ZLIB_ENCODING), unsigned32(0)));
    this.assertAtAMessage(session);
  }

  @Test
  void shouldRefuseZlibDataFarLargerThanItsPixels() throws IOException {
    final RfbSession session = this.connected();
    final long limit = 4L * 4 * 4 + RfbGuard.ZLIB_SLACK;
    this.assertRefused(session, concatenate(update(1), rectangle(0, 0, 4, 4, ZLIB_ENCODING), unsigned32(limit + 1)), "zlib data");
  }

  @Test
  void shouldFollowANewDesktopSizeWithinItsBounds() throws IOException {
    final RfbSession session = this.connected();
    session.sendFromServer(concatenate(update(1), rectangle(0, 0, SIDE * 2, SIDE, DESKTOP_SIZE_ENCODING)));
    session.sendFromServer(concatenate(update(1), rectangle(SIDE, 0, SIDE, 1, COPY_RECTANGLE_ENCODING), unsigned32(0)));
    this.assertRefused(
      session,
      concatenate(update(1), rectangle(0, 0, RfbGuard.MAXIMUM_SIDE + 1, SIDE, DESKTOP_SIZE_ENCODING)),
      FRAMEBUFFER_REASON
    );
  }

  @Test
  void shouldFrameCursors() throws IOException {
    final RfbSession session = this.connected();
    session.sendFromServer(concatenate(update(1), rectangle(0, 0, 9, 2, CURSOR_ENCODING), this.pixels(9 * 2 * 4 + 2 * 2)));
    session.sendFromServer(concatenate(update(1), rectangle(0, 0, 0, 0, CURSOR_ENCODING)));
    this.assertAtAMessage(session);
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, 1 })
  void shouldRefuseACursorAboveItsBound(final int tallInsteadOfWide) throws IOException {
    final RfbSession session = this.connected();
    final int wide = tallInsteadOfWide == 1 ? 1 : RfbGuard.MAXIMUM_SIDE + 1;
    final int tall = tallInsteadOfWide == 1 ? RfbGuard.MAXIMUM_SIDE + 1 : 1;
    this.assertRefused(session, concatenate(update(1), rectangle(0, 0, wide, tall, CURSOR_ENCODING)), "cursor");
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, 1 })
  void shouldRefuseARectangleOutsideTheFramebuffer(final int belowInsteadOfRight) throws IOException {
    final RfbSession session = this.connected();
    final int left = belowInsteadOfRight == 1 ? 0 : SIDE - 1;
    final int top = belowInsteadOfRight == 1 ? SIDE - 1 : 0;
    this.assertRefused(session, concatenate(update(1), rectangle(left, top, 2, 2, RAW_ENCODING)), "outside the framebuffer");
  }

  @Test
  void shouldRefuseAnEncodingTheClientCannotDecode() throws IOException {
    final RfbSession session = this.connected();
    this.assertRefused(session, concatenate(update(1), rectangle(0, 0, 2, 2, 16)), "encoding 16");
  }

  @Test
  void shouldCheckBytesThatArriveOneAtATime() throws IOException {
    final RfbSession session = this.connected();
    final byte[] update = concatenate(update(1), rectangle(0, 0, 2, 2, RAW_ENCODING), this.pixels(2 * 2 * 4), cutText(3));
    for (final byte value : update) {
      session.sendFromServer(new byte[] { value });
    }
    this.assertRefused(session, new byte[] { 9 }, UNKNOWN_MESSAGE_REASON);
  }

  @Test
  void shouldFollowEveryMessageTheClientSends() throws IOException {
    final RfbSession session = this.connected();
    session.sendFromClient(concatenate(new byte[] { 2, 0 }, unsigned16(2), unsigned32(0)));
    session.sendFromClient(unsigned32(1));
    session.sendFromClient(concatenate(new byte[] { 2, 0 }, unsigned16(0)));
    session.sendFromClient(concatenate(new byte[] { 3, 0 }, unsigned16(0), unsigned16(0), unsigned16(SIDE), unsigned16(SIDE)));
    session.sendFromClient(concatenate(new byte[] { 4, 1, 0, 0 }, unsigned32(65)));
    session.sendFromClient(concatenate(new byte[] { 5, 0 }, unsigned16(1), unsigned16(1)));
    session.sendFromClient(concatenate(new byte[] { 6, 0, 0, 0 }, unsigned32(2), new byte[] { 'h', 'i' }));
    session.sendFromClient(concatenate(new byte[] { 6, 0, 0, 0 }, unsigned32(0)));
    session.sendFromClient(setPixelFormat(BITS_PER_PIXEL_16));
    session.sendFromServer(concatenate(update(1), rectangle(0, 0, 2, 2, RAW_ENCODING), this.pixels(2 * 2 * 2)));
    session.sendFromClient(setPixelFormat(BITS_PER_PIXEL_8));
    session.sendFromServer(concatenate(update(1), rectangle(0, 0, 2, 2, RAW_ENCODING), this.pixels(2 * 2)));
    this.assertAtAMessage(session);
  }

  @Test
  void shouldStopFollowingAClientThatSendsAnUnknownMessage() throws IOException {
    final RfbSession session = this.connected();
    session.sendFromClient(new byte[] { 99, 1, 2, 3 });
    session.sendFromClient(setPixelFormat(BITS_PER_PIXEL_8));
    session.sendFromServer(concatenate(update(1), rectangle(0, 0, 1, 1, RAW_ENCODING), this.pixels(4)));
    this.assertAtAMessage(session);
  }

  @ParameterizedTest
  @ValueSource(strings = { "RFB 003.0x8\n", "RFB 003.0 8\n" })
  void shouldStopFollowingAClientVersionThatIsNotANumber(final String clientVersion) throws IOException {
    final RfbSession session = new RfbSession();
    session.sendFromServer(version(MINOR_VERSION_3_8));
    session.sendFromClient(clientVersion.getBytes(StandardCharsets.US_ASCII));
    this.assertRefused(session, new byte[] { 1 }, BEFORE_VERSION_REASON);
  }

  @ParameterizedTest
  @CsvSource({ "0, 1", "3, 1", "3, 2", "7, 1", "7, 2", "8, 1", "8, 2", "8, 113", "9, 1", "10, 1" })
  void shouldFollowTheClientThroughEveryHandshakeToItsPixelFormat(final int minor, final int security) throws IOException {
    final RfbSession session = new RfbSession();
    session.handshake(minor, security, SIDE, SIDE, BITS_PER_PIXEL_8);
    session.sendFromServer(concatenate(update(1), rectangle(0, 0, 3, 2, RAW_ENCODING), this.pixels(3 * 2)));
    this.assertAtAMessage(session);
  }

  @Test
  void shouldFrameAListOfMoreSecurityTypesThanAFieldHolds() throws IOException {
    final RfbSession session = new RfbSession();
    session.sendFromServer(version(MINOR_VERSION_3_8));
    session.sendFromClient(version(MINOR_VERSION_3_8));
    final byte[] types = new byte[255];
    Arrays.fill(types, (byte) NONE);
    session.sendFromServer(concatenate(new byte[] { (byte) types.length }, types));
    session.sendFromClient(new byte[] { NONE });
    session.sendFromServer(unsigned32(0));
    session.sendFromClient(new byte[] { SHARE_DESKTOP });
    session.sendFromServer(serverInitialization(SIDE, SIDE, BITS_PER_PIXEL_32, DESKTOP_NAME));
    this.assertAtAMessage(session);
  }

  @Test
  void shouldFollowTheClientPastEveryEncodingItLists() throws IOException {
    final RfbSession session = this.connected();
    session.sendFromClient(concatenate(new byte[] { 2, 0 }, unsigned16(3), this.pixels(3 * 4)));
    session.sendFromClient(setPixelFormat(BITS_PER_PIXEL_8));
    session.sendFromServer(concatenate(update(1), rectangle(0, 0, 2, 2, RAW_ENCODING), this.pixels(2 * 2)));
    this.assertAtAMessage(session);
  }

  @Test
  void shouldFollowServerBytesThatStartPartwayIntoABuffer() throws IOException {
    final RfbGuard guard = new RfbGuard();
    final byte[] version = version(MINOR_VERSION_3_8);
    guard.acceptServer(version, 0, version.length);
    guard.acceptClient(version, 0, version.length);
    guard.acceptServer(new byte[] { 9, 9, 1, NONE }, 2, 2);
    guard.acceptClient(new byte[] { NONE }, 0, 1);
    guard.acceptServer(concatenate(new byte[] { 9 }, unsigned32(0)), 1, 4);
    guard.acceptClient(new byte[] { SHARE_DESKTOP }, 0, 1);
    final byte[] initialization = serverInitialization(SIDE, SIDE, BITS_PER_PIXEL_32, DESKTOP_NAME);
    guard.acceptServer(initialization, 0, initialization.length);
    guard.acceptServer(new byte[] { BELL }, 0, 1);
    final IOException refused = assertThrows(IOException.class, () -> guard.acceptServer(new byte[] { 9 }, 0, 1));
    final String message = refused.getMessage();
    final boolean named = message.contains(UNKNOWN_MESSAGE_REASON);
    assertTrue(named, message);
  }

  @Test
  void shouldAcceptAFailureReasonOfItsBound() throws IOException {
    final RfbSession session = new RfbSession();
    session.sendFromServer(version(MINOR_VERSION_3_8));
    session.sendFromClient(version(MINOR_VERSION_3_8));
    session.sendFromServer(concatenate(new byte[] { 0 }, unsigned32(RfbGuard.MAXIMUM_REASON), this.pixels(RfbGuard.MAXIMUM_REASON)));
    this.assertRefused(session, new byte[] { 2 }, ENDED_REASON);
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, 1 })
  void shouldAcceptAFramebufferOfOnePixelAcrossAndTheLargestSide(final int tallInsteadOfWide) throws IOException {
    final int width = tallInsteadOfWide == 1 ? 1 : RfbGuard.MAXIMUM_SIDE;
    final int height = tallInsteadOfWide == 1 ? RfbGuard.MAXIMUM_SIDE : 1;
    final RfbSession session = new RfbSession();
    session.handshake(MINOR_VERSION_3_8, NONE, width, height);
    session.sendFromServer(concatenate(update(1), rectangle(width - 1, height - 1, 1, 1, RAW_ENCODING), this.pixels(4)));
    this.assertAtAMessage(session);
  }

  @ParameterizedTest
  @ValueSource(ints = { 0, 1 })
  void shouldAcceptACursorOfTheLargestSide(final int tallInsteadOfWide) throws IOException {
    final RfbSession session = this.connected();
    final int wide = tallInsteadOfWide == 1 ? 1 : RfbGuard.MAXIMUM_SIDE;
    final int tall = tallInsteadOfWide == 1 ? RfbGuard.MAXIMUM_SIDE : 1;
    session.sendFromServer(
      concatenate(update(1), rectangle(0, 0, wide, tall, CURSOR_ENCODING), this.pixels(wide * tall * 4 + ((wide + 7) / 8) * tall))
    );
    this.assertAtAMessage(session);
  }

  @Test
  void shouldAcceptZlibDataUpToItsBound() throws IOException {
    final RfbSession session = this.connected();
    final int limit = TILE_SIDE * TILE_SIDE * PIXEL_BYTES + RfbGuard.ZLIB_SLACK;
    session.sendFromServer(
      concatenate(update(1), rectangle(0, 0, TILE_SIDE, TILE_SIDE, ZLIB_ENCODING), unsigned32(limit), this.pixels(limit))
    );
    this.assertAtAMessage(session);
  }

  @Test
  void shouldCheckEveryByteReadOneAtATimeThroughItsStream() throws IOException {
    final RfbSession session = this.connected();
    final byte[] bytes = concatenate(update(1), rectangle(0, 0, 1, 1, RAW_ENCODING), this.pixels(4), new byte[] { 9 });
    final InputStream input = session.getGuard().serverInput(new ByteArrayInputStream(bytes));
    for (int offset = 0; offset < bytes.length - 1; offset++) {
      final int expected = bytes[offset] & 0xFF;
      final int read = input.read();
      assertEquals(expected, read);
    }

    final IOException refused = assertThrows(IOException.class, input::read);
    final String message = refused.getMessage();
    final boolean named = message.contains(UNKNOWN_MESSAGE_REASON);
    assertTrue(named, message);
  }

  @Test
  void shouldPassTheBytesThroughItsStreamsUnchanged() throws IOException {
    final RfbGuard guard = new RfbGuard();
    final byte[] version = version(MINOR_VERSION_3_8);
    final ByteArrayOutputStream sent = new ByteArrayOutputStream();
    final OutputStream output = guard.clientOutput(sent);
    final InputStream input = guard.serverInput(new ByteArrayInputStream(concatenate(version, new byte[] { 1, NONE })));
    final byte[] read = input.readNBytes(version.length);
    output.write(version);
    output.write(NONE);
    assertArrayEquals(version, read);

    final int count = input.read();
    final int type = input.read();
    final int end = input.read();
    final int endOfBuffer = input.read(new byte[4], 0, 4);
    assertEquals(1, count);
    assertEquals(NONE, type);
    assertEquals(-1, end);
    assertEquals(-1, endOfBuffer);

    final byte[] expected = concatenate(version, new byte[] { NONE });
    final byte[] written = sent.toByteArray();
    final boolean markSupported = input.markSupported();
    assertArrayEquals(expected, written);
    assertFalse(markSupported);
  }

  @Test
  void shouldSkipByReadingThroughTheGuard() throws IOException {
    final RfbGuard guard = new RfbGuard();
    final InputStream input = guard.serverInput(new ByteArrayInputStream(concatenate(version(MINOR_VERSION_3_8), new byte[] { 1 })));
    final long skipped = input.skip(12);
    assertEquals(12, skipped);

    final IOException refused = assertThrows(IOException.class, () -> input.skip(1));
    final String message = refused.getMessage();
    final boolean named = message.contains(BEFORE_VERSION_REASON);
    assertTrue(named, message);

    final long skippedNothing = input.skip(-1);
    assertEquals(0, skippedNothing);
  }
}
