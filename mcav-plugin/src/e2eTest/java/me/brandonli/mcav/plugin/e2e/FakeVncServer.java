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
package me.brandonli.mcav.plugin.e2e;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * A VNC server of the test on the loopback address: RFB 3.8 with VNC authentication, which the plugin's allow-list
 * holds the password of, and a picture whose colour changes with every update it sends, raw, in the pixel format the
 * client asks for. It counts the clients that logged in and the updates it sent.
 */
final class FakeVncServer implements AutoCloseable {

  private static final int WIDTH = 320;

  private static final int HEIGHT = 240;

  private static final int VNC_AUTHENTICATION = 2;

  private static final int CHALLENGE = 16;

  private static final String PASSWORD_PREFIX = "e2e";
  private static final int PASSWORD_RANDOM_BYTES = 4;
  private static final int PASSWORD_RANDOM_DIGITS = 5;

  private static final int BACKLOG = 4;

  private static final String PROTOCOL_VERSION = "RFB 003.008\n";
  private static final int PROTOCOL_VERSION_LENGTH = 12;

  private static final int DES_KEY_LENGTH = 8;
  private static final int MIRRORED_BYTE_SHIFT = 24;
  private static final String CIPHER_TRANSFORMATION = "DES/ECB/NoPadding";
  private static final String CIPHER_ALGORITHM = "DES";

  private static final byte[] SERVER_PIXEL_FORMAT = { 32, 24, 0, 1, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255, 16, 8, 0, 0, 0, 0 };

  private static final int SET_PIXEL_FORMAT = 0;
  private static final int SET_ENCODINGS = 2;
  private static final int FRAMEBUFFER_UPDATE_REQUEST = 3;
  private static final int KEY_EVENT = 4;
  private static final int POINTER_EVENT = 5;
  private static final int CLIENT_CUT_TEXT = 6;

  private static final int PIXEL_FORMAT_PADDING = 3;
  private static final int PIXEL_FORMAT_LENGTH = 16;
  private static final int ENCODING_LENGTH = 4;
  private static final int UPDATE_REQUEST_LENGTH = 9;
  private static final int KEY_EVENT_LENGTH = 7;
  private static final int POINTER_EVENT_LENGTH = 5;
  private static final int CUT_TEXT_PADDING = 3;

  private static final int RED_STEP = 37;
  private static final int GREEN_STEP = 91;
  private static final int BLUE_STEP = 13;

  private static final int RED_SHIFT = 16;
  private static final int GREEN_SHIFT = 8;
  private static final int BLUE_SHIFT = 0;
  private static final int CHANNELS = 3;
  private static final int CHANNEL_MAXIMUM = 255;

  private static final int BYTE_MASK = 0xff;
  private static final int BITS_PER_BYTE = 8;

  private static final int BIG_ENDIAN_INDEX = 2;
  private static final int RED_MAXIMUM_INDEX = 4;
  private static final int GREEN_MAXIMUM_INDEX = 6;
  private static final int BLUE_MAXIMUM_INDEX = 8;
  private static final int RED_SHIFT_INDEX = 10;
  private static final int GREEN_SHIFT_INDEX = 11;
  private static final int BLUE_SHIFT_INDEX = 12;
  private static final int DEFAULT_BYTES_PER_PIXEL = 4;

  private static final long CLOSE_TIMEOUT_MILLISECONDS = 2_000L;

  private final ServerSocket socket;

  private final String password;

  private final Thread acceptor;

  private final AtomicInteger logins = new AtomicInteger();

  private final AtomicInteger refused = new AtomicInteger();

  private final AtomicInteger updates = new AtomicInteger();

  private FakeVncServer(final ServerSocket socket, final String password) {
    this.socket = socket;
    this.password = password;
    this.acceptor = Thread.ofPlatform().daemon().name("fake-vnc").start(this::accept);
  }

  /**
   * Starts a server with a new random password on a free loopback port.
   *
   * @return the server
   * @throws IOException if no port can be opened
   */
  static FakeVncServer start() throws IOException {
    final byte[] random = new byte[PASSWORD_RANDOM_BYTES];
    new SecureRandom().nextBytes(random);
    final ServerSocket socket = new ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress());
    return new FakeVncServer(socket, PASSWORD_PREFIX + HexFormat.of().formatHex(random).substring(0, PASSWORD_RANDOM_DIGITS));
  }

  @Override
  public void close() throws IOException {
    this.socket.close();
    try {
      this.acceptor.join(CLOSE_TIMEOUT_MILLISECONDS);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private void accept() {
    while (!this.socket.isClosed()) {
      try {
        final Socket client = this.socket.accept();
        Thread.ofPlatform()
          .daemon()
          .name("fake-vnc-client")
          .start(() -> this.serve(client));
      } catch (final IOException exception) {
        return;
      }
    }
  }

  private void serve(final Socket client) {
    try (
      client;
      final DataInputStream in = new DataInputStream(client.getInputStream());
      final DataOutputStream out = new DataOutputStream(client.getOutputStream())
    ) {
      out.write(PROTOCOL_VERSION.getBytes(StandardCharsets.US_ASCII));
      out.flush();
      in.readFully(new byte[PROTOCOL_VERSION_LENGTH]);
      out.write(new byte[] { 1, VNC_AUTHENTICATION });
      out.flush();
      if (in.readUnsignedByte() != VNC_AUTHENTICATION || !this.authenticate(in, out)) {
        return;
      }
      in.readUnsignedByte();
      final PixelFormat format = new PixelFormat();
      this.writeServerInit(out);
      this.serveMessages(in, out, format);
    } catch (final IOException | GeneralSecurityException exception) {
      // the client left, or sent what this server does not speak
    }
  }

  private boolean authenticate(final DataInputStream in, final DataOutputStream out) throws IOException, GeneralSecurityException {
    final byte[] challenge = new byte[CHALLENGE];
    new SecureRandom().nextBytes(challenge);
    out.write(challenge);
    out.flush();
    final byte[] response = new byte[CHALLENGE];
    in.readFully(response);
    final boolean correct = MessageDigest.isEqual(response, this.encrypt(challenge, this.password));
    out.writeInt(correct ? 0 : 1);
    if (!correct) {
      final byte[] reason = "wrong password".getBytes(StandardCharsets.US_ASCII);
      out.writeInt(reason.length);
      out.write(reason);
      this.refused.incrementAndGet();
    } else {
      this.logins.incrementAndGet();
    }
    out.flush();
    return correct;
  }

  /** VNC authentication encrypts the challenge with DES, its key the password with the bits of every byte reversed. */
  private byte[] encrypt(final byte[] challenge, final String password) throws GeneralSecurityException {
    final byte[] key = new byte[DES_KEY_LENGTH];
    final byte[] bytes = password.getBytes(StandardCharsets.ISO_8859_1);
    for (int index = 0; index < Math.min(bytes.length, key.length); index++) {
      key[index] = (byte) (Integer.reverse(bytes[index] & BYTE_MASK) >>> MIRRORED_BYTE_SHIFT);
    }
    final Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, CIPHER_ALGORITHM));
    return cipher.doFinal(challenge);
  }

  private void writeServerInit(final DataOutputStream out) throws IOException {
    out.writeShort(WIDTH);
    out.writeShort(HEIGHT);
    out.write(SERVER_PIXEL_FORMAT);
    final byte[] name = "mcav e2e desktop".getBytes(StandardCharsets.US_ASCII);
    out.writeInt(name.length);
    out.write(name);
    out.flush();
  }

  private void serveMessages(final DataInputStream in, final DataOutputStream out, final PixelFormat format) throws IOException {
    while (true) {
      final int type = in.readUnsignedByte();
      switch (type) {
        case SET_PIXEL_FORMAT -> {
          in.readFully(new byte[PIXEL_FORMAT_PADDING]);
          final byte[] requested = new byte[PIXEL_FORMAT_LENGTH];
          in.readFully(requested);
          format.set(requested);
        }
        case SET_ENCODINGS -> {
          in.readUnsignedByte();
          in.readFully(new byte[in.readUnsignedShort() * ENCODING_LENGTH]);
        }
        case FRAMEBUFFER_UPDATE_REQUEST -> {
          in.readFully(new byte[UPDATE_REQUEST_LENGTH]);
          this.writeUpdate(out, format);
        }
        case KEY_EVENT -> in.readFully(new byte[KEY_EVENT_LENGTH]);
        case POINTER_EVENT -> in.readFully(new byte[POINTER_EVENT_LENGTH]);
        case CLIENT_CUT_TEXT -> {
          in.readFully(new byte[CUT_TEXT_PADDING]);
          in.readFully(new byte[in.readInt()]);
        }
        default -> {
          final String message = "Client message type %s is unknown!".formatted(type);
          throw new IOException(message);
        }
      }
    }
  }

  private void writeUpdate(final DataOutputStream out, final PixelFormat format) throws IOException {
    final int count = this.updates.incrementAndGet();
    final int rgb =
      (((count * RED_STEP) & BYTE_MASK) << RED_SHIFT) |
      (((count * GREEN_STEP) & BYTE_MASK) << GREEN_SHIFT) |
      ((count * BLUE_STEP) & BYTE_MASK);
    final byte[] pixel = format.encode(rgb);
    final byte[] rectangle = new byte[WIDTH * HEIGHT * pixel.length];
    for (int at = 0; at < rectangle.length; at += pixel.length) {
      System.arraycopy(pixel, 0, rectangle, at, pixel.length);
    }
    out.writeByte(0);
    out.writeByte(0);
    out.writeShort(1);
    out.writeShort(0);
    out.writeShort(0);
    out.writeShort(WIDTH);
    out.writeShort(HEIGHT);
    out.writeInt(0);
    out.write(rectangle);
    out.flush();
  }

  int getPort() {
    return this.socket.getLocalPort();
  }

  String getPassword() {
    return this.password;
  }

  int getLogins() {
    return this.logins.get();
  }

  int getRefused() {
    return this.refused.get();
  }

  int getUpdates() {
    return this.updates.get();
  }

  /** The pixel format the client asked for: true colour, 8, 16 or 32 bits a pixel, in either byte order. */
  private static final class PixelFormat {

    private int bytes = DEFAULT_BYTES_PER_PIXEL;

    private boolean bigEndian;

    private int[] maximum = { CHANNEL_MAXIMUM, CHANNEL_MAXIMUM, CHANNEL_MAXIMUM };

    private int[] shift = { RED_SHIFT, GREEN_SHIFT, BLUE_SHIFT };

    void set(final byte[] format) {
      this.bytes = (format[0] & BYTE_MASK) / BITS_PER_BYTE;
      this.bigEndian = format[BIG_ENDIAN_INDEX] != 0;
      this.maximum = new int[] {
        this.unsigned16(format, RED_MAXIMUM_INDEX),
        this.unsigned16(format, GREEN_MAXIMUM_INDEX),
        this.unsigned16(format, BLUE_MAXIMUM_INDEX),
      };
      this.shift = new int[] {
        format[RED_SHIFT_INDEX] & BYTE_MASK,
        format[GREEN_SHIFT_INDEX] & BYTE_MASK,
        format[BLUE_SHIFT_INDEX] & BYTE_MASK,
      };
    }

    private int unsigned16(final byte[] bytes, final int at) {
      return ((bytes[at] & BYTE_MASK) << BITS_PER_BYTE) | (bytes[at + 1] & BYTE_MASK);
    }

    byte[] encode(final int rgb) {
      long value = 0;
      for (int channel = 0; channel < CHANNELS; channel++) {
        final int level = (rgb >> (RED_SHIFT - BITS_PER_BYTE * channel)) & BYTE_MASK;
        value |= (long) ((level * this.maximum[channel]) / CHANNEL_MAXIMUM) << this.shift[channel];
      }
      final byte[] pixel = new byte[this.bytes];
      for (int byteIndex = 0; byteIndex < this.bytes; byteIndex++) {
        final int byteShift = this.bigEndian ? BITS_PER_BYTE * (this.bytes - 1 - byteIndex) : BITS_PER_BYTE * byteIndex;
        pixel[byteIndex] = (byte) (value >> byteShift);
      }
      return pixel;
    }
  }
}
