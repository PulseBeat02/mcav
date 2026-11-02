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
package me.brandonli.mcav.vm;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

// The RFB subset of the audio connection: a second, shared client that asks for QEMU's audio extension and never for
// the picture. Every length is checked before anything is allocated, and an unexpected message ends the connection.
final class QemuAudioProtocol {

  static final int AUDIO_ENCODING = -259;

  private static final int FORMAT_S16 = 3;

  // QEMU sends about 10 ms at a time
  static final int MAXIMUM_AUDIO_BYTES = 64 * 1024;

  static final int MAXIMUM_TEXT_BYTES = 4096;

  static final int MAXIMUM_CUT_TEXT_BYTES = 1024 * 1024;

  static final int MAXIMUM_RECTANGLES = 16;

  static final int SERVER_UPDATE = 0;
  static final int SERVER_COLOUR_MAP = 1;
  static final int SERVER_BELL = 2;
  static final int SERVER_CUT_TEXT = 3;
  static final int SERVER_QEMU = 255;

  static final int QEMU_AUDIO = 1;

  private static final int AUDIO_END = 0;
  private static final int AUDIO_BEGIN = 1;
  static final int AUDIO_DATA = 2;

  private static final String VERSION = "RFB 003.008\n";
  private static final int VERSION_BYTES = 12;
  private static final Pattern SERVER_VERSION = Pattern.compile("RFB \\d{3}\\.\\d{3}\n");

  private static final int MAJOR_VERSION_START = 4;
  private static final int MAJOR_VERSION_END = 7;
  private static final int MINOR_VERSION_START = 8;
  private static final int MINOR_VERSION_END = 11;

  private static final int MINIMUM_MAJOR_VERSION = 3;
  private static final int MINIMUM_MINOR_VERSION = 8;

  private static final int SECURITY_NONE = 1;
  private static final int SECURITY_VNC = 2;

  private static final int SECURITY_RESULT_OK = 0;

  private static final int CHALLENGE_BYTES = 16;
  // VNC authentication takes the first eight characters of the password as the DES key
  private static final int KEY_BYTES = 8;
  private static final int DES_BLOCK_BYTES = 8;
  // CBC from a zero vector encrypts a single block as ECB, the mode VNC authentication uses
  private static final String CIPHER = "DES/CBC/NoPadding";
  private static final String KEY_ALGORITHM = "DES";

  private static final int BYTE_MASK = 0xFF;
  private static final int REVERSED_BYTE_SHIFT = Integer.SIZE - Byte.SIZE;

  private static final int SHARED_DISPLAY = 1;

  private static final int CLIENT_SET_ENCODINGS = 2;
  private static final int CLIENT_QEMU = 255;
  private static final int CLIENT_AUDIO_ENABLE = 0;
  private static final int CLIENT_AUDIO_SET_FORMAT = 2;

  private static final int PADDING = 0;
  private static final int REQUESTED_ENCODINGS = 1;

  private static final int FRAMEBUFFER_SIZE_BYTES = 4;
  private static final int PIXEL_FORMAT_BYTES = 16;
  private static final int UPDATE_PADDING_BYTES = 1;
  private static final int COLOUR_MAP_PADDING_BYTES = 1;
  private static final int FIRST_COLOUR_BYTES = 2;
  private static final int COLOUR_BYTES = 6;
  private static final int CUT_TEXT_PADDING_BYTES = 3;
  private static final int RECTANGLE_BYTES = 8;

  private static final int SKIP_BUFFER_BYTES = 8192;

  private static final String REFUSED_MESSAGE = "Because of \"%s\", VNC server refused the connection!";

  private QemuAudioProtocol() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  static void readVersion(final DataInput input) throws IOException {
    final byte[] bytes = new byte[VERSION_BYTES];
    input.readFully(bytes);

    final String version = new String(bytes, StandardCharsets.US_ASCII);
    final Matcher matcher = SERVER_VERSION.matcher(version);
    final boolean rfb = matcher.matches();
    if (!rfb) {
      final String message = "VNC server does not speak RFB!";
      throw new ProtocolException(message);
    }

    final String majorText = version.substring(MAJOR_VERSION_START, MAJOR_VERSION_END);
    final int major = Integer.parseInt(majorText);
    final String minorText = version.substring(MINOR_VERSION_START, MINOR_VERSION_END);
    final int minor = Integer.parseInt(minorText);
    if (major < MINIMUM_MAJOR_VERSION || (major == MINIMUM_MAJOR_VERSION && minor < MINIMUM_MINOR_VERSION)) {
      final String message = "VNC server speaks RFB %s.%s instead of RFB 3.8 or later!".formatted(major, minor);
      throw new ProtocolException(message);
    }
  }

  static void writeVersion(final DataOutput output) throws IOException {
    final byte[] version = VERSION.getBytes(StandardCharsets.US_ASCII);
    output.write(version);
  }

  static boolean negotiateSecurity(final DataInput input, final DataOutput output, final String password) throws IOException {
    final int count = input.readUnsignedByte();
    if (count == 0) {
      final String reason = readText(input);
      final String message = REFUSED_MESSAGE.formatted(reason);
      throw new ProtocolException(message);
    }

    boolean withoutAuthentication = false;
    boolean withPassword = false;
    for (int index = 0; index < count; index++) {
      final int type = input.readUnsignedByte();
      withoutAuthentication = withoutAuthentication || type == SECURITY_NONE;
      withPassword = withPassword || type == SECURITY_VNC;
    }

    final boolean authenticate = withPassword && !password.isEmpty();
    if (!authenticate && !withoutAuthentication) {
      final String message = "VNC server asks for authentication, which the audio connection does not do!";
      throw new ProtocolException(message);
    }

    final int securityType = authenticate ? SECURITY_VNC : SECURITY_NONE;
    output.writeByte(securityType);

    return authenticate;
  }

  // RFC 6143, 7.2.2
  static void answerChallenge(final DataInput input, final DataOutput output, final String password) throws IOException {
    final byte[] challenge = new byte[CHALLENGE_BYTES];
    input.readFully(challenge);

    final byte[] response = respond(challenge, password);
    output.write(response);
  }

  // the DES key is the first eight bytes of the password, zero-padded, with the bits of every byte reversed
  static byte[] respond(final byte[] challenge, final String password) throws IOException {
    final byte[] key = deriveKey(password);
    try {
      return encrypt(challenge, key);
    } catch (final GeneralSecurityException exception) {
      final String message = "Java runtime cannot answer the password challenge of the VNC server!";
      throw new IOException(message, exception);
    }
  }

  private static byte[] deriveKey(final String password) {
    final byte[] characters = password.getBytes(StandardCharsets.ISO_8859_1);
    final byte[] key = new byte[KEY_BYTES];
    final int length = Math.min(KEY_BYTES, characters.length);
    for (int index = 0; index < length; index++) {
      key[index] = reverseBits(characters[index]);
    }

    return key;
  }

  private static byte reverseBits(final byte character) {
    final int unsigned = character & BYTE_MASK;
    final int reversed = Integer.reverse(unsigned);
    return (byte) (reversed >>> REVERSED_BYTE_SHIFT);
  }

  private static byte[] encrypt(final byte[] challenge, final byte[] key) throws GeneralSecurityException {
    final Cipher cipher = Cipher.getInstance(CIPHER);
    final SecretKeySpec secret = new SecretKeySpec(key, KEY_ALGORITHM);
    final byte[] zeroVector = new byte[DES_BLOCK_BYTES];
    final IvParameterSpec zeros = new IvParameterSpec(zeroVector);
    final byte[] response = new byte[challenge.length];
    for (int block = 0; block < challenge.length; block += DES_BLOCK_BYTES) {
      cipher.init(Cipher.ENCRYPT_MODE, secret, zeros);
      cipher.doFinal(challenge, block, DES_BLOCK_BYTES, response, block);
    }

    return response;
  }

  static void readSecurityResult(final DataInput input) throws IOException {
    final int result = input.readInt();
    if (result != SECURITY_RESULT_OK) {
      final String reason = readText(input);
      final String message = REFUSED_MESSAGE.formatted(reason);
      throw new ProtocolException(message);
    }
  }

  static void writeClientInitialization(final DataOutput output) throws IOException {
    output.writeByte(SHARED_DISPLAY);
  }

  static void readServerInitialization(final DataInput input) throws IOException {
    skipFully(input, FRAMEBUFFER_SIZE_BYTES + PIXEL_FORMAT_BYTES);
    readText(input);
  }

  static void writeSetEncodings(final DataOutput output) throws IOException {
    output.writeByte(CLIENT_SET_ENCODINGS);
    output.writeByte(PADDING);
    output.writeShort(REQUESTED_ENCODINGS);
    output.writeInt(AUDIO_ENCODING);
  }

  static void writeEnableAudio(final DataOutput output, final int channels, final int sampleRate) throws IOException {
    output.writeByte(CLIENT_QEMU);
    output.writeByte(QEMU_AUDIO);
    output.writeShort(CLIENT_AUDIO_SET_FORMAT);
    output.writeByte(FORMAT_S16);
    output.writeByte(channels);
    output.writeInt(sampleRate);

    output.writeByte(CLIENT_QEMU);
    output.writeByte(QEMU_AUDIO);
    output.writeShort(CLIENT_AUDIO_ENABLE);
  }

  static Message readMessage(final DataInput input, final int frameSize, final IntFunction<byte[]> buffers) throws IOException {
    final int type = input.readUnsignedByte();
    return switch (type) {
      case SERVER_UPDATE -> readUpdate(input);
      case SERVER_COLOUR_MAP -> readColourMap(input);
      case SERVER_BELL -> Message.IGNORED;
      case SERVER_CUT_TEXT -> readCutText(input);
      case SERVER_QEMU -> readQemu(input, frameSize, buffers);
      default -> {
        final String message = "VNC server sent message type %s, which the audio connection does not expect!".formatted(type);
        throw new ProtocolException(message);
      }
    };
  }

  private static Message readUpdate(final DataInput input) throws IOException {
    skipFully(input, UPDATE_PADDING_BYTES);

    final int rectangles = input.readUnsignedShort();
    if (rectangles > MAXIMUM_RECTANGLES) {
      final String message = "VNC server announced %s rectangles, more than the %s allowed!".formatted(rectangles, MAXIMUM_RECTANGLES);
      throw new ProtocolException(message);
    }

    for (int index = 0; index < rectangles; index++) {
      skipFully(input, RECTANGLE_BYTES);
      final int encoding = input.readInt();
      if (encoding != AUDIO_ENCODING) {
        final String message = "VNC server sent a rectangle of encoding %s, which the audio connection never asks for!".formatted(encoding);
        throw new ProtocolException(message);
      }
    }

    return rectangles == 0 ? Message.IGNORED : Message.ACKNOWLEDGED;
  }

  private static Message readColourMap(final DataInput input) throws IOException {
    skipFully(input, COLOUR_MAP_PADDING_BYTES + FIRST_COLOUR_BYTES);

    final int colours = input.readUnsignedShort();
    final long colourBytes = (long) colours * COLOUR_BYTES;
    skipFully(input, colourBytes);

    return Message.IGNORED;
  }

  private static Message readCutText(final DataInput input) throws IOException {
    skipFully(input, CUT_TEXT_PADDING_BYTES);

    final long length = readUnsignedInt(input);
    if (length > MAXIMUM_CUT_TEXT_BYTES) {
      final String message = "VNC server sent %s bytes of clipboard text, more than the %s bytes allowed!".formatted(
        length,
        MAXIMUM_CUT_TEXT_BYTES
      );
      throw new ProtocolException(message);
    }

    skipFully(input, length);

    return Message.IGNORED;
  }

  private static Message readQemu(final DataInput input, final int frameSize, final IntFunction<byte[]> buffers) throws IOException {
    final int submessage = input.readUnsignedByte();
    if (submessage != QEMU_AUDIO) {
      final String message = "VNC server sent QEMU message %s instead of audio!".formatted(submessage);
      throw new ProtocolException(message);
    }

    final int operation = input.readUnsignedShort();
    return switch (operation) {
      case AUDIO_END -> Message.END;
      case AUDIO_BEGIN -> Message.BEGIN;
      case AUDIO_DATA -> readAudioData(input, frameSize, buffers);
      default -> {
        final String message = "VNC server sent audio operation %s, which the audio connection does not know!".formatted(operation);
        throw new ProtocolException(message);
      }
    };
  }

  private static Message readAudioData(final DataInput input, final int frameSize, final IntFunction<byte[]> buffers) throws IOException {
    final long length = readUnsignedInt(input);
    if (length > MAXIMUM_AUDIO_BYTES || length % frameSize != 0) {
      final String message = "VNC server sent %s bytes of audio, not whole frames of at most %s bytes!".formatted(
        length,
        MAXIMUM_AUDIO_BYTES
      );
      throw new ProtocolException(message);
    }

    final int size = (int) length;
    final byte[] buffer = buffers.apply(size);
    input.readFully(buffer, 0, size);

    return Message.data(buffer, size);
  }

  private static String readText(final DataInput input) throws IOException {
    final long length = readUnsignedInt(input);
    if (length > MAXIMUM_TEXT_BYTES) {
      final String message = "VNC server sent a text of %s bytes, more than the %s bytes allowed!".formatted(length, MAXIMUM_TEXT_BYTES);
      throw new ProtocolException(message);
    }

    final byte[] bytes = new byte[(int) length];
    input.readFully(bytes);

    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static long readUnsignedInt(final DataInput input) throws IOException {
    final int value = input.readInt();
    return Integer.toUnsignedLong(value);
  }

  private static void skipFully(final DataInput input, final long count) throws IOException {
    final int scratchBytes = (int) Math.min(count, SKIP_BUFFER_BYTES);
    final byte[] scratch = new byte[scratchBytes];
    long left = count;
    while (left > 0) {
      final int chunk = (int) Math.min(left, scratch.length);
      input.readFully(scratch, 0, chunk);
      left -= chunk;
    }
  }

  static final class Message {

    static final Message IGNORED = new Message(Kind.IGNORED, new byte[0], 0);
    static final Message ACKNOWLEDGED = new Message(Kind.ACKNOWLEDGED, new byte[0], 0);
    static final Message BEGIN = new Message(Kind.BEGIN, new byte[0], 0);
    static final Message END = new Message(Kind.END, new byte[0], 0);

    private final Kind kind;
    private final byte[] samples;
    private final int length;

    private Message(final Kind kind, final byte[] samples, final int length) {
      this.kind = kind;
      this.samples = samples;
      this.length = length;
    }

    static Message data(final byte[] samples, final int length) {
      return new Message(Kind.DATA, samples, length);
    }

    Kind getKind() {
      return this.kind;
    }

    byte[] getSamples() {
      return this.samples;
    }

    int getLength() {
      return this.length;
    }
  }

  enum Kind {
    IGNORED,
    ACKNOWLEDGED,
    BEGIN,
    END,
    DATA,
  }
}
