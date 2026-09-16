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

import com.google.common.annotations.VisibleForTesting;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A tiny HTTP server that serves exactly one file on its own port.
 *
 * <p>The server uses one thread to accept connections and two threads to serve them. Files are sent with zero-copy
 * transfers, so even large resource packs are served without loading them into memory. The server binds
 * synchronously, which means {@link #start()} fails immediately with an exception if the port is already in use.
 * This class is thread-safe.
 */
final class FileHttpServer {

  private static final int ACCEPTOR_THREADS = 1;
  private static final int WORKER_THREADS = 2;
  private static final String THREAD_NAME = "mcav-pack-http";
  private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

  private final int port;
  private final Path filePath;

  private @Nullable EventLoopGroup acceptorGroup;
  private @Nullable EventLoopGroup workerGroup;
  private @Nullable Channel serverChannel;

  /**
   * Constructs a new server. Nothing is bound until {@link #start()} is called.
   *
   * @param port     the port to listen on
   * @param filePath the file to serve
   */
  FileHttpServer(final int port, final Path filePath) {
    this.port = port;
    this.filePath = filePath;
  }

  /**
   * Binds the server to its port and starts serving the file. Calling this method while the server is running has
   * no effect.
   *
   * @throws HttpServerException if the server cannot bind to the port
   */
  synchronized void start() {
    if (this.serverChannel != null) {
      return;
    }

    final DefaultThreadFactory threadFactory = new DefaultThreadFactory(THREAD_NAME, true);
    final IoHandlerFactory ioHandlerFactory = NioIoHandler.newFactory();
    final EventLoopGroup acceptors = new MultiThreadIoEventLoopGroup(ACCEPTOR_THREADS, threadFactory, ioHandlerFactory);
    final EventLoopGroup workers = new MultiThreadIoEventLoopGroup(WORKER_THREADS, threadFactory, ioHandlerFactory);

    final ChannelFuture bindFuture = this.bind(acceptors, workers);
    final boolean bound = bindFuture.isSuccess();
    if (!bound) {
      terminate(acceptors);
      terminate(workers);
      final Throwable cause = bindFuture.cause();
      final String message = "Failed to start the resource pack server on port %d".formatted(this.port);
      throw new HttpServerException(message, cause);
    }

    this.acceptorGroup = acceptors;
    this.workerGroup = workers;
    this.serverChannel = bindFuture.channel();
  }

  private ChannelFuture bind(final EventLoopGroup acceptors, final EventLoopGroup workers) {
    final FileHttpChannelInitializer initializer = new FileHttpChannelInitializer(this.filePath);
    final ServerBootstrap bootstrap = new ServerBootstrap();
    bootstrap.group(acceptors, workers);
    bootstrap.channel(NioServerSocketChannel.class);
    bootstrap.childHandler(initializer);
    final ChannelFuture bindFuture = bootstrap.bind(this.port);
    bindFuture.awaitUninterruptibly();
    return bindFuture;
  }

  /**
   * Gets the port the server is bound to. This is the configured port, unless the server was configured with port
   * {@code 0}, in which case the operating system picked a free port.
   *
   * @return the bound port
   * @throws IllegalStateException if the server is not running
   */
  @VisibleForTesting
  synchronized int getLocalPort() {
    final Channel channel = this.serverChannel;
    if (channel == null) {
      throw new IllegalStateException("The resource pack server is not running");
    }
    final SocketAddress address = channel.localAddress();
    final InetSocketAddress socketAddress = (InetSocketAddress) address;
    return socketAddress.getPort();
  }

  /**
   * Stops the server and releases its threads. Calling this method while the server is stopped has no effect.
   */
  synchronized void stop() {
    final Channel channel = this.serverChannel;
    if (channel != null) {
      final ChannelFuture closeFuture = channel.close();
      closeFuture.awaitUninterruptibly();
      this.serverChannel = null;
    }
    final EventLoopGroup acceptors = this.acceptorGroup;
    if (acceptors != null) {
      terminate(acceptors);
      this.acceptorGroup = null;
    }
    final EventLoopGroup workers = this.workerGroup;
    if (workers != null) {
      terminate(workers);
      this.workerGroup = null;
    }
  }

  /**
   * Shuts the threads down without a quiet period and waits until they are gone. The listening socket is only
   * released once its event loop has finished, so waiting here lets the port be bound again right away.
   */
  private static void terminate(final EventLoopGroup group) {
    final Future<?> termination = group.shutdownGracefully(0, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    termination.awaitUninterruptibly();
  }
}
