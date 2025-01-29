/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.resourcepack.provider.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import me.brandonli.mcav.utils.ExecutorUtils;
import org.jetbrains.annotations.NotNull;

/**
 * A server implementation that serves files over HTTP using Netty framework.
 * <p>
 * This class is responsible for setting up an HTTP server that binds to a specified port and serves
 * a file from a provided file path. It features asynchronous operations and handles server events
 * such as starting and stopping the server.
 * <p>
 * The server uses a single-threaded executor service to manage asynchronous tasks and integrates
 * Netty's channel pipeline for handling HTTP requests. The server lifecycle is tied to methods
 * that start and stop the HTTP server.
 * <p>
 * Features include:
 * - Asynchronous server startup using a `CompletableFuture`
 * - Graceful shutdown of server resources and thread pools
 * - Customizable Netty event loop groups for handling server channels
 */
public final class FileHttpServer {

  private final int port;
  private final Path filePath;
  private final ExecutorService service;

  private EventLoopGroup bossGroup;
  private EventLoopGroup workerGroup;

  /**
   * Constructs a new {@code FileHttpServer} instance for serving files over HTTP.
   * This server operates on a specified port and serves files from a specified path.
   *
   * @param port     the port number on which the server will listen for HTTP requests
   * @param filePath the path to the file to be served by the HTTP server
   */
  public FileHttpServer(final int port, final Path filePath) {
    this.port = port;
    this.filePath = filePath;
    this.service = Executors.newSingleThreadExecutor();
  }

  /**
   * Starts the file HTTP server asynchronously using Netty.
   * This method is responsible for initializing and bootstrapping the server,
   * binding it to the configured port, and managing its lifecycle.
   * <p>
   * The server is run on an asynchronous task using a dedicated executor
   * service to avoid blocking the calling thread. A countdown latch is used
   * to ensure that the server binding process completes before proceeding.
   * <p>
   * The server listens for incoming connections, processes requests, and serves
   * files over HTTP. Once the server is initiated, it waits for the channel's
   * close future to complete, ensuring proper resource cleanup by shutting down
   * thread pools and event loops.
   * <p>
   * Throws an {@link AssertionError} in case of interruptions or unexpected
   * errors, interrupting the current thread as part of exception handling.
   * Additionally, server shutdown is performed gracefully in case of any failures.
   */
  public void start() {
    final CountDownLatch latch = new CountDownLatch(1);
    CompletableFuture.runAsync(
      () -> {
        try {
          final ServerBootstrap b = this.initializeServerBootstrap();
          final ChannelFuture before = this.addServerListener(b, latch);
          final ChannelFuture f = before.sync();
          final Channel channel = f.channel();
          final ChannelFuture closeFuture = channel.closeFuture();
          closeFuture.sync();
        } catch (final InterruptedException e) {
          final Thread current = Thread.currentThread();
          current.interrupt();
          throw new AssertionError(e);
        } finally {
          this.stop();
        }
      },
      this.service
    );
    try {
      latch.await();
    } catch (final InterruptedException e) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      throw new AssertionError(e);
    }
  }

  private @NotNull ChannelFuture addServerListener(final ServerBootstrap b, final CountDownLatch latch) {
    final ChannelFuture before = b.bind(this.port);
    final ChannelFutureListener listener = getChannelFutureListener(latch);
    before.addListener(listener);
    return before;
  }

  private @NotNull ServerBootstrap initializeServerBootstrap() {
    final FileHttpChannelInitializer initializer = new FileHttpChannelInitializer(this);
    final ServerBootstrap b = new ServerBootstrap();
    this.bossGroup = new NioEventLoopGroup();
    this.workerGroup = new NioEventLoopGroup();
    b.group(this.bossGroup, this.workerGroup).channel(NioServerSocketChannel.class).childHandler(initializer);
    return b;
  }

  private static @NotNull ChannelFutureListener getChannelFutureListener(final CountDownLatch latch) {
    return future -> {
      if (future.isSuccess()) {
        latch.countDown();
      } else {
        final Throwable cause = future.cause();
        throw new AssertionError(cause);
      }
    };
  }

  /**
   * Stops the file HTTP server and releases all allocated resources.
   * <p>
   * This method shuts down the Netty event loop groups (`bossGroup` and `workerGroup`)
   * gracefully to ensure proper cleanup of threads and resources used by the server.
   * Additionally, it gracefully shuts down the executor service used for asynchronous
   * tasks, ensuring that all tasks are handled appropriately before termination.
   * <p>
   * If any of the server resources (e.g., event loop groups or executor service)
   * remain uninitialized, this method safely skips their shutdown to avoid null-related
   * issues.
   */
  public void stop() {
    if (this.bossGroup != null) {
      this.bossGroup.shutdownGracefully();
    }
    if (this.workerGroup != null) {
      this.workerGroup.shutdownGracefully();
    }
    ExecutorUtils.shutdownExecutorGracefully(this.service);
  }

  /**
   * Retrieves the port number on which the HTTP server is configured to listen
   * for incoming connections.
   *
   * @return the port number as an integer.
   */
  public int getPort() {
    return this.port;
  }

  /**
   * Retrieves the file path associated with this server.
   *
   * @return the file path as a {@link Path} object, representing the location
   * of the file being served.
   */
  public Path getFilePath() {
    return this.filePath;
  }

  /**
   * Retrieves the {@link ExecutorService} instance associated with this server.
   * The executor service is responsible for asynchronous task execution
   * within the server's lifecycle.
   *
   * @return the {@link ExecutorService} used by this server for managing asynchronous tasks.
   */
  public ExecutorService getService() {
    return this.service;
  }

  /**
   * Retrieves the boss group associated with the server.
   * The boss group is responsible for accepting incoming connections
   * and delegating them to the worker group for processing.
   *
   * @return the {@link EventLoopGroup} handling incoming connection acceptances.
   */
  public EventLoopGroup getBossGroup() {
    return this.bossGroup;
  }

  /**
   * Sets the boss group for the server.
   * The boss group is responsible for accepting incoming connections and delegating them
   * for further processing.
   *
   * @param bossGroup the {@link EventLoopGroup} instance to be set as the boss group
   */
  public void setBossGroup(final EventLoopGroup bossGroup) {
    this.bossGroup = bossGroup;
  }

  /**
   * Retrieves the worker group used by the server for handling worker threads.
   * The worker group is responsible for handling actual data I/O and processing
   * incoming requests on the server.
   *
   * @return the {@link EventLoopGroup} instance being used as the worker group for this server.
   */
  public EventLoopGroup getWorkerGroup() {
    return this.workerGroup;
  }

  /**
   * Sets the worker group responsible for handling network events for worker threads
   * in the Netty server.
   *
   * @param workerGroup the {@link EventLoopGroup} instance to be used as the worker group
   */
  public void setWorkerGroup(final EventLoopGroup workerGroup) {
    this.workerGroup = workerGroup;
  }
}
