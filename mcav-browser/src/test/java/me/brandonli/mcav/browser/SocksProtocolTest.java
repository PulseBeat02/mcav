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
  void shouldRequireAGreetingToOfferToGoWithoutAuthentication() throws IOException {
    final boolean onlyMethodAccepted = SocksProtocol.readGreeting(input(5, 1, 0));
    assertTrue(onlyMethodAccepted);
    final boolean lastMethodAccepted = SocksProtocol.readGreeting(input(5, 3, 2, 1, 0));
    assertTrue(lastMethodAccepted);
    final boolean firstMethodAccepted = SocksProtocol.readGreeting(input(5, 2, 0, 1));
    assertTrue(firstMethodAccepted, "no authentication stays chosen after other methods");
    final boolean authenticationOnlyAccepted = SocksProtocol.readGreeting(input(5, 2, 1, 2));
    assertFalse(authenticationOnlyAccepted);
  }

  @Test
  void shouldRefuseAMalformedGreeting() {
    final ProtocolException version = assertThrows(ProtocolException.class, () -> SocksProtocol.readGreeting(input(4, 1, 0)));
    final String versionMessage = version.getMessage();
    assertEquals("The client speaks SOCKS 4 instead of SOCKS 5", versionMessage);
    final ProtocolException empty = assertThrows(ProtocolException.class, () -> SocksProtocol.readGreeting(input(5, 0)));
    final String emptyMessage = empty.getMessage();
    assertEquals("The greeting offers no method", emptyMessage);
    assertThrows(EOFException.class, () -> SocksProtocol.readGreeting(input(5, 2, 0)));
  }

  @Test
  void shouldReadAHostNameOrAnAddressFromAConnectionRequest() throws IOException {
    final SocksProtocol.Request named = request(domainRequest(SocksProtocol.CONNECT, "Example-1.test_x", 443));
    final String namedHost = named.getHost();
    assertEquals("Example-1.test_x", namedHost);
    final int namedPort = named.getPort();
    assertEquals(443, namedPort);
    final SocksProtocol.Request ipv4 = SocksProtocol.readRequest(input(5, 1, 0, 1, 93, 184, 216, 34, 0, 80));
    final String ipv4Host = ipv4.getHost();
    assertEquals("93.184.216.34", ipv4Host);
    final int ipv4Port = ipv4.getPort();
    assertEquals(80, ipv4Port);
    final SocksProtocol.Request ipv6 = SocksProtocol.readRequest(
      input(5, 1, 0, 4, 0x26, 0x06, 0x47, 0, 0x47, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x11, 0x11, 0xFF, 0xFF)
    );
    final String ipv6Host = ipv6.getHost();
    assertEquals("2606:4700:4700:0:0:0:0:1111", ipv6Host);
    final int ipv6Port = ipv6.getPort();
    assertEquals(65_535, ipv6Port);
  }

  @Test
  void shouldRefuseRequestsTheGuardDoesNotServeWithTheirReplyCode() {
    final SocksProtocol.Refusal bind = assertThrows(SocksProtocol.Refusal.class, () -> request(domainRequest(2, "a.test", 80)));
    final int bindReply = bind.getReply();
    assertEquals(SocksProtocol.COMMAND_NOT_SUPPORTED, bindReply);
    final String bindMessage = bind.getMessage();
    assertEquals("The request asks for command 2 instead of a connection", bindMessage);
    final SocksProtocol.Refusal portZero = assertThrows(SocksProtocol.Refusal.class, () ->
      request(domainRequest(SocksProtocol.CONNECT, "a.test", 0))
    );
    final int portZeroReply = portZero.getReply();
    assertEquals(SocksProtocol.NOT_ALLOWED, portZeroReply);
    final SocksProtocol.Refusal type = assertThrows(SocksProtocol.Refusal.class, () -> SocksProtocol.readRequest(input(5, 1, 0, 9, 1, 2)));
    final int typeReply = type.getReply();
    assertEquals(SocksProtocol.ADDRESS_NOT_SUPPORTED, typeReply);
    final String typeMessage = type.getMessage();
    assertEquals("The request names an address of type 9", typeMessage);
  }

  @Test
  void shouldRefuseAMalformedRequest() {
    assertThrows(ProtocolException.class, () -> SocksProtocol.readRequest(input(4, 1, 0, 1)));
    final ProtocolException reserved = assertThrows(ProtocolException.class, () -> SocksProtocol.readRequest(input(5, 1, 7, 1)));
    final String reservedMessage = reserved.getMessage();
    assertEquals("The reserved byte of the request is 7", reservedMessage);
    final ProtocolException empty = assertThrows(ProtocolException.class, () -> SocksProtocol.readRequest(input(5, 1, 0, 3, 0)));
    final String emptyMessage = empty.getMessage();
    assertEquals("The request names an empty host", emptyMessage);
    final ProtocolException character = assertThrows(ProtocolException.class, () ->
      request(domainRequest(SocksProtocol.CONNECT, "a b", 80))
    );
    final String characterMessage = character.getMessage();
    assertEquals("The host name of the request holds the byte 32", characterMessage);
    assertThrows(EOFException.class, () -> SocksProtocol.readRequest(input(5, 1, 0, 3, 5, 'a')));
  }

  @Test
  void shouldOnlyAllowLettersDigitsHyphensDotsAndUnderscoresInHostNames() {
    for (final char allowed : "azAZ09-._".toCharArray()) {
      final boolean allowedAccepted = SocksProtocol.isHostNameCharacter((byte) allowed);
      assertTrue(allowedAccepted, String.valueOf(allowed));
    }
    for (final char refused : "`{@[/:%\u0000 \u007f".toCharArray()) {
      final boolean refusedAccepted = SocksProtocol.isHostNameCharacter((byte) refused);
      assertFalse(refusedAccepted, String.valueOf((int) refused));
    }
    final boolean nonAsciiAccepted = SocksProtocol.isHostNameCharacter((byte) 0xC3);
    assertFalse(nonAsciiAccepted);
  }

  @Test
  void shouldNotInstantiateTheProtocol() {
    UtilityClassAssertions.assertNotInstantiable(SocksProtocol.class);
  }

  @Test
  void shouldReadAnIpv6AddressNamedAsAHostAsTheAddress() throws IOException {
    final String compressedIpv6Host = request(domainRequest(1, "2606:4700:4700::1111", 80)).getHost();
    assertEquals("2606:4700:4700:0:0:0:0:1111", compressedIpv6Host);
    final String bracketedIpv6Host = request(domainRequest(1, "[2606:4700:4700::1111]", 80)).getHost();
    assertEquals("2606:4700:4700:0:0:0:0:1111", bracketedIpv6Host);
    final String loopbackHost = request(domainRequest(1, "::1", 80)).getHost();
    assertEquals("0:0:0:0:0:0:0:1", loopbackHost);
    final String mappedIpv4Host = request(domainRequest(1, "::ffff:10.0.0.1", 80)).getHost();
    assertEquals("10.0.0.1", mappedIpv4Host);
    for (final String bad : new String[] { "2606::zz::1", "1.2.3.4:80", "[::1", "exa[mple]", "exam]ple", "fe80::1%eth0" }) {
      assertThrows(ProtocolException.class, () -> request(domainRequest(1, bad, 80)), bad);
    }
  }

  @Test
  void shouldGiveAnswersTheFormOfTheProtocol() throws IOException {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final DataOutputStream out = new DataOutputStream(bytes);
    SocksProtocol.writeMethod(out, SocksProtocol.NO_ACCEPTABLE_METHOD);
    SocksProtocol.writeReply(out, SocksProtocol.NOT_ALLOWED);
    final byte[] writtenAnswers = bytes.toByteArray();
    assertArrayEquals(new byte[] { 5, (byte) 0xFF, 5, 2, 0, 1, 0, 0, 0, 0, 0, 0 }, writtenAnswers);
  }

  @Test
  void shouldRefuseAHostNameThatStartsWithABracket() {
    for (final String host : new String[] { "[example.com", "]example.com", "example.com]" }) {
      final ProtocolException failure = assertThrows(ProtocolException.class, () ->
        request(domainRequest(SocksProtocol.CONNECT, host, 443))
      );
      final String message = failure.getMessage();
      assertEquals("The host name of the request holds a bracket: " + host, message);
    }
  }
}
