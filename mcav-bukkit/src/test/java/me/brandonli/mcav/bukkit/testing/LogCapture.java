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

import java.time.Duration;
import java.util.List;
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

/**
 * Records what a class logs through SLF4J, which the Paper test runtime routes to Log4j.
 *
 * <p>Use it in a try-with-resources block. While it is open, every level is recorded for the logger of the class,
 * from any thread.
 */
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

  /**
   * Starts recording what the class logs.
   *
   * @param type the class whose logger is recorded
   * @return the capture, which must be closed
   */
  public static LogCapture capture(final Class<?> type) {
    final Logger logger = (Logger) LogManager.getLogger(type);
    return new LogCapture(logger);
  }

  /**
   * Gets the recorded events, in order.
   *
   * @return the events
   */
  public List<RecordedEvent> getEvents() {
    return List.copyOf(this.appender.events);
  }

  /**
   * Waits for the next event that has not been returned by this method yet. Events are handed out in the order
   * they were recorded, so the first call returns the first event, even if it was logged before the call.
   *
   * @param timeout the longest time to wait
   * @return the next event
   * @throws AssertionError       if no event was recorded before the timeout elapsed
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public RecordedEvent awaitEvent(final Duration timeout) throws InterruptedException {
    final long timeoutMillis = timeout.toMillis();
    final RecordedEvent event = this.appender.arrivals.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    if (event == null) {
      throw new AssertionError("No event was logged within " + timeout);
    }
    return event;
  }

  /**
   * Stops recording.
   */
  @Override
  public void close() {
    this.logger.removeAppender(this.appender);
    this.logger.setLevel(this.previousLevel);
    this.appender.stop();
  }

  /**
   * One recorded log event.
   */
  public static final class RecordedEvent {

    private final Level level;
    private final String message;
    private final Throwable thrown;

    RecordedEvent(final Level level, final String message, final Throwable thrown) {
      this.level = level;
      this.message = message;
      this.thrown = thrown;
    }

    /**
     * Gets the level of the event.
     *
     * @return the level
     */
    public Level getLevel() {
      return this.level;
    }

    /**
     * Gets the formatted message of the event.
     *
     * @return the message
     */
    public String getMessage() {
      return this.message;
    }

    /**
     * Gets the exception logged with the event.
     *
     * @return the exception, or null if there is none
     */
    public Throwable getThrown() {
      return this.thrown;
    }
  }

  /**
   * An appender that keeps every event in memory.
   */
  private static final class RecordingAppender extends AbstractAppender {

    private final List<RecordedEvent> events;
    private final BlockingQueue<RecordedEvent> arrivals;

    RecordingAppender() {
      super("mcav-test-capture", null, null, true, Property.EMPTY_ARRAY);
      this.events = new CopyOnWriteArrayList<>();
      this.arrivals = new LinkedBlockingQueue<>();
    }

    @Override
    public void append(final LogEvent event) {
      final Level level = event.getLevel();
      final Message message = event.getMessage();
      final String text = message.getFormattedMessage();
      final Throwable thrown = event.getThrown();
      final RecordedEvent recorded = new RecordedEvent(level, text, thrown);
      this.events.add(recorded);
      this.arrivals.add(recorded);
    }
  }
}
