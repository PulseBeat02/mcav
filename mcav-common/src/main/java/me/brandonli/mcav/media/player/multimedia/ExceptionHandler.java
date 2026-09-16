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
package me.brandonli.mcav.media.player.multimedia;

import java.util.function.BiConsumer;

/**
 * A player that reports failures on its background threads to a handler instead of dying silently. The handler
 * receives a short description of what failed and the exception; the default handler logs both.
 *
 * <pre><code>
 *   final VideoPlayerMultiplexer player = VideoPlayer.ffmpeg();
 *   final Logger logger = plugin.getLogger();
 *   player.setExceptionHandler((message, error) -&gt; logger.log(Level.SEVERE, message, error));
 * </code></pre>
 */
public interface ExceptionHandler {
  /**
   * Gets the handler that receives failures.
   *
   * @return the handler
   */
  BiConsumer<String, Throwable> getExceptionHandler();

  /**
   * Sets the handler that receives failures. The handler is called on the thread that failed, so it must be fast
   * and must not throw.
   *
   * @param exceptionHandler the handler
   */
  void setExceptionHandler(final BiConsumer<String, Throwable> exceptionHandler);

  /**
   * Creates a standalone handler holder that logs failures with SLF4J until another handler is set. Players use
   * it to implement this interface by delegation.
   *
   * @return a new handler holder
   */
  static ExceptionHandler createDefault() {
    return new DefaultExceptionHandler();
  }
}
