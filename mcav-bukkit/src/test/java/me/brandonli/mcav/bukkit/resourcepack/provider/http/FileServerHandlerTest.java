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
package me.brandonli.mcav.bukkit.resourcepack.provider.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FileRegion;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import me.brandonli.mcav.bukkit.testing.LogCapture;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link FileServerHandler}.
 */
final class FileServerHandlerTest {

  private static final byte[] PACK = { 'P', 'K', 3, 4, 7, 7 };
  private static final byte[] REPLACED_PACK = { 'P', 'K', 3, 4, 8, 8, 8, 8 };
  private static final String OK_HEADERS =
    "HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: " +
    PACK.length +
    "\r\nContent-Disposition: attachment; filename=\"pack.zip\"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
  private static final String METHOD_NOT_ALLOWED = statusResponse("405 Method Not Allowed");
  private static final String NOT_FOUND = statusResponse("404 Not Found");
  private static final String TOO_LARGE = statusResponse("431 Request Header Fields Too Large");

  @TempDir
  private Path directory;

  private Path pack;

  @BeforeEach
  void writePack() throws IOException {
    this.pack = this.directory.resolve("pack.zip");
    Files.write(this.pack, PACK);
  }

  private static ByteBuf ascii(final String text) {
    return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
  }

  private static ByteBuf repeatedLetters(final int count) {
    final String letters = "a".repeat(count);
    return ascii(letters);
  }

  private static void writeRequest(final EmbeddedChannel channel, final String request) {
    final ByteBuf requestBytes = ascii(request);
    channel.writeInbound(requestBytes);
  }

  private EmbeddedChannel createChannel(final Path file) {
    final FileServerHandler handler = new FileServerHandler(file);
    return new EmbeddedChannel(handler);
  }

  private static String readOutboundText(final EmbeddedChannel channel) {
    final ByteBuf buffer = channel.readOutbound();
    try {
      return buffer.toString(StandardCharsets.US_ASCII);
    } finally {
      buffer.release();
    }
  }

  private static byte[] readRegion(final EmbeddedChannel channel) throws IOException {
    final Object message = channel.readOutbound();
    final FileRegion region = assertInstanceOf(FileRegion.class, message);
    try {
      final ByteArrayOutputStream output = new ByteArrayOutputStream();
      final WritableByteChannel target = Channels.newChannel(output);
      final long count = region.count();
      long transferred = 0;
      while (transferred < count) {
        transferred += region.transferTo(target, transferred);
      }
      return output.toByteArray();
    } finally {
      region.release();
    }
  }

  private static String statusResponse(final String status) {
    return "HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
  }

  private static ChannelHandlerContext mockContext(final ChannelPipeline pipeline) {
    final ChannelHandlerContext context = mock(ChannelHandlerContext.class);
    final ChannelFuture future = mock(ChannelFuture.class);
    when(context.alloc()).thenReturn(ByteBufAllocator.DEFAULT);
    when(context.pipeline()).thenReturn(pipeline);
    when(context.writeAndFlush(any())).thenReturn(future);
    return context;
  }

  @Test
  void answersGetRequestsWithTheFileAndClosesTheConnection() throws IOException {
    final EmbeddedChannel channel = this.createChannel(this.pack);
    final ByteBuf request = ascii("GET /pack.zip HTTP/1.1\r\nHost: example\r\n\r\n");

    channel.writeInbound(request);
    final String headers = readOutboundText(channel);
    final byte[] body = readRegion(channel);
    final boolean open = channel.isOpen();
    final int references = request.refCnt();

    assertEquals(OK_HEADERS, headers);
    assertArrayEquals(PACK, body);
    assertFalse(open);
    assertEquals(0, references);
  }

  @Test
  void keepsUntrustedFileNamesInsideOneHeaderValue() throws IOException {
    final Path path = mock(Path.class);
    final Path name = mock(Path.class);
    when(path.getFileName()).thenReturn(name);
    when(name.toString()).thenReturn("pa\r\nc\t\"\\k.zip");
    final FileServerHandler handler = new FileServerHandler(path, _ -> FileChannel.open(this.pack, StandardOpenOption.READ));
    final EmbeddedChannel channel = new EmbeddedChannel(handler);
    try {
      writeRequest(channel, "GET / HTTP/1.1\r\n\r\n");
      final String headers = readOutboundText(channel);
      final byte[] body = readRegion(channel);
      assertEquals(OK_HEADERS, headers);
      assertArrayEquals(PACK, body);
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void answersHeadRequestsWithTheHeadersOnly() {
    final EmbeddedChannel channel = this.createChannel(this.pack);

    writeRequest(channel, "HEAD / HTTP/1.1\r\n\r\n");
    final String headers = readOutboundText(channel);
    final Object body = channel.readOutbound();
    final boolean open = channel.isOpen();

    assertEquals(OK_HEADERS, headers);
    assertNull(body);
    assertFalse(open);
  }

  @Test
  void waitsForTheEndOfTheHeadersAcrossSeveralReads() {
    final EmbeddedChannel channel = this.createChannel(this.pack);

    writeRequest(channel, "HEAD / HTTP/1.1\r\nHost: example\r\n\r");
    final Object early = channel.readOutbound();
    final boolean openWhileWaiting = channel.isOpen();
    writeRequest(channel, "\n");
    final String headers = readOutboundText(channel);

    assertNull(early);
    assertTrue(openWhileWaiting);
    assertEquals(OK_HEADERS, headers);
  }

  @Test
  void rejectsOtherMethods() {
    final EmbeddedChannel post = this.createChannel(this.pack);
    final EmbeddedChannel withoutSpace = this.createChannel(this.pack);

    writeRequest(post, "POST / HTTP/1.1\r\n\r\n");
    final String postResponse = readOutboundText(post);
    writeRequest(withoutSpace, "GET\r\n\r\n");
    final String withoutSpaceResponse = readOutboundText(withoutSpace);
    final boolean postOpen = post.isOpen();

    assertEquals(METHOD_NOT_ALLOWED, postResponse);
    assertEquals(METHOD_NOT_ALLOWED, withoutSpaceResponse);
    assertFalse(postOpen);
  }

  @Test
  void answersNotFoundWhenTheFileIsMissing() {
    final Path missing = this.directory.resolve("missing.zip");
    final EmbeddedChannel channel = this.createChannel(missing);

    writeRequest(channel, "GET / HTTP/1.1\r\n\r\n");
    final String response = readOutboundText(channel);

    assertEquals(NOT_FOUND, response);
  }

  @Test
  void closesTheFileWhenOnlyTheHeadersAreRequested() throws IOException {
    final FileChannel channel = mock(FileChannel.class);
    final long length = PACK.length;
    when(channel.size()).thenReturn(length);
    final FileServerHandler handler = new FileServerHandler(this.pack, _ -> channel);
    final EmbeddedChannel embedded = new EmbeddedChannel(handler);

    writeRequest(embedded, "HEAD / HTTP/1.1\r\n\r\n");
    final String headers = readOutboundText(embedded);

    assertEquals(OK_HEADERS, headers);
    verify(channel).close();
  }

  @Test
  void acceptsHeadersThatFillTheBufferExactly() {
    final EmbeddedChannel channel = this.createChannel(this.pack);
    final ByteBuf exactlyFull = repeatedLetters(8192);

    channel.writeInbound(exactlyFull);
    final Object response = channel.readOutbound();
    final boolean open = channel.isOpen();

    assertNull(response, "a request that fills the buffer exactly is not rejected");
    assertTrue(open);
  }

  @Test
  void answersRequestsWhoseHeadersEndWithTheirFirstBytes() {
    final EmbeddedChannel channel = this.createChannel(this.pack);

    writeRequest(channel, "\r\n\r\n");
    final String response = readOutboundText(channel);

    assertEquals(METHOD_NOT_ALLOWED, response, "a request without a method is answered, not awaited");
  }

  @Test
  void rejectsAMethodWithoutSpacesAtTheExactBufferBoundary() {
    final EmbeddedChannel channel = this.createChannel(this.pack);
    final String malformed = "a".repeat(252) + "\r\n\r\n";
    writeRequest(channel, malformed);
    final String response = readOutboundText(channel);
    assertEquals(METHOD_NOT_ALLOWED, response);
  }

  @Test
  void rejectsHeadersThatAreTooLarge() {
    final EmbeddedChannel single = this.createChannel(this.pack);
    final EmbeddedChannel accumulated = this.createChannel(this.pack);
    final ByteBuf oversized = repeatedLetters(9000);
    final ByteBuf firstHalf = repeatedLetters(5000);
    final ByteBuf secondHalf = repeatedLetters(5000);

    single.writeInbound(oversized);
    final String singleResponse = readOutboundText(single);
    accumulated.writeInbound(firstHalf);
    final Object early = accumulated.readOutbound();
    accumulated.writeInbound(secondHalf);
    final String accumulatedResponse = readOutboundText(accumulated);

    assertEquals(TOO_LARGE, singleResponse);
    assertNull(early);
    assertEquals(TOO_LARGE, accumulatedResponse);
  }

  @Test
  void ignoresMessagesThatAreNotBytes() {
    final EmbeddedChannel channel = this.createChannel(this.pack);

    channel.writeInbound("not bytes");
    final Object response = channel.readOutbound();
    final boolean open = channel.isOpen();

    assertNull(response);
    assertTrue(open);
  }

  @Test
  void usesAGenericFileNameForPathsWithoutOne() {
    final Path root = this.directory.getRoot();

    final String rootName = FileServerHandler.getFileName(root);
    final String packName = FileServerHandler.getFileName(this.pack);

    assertEquals("resourcepack.zip", rootName);
    assertEquals("pack.zip", packName);
  }

  @Test
  void takesTheLengthAndTheBodyFromTheSameOpenFile() throws IOException {
    final Path opened = this.directory.resolve("opened.zip");
    Files.write(opened, PACK);
    Files.write(this.pack, REPLACED_PACK);
    final FileServerHandler handler = new FileServerHandler(this.pack, _ -> FileChannel.open(opened, StandardOpenOption.READ));
    final EmbeddedChannel channel = new EmbeddedChannel(handler);

    writeRequest(channel, "GET / HTTP/1.1\r\n\r\n");
    final String headers = readOutboundText(channel);
    final byte[] body = readRegion(channel);

    assertEquals(OK_HEADERS, headers, "the pack was replaced after it was opened");
    assertArrayEquals(PACK, body);
  }

  @Test
  void answersNotFoundAndClosesTheFileWhenItsSizeCannotBeRead() throws IOException {
    final FileChannel broken = mock(FileChannel.class);
    when(broken.size()).thenThrow(new IOException("size failed"));
    doThrow(new IOException("close failed")).when(broken).close();
    final FileServerHandler handler = new FileServerHandler(this.pack, _ -> broken);
    final EmbeddedChannel channel = new EmbeddedChannel(handler);

    writeRequest(channel, "GET / HTTP/1.1\r\n\r\n");
    final String response = readOutboundText(channel);

    assertEquals(NOT_FOUND, response);
    verify(broken).close();
  }

  @Test
  void removesTheReadTimeoutOnceTheRequestIsCompleteAndKeepsTheWriteTimeout() {
    final FileServerHandler handler = new FileServerHandler(this.pack);
    final ChannelPipeline pipeline = mock(ChannelPipeline.class);
    final ChannelHandler timeout = mock(ChannelHandler.class);
    final ChannelHandler writeTimeout = mock(ChannelHandler.class);
    final ChannelHandlerContext context = mockContext(pipeline);
    when(pipeline.get(FileHttpChannelInitializer.READ_TIMEOUT_NAME)).thenReturn(timeout);
    when(pipeline.get(FileHttpChannelInitializer.WRITE_TIMEOUT_NAME)).thenReturn(writeTimeout);
    final ByteBuf requestLine = ascii("POST / HTTP/1.1\r\n");
    final ByteBuf headerEnd = ascii("\r\n");

    handler.handlerAdded(context);
    handler.channelRead(context, requestLine);
    verify(pipeline, never()).remove(timeout);
    handler.channelRead(context, headerEnd);
    handler.handlerRemoved(context);

    verify(pipeline).remove(timeout);
    verify(pipeline, never()).remove(writeTimeout);
  }

  @Test
  void answersOnlyTheFirstRequestOfAConnection() {
    final FileServerHandler handler = new FileServerHandler(this.pack);
    final ChannelPipeline pipeline = mock(ChannelPipeline.class);
    final ChannelHandlerContext context = mockContext(pipeline);
    final ByteBuf first = ascii("POST / HTTP/1.1\r\n\r\n");
    final ByteBuf late = ascii("HEAD / HTTP/1.1\r\n\r\n");

    handler.handlerAdded(context);
    handler.channelRead(context, first);
    handler.channelRead(context, late);
    final int references = late.refCnt();
    handler.handlerRemoved(context);

    assertEquals(0, references);
    verify(context, times(1)).writeAndFlush(any());
  }

  @Test
  void releasesTheRequestBufferOnceWhenRemoved() {
    final FileServerHandler handler = new FileServerHandler(this.pack);
    final ChannelHandlerContext context = mock(ChannelHandlerContext.class);
    final ByteBufAllocator allocator = mock(ByteBufAllocator.class);
    final ByteBuf requestBuffer = Unpooled.buffer(256, 8192);
    when(context.alloc()).thenReturn(allocator);
    when(allocator.buffer(256, 8192)).thenReturn(requestBuffer);
    final ByteBuf late = ascii("GET / HTTP/1.1\r\n\r\n");

    handler.handlerAdded(context);
    handler.handlerRemoved(context);
    handler.handlerRemoved(context);
    handler.channelRead(context, late);
    final int bufferReferences = requestBuffer.refCnt();
    final int lateReferences = late.refCnt();

    assertEquals(0, bufferReferences);
    assertEquals(0, lateReferences, "requests after removal are dropped");
  }

  @Test
  void closesTheConnectionOnErrors() {
    final EmbeddedChannel channel = this.createChannel(this.pack);
    final ChannelPipeline pipeline = channel.pipeline();
    final IOException reset = new IOException("connection reset");

    pipeline.fireExceptionCaught(reset);
    final boolean open = channel.isOpen();

    assertFalse(open);
  }

  @Test
  void logsAConnectionThatCannotBeClosedAfterAnError() {
    final EmbeddedChannel channel = new EmbeddedChannel();
    final IOException closeFailure = new IOException("close failed");
    final ChannelFuture failedClose = channel.newFailedFuture(closeFailure);
    final ChannelHandlerContext context = mock(ChannelHandlerContext.class);
    when(context.close()).thenReturn(failedClose);
    final FileServerHandler handler = new FileServerHandler(this.pack);
    final IOException reset = new IOException("connection reset");

    final List<LogCapture.RecordedEvent> events;
    try (final LogCapture logs = LogCapture.capture(FileServerHandler.class)) {
      handler.exceptionCaught(context, reset);
      events = logs.getEvents();
    }

    final int count = events.size();
    assertEquals(1, count);
    final LogCapture.RecordedEvent event = events.getFirst();
    final Level level = event.getLevel();
    final String message = event.getMessage();
    final Throwable thrown = event.getThrown();
    assertEquals(Level.DEBUG, level);
    assertEquals("Could not close a resource pack download connection after an error", message);
    assertSame(closeFailure, thrown);
  }

  @Test
  void logsNothingWhenTheConnectionClosesAfterAnError() {
    final EmbeddedChannel channel = this.createChannel(this.pack);
    final ChannelPipeline pipeline = channel.pipeline();
    final IOException reset = new IOException("connection reset");

    final List<LogCapture.RecordedEvent> events;
    try (final LogCapture logs = LogCapture.capture(FileServerHandler.class)) {
      pipeline.fireExceptionCaught(reset);
      events = logs.getEvents();
    }

    final boolean nothingLogged = events.isEmpty();
    assertTrue(nothingLogged);
  }
}
