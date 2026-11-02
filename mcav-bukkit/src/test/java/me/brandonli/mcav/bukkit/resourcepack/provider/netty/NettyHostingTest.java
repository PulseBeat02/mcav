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
import me.brandonli.mcav.bukkit.BukkitException;
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

final class NettyHostingTest {

  private static final byte[] PACK = { 'P', 'K', 3, 4, 8 };

  private static final String PACK_FILE_NAME = "pack.zip";
  private static final String OTHER_FILE_NAME = "other.zip";
  private static final String UNREADABLE_FILE_NAME = "unreadable.zip";
  private static final String MISSING_FILE_NAME = "missing.zip";

  private static final String GET_PREFIX = "GET ";
  private static final String REQUEST_SUFFIX = " HTTP/1.1\r\n\r\n";

  private static final String PUBLIC_ADDRESS = "203.0.113.9";
  private static final String LOCALHOST = "localhost";
  private static final int SERVER_PORT = 25570;

  private static final String MESSAGE = "Message!";

  @TempDir
  private Path directory;

  private FakeServer server;
  private Path zip;

  @BeforeEach
  void startServer() throws IOException {
    this.server = FakeServer.start();
    this.zip = this.directory.resolve(PACK_FILE_NAME);
    Files.write(this.zip, PACK);
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private int listenerCount() {
    final Map<Key, ChannelInitializeListener> listeners = ChannelInitializeListenerHolder.getListeners();
    return listeners.size();
  }

  private EmbeddedChannel connect() {
    final EmbeddedChannel channel = new EmbeddedChannel();
    ChannelInitializeListenerHolder.callListeners(channel);
    return channel;
  }

  private byte[] downloadPack(final EmbeddedChannel channel, final NettyHosting hosting) {
    final String url = hosting.getRawUrl();
    final URI uri = URI.create(url);
    final String path = uri.getRawPath();
    final ByteBuf request = Unpooled.copiedBuffer(GET_PREFIX + path + REQUEST_SUFFIX, StandardCharsets.US_ASCII);
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
  void shouldAnswerHttpRequestsOnNewConnections() {
    final NettyHosting hosting = new NettyHosting(this.zip);
    hosting.start();

    try {
      final EmbeddedChannel channel = this.connect();
      final ChannelPipeline pipeline = channel.pipeline();
      final List<String> names = pipeline.names();
      final String firstName = names.getFirst();
      final String handlerName = hosting.getHandlerName();
      final ChannelHandler handler = pipeline.get(handlerName);
      final byte[] bodyBytes = this.downloadPack(channel, hosting);

      assertEquals(handlerName, firstName, "the handler sees the bytes before Minecraft does");
      assertInstanceOf(ResourcePackHttpHandler.class, handler);
      assertArrayEquals(PACK, bodyBytes);
    } finally {
      hosting.shutdown();
    }
  }

  @Test
  void shouldRouteEachUrlToItsOwnPackAcrossFragmentedRequests() throws IOException {
    final byte[] otherBytes = { 'P', 'K', 7, 8, 9 };
    final Path other = this.directory.resolve(OTHER_FILE_NAME);
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
        this.assertServesFragmentedRequest(hosting);
      }
    } finally {
      first.shutdown();
      second.shutdown();
    }
  }

  private void assertServesFragmentedRequest(final NettyHosting hosting) throws IOException {
    final URI uri = URI.create(hosting.getRawUrl());
    final String request = GET_PREFIX + uri.getRawPath() + REQUEST_SUFFIX;
    final byte[] bytes = request.getBytes(StandardCharsets.US_ASCII);
    final EmbeddedChannel channel = this.connect();
    try {
      this.writeByteByByte(channel, bytes);

      final ByteBuf headers = channel.readOutbound();
      final String text = headers.toString(StandardCharsets.US_ASCII);
      headers.release();
      final boolean served = text.startsWith("HTTP/1.1 200 OK");
      assertTrue(served);

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

  private void writeByteByByte(final EmbeddedChannel channel, final byte[] bytes) {
    for (final byte value : bytes) {
      if (!channel.isOpen()) {
        return;
      }

      final ByteBuf part = Unpooled.buffer(1);
      part.writeByte(value);
      channel.writeInbound(part);
    }
  }

  @Test
  void shouldRegisterOneListenerPerRunningInstance() {
    final int before = this.listenerCount();
    final NettyHosting first = new NettyHosting(this.zip);
    final NettyHosting second = new NettyHosting(this.zip);

    first.start();
    first.start();
    second.start();
    final int running = this.listenerCount();
    first.shutdown();
    first.shutdown();
    final int afterFirstShutdown = this.listenerCount();
    second.shutdown();
    final int after = this.listenerCount();

    final int expectedRunning = before + 2;
    assertEquals(expectedRunning, running, "starting twice registers one listener, and every instance has its own");

    final int expectedAfterFirstShutdown = before + 1;
    assertEquals(expectedAfterFirstShutdown, afterFirstShutdown);
    assertEquals(before, after);
  }

  @Test
  void shouldGiveEveryInstanceItsOwnPipelineName() {
    final NettyHosting first = new NettyHosting(this.zip);
    final NettyHosting second = new NettyHosting(this.zip);

    final String firstName = first.getHandlerName();
    final String secondName = second.getHandlerName();

    assertNotEquals(firstName, secondName, "two instances must not install handlers under one name");

    final boolean firstNamePrefixed = firstName.startsWith(ResourcePackHttpHandler.NAME);
    assertTrue(firstNamePrefixed, firstName);

    final boolean secondNamePrefixed = secondName.startsWith(ResourcePackHttpHandler.NAME);
    assertTrue(secondNamePrefixed, secondName);
  }

  @Test
  void shouldStopAnsweringNewConnectionsWhenShutDown() {
    final NettyHosting hosting = new NettyHosting(this.zip);

    hosting.start();
    final String handlerName = hosting.getHandlerName();
    final EmbeddedChannel before = this.connect();
    final ChannelPipeline activePipeline = before.pipeline();
    final ChannelHandler active = activePipeline.get(handlerName);
    assertNotNull(active, "the instance handler must be installed before shutdown");

    hosting.shutdown();
    final EmbeddedChannel channel = this.connect();

    final ChannelPipeline pipeline = channel.pipeline();
    final ChannelHandler handler = pipeline.get(handlerName);
    assertNull(handler);
  }

  @Test
  void shouldReadThePackWhenItStartsSoTheNettyThreadNeverReadsIt() {
    final NettyHosting hosting = new NettyHosting(this.zip);
    try (final MockedStatic<Files> files = Mockito.mockStatic(Files.class, CALLS_REAL_METHODS)) {
      hosting.start();
      files.verify(() -> Files.readAllBytes(this.zip), times(1));

      final EmbeddedChannel channel = this.connect();
      final byte[] bodyBytes = this.downloadPack(channel, hosting);

      assertArrayEquals(PACK, bodyBytes);

      files.verify(() -> Files.readAllBytes(this.zip), times(1));
      files.verify(() -> Files.readAttributes(this.zip, BasicFileAttributes.class), times(2));
    } finally {
      hosting.shutdown();
    }
  }

  @Test
  void shouldStartEvenWhenThePackCannotBeReadAhead() throws IOException {
    final Path unreadable = this.directory.resolve(UNREADABLE_FILE_NAME);
    Files.write(unreadable, PACK);
    final NettyHosting hosting = new NettyHosting(unreadable);
    try (final MockedStatic<Files> files = Mockito.mockStatic(Files.class, CALLS_REAL_METHODS)) {
      files.when(() -> Files.readAllBytes(unreadable)).thenThrow(new IOException("No permission!"));

      hosting.start();

      final EmbeddedChannel channel = this.connect();
      final ChannelHandler handler = channel.pipeline().get(hosting.getHandlerName());
      assertNotNull(handler, "hosting starts, and the failure is reported when a download is answered");
    } finally {
      hosting.shutdown();
    }
  }

  @Test
  void shouldRefuseToStartWithoutAPack() {
    final Path missing = this.directory.resolve(MISSING_FILE_NAME);
    final NettyHosting hosting = new NettyHosting(missing);
    final int before = this.listenerCount();

    final InjectorException exception = assertThrows(InjectorException.class, hosting::start);

    final String message = exception.getMessage();
    final int after = this.listenerCount();
    final String expectedMessage = "Resource pack %s is not a regular file!".formatted(missing);
    assertEquals(expectedMessage, message);
    assertEquals(before, after);
  }

  @Test
  void shouldBuildTheUrlFromThePublicAddressAndTheMinecraftPortOnce() {
    final MockedStatic<Bukkit> bukkit = this.server.getBukkit();
    bukkit.when(Bukkit::getIp).thenReturn(PUBLIC_ADDRESS);
    bukkit.when(Bukkit::getPort).thenReturn(SERVER_PORT);
    final NettyHosting hosting = new NettyHosting(this.zip);

    final String url = hosting.getRawUrl();
    bukkit.when(Bukkit::getIp).thenReturn("198.51.100.1");
    final String cachedUrl = hosting.getRawUrl();

    final boolean usesThePublicAddress = url.startsWith("http://203.0.113.9:25570/mcav/resourcepack_");
    assertTrue(usesThePublicAddress);
    assertEquals(url, cachedUrl);
  }

  @Test
  void shouldEncloseIpv6AddressesInBrackets() {
    final MockedStatic<Bukkit> bukkit = this.server.getBukkit();
    bukkit.when(Bukkit::getIp).thenReturn("2001:db8::5");
    bukkit.when(Bukkit::getPort).thenReturn(SERVER_PORT);
    final NettyHosting hosting = new NettyHosting(this.zip);

    final String url = hosting.getRawUrl();
    final URI uri = URI.create(url);
    final int port = uri.getPort();

    final boolean bracketed = url.startsWith("http://[2001:db8::5]:25570/mcav/resourcepack_");
    assertTrue(bracketed);
    assertEquals(SERVER_PORT, port, "the URL can be parsed");
  }

  @Test
  void shouldFormatOnlyIpv6AddressesAsBracketedHosts() {
    final String ipv6 = NettyHosting.formatHost("::1");
    final String ipv4 = NettyHosting.formatHost(PUBLIC_ADDRESS);
    final String hostName = NettyHosting.formatHost("example.org");
    final String fallback = NettyHosting.formatHost(LOCALHOST);

    assertEquals("[::1]", ipv6);
    assertEquals(PUBLIC_ADDRESS, ipv4);
    assertEquals("example.org", hostName);
    assertEquals(LOCALHOST, fallback);
  }

  @Test
  void shouldBuildTheUrlAgainWhileThePublicAddressIsUnknown() {
    try (final MockedStatic<ServerAddress> serverAddress = Mockito.mockStatic(ServerAddress.class, Mockito.CALLS_REAL_METHODS)) {
      serverAddress.when(ServerAddress::getPublicAddress).thenReturn(LOCALHOST, "198.51.100.7", "203.0.113.1");
      final NettyHosting hosting = new NettyHosting(this.zip);

      final String fallbackUrl = hosting.getRawUrl();
      final String resolvedUrl = hosting.getRawUrl();
      final String cachedUrl = hosting.getRawUrl();

      final boolean fallsBackToLocalhost = fallbackUrl.startsWith("http://localhost:25565/mcav/resourcepack_");
      assertTrue(fallsBackToLocalhost);

      final boolean usesTheResolvedAddress = resolvedUrl.startsWith("http://198.51.100.7:25565/mcav/resourcepack_");
      assertTrue(usesTheResolvedAddress, "the fallback is not cached");
      assertEquals(resolvedUrl, cachedUrl, "a resolved address is cached");
    }
  }

  @Test
  void shouldBeCreatedByTheInjectorFactory() {
    final InjectorHosting hosting = PackHosting.injector(this.zip);

    final Path hostedZip = hosting.getZip();

    assertInstanceOf(NettyHosting.class, hosting);
    assertSame(this.zip, hostedZip);
    assertThrows(NullPointerException.class, () -> PackHosting.injector(null));
    assertThrows(NullPointerException.class, () -> new NettyHosting(null));
  }

  @Test
  void shouldKeepTheMessageInExceptions() {
    final InjectorException exception = new InjectorException(MESSAGE);

    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();

    assertEquals(MESSAGE, message);
    assertNull(cause);
  }

  @Test
  void shouldKeepTheMessageAndTheCauseInExceptions() {
    final IOException failure = new IOException("Cause!");
    final InjectorException exception = new InjectorException(MESSAGE, failure);

    final String message = exception.getMessage();
    final Throwable cause = exception.getCause();

    assertEquals(MESSAGE, message);
    assertSame(failure, cause);
  }

  @Test
  void shouldMakeExceptionsBukkitExceptions() {
    final InjectorException exception = new InjectorException(MESSAGE);
    assertInstanceOf(BukkitException.class, exception);
  }
}
