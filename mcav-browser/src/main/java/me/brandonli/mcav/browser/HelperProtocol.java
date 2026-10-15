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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * The messages between the server and the browser helper process, and their binary form.
 *
 * <p>Every message is a type byte, a 32-bit payload length and the payload, all big-endian. The helper runs web
 * content without the Chromium sandbox, so the server treats everything it receives as untrusted: every length is
 * checked against the limit of its message type before anything is allocated, every size and position against the
 * page, and every string is decoded strictly. A message that breaks a rule ends the connection with a
 * {@link ProtocolException}.
 *
 * <p>From the helper: {@link #HELLO} (the session token, first), {@link #READY} (the browser exists),
 * {@link #FRAME} (a changed region of the page, four bytes per pixel in BGRA order), {@link #LOADING},
 * {@link #LOAD_ERROR}, {@link #NOTICE} and {@link #FAILURE}. From the server: {@link #MOUSE}, {@link #KEY} and
 * {@link #CLOSE}.
 */
final class HelperProtocol {

  /**
   * The version of the protocol, sent in {@link #HELLO}.
   */
  static final int VERSION = 1;

  /**
   * The length of the session token in bytes.
   */
  static final int TOKEN_BYTES = 32;

  /**
   * The largest width or height of a page in pixels.
   */
  static final int MAXIMUM_SIDE = 4096;

  /**
   * The largest string in a message, in UTF-8 bytes.
   */
  static final int MAXIMUM_TEXT_BYTES = 4096;

  /**
   * The bytes per pixel of a frame.
   */
  static final int PIXEL_BYTES = 4;

  /**
   * The bytes of one frame of sound: a 16-bit sample for each of the two channels.
   */
  static final int AUDIO_FRAME_BYTES = 4;

  /**
   * The largest message of sound in bytes: 16384 frames, a third of a second at 48 kHz.
   */
  static final int MAXIMUM_AUDIO_BYTES = 65_536;

  /** The token and protocol version of a new helper: {@code byte[32] token, u16 version}. */
  static final int HELLO = 1;
  /** The browser was created: {@code string cefVersion}. */
  static final int READY = 2;
  /** A changed region: {@code u16 pageWidth, u16 pageHeight, u16 x, u16 y, u16 width, u16 height, pixels}. */
  static final int FRAME = 3;
  /** Whether the page is loading: {@code u8 loading}. */
  static final int LOADING = 4;
  /** The page failed to load: {@code i32 code, string text, string url}. */
  static final int LOAD_ERROR = 5;
  /** Something the helper refused or changed, for the log: {@code string text}. */
  static final int NOTICE = 6;
  /** The helper cannot go on and exits: {@code string text}. */
  static final int FAILURE = 7;

  /** Sound of the page: {@code pcm}, 16-bit little-endian stereo samples at 48 kHz, whole frames. */
  static final int AUDIO = 8;
  /** Mouse input: {@code u8 action, u16 x, u16 y, u8 button, u8 clickCount, i16 deltaX, i16 deltaY}. */
  static final int MOUSE = 16;
  /** Keyboard input: {@code u8 action, string value}. */
  static final int KEY = 17;
  /** Close the browser and exit: no payload. */
  static final int CLOSE = 18;

  /** A mouse move. */
  static final int MOUSE_MOVE = 0;
  /** A mouse button goes down. */
  static final int MOUSE_PRESS = 1;
  /** A mouse button goes up. */
  static final int MOUSE_RELEASE = 2;
  /** A turn of the mouse wheel. */
  static final int MOUSE_WHEEL = 3;

  /** The left mouse button. */
  static final int BUTTON_LEFT = 0;
  /** The middle mouse button. */
  static final int BUTTON_MIDDLE = 1;
  /** The right mouse button. */
  static final int BUTTON_RIGHT = 2;

  /** Press and release a named key, such as {@code Enter}. */
  static final int KEY_PRESS = 0;
  /** Type text character by character. */
  static final int KEY_TYPE = 1;

  private static final int FRAME_HEADER_BYTES = 12;
  private static final int MOUSE_BYTES = 11;
  private static final int MAXIMUM_CLICK_COUNT = 3;

  private static final int VERSION_BYTES = 2;
  private static final int STRING_LENGTH_BYTES = 2;
  private static final int ERROR_CODE_BYTES = 4;
  private static final int ACTION_BYTES = 1;
  private static final int LOADING_BYTES = 1;

  private static final int UTF8_TWO_BYTE_START = 0x80;
  private static final int UTF8_THREE_BYTE_START = 0x800;
  private static final int UTF8_FOUR_BYTE_START = 0x10000;
  private static final int UTF8_CONTINUATION_MASK = 0xC0;
  private static final int UTF8_CONTINUATION_BITS = 0x80;

  private HelperProtocol() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  /**
   * Writes the first message of a helper.
   *
   * @param output   the stream
   * @param token the session token
   * @throws IOException if the stream fails
   */
  static void writeHello(final DataOutput output, final byte[] token) throws IOException {
    if (token.length != TOKEN_BYTES) {
      final String message = "Token length %s must be %s bytes!".formatted(token.length, TOKEN_BYTES);
      throw new IllegalArgumentException(message);
    }

    output.writeByte(HELLO);
    output.writeInt(TOKEN_BYTES + VERSION_BYTES);
    output.write(token);
    output.writeShort(VERSION);
  }

  /**
   * Writes a message that carries one string.
   *
   * @param output  the stream
   * @param type {@link #READY}, {@link #NOTICE} or {@link #FAILURE}
   * @param text the string, cut to {@link #MAXIMUM_TEXT_BYTES} bytes
   * @throws IOException if the stream fails
   */
  static void writeText(final DataOutput output, final int type, final String text) throws IOException {
    final byte[] bytes = encode(text);
    output.writeByte(type);
    output.writeInt(STRING_LENGTH_BYTES + bytes.length);
    writeString(output, bytes);
  }

  /**
   * Writes whether the page is loading.
   *
   * @param output     the stream
   * @param loading true while the page loads
   * @throws IOException if the stream fails
   */
  static void writeLoading(final DataOutput output, final boolean loading) throws IOException {
    output.writeByte(LOADING);
    output.writeInt(LOADING_BYTES);
    output.writeByte(loading ? 1 : 0);
  }

  /**
   * Writes that the page failed to load.
   *
   * @param output  the stream
   * @param code the network error code of Chromium
   * @param text the description of the error
   * @param url  the address that failed
   * @throws IOException if the stream fails
   */
  static void writeLoadError(final DataOutput output, final int code, final String text, final String url) throws IOException {
    final byte[] textBytes = encode(text);
    final byte[] urlBytes = encode(url);
    output.writeByte(LOAD_ERROR);
    output.writeInt(ERROR_CODE_BYTES + STRING_LENGTH_BYTES + textBytes.length + STRING_LENGTH_BYTES + urlBytes.length);
    output.writeInt(code);
    writeString(output, textBytes);
    writeString(output, urlBytes);
  }

  /**
   * Writes a changed region of the page.
   *
   * @param output    the stream
   * @param region the region, whose pixels are written from the start of its array
   * @throws IOException if the stream fails
   */
  static void writeFrame(final DataOutput output, final FrameRegion region) throws IOException {
    final int pixelBytes = region.getPixelBytes();
    output.writeByte(FRAME);
    output.writeInt(FRAME_HEADER_BYTES + pixelBytes);
    output.writeShort(region.getPageWidth());
    output.writeShort(region.getPageHeight());
    output.writeShort(region.getX());
    output.writeShort(region.getY());
    output.writeShort(region.getWidth());
    output.writeShort(region.getHeight());
    final byte[] pixels = region.getPixels();
    output.write(pixels, 0, pixelBytes);
  }

  /**
   * Writes sound of the page.
   *
   * @param output     the stream
   * @param samples the samples, 16-bit little-endian stereo at 48 kHz, from the start of the array
   * @param length  the number of bytes of samples, whole frames and at most {@link #MAXIMUM_AUDIO_BYTES}
   * @throws IOException if the stream fails
   */
  static void writeAudio(final DataOutput output, final byte[] samples, final int length) throws IOException {
    if (length <= 0 || length > MAXIMUM_AUDIO_BYTES || length % AUDIO_FRAME_BYTES != 0 || length > samples.length) {
      final String message = "Sound of %s bytes cannot be sent!".formatted(length);
      throw new IllegalArgumentException(message);
    }

    output.writeByte(AUDIO);
    output.writeInt(length);
    output.write(samples, 0, length);
  }

  /**
   * Writes mouse input.
   *
   * @param output   the stream
   * @param input the input
   * @throws IOException if the stream fails
   */
  static void writeMouse(final DataOutput output, final MouseInput input) throws IOException {
    output.writeByte(MOUSE);
    output.writeInt(MOUSE_BYTES);
    output.writeByte(input.getAction());
    output.writeShort(input.getX());
    output.writeShort(input.getY());
    output.writeByte(input.getButton());
    output.writeByte(input.getClickCount());
    output.writeShort(input.getDeltaX());
    output.writeShort(input.getDeltaY());
  }

  /**
   * Writes keyboard input.
   *
   * @param output    the stream
   * @param action {@link #KEY_PRESS} or {@link #KEY_TYPE}
   * @param value  the key name or the text, cut to {@link #MAXIMUM_TEXT_BYTES} bytes
   * @throws IOException if the stream fails
   */
  static void writeKey(final DataOutput output, final int action, final String value) throws IOException {
    final byte[] bytes = encode(value);
    output.writeByte(KEY);
    output.writeInt(ACTION_BYTES + STRING_LENGTH_BYTES + bytes.length);
    output.writeByte(action);
    writeString(output, bytes);
  }

  /**
   * Writes the request to close the browser.
   *
   * @param output the stream
   * @throws IOException if the stream fails
   */
  static void writeClose(final DataOutput output) throws IOException {
    output.writeByte(CLOSE);
    output.writeInt(0);
  }

  /**
   * Reads the next message.
   *
   * @param input     the stream
   * @param pixels supplies an array for the pixels of a frame, which may be reused once the message has been handled;
   *               an array shorter than the frame means frames are not expected here, which ends the reading
   * @return the message
   * @throws java.io.EOFException if the stream ends before or inside a message
   * @throws ProtocolException    if the message breaks a rule of the protocol
   * @throws IOException          if the stream fails
   */
  static HelperMessage read(final DataInput input, final IntFunction<byte[]> pixels) throws IOException {
    final int type = input.readUnsignedByte();
    final int length = input.readInt();
    return switch (type) {
      case HELLO -> readHello(input, length);
      case READY, NOTICE, FAILURE -> readTextMessage(input, type, length);
      case FRAME -> readFrame(input, length, pixels);
      case AUDIO -> readAudio(input, length);
      case LOADING -> readLoading(input, length);
      case LOAD_ERROR -> readLoadError(input, length);
      case MOUSE -> readMouse(input, length);
      case KEY -> readKey(input, length);
      case CLOSE -> readClose(length);
      default -> {
        final String message = "Message type %s is unknown!".formatted(type);
        throw new ProtocolException(message);
      }
    };
  }

  private static HelperMessage readHello(final DataInput input, final int length) throws IOException {
    expectLength(HELLO, length, TOKEN_BYTES + VERSION_BYTES);
    final byte[] token = new byte[TOKEN_BYTES];
    input.readFully(token);
    final int version = input.readUnsignedShort();
    return HelperMessage.hello(token, version);
  }

  private static HelperMessage readTextMessage(final DataInput input, final int type, final int length) throws IOException {
    final String text = readSingleString(input, type, length);
    return HelperMessage.text(type, text);
  }

  private static HelperMessage readLoading(final DataInput input, final int length) throws IOException {
    expectLength(LOADING, length, LOADING_BYTES);
    final int value = input.readUnsignedByte();
    if (value > 1) {
      final String message = "Loading state %s must be 0 or 1!".formatted(value);
      throw new ProtocolException(message);
    }

    return HelperMessage.loading(value == 1);
  }

  private static HelperMessage readLoadError(final DataInput input, final int length) throws IOException {
    checkLength(
      LOAD_ERROR,
      length,
      ERROR_CODE_BYTES + STRING_LENGTH_BYTES + STRING_LENGTH_BYTES,
      ERROR_CODE_BYTES + STRING_LENGTH_BYTES + MAXIMUM_TEXT_BYTES + STRING_LENGTH_BYTES + MAXIMUM_TEXT_BYTES
    );
    final int code = input.readInt();
    final byte[] textBytes = readStringBytes(input);
    final byte[] urlBytes = readStringBytes(input);
    final int used = ERROR_CODE_BYTES + STRING_LENGTH_BYTES + textBytes.length + STRING_LENGTH_BYTES + urlBytes.length;
    if (used != length) {
      final String message = "A load error of %s bytes holds %s bytes!".formatted(length, used);
      throw new ProtocolException(message);
    }

    final String text = decode(textBytes);
    final String url = decode(urlBytes);
    return HelperMessage.loadError(code, text, url);
  }

  private static HelperMessage readFrame(final DataInput input, final int length, final IntFunction<byte[]> pixels) throws IOException {
    final long maximum = FRAME_HEADER_BYTES + (long) MAXIMUM_SIDE * MAXIMUM_SIDE * PIXEL_BYTES;
    checkLength(FRAME, length, FRAME_HEADER_BYTES, maximum);
    final int pageWidth = input.readUnsignedShort();
    final int pageHeight = input.readUnsignedShort();
    final int left = input.readUnsignedShort();
    final int top = input.readUnsignedShort();
    final int width = input.readUnsignedShort();
    final int height = input.readUnsignedShort();
    checkSide("page width", pageWidth);
    checkSide("page height", pageHeight);
    checkSide("region width", width);
    checkSide("region height", height);

    if ((long) left + width > pageWidth || (long) top + height > pageHeight) {
      final String message = "Region %sx%s at %s,%s leaves the %sx%s page!".formatted(width, height, left, top, pageWidth, pageHeight);
      throw new ProtocolException(message);
    }

    final long pixelBytes = (long) width * height * PIXEL_BYTES;
    if (FRAME_HEADER_BYTES + pixelBytes != length) {
      final String message = "A frame of %s bytes cannot hold a %sx%s region!".formatted(length, width, height);
      throw new ProtocolException(message);
    }

    final int size = (int) pixelBytes;
    final byte[] target = pixels.apply(size);
    if (target.length < size) {
      final String message = "A frame of %s bytes arrived where no frame of that size is expected!".formatted(size);
      throw new ProtocolException(message);
    }

    input.readFully(target, 0, size);
    final FrameRegion region = new FrameRegion(pageWidth, pageHeight, left, top, width, height, target);
    return HelperMessage.frame(region);
  }

  private static HelperMessage readAudio(final DataInput input, final int length) throws IOException {
    checkLength(AUDIO, length, AUDIO_FRAME_BYTES, MAXIMUM_AUDIO_BYTES);

    if (length % AUDIO_FRAME_BYTES != 0) {
      final String message = "Sound of %s bytes does not hold whole frames of %s bytes!".formatted(length, AUDIO_FRAME_BYTES);
      throw new ProtocolException(message);
    }

    final byte[] samples = new byte[length];
    input.readFully(samples);

    return HelperMessage.audio(samples);
  }

  private static HelperMessage readMouse(final DataInput input, final int length) throws IOException {
    expectLength(MOUSE, length, MOUSE_BYTES);
    final int action = input.readUnsignedByte();
    final int pageX = input.readUnsignedShort();
    final int pageY = input.readUnsignedShort();
    final int button = input.readUnsignedByte();
    final int clickCount = input.readUnsignedByte();
    final int deltaX = input.readShort();
    final int deltaY = input.readShort();
    if (action > MOUSE_WHEEL) {
      final String message = "Mouse action %s is unknown!".formatted(action);
      throw new ProtocolException(message);
    }

    if (button > BUTTON_RIGHT) {
      final String message = "Mouse button %s is unknown!".formatted(button);
      throw new ProtocolException(message);
    }

    if (clickCount > MAXIMUM_CLICK_COUNT) {
      final String message = "Click count %s must be at most %s!".formatted(clickCount, MAXIMUM_CLICK_COUNT);
      throw new ProtocolException(message);
    }

    final MouseInput mouseInput = new MouseInput(action, pageX, pageY, button, clickCount, deltaX, deltaY);
    return HelperMessage.mouse(mouseInput);
  }

  private static HelperMessage readKey(final DataInput input, final int length) throws IOException {
    checkLength(KEY, length, ACTION_BYTES + STRING_LENGTH_BYTES, ACTION_BYTES + STRING_LENGTH_BYTES + MAXIMUM_TEXT_BYTES);
    final int action = input.readUnsignedByte();
    if (action > KEY_TYPE) {
      final String message = "Key action %s is unknown!".formatted(action);
      throw new ProtocolException(message);
    }

    final byte[] bytes = readStringBytes(input);
    final int used = ACTION_BYTES + STRING_LENGTH_BYTES + bytes.length;
    if (used != length) {
      final String message = "A key message of %s bytes holds %s bytes!".formatted(length, used);
      throw new ProtocolException(message);
    }

    final String value = decode(bytes);
    return HelperMessage.key(action, value);
  }

  private static HelperMessage readClose(final int length) throws IOException {
    expectLength(CLOSE, length, 0);

    return HelperMessage.close();
  }

  private static String readSingleString(final DataInput input, final int type, final int length) throws IOException {
    checkLength(type, length, STRING_LENGTH_BYTES, STRING_LENGTH_BYTES + MAXIMUM_TEXT_BYTES);
    final byte[] bytes = readStringBytes(input);
    if (STRING_LENGTH_BYTES + bytes.length != length) {
      final String message = "A message of %s bytes holds a string of %s bytes!".formatted(length, bytes.length);
      throw new ProtocolException(message);
    }

    return decode(bytes);
  }

  private static byte[] readStringBytes(final DataInput input) throws IOException {
    final int length = input.readUnsignedShort();
    if (length > MAXIMUM_TEXT_BYTES) {
      final String message = "A string of %s bytes must have at most %s bytes!".formatted(length, MAXIMUM_TEXT_BYTES);
      throw new ProtocolException(message);
    }

    final byte[] bytes = new byte[length];
    input.readFully(bytes);

    return bytes;
  }

  private static void writeString(final DataOutput output, final byte[] bytes) throws IOException {
    output.writeShort(bytes.length);
    output.write(bytes);
  }

  /**
   * Splits a text into parts that each fit into one message, at character boundaries and never between the two
   * characters of a line break {@code \r\n}, so a long text can be sent as several messages without losing any of it.
   *
   * @param text the text
   * @return the parts, in order; an empty text is one empty part
   */
  static List<String> split(final String text) {
    final List<String> parts = new ArrayList<>();
    int start = 0;
    int bytes = 0;
    int index = 0;
    while (index < text.length()) {
      final int codePoint = text.codePointAt(index);
      final int size = utf8Length(codePoint);
      if (bytes + size > MAXIMUM_TEXT_BYTES) {
        final boolean isLineBreak = codePoint == '\n' && text.charAt(index - 1) == '\r';
        final int end = isLineBreak ? index - 1 : index;
        parts.add(text.substring(start, end));
        start = end;
        bytes = index - end;
      }
      bytes += size;
      index += Character.charCount(codePoint);
    }
    parts.add(text.substring(start));

    return parts;
  }

  /**
   * Counts the bytes of a character in UTF-8; an unpaired surrogate, which Java encodes as one byte, counts three.
   *
   * @param codePoint the character
   * @return the bytes, from 1 to 4
   */
  private static int utf8Length(final int codePoint) {
    if (codePoint < UTF8_TWO_BYTE_START) {
      return 1;
    }

    if (codePoint < UTF8_THREE_BYTE_START) {
      return 2;
    }

    return codePoint < UTF8_FOUR_BYTE_START ? 3 : 4;
  }

  /**
   * Encodes a string as UTF-8, cut at a character boundary to at most {@link #MAXIMUM_TEXT_BYTES} bytes.
   *
   * @param text the string
   * @return the bytes
   */
  static byte[] encode(final String text) {
    final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= MAXIMUM_TEXT_BYTES) {
      return bytes;
    }

    int end = MAXIMUM_TEXT_BYTES;
    while ((bytes[end] & UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION_BITS) {
      end--;
    }
    final byte[] cut = new byte[end];
    System.arraycopy(bytes, 0, cut, 0, end);

    return cut;
  }

  private static String decode(final byte[] bytes) throws ProtocolException {
    final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
    decoder.onMalformedInput(CodingErrorAction.REPORT);
    decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
    final ByteBuffer input = ByteBuffer.wrap(bytes);
    try {
      final CharBuffer decoded = decoder.decode(input);
      return decoded.toString();
    } catch (final CharacterCodingException exception) {
      final ProtocolException failure = new ProtocolException("A string is not valid UTF-8!");
      failure.initCause(exception);
      throw failure;
    }
  }

  private static void expectLength(final int type, final int length, final int expected) throws ProtocolException {
    if (length != expected) {
      final String message = "A message of type %s has %s bytes, but %s bytes were announced!".formatted(type, expected, length);
      throw new ProtocolException(message);
    }
  }

  private static void checkLength(final int type, final int length, final long minimum, final long maximum) throws ProtocolException {
    if (length < minimum || length > maximum) {
      final String announced = Integer.toUnsignedString(length);
      final String message = "A message of type %s has %s to %s bytes, but %s bytes were announced!".formatted(
        type,
        minimum,
        maximum,
        announced
      );
      throw new ProtocolException(message);
    }
  }

  private static void checkSide(final String name, final int value) throws ProtocolException {
    if (value < 1 || value > MAXIMUM_SIDE) {
      final String message = "The %s %s must be between 1 and %s!".formatted(name, value, MAXIMUM_SIDE);
      throw new ProtocolException(message);
    }
  }
}
