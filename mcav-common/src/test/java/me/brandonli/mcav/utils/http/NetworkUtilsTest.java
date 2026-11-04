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
package me.brandonli.mcav.utils.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.testing.LocalHttpServer;
import me.brandonli.mcav.testing.UtilityClassAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

final class NetworkUtilsTest {

  private static final int OK_STATUS = 200;

  private static final String PUBLIC_ADDRESS = "203.0.113.7";
  private static final String IPV6_ADDRESS = "2001:db8::1";

  private static final String LOOPBACK_URL_PREFIX = "http://127.0.0.1:";

  private static final String IPV4_PATH = "/ipv4";
  private static final String IPV6_PATH = "/ipv6";
  private static final String ADDRESS_PATH = "/ip";
  private static final String ERROR_PATH = "/error";
  private static final String GARBAGE_PATH = "/garbage";
  private static final String PADDED_PATH = "/padded";

  private static final String PACK_PATH = "/pack.zip";
  private static final String MISSING_PACK_PATH = "/missing.zip";

  private static final String OLD_PATH = "/old";
  private static final String NEW_PATH = "/new";

  private LocalHttpServer http;

  @BeforeEach
  void startServer() {
    this.http = LocalHttpServer.start();
  }

  @AfterEach
  void stopServer() {
    this.http.close();
    final boolean leftInterrupted = Thread.interrupted();
    assertFalse(leftInterrupted, "every test clears the interrupt flag it sets");
  }

  private static URI closedPortUri() throws IOException {
    final int port;
    try (final ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }

    return URI.create(LOOPBACK_URL_PREFIX + port + "/");
  }

  @Test
  void shouldLookUpThePublicAddressAndIgnoreSurroundingWhitespace() {
    final byte[] ipv4Body = " 203.0.113.7\n".getBytes(StandardCharsets.US_ASCII);
    final byte[] ipv6Body = IPV6_ADDRESS.getBytes(StandardCharsets.US_ASCII);
    this.http.respond(IPV4_PATH, OK_STATUS, ipv4Body);
    this.http.respond(IPV6_PATH, OK_STATUS, ipv6Body);
    final URI ipv4Service = this.http.uri(IPV4_PATH);
    final URI ipv6Service = this.http.uri(IPV6_PATH);
    final Optional<String> ipv4Address = NetworkUtils.lookUpPublicAddress(ipv4Service);
    final Optional<String> ipv6Address = NetworkUtils.lookUpPublicAddress(ipv6Service);
    final Optional<String> repeatedAddress = NetworkUtils.lookUpPublicAddress(ipv4Service);
    final int requests = this.http.getRequestCount(IPV4_PATH);
    final Optional<String> expectedIpv4 = Optional.of(PUBLIC_ADDRESS);
    final Optional<String> expectedIpv6 = Optional.of(IPV6_ADDRESS);
    assertEquals(expectedIpv4, ipv4Address);
    assertEquals(expectedIpv6, ipv6Address);
    assertEquals(ipv4Address, repeatedAddress);
    assertEquals(2, requests, "the result is not cached");
  }

  @Test
  void shouldLookUpThePublicAddressWithTheDefaultService() {
    final URI expectedService = URI.create("https://ipv4.icanhazip.com/");
    final Optional<String> answer = Optional.of("198.51.100.4");
    try (final MockedStatic<NetworkUtils> networkUtils = Mockito.mockStatic(NetworkUtils.class, Mockito.CALLS_REAL_METHODS)) {
      networkUtils.when(() -> NetworkUtils.lookUpPublicAddress(expectedService)).thenReturn(answer);
      final Optional<String> address = NetworkUtils.lookUpPublicAddress();
      assertEquals(answer, address);

      networkUtils.verify(() -> NetworkUtils.lookUpPublicAddress(expectedService));
    }

    final URI defaultService = NetworkUtils.DEFAULT_ADDRESS_SERVICE;
    assertEquals(expectedService, defaultService);
  }

  @Test
  void shouldReportLookupsThatFail() throws IOException {
    final byte[] address = PUBLIC_ADDRESS.getBytes(StandardCharsets.US_ASCII);
    final byte[] garbage = "<html>not an address</html>".getBytes(StandardCharsets.US_ASCII);
    this.http.respond(ERROR_PATH, 500, address);
    this.http.respond(GARBAGE_PATH, OK_STATUS, garbage);
    final URI error = this.http.uri(ERROR_PATH);
    final URI invalid = this.http.uri(GARBAGE_PATH);
    final URI closed = closedPortUri();
    final Optional<String> errorAddress = NetworkUtils.lookUpPublicAddress(error);
    final Optional<String> invalidAddress = NetworkUtils.lookUpPublicAddress(invalid);
    final Optional<String> closedAddress = NetworkUtils.lookUpPublicAddress(closed);
    final boolean errorAddressEmpty = errorAddress.isEmpty();
    final boolean invalidAddressEmpty = invalidAddress.isEmpty();
    final boolean closedAddressEmpty = closedAddress.isEmpty();
    assertTrue(errorAddressEmpty);
    assertTrue(invalidAddressEmpty);
    assertTrue(closedAddressEmpty);
  }

  @Test
  void shouldGiveUpOnAnAnswerThatDoesNotEndWithinTheTimeout() {
    this.http.respondStalling(ADDRESS_PATH, OK_STATUS, "203.0".getBytes(StandardCharsets.US_ASCII), 11);
    final URI service = this.http.uri(ADDRESS_PATH);
    final Optional<String> address = assertTimeoutPreemptively(Duration.ofSeconds(9), () -> NetworkUtils.lookUpPublicAddress(service));
    final boolean empty = address.isEmpty();
    assertTrue(empty);
  }

  @Test
  void shouldReadNoAnswerLongerThanAnyAddress() {
    final byte[] padded = (" ".repeat(300) + PUBLIC_ADDRESS).getBytes(StandardCharsets.US_ASCII);
    this.http.respond(PADDED_PATH, OK_STATUS, padded);
    final Optional<String> address = NetworkUtils.lookUpPublicAddress(this.http.uri(PADDED_PATH));
    final boolean empty = address.isEmpty();
    assertTrue(empty, "an address is a few dozen characters");
  }

  @Test
  void shouldRejectAddressServicesThatAreNotHttpUris() {
    final URI ftp = URI.create("ftp://127.0.0.1/ip");
    final URI relative = URI.create("relative/ip");
    final URI withoutHost = URI.create("http:///ip");
    assertThrows(IllegalArgumentException.class, () -> NetworkUtils.lookUpPublicAddress(ftp));
    assertThrows(IllegalArgumentException.class, () -> NetworkUtils.lookUpPublicAddress(relative));
    assertThrows(IllegalArgumentException.class, () -> NetworkUtils.lookUpPublicAddress(withoutHost));
    assertThrows(NullPointerException.class, () -> NetworkUtils.lookUpPublicAddress(null));
  }

  @Test
  void shouldKeepTheInterruptFlagWhenInterruptedDuringTheLookup() {
    final byte[] address = PUBLIC_ADDRESS.getBytes(StandardCharsets.US_ASCII);
    this.http.respond(ADDRESS_PATH, OK_STATUS, address);
    final URI service = this.http.uri(ADDRESS_PATH);
    final Thread current = Thread.currentThread();
    current.interrupt();
    final Optional<String> result = NetworkUtils.lookUpPublicAddress(service);
    final boolean interrupted = Thread.interrupted();
    final boolean resultEmpty = result.isEmpty();
    assertTrue(resultEmpty);
    assertTrue(interrupted);
  }

  @Test
  void shouldAcceptUrlsThatAnswerWithStatus200() {
    this.http.respond(PACK_PATH, OK_STATUS, new byte[] { 1, 2, 3 });
    final URI pack = this.http.uri(PACK_PATH);
    final URI missing = this.http.uri(MISSING_PACK_PATH);
    final boolean packReachable = NetworkUtils.isReachable(pack);
    final boolean missingReachable = NetworkUtils.isReachable(missing);
    final int requests = this.http.getRequestCount(PACK_PATH);
    assertTrue(packReachable);
    assertFalse(missingReachable);
    assertEquals(1, requests);
  }

  @Test
  void shouldFollowRedirects() throws IOException {
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    final InetSocketAddress socketAddress = new InetSocketAddress(loopback, 0);
    final HttpServer redirecting = HttpServer.create(socketAddress, 0);
    redirecting.createContext(OLD_PATH, NetworkUtilsTest::redirectToNew);
    redirecting.createContext(NEW_PATH, NetworkUtilsTest::answerOk);
    redirecting.start();
    try {
      final InetSocketAddress bound = redirecting.getAddress();
      final int port = bound.getPort();
      final URI old = URI.create(LOOPBACK_URL_PREFIX + port + OLD_PATH);
      final boolean reachable = NetworkUtils.isReachable(old);
      assertTrue(reachable);
    } finally {
      redirecting.stop(0);
    }
  }

  private static void redirectToNew(final HttpExchange exchange) throws IOException {
    final Headers headers = exchange.getResponseHeaders();
    headers.add("Location", NEW_PATH);
    exchange.sendResponseHeaders(302, -1);
    exchange.close();
  }

  private static void answerOk(final HttpExchange exchange) throws IOException {
    exchange.sendResponseHeaders(OK_STATUS, -1);
    exchange.close();
  }

  @Test
  void shouldRejectUrlsThatAreNotReachableHttpResources() throws IOException {
    final URI ftp = URI.create("ftp://127.0.0.1/pack.zip");
    final URI relative = URI.create("relative/pack.zip");
    final URI withoutHost = URI.create("http:///pack.zip");
    final URI closed = closedPortUri();
    final boolean ftpReachable = NetworkUtils.isReachable(ftp);
    final boolean relativeReachable = NetworkUtils.isReachable(relative);
    final boolean withoutHostReachable = NetworkUtils.isReachable(withoutHost);
    final boolean closedReachable = NetworkUtils.isReachable(closed);
    assertFalse(ftpReachable);
    assertFalse(relativeReachable);
    assertFalse(withoutHostReachable);
    assertFalse(closedReachable);
    assertThrows(NullPointerException.class, () -> NetworkUtils.isReachable(null));
  }

  @Test
  void shouldAcceptSchemesInAnyCase() {
    this.http.respond(PACK_PATH, OK_STATUS, new byte[] { 1 });
    final URI pack = this.http.uri(PACK_PATH);
    final String lowerCase = pack.toString();
    final String upperCase = lowerCase.replace("http:", "HTTP:");
    final URI upperCaseUri = URI.create(upperCase);
    final boolean reachable = NetworkUtils.isReachable(upperCaseUri);
    assertTrue(reachable);
  }

  @Test
  void shouldKeepTheInterruptFlagWhenInterruptedDuringTheCheck() {
    this.http.respond(PACK_PATH, OK_STATUS, new byte[] { 1 });
    final URI pack = this.http.uri(PACK_PATH);
    final Thread current = Thread.currentThread();
    current.interrupt();
    final boolean reachable = NetworkUtils.isReachable(pack);
    final boolean interrupted = Thread.interrupted();
    assertFalse(reachable);
    assertTrue(interrupted);
  }

  @Test
  void shouldAttemptHttpsUrls() throws Exception {
    try (final ServerSocket listener = new ServerSocket(0)) {
      listener.setSoTimeout(10_000);
      final int port = listener.getLocalPort();
      final CompletableFuture<Boolean> accepted = CompletableFuture.supplyAsync(() -> acceptOnce(listener));
      final URI secure = URI.create("https://127.0.0.1:" + port + PACK_PATH);
      final boolean reachable = NetworkUtils.isReachable(secure);
      final boolean connected = accepted.get(10, TimeUnit.SECONDS);
      assertFalse(reachable, "the listener does not speak TLS");
      assertTrue(connected, "https URLs are requested");
    }
  }

  private static boolean acceptOnce(final ServerSocket listener) {
    try (final Socket socket = listener.accept()) {
      return socket.isConnected();
    } catch (final IOException exception) {
      return false;
    }
  }

  @Test
  void shouldNotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(NetworkUtils.class);
  }

  @Test
  void shouldBracketIpv6AddressesForTheHostPartOfAUrl() {
    final String loopback = NetworkUtils.formatHostForUrl("::1");
    final String full = NetworkUtils.formatHostForUrl(IPV6_ADDRESS);
    final String scoped = NetworkUtils.formatHostForUrl("fe80::1%eth0");
    final String ipv4 = NetworkUtils.formatHostForUrl("203.0.113.9");
    final String hostName = NetworkUtils.formatHostForUrl("example.org");

    assertEquals("[::1]", loopback);
    assertEquals("[2001:db8::1]", full);
    assertEquals("[fe80::1%25eth0]", scoped, "the zone separator is escaped for a URL");
    assertEquals("203.0.113.9", ipv4);
    assertEquals("example.org", hostName);
    assertThrows(NullPointerException.class, () -> NetworkUtils.formatHostForUrl(null));
  }
}
