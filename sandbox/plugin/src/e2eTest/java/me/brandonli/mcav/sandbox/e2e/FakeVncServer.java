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
package me.brandonli.mcav.sandbox.e2e;

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
    final byte[] random = new byte[4];
    new SecureRandom().nextBytes(random);
    final ServerSocket socket = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
    return new FakeVncServer(socket, "e2e" + HexFormat.of().formatHex(random).substring(0, 5));
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

  private void accept() {
    while (!this.socket.isClosed()) {
      try {
        final Socket client = this.socket.accept();
        Thread.ofPlatform()
          .daemon()
          .name("fake-vnc-client")
          .start(() -> this.serve(client));
      } catch (final IOException closed) {
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
      out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
      out.flush();
      in.readFully(new byte[12]);
      out.write(new byte[] { 1, VNC_AUTHENTICATION });
      out.flush();
      if (in.readUnsignedByte() != VNC_AUTHENTICATION || !this.authenticate(in, out)) {
        return;
      }
      in.readUnsignedByte();
      final PixelFormat format = new PixelFormat();
      writeServerInit(out);
      this.serveMessages(in, out, format);
    } catch (final IOException | GeneralSecurityException disconnected) {}
  }

  private boolean authenticate(final DataInputStream in, final DataOutputStream out) throws IOException, GeneralSecurityException {
    final byte[] challenge = new byte[CHALLENGE];
    new SecureRandom().nextBytes(challenge);
    out.write(challenge);
    out.flush();
    final byte[] response = new byte[CHALLENGE];
    in.readFully(response);
    final boolean correct = MessageDigest.isEqual(response, encrypt(challenge, this.password));
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
  private static byte[] encrypt(final byte[] challenge, final String password) throws GeneralSecurityException {
    final byte[] key = new byte[8];
    final byte[] bytes = password.getBytes(StandardCharsets.ISO_8859_1);
    for (int index = 0; index < Math.min(bytes.length, key.length); index++) {
      key[index] = (byte) (Integer.reverse(bytes[index] & 0xff) >>> 24);
    }
    final Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
    return cipher.doFinal(challenge);
  }

  private static void writeServerInit(final DataOutputStream out) throws IOException {
    out.writeShort(WIDTH);
    out.writeShort(HEIGHT);
    out.write(new byte[] { 32, 24, 0, 1, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255, 16, 8, 0, 0, 0, 0 });
    final byte[] name = "mcav e2e desktop".getBytes(StandardCharsets.US_ASCII);
    out.writeInt(name.length);
    out.write(name);
    out.flush();
  }

  private void serveMessages(final DataInputStream in, final DataOutputStream out, final PixelFormat format) throws IOException {
    while (true) {
      final int type = in.readUnsignedByte();
      switch (type) {
        case 0 -> {
          in.readFully(new byte[3]);
          final byte[] requested = new byte[16];
          in.readFully(requested);
          format.set(requested);
        }
        case 2 -> {
          in.readUnsignedByte();
          in.readFully(new byte[in.readUnsignedShort() * 4]);
        }
        case 3 -> {
          in.readFully(new byte[9]);
          this.writeUpdate(out, format);
        }
        case 4 -> in.readFully(new byte[7]);
        case 5 -> in.readFully(new byte[5]);
        case 6 -> {
          in.readFully(new byte[3]);
          in.readFully(new byte[in.readInt()]);
        }
        default -> throw new IOException("unknown client message " + type);
      }
    }
  }

  private void writeUpdate(final DataOutputStream out, final PixelFormat format) throws IOException {
    final int count = this.updates.incrementAndGet();
    final int rgb = (((count * 37) & 0xff) << 16) | (((count * 91) & 0xff) << 8) | ((count * 13) & 0xff);
    final byte[] pixel = format.encode(rgb);
    final byte[] rect = new byte[WIDTH * HEIGHT * pixel.length];
    for (int at = 0; at < rect.length; at += pixel.length) {
      System.arraycopy(pixel, 0, rect, at, pixel.length);
    }
    out.writeByte(0);
    out.writeByte(0);
    out.writeShort(1);
    out.writeShort(0);
    out.writeShort(0);
    out.writeShort(WIDTH);
    out.writeShort(HEIGHT);
    out.writeInt(0);
    out.write(rect);
    out.flush();
  }

  @Override
  public void close() throws IOException {
    this.socket.close();
    try {
      this.acceptor.join(2_000L);
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /** The pixel format the client asked for: true colour, 8, 16 or 32 bits a pixel, in either byte order. */
  private static final class PixelFormat {

    private int bytes = 4;

    private boolean bigEndian;

    private int[] max = { 255, 255, 255 };

    private int[] shift = { 16, 8, 0 };

    void set(final byte[] format) {
      this.bytes = (format[0] & 0xff) / 8;
      this.bigEndian = format[2] != 0;
      this.max = new int[] { unsigned16(format, 4), unsigned16(format, 6), unsigned16(format, 8) };
      this.shift = new int[] { format[10] & 0xff, format[11] & 0xff, format[12] & 0xff };
    }

    private static int unsigned16(final byte[] bytes, final int at) {
      return ((bytes[at] & 0xff) << 8) | (bytes[at + 1] & 0xff);
    }

    byte[] encode(final int rgb) {
      long value = 0;
      for (int channel = 0; channel < 3; channel++) {
        final int level = (rgb >> (16 - 8 * channel)) & 0xff;
        value |= (long) ((level * this.max[channel]) / 255) << this.shift[channel];
      }
      final byte[] pixel = new byte[this.bytes];
      for (int byteIndex = 0; byteIndex < this.bytes; byteIndex++) {
        final int byteShift = this.bigEndian ? 8 * (this.bytes - 1 - byteIndex) : 8 * byteIndex;
        pixel[byteIndex] = (byte) (value >> byteShift);
      }
      return pixel;
    }
  }
}
