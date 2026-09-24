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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Checks the fixture against RFB 3.8 wire bytes independently of the Vernacular client. */
final class RfbTestServerTest {

  private static final byte[] VERSION = "RFB 003.008\n".getBytes(StandardCharsets.US_ASCII);
  private static final int COLOR = 0xFF8040;

  @ParameterizedTest
  @CsvSource({ "32,true,00ff8040", "32,false,4080ff00", "16,true,fbe7", "16,false,e7fb", "8,true,ec", "8,false,ec" })
  void encodesRawPixelsInTheNegotiatedByteOrder(final int bits, final boolean bigEndian, final String expectedHex) throws IOException {
    try (final RfbTestServer server = RfbTestServer.start(1, 1); final Socket socket = connect(server)) {
      server.setColor(COLOR);
      final DataInputStream input = new DataInputStream(socket.getInputStream());
      final DataOutputStream output = new DataOutputStream(socket.getOutputStream());
      handshake(input, output);
      setPixelFormat(output, bits, bigEndian);
      final byte[] actual = requestPixel(input, output, bits / 8);
      final HexFormat hex = HexFormat.of();
      final byte[] expected = hex.parseHex(expectedHex);
      assertArrayEquals(expected, actual);
    }
  }

  @Test
  void restoresTheAdvertisedDefaultFormatForTheNextClient() throws IOException {
    try (final RfbTestServer server = RfbTestServer.start(1, 1)) {
      server.setColor(COLOR);
      try (final Socket first = connect(server)) {
        final DataInputStream input = new DataInputStream(first.getInputStream());
        final DataOutputStream output = new DataOutputStream(first.getOutputStream());
        handshake(input, output);
        setPixelFormat(output, 16, false);
        final byte[] pixel = requestPixel(input, output, 2);
        assertArrayEquals(new byte[] { (byte) 0xE7, (byte) 0xFB }, pixel);
      }
      try (final Socket second = connect(server)) {
        final DataInputStream input = new DataInputStream(second.getInputStream());
        final DataOutputStream output = new DataOutputStream(second.getOutputStream());
        handshake(input, output);
        final byte[] pixel = requestPixel(input, output, 4);
        assertArrayEquals(new byte[] { 0, (byte) 0xFF, (byte) 0x80, 0x40 }, pixel);
      }
    }
  }

  private static Socket connect(final RfbTestServer server) throws IOException {
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    final int port = server.getPort();
    final Socket socket = new Socket(loopback, port);
    socket.setSoTimeout(5_000);
    return socket;
  }

  private static void handshake(final DataInputStream input, final DataOutputStream output) throws IOException {
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

  private static void setPixelFormat(final DataOutputStream output, final int bits, final boolean bigEndian) throws IOException {
    output.writeByte(0);
    output.write(new byte[3]);
    output.writeByte(bits);
    output.writeByte(bits == 32 ? 24 : bits);
    output.writeByte(bigEndian ? 1 : 0);
    output.writeByte(1);
    output.writeShort(bits == 32 ? 255 : bits == 16 ? 31 : 7);
    output.writeShort(bits == 32 ? 255 : bits == 16 ? 63 : 7);
    output.writeShort(bits == 32 ? 255 : bits == 16 ? 31 : 3);
    output.writeByte(bits == 32 ? 16 : bits == 16 ? 11 : 5);
    output.writeByte(bits == 32 ? 8 : bits == 16 ? 5 : 2);
    output.writeByte(0);
    output.write(new byte[3]);
    output.flush();
  }

  private static byte[] requestPixel(final DataInputStream input, final DataOutputStream output, final int bytes) throws IOException {
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
    final int x = input.readUnsignedShort();
    final int y = input.readUnsignedShort();
    final int width = input.readUnsignedShort();
    final int height = input.readUnsignedShort();
    final int encoding = input.readInt();
    assertEquals(0, message);
    assertEquals(0, padding);
    assertEquals(1, rectangles);
    assertEquals(0, x);
    assertEquals(0, y);
    assertEquals(1, width);
    assertEquals(1, height);
    assertEquals(0, encoding);
    return input.readNBytes(bytes);
  }
}
