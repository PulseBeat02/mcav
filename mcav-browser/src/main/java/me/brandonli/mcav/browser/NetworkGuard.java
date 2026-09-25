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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A SOCKS 5 proxy on the loopback interface of the helper, through which the browser makes every connection while
 * pages may reach public addresses only.
 *
 * <p>Chromium hands the proxy the host name, not an address, so the guard resolves the name itself and connects only
 * to an address the policy allows, the very address it checked: a name that resolves to a private address, or a name
 * whose answer changes between two lookups, does not lead past it. Loopback goes through the proxy too, WebRTC may
 * only use proxied connections and QUIC is off (see {@link CefEngine#createSwitches}), so no connection of a page
 * leaves the helper around the guard.
 *
 * <p>The guard holds at most {@value #MAX_CONNECTIONS} connections, closes a client that has not finished its
 * handshake in time, and closes both sides of a connection as soon as one side ends. Each refused host is reported
 * once, up to {@value #MAX_REPORTED_HOSTS} hosts.
 */
final class NetworkGuard implements Closeable {

  static final int MAX_CONNECTIONS = 256;
  static final int MAX_REPORTED_HOSTS = 64;
  static final int HANDSHAKE_TIMEOUT_MILLIS = 10_000;
  static final int CONNECT_TIMEOUT_MILLIS = 10_000;
  private static final int BACKLOG = 64;
  private static final int BUFFER_BYTES = 16 * 1024;

  private final ServerSocket server;
  private final Resolver resolver;
  private final Predicate<InetAddress> policy;
  private final Connector connector;
  private final Consumer<String> notices;
  private final int handshakeTimeoutMillis;
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
    final int handshakeTimeoutMillis
  ) {
    this.server = server;
    this.resolver = resolver;
    this.policy = policy;
    this.connector = connector;
    this.notices = notices;
    this.handshakeTimeoutMillis = handshakeTimeoutMillis;
    this.slots = new Semaphore(MAX_CONNECTIONS);
    this.sockets = ConcurrentHashMap.newKeySet();
    this.reported = ConcurrentHashMap.newKeySet();
  }

  /**
   * Starts a guard that lets pages reach public addresses only.
   *
   * @param notices receives a line for every refused host
   * @return the running guard
   * @throws IOException if no port of the loopback interface can be bound
   */
  static NetworkGuard start(final Consumer<String> notices) throws IOException {
    return start(InetAddress::getAllByName, AddressPolicy::isPublic, NetworkGuard::connect, notices, HANDSHAKE_TIMEOUT_MILLIS);
  }

  /**
   * Starts a guard with another resolver, policy, connector and handshake timeout, for tests.
   *
   * @param resolver               resolves host names
   * @param policy                 decides which addresses may be reached
   * @param connector              connects to an allowed address
   * @param notices                receives a line for every refused host
   * @param handshakeTimeoutMillis how long a client may take for its handshake
   * @return the running guard
   * @throws IOException if no port of the loopback interface can be bound
   */
  static NetworkGuard start(
    final Resolver resolver,
    final Predicate<InetAddress> policy,
    final Connector connector,
    final Consumer<String> notices,
    final int handshakeTimeoutMillis
  ) throws IOException {
    final ServerSocket server = new ServerSocket();
    final InetAddress loopback = InetAddress.getLoopbackAddress();
    server.bind(new InetSocketAddress(loopback, 0), BACKLOG);
    final NetworkGuard guard = new NetworkGuard(server, resolver, policy, connector, notices, handshakeTimeoutMillis);
    final Thread thread = new Thread(guard::acceptConnections, "mcav-browser-guard");
    thread.setDaemon(true);
    thread.start();
    return guard;
  }

  /**
   * Gets the port the guard listens on.
   *
   * @return the port on the loopback interface
   */
  int getPort() {
    return this.server.getLocalPort();
  }

  private void acceptConnections() {
    while (true) {
      final Socket client;
      try {
        client = this.server.accept();
      } catch (final IOException exception) {
        // the guard was closed, or the helper ran out of sockets; either way no page gets past it any more
        if (!this.closed) {
          this.notices.accept("The network guard stopped: " + exception.getMessage());
        }
        return;
      }
      if (!this.slots.tryAcquire()) {
        closeQuietly(client);
        continue;
      }
      this.sockets.add(client);
      Thread.ofVirtual()
        .name("mcav-browser-guard-connection")
        .start(() -> {
          try {
            this.serve(client);
          } finally {
            this.sockets.remove(client);
            closeQuietly(client);
            this.slots.release();
          }
        });
    }
  }

  /**
   * Serves one client: the handshake, the connection to the target if the policy allows it, and then the relay until
   * one side ends.
   *
   * @param client the client
   */
  private void serve(final Socket client) {
    try {
      client.setSoTimeout(this.handshakeTimeoutMillis);
      final InputStream rawInput = client.getInputStream();
      final DataInputStream in = new DataInputStream(new BufferedInputStream(rawInput));
      final OutputStream rawOutput = client.getOutputStream();
      final DataOutputStream out = new DataOutputStream(rawOutput);
      final boolean withoutAuthentication = SocksProtocol.readGreeting(in);
      if (!withoutAuthentication) {
        SocksProtocol.writeMethod(out, SocksProtocol.NO_ACCEPTABLE_METHOD);
        return;
      }
      SocksProtocol.writeMethod(out, SocksProtocol.NO_AUTHENTICATION);
      final SocksProtocol.Request request;
      try {
        request = SocksProtocol.readRequest(in);
      } catch (final SocksProtocol.Refusal refusal) {
        SocksProtocol.writeReply(out, refusal.getReply());
        return;
      }
      final Socket target = this.open(request, out);
      if (target == null) {
        return;
      }
      this.sockets.add(target);
      try {
        SocksProtocol.writeReply(out, SocksProtocol.SUCCEEDED);
        client.setSoTimeout(0);
        relay(in, client, target);
      } finally {
        this.sockets.remove(target);
        closeQuietly(target);
      }
    } catch (final IOException exception) {
      // the client broke the protocol, took too long, or went away
    }
  }

  /**
   * Resolves the host of a request and connects to the first address the policy allows, answering the client if
   * that fails.
   *
   * @param request the request
   * @param out     the stream to the client
   * @return the connection, or null if the client got a failure as its answer
   * @throws IOException if the answer cannot be sent
   */
  private @Nullable Socket open(final SocksProtocol.Request request, final DataOutputStream out) throws IOException {
    final String host = request.getHost();
    final InetAddress[] addresses;
    try {
      addresses = this.resolver.resolve(host);
    } catch (final UnknownHostException exception) {
      SocksProtocol.writeReply(out, SocksProtocol.HOST_UNREACHABLE);
      return null;
    }
    IOException failure = null;
    for (final InetAddress address : addresses) {
      if (!this.policy.test(address)) {
        continue;
      }
      try {
        return this.connector.connect(new InetSocketAddress(address, request.getPort()));
      } catch (final IOException exception) {
        failure = exception;
      }
    }
    if (failure == null) {
      this.report(host);
      SocksProtocol.writeReply(out, SocksProtocol.NOT_ALLOWED);
    } else {
      final boolean refused = failure instanceof ConnectException;
      SocksProtocol.writeReply(out, refused ? SocksProtocol.CONNECTION_REFUSED : SocksProtocol.HOST_UNREACHABLE);
    }
    return null;
  }

  private void report(final String host) {
    if (this.reported.size() < MAX_REPORTED_HOSTS && this.reported.add(host)) {
      this.notices.accept("Refused a connection to " + host + ", which is not a public address");
    }
  }

  /**
   * Copies both directions of a connection until one side ends, then closes both.
   *
   * @param fromClient the stream from the client, which may hold bytes the handshake read ahead
   * @param client     the client
   * @param target     the target
   * @throws IOException if the streams of the sockets cannot be opened
   */
  private static void relay(final InputStream fromClient, final Socket client, final Socket target) throws IOException {
    final InputStream fromTarget = target.getInputStream();
    final OutputStream toTarget = target.getOutputStream();
    final OutputStream toClient = client.getOutputStream();
    Thread.ofVirtual()
      .name("mcav-browser-guard-upstream")
      .start(() -> {
        pump(fromClient, toTarget);
        closeQuietly(target);
        closeQuietly(client);
      });
    pump(fromTarget, toClient);
    closeQuietly(client);
    closeQuietly(target);
  }

  private static void pump(final InputStream in, final OutputStream out) {
    final byte[] buffer = new byte[BUFFER_BYTES];
    try {
      int count = in.read(buffer);
      while (count >= 0) {
        out.write(buffer, 0, count);
        out.flush();
        count = in.read(buffer);
      }
    } catch (final IOException exception) {
      // one side ended
    }
  }

  /**
   * Connects to an address, waiting at most {@value #CONNECT_TIMEOUT_MILLIS} ms.
   *
   * @param address the address
   * @return the connection
   * @throws IOException if the connection fails
   */
  static Socket connect(final InetSocketAddress address) throws IOException {
    final Socket socket = new Socket();
    try {
      socket.connect(address, CONNECT_TIMEOUT_MILLIS);
      return socket;
    } catch (final IOException exception) {
      socket.close();
      throw exception;
    }
  }

  /**
   * Closes a socket or stream whose failure to close changes nothing, as its other side is gone or going.
   *
   * @param closeable the socket or stream
   */
  static void closeQuietly(final Closeable closeable) {
    try {
      closeable.close();
    } catch (final IOException exception) {
      // already gone
    }
  }

  /**
   * Gets the socket the guard listens on, so tests can break it.
   *
   * @return the listening socket
   */
  ServerSocket getServer() {
    return this.server;
  }

  /**
   * Stops accepting connections and closes every open connection.
   */
  @Override
  public void close() {
    this.closed = true;
    closeQuietly(this.server);
    for (final Socket socket : this.sockets) {
      closeQuietly(socket);
    }
  }

  /**
   * Resolves host names.
   */
  @FunctionalInterface
  interface Resolver {
    /**
     * Resolves a host name or parses an address.
     *
     * @param host the host
     * @return the addresses, at least one
     * @throws UnknownHostException if the host has no address
     */
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  /**
   * Connects to an address the policy allowed.
   */
  @FunctionalInterface
  interface Connector {
    /**
     * Connects.
     *
     * @param address the address and port
     * @return the connection
     * @throws IOException if the connection fails
     */
    Socket connect(InetSocketAddress address) throws IOException;
  }
}
