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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import me.brandonli.mcav.installer.testing.RecordingHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Marker;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;

final class JulServiceProviderTest {

  private static final String NAME = "me.brandonli.mcav.installer.JulServiceProviderTest";
  private static final String TRACE = "trace {}";
  private static final String DEBUG = "debug {}";
  private static final String INFO = "info {} {}";
  private static final String WARN = "warn";
  private static final String ERROR = "error {}";

  private RecordingHandler recorder;
  private List<LogRecord> records;
  private Logger recordedLogger;

  @BeforeEach
  void listen() {
    this.recorder = new RecordingHandler();
    this.records = this.recorder.getRecords();
    this.recordedLogger = Logger.getLogger(NAME);
    this.recordedLogger.setLevel(Level.ALL);
    this.recordedLogger.setUseParentHandlers(false);
    this.recordedLogger.addHandler(this.recorder);
  }

  @AfterEach
  void stopListening() {
    this.recordedLogger.removeHandler(this.recorder);
    this.recordedLogger.setUseParentHandlers(true);
    this.recordedLogger.setLevel(null);
  }

  @Test
  void shouldWriteEveryLevelToJavaUtilLoggingWithItsArgumentsFormatted() {
    final JulServiceProvider provider = new JulServiceProvider();
    provider.initialize();
    final org.slf4j.Logger logger = provider.getLoggerFactory().getLogger(NAME); // fqn: SLF4J's logger beside java.util.logging's
    final IllegalStateException failure = new IllegalStateException("Broken!");
    logger.trace(TRACE, 1);
    logger.debug(DEBUG, 2);
    logger.info(INFO, 3, 4);
    logger.warn(WARN);
    logger.error(ERROR, 5, failure);

    final List<Level> levels = this.records.stream().map(LogRecord::getLevel).toList();
    final List<Level> expectedLevels = List.of(Level.FINEST, Level.FINE, Level.INFO, Level.WARNING, Level.SEVERE);
    assertEquals(expectedLevels, levels);

    final List<String> messages = this.records.stream().map(LogRecord::getMessage).toList();
    final List<String> expectedMessages = List.of("trace 1", "debug 2", "info 3 4", "warn", "error 5");
    assertEquals(expectedMessages, messages);

    final LogRecord last = this.records.getLast();
    final Throwable thrown = last.getThrown();
    assertSame(failure, thrown);

    final LogRecord first = this.records.getFirst();
    final String loggerName = first.getLoggerName();
    assertEquals(NAME, loggerName);
  }

  @Test
  void shouldBeEnabledWhereJavaUtilLoggingIsAndKeepNoContext() {
    final JulServiceProvider provider = new JulServiceProvider();
    final org.slf4j.Logger logger = provider.getLoggerFactory().getLogger(NAME); // fqn: SLF4J's logger beside java.util.logging's
    final Marker marker = provider.getMarkerFactory().getMarker("installer");
    this.recordedLogger.setLevel(Level.INFO);
    final boolean traceAtInfo = logger.isTraceEnabled(marker);
    assertFalse(traceAtInfo);

    final boolean debugAtInfo = logger.isDebugEnabled();
    assertFalse(debugAtInfo);

    final boolean infoAtInfo = logger.isInfoEnabled();
    assertTrue(infoAtInfo);

    final boolean warnAtInfo = logger.isWarnEnabled();
    assertTrue(warnAtInfo);

    final boolean errorAtInfo = logger.isErrorEnabled();
    assertTrue(errorAtInfo);

    this.recordedLogger.setLevel(Level.SEVERE);
    final boolean warnAtSevere = logger.isWarnEnabled();
    assertFalse(warnAtSevere);

    final boolean errorAtSevere = logger.isErrorEnabled(marker);
    assertTrue(errorAtSevere);

    this.recordedLogger.setLevel(Level.ALL);
    final boolean traceAtAll = logger.isTraceEnabled();
    assertTrue(traceAtAll);

    final boolean debugAtAll = logger.isDebugEnabled(marker);
    assertTrue(debugAtAll);

    final String markerName = marker.getName();
    assertEquals("installer", markerName);

    final MDCAdapter context = provider.getMDCAdapter();
    assertInstanceOf(NOPMDCAdapter.class, context);

    final String apiVersion = provider.getRequestedApiVersion();
    assertEquals(JulServiceProvider.API_VERSION, apiVersion);

    final JulLogger julLogger = new JulLogger(NAME);
    final String callerName = julLogger.getFullyQualifiedCallerName();
    final String expectedCallerName = JulLogger.class.getName();
    assertEquals(expectedCallerName, callerName);
  }

  @Test
  void shouldHandTheMessagesOfTheInstallerJarToJavaUtilLogging() throws Exception {
    final String path = System.getProperty("mcav.installer.jar");
    assertNotNull(path, "the build passes the jar it built");

    final URL[] jar = { Path.of(path).toUri().toURL() };
    try (final URLClassLoader loader = new URLClassLoader(jar, ClassLoader.getPlatformClassLoader())) {
      final Class<?> factory = loader.loadClass("me.brandonli.mcav.libs.org.slf4j.LoggerFactory");
      final Class<?> loggerType = loader.loadClass("me.brandonli.mcav.libs.org.slf4j.Logger");
      final Object logger = factory.getMethod("getLogger", String.class).invoke(null, NAME);
      loggerType.getMethod("info", String.class, Object.class).invoke(logger, "from the jar {}", 6);
    }

    final List<String> messages = this.records.stream().map(LogRecord::getMessage).toList();
    final List<String> expectedMessages = List.of("from the jar 6");
    assertEquals(expectedMessages, messages);
  }
}
