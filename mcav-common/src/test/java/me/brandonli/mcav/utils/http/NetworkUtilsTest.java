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

/**
 * Tests {@link NetworkUtils} against servers on the loopback interface.
 */
final class NetworkUtilsTest {

  private LocalHttpServer http;

  @BeforeEach
  void startServer() {
    this.http = LocalHttpServer.start();
  }

  @AfterEach
  void stopServer() {
    this.http.close();
    // clearing the flag keeps a test that failed before clearing its interrupt from breaking the next test
    final boolean leftInterrupted = Thread.interrupted();
    assertFalse(leftInterrupted, "every test clears the interrupt flag it sets");
  }

  private static URI closedPortUri() throws IOException {
    final int port;
    try (final ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    return URI.create("http://127.0.0.1:" + port + "/");
  }

  @Test
  void looksUpThePublicAddressAndIgnoresSurroundingWhitespace() {
    final byte[] ipv4Body = " 203.0.113.7\n".getBytes(StandardCharsets.US_ASCII);
    final byte[] ipv6Body = "2001:db8::1".getBytes(StandardCharsets.US_ASCII);
    this.http.respond("/ipv4", 200, ipv4Body);
    this.http.respond("/ipv6", 200, ipv6Body);
    final URI ipv4Service = this.http.uri("/ipv4");
    final URI ipv6Service = this.http.uri("/ipv6");
    final Optional<String> ipv4Address = NetworkUtils.lookUpPublicAddress(ipv4Service);
    final Optional<String> ipv6Address = NetworkUtils.lookUpPublicAddress(ipv6Service);
    final Optional<String> repeatedAddress = NetworkUtils.lookUpPublicAddress(ipv4Service);
    final int requests = this.http.getRequestCount("/ipv4");
    final Optional<String> expectedIpv4 = Optional.of("203.0.113.7");
    final Optional<String> expectedIpv6 = Optional.of("2001:db8::1");
    assertEquals(expectedIpv4, ipv4Address);
    assertEquals(expectedIpv6, ipv6Address);
    assertEquals(ipv4Address, repeatedAddress);
    assertEquals(2, requests, "the result is not cached");
  }

  @Test
  void looksUpThePublicAddressWithTheDefaultService() {
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
  void reportsLookupsThatFail() throws IOException {
    final byte[] address = "203.0.113.7".getBytes(StandardCharsets.US_ASCII);
    final byte[] garbage = "<html>not an address</html>".getBytes(StandardCharsets.US_ASCII);
    this.http.respond("/error", 500, address);
    this.http.respond("/garbage", 200, garbage);
    final URI error = this.http.uri("/error");
    final URI invalid = this.http.uri("/garbage");
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
  void rejectsAddressServicesThatAreNotHttpUris() {
    final URI ftp = URI.create("ftp://127.0.0.1/ip");
    final URI relative = URI.create("relative/ip");
    final URI withoutHost = URI.create("http:///ip");
    assertThrows(IllegalArgumentException.class, () -> NetworkUtils.lookUpPublicAddress(ftp));
    assertThrows(IllegalArgumentException.class, () -> NetworkUtils.lookUpPublicAddress(relative));
    assertThrows(IllegalArgumentException.class, () -> NetworkUtils.lookUpPublicAddress(withoutHost));
    assertThrows(NullPointerException.class, () -> NetworkUtils.lookUpPublicAddress(null));
  }

  @Test
  void keepsTheInterruptFlagWhenInterruptedDuringTheLookup() {
    final byte[] address = "203.0.113.7".getBytes(StandardCharsets.US_ASCII);
    this.http.respond("/ip", 200, address);
    final URI service = this.http.uri("/ip");
    final Thread currentThread = Thread.currentThread();
    currentThread.interrupt();
    final Optional<String> result = NetworkUtils.lookUpPublicAddress(service);
    final boolean interrupted = Thread.interrupted();
    final boolean resultEmpty = result.isEmpty();
    assertTrue(resultEmpty);
    assertTrue(interrupted);
  }

  @Test
  void acceptsUrlsThatAnswerWithStatus200() {
    this.http.respond("/pack.zip", 200, new byte[] { 1, 2, 3 });
    final URI pack = this.http.uri("/pack.zip");
    final URI missing = this.http.uri("/missing.zip");
    final boolean packReachable = NetworkUtils.isReachable(pack);
    final boolean missingReachable = NetworkUtils.isReachable(missing);
    final int requests = this.http.getRequestCount("/pack.zip");
    assertTrue(packReachable);
    assertFalse(missingReachable);
    assertEquals(1, requests);
  }

  @Test
  void followsRedirects() throws IOException {
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    final InetSocketAddress socketAddress = new InetSocketAddress(loopback, 0);
    final HttpServer redirecting = HttpServer.create(socketAddress, 0);
    redirecting.createContext("/old", NetworkUtilsTest::redirectToNew);
    redirecting.createContext("/new", NetworkUtilsTest::answerOk);
    redirecting.start();
    try {
      final InetSocketAddress bound = redirecting.getAddress();
      final int port = bound.getPort();
      final URI old = URI.create("http://127.0.0.1:" + port + "/old");
      final boolean reachable = NetworkUtils.isReachable(old);
      assertTrue(reachable);
    } finally {
      redirecting.stop(0);
    }
  }

  private static void redirectToNew(final HttpExchange exchange) throws IOException {
    final Headers headers = exchange.getResponseHeaders();
    headers.add("Location", "/new");
    exchange.sendResponseHeaders(302, -1);
    exchange.close();
  }

  private static void answerOk(final HttpExchange exchange) throws IOException {
    exchange.sendResponseHeaders(200, -1);
    exchange.close();
  }

  @Test
  void rejectsUrlsThatAreNotReachableHttpResources() throws IOException {
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
  void acceptsSchemesInAnyCase() {
    this.http.respond("/pack.zip", 200, new byte[] { 1 });
    final URI pack = this.http.uri("/pack.zip");
    final String lowerCase = pack.toString();
    final String upperCase = lowerCase.replace("http:", "HTTP:");
    final URI upperCaseUri = URI.create(upperCase);
    final boolean reachable = NetworkUtils.isReachable(upperCaseUri);
    assertTrue(reachable);
  }

  @Test
  void keepsTheInterruptFlagWhenInterruptedDuringTheCheck() {
    this.http.respond("/pack.zip", 200, new byte[] { 1 });
    final URI pack = this.http.uri("/pack.zip");
    final Thread currentThread = Thread.currentThread();
    currentThread.interrupt();
    final boolean reachable = NetworkUtils.isReachable(pack);
    final boolean interrupted = Thread.interrupted();
    assertFalse(reachable);
    assertTrue(interrupted);
  }

  @Test
  void attemptsHttpsUrls() throws Exception {
    try (final ServerSocket listener = new ServerSocket(0)) {
      listener.setSoTimeout(10_000);
      final int port = listener.getLocalPort();
      final CompletableFuture<Boolean> accepted = CompletableFuture.supplyAsync(() -> acceptOnce(listener));
      final URI secure = URI.create("https://127.0.0.1:" + port + "/pack.zip");
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
  void cannotBeInstantiated() throws ReflectiveOperationException {
    UtilityClassAssertions.assertNotInstantiable(NetworkUtils.class);
  }

  @Test
  void bracketsIpv6AddressesForTheHostPartOfAUrl() {
    // http://::1:8080/ cannot be parsed: nothing separates the colons of the address from the colon of the port
    final String loopback = NetworkUtils.formatHostForUrl("::1");
    final String full = NetworkUtils.formatHostForUrl("2001:db8::1");
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
