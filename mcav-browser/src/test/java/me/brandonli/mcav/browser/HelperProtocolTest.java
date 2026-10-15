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
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
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
  void shouldCarryTheTokenAndTheVersionInAHello() throws IOException {
    final byte[] token = token();
    final HelperMessage message = read(bytes(out -> HelperProtocol.writeHello(out, token)));
    final int type = message.getType();
    assertEquals(HelperProtocol.HELLO, type);
    final byte[] readToken = message.getToken();
    assertArrayEquals(token, readToken);
    final int version = message.getNumber();
    assertEquals(HelperProtocol.VERSION, version);
  }

  @Test
  void shouldRequireATokenOfTheRightLengthInAHello() {
    final byte[] shortToken = new byte[HelperProtocol.TOKEN_BYTES - 1];
    assertThrows(IllegalArgumentException.class, () -> bytes(out -> HelperProtocol.writeHello(out, shortToken)));
  }

  @Test
  void shouldCarryTheStringOfTextMessages() throws IOException {
    for (final int type : new int[] { HelperProtocol.READY, HelperProtocol.NOTICE, HelperProtocol.FAILURE }) {
      final HelperMessage message = read(bytes(out -> HelperProtocol.writeText(out, type, "héllo ☃")));
      final int readType = message.getType();
      assertEquals(type, readType);
      final String text = message.getText();
      assertEquals("héllo ☃", text);
      final String url = message.getUrl();
      assertEquals("", url);
      final int number = message.getNumber();
      assertEquals(0, number);
    }
  }

  @Test
  void shouldEncodeTheLoadingStateAsOneOrZero() throws IOException {
    final int loadingNumber = read(bytes(out -> HelperProtocol.writeLoading(out, true))).getNumber();
    assertEquals(1, loadingNumber);
    final int idleNumber = read(bytes(out -> HelperProtocol.writeLoading(out, false))).getNumber();
    assertEquals(0, idleNumber);
    final ProtocolException failure = assertThrows(ProtocolException.class, () -> read(message(HelperProtocol.LOADING, new byte[] { 2 })));
    final boolean namesTheAllowedValues = failure.getMessage().contains("0 or 1");
    assertTrue(namesTheAllowedValues);
  }

  @Test
  void shouldCarryTheCodeTheTextAndTheAddressInALoadError() throws IOException {
    final HelperMessage message = read(
      bytes(out -> HelperProtocol.writeLoadError(out, -105, "ERR_NAME_NOT_RESOLVED", "http://x.invalid/"))
    );
    final int type = message.getType();
    assertEquals(HelperProtocol.LOAD_ERROR, type);
    final int errorCode = message.getNumber();
    assertEquals(-105, errorCode);
    final String text = message.getText();
    assertEquals("ERR_NAME_NOT_RESOLVED", text);
    final String url = message.getUrl();
    assertEquals("http://x.invalid/", url);
  }

  @Test
  void shouldRequireALoadErrorToBeExactlyAsLongAsItsStrings() throws IOException {
    final byte[] valid = bytes(out -> HelperProtocol.writeLoadError(out, 1, "a", "b"));
    final byte[] padded = Arrays.copyOf(valid, valid.length + 1);
    padded[4] = (byte) (padded[4] + 1);
    assertThrows(ProtocolException.class, () -> read(padded));
  }

  @Test
  void shouldCarryTheRegionAndPixelsOfAFrame() throws IOException {
    final byte[] pixels = new byte[2 * 3 * HelperProtocol.PIXEL_BYTES];
    for (int index = 0; index < pixels.length; index++) {
      pixels[index] = (byte) index;
    }
    final FrameRegion region = new FrameRegion(640, 480, 10, 20, 2, 3, pixels);
    final HelperMessage message = read(bytes(out -> HelperProtocol.writeFrame(out, region)));
    final int type = message.getType();
    assertEquals(HelperProtocol.FRAME, type);
    final FrameRegion read = message.getRegion();
    final int pageWidth = read.getPageWidth();
    assertEquals(640, pageWidth);
    final int pageHeight = read.getPageHeight();
    assertEquals(480, pageHeight);
    final int regionX = read.getX();
    assertEquals(10, regionX);
    final int regionY = read.getY();
    assertEquals(20, regionY);
    final int width = read.getWidth();
    assertEquals(2, width);
    final int height = read.getHeight();
    assertEquals(3, height);
    final int pixelBytes = read.getPixelBytes();
    assertEquals(pixels.length, pixelBytes);
    final byte[] readPixels = Arrays.copyOf(read.getPixels(), pixels.length);
    assertArrayEquals(pixels, readPixels);
  }

  @Test
  void shouldReadAFrameIntoTheBufferTheCallerSupplies() throws IOException {
    final byte[] pixels = new byte[HelperProtocol.PIXEL_BYTES];
    final FrameRegion region = new FrameRegion(1, 1, 0, 0, 1, 1, pixels);
    final byte[] reused = new byte[64];
    final byte[] data = bytes(out -> HelperProtocol.writeFrame(out, region));
    final HelperMessage message = HelperProtocol.read(new DataInputStream(new ByteArrayInputStream(data)), size -> reused);
    final byte[] regionPixels = message.getRegion().getPixels();
    assertSame(reused, regionPixels);
  }

  @Test
  void shouldEndTheReadingOnAFrameWhereNoFramesAreExpected() throws IOException {
    final FrameRegion region = new FrameRegion(2, 2, 0, 0, 2, 2, new byte[16]);
    final byte[] data = bytes(out -> HelperProtocol.writeFrame(out, region));
    final ProtocolException failure = assertThrows(ProtocolException.class, () ->
      HelperProtocol.read(new DataInputStream(new ByteArrayInputStream(data)), size -> new byte[0])
    );
    final String message = failure.getMessage();
    assertEquals("A frame of 16 bytes arrived where no frame of that size is expected!", message);
  }

  private static byte[] frame(
    final int pageWidth,
    final int pageHeight,
    final int left,
    final int top,
    final int width,
    final int height,
    final int pixelBytes
  ) throws IOException {
    return bytes(out -> {
      out.writeByte(HelperProtocol.FRAME);
      out.writeInt(12 + pixelBytes);
      out.writeShort(pageWidth);
      out.writeShort(pageHeight);
      out.writeShort(left);
      out.writeShort(top);
      out.writeShort(width);
      out.writeShort(height);
      out.write(new byte[pixelBytes]);
    });
  }

  @Test
  void shouldRequireAFrameToStayInsideItsPage() throws IOException {
    assertThrows(ProtocolException.class, () -> read(frame(10, 10, 9, 0, 2, 1, 8)));
    assertThrows(ProtocolException.class, () -> read(frame(10, 10, 0, 9, 1, 2, 8)));
    assertThrows(ProtocolException.class, () -> read(frame(10, 10, 65_535, 0, 1, 1, 4)));
    final int type = read(frame(10, 10, 9, 9, 1, 1, 4)).getType();
    assertEquals(HelperProtocol.FRAME, type);
  }

  @Test
  void shouldKeepEverySideOfAFrameBetweenOneAndTheLimit() throws IOException {
    assertThrows(ProtocolException.class, () -> read(frame(0, 10, 0, 0, 1, 1, 4)));
    assertThrows(ProtocolException.class, () -> read(frame(10, 0, 0, 0, 1, 1, 4)));
    assertThrows(ProtocolException.class, () -> read(frame(10, 10, 0, 0, 0, 1, 0)));
    assertThrows(ProtocolException.class, () -> read(frame(10, 10, 0, 0, 1, 0, 0)));
    assertThrows(ProtocolException.class, () -> read(frame(HelperProtocol.MAXIMUM_SIDE + 1, 10, 0, 0, 1, 1, 4)));
    assertThrows(ProtocolException.class, () -> read(frame(10, HelperProtocol.MAXIMUM_SIDE + 1, 0, 0, 1, 1, 4)));
    final int type = read(frame(HelperProtocol.MAXIMUM_SIDE, HelperProtocol.MAXIMUM_SIDE, 0, 0, 1, 1, 4)).getType();
    assertEquals(HelperProtocol.FRAME, type);
  }

  @Test
  void shouldRequireAFrameToHoldExactlyItsPixels() throws IOException {
    assertThrows(ProtocolException.class, () -> read(frame(10, 10, 0, 0, 2, 2, 15)));
    assertThrows(ProtocolException.class, () -> read(frame(10, 10, 0, 0, 2, 2, 17)));
  }

  @Test
  void shouldRefuseAFrameLengthOutsideTheLimitsBeforeAnythingIsAllocated() throws IOException {
    final byte[] negative = bytes(out -> {
      out.writeByte(HelperProtocol.FRAME);
      out.writeInt(-1);
    });
    final ProtocolException failure = assertThrows(ProtocolException.class, () -> read(negative));
    final boolean namesTheLength = failure.getMessage().contains("4294967295");
    assertTrue(namesTheLength);
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
  void shouldKeepEveryValueOfMouseInput() throws IOException {
    final MouseInput input = new MouseInput(HelperProtocol.MOUSE_WHEEL, 4095, 17, HelperProtocol.BUTTON_RIGHT, 3, -120, 32_767);
    final HelperMessage message = read(bytes(out -> HelperProtocol.writeMouse(out, input)));
    final MouseInput read = message.getMouse();
    final int action = read.getAction();
    assertEquals(HelperProtocol.MOUSE_WHEEL, action);
    final int mouseX = read.getX();
    assertEquals(4095, mouseX);
    final int mouseY = read.getY();
    assertEquals(17, mouseY);
    final int button = read.getButton();
    assertEquals(HelperProtocol.BUTTON_RIGHT, button);
    final int clickCount = read.getClickCount();
    assertEquals(3, clickCount);
    final int deltaX = read.getDeltaX();
    assertEquals(-120, deltaX);
    final int deltaY = read.getDeltaY();
    assertEquals(32_767, deltaY);
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
  void shouldRefuseMouseInputWithUnknownValues() throws IOException {
    assertThrows(ProtocolException.class, () -> read(mouse(HelperProtocol.MOUSE_WHEEL + 1, 0, 1)));
    assertThrows(ProtocolException.class, () -> read(mouse(0, HelperProtocol.BUTTON_RIGHT + 1, 1)));
    assertThrows(ProtocolException.class, () -> read(mouse(0, 0, 4)));
    assertThrows(ProtocolException.class, () -> read(message(HelperProtocol.MOUSE, new byte[10])));
  }

  @Test
  void shouldKeepTheActionAndTheValueOfKeyInput() throws IOException {
    final HelperMessage press = read(bytes(out -> HelperProtocol.writeKey(out, HelperProtocol.KEY_PRESS, "Enter")));
    final int pressType = press.getType();
    assertEquals(HelperProtocol.KEY, pressType);
    final int pressAction = press.getNumber();
    assertEquals(HelperProtocol.KEY_PRESS, pressAction);
    final String pressedKey = press.getText();
    assertEquals("Enter", pressedKey);
    final HelperMessage type = read(bytes(out -> HelperProtocol.writeKey(out, HelperProtocol.KEY_TYPE, "hi")));
    final int typeAction = type.getNumber();
    assertEquals(HelperProtocol.KEY_TYPE, typeAction);
  }

  @Test
  void shouldRefuseKeyInputWithAnUnknownActionOrAWrongLength() throws IOException {
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
  void shouldRefuseAMessageOfTheWrongLengthBeforeItsContentIsRead() {
    final int max = HelperProtocol.MAXIMUM_TEXT_BYTES;
    final ProtocolException loading = assertThrows(ProtocolException.class, () -> read(message(HelperProtocol.LOADING, new byte[2])));
    final String loadingMessage = loading.getMessage();
    assertEquals("A message of type 4 has 1 bytes, but 2 bytes were announced!", loadingMessage);
    final ProtocolException loadError = assertThrows(ProtocolException.class, () -> read(message(HelperProtocol.LOAD_ERROR, new byte[3])));
    final String loadErrorMessage = loadError.getMessage();
    assertEquals("A message of type 5 has 8 to " + (4 + 2 * (2 + max)) + " bytes, but 3 bytes were announced!", loadErrorMessage);
    final ProtocolException text = assertThrows(ProtocolException.class, () -> read(message(HelperProtocol.READY, new byte[1])));
    final String textMessage = text.getMessage();
    assertEquals(
      "A message of type " + HelperProtocol.READY + " has 2 to " + (2 + max) + " bytes, but 1 bytes were announced!",
      textMessage
    );
  }

  @Test
  void shouldTellHowMuchAKeyMessageHolds() throws IOException {
    final byte[] padded = bytes(out -> {
      out.writeByte(HelperProtocol.KEY);
      out.writeInt(5);
      out.writeByte(HelperProtocol.KEY_TYPE);
      out.writeShort(1);
      out.writeByte('a');
      out.writeByte('b');
    });
    final ProtocolException failure = assertThrows(ProtocolException.class, () -> read(padded));
    final String message = failure.getMessage();
    assertEquals("A key message of 5 bytes holds 4 bytes!", message);
  }

  @Test
  void shouldFitTheShortestAndTheLongestTexts() throws IOException {
    final String longest = "a".repeat(HelperProtocol.MAXIMUM_TEXT_BYTES);
    final String longestText = read(bytes(out -> HelperProtocol.writeText(out, HelperProtocol.NOTICE, longest))).getText();
    assertEquals(longest, longestText);
    final String emptyText = read(bytes(out -> HelperProtocol.writeText(out, HelperProtocol.NOTICE, ""))).getText();
    assertEquals("", emptyText);
  }

  @Test
  void shouldCarrySoundAsWholeFramesUpToItsLimit() throws IOException {
    final byte[] samples = { 1, 2, 3, 4, 5, 6, 7, 8, 99 };
    final HelperMessage sound = read(bytes(out -> HelperProtocol.writeAudio(out, samples, 8)));
    final int type = sound.getType();
    assertEquals(HelperProtocol.AUDIO, type);
    final byte[] soundSamples = sound.getSamples();
    assertArrayEquals(Arrays.copyOf(samples, 8), soundSamples);
    final byte[] largest = new byte[HelperProtocol.MAXIMUM_AUDIO_BYTES];
    largest[largest.length - 1] = 5;
    final byte[] largestSamples = read(bytes(out -> HelperProtocol.writeAudio(out, largest, largest.length))).getSamples();
    assertArrayEquals(largest, largestSamples);
    final byte[] closeSamples = read(bytes(HelperProtocol::writeClose)).getSamples();
    assertArrayEquals(new byte[0], closeSamples, "other messages carry no sound");
    assertSoundRefused(new byte[8], 0);
    assertSoundRefused(new byte[8], -4);
    assertSoundRefused(new byte[8], 6);
    assertSoundRefused(new byte[8], 12);
    assertSoundRefused(new byte[HelperProtocol.MAXIMUM_AUDIO_BYTES + 4], HelperProtocol.MAXIMUM_AUDIO_BYTES + 4);
  }

  private static void assertSoundRefused(final byte[] samples, final int length) {
    final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () ->
      bytes(out -> HelperProtocol.writeAudio(out, samples, length))
    );
    final String message = refused.getMessage();
    assertEquals("Sound of " + length + " bytes cannot be sent!", message);
  }

  @Test
  void shouldRefuseSoundThatIsNotWholeFramesOrTooLong() throws IOException {
    final ProtocolException half = assertThrows(ProtocolException.class, () -> read(message(HelperProtocol.AUDIO, new byte[6])));
    final String halfFrameMessage = half.getMessage();
    assertEquals("Sound of 6 bytes does not hold whole frames of 4 bytes!", halfFrameMessage);
    assertThrows(ProtocolException.class, () -> read(message(HelperProtocol.AUDIO, new byte[0])));
    assertThrows(ProtocolException.class, () -> read(message(HelperProtocol.AUDIO, new byte[2])));
    final byte[] tooLong = bytes(out -> {
      out.writeByte(HelperProtocol.AUDIO);
      out.writeInt(HelperProtocol.MAXIMUM_AUDIO_BYTES + 4);
    });
    final ProtocolException refused = assertThrows(ProtocolException.class, () -> read(tooLong));
    final String tooLongMessage = refused.getMessage();
    assertEquals("A message of type 8 has 4 to 65536 bytes, but 65540 bytes were announced!", tooLongMessage);
  }

  @Test
  void shouldCarryNothingInAClose() throws IOException {
    final int type = read(bytes(HelperProtocol::writeClose)).getType();
    assertEquals(HelperProtocol.CLOSE, type);
    assertThrows(ProtocolException.class, () -> read(message(HelperProtocol.CLOSE, new byte[1])));
  }

  @Test
  void shouldRefuseAHelloOfTheWrongLength() throws IOException {
    assertThrows(ProtocolException.class, () -> read(message(HelperProtocol.HELLO, new byte[HelperProtocol.TOKEN_BYTES])));
  }

  @Test
  void shouldRefuseAnUnknownType() throws IOException {
    final ProtocolException failure = assertThrows(ProtocolException.class, () -> read(message(99, new byte[0])));
    final boolean namesTheType = failure.getMessage().contains("99");
    assertTrue(namesTheType);
  }

  @Test
  void shouldRefuseAStringLongerThanTheLimit() throws IOException {
    final byte[] tooLong = bytes(out -> {
      out.writeByte(HelperProtocol.NOTICE);
      out.writeInt(2 + HelperProtocol.MAXIMUM_TEXT_BYTES + 1);
      out.writeShort(HelperProtocol.MAXIMUM_TEXT_BYTES + 1);
      out.write(new byte[HelperProtocol.MAXIMUM_TEXT_BYTES + 1]);
    });
    assertThrows(ProtocolException.class, () -> read(tooLong));
    final byte[] lyingLength = bytes(out -> {
      out.writeByte(HelperProtocol.NOTICE);
      out.writeInt(2 + 10);
      out.writeShort(HelperProtocol.MAXIMUM_TEXT_BYTES + 1);
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
  void shouldRefuseAStringThatIsNotUtf8() throws IOException {
    final byte[] invalid = bytes(out -> {
      out.writeByte(HelperProtocol.NOTICE);
      out.writeInt(2 + 1);
      out.writeShort(1);
      out.writeByte(0xFF);
    });
    final ProtocolException failure = assertThrows(ProtocolException.class, () -> read(invalid));
    final boolean codingFailure = failure.getCause() instanceof CharacterCodingException;
    assertTrue(codingFailure);
  }

  @Test
  void shouldFailWhenTheStreamEndsInsideAMessage() throws IOException {
    final byte[] complete = bytes(out -> HelperProtocol.writeText(out, HelperProtocol.NOTICE, "hello"));
    final byte[] cut = Arrays.copyOf(complete, complete.length - 1);
    assertThrows(EOFException.class, () -> read(cut));
    assertThrows(EOFException.class, () -> read(new byte[0]));
  }

  @Test
  void shouldCutLongTextsAtACharacterBoundary() {
    final String ascii = "a".repeat(HelperProtocol.MAXIMUM_TEXT_BYTES + 10);
    assertEquals(HelperProtocol.MAXIMUM_TEXT_BYTES, HelperProtocol.encode(ascii).length);
    final String straddling = "a".repeat(HelperProtocol.MAXIMUM_TEXT_BYTES - 1) + "☃";
    final byte[] cut = HelperProtocol.encode(straddling);
    assertEquals(HelperProtocol.MAXIMUM_TEXT_BYTES - 1, cut.length);
    final String cutText = new String(cut, StandardCharsets.UTF_8);
    assertEquals("a".repeat(HelperProtocol.MAXIMUM_TEXT_BYTES - 1), cutText);
    final byte[] exact = HelperProtocol.encode("a".repeat(HelperProtocol.MAXIMUM_TEXT_BYTES));
    assertEquals(HelperProtocol.MAXIMUM_TEXT_BYTES, exact.length);
  }

  @Test
  void shouldCutWrittenTextsToo() throws IOException {
    final String longText = "b".repeat(HelperProtocol.MAXIMUM_TEXT_BYTES * 2);
    final HelperMessage message = read(bytes(out -> HelperProtocol.writeText(out, HelperProtocol.NOTICE, longText)));
    final int textLength = message.getText().length();
    assertEquals(HelperProtocol.MAXIMUM_TEXT_BYTES, textLength);
  }

  private static int utf8(final String text) {
    return text.getBytes(StandardCharsets.UTF_8).length;
  }

  @Test
  void shouldSplitALongTextIntoPartsThatEachFitIntoAMessage() {
    final int max = HelperProtocol.MAXIMUM_TEXT_BYTES;
    final List<String> emptyParts = HelperProtocol.split("");
    assertEquals(List.of(""), emptyParts);
    final List<String> shortParts = HelperProtocol.split("hello");
    assertEquals(List.of("hello"), shortParts);
    final List<String> fullParts = HelperProtocol.split("a".repeat(max));
    assertEquals(List.of("a".repeat(max)), fullParts);
    final List<String> overflowingParts = HelperProtocol.split("a".repeat(max + 1));
    assertEquals(List.of("a".repeat(max), "a"), overflowingParts);
    for (final String character : new String[] { "\u0080", "\u00e9", "\u0800", "\u20ac", "\uD800\uDC00", "\uD83D\uDE00" }) {
      final String text = character.repeat(max);
      final List<String> parts = HelperProtocol.split(text);
      final String joined = String.join("", parts);
      assertEquals(text, joined);
      for (int index = 0; index < parts.size(); index++) {
        final String part = parts.get(index);
        final boolean partFits = utf8(part) <= max;
        assertTrue(partFits, character + " " + utf8(part));
        final boolean last = index == parts.size() - 1;
        final boolean partFull = last || utf8(part) > max - utf8(character);
        assertTrue(partFull, "parts are as long as they can be");
      }
    }
  }

  @Test
  void shouldNeverSplitALineBreak() {
    final int max = HelperProtocol.MAXIMUM_TEXT_BYTES;
    final String text = "a".repeat(max - 1) + "\r\nb";
    final List<String> carriageReturnParts = HelperProtocol.split(text);
    assertEquals(List.of("a".repeat(max - 1), "\r\nb"), carriageReturnParts);
    final String lone = "a".repeat(max) + "\nb";
    final List<String> lineFeedParts = HelperProtocol.split(lone);
    assertEquals(List.of("a".repeat(max), "\nb"), lineFeedParts);
  }

  @Test
  void shouldNotInstantiateTheProtocol() {
    UtilityClassAssertions.assertNotInstantiable(HelperProtocol.class);
  }

  @FunctionalInterface
  private interface ThrowingWriter {
    void write(DataOutputStream out) throws IOException;
  }
}
