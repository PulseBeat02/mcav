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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import me.brandonli.mcav.browser.testing.EqualityAssertions;
import me.brandonli.mcav.browser.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;

class SocksProtocolTest {

  private static final int CHANNEL_MASK = 0xFF;

  private static final int HTTPS_PORT = 443;

  private static final int HTTP_PORT = 80;

  private static final String EXPANDED_IPV6_HOST = "2606:4700:4700:0:0:0:0:1111";

  private static final String HOST = "example.com";

  private DataInputStream input(final int... bytes) {
    final byte[] data = new byte[bytes.length];
    for (int index = 0; index < bytes.length; index++) {
      data[index] = (byte) bytes[index];
    }

    return new DataInputStream(new ByteArrayInputStream(data));
  }

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
    bytes.write(port & CHANNEL_MASK);

    return bytes.toByteArray();
  }

  private SocksProtocol.Request request(final byte[] bytes) throws IOException {
    return SocksProtocol.readRequest(new DataInputStream(new ByteArrayInputStream(bytes)));
  }

  @Test
  void shouldRequireAGreetingToOfferToGoWithoutAuthentication() throws IOException {
    final boolean onlyMethodAccepted = SocksProtocol.readOffersNoAuthentication(this.input(5, 1, 0));
    assertTrue(onlyMethodAccepted);

    final boolean lastMethodAccepted = SocksProtocol.readOffersNoAuthentication(this.input(5, 3, 2, 1, 0));
    assertTrue(lastMethodAccepted);

    final boolean firstMethodAccepted = SocksProtocol.readOffersNoAuthentication(this.input(5, 2, 0, 1));
    assertTrue(firstMethodAccepted, "no authentication stays chosen after other methods");

    final boolean authenticationOnlyAccepted = SocksProtocol.readOffersNoAuthentication(this.input(5, 2, 1, 2));
    assertFalse(authenticationOnlyAccepted);
  }

  @Test
  void shouldRefuseAMalformedGreeting() {
    final ProtocolException version = assertThrows(ProtocolException.class, () ->
      SocksProtocol.readOffersNoAuthentication(this.input(4, 1, 0))
    );
    final String versionMessage = version.getMessage();
    assertEquals("SOCKS version 4 of the client is not SOCKS 5!", versionMessage);

    final ProtocolException empty = assertThrows(ProtocolException.class, () -> SocksProtocol.readOffersNoAuthentication(this.input(5, 0)));
    final String emptyMessage = empty.getMessage();
    assertEquals("The greeting offers no method!", emptyMessage);
    assertThrows(EOFException.class, () -> SocksProtocol.readOffersNoAuthentication(this.input(5, 2, 0)));
  }

  @Test
  void shouldReadAHostNameOrAnAddressFromAConnectionRequest() throws IOException {
    final SocksProtocol.Request named = this.request(domainRequest(SocksProtocol.CONNECT, "Example-1.test_x", HTTPS_PORT));
    final String namedHost = named.getHost();
    assertEquals("Example-1.test_x", namedHost);

    final int namedPort = named.getPort();
    assertEquals(HTTPS_PORT, namedPort);

    final SocksProtocol.Request ipv4 = SocksProtocol.readRequest(this.input(5, 1, 0, 1, 93, 184, 216, 34, 0, HTTP_PORT));
    final String ipv4Host = ipv4.getHost();
    assertEquals("93.184.216.34", ipv4Host);

    final int ipv4Port = ipv4.getPort();
    assertEquals(HTTP_PORT, ipv4Port);

    final SocksProtocol.Request ipv6 = SocksProtocol.readRequest(
      this.input(5, 1, 0, 4, 0x26, 0x06, 0x47, 0, 0x47, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x11, 0x11, CHANNEL_MASK, CHANNEL_MASK)
    );
    final String ipv6Host = ipv6.getHost();
    assertEquals(EXPANDED_IPV6_HOST, ipv6Host);

    final int ipv6Port = ipv6.getPort();
    assertEquals(65_535, ipv6Port);
  }

  @Test
  void shouldRefuseRequestsTheGuardDoesNotServeWithTheirReplyCode() {
    final SocksProtocol.Refusal bind = assertThrows(SocksProtocol.Refusal.class, () -> this.request(domainRequest(2, "a.test", HTTP_PORT)));
    final int bindReply = bind.getReply();
    assertEquals(SocksProtocol.COMMAND_NOT_SUPPORTED, bindReply);

    final String bindMessage = bind.getMessage();
    assertEquals("Command 2 of the request is not a connection!", bindMessage);

    final SocksProtocol.Refusal portZero = assertThrows(SocksProtocol.Refusal.class, () -> this.requestAConnection("a.test", 0));
    final int portZeroReply = portZero.getReply();
    assertEquals(SocksProtocol.NOT_ALLOWED, portZeroReply);

    final SocksProtocol.Refusal type = assertThrows(SocksProtocol.Refusal.class, () ->
      SocksProtocol.readRequest(this.input(5, 1, 0, 9, 1, 2))
    );
    final int typeReply = type.getReply();
    assertEquals(SocksProtocol.ADDRESS_NOT_SUPPORTED, typeReply);

    final String typeMessage = type.getMessage();
    assertEquals("Address type 9 of the request is not supported!", typeMessage);
  }

  private SocksProtocol.Request requestAConnection(final String host, final int port) throws IOException {
    return this.request(domainRequest(SocksProtocol.CONNECT, host, port));
  }

  @Test
  void shouldRefuseAMalformedRequest() {
    assertThrows(ProtocolException.class, () -> SocksProtocol.readRequest(this.input(4, 1, 0, 1)));

    final ProtocolException reserved = assertThrows(ProtocolException.class, () -> SocksProtocol.readRequest(this.input(5, 1, 7, 1)));
    final String reservedMessage = reserved.getMessage();
    assertEquals("Reserved byte 7 of the request must be 0!", reservedMessage);

    final ProtocolException empty = assertThrows(ProtocolException.class, () -> SocksProtocol.readRequest(this.input(5, 1, 0, 3, 0)));
    final String emptyMessage = empty.getMessage();
    assertEquals("The request names an empty host!", emptyMessage);

    final ProtocolException character = assertThrows(ProtocolException.class, () -> this.requestAConnection("a b", HTTP_PORT));
    final String characterMessage = character.getMessage();
    assertEquals("Byte 32 is not allowed in the host name of the request!", characterMessage);
    assertThrows(EOFException.class, () -> SocksProtocol.readRequest(this.input(5, 1, 0, 3, 5, 'a')));
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
    final String compressedIpv6Host = this.request(domainRequest(1, "2606:4700:4700::1111", HTTP_PORT)).getHost();
    assertEquals(EXPANDED_IPV6_HOST, compressedIpv6Host);

    final String bracketedIpv6Host = this.request(domainRequest(1, "[2606:4700:4700::1111]", HTTP_PORT)).getHost();
    assertEquals(EXPANDED_IPV6_HOST, bracketedIpv6Host);

    final String loopbackHost = this.request(domainRequest(1, "::1", HTTP_PORT)).getHost();
    assertEquals("0:0:0:0:0:0:0:1", loopbackHost);

    final String mappedIpv4Host = this.request(domainRequest(1, "::ffff:10.0.0.1", HTTP_PORT)).getHost();
    assertEquals("10.0.0.1", mappedIpv4Host);

    for (final String bad : new String[] { "2606::zz::1", "1.2.3.4:80", "[::1", "exa[mple]", "exam]ple", "fe80::1%eth0" }) {
      assertThrows(ProtocolException.class, () -> this.request(domainRequest(1, bad, HTTP_PORT)), bad);
    }
  }

  @Test
  void shouldNameTheHostAndKeepTheCauseWhenAnIpv6AddressIsMalformed() {
    final ProtocolException failure = assertThrows(ProtocolException.class, () -> this.requestAConnection("2606::zz::1", HTTP_PORT));
    final String message = failure.getMessage();
    assertEquals("Host name 2606::zz::1 of the request is not an IPv6 address!", message);
    final Throwable cause = failure.getCause();
    assertInstanceOf(IllegalArgumentException.class, cause);
  }

  @Test
  void shouldGiveAnswersTheFormOfTheProtocol() throws IOException {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final DataOutputStream output = new DataOutputStream(bytes);
    SocksProtocol.writeMethod(output, SocksProtocol.NO_ACCEPTABLE_METHOD);
    SocksProtocol.writeReply(output, SocksProtocol.NOT_ALLOWED);
    final byte[] writtenAnswers = bytes.toByteArray();
    final byte[] expectedWrittenAnswers = new byte[] { 5, (byte) CHANNEL_MASK, 5, 2, 0, 1, 0, 0, 0, 0, 0, 0 };
    assertArrayEquals(expectedWrittenAnswers, writtenAnswers);
  }

  @Test
  void shouldRefuseAHostNameThatStartsWithABracket() {
    for (final String host : new String[] { "[example.com", "]example.com", "example.com]" }) {
      final ProtocolException failure = assertThrows(ProtocolException.class, () -> this.requestAConnection(host, HTTPS_PORT));
      final String message = failure.getMessage();
      final String expectedMessage = "Host name %s of the request holds a bracket!".formatted(host);
      assertEquals(expectedMessage, message);
    }
  }

  @Test
  void shouldTreatRequestsForTheSameHostAndPortAsEqual() {
    EqualityAssertions.assertEqualityContract(
      new SocksProtocol.Request(HOST, HTTPS_PORT),
      new SocksProtocol.Request(HOST, HTTPS_PORT),
      new SocksProtocol.Request("example.org", HTTPS_PORT),
      new SocksProtocol.Request(HOST, HTTP_PORT)
    );
  }

  @Test
  void shouldHashRequestsForAnotherPortDifferently() {
    final SocksProtocol.Request request = new SocksProtocol.Request(HOST, HTTPS_PORT);
    final SocksProtocol.Request otherRequest = new SocksProtocol.Request(HOST, HTTP_PORT);
    final int hashCode = request.hashCode();
    final int otherHashCode = otherRequest.hashCode();
    assertNotEquals(hashCode, otherHashCode);
  }

  @Test
  void shouldDescribeARequestByItsHostAndPort() {
    final SocksProtocol.Request request = new SocksProtocol.Request(HOST, HTTPS_PORT);
    final String description = request.toString();
    assertEquals("Request{host=example.com, port=443}", description);
  }
}
