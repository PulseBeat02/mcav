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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.concurrent.ImmediateEventExecutor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Tests {@link FileHttpChannelInitializer}.
 */
final class FileHttpChannelInitializerTest {

  private static final byte[] PACK = { 'P', 'K', 3, 4 };

  @TempDir
  private Path directory;

  private Path pack;

  @BeforeEach
  void writePack() throws IOException {
    this.pack = this.directory.resolve("pack.zip");
    Files.write(this.pack, PACK);
  }

  private EmbeddedChannel createChannel() {
    final EmbeddedChannel channel = new EmbeddedChannel();
    final ChannelPipeline pipeline = channel.pipeline();
    final ReadTimeoutHandler timeout = new ReadTimeoutHandler(FileHttpChannelInitializer.READ_TIMEOUT_SECONDS);
    final WriteTimeoutHandler writeTimeout = new WriteTimeoutHandler(FileHttpChannelInitializer.WRITE_TIMEOUT_SECONDS);
    final FileServerHandler handler = new FileServerHandler(this.pack);
    pipeline.addLast(FileHttpChannelInitializer.READ_TIMEOUT_NAME, timeout);
    pipeline.addLast(FileHttpChannelInitializer.WRITE_TIMEOUT_NAME, writeTimeout);
    pipeline.addLast(handler);
    return channel;
  }

  @Test
  void addsTheTimeoutsInFrontOfTheFileHandler() {
    final Connection connection = new Connection();
    final SocketChannel channel = connection.channel;
    final ChannelPipeline pipeline = connection.pipeline;
    final FileHttpChannelInitializer initializer = new FileHttpChannelInitializer(this.pack);
    final ArgumentCaptor<ChannelHandler> timeoutCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
    final ArgumentCaptor<ChannelHandler> handlerCaptor = ArgumentCaptor.forClass(ChannelHandler.class);

    initializer.initChannel(channel);

    final ArgumentCaptor<ChannelHandler> writeTimeoutCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
    final InOrder order = inOrder(pipeline);
    order.verify(pipeline).addLast(eq(FileHttpChannelInitializer.READ_TIMEOUT_NAME), timeoutCaptor.capture());
    order.verify(pipeline).addLast(eq(FileHttpChannelInitializer.WRITE_TIMEOUT_NAME), writeTimeoutCaptor.capture());
    order.verify(pipeline).addLast(handlerCaptor.capture());
    final ChannelHandler timeoutHandler = timeoutCaptor.getValue();
    final ChannelHandler writeTimeoutHandler = writeTimeoutCaptor.getValue();
    final ChannelHandler fileHandler = handlerCaptor.getValue();
    final ReadTimeoutHandler readTimeout = assertInstanceOf(ReadTimeoutHandler.class, timeoutHandler);
    final long timeoutMillis = readTimeout.getReaderIdleTimeInMillis();
    assertEquals(30_000, timeoutMillis);
    assertInstanceOf(WriteTimeoutHandler.class, writeTimeoutHandler);
    assertEquals(300, FileHttpChannelInitializer.WRITE_TIMEOUT_SECONDS, "a stalled download is closed after five minutes");
    assertInstanceOf(FileServerHandler.class, fileHandler);
  }

  /**
   * A socket channel whose pipeline and close future are observable, as a connection of the server.
   */
  private static final class Connection {

    private final SocketChannel channel;
    private final ChannelPipeline pipeline;
    private final ChannelPromise closed;

    private Connection() {
      this.channel = mock(SocketChannel.class);
      this.pipeline = mock(ChannelPipeline.class);
      this.closed = new DefaultChannelPromise(this.channel, ImmediateEventExecutor.INSTANCE);
      final ChannelPromise written = new DefaultChannelPromise(this.channel, ImmediateEventExecutor.INSTANCE);
      when(this.channel.pipeline()).thenReturn(this.pipeline);
      when(this.channel.closeFuture()).thenReturn(this.closed);
      when(this.channel.writeAndFlush(any())).thenReturn(written);
    }

    private void close() {
      this.closed.setSuccess();
    }
  }

  @Test
  void refusesMoreConnectionsThanTheLimitAndCountsThemBackDown() {
    final FileHttpChannelInitializer initializer = new FileHttpChannelInitializer(this.pack, 2);
    final Connection first = new Connection();
    final Connection second = new Connection();
    final Connection refused = new Connection();

    initializer.initChannel(first.channel);
    initializer.initChannel(second.channel);
    initializer.initChannel(refused.channel);

    assertEquals(3, initializer.getOpenConnections());
    verify(first.pipeline).addLast(eq(FileHttpChannelInitializer.READ_TIMEOUT_NAME), any(ChannelHandler.class));
    verify(second.pipeline).addLast(eq(FileHttpChannelInitializer.READ_TIMEOUT_NAME), any(ChannelHandler.class));
    verify(refused.pipeline, never()).addLast(eq(FileHttpChannelInitializer.READ_TIMEOUT_NAME), any(ChannelHandler.class));
    verify(refused.pipeline, never()).addLast(any(ChannelHandler.class));

    final ArgumentCaptor<Object> answers = ArgumentCaptor.forClass(Object.class);
    verify(refused.channel).writeAndFlush(answers.capture());
    final Object answer = answers.getValue();
    final ByteBuf bytes = assertInstanceOf(ByteBuf.class, answer);
    final String text = bytes.toString(StandardCharsets.US_ASCII);
    bytes.release();
    final boolean busy = text.startsWith("HTTP/1.1 503 Service Unavailable");
    assertTrue(busy, text);

    first.close();
    assertEquals(2, initializer.getOpenConnections(), "a closed connection makes room for the next one");
  }

  @Test
  void refusesANonPositiveConnectionLimit() {
    assertThrows(IllegalArgumentException.class, () -> new FileHttpChannelInitializer(this.pack, 0));
  }

  @Test
  void closesConnectionsThatStaySilentBeforeTheRequestIsComplete() {
    final EmbeddedChannel channel = this.createChannel();
    final ByteBuf partialRequest = Unpooled.copiedBuffer("GET / HTTP/1.1\r\n", StandardCharsets.US_ASCII);
    final long almostTimedOut = FileHttpChannelInitializer.READ_TIMEOUT_SECONDS - 1;

    channel.writeInbound(partialRequest);
    channel.advanceTimeBy(almostTimedOut, TimeUnit.SECONDS);
    channel.runScheduledPendingTasks();
    final boolean openBeforeTimeout = channel.isOpen();
    channel.advanceTimeBy(2, TimeUnit.SECONDS);
    channel.runScheduledPendingTasks();
    final boolean openAfterTimeout = channel.isOpen();

    assertTrue(openBeforeTimeout);
    assertFalse(openAfterTimeout, "a slow client cannot keep the connection open forever");
  }
}
