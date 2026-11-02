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

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.checkerframework.checker.nullness.qual.Nullable;

// A SOCKS 5 proxy through which the browser makes every connection. Chromium sends host names, so the guard resolves
// them itself and connects to the very address it checked. Besides private addresses it refuses this machine's own,
// whose services a firewall hides from the internet but not from the machine, and addresses a NAT64 translator of the
// network turns private.
final class NetworkGuard implements Closeable {

  static final int MAXIMUM_CONNECTIONS = 256;

  static final int MAXIMUM_REPORTED_HOSTS = 64;

  private static final int HANDSHAKE_TIMEOUT_MILLISECONDS = 10_000;
  private static final int CONNECT_TIMEOUT_MILLISECONDS = 10_000;

  private static final int BACKLOG = 64;

  private static final int BUFFER_BYTES = 16 * 1024;

  private final ServerSocket server;
  private final Resolver resolver;
  private final Predicate<InetAddress> policy;
  private final Connector connector;
  private final Consumer<String> notices;
  private final int handshakeTimeoutMilliseconds;
  private final Semaphore slots;
  private final Set<Socket> sockets;
  private final Set<String> reported;

  private volatile boolean closed;

  private NetworkGuard(
    final ServerSocket server,
    final Resolver resolver,
    final Predicate<InetAddress> policy,
    final Connector connector,
    final Consumer<String> notices,
    final int handshakeTimeoutMilliseconds
  ) {
    this.server = server;
    this.resolver = resolver;
    this.policy = policy;
    this.connector = connector;
    this.notices = notices;
    this.handshakeTimeoutMilliseconds = handshakeTimeoutMilliseconds;
    this.slots = new Semaphore(MAXIMUM_CONNECTIONS);
    this.sockets = ConcurrentHashMap.newKeySet();
    this.reported = ConcurrentHashMap.newKeySet();
  }

  static NetworkGuard start(final Consumer<String> notices, final List<String> refusedHosts) throws IOException {
    final Resolver resolver = InetAddress::getAllByName;
    final Set<InetAddress> refused = resolveAll(resolver, refusedHosts, notices);
    final PublicAddresses policy = new PublicAddresses(resolver, ownOrRefused(NetworkGuard::isOwnAddress, refused));
    return start(resolver, policy, NetworkGuard::connect, notices, HANDSHAKE_TIMEOUT_MILLISECONDS);
  }

  static NetworkGuard start(
    final Resolver resolver,
    final Predicate<InetAddress> policy,
    final Connector connector,
    final Consumer<String> notices,
    final int handshakeTimeoutMilliseconds
  ) throws IOException {
    final ServerSocket server = new ServerSocket();
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    server.bind(new InetSocketAddress(loopback, 0), BACKLOG);
    final NetworkGuard guard = new NetworkGuard(server, resolver, policy, connector, notices, handshakeTimeoutMilliseconds);
    final Thread thread = new Thread(guard::acceptConnections, "mcav-browser-guard");
    thread.setDaemon(true);
    thread.start();

    return guard;
  }

  static Predicate<InetAddress> ownOrRefused(final Predicate<InetAddress> own, final Set<InetAddress> refused) {
    return address -> own.test(address) || refused.contains(address);
  }

  // a host without an address now is left out, so its later addresses are not refused
  static Set<InetAddress> resolveAll(final Resolver resolver, final List<String> hosts, final Consumer<String> notices) {
    final Set<InetAddress> addresses = new HashSet<>();
    for (final String host : hosts) {
      try {
        addresses.addAll(Arrays.asList(resolver.resolve(host)));
      } catch (final UnknownHostException exception) {
        notices.accept("The refused host %s has no address, so pages are not kept from it.".formatted(host));
      }
    }

    return addresses;
  }

  private void acceptConnections() {
    while (true) {
      final Socket client;
      try {
        client = this.server.accept();
      } catch (final IOException exception) {
        this.reportStopUnlessClosed(exception);
        return;
      }
      if (!this.slots.tryAcquire()) {
        closeQuietly(client);
        continue;
      }
      this.sockets.add(client);
      Thread.ofVirtual()
        .name("mcav-browser-guard-connection")
        .start(() -> this.serveAndRelease(client));
    }
  }

  private void reportStopUnlessClosed(final IOException exception) {
    if (!this.closed) {
      final @Nullable String reason = exception.getMessage();
      this.notices.accept("The network guard stopped after the error %s, so pages cannot connect anymore.".formatted(reason));
    }
  }

  private void serveAndRelease(final Socket client) {
    try {
      this.serve(client);
    } finally {
      this.sockets.remove(client);
      closeQuietly(client);
      this.slots.release();
    }
  }

  private void serve(final Socket client) {
    // a thread of its own, so a busy common pool cannot delay closing an expired handshake
    final Executor later = CompletableFuture.delayedExecutor(this.handshakeTimeoutMilliseconds, TimeUnit.MILLISECONDS, this::startDeadline);
    final CompletableFuture<Void> deadline = CompletableFuture.runAsync(() -> closeQuietly(client), later);
    try {
      final InputStream rawInput = client.getInputStream();
      final DataInputStream input = new DataInputStream(new BufferedInputStream(rawInput));
      final OutputStream rawOutput = client.getOutputStream();
      final DataOutputStream output = new DataOutputStream(rawOutput);
      final boolean withoutAuthentication = SocksProtocol.readOffersNoAuthentication(input);
      if (!withoutAuthentication) {
        SocksProtocol.writeMethod(output, SocksProtocol.NO_ACCEPTABLE_METHOD);
        return;
      }

      SocksProtocol.writeMethod(output, SocksProtocol.NO_AUTHENTICATION);
      final SocksProtocol.Request request;
      try {
        request = SocksProtocol.readRequest(input);
      } catch (final SocksProtocol.Refusal exception) {
        final int reply = exception.getReply();
        SocksProtocol.writeReply(output, reply);
        return;
      }
      deadline.cancel(false);
      final Socket target = this.open(request, output);
      if (target == null) {
        return;
      }

      this.sockets.add(target);
      try {
        SocksProtocol.writeReply(output, SocksProtocol.SUCCEEDED);
        this.relay(input, client, target);
      } finally {
        this.sockets.remove(target);
        closeQuietly(target);
      }
    } catch (final IOException exception) {
      // the client broke the protocol, took too long, or went away
    }
  }

  private void startDeadline(final Runnable task) {
    Thread.ofVirtual().name("mcav-browser-guard-deadline").start(task);
  }

  private @Nullable Socket open(final SocksProtocol.Request request, final DataOutputStream output) throws IOException {
    final String host = request.getHost();
    final InetAddress[] addresses;
    try {
      addresses = this.resolver.resolve(host);
    } catch (final UnknownHostException exception) {
      SocksProtocol.writeReply(output, SocksProtocol.HOST_UNREACHABLE);

      return null;
    }

    IOException failure = null;
    for (final InetAddress address : addresses) {
      if (!this.policy.test(address)) {
        continue;
      }
      try {
        final int port = request.getPort();
        return this.connector.connect(new InetSocketAddress(address, port));
      } catch (final IOException exception) {
        failure = exception;
      }
    }

    if (failure == null) {
      this.report(host);
      SocksProtocol.writeReply(output, SocksProtocol.NOT_ALLOWED);
    } else {
      final boolean refused = failure instanceof ConnectException;
      SocksProtocol.writeReply(output, refused ? SocksProtocol.CONNECTION_REFUSED : SocksProtocol.HOST_UNREACHABLE);
    }

    return null;
  }

  private void report(final String host) {
    final boolean first;
    synchronized (this.reported) {
      first = this.reported.size() < MAXIMUM_REPORTED_HOSTS && this.reported.add(host);
    }

    if (first) {
      this.notices.accept("The connection to %s was refused, because it is not a public address.".formatted(host));
    }
  }

  // the caller closes both sockets
  private void relay(final InputStream fromClient, final Socket client, final Socket target) throws IOException {
    final InputStream fromTarget = target.getInputStream();
    final OutputStream toTarget = target.getOutputStream();
    final OutputStream toClient = client.getOutputStream();
    Thread.ofVirtual()
      .name("mcav-browser-guard-upstream")
      .start(() -> this.pumpUpstream(fromClient, toTarget, target));
    this.pump(fromTarget, toClient);
  }

  private void pumpUpstream(final InputStream fromClient, final OutputStream toTarget, final Socket target) {
    this.pump(fromClient, toTarget);
    closeQuietly(target);
  }

  private void pump(final InputStream input, final OutputStream output) {
    final byte[] buffer = new byte[BUFFER_BYTES];
    try {
      int count = input.read(buffer);
      while (count >= 0) {
        output.write(buffer, 0, count);
        count = input.read(buffer);
      }
    } catch (final IOException exception) {
      // one side ended
    }
  }

  static Socket connect(final InetSocketAddress address) throws IOException {
    final Socket socket = new Socket();
    try {
      socket.connect(address, CONNECT_TIMEOUT_MILLISECONDS);
      return socket;
    } catch (final IOException exception) {
      socket.close();
      throw exception;
    }
  }

  static void closeQuietly(final Closeable closeable) {
    try {
      closeable.close();
    } catch (final IOException exception) {
      // already gone
    }
  }

  @Override
  public void close() {
    this.closed = true;
    closeQuietly(this.server);
    for (final Socket socket : this.sockets) {
      closeQuietly(socket);
    }
  }

  static boolean isOwnAddress(final InetAddress address) {
    return isOwnAddress(address, NetworkInterface::getByInetAddress);
  }

  static boolean isOwnAddress(final InetAddress address, final InterfaceLookup lookup) {
    try {
      return lookup.find(address) != null;
    } catch (final SocketException exception) {
      return true;
    }
  }

  int getPort() {
    return this.server.getLocalPort();
  }

  ServerSocket getServer() {
    return this.server;
  }

  // IPv6 addresses wait for the NAT64 prefixes and are refused while the resolver fails, since any of them may be a
  // private address behind a translator
  static final class PublicAddresses implements Predicate<InetAddress> {

    private final Resolver resolver;
    private final Predicate<InetAddress> own;

    private @Nullable List<AddressPolicy.TranslationPrefix> prefixes;

    PublicAddresses(final Resolver resolver) {
      this(resolver, NetworkGuard::isOwnAddress);
    }

    PublicAddresses(final Resolver resolver, final Predicate<InetAddress> own) {
      this.resolver = resolver;
      this.own = own;
    }

    @Override
    public boolean test(final InetAddress address) {
      final boolean publicAddress;
      if (address instanceof Inet4Address) {
        publicAddress = AddressPolicy.isPublic(address);
      } else {
        final List<AddressPolicy.TranslationPrefix> known = this.findTranslationPrefixes();
        publicAddress = known != null && AddressPolicy.isPublic(address, known);
      }

      return publicAddress && !this.own.test(address);
    }

    synchronized @Nullable List<AddressPolicy.TranslationPrefix> findTranslationPrefixes() {
      final List<AddressPolicy.TranslationPrefix> known = this.prefixes;
      if (known != null) {
        return known;
      }

      final InetAddress[] answer;
      try {
        answer = this.resolver.resolve(AddressPolicy.IPV4_ONLY_HOST);
      } catch (final UnknownHostException exception) {
        return null;
      }

      final List<AddressPolicy.TranslationPrefix> found = AddressPolicy.findTranslationPrefixes(answer);
      this.prefixes = found;
      return found;
    }
  }

  @FunctionalInterface
  interface Resolver {
    InetAddress[] resolve(final String host) throws UnknownHostException;
  }

  @FunctionalInterface
  interface Connector {
    Socket connect(final InetSocketAddress address) throws IOException;
  }

  @FunctionalInterface
  interface InterfaceLookup {
    @Nullable NetworkInterface find(final InetAddress address) throws SocketException;
  }
}
