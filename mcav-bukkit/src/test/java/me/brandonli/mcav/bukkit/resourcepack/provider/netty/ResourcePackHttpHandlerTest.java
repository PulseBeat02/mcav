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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import me.brandonli.mcav.bukkit.resourcepack.provider.netty.ResourcePackHttpHandler.RequestType;
import me.brandonli.mcav.bukkit.testing.LogCapture;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link ResourcePackHttpHandler}.
 */
final class ResourcePackHttpHandlerTest {

  private static final byte[] PACK = { 'P', 'K', 3, 4, 9 };
  private static final byte[] HANDSHAKE = { 0x10, 0x00, (byte) 0xF7, 0x05, 0x09, 'l', 'o', 'c', 'a', 'l' };
  private static final String OK_HEADERS = headers("200 OK", "application/zip", PACK.length);
  private static final String ERROR_HEADERS = headers("500 Internal Server Error", "text/plain", 0);

  @TempDir
  private Path directory;

  private Path packPath;

  @BeforeEach
  void writePack() throws IOException {
    this.packPath = this.directory.resolve("pack.zip");
    Files.write(this.packPath, PACK);
  }

  private EmbeddedChannel createChannel(final Path pack) {
    final ResourcePackFile file = new ResourcePackFile(pack);
    final ResourcePackHttpHandler handler = new ResourcePackHttpHandler(file, "/");
    final EmbeddedChannel channel = new EmbeddedChannel();
    final ChannelPipeline pipeline = channel.pipeline();
    pipeline.addFirst(ResourcePackHttpHandler.NAME, handler);
    return channel;
  }

  private static ByteBuf ascii(final String text) {
    return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
  }

  private static void writeText(final EmbeddedChannel channel, final String text) {
    final ByteBuf bytes = ascii(text);
    channel.writeInbound(bytes);
  }

  private static void writeBytes(final EmbeddedChannel channel, final byte[] bytes) {
    final ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
    channel.writeInbound(buffer);
  }

  private static RequestType classifyText(final String text) {
    final ByteBuf bytes = ascii(text);
    return ResourcePackHttpHandler.classify(bytes);
  }

  private static ChannelHandler findHandler(final EmbeddedChannel channel) {
    final ChannelPipeline pipeline = channel.pipeline();
    return pipeline.get(ResourcePackHttpHandler.NAME);
  }

  private static String readOutboundText(final EmbeddedChannel channel) {
    final ByteBuf buffer = channel.readOutbound();
    try {
      return buffer.toString(StandardCharsets.US_ASCII);
    } finally {
      buffer.release();
    }
  }

  private static byte[] readOutboundBytes(final EmbeddedChannel channel) {
    final ByteBuf buffer = channel.readOutbound();
    try {
      return ByteBufUtil.getBytes(buffer);
    } finally {
      buffer.release();
    }
  }

  private static byte[] readInboundBytes(final EmbeddedChannel channel) {
    final ByteBuf buffer = channel.readInbound();
    try {
      return ByteBufUtil.getBytes(buffer);
    } finally {
      buffer.release();
    }
  }

  private static String headers(final String status, final String contentType, final int contentLength) {
    return (
      "HTTP/1.1 " +
      status +
      "\r\nContent-Type: " +
      contentType +
      "\r\nContent-Length: " +
      contentLength +
      "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
    );
  }

  @Test
  void servesThePackToGetRequestsAndClosesTheConnection() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);
    final ByteBuf request = ascii("GET / HTTP/1.1\r\nHost: example\r\n\r\n");

    channel.writeInbound(request);
    final String responseHeaders = readOutboundText(channel);
    final byte[] body = readOutboundBytes(channel);
    final Object passedOn = channel.readInbound();
    final boolean open = channel.isOpen();
    final int requestReferences = request.refCnt();

    assertEquals(OK_HEADERS, responseHeaders);
    assertArrayEquals(PACK, body);
    assertNull(passedOn, "the request never reaches Minecraft");
    assertFalse(open);
    assertEquals(0, requestReferences, "the request is released");
  }

  @Test
  void waitsForTheWholeTargetAndPassesAnUnmatchedRequestUnchanged() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);
    writeText(channel, "HEAD /");
    final Object premature = channel.readOutbound();
    assertNull(premature);
    writeText(channel, "other HTTP/1.1\r\n\r\n");
    final String passed = new String(readInboundBytes(channel), StandardCharsets.US_ASCII);
    final Object response = channel.readOutbound();
    assertEquals("HEAD /other HTTP/1.1\r\n\r\n", passed);
    assertNull(response);
    final ChannelHandler handler = findHandler(channel);
    assertNull(handler);
    channel.finishAndReleaseAll();
  }

  @Test
  void answersHeadRequestsWithTheHeadersOnly() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);

    writeText(channel, "HEAD / HTTP/1.1\r\n\r\n");
    final String responseHeaders = readOutboundText(channel);
    final Object body = channel.readOutbound();
    final boolean open = channel.isOpen();

    assertEquals(OK_HEADERS, responseHeaders);
    assertNull(body);
    assertFalse(open);
  }

  @Test
  void waitsUntilEnoughBytesArrivedToDecide() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);
    final ByteBuf firstPart = ascii("GE");

    channel.writeInbound(firstPart);
    final Object earlyResponse = channel.readOutbound();
    final Object earlyPassedOn = channel.readInbound();
    final boolean openWhileWaiting = channel.isOpen();
    writeText(channel, "T / HTTP/1.1\r\n\r\n");
    final String responseHeaders = readOutboundText(channel);
    final byte[] body = readOutboundBytes(channel);
    final int firstPartReferences = firstPart.refCnt();

    assertNull(earlyResponse);
    assertNull(earlyPassedOn);
    assertTrue(openWhileWaiting);
    assertEquals(OK_HEADERS, responseHeaders);
    assertArrayEquals(PACK, body);
    assertEquals(0, firstPartReferences, "the buffered bytes are released");
  }

  @Test
  void handsMinecraftConnectionsBackUntouchedAndRemovesItself() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);

    writeBytes(channel, HANDSHAKE);
    final byte[] passedOn = readInboundBytes(channel);
    final ChannelHandler handler = findHandler(channel);
    final Object response = channel.readOutbound();
    final boolean open = channel.isOpen();

    assertArrayEquals(HANDSHAKE, passedOn);
    assertNull(handler, "the handler adds no overhead to game connections");
    assertNull(response);
    assertTrue(open);
  }

  @Test
  void handsBackEveryBufferedByteWhenAPartialPrefixTurnsOutToBeMinecraft() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);

    writeText(channel, "HE");
    writeText(channel, "X!");
    final byte[] passedOn = readInboundBytes(channel);
    final String passedOnText = new String(passedOn, StandardCharsets.US_ASCII);
    final ChannelHandler handler = findHandler(channel);

    assertEquals("HEX!", passedOnText);
    assertNull(handler);
  }

  @Test
  void handsShortMinecraftPacketsBackImmediately() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);
    final byte[] ping = { 0x01, 0x00 };

    writeBytes(channel, ping);
    final byte[] passedOn = readInboundBytes(channel);

    assertArrayEquals(ping, passedOn);
  }

  @Test
  void passesMessagesThatAreNotBytesOn() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);
    final Object message = "not a byte buffer";

    channel.writeInbound(message);
    final Object passedOn = channel.readInbound();
    final ChannelHandler handler = findHandler(channel);

    assertSame(message, passedOn);
    assertInstanceOf(ResourcePackHttpHandler.class, handler, "the handler still waits for the first bytes");
  }

  @Test
  void answersWithAnErrorAndLogsItWhenThePackCannotBeRead() {
    final Path missing = this.directory.resolve("missing.zip");
    final EmbeddedChannel channel = this.createChannel(missing);
    final List<LogCapture.RecordedEvent> events;
    try (final LogCapture logs = LogCapture.capture(ResourcePackHttpHandler.class)) {
      writeText(channel, "GET / HTTP/1.1\r\n\r\n");
      events = logs.getEvents();
    }

    final String responseHeaders = readOutboundText(channel);
    final Object body = channel.readOutbound();
    final boolean open = channel.isOpen();
    final LogCapture.RecordedEvent event = events.getFirst();
    final Level level = event.getLevel();
    final String message = event.getMessage();
    final Throwable thrown = event.getThrown();
    assertEquals(ERROR_HEADERS, responseHeaders);
    assertNull(body);
    assertFalse(open);
    assertEquals(Level.WARN, level);
    assertEquals("Could not read the resource pack for an HTTP download", message);
    assertInstanceOf(NoSuchFileException.class, thrown);
  }

  @Test
  void releasesBufferedBytesWhenTheConnectionClosesEarly() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);
    final ByteBuf partial = ascii("GE");

    channel.writeInbound(partial);
    final ChannelFuture closing = channel.close();
    final boolean closed = closing.isSuccess();
    final int references = partial.refCnt();

    assertTrue(closed);
    assertEquals(0, references);
  }

  @Test
  void releasesBufferedBytesWhenTheHandlerIsRemoved() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);
    final ChannelPipeline pipeline = channel.pipeline();
    final ByteBuf partial = ascii("HEA");

    channel.writeInbound(partial);
    pipeline.remove(ResourcePackHttpHandler.NAME);
    final int references = partial.refCnt();
    final boolean open = channel.isOpen();

    assertEquals(0, references);
    assertTrue(open);
  }

  @Test
  void dropsEverythingThatArrivesAfterThePackWasServed() {
    final ResourcePackFile file = new ResourcePackFile(this.packPath);
    final ResourcePackHttpHandler handler = new ResourcePackHttpHandler(file, "/");
    final ChannelHandlerContext context = mock(ChannelHandlerContext.class);
    final ChannelFuture future = mock(ChannelFuture.class);
    when(context.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
    // like a real channel, every write returns a future
    when(context.write(any())).thenReturn(future);
    when(context.writeAndFlush(any())).thenReturn(future);
    final ByteBuf request = ascii("GET / HTTP/1.1\r\n\r\n");
    final ByteBuf late = ascii("more data");

    handler.channelRead(context, request);
    handler.channelRead(context, late);
    final int references = late.refCnt();

    assertEquals(0, references);
    verify(context, never()).fireChannelRead(any());
  }

  @Test
  void classifiesClientsAsSoonAsTheirMethodIsComplete() {
    final RequestType get = classifyText("GET ");
    final RequestType head = classifyText("HEAD ");

    assertEquals(RequestType.GET, get, "the shortest complete request line is already a GET");
    assertEquals(RequestType.HEAD, head);
  }

  @Test
  void releasesBufferedBytesWhenTheConnectionCloses() {
    final EmbeddedChannel channel = this.createChannel(this.packPath);
    final ChannelPipeline pipeline = channel.pipeline();
    final ByteBuf partial = ascii("GE");

    channel.writeInbound(partial);
    pipeline.fireChannelInactive();
    final int references = partial.refCnt();

    assertEquals(0, references, "bytes buffered while the client type is unknown are released");
  }

  @Test
  void classifiesClientsByTheirFirstBytes() {
    final RequestType get = classifyText("GET /");
    final RequestType head = classifyText("HEAD /");
    final RequestType partialGet = classifyText("GET");
    final RequestType partialHead = classifyText("HEAD");
    final RequestType nothing = ResourcePackHttpHandler.classify(Unpooled.EMPTY_BUFFER);
    final RequestType post = classifyText("POST / HTTP/1.1");
    final RequestType lowerCase = classifyText("get / HTTP/1.1");
    final RequestType noSpace = classifyText("GETX");

    assertEquals(RequestType.GET, get);
    assertEquals(RequestType.HEAD, head);
    assertEquals(RequestType.INCOMPLETE, partialGet);
    assertEquals(RequestType.INCOMPLETE, partialHead);
    assertEquals(RequestType.INCOMPLETE, nothing);
    assertEquals(RequestType.MINECRAFT, post);
    assertEquals(RequestType.MINECRAFT, lowerCase);
    assertEquals(RequestType.MINECRAFT, noSpace);
  }

  @Test
  void classifiesFromTheReaderIndexWithoutConsumingBytes() {
    final ByteBuf skipped = ascii("xxGET /");
    skipped.skipBytes(2);

    final RequestType afterReaderIndex = ResourcePackHttpHandler.classify(skipped);
    final int readerIndex = skipped.readerIndex();

    assertEquals(RequestType.GET, afterReaderIndex);
    assertEquals(2, readerIndex, "classifying does not consume bytes");
  }

  @Test
  void usesAStablePipelineNamePrefix() {
    // NettyHosting appends its instance number, because a Netty pipeline rejects two handlers of one name
    assertEquals("mcav_resource_pack_http", ResourcePackHttpHandler.NAME);
  }
}
