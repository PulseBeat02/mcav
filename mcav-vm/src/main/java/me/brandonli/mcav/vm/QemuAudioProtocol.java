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
import java.util.function.IntFunction;
import java.util.regex.Pattern;

/**
 * The part of the RFB protocol that the audio connection to QEMU's VNC server speaks, with QEMU's audio extension.
 *
 * <p>The connection is a second, shared client of the display: it agrees on RFB 3.8 without authentication, asks for
 * the audio pseudo-encoding ({@value #AUDIO_ENCODING}), which QEMU confirms with an empty rectangle of that encoding,
 * sets the format of the samples and enables the audio. It never asks for the picture, so QEMU sends it none. From
 * then on QEMU sends a begin and an end whenever the guest starts and stops playing, and the samples in between.
 *
 * <p>The reader is strict and bounded: every length it reads has a limit that is checked before anything is
 * allocated, a message it does not expect ends the connection with a {@link ProtocolException}, and the few messages
 * a VNC server may send unasked, a bell, a change of the colour map and the clipboard, are skipped.
 */
final class QemuAudioProtocol {

  /**
   * The pseudo-encoding of QEMU's audio extension.
   */
  static final int AUDIO_ENCODING = -259;

  /**
   * The sample format QEMU is asked for: signed 16 bits.
   */
  static final int FORMAT_S16 = 3;

  /**
   * The longest audio message accepted, in bytes; QEMU sends about 10 ms at a time.
   */
  static final int MAX_AUDIO_BYTES = 64 * 1024;

  /**
   * The longest text a VNC server may send as a reason or a name, in bytes.
   */
  static final int MAX_TEXT_BYTES = 4096;

  /**
   * The longest clipboard text that is skipped, in bytes.
   */
  static final int MAX_CUT_TEXT_BYTES = 1024 * 1024;

  /**
   * The most rectangles one update may announce.
   */
  static final int MAX_RECTANGLES = 16;

  static final int SERVER_UPDATE = 0;
  static final int SERVER_COLOUR_MAP = 1;
  static final int SERVER_BELL = 2;
  static final int SERVER_CUT_TEXT = 3;
  static final int SERVER_QEMU = 255;
  static final int QEMU_AUDIO = 1;
  static final int AUDIO_END = 0;
  static final int AUDIO_BEGIN = 1;
  static final int AUDIO_DATA = 2;

  private static final String VERSION = "RFB 003.008\n";
  private static final int VERSION_BYTES = 12;
  private static final Pattern SERVER_VERSION = Pattern.compile("RFB \\d{3}\\.\\d{3}\n");
  private static final int SECURITY_NONE = 1;
  private static final int CLIENT_SET_ENCODINGS = 2;
  private static final int CLIENT_QEMU = 255;
  private static final int CLIENT_AUDIO_ENABLE = 0;
  private static final int CLIENT_AUDIO_SET_FORMAT = 2;
  private static final int PIXEL_FORMAT_BYTES = 16;
  private static final int COLOUR_BYTES = 6;
  private static final int RECTANGLE_BYTES = 8;
  private static final int SKIP_BUFFER_BYTES = 8192;

  private QemuAudioProtocol() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Reads the version of the server, which must speak RFB 3.8 or later.
   *
   * @param in the stream from the server
   * @throws IOException if the stream ends or the server speaks an older version or no RFB at all
   */
  static void readVersion(final DataInput in) throws IOException {
    final byte[] bytes = new byte[VERSION_BYTES];
    in.readFully(bytes);
    final String version = new String(bytes, StandardCharsets.US_ASCII);
    if (!SERVER_VERSION.matcher(version).matches()) {
      throw new ProtocolException("The VNC server does not speak RFB");
    }
    final int major = Integer.parseInt(version.substring(4, 7));
    final int minor = Integer.parseInt(version.substring(8, 11));
    if (major < 3 || (major == 3 && minor < 8)) {
      throw new ProtocolException("The VNC server speaks RFB " + major + "." + minor + " instead of 3.8");
    }
  }

  /**
   * Answers with RFB 3.8.
   *
   * @param out the stream to the server
   * @throws IOException if the stream fails
   */
  static void writeVersion(final DataOutput out) throws IOException {
    out.write(VERSION.getBytes(StandardCharsets.US_ASCII));
  }

  /**
   * Reads the security types of the server, which must offer to go without authentication, and chooses it.
   *
   * @param in  the stream from the server
   * @param out the stream to the server
   * @throws IOException if the stream ends, the server refuses the connection, or it asks for authentication
   */
  static void negotiateSecurity(final DataInput in, final DataOutput out) throws IOException {
    final int count = in.readUnsignedByte();
    if (count == 0) {
      throw new ProtocolException("The VNC server refused the connection: " + readText(in));
    }
    boolean withoutAuthentication = false;
    for (int index = 0; index < count; index++) {
      // every offered type is read, so the stream stays in step whichever comes first
      final int type = in.readUnsignedByte();
      withoutAuthentication = withoutAuthentication || type == SECURITY_NONE;
    }
    if (!withoutAuthentication) {
      throw new ProtocolException("The VNC server asks for authentication, which the audio connection does not do");
    }
    out.writeByte(SECURITY_NONE);
  }

  /**
   * Reads the result of the security handshake.
   *
   * @param in the stream from the server
   * @throws IOException if the stream ends or the server refused the connection
   */
  static void readSecurityResult(final DataInput in) throws IOException {
    final int result = in.readInt();
    if (result != 0) {
      throw new ProtocolException("The VNC server refused the connection: " + readText(in));
    }
  }

  /**
   * Asks to share the display with the other clients.
   *
   * @param out the stream to the server
   * @throws IOException if the stream fails
   */
  static void writeClientInit(final DataOutput out) throws IOException {
    out.writeByte(1);
  }

  /**
   * Reads the description of the display, which the audio connection does not need.
   *
   * @param in the stream from the server
   * @throws IOException if the stream ends or the name of the display is longer than allowed
   */
  static void readServerInit(final DataInput in) throws IOException {
    skipFully(in, 4 + PIXEL_FORMAT_BYTES);
    readText(in);
  }

  /**
   * Asks for the audio pseudo-encoding, and nothing else.
   *
   * @param out the stream to the server
   * @throws IOException if the stream fails
   */
  static void writeSetEncodings(final DataOutput out) throws IOException {
    out.writeByte(CLIENT_SET_ENCODINGS);
    out.writeByte(0);
    out.writeShort(1);
    out.writeInt(AUDIO_ENCODING);
  }

  /**
   * Sets the format of the samples and enables the audio.
   *
   * @param out        the stream to the server
   * @param channels   the number of channels, 1 or 2
   * @param sampleRate the sample rate in hertz
   * @throws IOException if the stream fails
   */
  static void writeEnableAudio(final DataOutput out, final int channels, final int sampleRate) throws IOException {
    out.writeByte(CLIENT_QEMU);
    out.writeByte(QEMU_AUDIO);
    out.writeShort(CLIENT_AUDIO_SET_FORMAT);
    out.writeByte(FORMAT_S16);
    out.writeByte(channels);
    out.writeInt(sampleRate);
    out.writeByte(CLIENT_QEMU);
    out.writeByte(QEMU_AUDIO);
    out.writeShort(CLIENT_AUDIO_ENABLE);
  }

  /**
   * Reads one message of the server.
   *
   * @param in        the stream from the server
   * @param frameSize the bytes of one frame of samples; the samples of a message are whole frames
   * @param buffers   gives a buffer of at least the asked size for the samples of a message
   * @return what the message said
   * @throws IOException if the stream ends or the message is not one the audio connection expects
   */
  static Message readMessage(final DataInput in, final int frameSize, final IntFunction<byte[]> buffers) throws IOException {
    final int type = in.readUnsignedByte();
    return switch (type) {
      case SERVER_UPDATE -> readUpdate(in);
      case SERVER_COLOUR_MAP -> {
        skipFully(in, 1 + 2);
        final int colours = in.readUnsignedShort();
        skipFully(in, (long) colours * COLOUR_BYTES);
        yield Message.IGNORED;
      }
      case SERVER_BELL -> Message.IGNORED;
      case SERVER_CUT_TEXT -> {
        skipFully(in, 3);
        final long length = Integer.toUnsignedLong(in.readInt());
        if (length > MAX_CUT_TEXT_BYTES) {
          throw new ProtocolException("The VNC server sent " + length + " bytes of clipboard text, more than " + MAX_CUT_TEXT_BYTES);
        }
        skipFully(in, length);
        yield Message.IGNORED;
      }
      case SERVER_QEMU -> readQemu(in, frameSize, buffers);
      default -> throw new ProtocolException("The VNC server sent message type " + type + ", which the audio connection does not expect");
    };
  }

  private static Message readUpdate(final DataInput in) throws IOException {
    skipFully(in, 1);
    final int rectangles = in.readUnsignedShort();
    if (rectangles > MAX_RECTANGLES) {
      throw new ProtocolException("The VNC server announced " + rectangles + " rectangles, more than " + MAX_RECTANGLES);
    }
    for (int index = 0; index < rectangles; index++) {
      skipFully(in, RECTANGLE_BYTES);
      final int encoding = in.readInt();
      if (encoding != AUDIO_ENCODING) {
        throw new ProtocolException(
          "The VNC server sent a rectangle of encoding " + encoding + ", which the audio connection never asks for"
        );
      }
    }
    return rectangles == 0 ? Message.IGNORED : Message.ACKNOWLEDGED;
  }

  private static Message readQemu(final DataInput in, final int frameSize, final IntFunction<byte[]> buffers) throws IOException {
    final int submessage = in.readUnsignedByte();
    if (submessage != QEMU_AUDIO) {
      throw new ProtocolException("The VNC server sent QEMU message " + submessage + " instead of audio");
    }
    final int operation = in.readUnsignedShort();
    return switch (operation) {
      case AUDIO_END -> Message.END;
      case AUDIO_BEGIN -> Message.BEGIN;
      case AUDIO_DATA -> {
        final long length = Integer.toUnsignedLong(in.readInt());
        if (length > MAX_AUDIO_BYTES || length % frameSize != 0) {
          throw new ProtocolException("The VNC server sent " + length + " bytes of audio, not whole frames of at most " + MAX_AUDIO_BYTES);
        }
        final int size = (int) length;
        final byte[] buffer = buffers.apply(size);
        in.readFully(buffer, 0, size);
        yield Message.data(buffer, size);
      }
      default -> throw new ProtocolException("The VNC server sent audio operation " + operation);
    };
  }

  private static String readText(final DataInput in) throws IOException {
    final long length = Integer.toUnsignedLong(in.readInt());
    if (length > MAX_TEXT_BYTES) {
      throw new ProtocolException("The VNC server sent a text of " + length + " bytes, more than " + MAX_TEXT_BYTES);
    }
    final byte[] bytes = new byte[(int) length];
    in.readFully(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  /**
   * Skips bytes, failing like a read when the stream ends before them.
   *
   * @param in    the stream
   * @param count the number of bytes
   * @throws IOException if the stream ends or fails
   */
  private static void skipFully(final DataInput in, final long count) throws IOException {
    final byte[] scratch = new byte[(int) Math.min(count, SKIP_BUFFER_BYTES)];
    long left = count;
    while (left > 0) {
      final int chunk = (int) Math.min(left, scratch.length);
      in.readFully(scratch, 0, chunk);
      left -= chunk;
    }
  }

  /**
   * What one message of the server said.
   */
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

    /**
     * Gets the buffer that holds the samples of a data message, which the reader may reuse for the next message.
     *
     * @return the buffer
     */
    byte[] getSamples() {
      return this.samples;
    }

    /**
     * Gets the number of sample bytes of a data message.
     *
     * @return the length, 0 for every other message
     */
    int getLength() {
      return this.length;
    }
  }

  /**
   * The kinds of messages.
   */
  enum Kind {
    /**
     * A message the audio connection skips.
     */
    IGNORED,
    /**
     * QEMU confirmed that it sends audio.
     */
    ACKNOWLEDGED,
    /**
     * The guest started to play.
     */
    BEGIN,
    /**
     * The guest stopped playing.
     */
    END,
    /**
     * Samples.
     */
    DATA,
  }
}
