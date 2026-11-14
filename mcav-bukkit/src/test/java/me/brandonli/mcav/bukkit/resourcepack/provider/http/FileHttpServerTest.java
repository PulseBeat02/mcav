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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

final class FileHttpServerTest {

  private static final String PACK_FILE_NAME = "pack.zip";

  private static final int PORT = 8123;

  private static final long QUIET_PERIOD_SECONDS = 0;
  private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

  @Test
  void shouldShutDownTheWorkersWhenBindingFails() {
    final IOException bindFailure = new IOException("Address unavailable!");
    final ChannelFuture binding = mock(ChannelFuture.class);
    when(binding.cause()).thenReturn(bindFailure);
    try (
      final MockedConstruction<MultiThreadIoEventLoopGroup> groups = mockConstruction(MultiThreadIoEventLoopGroup.class, (group, _) ->
        this.configureTermination(group)
      );
      final MockedConstruction<ServerBootstrap> bootstraps = mockConstruction(ServerBootstrap.class, (bootstrap, _) ->
        when(bootstrap.bind(PORT)).thenReturn(binding)
      )
    ) {
      final FileHttpServer server = new FileHttpServer(PORT, Path.of(PACK_FILE_NAME));
      final HttpServerException exception = assertThrows(HttpServerException.class, server::start);
      final Throwable cause = exception.getCause();
      assertSame(bindFailure, cause);

      final List<MultiThreadIoEventLoopGroup> constructed = groups.constructed();
      final int groupCount = constructed.size();
      assertEquals(2, groupCount);

      final MultiThreadIoEventLoopGroup workers = constructed.get(1);
      verify(workers).shutdownGracefully(QUIET_PERIOD_SECONDS, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);

      final List<ServerBootstrap> constructedBootstraps = bootstraps.constructed();
      final int bootstrapCount = constructedBootstraps.size();
      assertEquals(1, bootstrapCount);
    }
  }

  @Test
  void shouldShutDownTheWorkersWhenStopping() {
    final ChannelFuture closing = mock(ChannelFuture.class);
    final Channel channel = mock(Channel.class);
    when(channel.close()).thenReturn(closing);
    final ChannelFuture binding = mock(ChannelFuture.class);
    when(binding.isSuccess()).thenReturn(true);
    when(binding.channel()).thenReturn(channel);
    try (
      final MockedConstruction<MultiThreadIoEventLoopGroup> groups = mockConstruction(MultiThreadIoEventLoopGroup.class, (group, _) ->
        this.configureTermination(group)
      );
      final MockedConstruction<ServerBootstrap> _ = mockConstruction(ServerBootstrap.class, (bootstrap, _) ->
        when(bootstrap.bind(PORT)).thenReturn(binding)
      )
    ) {
      final FileHttpServer server = new FileHttpServer(PORT, Path.of(PACK_FILE_NAME));
      server.start();
      server.stop();

      final List<MultiThreadIoEventLoopGroup> constructed = groups.constructed();
      final MultiThreadIoEventLoopGroup workers = constructed.get(1);
      verify(workers).shutdownGracefully(QUIET_PERIOD_SECONDS, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }

  @Test
  void shouldWaitUntilBothGroupsTerminateWhenStopping() {
    final ChannelFuture closing = mock(ChannelFuture.class);
    final Channel channel = mock(Channel.class);
    when(channel.close()).thenReturn(closing);

    final ChannelFuture binding = mock(ChannelFuture.class);
    when(binding.isSuccess()).thenReturn(true);
    when(binding.channel()).thenReturn(channel);

    final Future<?> termination = mock(Future.class);
    try (
      final MockedConstruction<MultiThreadIoEventLoopGroup> _ = mockConstruction(MultiThreadIoEventLoopGroup.class, (group, _) ->
        doReturn(termination).when(group).shutdownGracefully(QUIET_PERIOD_SECONDS, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      );
      final MockedConstruction<ServerBootstrap> _ = mockConstruction(ServerBootstrap.class, (bootstrap, _) ->
        when(bootstrap.bind(PORT)).thenReturn(binding)
      )
    ) {
      final FileHttpServer server = new FileHttpServer(PORT, Path.of(PACK_FILE_NAME));
      server.start();
      server.stop();

      verify(termination, times(2)).awaitUninterruptibly();
    }
  }

  private void configureTermination(final MultiThreadIoEventLoopGroup group) {
    final Future<Boolean> termination = ImmediateEventExecutor.INSTANCE.newSucceededFuture(Boolean.TRUE);
    doReturn(termination).when(group).shutdownGracefully(QUIET_PERIOD_SECONDS, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }
}
