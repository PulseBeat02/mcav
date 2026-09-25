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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import me.brandonli.mcav.browser.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

class SocksProtocolTest {

  private static DataInputStream input(final int... bytes) {
    final byte[] data = new byte[bytes.length];
    for (int index = 0; index < bytes.length; index++) {
      data[index] = (byte) bytes[index];
    }
    return new DataInputStream(new ByteArrayInputStream(data));
  }

  /**
   * Builds a request to a host name.
   *
   * @param command the command
   * @param host    the host name
   * @param port    the port
   * @return the bytes
   */
  static byte[] domainRequest(final int command, final String host, final int port) {
    final byte[] name = host.getBytes(StandardCharsets.ISO_8859_1);
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    bytes.write(SocksProtocol.VERSION);
    bytes.write(command);
    bytes.write(0);
    bytes.write(SocksProtocol.ADDRESS_DOMAIN);
    bytes.write(name.length);
    bytes.writeBytes(name);
    bytes.write(port >> 8);
    bytes.write(port & 0xFF);
    return bytes.toByteArray();
  }

  private static SocksProtocol.Request request(final byte[] bytes) throws IOException {
    return SocksProtocol.readRequest(new DataInputStream(new ByteArrayInputStream(bytes)));
  }

  @Test
  void aGreetingMustOfferToGoWithoutAuthentication() throws IOException {
    assertTrue(SocksProtocol.readGreeting(input(5, 1, 0)));
    assertTrue(SocksProtocol.readGreeting(input(5, 3, 2, 1, 0)));
    assertTrue(SocksProtocol.readGreeting(input(5, 2, 0, 1)), "no authentication stays chosen after other methods");
    assertFalse(SocksProtocol.readGreeting(input(5, 2, 1, 2)));
  }

  @Test
  void aMalformedGreetingIsRefused() {
    final ProtocolException version = assertThrows(ProtocolException.class, () -> SocksProtocol.readGreeting(input(4, 1, 0)));
    assertEquals("The client speaks SOCKS 4 instead of SOCKS 5", version.getMessage());
    final ProtocolException empty = assertThrows(ProtocolException.class, () -> SocksProtocol.readGreeting(input(5, 0)));
    assertEquals("The greeting offers no method", empty.getMessage());
    assertThrows(EOFException.class, () -> SocksProtocol.readGreeting(input(5, 2, 0)));
  }

  @Test
  void aConnectionRequestNamesAHostNameOrAnAddress() throws IOException {
    final SocksProtocol.Request named = request(domainRequest(SocksProtocol.CONNECT, "Example-1.test_x", 443));
    assertEquals("Example-1.test_x", named.getHost());
    assertEquals(443, named.getPort());
    final SocksProtocol.Request ipv4 = SocksProtocol.readRequest(input(5, 1, 0, 1, 93, 184, 216, 34, 0, 80));
    assertEquals("93.184.216.34", ipv4.getHost());
    assertEquals(80, ipv4.getPort());
    final SocksProtocol.Request ipv6 = SocksProtocol.readRequest(
      input(5, 1, 0, 4, 0x26, 0x06, 0x47, 0, 0x47, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x11, 0x11, 0xFF, 0xFF)
    );
    assertEquals("2606:4700:4700:0:0:0:0:1111", ipv6.getHost());
    assertEquals(65_535, ipv6.getPort());
  }

  @Test
  void requestsTheGuardDoesNotServeAreRefusedWithTheirReplyCode() {
    final SocksProtocol.Refusal bind = assertThrows(SocksProtocol.Refusal.class, () -> request(domainRequest(2, "a.test", 80)));
    assertEquals(SocksProtocol.COMMAND_NOT_SUPPORTED, bind.getReply());
    assertEquals("The request asks for command 2 instead of a connection", bind.getMessage());
    final SocksProtocol.Refusal portZero = assertThrows(SocksProtocol.Refusal.class, () ->
      request(domainRequest(SocksProtocol.CONNECT, "a.test", 0))
    );
    assertEquals(SocksProtocol.NOT_ALLOWED, portZero.getReply());
    final SocksProtocol.Refusal type = assertThrows(SocksProtocol.Refusal.class, () -> SocksProtocol.readRequest(input(5, 1, 0, 9, 1, 2)));
    assertEquals(SocksProtocol.ADDRESS_NOT_SUPPORTED, type.getReply());
    assertEquals("The request names an address of type 9", type.getMessage());
  }

  @Test
  void aMalformedRequestIsRefused() {
    assertThrows(ProtocolException.class, () -> SocksProtocol.readRequest(input(4, 1, 0, 1)));
    final ProtocolException reserved = assertThrows(ProtocolException.class, () -> SocksProtocol.readRequest(input(5, 1, 7, 1)));
    assertEquals("The reserved byte of the request is 7", reserved.getMessage());
    final ProtocolException empty = assertThrows(ProtocolException.class, () -> SocksProtocol.readRequest(input(5, 1, 0, 3, 0)));
    assertEquals("The request names an empty host", empty.getMessage());
    final ProtocolException character = assertThrows(ProtocolException.class, () -> request(domainRequest(SocksProtocol.CONNECT, "a b", 80))
    );
    assertEquals("The host name of the request holds the byte 32", character.getMessage());
    assertThrows(EOFException.class, () -> SocksProtocol.readRequest(input(5, 1, 0, 3, 5, 'a')));
  }

  @Test
  void hostNamesHoldLettersDigitsHyphensDotsAndUnderscoresOnly() {
    for (final char allowed : "azAZ09-._".toCharArray()) {
      assertTrue(SocksProtocol.isHostNameCharacter((byte) allowed), String.valueOf(allowed));
    }
    for (final char refused : "`{@[/:%\u0000 \u007f".toCharArray()) {
      assertFalse(SocksProtocol.isHostNameCharacter((byte) refused), String.valueOf((int) refused));
    }
    assertFalse(SocksProtocol.isHostNameCharacter((byte) 0xC3));
  }

  @Test
  void theProtocolIsNotInstantiable() {
    UtilityClassAssertions.assertNotInstantiable(SocksProtocol.class);
  }

  @Test
  void answersHaveTheFormOfTheProtocol() throws IOException {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final DataOutputStream out = new DataOutputStream(bytes);
    SocksProtocol.writeMethod(out, SocksProtocol.NO_ACCEPTABLE_METHOD);
    SocksProtocol.writeReply(out, SocksProtocol.NOT_ALLOWED);
    assertArrayEquals(new byte[] { 5, (byte) 0xFF, 5, 2, 0, 1, 0, 0, 0, 0, 0, 0 }, bytes.toByteArray());
  }
}
