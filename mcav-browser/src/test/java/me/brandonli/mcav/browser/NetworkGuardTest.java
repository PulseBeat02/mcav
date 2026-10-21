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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import me.brandonli.mcav.browser.testing.Await;
import me.brandonli.mcav.browser.testing.OpenFiles;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

class NetworkGuardTest {

  private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
  private static final byte[] GREETING = { 5, 1, 0 };
  private static final byte[] SUCCESS = { 5, 0, 0, 1, 0, 0, 0, 0, 0, 0 };

  private static final int REPLY_BYTES = 10;
  private static final int HTTP_PORT = 80;

  private static final String ECHO_HOST = "echo.test";
  private static final String PUBLIC_IPV4 = "8.8.8.8";
  private static final String TRANSLATED_PRIVATE_IPV6 = "2a01:4f8:1:2:3:4:a00:1";

  private static final int CONNECT_TIMEOUT_MILLISECONDS = 5_000;
  private static final int READ_TIMEOUT_MILLISECONDS = 5_000;

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
    try (socket; final InputStream input = socket.getInputStream(); final OutputStream output = socket.getOutputStream()) {
      input.transferTo(output);
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
      case ECHO_HOST -> new InetAddress[] { LOOPBACK };
      case "mixed.test" -> new InetAddress[] { InetAddress.getByName("10.0.0.1"), LOOPBACK };
      case "missing.test" -> throw new UnknownHostException(host);
      default -> host.endsWith(".private.test") ? new InetAddress[] { InetAddress.getByName("10.0.0.1") } : InetAddress.getAllByName(host);
    };
  }

  private NetworkGuard guard(
    final Predicate<InetAddress> policy,
    final NetworkGuard.Connector connector,
    final int handshakeTimeoutMilliseconds
  ) throws IOException {
    final NetworkGuard guard = NetworkGuard.start(
      NetworkGuardTest::resolve,
      policy,
      connector,
      this.notices::add,
      handshakeTimeoutMilliseconds
    );
    this.closeables.add(guard);

    return guard;
  }

  private NetworkGuard loopbackGuard() throws IOException {
    return this.guard(InetAddress::isLoopbackAddress, NetworkGuard::connect, CONNECT_TIMEOUT_MILLISECONDS);
  }

  private Socket client(final NetworkGuard guard) throws IOException {
    final Socket socket = new Socket(LOOPBACK, guard.getPort());
    socket.setSoTimeout(READ_TIMEOUT_MILLISECONDS);
    this.closeables.add(socket);

    return socket;
  }

  private byte[] exchange(final Socket socket, final byte[] request, final int answerBytes) throws IOException {
    final OutputStream output = socket.getOutputStream();
    output.write(request);
    output.flush();
    final byte[] answer = new byte[answerBytes];
    new DataInputStream(socket.getInputStream()).readFully(answer);

    return answer;
  }

  private byte[] reply(final int code) {
    return new byte[] { 5, (byte) code, 0, 1, 0, 0, 0, 0, 0, 0 };
  }

  private void assertEnded(final Socket socket) throws IOException {
    try {
      final int nextByte = socket.getInputStream().read();
      assertEquals(-1, nextByte);
    } catch (final SocketException exception) {
      // a connection the guard closed with unread bytes may be reset instead
    }
  }

  private Socket connectThrough(final NetworkGuard guard, final String host, final int port) throws IOException {
    final Socket client = this.client(guard);
    final byte[] greetingAnswer = this.exchange(client, GREETING, 2);
    assertArrayEquals(new byte[] { 5, 0 }, greetingAnswer);

    final byte[] connectAnswer = this.exchange(client, SocksProtocolTest.domainRequest(SocksProtocol.CONNECT, host, port), REPLY_BYTES);
    assertArrayEquals(SUCCESS, connectAnswer);

    return client;
  }

  @Test
  void shouldRelayAConnectionToAnAllowedHostBothWaysUntilOneSideEnds() throws IOException {
    final NetworkGuard guard = this.loopbackGuard();
    final Socket client = this.connectThrough(guard, ECHO_HOST, this.echo.getLocalPort());
    final byte[] hello = "hello through the guard".getBytes(StandardCharsets.UTF_8);
    final byte[] echoed = this.exchange(client, hello, hello.length);
    assertArrayEquals(hello, echoed);

    client.close();
    Await.until("the target connection ended", () -> this.echoEnded.get() == 1);
    assertEquals(List.of(), this.notices);
  }

  @Test
  void shouldCheckAnAddressInTheRequestLikeAName() throws IOException {
    final NetworkGuard guard = this.loopbackGuard();
    final Socket client = this.client(guard);
    this.exchange(client, GREETING, 2);
    final int port = this.echo.getLocalPort();
    final byte[] request = { 5, 1, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port };
    final byte[] loopbackAnswer = this.exchange(client, request, 10);
    assertArrayEquals(SUCCESS, loopbackAnswer);

    final Socket refused = this.client(guard);
    this.exchange(refused, GREETING, 2);
    final byte[] privateRequest = { 5, 1, 0, 1, 10, 0, 0, 1, 0, 80 };
    final byte[] privateAnswer = this.exchange(refused, privateRequest, 10);
    assertArrayEquals(this.reply(SocksProtocol.NOT_ALLOWED), privateAnswer);

    this.assertEnded(refused);
  }

  @Test
  void shouldRefuseAndReportOnceAHostWithoutAnAllowedAddress() throws IOException {
    final NetworkGuard guard = this.guard(AddressPolicy::isPublic, NetworkGuard::connect, CONNECT_TIMEOUT_MILLISECONDS);
    for (int attempt = 0; attempt < 2; attempt++) {
      final Socket client = this.client(guard);
      this.exchange(client, GREETING, 2);
      final byte[] answer = this.exchange(
        client,
        SocksProtocolTest.domainRequest(SocksProtocol.CONNECT, "router.private.test", HTTP_PORT),
        REPLY_BYTES
      );
      assertArrayEquals(this.reply(SocksProtocol.NOT_ALLOWED), answer);
    }
    assertEquals(List.of("The connection to router.private.test was refused, because it is not a public address."), this.notices);

    final int echoConnectionCount = this.echoConnections.get();
    assertEquals(0, echoConnectionCount);
  }

  @Test
  void shouldOnlyTryAllowedAddressesOfAHost() throws IOException {
    final NetworkGuard guard = this.loopbackGuard();
    final Socket client = this.connectThrough(guard, "mixed.test", this.echo.getLocalPort());
    final byte[] ping = { 42 };
    final byte[] echoed = this.exchange(client, ping, 1);
    assertArrayEquals(ping, echoed);
  }

  @Test
  void shouldStopRefusedReportsAtTheirLimit() throws IOException {
    final NetworkGuard guard = this.guard(AddressPolicy::isPublic, NetworkGuard::connect, CONNECT_TIMEOUT_MILLISECONDS);
    for (int host = 0; host <= NetworkGuard.MAXIMUM_REPORTED_HOSTS; host++) {
      final Socket client = this.client(guard);
      this.exchange(client, GREETING, 2);
      this.exchange(client, SocksProtocolTest.domainRequest(SocksProtocol.CONNECT, "h" + host + ".private.test", HTTP_PORT), REPLY_BYTES);
      client.close();
    }
    final int noticeCount = this.notices.size();
    assertEquals(NetworkGuard.MAXIMUM_REPORTED_HOSTS, noticeCount);
  }

  @Test
  void shouldAnswerFailedConnectionsWithTheirReason() throws IOException {
    final int closedPort;
    try (final ServerSocket closed = new ServerSocket(0, 1, LOOPBACK)) {
      closedPort = closed.getLocalPort();
    }
    final NetworkGuard guard = this.loopbackGuard();
    final Socket refused = this.client(guard);
    this.exchange(refused, GREETING, 2);
    final byte[] refusedAnswer = this.exchange(
      refused,
      SocksProtocolTest.domainRequest(SocksProtocol.CONNECT, ECHO_HOST, closedPort),
      REPLY_BYTES
    );
    assertArrayEquals(this.reply(SocksProtocol.CONNECTION_REFUSED), refusedAnswer);

    final Socket missing = this.client(guard);
    this.exchange(missing, GREETING, 2);
    final byte[] missingAnswer = this.exchange(
      missing,
      SocksProtocolTest.domainRequest(SocksProtocol.CONNECT, "missing.test", HTTP_PORT),
      REPLY_BYTES
    );
    assertArrayEquals(this.reply(SocksProtocol.HOST_UNREACHABLE), missingAnswer);

    final NetworkGuard slow = this.guard(InetAddress::isLoopbackAddress, _ -> this.timeOutConnecting(), CONNECT_TIMEOUT_MILLISECONDS);
    final Socket timedOut = this.client(slow);
    this.exchange(timedOut, GREETING, 2);
    final byte[] timedOutAnswer = this.exchange(
      timedOut,
      SocksProtocolTest.domainRequest(SocksProtocol.CONNECT, ECHO_HOST, HTTP_PORT),
      REPLY_BYTES
    );
    assertArrayEquals(this.reply(SocksProtocol.HOST_UNREACHABLE), timedOutAnswer);
  }

  private Socket timeOutConnecting() throws SocketTimeoutException {
    throw new SocketTimeoutException("connect timed out");
  }

  @Test
  void shouldTurnAwayClientsThatDoNotSpeakTheProtocol() throws IOException {
    final NetworkGuard guard = this.loopbackGuard();
    final Socket withPassword = this.client(guard);
    final byte[] passwordAnswer = this.exchange(withPassword, new byte[] { 5, 1, 2 }, 2);
    assertArrayEquals(new byte[] { 5, (byte) 0xFF }, passwordAnswer);

    this.assertEnded(withPassword);
    final Socket binding = this.client(guard);
    this.exchange(binding, GREETING, 2);
    final byte[] bindAnswer = this.exchange(binding, SocksProtocolTest.domainRequest(2, ECHO_HOST, HTTP_PORT), REPLY_BYTES);
    assertArrayEquals(this.reply(SocksProtocol.COMMAND_NOT_SUPPORTED), bindAnswer);

    final Socket socks4 = this.client(guard);
    socks4.getOutputStream().write(new byte[] { 4, 1, 0, 80, 127, 0, 0, 1, 0 });
    this.assertEnded(socks4);
  }

  // the tasks of other plugins can fill the common pool like this
  private CountDownLatch occupyTheCommonPool() throws InterruptedException {
    final int workers = ForkJoinPool.getCommonPoolParallelism();
    final CountDownLatch busy = new CountDownLatch(workers);
    final CountDownLatch release = new CountDownLatch(1);
    final ForkJoinPool pool = ForkJoinPool.commonPool();
    for (int worker = 0; worker < workers; worker++) {
      pool.execute(() -> this.occupyUntilReleased(busy, release));
    }
    final boolean allBusy = busy.await(30, TimeUnit.SECONDS);
    assertTrue(allBusy, "every worker of the common pool is busy");

    return release;
  }

  private void occupyUntilReleased(final CountDownLatch busy, final CountDownLatch release) {
    busy.countDown();
    try {
      release.await();
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void shouldDropAClientThatStallsInItsHandshakeWhileTheCommonPoolIsBusy() throws Exception {
    final CountDownLatch release = this.occupyTheCommonPool();
    try {
      final NetworkGuard guard = this.guard(InetAddress::isLoopbackAddress, NetworkGuard::connect, 200);
      final Socket client = this.client(guard);
      client.getOutputStream().write(5);
      assertTimeoutPreemptively(Duration.ofSeconds(30), () -> this.assertEnded(client));
    } finally {
      release.countDown();
    }
  }

  @Test
  void shouldDropAClientThatStallsInItsHandshake() throws IOException {
    final NetworkGuard guard = this.guard(InetAddress::isLoopbackAddress, NetworkGuard::connect, 200);
    final Socket client = this.client(guard);
    client.getOutputStream().write(5);
    this.assertEnded(client);
  }

  @Test
  void shouldDropAClientThatTricklesItsHandshakeOnceTheWholeHandshakeTookTooLong() throws Exception {
    final NetworkGuard guard = this.guard(InetAddress::isLoopbackAddress, NetworkGuard::connect, 600);
    final Socket client = this.client(guard);
    final OutputStream output = client.getOutputStream();
    final int port = this.echo.getLocalPort();
    final byte[] handshake = { 5, 1, 0, 5, 1, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port };
    try {
      for (final byte value : handshake) {
        output.write(value);
        output.flush();
        Thread.sleep(150L);
      }
    } catch (final SocketException exception) {
      // the guard closed the connection while the client still wrote
    }
    final InputStream input = client.getInputStream();
    try {
      while (input.read() >= 0) {}
    } catch (final SocketException exception) {
      // a guard that closed with unread bytes resets the connection
    }
    final int echoConnectionCount = this.echoConnections.get();
    assertEquals(0, echoConnectionCount, "the request was never carried out");
  }

  @Test
  void shouldCloseConnectionsBeyondTheLimitAtOnce() throws IOException {
    final NetworkGuard guard = this.guard(InetAddress::isLoopbackAddress, NetworkGuard::connect, 30_000);
    final List<Socket> idle = new ArrayList<>();
    for (int count = 0; count < NetworkGuard.MAXIMUM_CONNECTIONS; count++) {
      idle.add(this.client(guard));
    }
    final Socket extra = this.client(guard);
    this.assertEnded(extra);
    final Socket first = idle.getFirst();
    final byte[] greetingAnswer = this.exchange(first, GREETING, 2);
    assertArrayEquals(new byte[] { 5, 0 }, greetingAnswer);
  }

  @Test
  void shouldEndTheConnectionOfTheClientWhenTheTargetEnds() throws Exception {
    try (final ServerSocket ending = new ServerSocket(0, 1, LOOPBACK)) {
      final CompletableFuture<Void> target = CompletableFuture.runAsync(() -> this.sayByeAndHangUp(ending));
      final NetworkGuard guard = this.loopbackGuard();
      final Socket client = this.connectThrough(guard, ECHO_HOST, ending.getLocalPort());
      final byte[] bye = new byte[3];
      new DataInputStream(client.getInputStream()).readFully(bye);
      assertArrayEquals("bye".getBytes(StandardCharsets.US_ASCII), bye);

      this.assertEnded(client);
      target.get(5, TimeUnit.SECONDS);
    }
  }

  private void sayByeAndHangUp(final ServerSocket server) {
    try (final Socket accepted = server.accept()) {
      accepted.getOutputStream().write("bye".getBytes(StandardCharsets.US_ASCII));
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @Test
  void shouldDisconnectAClientTurnedAway() throws IOException {
    final NetworkGuard guard = this.loopbackGuard();
    final Socket client = this.client(guard);
    final byte[] passwordAnswer = this.exchange(client, new byte[] { 5, 1, 2 }, 2);
    assertArrayEquals(new byte[] { 5, (byte) 0xFF }, passwordAnswer);

    this.assertEnded(client);
  }

  @Test
  void shouldFreeTheSlotOfAConnectionWhenItEnds() throws IOException {
    final NetworkGuard guard = this.loopbackGuard();
    for (int count = 0; count < NetworkGuard.MAXIMUM_CONNECTIONS + 44; count++) {
      new Socket(LOOPBACK, guard.getPort()).close();
    }
    final Socket client = this.client(guard);
    final byte[] greetingAnswer = this.exchange(client, GREETING, 2);
    assertArrayEquals(new byte[] { 5, 0 }, greetingAnswer);
  }

  @Test
  void shouldNotKeepTheJvmAliveWithTheThreadOfTheGuard() throws IOException {
    this.loopbackGuard();
    final List<Thread> threads = Thread.getAllStackTraces()
      .keySet()
      .stream()
      .filter(thread -> thread.getName().equals("mcav-browser-guard"))
      .toList();
    final boolean empty = threads.isEmpty();
    assertFalse(empty);

    for (final Thread thread : threads) {
      final boolean daemon = thread.isDaemon();
      assertTrue(daemon);
    }
  }

  @Test
  void shouldLeaveNoFileOpenWhenAConnectionFails() throws IOException {
    final int closedPort;
    try (final ServerSocket closed = new ServerSocket(0, 1, LOOPBACK)) {
      closedPort = closed.getLocalPort();
    }
    final InetSocketAddress refusing = new InetSocketAddress(LOOPBACK, closedPort);
    OpenFiles.leaveNoneOpen("a failed connection", () -> assertThrows(ConnectException.class, () -> NetworkGuard.connect(refusing)));
  }

  @Test
  void shouldEndEveryConnectionAndStopListeningWhenTheGuardCloses() throws IOException {
    final NetworkGuard guard = this.loopbackGuard();
    final Socket client = this.connectThrough(guard, ECHO_HOST, this.echo.getLocalPort());
    guard.close();
    this.assertEnded(client);
    Await.until("the target connection ended", () -> this.echoEnded.get() == 1);
    // the socket itself, not a connection attempt: another process of the machine may take the freed port at once
    final boolean closed = guard.getServer().isClosed();
    assertTrue(closed, "the guard stopped listening");
    assertEquals(List.of(), this.notices);
  }

  @Test
  void shouldReportABrokenListeningSocket() throws IOException {
    final NetworkGuard guard = this.loopbackGuard();
    guard.getServer().close();
    Await.until("the report", () -> !this.notices.isEmpty());
    final boolean reportsTheStop = this.notices.getFirst().startsWith("The network guard stopped (");
    assertTrue(reportsTheStop, this.notices.getFirst());
  }

  @Test
  void shouldAskForTheNat64PrefixesOnceAnIpv6AddressIsJudged() throws UnknownHostException {
    final TranslatingResolver resolver = new TranslatingResolver(0);
    final NetworkGuard.PublicAddresses policy = new NetworkGuard.PublicAddresses(resolver);
    final boolean publicIpv4Allowed = policy.test(InetAddress.getByName(PUBLIC_IPV4));
    assertTrue(publicIpv4Allowed);

    final boolean privateIpv4Allowed = policy.test(InetAddress.getByName("10.0.0.1"));
    assertFalse(privateIpv4Allowed);

    final int questionsBeforeIpv6 = resolver.questions.get();
    assertEquals(0, questionsBeforeIpv6, "an IPv4 address needs no prefix");

    final boolean translatedPrivateAllowed = policy.test(InetAddress.getByName(TRANSLATED_PRIVATE_IPV6));
    assertFalse(translatedPrivateAllowed, "10.0.0.1 through the translator");

    final boolean translatedPublicAllowed = policy.test(InetAddress.getByName("2a01:4f8:1:2:3:4:808:808"));
    assertTrue(translatedPublicAllowed, "8.8.8.8 through the translator");

    final boolean publicIpv6Allowed = policy.test(InetAddress.getByName("2606:4700:4700::1111"));
    assertTrue(publicIpv6Allowed);

    final boolean uniqueLocalAllowed = policy.test(InetAddress.getByName("fd00::1"));
    assertFalse(uniqueLocalAllowed);

    final int questionCount = resolver.questions.get();
    assertEquals(1, questionCount, "the answer is kept");
  }

  @Test
  void shouldAskAgainWhileTheResolverFails() throws UnknownHostException {
    final TranslatingResolver resolver = new TranslatingResolver(1);
    final NetworkGuard.PublicAddresses policy = new NetworkGuard.PublicAddresses(resolver);
    final InetAddress translatedPrivate = InetAddress.getByName(TRANSLATED_PRIVATE_IPV6);
    final @Nullable List<AddressPolicy.TranslationPrefix> prefixes = policy.getPrefixes();
    assertNull(prefixes, "no prefixes are known while the resolver fails");

    final boolean firstAllowed = policy.test(translatedPrivate);
    assertFalse(firstAllowed);

    final boolean secondAllowed = policy.test(translatedPrivate);
    assertFalse(secondAllowed);

    final int questionCount = resolver.questions.get();
    assertEquals(2, questionCount);
  }

  @Test
  void shouldRefuseAnIpv6AddressWhileTheNat64PrefixesAreUnknown() throws UnknownHostException {
    final TranslatingResolver resolver = new TranslatingResolver(2);
    final NetworkGuard.PublicAddresses policy = new NetworkGuard.PublicAddresses(resolver);
    final boolean translatedPrivateAllowed = policy.test(InetAddress.getByName(TRANSLATED_PRIVATE_IPV6));
    assertFalse(translatedPrivateAllowed, "10.0.0.1 through the translator");

    final boolean ipv6WithoutPrefixesAllowed = policy.test(InetAddress.getByName("2606:4700:4700::1111"));
    assertFalse(ipv6WithoutPrefixesAllowed, "any IPv6 address may be translated");

    final boolean publicIpv4Allowed = policy.test(InetAddress.getByName(PUBLIC_IPV4));
    assertTrue(publicIpv4Allowed, "an IPv4 address needs no prefix");

    final boolean ipv6WithPrefixesAllowed = policy.test(InetAddress.getByName("2606:4700:4700::1111"));
    assertTrue(ipv6WithPrefixesAllowed, "the resolver answers the third question");

    final int questionCount = resolver.questions.get();
    assertEquals(3, questionCount);
  }

  @Test
  void shouldRefuseAnAddressOfThisMachineHoweverPublicItIs() throws UnknownHostException {
    final InetAddress ownIpv4 = InetAddress.getByName(PUBLIC_IPV4);
    final InetAddress ownIpv6 = InetAddress.getByName("2606:4700:4700::1111");
    final Set<InetAddress> own = Set.of(ownIpv4, ownIpv6);
    final NetworkGuard.PublicAddresses policy = new NetworkGuard.PublicAddresses(new TranslatingResolver(0), own::contains);
    final boolean ownIpv4Allowed = policy.test(ownIpv4);
    assertFalse(ownIpv4Allowed);

    final boolean ownIpv6Allowed = policy.test(ownIpv6);
    assertFalse(ownIpv6Allowed);

    final boolean publicIpv4Allowed = policy.test(InetAddress.getByName("1.1.1.1"));
    assertTrue(publicIpv4Allowed);

    final boolean publicIpv6Allowed = policy.test(InetAddress.getByName("2606:4700:4700::1001"));
    assertTrue(publicIpv6Allowed);

    final boolean privateIpv4Allowed = policy.test(InetAddress.getByName("10.0.0.1"));
    assertFalse(privateIpv4Allowed, "a private address stays refused");
  }

  @Test
  void shouldCountAnAddressOfAnInterfaceOfThisMachineAsItsOwn() throws UnknownHostException {
    final boolean loopbackOwn = NetworkGuard.isOwnAddress(InetAddress.getLoopbackAddress());
    assertTrue(loopbackOwn);

    final boolean documentationAddressOwn = NetworkGuard.isOwnAddress(InetAddress.getByName("192.0.2.1"));
    assertFalse(documentationAddressOwn, "an address of the documentation");
  }

  @Test
  void shouldCountAnAddressAsOwnWhileTheInterfacesCannotBeListed() throws UnknownHostException {
    final InetAddress address = InetAddress.getByName("192.0.2.1");
    final boolean ownWhenListingFails = NetworkGuard.isOwnAddress(address, _ -> this.failToListInterfaces());
    assertTrue(ownWhenListingFails);

    final boolean ownWithoutInterfaces = NetworkGuard.isOwnAddress(address, candidate -> null);
    assertFalse(ownWithoutInterfaces);
  }

  private NetworkInterface failToListInterfaces() throws SocketException {
    throw new SocketException("no interfaces");
  }

  @Test
  void shouldRefuseANameOfAPublicAddressOfThisMachine() throws IOException {
    final NetworkGuard guard = NetworkGuard.start(
      host -> new InetAddress[] { InetAddress.getByName(PUBLIC_IPV4) },
      new NetworkGuard.PublicAddresses(new TranslatingResolver(0), address -> true),
      this::failConnecting,
      this.notices::add,
      CONNECT_TIMEOUT_MILLISECONDS
    );
    this.closeables.add(guard);
    final Socket client = this.client(guard);
    this.exchange(client, GREETING, 2);
    final byte[] answer = this.exchange(
      client,
      SocksProtocolTest.domainRequest(SocksProtocol.CONNECT, "server.example", 8080),
      REPLY_BYTES
    );
    assertArrayEquals(this.reply(SocksProtocol.NOT_ALLOWED), answer);
    assertEquals(List.of("The connection to server.example was refused, because it is not a public address."), this.notices);
  }

  private Socket failConnecting(final InetSocketAddress address) {
    final String message = "The guard connected to %s, which it must not reach!".formatted(address);
    throw new AssertionError(message);
  }

  @Test
  void shouldRefuseANameThatTheTranslatorOfTheNetworkTurnsIntoAPrivateAddress() throws IOException {
    final TranslatingResolver resolver = new TranslatingResolver(0);
    final NetworkGuard guard = NetworkGuard.start(
      resolver,
      new NetworkGuard.PublicAddresses(resolver),
      this::failConnecting,
      this.notices::add,
      CONNECT_TIMEOUT_MILLISECONDS
    );
    this.closeables.add(guard);
    final Socket client = this.client(guard);
    this.exchange(client, GREETING, 2);
    final byte[] answer = this.exchange(
      client,
      SocksProtocolTest.domainRequest(SocksProtocol.CONNECT, "internal.test", HTTP_PORT),
      REPLY_BYTES
    );
    assertArrayEquals(this.reply(SocksProtocol.NOT_ALLOWED), answer);
    assertEquals(List.of("The connection to internal.test was refused, because it is not a public address."), this.notices);
  }

  @Test
  void shouldResolveTheRefusedHostsOnceAndReportOneWithoutAnAddress() throws UnknownHostException {
    final InetAddress first = InetAddress.getByName("203.0.113.5");
    final InetAddress second = InetAddress.getByName("2001:db8::5");
    final NetworkGuard.Resolver resolver = host -> this.resolveUnlessMissing(host, first, second);
    final Set<InetAddress> refused = NetworkGuard.resolveAll(resolver, List.of("mc.example.com", "missing.example"), this.notices::add);
    assertEquals(Set.of(first, second), refused);
    assertEquals(List.of("The refused host missing.example has no address, so pages are not kept from it."), this.notices);
  }

  private InetAddress[] resolveUnlessMissing(final String host, final InetAddress first, final InetAddress second)
    throws UnknownHostException {
    if (host.equals("missing.example")) {
      throw new UnknownHostException(host);
    }

    return new InetAddress[] { first, second };
  }

  @Test
  void shouldCountAnAddressOfARefusedHostAsTheMachinesOwn() throws UnknownHostException {
    final InetAddress refusedAddress = InetAddress.getByName("203.0.113.5");
    final InetAddress interfaceAddress = InetAddress.getByName("198.51.100.7");
    final Predicate<InetAddress> own = NetworkGuard.ownOrRefused(interfaceAddress::equals, Set.of(refusedAddress));
    final boolean refusedAddressOwn = own.test(refusedAddress);
    assertTrue(refusedAddressOwn);

    final boolean interfaceAddressOwn = own.test(interfaceAddress);
    assertTrue(interfaceAddressOwn);

    final boolean publicAddressOwn = own.test(InetAddress.getByName("1.1.1.1"));
    assertFalse(publicAddressOwn);
  }

  @Test
  void shouldRefuseTheAddressesOfTheRefusedHostsByDefault() throws IOException {
    try (final NetworkGuard guard = NetworkGuard.start(this.notices::add, List.of(PUBLIC_IPV4))) {
      final Socket client = this.client(guard);
      this.exchange(client, GREETING, 2);
      final byte[] answer = this.exchange(client, SocksProtocolTest.domainRequest(SocksProtocol.CONNECT, PUBLIC_IPV4, 53), REPLY_BYTES);
      assertArrayEquals(this.reply(SocksProtocol.NOT_ALLOWED), answer);
      assertEquals(List.of("The connection to 8.8.8.8 was refused, because it is not a public address."), this.notices);
    }
  }

  @Test
  void shouldRefuseLoopbackByDefault() throws IOException {
    try (final NetworkGuard guard = NetworkGuard.start(this.notices::add, List.of())) {
      final Socket client = this.client(guard);
      this.exchange(client, GREETING, 2);
      final byte[] answer = this.exchange(
        client,
        SocksProtocolTest.domainRequest(SocksProtocol.CONNECT, "localhost", this.echo.getLocalPort()),
        REPLY_BYTES
      );
      assertArrayEquals(this.reply(SocksProtocol.NOT_ALLOWED), answer);
    }
    assertEquals(List.of("The connection to localhost was refused, because it is not a public address."), this.notices);
  }

  @Test
  void shouldCloseTheSocketOfAFailedAttemptWhenConnecting() throws IOException {
    final int closedPort;
    try (final ServerSocket closed = new ServerSocket(0, 1, LOOPBACK)) {
      closedPort = closed.getLocalPort();
    }
    assertThrows(ConnectException.class, () -> NetworkGuard.connect(new InetSocketAddress(LOOPBACK, closedPort)));

    try (final Socket socket = NetworkGuard.connect(new InetSocketAddress(LOOPBACK, this.echo.getLocalPort()))) {
      final boolean connected = socket.isConnected();
      assertTrue(connected);
    }

    final ConnectException refusal = new ConnectException("fixture refused connection");
    final MockedConstruction.MockInitializer<Socket> refusingSockets = (socket, _) -> this.refuseConnecting(socket, refusal);
    try (final MockedConstruction<Socket> sockets = Mockito.mockConstruction(Socket.class, refusingSockets)) {
      final ConnectException thrown = assertThrows(ConnectException.class, () -> this.connectToLoopback(closedPort));
      assertSame(refusal, thrown);

      final int socketCount = sockets.constructed().size();
      assertEquals(1, socketCount);
      Mockito.verify(sockets.constructed().getFirst()).close();
    }
  }

  private void refuseConnecting(final Socket socket, final ConnectException refusal) throws IOException {
    Mockito.doThrow(refusal).when(socket).connect(ArgumentMatchers.any(), ArgumentMatchers.anyInt());
  }

  private Socket connectToLoopback(final int port) throws IOException {
    return NetworkGuard.connect(new InetSocketAddress(LOOPBACK, port));
  }

  @Test
  void shouldSwallowAFailureWhenClosingQuietly() {
    final AtomicInteger closed = new AtomicInteger();
    NetworkGuard.closeQuietly(() -> this.countAndFailClosing(closed));
    final int closeCount = closed.get();
    assertEquals(1, closeCount);
  }

  private void countAndFailClosing(final AtomicInteger closed) throws IOException {
    closed.incrementAndGet();
    throw new IOException("already gone");
  }

  private static final class TranslatingResolver implements NetworkGuard.Resolver {

    private final AtomicInteger questions = new AtomicInteger();
    private final AtomicInteger failuresLeft;

    TranslatingResolver(final int failures) {
      this.failuresLeft = new AtomicInteger(failures);
    }

    @Override
    public InetAddress[] resolve(final String host) throws UnknownHostException {
      if (!host.equals(AddressPolicy.IPV4_ONLY_HOST)) {
        return new InetAddress[] { InetAddress.getByName(TRANSLATED_PRIVATE_IPV6) };
      }

      this.questions.incrementAndGet();

      if (this.failuresLeft.getAndDecrement() > 0) {
        throw new UnknownHostException(host);
      }

      return new InetAddress[] {
        InetAddress.getByName("192.0.0.170"),
        InetAddress.getByName("2a01:4f8:1:2:3:4:c000:aa"),
        InetAddress.getByName("2a01:4f8:1:2:3:4:c000:ab"),
      };
    }
  }
}
