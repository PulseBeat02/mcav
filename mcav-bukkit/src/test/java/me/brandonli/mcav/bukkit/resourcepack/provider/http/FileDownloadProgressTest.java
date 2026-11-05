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

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelProgressivePromise;
import io.netty.channel.ChannelPromise;
import io.netty.channel.FileRegion;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.WriteTimeoutException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

final class FileDownloadProgressTest {

  private static final String PACK_FILE_NAME = "pack.zip";
  private static final String PAYLOAD = "payload";

  private static final int WRITE_TIMEOUT_SECONDS = 300;
  private static final int AFTER_TIMEOUT_SECONDS = 301;

  @Test
  void shouldKeepAProgressingFileRegionOpenPastTheWriteTimeout(@TempDir final Path folder) throws Exception {
    final Path pack = folder.resolve(PACK_FILE_NAME);
    Files.write(pack, new byte[4096]);
    final SlowSocket channel = new SlowSocket();
    channel.freezeTime();
    final SocketChannel socket = Mockito.mock(SocketChannel.class);
    Mockito.when(socket.pipeline()).thenReturn(channel.pipeline());
    Mockito.when(socket.closeFuture()).thenReturn(channel.closeFuture());
    new FileHttpChannelInitializer(pack).initChannel(socket);
    try {
      channel.writeInbound(Unpooled.copiedBuffer("GET /pack.zip HTTP/1.1\r\nHost: local\r\n\r\n", StandardCharsets.US_ASCII));
      channel.take(512);
      for (int interval = 0; interval < 8; interval++) {
        channel.advanceTimeBy(100, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        final boolean openWhileProgressing = channel.isOpen();
        assertTrue(openWhileProgressing, "partial file progress must renew the download deadline");

        channel.take(128);
      }

      final boolean partiallyWritten = channel.bodyBytes > 0 && channel.bodyBytes < 4096;
      assertTrue(partiallyWritten);

      channel.advanceTimeBy(299, TimeUnit.SECONDS);
      channel.runScheduledPendingTasks();
      final boolean openAfterShortStall = channel.isOpen();
      assertTrue(openAfterShortStall, "a stall shorter than the limit is allowed");

      channel.advanceTimeBy(2, TimeUnit.SECONDS);
      channel.runScheduledPendingTasks();
      final boolean openAfterStall = channel.isOpen();
      assertFalse(openAfterStall, "an actually stalled download is closed");

      final int regionReferenceCount = channel.region.refCnt();
      assertEquals(0, regionReferenceCount, "closing releases the owned file region");
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void shouldForwardCompletionAndCancelItsDeadline() {
    final HeldWrite held = new HeldWrite();
    final EmbeddedChannel channel = new EmbeddedChannel(held, new ProgressWriteTimeoutHandler(WRITE_TIMEOUT_SECONDS));
    channel.freezeTime();
    try {
      final ChannelPromise completion = channel.newPromise();
      final ChannelFuture submitted = channel.writeOneOutbound(PAYLOAD, completion);
      assertSame(completion, submitted);

      final boolean done = completion.isDone();
      assertFalse(done);

      held.promise().setSuccess();
      final boolean succeeded = completion.isSuccess();
      assertTrue(succeeded);

      channel.advanceTimeBy(AFTER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      channel.runScheduledPendingTasks();
      final boolean open = channel.isOpen();
      assertTrue(open);
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void shouldForwardTheOriginalWriteFailure() {
    final HeldWrite held = new HeldWrite();
    final EmbeddedChannel channel = new EmbeddedChannel(held, new ProgressWriteTimeoutHandler(WRITE_TIMEOUT_SECONDS));
    channel.freezeTime();
    try {
      final ChannelPromise completion = channel.newPromise();
      final ChannelFuture submitted = channel.writeOneOutbound(PAYLOAD, completion);
      assertSame(completion, submitted);

      final IOException failure = new IOException("Socket closed!");
      held.promise().setFailure(failure);
      final @Nullable Throwable cause = completion.cause();
      assertSame(failure, cause);

      channel.advanceTimeBy(AFTER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      channel.runScheduledPendingTasks();
      final boolean open = channel.isOpen();
      assertTrue(open);
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void shouldNotExtendAStalledWriteOnZeroProgress() {
    final HeldWrite held = new HeldWrite();
    final EmbeddedChannel channel = new EmbeddedChannel(held, new ProgressWriteTimeoutHandler(WRITE_TIMEOUT_SECONDS), new IgnoreTimeout());
    channel.freezeTime();
    try {
      final ChannelFuture completion = channel.writeOneOutbound(PAYLOAD);
      final boolean done = completion.isDone();
      assertFalse(done);

      channel.advanceTimeBy(200, TimeUnit.SECONDS);
      final boolean progressed = held.promise().tryProgress(0, 10);
      assertTrue(progressed);

      channel.advanceTimeBy(101, TimeUnit.SECONDS);
      channel.runScheduledPendingTasks();
      final boolean open = channel.isOpen();
      assertFalse(open);

      held.promise().setFailure(new IOException("Closed after timeout!"));
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void shouldCancelDeadlinesOnRemovalEvenWhenTheWriteMakesLaterProgress() {
    final HeldWrite held = new HeldWrite();
    final ProgressWriteTimeoutHandler handler = new ProgressWriteTimeoutHandler(WRITE_TIMEOUT_SECONDS);
    final EmbeddedChannel channel = new EmbeddedChannel(held, handler, new IgnoreTimeout());
    channel.freezeTime();
    try {
      final ChannelPromise completion = channel.newPromise();
      final ChannelFuture submitted = channel.writeOneOutbound(PAYLOAD, completion);
      assertSame(completion, submitted);

      channel.pipeline().remove(handler);
      final boolean progressed = held.promise().tryProgress(1, 10);
      assertTrue(progressed);

      channel.advanceTimeBy(AFTER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      channel.runScheduledPendingTasks();
      final boolean open = channel.isOpen();
      assertTrue(open, "a removed handler cannot create another deadline");

      held.promise().setSuccess();
      final boolean succeeded = completion.isSuccess();
      assertTrue(succeeded);
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void shouldReportAnExceptionThrownWhileExpiringAWrite() {
    final EmbeddedChannel channel = new EmbeddedChannel();
    channel.freezeTime();
    final ProgressWriteTimeoutHandler handler = new ProgressWriteTimeoutHandler(WRITE_TIMEOUT_SECONDS);
    final ChannelHandlerContext context = Mockito.mock(ChannelHandlerContext.class);
    final ChannelProgressivePromise progress = channel.newProgressivePromise();
    final ChannelPromise completion = channel.newPromise();
    final RuntimeException failure = new IllegalStateException("Timeout reporting failed!");
    Mockito.when(context.executor()).thenReturn(channel.eventLoop());
    Mockito.when(context.newProgressivePromise()).thenReturn(progress);
    Mockito.when(context.write(PAYLOAD, progress)).thenReturn(progress);
    Mockito.when(context.fireExceptionCaught(WriteTimeoutException.INSTANCE)).thenThrow(failure);
    try {
      handler.write(context, PAYLOAD, completion);
      channel.advanceTimeBy(AFTER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      channel.runScheduledPendingTasks();
      Mockito.verify(context).fireExceptionCaught(failure);
      final boolean done = completion.isDone();
      assertFalse(done, "reporting a timeout does not invent successful write completion");

      progress.setSuccess();
      final boolean succeeded = completion.isSuccess();
      assertTrue(succeeded);
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  private static final class HeldWrite extends ChannelOutboundHandlerAdapter {

    private @Nullable ChannelProgressivePromise pending;

    @Override
    public void write(final ChannelHandlerContext context, final Object message, final ChannelPromise promise) {
      this.pending = (ChannelProgressivePromise) promise;
    }

    private ChannelProgressivePromise promise() {
      return requireNonNull(this.pending);
    }
  }

  private static final class IgnoreTimeout extends ChannelInboundHandlerAdapter {

    @Override
    public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) {}
  }

  private static final class SlowSocket extends EmbeddedChannel {

    private long allowance;
    private long bodyBytes;
    private FileRegion region;

    private void take(final long bytes) {
      this.allowance += bytes;
      this.flushOutbound();
      this.runPendingTasks();
    }

    @Override
    protected void doWrite(final ChannelOutboundBuffer outbound) throws Exception {
      while (this.allowance > 0) {
        final Object current = outbound.current();
        if (current instanceof final ByteBuf buffer) {
          this.writeBuffer(outbound, buffer);
          continue;
        }

        if (!(current instanceof final FileRegion file)) {
          return;
        }

        final boolean progressed = this.transferRegion(outbound, file);
        if (!progressed) {
          return;
        }
      }
    }

    private void writeBuffer(final ChannelOutboundBuffer outbound, final ByteBuf buffer) {
      final int written = (int) Math.min(this.allowance, buffer.readableBytes());
      this.allowance -= written;
      outbound.removeBytes(written);
    }

    private boolean transferRegion(final ChannelOutboundBuffer outbound, final FileRegion file) throws IOException {
      this.region = file;
      final long written = file.transferTo(this.allowanceSink(), file.transferred());
      this.bodyBytes += written;
      outbound.progress(written);

      if (file.transferred() == file.count()) {
        outbound.remove();
      }

      return written != 0;
    }

    private WritableByteChannel allowanceSink() {
      return new WritableByteChannel() {
        @Override
        public int write(final ByteBuffer bytes) {
          final int written = (int) Math.min(SlowSocket.this.allowance, bytes.remaining());
          bytes.position(bytes.position() + written);
          SlowSocket.this.allowance -= written;
          return written;
        }

        @Override
        public boolean isOpen() {
          return true;
        }

        @Override
        public void close() {}
      };
    }
  }
}
