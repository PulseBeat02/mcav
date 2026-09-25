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
 * resource pack, for this instance's URL, and other requests pass to the next handler. Minecraft connections pass through untouched. See {@link ResourcePackHttpHandler}
 * for details.
 *
 * <p>Connections that arrive through a proxy using the PROXY protocol, or that are terminated by a proxy such as
 * Velocity, never reach this server as HTTP and cannot be served. Use a separate web server in that case.
 */
public final class NettyHosting implements InjectorHosting {

  private static final Logger LOGGER = LoggerFactory.getLogger(NettyHosting.class);
  private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();
  private static final String KEY_NAMESPACE = "mcav";

  private final Path zip;
  private final Key listenerKey;
  private final String handlerName;
  private final String requestPath;
  private final ResourcePackFile packFile;
  private boolean running;

  private volatile @Nullable String url;

  /**
   * Constructs a new {@code NettyHosting} for the specified resource pack. Nothing is served until
   * {@link #start()} is called.
   *
   * @param zip the path to the resource pack zip to serve
   */
  public NettyHosting(final Path zip) {
    Preconditions.checkNotNull(zip, "Resource pack path must not be null");
    final int instanceNumber = INSTANCE_COUNTER.incrementAndGet();
    @Subst("resourcepack_1")
    final String keyValue = "resourcepack_%d".formatted(instanceNumber);
    this.requestPath = "/mcav/" + keyValue + ".zip";
    this.zip = zip;
    this.listenerKey = Key.key(KEY_NAMESPACE, keyValue);
    // a Netty pipeline rejects two handlers of the same name, and this class supports several running instances,
    // so the name carries the instance number exactly as the listener key does
    this.handlerName = ResourcePackHttpHandler.NAME + "_" + instanceNumber;
    this.packFile = new ResourcePackFile(zip);
  }

  /**
   * Gets the URL players download the resource pack from.
   *
   * <p>The URL consists of the public address of the server and the port of the Minecraft server, and a unique path for this hosting instance. IPv6 addresses
   * are enclosed in brackets. The public address is looked up the first time this method is called, and the URL is
   * cached once the address is known. While the address cannot be determined, a URL with {@code localhost} is
   * returned and the lookup is tried again on the next call.
   *
   * @return the URL of the resource pack, in the format {@code http://<address>:<port>/mcav/resourcepack_<instance>.zip}
   */
  @Override
  public String getRawUrl() {
    final String cachedUrl = this.url;
    if (cachedUrl != null) {
      return cachedUrl;
    }

    final String address = ServerAddress.getPublicIPAddress();
    final int port = Bukkit.getPort();
    final String host = formatHost(address);
    final String builtUrl = "http://%s:%d%s".formatted(host, port, this.requestPath);
    final boolean fallback = ServerAddress.isFallbackAddress(address);
    if (!fallback) {
      this.url = builtUrl;
    }
    return builtUrl;
  }

  /**
   * Formats an address as the host of a URL. IPv6 addresses are enclosed in brackets and the percent sign of a
   * zone id is escaped, as required by RFC 3986 and RFC 6874. Other addresses and host names are returned as is.
   *
   * @param address the address or host name
   * @return the host part of a URL
   */
  @VisibleForTesting
  static String formatHost(final String address) {
    return NetworkUtils.formatHostForUrl(address);
  }

  /**
   * Starts answering HTTP requests on the Minecraft port. Calling this method again while running has no effect.
   *
   * @throws InjectorException if the resource pack file does not exist
   */
  @Override
  public synchronized void start() {
    final boolean exists = Files.isRegularFile(this.zip);
    if (!exists) {
      final String message = "Resource pack does not exist: %s".formatted(this.zip);
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
   * Reads the pack here, on the thread that starts the hosting, so the first download does not read the whole file on
   * the Netty thread that also carries the packets of every player. A pack that cannot be read is reported when the
   * download is answered, exactly as before, so hosting still starts.
   */
  private void readPackAhead() {
    try {
      this.packFile.read();
    } catch (final IOException failure) {
      LOGGER.warn("Could not read the resource pack {} ahead of the first download", this.zip, failure);
    }
  }

  /**
   * Gets the name this instance installs its handler under in the Netty pipeline. Visible for testing.
   *
   * @return the pipeline name, which is unique per instance
   */
  @VisibleForTesting
  String getHandlerName() {
    return this.handlerName;
  }

  private void installHandler(final Channel channel) {
    final ChannelPipeline pipeline = channel.pipeline();
    final ResourcePackHttpHandler handler = new ResourcePackHttpHandler(this.packFile, this.requestPath);
    pipeline.addFirst(this.handlerName, handler);
  }

  /**
   * Stops answering HTTP requests for new connections. Connections that are already open are not affected.
   */
  @Override
  public synchronized void shutdown() {
    if (this.running) {
      ChannelInitializeListenerHolder.removeListener(this.listenerKey);
      this.running = false;
    }
  }

  /**
   * Gets the resource pack zip that is served. The file is read again whenever it changed.
   *
   * @return the path to the resource pack zip
   */
  @Override
  public Path getZip() {
    return this.zip;
  }
}
