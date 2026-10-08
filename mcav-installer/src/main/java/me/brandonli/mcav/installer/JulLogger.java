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
package me.brandonli.mcav.installer;

import java.io.Serial;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.slf4j.Marker;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

/**
 * An SLF4J logger that writes to the {@code java.util.logging} logger of its name: trace as {@code FINEST}, debug as
 * {@code FINE}, info as {@code INFO}, warn as {@code WARNING} and error as {@code SEVERE}, its arguments formatted the
 * way SLF4J formats them.
 */
final class JulLogger extends LegacyAbstractLogger {

  @Serial
  private static final long serialVersionUID = 1L;

  private final transient Logger logger;

  /**
   * Creates the logger of a name.
   *
   * @param name the name, which is also the name of the {@code java.util.logging} logger written to
   */
  JulLogger(final String name) {
    this.name = name;
    this.logger = Logger.getLogger(name);
  }

  @Override
  public boolean isTraceEnabled() {
    return this.logger.isLoggable(Level.FINEST);
  }

  @Override
  public boolean isDebugEnabled() {
    return this.logger.isLoggable(Level.FINE);
  }

  @Override
  public boolean isInfoEnabled() {
    return this.logger.isLoggable(Level.INFO);
  }

  @Override
  public boolean isWarnEnabled() {
    return this.logger.isLoggable(Level.WARNING);
  }

  @Override
  public boolean isErrorEnabled() {
    return this.logger.isLoggable(Level.SEVERE);
  }

  @Override
  protected String getFullyQualifiedCallerName() {
    return JulLogger.class.getName();
  }

  @Override
  protected void handleNormalizedLoggingCall(
    final org.slf4j.event.Level level, // fqn: SLF4J's level beside java.util.logging's
    final Marker marker,
    final String messagePattern,
    final Object[] arguments,
    final Throwable throwable
  ) {
    final String message = MessageFormatter.basicArrayFormat(messagePattern, arguments);
    this.logger.log(levelOf(level), message, throwable);
  }

  private static Level levelOf(
    final org.slf4j.event.Level level // fqn: SLF4J's level beside java.util.logging's
  ) {
    return switch (level) {
      case ERROR -> Level.SEVERE;
      case WARN -> Level.WARNING;
      case INFO -> Level.INFO;
      case DEBUG -> Level.FINE;
      case TRACE -> Level.FINEST;
    };
  }
}
