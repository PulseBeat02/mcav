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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import me.brandonli.mcav.browser.testing.Await;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NetworkGuardTest {

  private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
  private static final byte[] GREETING = { 5, 1, 0 };
  private static final byte[] SUCCESS = { 5, 0, 0, 1, 0, 0, 0, 0, 0, 0 };

  private final List<String> notices = new CopyOnWriteArrayList<>();
  private final List<Closeable> closeables = new ArrayList<>();
  private final AtomicInteger echoConnections = new AtomicInteger();
  private final AtomicInteger echoEnded = new AtomicInteger();
  private ServerSocket echo;

  @BeforeEach
  void startEchoServer() throws IOException {
    this.echo = new ServerSocket(0, 50, LOOPBACK);
    final Thread thread = new Thread(this::serveEcho, "echo");
    thread.setDaemon(true);
    thread.start();
  }

  private void serveEcho() {
    while (true) {
      final Socket socket;
      try {
        socket = this.echo.accept();
      } catch (final IOException exception) {
        return;
      }
      this.echoConnections.incrementAndGet();
      Thread.ofVirtual().start(() -> this.echo(socket));
    }
  }

  private void echo(final Socket socket) {
    try (socket; final InputStream in = socket.getInputStream(); final OutputStream out = socket.getOutputStream()) {
      in.transferTo(out);
    } catch (final IOException exception) {
      // the guard closed the connection
    }
    this.echoEnded.incrementAndGet();
  }

  @AfterEach
  void closeEverything() throws IOException {
    for (final Closeable closeable : this.closeables) {
      closeable.close();
    }
    this.echo.close();
  }

  private static InetAddress[] resolve(final String host) throws UnknownHostException {
    return switch (host) {
      case "echo.test" -> new InetAddress[] { LOOPBACK };
      case "mixed.test" -> new InetAddress[] { InetAddress.getByName("10.0.0.1"), LOOPBACK };
      case "missing.test" -> throw new UnknownHostException(host);
      default -> host.endsWith(".private.test") ? new InetAddress[] { InetAddress.getByName("10.0.0.1") } : InetAddress.getAllByName(host);
    };
  }

  private NetworkGuard guard(final Predicate<InetAddress> policy, final NetworkGuard.Connector connector, final int handshakeTimeoutMillis)
    throws IOException {
    final NetworkGuard guard = NetworkGuard.start(NetworkGuardTest::resolve, policy, connector, this.notices::add, handshakeTimeoutMillis);
    this.closeables.add(guard);
    return guard;
  }

  private NetworkGuard loopbackGuard() throws IOException {
    return this.guard(InetAddress::isLoopbackAddress, NetworkGuard::connect, 5_000);
  }

  private Socket client(final NetworkGuard guard) throws IOException {
    final Socket socket = new Socket(LOOPBACK, guard.getPort());
    socket.setSoTimeout(5_000);
    this.closeables.add(socket);
    return socket;
  }

  private static byte[] exchange(final Socket socket, final byte[] request, final int answerBytes) throws IOException {
    final OutputStream out = socket.getOutputStream();
    out.write(request);
    out.flush();
    final byte[] answer = new byte[answerBytes];
    new DataInputStream(socket.getInputStream()).readFully(answer);
    return answer;
  }

  private static byte[] reply(final int code) {
    return new byte[] { 5, (byte) code, 0, 1, 0, 0, 0, 0, 0, 0 };
  }

  private static void assertEnded(final Socket socket) throws IOException {
    try {
      assertEquals(-1, socket.getInputStream().read());
    } catch (final SocketException reset) {
      // a connection the guard closed with unread bytes may be reset instead
    }
  }

  private Socket connectThrough(final NetworkGuard guard, final String host, final int port) throws IOException {
    final Socket client = this.client(guard);
    assertArrayEquals(new byte[] { 5, 0 }, exchange(client, GREETING, 2));
    assertArrayEquals(SUCCESS, exchange(client, SocksProtocolTest.domainRequest(SocksProtocol.CONNECT, host, port), 10));
    return client;
  }

  @Test
  void aConnectionToAnAllowedHostIsRelayedBothWaysUntilOneSideEnds() throws IOException {
    final NetworkGuard guard = this.loopbackGuard();
    final Socket client = this.connectThrough(guard, "echo.test", this.echo.getLocalPort());
    final byte[] hello = "hello through the guard".getBytes(StandardCharsets.UTF_8);
    assertArrayEquals(hello, exchange(client, hello, hello.length));
    client.close();
    Await.until("the target connection ended", () -> this.echoEnded.get() == 1);
    assertEquals(List.of(), this.notices);
  }

  @Test
  void anAddressInTheRequestIsCheckedLikeAName() throws IOException {
    final NetworkGuard guard = this.loopbackGuard();
    final Socket client = this.client(guard);
    exchange(client, GREETING, 2);
    final int port = this.echo.getLocalPort();
    final byte[] request = { 5, 1, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port };
    assertArrayEquals(SUCCESS, exchange(client, request, 10));
    final Socket refused = this.client(guard);
    exchange(refused, GREETING, 2);
    final byte[] privateRequest = { 5, 1, 0, 1, 10, 0, 0, 1, 0, 80 };
    assertArrayEquals(reply(SocksProtocol.NOT_ALLOWED), exchange(refused, privateRequest, 10));
    assertEnded(refused);
  }

  @Test
  void aHostWithoutAnAllowedAddressIsRefusedAndReportedOnce() throws IOException {
    final NetworkGuard guard = this.guard(AddressPolicy::isPublic, NetworkGuard::connect, 5_000);
    for (int attempt = 0; attempt < 2; attempt++) {
      final Socket client = this.client(guard);
      exchange(client, GREETING, 2);
      final byte[] answer = exchange(client, SocksProtocolTest.domainRequest(SocksProtocol.CONNECT, "router.private.test", 80), 10);
      assertArrayEquals(reply(SocksProtocol.NOT_ALLOWED), answer);
    }
    assertEquals(List.of("Refused a connection to router.private.test, which is not a public address"), this.notices);
    assertEquals(0, this.echoConnections.get());
  }

  @Test
  void onlyAllowedAddressesOfAHostAreTried() throws IOException {
    final NetworkGuard guard = this.loopbackGuard();
    final Socket client = this.connectThrough(guard, "mixed.test", this.echo.getLocalPort());
    final byte[] ping = { 42 };
    assertArrayEquals(ping, exchange(client, ping, 1));
  }

  @Test
  void refusedReportsStopAtTheirLimit() throws IOException {
    final NetworkGuard guard = this.guard(AddressPolicy::isPublic, NetworkGuard::connect, 5_000);
    for (int host = 0; host <= NetworkGuard.MAX_REPORTED_HOSTS; host++) {
      final Socket client = this.client(guard);
      exchange(client, GREETING, 2);
      exchange(client, SocksProtocolTest.domainRequest(SocksProtocol.CONNECT, "h" + host + ".private.test", 80), 10);
      client.close();
    }
    assertEquals(NetworkGuard.MAX_REPORTED_HOSTS, this.notices.size());
  }

  @Test
  void failedConnectionsAreAnsweredWithTheirReason() throws IOException {
    final int closedPort;
    try (final ServerSocket closed = new ServerSocket(0, 1, LOOPBACK)) {
      closedPort = closed.getLocalPort();
    }
    final NetworkGuard guard = this.loopbackGuard();
    final Socket refused = this.client(guard);
    exchange(refused, GREETING, 2);
    assertArrayEquals(
      reply(SocksProtocol.CONNECTION_REFUSED),
      exchange(refused, SocksProtocolTest.domainRequest(1, "echo.test", closedPort), 10)
    );
    final Socket missing = this.client(guard);
    exchange(missing, GREETING, 2);
    assertArrayEquals(reply(SocksProtocol.HOST_UNREACHABLE), exchange(missing, SocksProtocolTest.domainRequest(1, "missing.test", 80), 10));
    final NetworkGuard slow =
      this.guard(
          InetAddress::isLoopbackAddress,
          address -> {
            throw new SocketTimeoutException("connect timed out");
          },
          5_000
        );
    final Socket timedOut = this.client(slow);
    exchange(timedOut, GREETING, 2);
    assertArrayEquals(reply(SocksProtocol.HOST_UNREACHABLE), exchange(timedOut, SocksProtocolTest.domainRequest(1, "echo.test", 80), 10));
  }

  @Test
  void clientsThatDoNotSpeakTheProtocolAreTurnedAway() throws IOException {
    final NetworkGuard guard = this.loopbackGuard();
    final Socket withPassword = this.client(guard);
    assertArrayEquals(new byte[] { 5, (byte) 0xFF }, exchange(withPassword, new byte[] { 5, 1, 2 }, 2));
    assertEnded(withPassword);
    final Socket binding = this.client(guard);
    exchange(binding, GREETING, 2);
    assertArrayEquals(
      reply(SocksProtocol.COMMAND_NOT_SUPPORTED),
      exchange(binding, SocksProtocolTest.domainRequest(2, "echo.test", 80), 10)
    );
    final Socket socks4 = this.client(guard);
    socks4.getOutputStream().write(new byte[] { 4, 1, 0, 80, 127, 0, 0, 1, 0 });
    assertEnded(socks4);
  }

  @Test
  void aClientThatStallsInItsHandshakeIsDropped() throws IOException {
    final NetworkGuard guard = this.guard(InetAddress::isLoopbackAddress, NetworkGuard::connect, 200);
    final Socket client = this.client(guard);
    client.getOutputStream().write(5);
    assertEnded(client);
  }

  @Test
  void connectionsBeyondTheLimitAreClosedAtOnce() throws IOException {
    final NetworkGuard guard = this.guard(InetAddress::isLoopbackAddress, NetworkGuard::connect, 30_000);
    final List<Socket> idle = new ArrayList<>();
    for (int count = 0; count < NetworkGuard.MAX_CONNECTIONS; count++) {
      idle.add(this.client(guard));
    }
    final Socket extra = this.client(guard);
    assertEnded(extra);
    final Socket first = idle.getFirst();
    assertArrayEquals(new byte[] { 5, 0 }, exchange(first, GREETING, 2));
  }

  @Test
  void closingTheGuardEndsEveryConnectionAndStopsListening() throws IOException {
    final NetworkGuard guard = this.loopbackGuard();
    final Socket client = this.connectThrough(guard, "echo.test", this.echo.getLocalPort());
    guard.close();
    assertEnded(client);
    Await.until("the target connection ended", () -> this.echoEnded.get() == 1);
    assertThrows(ConnectException.class, () -> new Socket(LOOPBACK, guard.getPort()).close());
    assertEquals(List.of(), this.notices);
  }

  @Test
  void aBrokenListeningSocketIsReported() throws IOException {
    final NetworkGuard guard = this.loopbackGuard();
    guard.getServer().close();
    Await.until("the report", () -> !this.notices.isEmpty());
    assertTrue(this.notices.getFirst().startsWith("The network guard stopped: "), this.notices.getFirst());
  }

  @Test
  void theDefaultGuardRefusesLoopback() throws IOException {
    try (final NetworkGuard guard = NetworkGuard.start(this.notices::add)) {
      final Socket client = this.client(guard);
      exchange(client, GREETING, 2);
      final byte[] answer = exchange(client, SocksProtocolTest.domainRequest(1, "localhost", this.echo.getLocalPort()), 10);
      assertArrayEquals(reply(SocksProtocol.NOT_ALLOWED), answer);
    }
    assertEquals(List.of("Refused a connection to localhost, which is not a public address"), this.notices);
  }

  @Test
  void connectingClosesTheSocketOfAFailedAttempt() throws IOException {
    final int closedPort;
    try (final ServerSocket closed = new ServerSocket(0, 1, LOOPBACK)) {
      closedPort = closed.getLocalPort();
    }
    assertThrows(ConnectException.class, () -> NetworkGuard.connect(new InetSocketAddress(LOOPBACK, closedPort)));
    try (final Socket socket = NetworkGuard.connect(new InetSocketAddress(LOOPBACK, this.echo.getLocalPort()))) {
      assertTrue(socket.isConnected());
    }
  }

  @Test
  void closingQuietlySwallowsAFailure() {
    final AtomicInteger closed = new AtomicInteger();
    NetworkGuard.closeQuietly(() -> {
      closed.incrementAndGet();
      throw new IOException("already gone");
    });
    assertEquals(1, closed.get());
  }
}
