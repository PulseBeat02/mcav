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
package me.brandonli.mcav.bukkit.testing;

import com.google.common.base.MoreObjects;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.message.Message;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class LogCapture implements AutoCloseable {

  private final Logger logger;
  private final Level previousLevel;
  private final RecordingAppender appender;

  private LogCapture(final Logger logger) {
    this.logger = logger;
    this.previousLevel = logger.getLevel();
    this.appender = new RecordingAppender();
    this.appender.start();
    logger.addAppender(this.appender);
    logger.setLevel(Level.ALL);
  }

  public static LogCapture capture(final Class<?> type) {
    final Logger logger = (Logger) LogManager.getLogger(type);
    return new LogCapture(logger);
  }

  // returns the events in recorded order, those logged before the call included
  public RecordedEvent awaitEvent(final Duration timeout) throws InterruptedException {
    final long timeoutMilliseconds = timeout.toMillis();
    final RecordedEvent event = this.appender.arrivals.poll(timeoutMilliseconds, TimeUnit.MILLISECONDS);
    if (event == null) {
      final String message = "No event was logged before the %s timeout ran out!".formatted(timeout);
      throw new AssertionError(message);
    }

    return event;
  }

  @Override
  public void close() {
    this.logger.removeAppender(this.appender);
    this.logger.setLevel(this.previousLevel);
    this.appender.stop();
  }

  public List<RecordedEvent> getEvents() {
    return List.copyOf(this.appender.events);
  }

  public static final class RecordedEvent {

    private final Level level;
    private final String message;
    private final @Nullable Throwable thrown;

    RecordedEvent(final Level level, final String message, final @Nullable Throwable thrown) {
      this.level = level;
      this.message = message;
      this.thrown = thrown;
    }

    public Level getLevel() {
      return this.level;
    }

    public String getMessage() {
      return this.message;
    }

    public Optional<Throwable> getThrown() {
      return Optional.ofNullable(this.thrown);
    }

    @Override
    public boolean equals(final @Nullable Object other) {
      if (this == other) {
        return true;
      }

      if (!(other instanceof final RecordedEvent event)) {
        return false;
      }

      return this.level.equals(event.level) && this.message.equals(event.message) && Objects.equals(this.thrown, event.thrown);
    }

    @Override
    public int hashCode() {
      return Objects.hash(this.level, this.message, this.thrown);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("level", this.level).add("message", this.message).add("thrown", this.thrown).toString();
    }
  }

  private static final class RecordingAppender extends AbstractAppender {

    private static final String APPENDER_NAME = "mcav-test-capture";

    private final List<RecordedEvent> events;
    private final BlockingQueue<RecordedEvent> arrivals;

    RecordingAppender() {
      super(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY);
      this.events = new CopyOnWriteArrayList<>();
      this.arrivals = new LinkedBlockingQueue<>();
    }

    @Override
    public void append(final LogEvent event) {
      final Level level = event.getLevel();
      final Message message = event.getMessage();
      final String text = message.getFormattedMessage();
      final @Nullable Throwable thrown = event.getThrown();
      final RecordedEvent recorded = new RecordedEvent(level, text, thrown);
      this.events.add(recorded);
      this.arrivals.add(recorded);
    }
  }
}
