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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.IntFunction;
import me.brandonli.mcav.browser.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

class HelperProtocolTest {

  private static final IntFunction<byte[]> NEW_BUFFER = byte[]::new;

  private static byte[] bytes(final ThrowingWriter writer) throws IOException {
    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (final DataOutputStream out = new DataOutputStream(buffer)) {
      writer.write(out);
    }
    return buffer.toByteArray();
  }

  private static HelperMessage read(final byte[] data) throws IOException {
    return HelperProtocol.read(new DataInputStream(new ByteArrayInputStream(data)), NEW_BUFFER);
  }

  private static byte[] message(final int type, final byte[] payload) throws IOException {
    return bytes(out -> {
      out.writeByte(type);
      out.writeInt(payload.length);
      out.write(payload);
    });
  }

  private static byte[] token() {
    final byte[] token = new byte[HelperProtocol.TOKEN_BYTES];
    Arrays.fill(token, (byte) 7);
    token[0] = 1;
    return token;
  }

  @Test
  void aHelloCarriesTheTokenAndTheVersion() throws IOException {
    final byte[] token = token();
    final HelperMessage message = read(bytes(out -> HelperProtocol.writeHello(out, token)));
    assertEquals(HelperProtocol.HELLO, message.getType());
    assertArrayEquals(token, message.getToken());
    assertEquals(HelperProtocol.VERSION, message.getNumber());
  }

  @Test
  void aHelloNeedsATokenOfTheRightLength() {
    final byte[] shortToken = new byte[HelperProtocol.TOKEN_BYTES - 1];
    assertThrows(IllegalArgumentException.class, () -> bytes(out -> HelperProtocol.writeHello(out, shortToken)));
  }

  @Test
  void textMessagesCarryTheirString() throws IOException {
    for (final int type : new int[] { HelperProtocol.READY, HelperProtocol.NOTICE, HelperProtocol.FAILURE }) {
      final HelperMessage message = read(bytes(out -> HelperProtocol.writeText(out, type, "héllo ☃")));
      assertEquals(type, message.getType());
      assertEquals("héllo ☃", message.getText());
      assertEquals("", message.getUrl());
      assertEquals(0, message.getNumber());
    }
  }

  @Test
  void theLoadingStateIsOneOrZero() throws IOException {
    assertEquals(1, read(bytes(out -> HelperProtocol.writeLoading(out, true))).getNumber());
    assertEquals(0, read(bytes(out -> HelperProtocol.writeLoading(out, false))).getNumber());
    final ProtocolException failure = assertThrows(ProtocolException.class, () -> read(message(HelperProtocol.LOADING, new byte[] { 2 })));
    assertTrue(failure.getMessage().contains("0 or 1"));
  }

  @Test
  void aLoadErrorCarriesTheCodeTheTextAndTheAddress() throws IOException {
    final HelperMessage message = read(
      bytes(out -> HelperProtocol.writeLoadError(out, -105, "ERR_NAME_NOT_RESOLVED", "http://x.invalid/"))
    );
    assertEquals(HelperProtocol.LOAD_ERROR, message.getType());
    assertEquals(-105, message.getNumber());
    assertEquals("ERR_NAME_NOT_RESOLVED", message.getText());
    assertEquals("http://x.invalid/", message.getUrl());
  }

  @Test
  void aLoadErrorMustBeExactlyAsLongAsItsStrings() throws IOException {
    final byte[] valid = bytes(out -> HelperProtocol.writeLoadError(out, 1, "a", "b"));
    // announce one byte more than the strings hold, and pad it
    final byte[] padded = Arrays.copyOf(valid, valid.length + 1);
    padded[4] = (byte) (padded[4] + 1);
    assertThrows(ProtocolException.class, () -> read(padded));
  }

  @Test
  void aFrameCarriesItsRegionAndPixels() throws IOException {
    final byte[] pixels = new byte[2 * 3 * HelperProtocol.PIXEL_BYTES];
    for (int index = 0; index < pixels.length; index++) {
      pixels[index] = (byte) index;
    }
    final FrameRegion region = new FrameRegion(640, 480, 10, 20, 2, 3, pixels);
    final HelperMessage message = read(bytes(out -> HelperProtocol.writeFrame(out, region)));
    assertEquals(HelperProtocol.FRAME, message.getType());
    final FrameRegion read = message.getRegion();
    assertEquals(640, read.getPageWidth());
    assertEquals(480, read.getPageHeight());
    assertEquals(10, read.getX());
    assertEquals(20, read.getY());
    assertEquals(2, read.getWidth());
    assertEquals(3, read.getHeight());
    assertEquals(pixels.length, read.getPixelBytes());
    assertArrayEquals(pixels, Arrays.copyOf(read.getPixels(), pixels.length));
  }

  @Test
  void aFrameIsReadIntoTheBufferTheCallerSupplies() throws IOException {
    final byte[] pixels = new byte[HelperProtocol.PIXEL_BYTES];
    final FrameRegion region = new FrameRegion(1, 1, 0, 0, 1, 1, pixels);
    final byte[] reused = new byte[64];
    final byte[] data = bytes(out -> HelperProtocol.writeFrame(out, region));
    final HelperMessage message = HelperProtocol.read(new DataInputStream(new ByteArrayInputStream(data)), size -> reused);
    assertSame(reused, message.getRegion().getPixels());
  }

  @Test
  void aFrameWhereNoFramesAreExpectedEndsTheReading() throws IOException {
    final FrameRegion region = new FrameRegion(2, 2, 0, 0, 2, 2, new byte[16]);
    final byte[] data = bytes(out -> HelperProtocol.writeFrame(out, region));
    final ProtocolException failure = assertThrows(ProtocolException.class, () ->
      HelperProtocol.read(new DataInputStream(new ByteArrayInputStream(data)), size -> new byte[0])
    );
    assertEquals("A frame of 16 bytes arrived where no frame of that size is expected", failure.getMessage());
  }

  private static byte[] frame(
    final int pageWidth,
    final int pageHeight,
    final int x,
    final int y,
    final int width,
    final int height,
    final int pixelBytes
  ) throws IOException {
    return bytes(out -> {
      out.writeByte(HelperProtocol.FRAME);
      out.writeInt(12 + pixelBytes);
      out.writeShort(pageWidth);
      out.writeShort(pageHeight);
      out.writeShort(x);
      out.writeShort(y);
      out.writeShort(width);
      out.writeShort(height);
      out.write(new byte[pixelBytes]);
    });
  }

  @Test
  void aFrameMustStayInsideItsPage() throws IOException {
    assertThrows(ProtocolException.class, () -> read(frame(10, 10, 9, 0, 2, 1, 8)));
    assertThrows(ProtocolException.class, () -> read(frame(10, 10, 0, 9, 1, 2, 8)));
    assertThrows(ProtocolException.class, () -> read(frame(10, 10, 65_535, 0, 1, 1, 4)));
    // a region that ends exactly at the edge is inside
    assertEquals(HelperProtocol.FRAME, read(frame(10, 10, 9, 9, 1, 1, 4)).getType());
  }

  @Test
  void everySideOfAFrameIsBetweenOneAndTheLimit() throws IOException {
    assertThrows(ProtocolException.class, () -> read(frame(0, 10, 0, 0, 1, 1, 4)));
    assertThrows(ProtocolException.class, () -> read(frame(10, 0, 0, 0, 1, 1, 4)));
    assertThrows(ProtocolException.class, () -> read(frame(10, 10, 0, 0, 0, 1, 0)));
    assertThrows(ProtocolException.class, () -> read(frame(10, 10, 0, 0, 1, 0, 0)));
    assertThrows(ProtocolException.class, () -> read(frame(HelperProtocol.MAX_SIDE + 1, 10, 0, 0, 1, 1, 4)));
    assertThrows(ProtocolException.class, () -> read(frame(10, HelperProtocol.MAX_SIDE + 1, 0, 0, 1, 1, 4)));
    assertEquals(HelperProtocol.FRAME, read(frame(HelperProtocol.MAX_SIDE, HelperProtocol.MAX_SIDE, 0, 0, 1, 1, 4)).getType());
  }

  @Test
  void aFrameMustHoldExactlyItsPixels() throws IOException {
    assertThrows(ProtocolException.class, () -> read(frame(10, 10, 0, 0, 2, 2, 15)));
    assertThrows(ProtocolException.class, () -> read(frame(10, 10, 0, 0, 2, 2, 17)));
  }

  @Test
  void aFrameLengthOutsideTheLimitsIsRefusedBeforeAnythingIsAllocated() throws IOException {
    final byte[] negative = bytes(out -> {
      out.writeByte(HelperProtocol.FRAME);
      out.writeInt(-1);
    });
    final ProtocolException failure = assertThrows(ProtocolException.class, () -> read(negative));
    assertTrue(failure.getMessage().contains("4294967295"));
    final byte[] tooShort = bytes(out -> {
      out.writeByte(HelperProtocol.FRAME);
      out.writeInt(11);
    });
    assertThrows(ProtocolException.class, () -> read(tooShort));
    final byte[] tooLong = bytes(out -> {
      out.writeByte(HelperProtocol.FRAME);
      out.writeInt(Integer.MAX_VALUE);
    });
    assertThrows(ProtocolException.class, () -> read(tooLong));
  }

  @Test
  void mouseInputKeepsEveryValue() throws IOException {
    final MouseInput input = new MouseInput(HelperProtocol.MOUSE_WHEEL, 4095, 17, HelperProtocol.BUTTON_RIGHT, 3, -120, 32_767);
    final HelperMessage message = read(bytes(out -> HelperProtocol.writeMouse(out, input)));
    final MouseInput read = message.getMouse();
    assertEquals(HelperProtocol.MOUSE_WHEEL, read.getAction());
    assertEquals(4095, read.getX());
    assertEquals(17, read.getY());
    assertEquals(HelperProtocol.BUTTON_RIGHT, read.getButton());
    assertEquals(3, read.getClickCount());
    assertEquals(-120, read.getDeltaX());
    assertEquals(32_767, read.getDeltaY());
  }

  private static byte[] mouse(final int action, final int button, final int clickCount) throws IOException {
    return bytes(out -> {
      out.writeByte(HelperProtocol.MOUSE);
      out.writeInt(11);
      out.writeByte(action);
      out.writeShort(1);
      out.writeShort(1);
      out.writeByte(button);
      out.writeByte(clickCount);
      out.writeShort(0);
      out.writeShort(0);
    });
  }

  @Test
  void mouseInputWithUnknownValuesIsRefused() throws IOException {
    assertThrows(ProtocolException.class, () -> read(mouse(HelperProtocol.MOUSE_WHEEL + 1, 0, 1)));
    assertThrows(ProtocolException.class, () -> read(mouse(0, HelperProtocol.BUTTON_RIGHT + 1, 1)));
    assertThrows(ProtocolException.class, () -> read(mouse(0, 0, 4)));
    assertThrows(ProtocolException.class, () -> read(message(HelperProtocol.MOUSE, new byte[10])));
  }

  @Test
  void keyInputKeepsTheActionAndTheValue() throws IOException {
    final HelperMessage press = read(bytes(out -> HelperProtocol.writeKey(out, HelperProtocol.KEY_PRESS, "Enter")));
    assertEquals(HelperProtocol.KEY, press.getType());
    assertEquals(HelperProtocol.KEY_PRESS, press.getNumber());
    assertEquals("Enter", press.getText());
    final HelperMessage type = read(bytes(out -> HelperProtocol.writeKey(out, HelperProtocol.KEY_TYPE, "hi")));
    assertEquals(HelperProtocol.KEY_TYPE, type.getNumber());
  }

  @Test
  void keyInputWithAnUnknownActionOrAWrongLengthIsRefused() throws IOException {
    final byte[] unknown = bytes(out -> {
      out.writeByte(HelperProtocol.KEY);
      out.writeInt(3);
      out.writeByte(2);
      out.writeShort(0);
    });
    assertThrows(ProtocolException.class, () -> read(unknown));
    final byte[] padded = bytes(out -> {
      out.writeByte(HelperProtocol.KEY);
      out.writeInt(5);
      out.writeByte(HelperProtocol.KEY_TYPE);
      out.writeShort(1);
      out.writeByte('a');
      out.writeByte('b');
    });
    assertThrows(ProtocolException.class, () -> read(padded));
    assertThrows(ProtocolException.class, () -> read(message(HelperProtocol.KEY, new byte[2])));
  }

  @Test
  void aMessageOfTheWrongLengthIsRefusedBeforeItsContentIsRead() {
    final int max = HelperProtocol.MAX_TEXT_BYTES;
    final ProtocolException loading = assertThrows(ProtocolException.class, () -> read(message(HelperProtocol.LOADING, new byte[2])));
    assertEquals("A message of type 4 has 1 bytes but announced 2", loading.getMessage());
    final ProtocolException loadError = assertThrows(ProtocolException.class, () -> read(message(HelperProtocol.LOAD_ERROR, new byte[3])));
    assertEquals("A message of type 5 has 8 to " + (4 + 2 * (2 + max)) + " bytes but announced 3", loadError.getMessage());
    final ProtocolException text = assertThrows(ProtocolException.class, () -> read(message(HelperProtocol.READY, new byte[1])));
    assertEquals("A message of type " + HelperProtocol.READY + " has 2 to " + (2 + max) + " bytes but announced 1", text.getMessage());
  }

  @Test
  void aKeyMessageSaysHowMuchItHolds() throws IOException {
    final byte[] padded = bytes(out -> {
      out.writeByte(HelperProtocol.KEY);
      out.writeInt(5);
      out.writeByte(HelperProtocol.KEY_TYPE);
      out.writeShort(1);
      out.writeByte('a');
      out.writeByte('b');
    });
    final ProtocolException failure = assertThrows(ProtocolException.class, () -> read(padded));
    assertEquals("A key message of 5 bytes holds 4", failure.getMessage());
  }

  @Test
  void theShortestAndTheLongestTextsFit() throws IOException {
    final String longest = "a".repeat(HelperProtocol.MAX_TEXT_BYTES);
    assertEquals(longest, read(bytes(out -> HelperProtocol.writeText(out, HelperProtocol.NOTICE, longest))).getText());
    assertEquals("", read(bytes(out -> HelperProtocol.writeText(out, HelperProtocol.NOTICE, ""))).getText());
  }

  @Test
  void aCloseCarriesNothing() throws IOException {
    assertEquals(HelperProtocol.CLOSE, read(bytes(HelperProtocol::writeClose)).getType());
    assertThrows(ProtocolException.class, () -> read(message(HelperProtocol.CLOSE, new byte[1])));
  }

  @Test
  void aHelloOfTheWrongLengthIsRefused() throws IOException {
    assertThrows(ProtocolException.class, () -> read(message(HelperProtocol.HELLO, new byte[HelperProtocol.TOKEN_BYTES])));
  }

  @Test
  void anUnknownTypeIsRefused() throws IOException {
    final ProtocolException failure = assertThrows(ProtocolException.class, () -> read(message(99, new byte[0])));
    assertTrue(failure.getMessage().contains("99"));
  }

  @Test
  void aStringLongerThanTheLimitIsRefused() throws IOException {
    final byte[] tooLong = bytes(out -> {
      out.writeByte(HelperProtocol.NOTICE);
      out.writeInt(2 + HelperProtocol.MAX_TEXT_BYTES + 1);
      out.writeShort(HelperProtocol.MAX_TEXT_BYTES + 1);
      out.write(new byte[HelperProtocol.MAX_TEXT_BYTES + 1]);
    });
    assertThrows(ProtocolException.class, () -> read(tooLong));
    final byte[] lyingLength = bytes(out -> {
      out.writeByte(HelperProtocol.NOTICE);
      out.writeInt(2 + 10);
      out.writeShort(HelperProtocol.MAX_TEXT_BYTES + 1);
    });
    assertThrows(ProtocolException.class, () -> read(lyingLength));
    final byte[] mismatch = bytes(out -> {
      out.writeByte(HelperProtocol.NOTICE);
      out.writeInt(2 + 3);
      out.writeShort(2);
      out.write(new byte[] { 'a', 'b', 'c' });
    });
    assertThrows(ProtocolException.class, () -> read(mismatch));
  }

  @Test
  void aStringThatIsNotUtf8IsRefused() throws IOException {
    final byte[] invalid = bytes(out -> {
      out.writeByte(HelperProtocol.NOTICE);
      out.writeInt(2 + 1);
      out.writeShort(1);
      out.writeByte(0xFF);
    });
    final ProtocolException failure = assertThrows(ProtocolException.class, () -> read(invalid));
    assertTrue(failure.getCause() instanceof java.nio.charset.CharacterCodingException);
  }

  @Test
  void aStreamThatEndsInsideAMessageFails() throws IOException {
    final byte[] complete = bytes(out -> HelperProtocol.writeText(out, HelperProtocol.NOTICE, "hello"));
    final byte[] cut = Arrays.copyOf(complete, complete.length - 1);
    assertThrows(EOFException.class, () -> read(cut));
    assertThrows(EOFException.class, () -> read(new byte[0]));
  }

  @Test
  void longTextsAreCutAtACharacterBoundary() {
    final String ascii = "a".repeat(HelperProtocol.MAX_TEXT_BYTES + 10);
    assertEquals(HelperProtocol.MAX_TEXT_BYTES, HelperProtocol.encode(ascii).length);
    // a three byte character straddles the limit, so it is left out entirely
    final String straddling = "a".repeat(HelperProtocol.MAX_TEXT_BYTES - 1) + "☃";
    final byte[] cut = HelperProtocol.encode(straddling);
    assertEquals(HelperProtocol.MAX_TEXT_BYTES - 1, cut.length);
    assertEquals("a".repeat(HelperProtocol.MAX_TEXT_BYTES - 1), new String(cut, StandardCharsets.UTF_8));
    final byte[] exact = HelperProtocol.encode("a".repeat(HelperProtocol.MAX_TEXT_BYTES));
    assertEquals(HelperProtocol.MAX_TEXT_BYTES, exact.length);
  }

  @Test
  void writtenTextsAreCutToo() throws IOException {
    final String longText = "b".repeat(HelperProtocol.MAX_TEXT_BYTES * 2);
    final HelperMessage message = read(bytes(out -> HelperProtocol.writeText(out, HelperProtocol.NOTICE, longText)));
    assertEquals(HelperProtocol.MAX_TEXT_BYTES, message.getText().length());
  }

  private static int utf8(final String text) {
    return text.getBytes(StandardCharsets.UTF_8).length;
  }

  @Test
  void aLongTextIsSplitIntoPartsThatEachFitIntoAMessage() {
    final int max = HelperProtocol.MAX_TEXT_BYTES;
    assertEquals(java.util.List.of(""), HelperProtocol.split(""));
    assertEquals(java.util.List.of("hello"), HelperProtocol.split("hello"));
    assertEquals(java.util.List.of("a".repeat(max)), HelperProtocol.split("a".repeat(max)));
    assertEquals(java.util.List.of("a".repeat(max), "a"), HelperProtocol.split("a".repeat(max + 1)));
    // two, three and four bytes per character: no character is cut, no part is too long, nothing is lost
    // the first characters of two, three and four bytes are counted right too
    for (final String character : new String[] { "\u0080", "\u00e9", "\u0800", "\u20ac", "\uD800\uDC00", "\uD83D\uDE00" }) {
      final String text = character.repeat(max);
      final java.util.List<String> parts = HelperProtocol.split(text);
      assertEquals(text, String.join("", parts));
      for (int index = 0; index < parts.size(); index++) {
        final String part = parts.get(index);
        assertTrue(utf8(part) <= max, character + " " + utf8(part));
        final boolean last = index == parts.size() - 1;
        assertTrue(last || utf8(part) > max - utf8(character), "parts are as long as they can be");
      }
    }
  }

  @Test
  void aLineBreakIsNeverSplit() {
    final int max = HelperProtocol.MAX_TEXT_BYTES;
    final String text = "a".repeat(max - 1) + "\r\nb";
    assertEquals(java.util.List.of("a".repeat(max - 1), "\r\nb"), HelperProtocol.split(text));
    final String lone = "a".repeat(max) + "\nb";
    assertEquals(java.util.List.of("a".repeat(max), "\nb"), HelperProtocol.split(lone));
  }

  @Test
  void theProtocolIsNotInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(HelperProtocol.class);
  }

  @FunctionalInterface
  private interface ThrowingWriter {
    void write(DataOutputStream out) throws IOException;
  }
}
