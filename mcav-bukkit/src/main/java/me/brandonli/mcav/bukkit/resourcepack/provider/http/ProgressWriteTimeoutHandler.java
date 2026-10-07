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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelProgressiveFuture;
import io.netty.channel.ChannelProgressiveFutureListener;
import io.netty.channel.ChannelProgressivePromise;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Applies the write deadline to stalls, including partial progress within a zero-copy file region. */
final class ProgressWriteTimeoutHandler extends WriteTimeoutHandler {

  private final int timeoutSeconds;
  private final Set<PendingWrite> writes = new HashSet<>();

  ProgressWriteTimeoutHandler(final int timeoutSeconds) {
    super(timeoutSeconds);
    this.timeoutSeconds = timeoutSeconds;
  }

  @Override
  public void write(final ChannelHandlerContext context, final Object message, final ChannelPromise promise) {
    final ChannelProgressivePromise progress = context.newProgressivePromise();
    final PendingWrite pending = new PendingWrite(context, promise.unvoid());
    this.writes.add(pending);
    pending.renew();
    context.write(message, progress).addListener(pending);
  }

  @Override
  public void handlerRemoved(final ChannelHandlerContext context) throws Exception {
    for (final PendingWrite pending : this.writes) {
      pending.cancel();
    }
    this.writes.clear();
    super.handlerRemoved(context);
  }

  private final class PendingWrite implements ChannelProgressiveFutureListener {

    private final ChannelHandlerContext context;
    private final ChannelPromise completion;
    private @Nullable ScheduledFuture<?> timeout;
    private long transferred;

    private PendingWrite(final ChannelHandlerContext context, final ChannelPromise completion) {
      this.context = context;
      this.completion = completion;
    }

    private void renew() {
      this.cancel();
      this.timeout = this.context.executor().schedule(this::expire, ProgressWriteTimeoutHandler.this.timeoutSeconds, TimeUnit.SECONDS);
    }

    private void cancel() {
      final ScheduledFuture<?> scheduled = this.timeout;
      if (scheduled != null) {
        scheduled.cancel(false);
        this.timeout = null;
      }
    }

    private void expire() {
      try {
        ProgressWriteTimeoutHandler.this.writeTimedOut(this.context);
      } catch (final Exception exception) {
        this.context.fireExceptionCaught(exception);
      }
    }

    @Override
    public void operationProgressed(final ChannelProgressiveFuture future, final long progress, final long total) {
      if (progress > this.transferred && ProgressWriteTimeoutHandler.this.writes.contains(this)) {
        this.transferred = progress;
        this.renew();
      }
    }

    @Override
    public void operationComplete(final ChannelProgressiveFuture future) {
      this.cancel();
      ProgressWriteTimeoutHandler.this.writes.remove(this);
      if (future.isSuccess()) {
        this.completion.trySuccess();
      } else {
        this.completion.tryFailure(Objects.requireNonNull(future.cause()));
      }
    }
  }
}
