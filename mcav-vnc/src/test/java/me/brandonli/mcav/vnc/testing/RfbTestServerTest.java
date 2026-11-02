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
package me.brandonli.mcav.vnc.testing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// Checks the fixture against RFB 3.8 wire bytes independently of the Vernacular client.
final class RfbTestServerTest {

  private static final byte[] VERSION = "RFB 003.008\n".getBytes(StandardCharsets.US_ASCII);
  private static final int COLOR = 0xFF8040;

  private static final int PADDING_BYTES = 3;

  private static final int BITS_PER_BYTE = 8;

  private static final Map<Integer, PixelFormat> FORMATS = Map.of(
    32,
    new PixelFormat(24, 255, 255, 255, 16, 8),
    16,
    new PixelFormat(16, 31, 63, 31, 11, 5),
    8,
    new PixelFormat(8, 7, 7, 3, 5, 2)
  );

  @ParameterizedTest
  @CsvSource({ "32,true,00ff8040", "32,false,4080ff00", "16,true,fbe7", "16,false,e7fb", "8,true,ec", "8,false,ec" })
  void shouldEncodeRawPixelsInTheNegotiatedByteOrder(final int bits, final boolean bigEndian, final String expectedHexadecimal)
    throws IOException {
    try (final RfbTestServer server = RfbTestServer.start(1, 1); final Socket socket = this.connect(server)) {
      server.setColor(COLOR);
      final DataInputStream input = new DataInputStream(socket.getInputStream());
      final DataOutputStream output = new DataOutputStream(socket.getOutputStream());
      this.handshake(input, output);
      this.setPixelFormat(output, bits, bigEndian);
      final byte[] actual = this.requestPixel(input, output, bits / BITS_PER_BYTE);
      final HexFormat hexadecimal = HexFormat.of();
      final byte[] expected = hexadecimal.parseHex(expectedHexadecimal);
      assertArrayEquals(expected, actual);
    }
  }

  @Test
  void shouldRestoreTheAdvertisedDefaultFormatForTheNextClient() throws IOException {
    try (final RfbTestServer server = RfbTestServer.start(1, 1)) {
      server.setColor(COLOR);
      try (final Socket first = this.connect(server)) {
        final DataInputStream input = new DataInputStream(first.getInputStream());
        final DataOutputStream output = new DataOutputStream(first.getOutputStream());
        this.handshake(input, output);
        this.setPixelFormat(output, 16, false);
        final byte[] pixel = this.requestPixel(input, output, 2);
        final byte[] expected = { (byte) 0xE7, (byte) 0xFB };
        assertArrayEquals(expected, pixel);
      }
      try (final Socket second = this.connect(server)) {
        final DataInputStream input = new DataInputStream(second.getInputStream());
        final DataOutputStream output = new DataOutputStream(second.getOutputStream());
        this.handshake(input, output);
        final byte[] pixel = this.requestPixel(input, output, 4);
        final byte[] expected = { 0, (byte) 0xFF, (byte) 0x80, 0x40 };
        assertArrayEquals(expected, pixel);
      }
    }
  }

  private Socket connect(final RfbTestServer server) throws IOException {
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    final int port = server.getPort();
    final Socket socket = new Socket(loopback, port);
    socket.setSoTimeout(5_000);
    return socket;
  }

  private void handshake(final DataInputStream input, final DataOutputStream output) throws IOException {
    final byte[] version = input.readNBytes(12);
    assertArrayEquals(VERSION, version);
    output.write(VERSION);
    output.flush();
    final int securityCount = input.readUnsignedByte();
    final int securityType = input.readUnsignedByte();
    assertEquals(1, securityCount);
    assertEquals(1, securityType);
    output.writeByte(1);
    output.flush();
    final int securityResult = input.readInt();
    assertEquals(0, securityResult);
    output.writeByte(1);
    output.flush();
    final int width = input.readUnsignedShort();
    final int height = input.readUnsignedShort();
    assertEquals(1, width);
    assertEquals(1, height);
    final byte[] format = input.readNBytes(16);
    assertEquals(32, format[0]);
    assertEquals(1, format[2]);
    final int nameLength = input.readInt();
    input.skipNBytes(nameLength);
  }

  private void setPixelFormat(final DataOutputStream output, final int bits, final boolean bigEndian) throws IOException {
    output.writeByte(0);
    output.write(new byte[PADDING_BYTES]);
    final PixelFormat format = FORMATS.get(bits);
    output.writeByte(bits);
    output.writeByte(format.depth());
    output.writeByte(bigEndian ? 1 : 0);
    output.writeByte(1);
    output.writeShort(format.redMaximum());
    output.writeShort(format.greenMaximum());
    output.writeShort(format.blueMaximum());
    output.writeByte(format.redShift());
    output.writeByte(format.greenShift());
    output.writeByte(0);
    output.write(new byte[PADDING_BYTES]);
    output.flush();
  }

  private byte[] requestPixel(final DataInputStream input, final DataOutputStream output, final int bytes) throws IOException {
    output.writeByte(3);
    output.writeByte(0);
    output.writeShort(0);
    output.writeShort(0);
    output.writeShort(1);
    output.writeShort(1);
    output.flush();
    final int message = input.readUnsignedByte();
    final int padding = input.readUnsignedByte();
    final int rectangles = input.readUnsignedShort();
    final int left = input.readUnsignedShort();
    final int top = input.readUnsignedShort();
    final int width = input.readUnsignedShort();
    final int height = input.readUnsignedShort();
    final int encoding = input.readInt();
    assertEquals(0, message);
    assertEquals(0, padding);
    assertEquals(1, rectangles);
    assertEquals(0, left);
    assertEquals(0, top);
    assertEquals(1, width);
    assertEquals(1, height);
    assertEquals(0, encoding);
    return input.readNBytes(bytes);
  }

  private record PixelFormat(int depth, int redMaximum, int greenMaximum, int blueMaximum, int redShift, int greenShift) {}
}
