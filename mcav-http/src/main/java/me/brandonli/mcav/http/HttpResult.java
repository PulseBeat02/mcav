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
package me.brandonli.mcav.http;

import me.brandonli.mcav.media.player.pipeline.filter.audio.AudioFilter;

/**
 * Represents the result of an HTTP-based audio processing operation that extends
 * the capabilities of an {@link AudioFilter}.
 * <p>
 * This interface is primarily used to define behavior for managing HTTP services
 * that interact with processed audio data.
 * Implementations can apply audio filters, and they should also provide the ability
 * to stop the HTTP service or clean up associated resources.
 */
public interface HttpResult extends AudioFilter {
  /**
   * Starts the HTTP service to process and handle audio-related requests.
   * This method initializes the necessary resources and begins serving incoming
   * HTTP requests. It is typically called to make the service operational.
   * Implementations of this method should ensure that the service is correctly configured
   * and ready to handle requests.
   */
  void start();

  /**
   * Terminates the HTTP service and releases any resources associated with it.
   * This method stops the underlying HTTP server, ensuring that no further
   * requests can be handled. It is typically invoked when the service is no
   * longer needed or during application shutdown.
   * <p>
   * Implementations of this method should ensure that the cleanup process is
   * thread-safe and frees resources efficiently.
   */
  void stop();

  /**
   * Retrieves the full URL of the HTTP service, including the protocol, host, and port.
   * This method provides the complete address at which the HTTP server is accessible.
   *
   * @return the full URL as a String, representing the location of the HTTP service.
   */
  String getFullUrl();

  /**
   * Creates a new {@link HttpResult} instance that starts an HTTP server at the specified port
   * and provides endpoints for handling audio processing operations.
   *
   * @param port the port number on which the HTTP server will be started.
   *             Must be a valid TCP port within the range 0 to 65535.
   * @return a newly created {@link HttpResult} instance configured to run on the specified port.
   * The HTTP server will be started and accessible via the specified port.
   */
  static HttpResult port(final int port) {
    return new HttpResultImpl(port);
  }

  /**
   * Creates an {@link HttpResult} instance with the specified port and an HTML response
   * for the root endpoint. The HTTP server runs on the given port and responds with the
   * provided HTML content for the root ("/") path.
   *
   * @param port the HTTP port on which the server should run
   * @param html the HTML content to be served at the root endpoint
   * @return an instance of {@link HttpResult} that manages the HTTP service
   */
  static HttpResult port(final int port, final String html) {
    return new HttpResultImpl(port, html);
  }
}
