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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
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
    final FileServerHandler handler = new FileServerHandler(this.pack);
    pipeline.addLast(FileHttpChannelInitializer.READ_TIMEOUT_NAME, timeout);
    pipeline.addLast(handler);
    return channel;
  }

  @Test
  void addsAReadTimeoutOfThirtySecondsInFrontOfTheFileHandler() {
    final SocketChannel channel = mock(SocketChannel.class);
    final ChannelPipeline pipeline = mock(ChannelPipeline.class);
    when(channel.pipeline()).thenReturn(pipeline);
    final FileHttpChannelInitializer initializer = new FileHttpChannelInitializer(this.pack);
    final ArgumentCaptor<ChannelHandler> timeoutCaptor = ArgumentCaptor.forClass(ChannelHandler.class);
    final ArgumentCaptor<ChannelHandler> handlerCaptor = ArgumentCaptor.forClass(ChannelHandler.class);

    initializer.initChannel(channel);

    final InOrder order = inOrder(pipeline);
    order.verify(pipeline).addLast(eq(FileHttpChannelInitializer.READ_TIMEOUT_NAME), timeoutCaptor.capture());
    order.verify(pipeline).addLast(handlerCaptor.capture());
    final ChannelHandler timeoutHandler = timeoutCaptor.getValue();
    final ChannelHandler fileHandler = handlerCaptor.getValue();
    final ReadTimeoutHandler readTimeout = assertInstanceOf(ReadTimeoutHandler.class, timeoutHandler);
    final long timeoutMillis = readTimeout.getReaderIdleTimeInMillis();
    assertEquals(30_000, timeoutMillis);
    assertInstanceOf(FileServerHandler.class, fileHandler);
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
