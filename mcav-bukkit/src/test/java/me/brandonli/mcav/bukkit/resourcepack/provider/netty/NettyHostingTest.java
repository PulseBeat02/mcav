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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  private static byte[] downloadPack(final EmbeddedChannel channel) {
    final ByteBuf request = Unpooled.copiedBuffer("GET / HTTP/1.1\r\n\r\n", StandardCharsets.US_ASCII);
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
      final byte[] bodyBytes = downloadPack(channel);

      assertEquals(handlerName, firstName, "the handler sees the bytes before Minecraft does");
      assertInstanceOf(ResourcePackHttpHandler.class, handler);
      assertArrayEquals(PACK, bodyBytes);
    } finally {
      hosting.shutdown();
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
    hosting.shutdown();
    final EmbeddedChannel channel = connect();

    final ChannelPipeline pipeline = channel.pipeline();
    final ChannelHandler handler = pipeline.get(ResourcePackHttpHandler.NAME);
    assertNull(handler);
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

    assertEquals("http://203.0.113.9:25570", url);
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

    assertEquals("http://[2001:db8::5]:25570", url);
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

      assertEquals("http://localhost:25565", fallbackUrl);
      assertEquals("http://198.51.100.7:25565", resolvedUrl, "the fallback is not cached");
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
