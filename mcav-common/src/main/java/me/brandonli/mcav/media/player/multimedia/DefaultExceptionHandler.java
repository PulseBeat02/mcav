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

import com.google.common.base.Preconditions;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The handler holder created by {@link ExceptionHandler#createDefault()}. Failures are logged at error level
 * until a custom handler is set.
 */
public final class DefaultExceptionHandler implements ExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(DefaultExceptionHandler.class);

  private volatile BiConsumer<String, Throwable> handler;

  DefaultExceptionHandler() {
    this.handler = DefaultExceptionHandler::log;
  }

  private static void log(final String message, final Throwable error) {
    LOGGER.error(message, error);
  }

  /**
   * Gets the handler that receives failures, which logs them at error level until another handler is set.
   *
   * @return the current handler
   */
  @Override
  public BiConsumer<String, Throwable> getExceptionHandler() {
    return this.handler;
  }

  /**
   * Sets the handler that receives failures, replacing the logging default. The handler is called on the thread that
   * failed, so it must be fast and must not throw.
   *
   * @param exceptionHandler the new handler
   * @throws NullPointerException if the handler is null
   */
  @Override
  public void setExceptionHandler(final BiConsumer<String, Throwable> exceptionHandler) {
    Preconditions.checkNotNull(exceptionHandler, "Exception handler must not be null");
    this.handler = exceptionHandler;
  }
}
