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
  static final int MAX_SIDE = 4096;

  /**
   * The largest string in a message, in UTF-8 bytes.
   */
  static final int MAX_TEXT_BYTES = 4096;

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
  static final int MAX_AUDIO_BYTES = 65_536;

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
  private static final int MAX_CLICK_COUNT = 3;

  private HelperProtocol() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  /**
   * Writes the first message of a helper.
   *
   * @param out   the stream
   * @param token the session token
   * @throws IOException if the stream fails
   */
  static void writeHello(final DataOutput out, final byte[] token) throws IOException {
    if (token.length != TOKEN_BYTES) {
      final String message = "Token length %s must be %s bytes!".formatted(token.length, TOKEN_BYTES);
      throw new IllegalArgumentException(message);
    }
    out.writeByte(HELLO);
    out.writeInt(TOKEN_BYTES + 2);
    out.write(token);
    out.writeShort(VERSION);
  }

  /**
   * Writes a message that carries one string.
   *
   * @param out  the stream
   * @param type {@link #READY}, {@link #NOTICE} or {@link #FAILURE}
   * @param text the string, cut to {@link #MAX_TEXT_BYTES} bytes
   * @throws IOException if the stream fails
   */
  static void writeText(final DataOutput out, final int type, final String text) throws IOException {
    final byte[] bytes = encode(text);
    out.writeByte(type);
    out.writeInt(2 + bytes.length);
    writeString(out, bytes);
  }

  /**
   * Writes whether the page is loading.
   *
   * @param out     the stream
   * @param loading true while the page loads
   * @throws IOException if the stream fails
   */
  static void writeLoading(final DataOutput out, final boolean loading) throws IOException {
    out.writeByte(LOADING);
    out.writeInt(1);
    out.writeByte(loading ? 1 : 0);
  }

  /**
   * Writes that the page failed to load.
   *
   * @param out  the stream
   * @param code the network error code of Chromium
   * @param text the description of the error
   * @param url  the address that failed
   * @throws IOException if the stream fails
   */
  static void writeLoadError(final DataOutput out, final int code, final String text, final String url) throws IOException {
    final byte[] textBytes = encode(text);
    final byte[] urlBytes = encode(url);
    out.writeByte(LOAD_ERROR);
    out.writeInt(4 + 2 + textBytes.length + 2 + urlBytes.length);
    out.writeInt(code);
    writeString(out, textBytes);
    writeString(out, urlBytes);
  }

  /**
   * Writes a changed region of the page.
   *
   * @param out    the stream
   * @param region the region, whose pixels are written from the start of its array
   * @throws IOException if the stream fails
   */
  static void writeFrame(final DataOutput out, final FrameRegion region) throws IOException {
    final int pixelBytes = region.getPixelBytes();
    out.writeByte(FRAME);
    out.writeInt(FRAME_HEADER_BYTES + pixelBytes);
    out.writeShort(region.getPageWidth());
    out.writeShort(region.getPageHeight());
    out.writeShort(region.getX());
    out.writeShort(region.getY());
    out.writeShort(region.getWidth());
    out.writeShort(region.getHeight());
    final byte[] pixels = region.getPixels();
    out.write(pixels, 0, pixelBytes);
  }

  /**
   * Writes sound of the page.
   *
   * @param out     the stream
   * @param samples the samples, 16-bit little-endian stereo at 48 kHz, from the start of the array
   * @param length  the number of bytes of samples, whole frames and at most {@link #MAX_AUDIO_BYTES}
   * @throws IOException if the stream fails
   */
  static void writeAudio(final DataOutput out, final byte[] samples, final int length) throws IOException {
    if (length <= 0 || length > MAX_AUDIO_BYTES || length % AUDIO_FRAME_BYTES != 0 || length > samples.length) {
      final String message = "Sound of %s bytes cannot be sent!".formatted(length);
      throw new IllegalArgumentException(message);
    }
    out.writeByte(AUDIO);
    out.writeInt(length);
    out.write(samples, 0, length);
  }

  /**
   * Writes mouse input.
   *
   * @param out   the stream
   * @param input the input
   * @throws IOException if the stream fails
   */
  static void writeMouse(final DataOutput out, final MouseInput input) throws IOException {
    out.writeByte(MOUSE);
    out.writeInt(MOUSE_BYTES);
    out.writeByte(input.getAction());
    out.writeShort(input.getX());
    out.writeShort(input.getY());
    out.writeByte(input.getButton());
    out.writeByte(input.getClickCount());
    out.writeShort(input.getDeltaX());
    out.writeShort(input.getDeltaY());
  }

  /**
   * Writes keyboard input.
   *
   * @param out    the stream
   * @param action {@link #KEY_PRESS} or {@link #KEY_TYPE}
   * @param value  the key name or the text, cut to {@link #MAX_TEXT_BYTES} bytes
   * @throws IOException if the stream fails
   */
  static void writeKey(final DataOutput out, final int action, final String value) throws IOException {
    final byte[] bytes = encode(value);
    out.writeByte(KEY);
    out.writeInt(1 + 2 + bytes.length);
    out.writeByte(action);
    writeString(out, bytes);
  }

  /**
   * Writes the request to close the browser.
   *
   * @param out the stream
   * @throws IOException if the stream fails
   */
  static void writeClose(final DataOutput out) throws IOException {
    out.writeByte(CLOSE);
    out.writeInt(0);
  }

  /**
   * Reads the next message.
   *
   * @param in     the stream
   * @param pixels supplies an array for the pixels of a frame, which may be reused once the message has been handled;
   *               an array shorter than the frame means frames are not expected here, which ends the reading
   * @return the message
   * @throws java.io.EOFException if the stream ends before or inside a message
   * @throws ProtocolException    if the message breaks a rule of the protocol
   * @throws IOException          if the stream fails
   */
  static HelperMessage read(final DataInput in, final IntFunction<byte[]> pixels) throws IOException {
    final int type = in.readUnsignedByte();
    final int length = in.readInt();
    return switch (type) {
      case HELLO -> readHello(in, length);
      case READY, NOTICE, FAILURE -> readTextMessage(in, type, length);
      case FRAME -> readFrame(in, length, pixels);
      case AUDIO -> readAudio(in, length);
      case LOADING -> readLoading(in, length);
      case LOAD_ERROR -> readLoadError(in, length);
      case MOUSE -> readMouse(in, length);
      case KEY -> readKey(in, length);
      case CLOSE -> readClose(length);
      default -> {
        final String message = "Message type %s is unknown!".formatted(type);
        throw new ProtocolException(message);
      }
    };
  }

  private static HelperMessage readHello(final DataInput in, final int length) throws IOException {
    expectLength(HELLO, length, TOKEN_BYTES + 2);
    final byte[] token = new byte[TOKEN_BYTES];
    in.readFully(token);
    final int version = in.readUnsignedShort();
    return HelperMessage.hello(token, version);
  }

  private static HelperMessage readTextMessage(final DataInput in, final int type, final int length) throws IOException {
    final String text = readSingleString(in, type, length);
    return HelperMessage.text(type, text);
  }

  private static HelperMessage readLoading(final DataInput in, final int length) throws IOException {
    expectLength(LOADING, length, 1);
    final int value = in.readUnsignedByte();
    if (value > 1) {
      final String message = "Loading state %s must be 0 or 1!".formatted(value);
      throw new ProtocolException(message);
    }
    return HelperMessage.loading(value == 1);
  }

  private static HelperMessage readLoadError(final DataInput in, final int length) throws IOException {
    checkLength(LOAD_ERROR, length, 4 + 2 + 2, 4 + 2 * (2 + MAX_TEXT_BYTES));
    final int code = in.readInt();
    final byte[] textBytes = readStringBytes(in);
    final byte[] urlBytes = readStringBytes(in);
    final int used = 4 + 2 + textBytes.length + 2 + urlBytes.length;
    if (used != length) {
      final String message = "A load error of %s bytes holds %s bytes!".formatted(length, used);
      throw new ProtocolException(message);
    }
    final String text = decode(textBytes);
    final String url = decode(urlBytes);
    return HelperMessage.loadError(code, text, url);
  }

  private static HelperMessage readFrame(final DataInput in, final int length, final IntFunction<byte[]> pixels) throws IOException {
    final long maximum = FRAME_HEADER_BYTES + (long) MAX_SIDE * MAX_SIDE * PIXEL_BYTES;
    checkLength(FRAME, length, FRAME_HEADER_BYTES, maximum);
    final int pageWidth = in.readUnsignedShort();
    final int pageHeight = in.readUnsignedShort();
    final int left = in.readUnsignedShort();
    final int top = in.readUnsignedShort();
    final int width = in.readUnsignedShort();
    final int height = in.readUnsignedShort();
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
    in.readFully(target, 0, size);
    final FrameRegion region = new FrameRegion(pageWidth, pageHeight, left, top, width, height, target);
    return HelperMessage.frame(region);
  }

  private static HelperMessage readAudio(final DataInput in, final int length) throws IOException {
    checkLength(AUDIO, length, AUDIO_FRAME_BYTES, MAX_AUDIO_BYTES);
    if (length % AUDIO_FRAME_BYTES != 0) {
      final String message = "Sound of %s bytes does not hold whole frames of %s bytes!".formatted(length, AUDIO_FRAME_BYTES);
      throw new ProtocolException(message);
    }
    final byte[] samples = new byte[length];
    in.readFully(samples);
    return HelperMessage.audio(samples);
  }

  private static HelperMessage readMouse(final DataInput in, final int length) throws IOException {
    expectLength(MOUSE, length, MOUSE_BYTES);
    final int action = in.readUnsignedByte();
    final int pageX = in.readUnsignedShort();
    final int pageY = in.readUnsignedShort();
    final int button = in.readUnsignedByte();
    final int clickCount = in.readUnsignedByte();
    final int deltaX = in.readShort();
    final int deltaY = in.readShort();
    if (action > MOUSE_WHEEL) {
      final String message = "Mouse action %s is unknown!".formatted(action);
      throw new ProtocolException(message);
    }
    if (button > BUTTON_RIGHT) {
      final String message = "Mouse button %s is unknown!".formatted(button);
      throw new ProtocolException(message);
    }
    if (clickCount > MAX_CLICK_COUNT) {
      final String message = "Click count %s must be at most %s!".formatted(clickCount, MAX_CLICK_COUNT);
      throw new ProtocolException(message);
    }
    final MouseInput input = new MouseInput(action, pageX, pageY, button, clickCount, deltaX, deltaY);
    return HelperMessage.mouse(input);
  }

  private static HelperMessage readKey(final DataInput in, final int length) throws IOException {
    checkLength(KEY, length, 1 + 2, 1 + 2 + MAX_TEXT_BYTES);
    final int action = in.readUnsignedByte();
    if (action > KEY_TYPE) {
      final String message = "Key action %s is unknown!".formatted(action);
      throw new ProtocolException(message);
    }
    final byte[] bytes = readStringBytes(in);
    if (1 + 2 + bytes.length != length) {
      final String message = "A key message of %s bytes holds %s bytes!".formatted(length, 1 + 2 + bytes.length);
      throw new ProtocolException(message);
    }
    final String value = decode(bytes);
    return HelperMessage.key(action, value);
  }

  private static HelperMessage readClose(final int length) throws IOException {
    expectLength(CLOSE, length, 0);
    return HelperMessage.close();
  }

  private static String readSingleString(final DataInput in, final int type, final int length) throws IOException {
    checkLength(type, length, 2, 2 + MAX_TEXT_BYTES);
    final byte[] bytes = readStringBytes(in);
    if (2 + bytes.length != length) {
      final String message = "A message of %s bytes holds a string of %s bytes!".formatted(length, bytes.length);
      throw new ProtocolException(message);
    }
    return decode(bytes);
  }

  private static byte[] readStringBytes(final DataInput in) throws IOException {
    final int length = in.readUnsignedShort();
    if (length > MAX_TEXT_BYTES) {
      final String message = "A string of %s bytes must have at most %s bytes!".formatted(length, MAX_TEXT_BYTES);
      throw new ProtocolException(message);
    }
    final byte[] bytes = new byte[length];
    in.readFully(bytes);
    return bytes;
  }

  private static void writeString(final DataOutput out, final byte[] bytes) throws IOException {
    out.writeShort(bytes.length);
    out.write(bytes);
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
      if (bytes + size > MAX_TEXT_BYTES) {
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
    if (codePoint < 0x80) {
      return 1;
    }
    if (codePoint < 0x800) {
      return 2;
    }
    return codePoint < 0x10000 ? 3 : 4;
  }

  /**
   * Encodes a string as UTF-8, cut at a character boundary to at most {@link #MAX_TEXT_BYTES} bytes.
   *
   * @param text the string
   * @return the bytes
   */
  static byte[] encode(final String text) {
    final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= MAX_TEXT_BYTES) {
      return bytes;
    }
    int end = MAX_TEXT_BYTES;
    while ((bytes[end] & 0xC0) == 0x80) {
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
    if (value < 1 || value > MAX_SIDE) {
      final String message = "The %s %s must be between 1 and %s!".formatted(name, value, MAX_SIDE);
      throw new ProtocolException(message);
    }
  }
}
