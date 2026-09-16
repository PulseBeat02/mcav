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
import com.google.common.net.InetAddresses;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.papermc.paper.network.ChannelInitializeListenerHolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import me.brandonli.mcav.bukkit.utils.ServerAddress;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.intellij.lang.annotations.Subst;

/**
 * Serves a resource pack over HTTP on the same port as the Minecraft server, so no extra port has to be opened
 * or forwarded.
 *
 * <p>A small handler is added to the front of every new connection using Paper's channel initializer API. The
 * handler looks at the first bytes a client sends: HTTP {@code GET} and {@code HEAD} requests are answered with the
 * resource pack, and every other connection is handed to Minecraft untouched. See {@link ResourcePackHttpHandler}
 * for details.
 *
 * <p>Connections that arrive through a proxy using the PROXY protocol, or that are terminated by a proxy such as
 * Velocity, never reach this server as HTTP and cannot be served. Use a separate web server in that case.
 */
public final class NettyHosting implements InjectorHosting {

  private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();
  private static final String KEY_NAMESPACE = "mcav";

  private final Path zip;
  private final Key listenerKey;
  private final ResourcePackFile packFile;
  private final AtomicBoolean running;

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
    this.zip = zip;
    this.listenerKey = Key.key(KEY_NAMESPACE, keyValue);
    this.packFile = new ResourcePackFile(zip);
    this.running = new AtomicBoolean(false);
  }

  /**
   * Gets the URL players download the resource pack from.
   *
   * <p>The URL consists of the public address of the server and the port of the Minecraft server. IPv6 addresses
   * are enclosed in brackets. The public address is looked up the first time this method is called, and the URL is
   * cached once the address is known. While the address cannot be determined, a URL with {@code localhost} is
   * returned and the lookup is tried again on the next call.
   *
   * @return the URL of the resource pack, in the format {@code http://<address>:<port>}
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
    final String builtUrl = "http://%s:%d".formatted(host, port);
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
    final boolean ipAddress = InetAddresses.isInetAddress(address);
    final boolean ipv6 = ipAddress && address.contains(":");
    if (!ipv6) {
      return address;
    }
    final String escaped = address.replace("%", "%25");
    return "[" + escaped + "]";
  }

  /**
   * Starts answering HTTP requests on the Minecraft port. Calling this method again while running has no effect.
   *
   * @throws InjectorException if the resource pack file does not exist
   */
  @Override
  public void start() {
    final boolean exists = Files.isRegularFile(this.zip);
    if (!exists) {
      final String message = "Resource pack does not exist: %s".formatted(this.zip);
      throw new InjectorException(message);
    }

    final boolean started = this.running.compareAndSet(false, true);
    if (!started) {
      return;
    }
    ChannelInitializeListenerHolder.addListener(this.listenerKey, this::installHandler);
  }

  private void installHandler(final Channel channel) {
    final ChannelPipeline pipeline = channel.pipeline();
    final ResourcePackHttpHandler handler = new ResourcePackHttpHandler(this.packFile);
    pipeline.addFirst(ResourcePackHttpHandler.NAME, handler);
  }

  /**
   * Stops answering HTTP requests for new connections. Connections that are already open are not affected.
   */
  @Override
  public void shutdown() {
    final boolean stopped = this.running.compareAndSet(true, false);
    if (stopped) {
      ChannelInitializeListenerHolder.removeListener(this.listenerKey);
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
