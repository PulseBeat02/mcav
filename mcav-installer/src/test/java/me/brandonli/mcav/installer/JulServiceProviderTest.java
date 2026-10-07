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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Marker;
import org.slf4j.helpers.NOPMDCAdapter;

/**
 * Tests {@link JulServiceProvider}: the installer's messages reach {@code java.util.logging}, from its classes and from
 * the jar the build makes, whose bundled SLF4J would drop them otherwise.
 */
final class JulServiceProviderTest {

  private static final String NAME = "me.brandonli.mcav.installer.JulServiceProviderTest";
  private static final String TRACE = "trace {}";
  private static final String DEBUG = "debug {}";
  private static final String INFO = "info {} {}";
  private static final String WARN = "warn";
  private static final String ERROR = "error {}";

  private final List<LogRecord> records = new CopyOnWriteArrayList<>();

  private final Handler recorder = new Handler() {
    @Override
    public void publish(final LogRecord logRecord) {
      JulServiceProviderTest.this.records.add(logRecord);
    }

    @Override
    public void flush() {
      // the records are kept in memory
    }

    @Override
    public void close() {
      // nothing to release
    }
  };

  private final Logger jul = Logger.getLogger(NAME);

  @BeforeEach
  void listen() {
    this.jul.setLevel(Level.ALL);
    this.jul.setUseParentHandlers(false);
    this.jul.addHandler(this.recorder);
  }

  @AfterEach
  void stopListening() {
    this.jul.removeHandler(this.recorder);
    this.jul.setUseParentHandlers(true);
    this.jul.setLevel(null);
  }

  @Test
  void writesEveryLevelToJavaUtilLoggingWithItsArgumentsFormatted() {
    final JulServiceProvider provider = new JulServiceProvider();
    provider.initialize();
    final org.slf4j.Logger logger = provider.getLoggerFactory().getLogger(NAME); // fqn: SLF4J's logger beside java.util.logging's
    final IllegalStateException failure = new IllegalStateException("broken");
    logger.trace(TRACE, 1);
    logger.debug(DEBUG, 2);
    logger.info(INFO, 3, 4);
    logger.warn(WARN);
    logger.error(ERROR, 5, failure);

    final List<Level> levels = this.records.stream().map(LogRecord::getLevel).toList();
    assertEquals(List.of(Level.FINEST, Level.FINE, Level.INFO, Level.WARNING, Level.SEVERE), levels);
    final List<String> messages = this.records.stream().map(LogRecord::getMessage).toList();
    assertEquals(List.of("trace 1", "debug 2", "info 3 4", "warn", "error 5"), messages);
    assertSame(failure, this.records.getLast().getThrown());
    assertEquals(NAME, this.records.getFirst().getLoggerName());
  }

  @Test
  void isEnabledWhereJavaUtilLoggingIsAndKeepsNoContext() {
    final JulServiceProvider provider = new JulServiceProvider();
    final org.slf4j.Logger logger = provider.getLoggerFactory().getLogger(NAME); // fqn: SLF4J's logger beside java.util.logging's
    final Marker marker = provider.getMarkerFactory().getMarker("installer");
    this.jul.setLevel(Level.INFO);
    assertFalse(logger.isTraceEnabled(marker));
    assertFalse(logger.isDebugEnabled());
    assertTrue(logger.isInfoEnabled());
    assertTrue(logger.isWarnEnabled());
    assertTrue(logger.isErrorEnabled());
    this.jul.setLevel(Level.SEVERE);
    assertFalse(logger.isWarnEnabled());
    assertTrue(logger.isErrorEnabled(marker));
    this.jul.setLevel(Level.ALL);
    assertTrue(logger.isTraceEnabled());
    assertTrue(logger.isDebugEnabled(marker));

    assertEquals("installer", marker.getName());
    assertInstanceOf(NOPMDCAdapter.class, provider.getMDCAdapter());
    assertEquals(JulServiceProvider.API_VERSION, provider.getRequestedApiVersion());
    // the class SLF4J's helpers skip when they look for the caller
    assertEquals(JulLogger.class.getName(), new JulLogger(NAME).getFullyQualifiedCallerName());
  }

  @Test
  void theJarOfTheInstallerHandsItsMessagesToJavaUtilLogging() throws Exception {
    // the jar's SLF4J lives under the installer's own package names and finds the provider through its own service file
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
    assertEquals(List.of("from the jar 6"), messages);
  }
}
