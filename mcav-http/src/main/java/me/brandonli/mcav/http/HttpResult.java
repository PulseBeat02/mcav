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

import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;

/**
 * Represents an HTTP-based audio filter that can stream PCM audio data into web browsers. Uses Javalin for
 * serving HTTP pages and WebSocket connections for streaming.
 * <p>
 * Here is an example of how to use it:
 *
 * <pre><code>
 *   final HttpResult result = HttpResult.port(3000);
 *   final AudioPipelineStep audioPipelineStep = AudioPipelineStep.of(result);
 *   ...
 * </code></pre>
 */
public interface HttpResult extends AudioFilter {
  /**
   * Starts the HTTP server and opens web socket connections for clients to receive audio data.
   */
  void start();

  /**
   * Stops the HTTP server and closes all web socket connections.
   */
  void stop();

  /**
   * Returns the full URL to the web page that streams audio data. In the format of
   * {@code http://<domain>:<port>/}
   *
   * @return the full URL to the web page
   */
  String getFullUrl();

  /**
   * Sets the current media information for serving.
   *
   * @param dump the URL parse dump containing information about the media
   */
  void setCurrentMedia(final URLParseDump dump);

  /**
   * Creates a new {@link HttpResult} instance with the default domain "localhost" and the specified port.
   *
   * @param port the port to bind the HTTP server to
   * @return a new {@link HttpResult} instance
   */
  static HttpResult port(final int port) {
    return http("localhost", port);
  }

  /**
   * Creates a new {@link HttpResult} instance with the specified domain and default port 80.
   *
   * @param domain the domain to bind the HTTP server to
   * @return a new {@link HttpResult} instance
   */
  static HttpResult domain(final String domain) {
    return http(domain, 80);
  }

  /**
   * Creates a new {@link HttpResult} instance with the specified domain and port.
   *
   * @param domain the domain to bind the HTTP server to
   * @param port   the port to bind the HTTP server to
   * @return a new {@link HttpResult} instance
   */
  static HttpResult http(final String domain, final int port) {
    return new HttpResultImpl(domain, port);
  }

  /**
   * Creates a new {@link HttpResult} instance with the specified domain, port, and HTML content.
   *
   * @param domain the domain to bind the HTTP server to
   * @param port   the port to bind the HTTP server to
   * @param html   the HTML content to serve
   * @return a new {@link HttpResult} instance
   */
  static HttpResult http(final String domain, final int port, final String html) {
    return new HttpResultImpl(domain, port, html);
  }
}
