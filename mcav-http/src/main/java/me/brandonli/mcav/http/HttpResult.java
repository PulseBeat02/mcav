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
package me.brandonli.mcav.http;

import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Serves a web page that plays the audio of a pipeline in the browser, together with the title and thumbnail of
 * the current media.
 *
 * <p>The server is an audio filter: attach it to the audio pipeline of a player and every connected browser
 * receives the samples over a WebSocket at {@code /audio}. The page is served at the root of the server and the
 * media information at {@code /media}. By default the server listens on every network interface, because the
 * browsers of players connect from other machines; {@link HttpResultBuilder#bindAddress(java.net.InetAddress)}
 * restricts it to one interface. The host name only decides the URL returned by {@link #getFullUrl()}. On Linux
 * and macOS, ports below 1024 need administrator rights, so use a higher port.
 *
 * <pre><code>
 *   final HttpResult http = HttpResult.http("play.example.com", 8080);
 *   http.start();
 *   http.setCurrentMedia(dump);
 *   final AudioAttachableCallback audio = player.getAudioAttachableCallback();
 *   final AudioPipelineStep step = AudioPipelineStep.of(http);
 *   audio.attach(step);
 *   final String url = http.getFullUrl();
 *   System.out.println("Listen at " + url);
 * </code></pre>
 */
public interface HttpResult extends AudioFilter {
  /**
   * Creates a server on a port that listens on every network interface. Its {@link #getFullUrl()} names
   * {@code localhost}, which only works in a browser on this machine; give players on other machines the public
   * host name with {@link #http(String, int)}.
   *
   * @param port the port, from 1 to 65535
   * @return the server, not started yet
   */
  static HttpResult port(final int port) {
    return http("localhost", port);
  }

  /**
   * Creates a server on port 80 for a host name.
   *
   * @param domain the host name listeners use
   * @return the server, not started yet
   */
  static HttpResult domain(final String domain) {
    return http(domain, 80);
  }

  /**
   * Creates a server.
   *
   * @param domain the host name listeners use
   * @param port   the port, from 1 to 65535
   * @return the server, not started yet
   */
  static HttpResult http(final String domain, final int port) {
    Preconditions.checkNotNull(domain, "Domain must not be null");
    Preconditions.checkArgument(!domain.isBlank(), "Domain must not be blank");
    Preconditions.checkArgument(port > 0 && port <= 65535, "Port must be between 1 and 65535 but was %s", port);
    return new HttpResultImpl(domain, port, null);
  }

  /**
   * Creates a server that serves the web page from a directory instead of the copy bundled in the jar, which is
   * useful while developing the page.
   *
   * @param domain    the host name listeners use
   * @param port      the port, from 1 to 65535
   * @param directory the directory with the built page, such as {@code mcav-website/out}
   * @return the server, not started yet
   */
  static HttpResult http(final String domain, final int port, final Path directory) {
    Preconditions.checkNotNull(domain, "Domain must not be null");
    Preconditions.checkArgument(!domain.isBlank(), "Domain must not be blank");
    Preconditions.checkArgument(port > 0 && port <= 65535, "Port must be between 1 and 65535 but was %s", port);
    Preconditions.checkNotNull(directory, "Directory must not be null");
    return new HttpResultImpl(domain, port, directory);
  }

  /**
   * Creates a builder for a server with settings the other factories do not offer, such as the network interface
   * it listens on.
   *
   * @return a builder with the default settings
   */
  static HttpResultBuilder builder() {
    return new HttpResultBuilder();
  }

  /**
   * Starts the server. Blocks for a few seconds while the web server starts; calling it again has no effect.
   *
   * @throws HttpException if the server cannot be started, for example because the port is in use
   */
  void start();

  /**
   * Starts the server on another thread.
   *
   * @return a future that completes when the server accepts connections
   */
  default CompletableFuture<Void> startAsync() {
    return CompletableFuture.runAsync(this::start);
  }

  /**
   * Stops the server and disconnects every listener. The server can be started again afterwards. Stopping never
   * waits for a listener whose connection is stuck.
   */
  void stop();

  /**
   * Checks whether the server is running.
   *
   * @return true between {@link #start()} and {@link #stop()}
   */
  boolean isRunning();

  /**
   * Gets the number of browsers currently listening.
   *
   * @return the listener count
   */
  int getListenerCount();

  /**
   * Gets the address of the web page, built from the host name and the port. An IPv6 address as host name is put
   * in brackets, as URLs require.
   *
   * @return the URL listeners open
   */
  String getFullUrl();

  /**
   * Shows the title, artist, and thumbnail yt-dlp reported for the current media.
   *
   * @param dump the output of yt-dlp
   */
  void setCurrentMedia(final URLParseDump dump);

  /**
   * Shows information about the current media.
   *
   * @param info the information, or null to show that nothing is playing
   */
  void setCurrentMedia(final @Nullable MediaInfo info);

  /**
   * Gets the information shown about the current media.
   *
   * @return the information
   */
  MediaInfo getCurrentMedia();
}
