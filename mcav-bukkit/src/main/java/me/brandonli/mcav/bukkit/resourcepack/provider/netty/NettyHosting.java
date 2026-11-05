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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.papermc.paper.network.ChannelInitializeListenerHolder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import me.brandonli.mcav.bukkit.utils.ServerAddress;
import me.brandonli.mcav.utils.http.NetworkUtils;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.intellij.lang.annotations.Subst;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves a resource pack over HTTP on the same port as the Minecraft server, so no extra port has to be opened
 * or forwarded.
 *
 * <p>A small handler is added to the front of every new connection using Paper's channel initializer API. The
 * handler looks at the first bytes a client sends: HTTP {@code GET} and {@code HEAD} requests are answered with the
 * resource pack, for this instance's URL, and other requests pass to the next handler. Minecraft connections pass
 * through untouched.
 *
 * <p>Connections that arrive through a proxy using the PROXY protocol, or that are terminated by a proxy such as
 * Velocity, never reach this server as HTTP and cannot be served. Use a separate web server in that case.
 *
 * <p>The caller retains ownership of the source file. This class is thread-safe: start and shutdown are synchronized
 * and support reuse. The hosting instance registers a connection initializer but does not own the Minecraft listener.
 * Shutdown removes that registration; already-installed handlers on existing connections retain their behavior.
 *
 * @since 1.0.0
 */
public final class NettyHosting implements InjectorHosting {

  private static final Logger LOGGER = LoggerFactory.getLogger(NettyHosting.class);

  private static final String KEY_NAMESPACE = "mcav";
  private static final String KEY_VALUE_PREFIX = "resourcepack_";

  private static final String REQUEST_DIRECTORY = "/mcav/";
  private static final String PACK_EXTENSION = ".zip";

  private static final String HANDLER_NUMBER_SEPARATOR = "_";

  private static final String URL_SCHEME = "http://";
  private static final String PORT_SEPARATOR = ":";

  private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();

  private final Path zip;
  private final Key listenerKey;
  private final String handlerName;
  private final String requestPath;
  private final ResourcePackFile packFile;

  private boolean running;

  private volatile @Nullable String url;

  /**
   * Creates a hosting for the given resource pack. Nothing is served until {@link #start()} is called.
   *
   * @param zip the path to the resource pack zip to serve
   * @throws NullPointerException if {@code zip} is null
   * @since 1.0.0
   */
  public NettyHosting(final Path zip) {
    Preconditions.checkNotNull(zip, "Resource pack path must not be null!");

    final int instanceNumber = INSTANCE_COUNTER.incrementAndGet();
    @Subst("resourcepack_1")
    final String keyValue = KEY_VALUE_PREFIX + instanceNumber;
    this.requestPath = REQUEST_DIRECTORY + keyValue + PACK_EXTENSION;
    this.zip = zip;
    this.listenerKey = Key.key(KEY_NAMESPACE, keyValue);
    // several instances may run and a pipeline rejects duplicate handler names, so the name carries the instance number, as the key does
    this.handlerName = ResourcePackHttpHandler.NAME + HANDLER_NUMBER_SEPARATOR + instanceNumber;
    this.packFile = new ResourcePackFile(zip);
  }

  /**
   * Starts answering HTTP requests on the Minecraft port. Calling this method again while running has no effect.
   *
   * <p>The initial pack read is attempted on the calling thread. A read failure is logged and hosting still
   * starts; a later download attempts to read it again and reports a failure to that HTTP client if necessary.
   *
   * @throws InjectorException if the path is not a regular file, including on a repeated start
   * @since 1.0.0
   */
  @Override
  public synchronized void start() {
    final boolean regularFile = Files.isRegularFile(this.zip);
    if (!regularFile) {
      final String message = "Resource pack %s is not a regular file!".formatted(this.zip);
      throw new InjectorException(message);
    }

    if (this.running) {
      return;
    }

    this.readPackAhead();
    ChannelInitializeListenerHolder.addListener(this.listenerKey, this::installHandler);
    this.running = true;
  }

  /**
   * Stops answering HTTP requests for new connections. Connections that are already open are not affected.
   *
   * @since 1.0.0
   */
  @Override
  public synchronized void shutdown() {
    if (this.running) {
      ChannelInitializeListenerHolder.removeListener(this.listenerKey);
      this.running = false;
    }
  }

  // keeps the first read off the Netty thread that carries every player's packets
  private void readPackAhead() {
    try {
      this.packFile.read();
    } catch (final IOException exception) {
      LOGGER.warn("Could not read the resource pack {} ahead of the first download.", this.zip, exception);
    }
  }

  private void installHandler(final Channel channel) {
    final ChannelPipeline pipeline = channel.pipeline();
    final ResourcePackHttpHandler handler = new ResourcePackHttpHandler(this.packFile, this.requestPath);
    pipeline.addFirst(this.handlerName, handler);
  }

  /**
   * Returns the URL players download the resource pack from.
   *
   * <p>The URL consists of the public address of the server and the port of the Minecraft server, and a unique path
   * for this hosting instance. IPv6 addresses are enclosed in brackets. The public address is looked up the first
   * time this method is called, and the URL is cached once the address is known. While the address cannot be
   * determined, a URL with {@code localhost} is returned and the lookup is tried again on the next call.
   *
   * <p>The first address lookup may block. Obtaining a URL does not start hosting or verify external reachability.
   *
   * @return the URL of the resource pack, in the format {@code http://<address>:<port>/mcav/resourcepack_<instance>.zip}
   * @since 1.0.0
   */
  @Override
  public String getRawUrl() {
    final String cachedUrl = this.url;
    if (cachedUrl != null) {
      return cachedUrl;
    }

    final String address = ServerAddress.getPublicAddress();
    final int port = Bukkit.getPort();
    final String host = formatHost(address);
    final String builtUrl = URL_SCHEME + host + PORT_SEPARATOR + port + this.requestPath;
    final boolean fallback = ServerAddress.isFallbackAddress(address);
    if (!fallback) {
      this.url = builtUrl;
    }

    return builtUrl;
  }

  // RFC 3986 and RFC 6874: IPv6 in brackets, the zone id's percent sign escaped
  @VisibleForTesting
  static String formatHost(final String address) {
    return NetworkUtils.formatHostForUrl(address);
  }

  @VisibleForTesting
  String getHandlerName() {
    return this.handlerName;
  }

  /**
   * Returns the resource pack zip that is served. The file is read again whenever it changed.
   *
   * @return the path to the resource pack zip
   * @since 1.0.0
   */
  @Override
  public Path getZip() {
    return this.zip;
  }
}
