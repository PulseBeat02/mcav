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
package me.brandonli.mcav.bukkit.resourcepack.provider.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.times;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import io.papermc.paper.network.ChannelInitializeListener;
import io.papermc.paper.network.ChannelInitializeListenerHolder;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import me.brandonli.mcav.bukkit.resourcepack.provider.PackHosting;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.utils.ServerAddress;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link NettyHosting} and {@link InjectorException}.
 */
final class NettyHostingTest {

  private static final byte[] PACK = { 'P', 'K', 3, 4, 8 };

  @TempDir
  private Path directory;

  private FakeServer server;
  private Path zip;

  @BeforeEach
  void startServer() throws IOException {
    this.server = FakeServer.start();
    this.zip = this.directory.resolve("pack.zip");
    Files.write(this.zip, PACK);
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private static int listenerCount() {
    final Map<Key, ChannelInitializeListener> listeners = ChannelInitializeListenerHolder.getListeners();
    return listeners.size();
  }

  private static EmbeddedChannel connect() {
    final EmbeddedChannel channel = new EmbeddedChannel();
    ChannelInitializeListenerHolder.callListeners(channel);
    return channel;
  }

  private static byte[] downloadPack(final EmbeddedChannel channel, final NettyHosting hosting) {
    final String url = hosting.getRawUrl();
    final URI uri = URI.create(url);
    final String path = uri.getRawPath();
    final ByteBuf request = Unpooled.copiedBuffer("GET " + path + " HTTP/1.1\r\n\r\n", StandardCharsets.US_ASCII);
    channel.writeInbound(request);

    final ByteBuf headers = channel.readOutbound();
    headers.release();
    final ByteBuf body = channel.readOutbound();
    try {
      return ByteBufUtil.getBytes(body);
    } finally {
      body.release();
    }
  }

  @Test
  void answersHttpRequestsOnNewConnections() {
    final NettyHosting hosting = new NettyHosting(this.zip);
    hosting.start();

    try {
      final EmbeddedChannel channel = connect();
      final ChannelPipeline pipeline = channel.pipeline();
      final List<String> names = pipeline.names();
      final String firstName = names.getFirst();
      final String handlerName = hosting.getHandlerName();
      final ChannelHandler handler = pipeline.get(handlerName);
      final byte[] bodyBytes = downloadPack(channel, hosting);

      assertEquals(handlerName, firstName, "the handler sees the bytes before Minecraft does");
      assertInstanceOf(ResourcePackHttpHandler.class, handler);
      assertArrayEquals(PACK, bodyBytes);
    } finally {
      hosting.shutdown();
    }
  }

  @Test
  void routesEachUrlToItsOwnPackAcrossFragmentedRequests() throws IOException {
    final byte[] otherBytes = { 'P', 'K', 7, 8, 9 };
    final Path other = this.directory.resolve("other.zip");
    Files.write(other, otherBytes);
    final NettyHosting first = new NettyHosting(this.zip);
    final NettyHosting second = new NettyHosting(other);
    first.start();
    second.start();
    try {
      final String firstUrl = first.getRawUrl();
      final String secondUrl = second.getRawUrl();
      assertNotEquals(firstUrl, secondUrl);
      final List<NettyHosting> hosts = List.of(first, second);
      for (final NettyHosting hosting : hosts) {
        final URI uri = URI.create(hosting.getRawUrl());
        final String request = "GET " + uri.getRawPath() + " HTTP/1.1\r\n\r\n";
        final byte[] bytes = request.getBytes(StandardCharsets.US_ASCII);
        final EmbeddedChannel channel = connect();
        try {
          for (final byte value : bytes) {
            if (!channel.isOpen()) {
              break;
            }
            final ByteBuf part = Unpooled.buffer(1);
            part.writeByte(value);
            channel.writeInbound(part);
          }
          final ByteBuf headers = channel.readOutbound();
          final String text = headers.toString(StandardCharsets.US_ASCII);
          headers.release();
          assertTrue(text.startsWith("HTTP/1.1 200 OK"));
          final ByteBuf body = channel.readOutbound();
          final byte[] received = ByteBufUtil.getBytes(body);
          body.release();
          final Path path = hosting.getZip();
          final byte[] expected = Files.readAllBytes(path);
          assertArrayEquals(expected, received);
        } finally {
          channel.finishAndReleaseAll();
        }
      }
    } finally {
      first.shutdown();
      second.shutdown();
    }
  }

  @Test
  void registersOneListenerPerRunningInstance() {
    final int before = listenerCount();
    final NettyHosting first = new NettyHosting(this.zip);
    final NettyHosting second = new NettyHosting(this.zip);

    first.start();
    first.start();
    second.start();
    final int running = listenerCount();
    first.shutdown();
    first.shutdown();
    final int afterFirstShutdown = listenerCount();
    second.shutdown();
    final int after = listenerCount();

    assertEquals(before + 2, running, "starting twice registers one listener, and every instance has its own");
    assertEquals(before + 1, afterFirstShutdown);
    assertEquals(before, after);
  }

  @Test
  void givesEveryInstanceItsOwnPipelineName() {
    // a Netty pipeline rejects two handlers of the same name. With a shared constant, a second running instance
    // threw inside the channel initializer, which kills every new player connection.
    final NettyHosting first = new NettyHosting(this.zip);
    final NettyHosting second = new NettyHosting(this.zip);

    final String firstName = first.getHandlerName();
    final String secondName = second.getHandlerName();

    assertNotEquals(firstName, secondName, "two instances must not install handlers under one name");
    assertTrue(firstName.startsWith(ResourcePackHttpHandler.NAME), firstName);
    assertTrue(secondName.startsWith(ResourcePackHttpHandler.NAME), secondName);
  }

  @Test
  void stopsAnsweringNewConnectionsWhenShutDown() {
    final NettyHosting hosting = new NettyHosting(this.zip);

    hosting.start();
    final String handlerName = hosting.getHandlerName();
    final EmbeddedChannel before = connect();
    final ChannelPipeline activePipeline = before.pipeline();
    final ChannelHandler active = activePipeline.get(handlerName);
    assertNotNull(active, "the instance handler must be installed before shutdown");
    hosting.shutdown();
    final EmbeddedChannel channel = connect();

    final ChannelPipeline pipeline = channel.pipeline();
    final ChannelHandler handler = pipeline.get(handlerName);
    assertNull(handler);
  }

  @Test
  void readsThePackWhenItStartsSoTheNettyThreadNeverReadsIt() {
    final NettyHosting hosting = new NettyHosting(this.zip);
    try (final MockedStatic<Files> files = Mockito.mockStatic(Files.class, CALLS_REAL_METHODS)) {
      hosting.start();
      files.verify(() -> Files.readAllBytes(this.zip), times(1));

      final EmbeddedChannel channel = connect();
      final byte[] bodyBytes = downloadPack(channel, hosting);

      assertArrayEquals(PACK, bodyBytes);
      files.verify(() -> Files.readAllBytes(this.zip), times(1));
      // the Netty thread only asks whether the file changed; the bytes came from the read that start() did
      files.verify(() -> Files.readAttributes(this.zip, BasicFileAttributes.class), times(2));
    } finally {
      hosting.shutdown();
    }
  }

  @Test
  void startsEvenWhenThePackCannotBeReadAhead() throws IOException {
    final Path unreadable = this.directory.resolve("unreadable.zip");
    Files.write(unreadable, PACK);
    final NettyHosting hosting = new NettyHosting(unreadable);
    try (final MockedStatic<Files> files = Mockito.mockStatic(Files.class, CALLS_REAL_METHODS)) {
      files.when(() -> Files.readAllBytes(unreadable)).thenThrow(new IOException("no permission"));

      hosting.start();

      final EmbeddedChannel channel = connect();
      final ChannelHandler handler = channel.pipeline().get(hosting.getHandlerName());
      assertNotNull(handler, "hosting starts, and the failure is reported when a download is answered");
    } finally {
      hosting.shutdown();
    }
  }

  @Test
  void refusesToStartWithoutAPack() {
    final Path missing = this.directory.resolve("missing.zip");
    final NettyHosting hosting = new NettyHosting(missing);
    final int before = listenerCount();

    final InjectorException exception = assertThrows(InjectorException.class, hosting::start);

    final String message = exception.getMessage();
    final int after = listenerCount();
    assertEquals("Resource pack does not exist: " + missing, message);
    assertEquals(before, after);
  }

  @Test
  void buildsTheUrlFromThePublicAddressAndTheMinecraftPortOnce() {
    final MockedStatic<Bukkit> bukkit = this.server.getBukkit();
    bukkit.when(Bukkit::getIp).thenReturn("203.0.113.9");
    bukkit.when(Bukkit::getPort).thenReturn(25570);
    final NettyHosting hosting = new NettyHosting(this.zip);

    final String url = hosting.getRawUrl();
    bukkit.when(Bukkit::getIp).thenReturn("198.51.100.1");
    final String cachedUrl = hosting.getRawUrl();

    assertTrue(url.startsWith("http://203.0.113.9:25570/mcav/resourcepack_"));
    assertEquals(url, cachedUrl);
  }

  @Test
  void enclosesIpv6AddressesInBrackets() {
    final MockedStatic<Bukkit> bukkit = this.server.getBukkit();
    bukkit.when(Bukkit::getIp).thenReturn("2001:db8::5");
    bukkit.when(Bukkit::getPort).thenReturn(25570);
    final NettyHosting hosting = new NettyHosting(this.zip);

    final String url = hosting.getRawUrl();
    final URI uri = URI.create(url);
    final int port = uri.getPort();

    assertTrue(url.startsWith("http://[2001:db8::5]:25570/mcav/resourcepack_"));
    assertEquals(25570, port, "the URL can be parsed");
  }

  @Test
  void formatsOnlyIpv6AddressesAsBracketedHosts() {
    final String ipv6 = NettyHosting.formatHost("::1");
    final String ipv4 = NettyHosting.formatHost("203.0.113.9");
    final String hostName = NettyHosting.formatHost("example.org");
    final String fallback = NettyHosting.formatHost("localhost");

    assertEquals("[::1]", ipv6);
    assertEquals("203.0.113.9", ipv4);
    assertEquals("example.org", hostName);
    assertEquals("localhost", fallback);
  }

  @Test
  void buildsTheUrlAgainWhileThePublicAddressIsUnknown() {
    try (final MockedStatic<ServerAddress> serverAddress = Mockito.mockStatic(ServerAddress.class, Mockito.CALLS_REAL_METHODS)) {
      serverAddress.when(ServerAddress::getPublicIPAddress).thenReturn("localhost", "198.51.100.7", "203.0.113.1");
      final NettyHosting hosting = new NettyHosting(this.zip);

      final String fallbackUrl = hosting.getRawUrl();
      final String resolvedUrl = hosting.getRawUrl();
      final String cachedUrl = hosting.getRawUrl();

      assertTrue(fallbackUrl.startsWith("http://localhost:25565/mcav/resourcepack_"));
      assertTrue(resolvedUrl.startsWith("http://198.51.100.7:25565/mcav/resourcepack_"), "the fallback is not cached");
      assertEquals(resolvedUrl, cachedUrl, "a resolved address is cached");
    }
  }

  @Test
  void isCreatedByTheInjectorFactory() {
    final InjectorHosting hosting = PackHosting.injector(this.zip);

    final Path hostedZip = hosting.getZip();

    assertInstanceOf(NettyHosting.class, hosting);
    assertSame(this.zip, hostedZip);
    assertThrows(NullPointerException.class, () -> PackHosting.injector(null));
    assertThrows(NullPointerException.class, () -> new NettyHosting(null));
  }

  @Test
  void exceptionsKeepTheMessage() {
    final InjectorException exception = new InjectorException("message");

    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();

    assertEquals("message", message);
    assertNull(cause);
  }

  @Test
  void exceptionsKeepTheMessageAndTheCause() {
    final IOException failure = new IOException("cause");
    final InjectorException exception = new InjectorException("message", failure);

    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();

    assertEquals("message", message);
    assertSame(failure, cause);
  }

  @Test
  void exceptionsAreIllegalStateExceptions() {
    final InjectorException exception = new InjectorException("message");
    assertInstanceOf(IllegalStateException.class, exception, "a missing pack is a state of the server, so it is no Error");
  }
}
