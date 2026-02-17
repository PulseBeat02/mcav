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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An interface for handling exceptions that occur during media playback.
 */
public interface ExceptionHandler {
  /**
   * Handles an exception that occurs during media playback.
   *
   * @return a predicate that determines whether the exception should be handled.
   */
  BiConsumer<String, Throwable> getExceptionHandler();

  /**
   * Sets a custom exception handler.
   *
   * @param exceptionHandler a BiConsumer that takes a String (the media identifier) and a Throwable (the exception).
   */
  void setExceptionHandler(final BiConsumer<String, Throwable> exceptionHandler);

  /**
   * Creates a default ExceptionHandler that logs exceptions using SLF4J.
   *
   * @return a default ExceptionHandler instance.
   */
  static ExceptionHandler createDefault() {
    final Logger logger = LoggerFactory.getLogger(ExceptionHandler.class);
    return new ExceptionHandler() {
      private volatile BiConsumer<String, Throwable> exceptionHandler = logger::error;

      @Override
      public BiConsumer<String, Throwable> getExceptionHandler() {
        return this.exceptionHandler;
      }

      @Override
      public void setExceptionHandler(final BiConsumer<String, Throwable> exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
      }
    };
  }
}
