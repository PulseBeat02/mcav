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
package me.brandonli.mcav.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.browser.testing.TestPages;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proves that a server needs no JVM option for the browser: {@link NoFlagsServerMain} runs in a child JVM started with
 * {@code -Djava.awt.headless=true} and nothing else, the class path given through the {@code CLASSPATH} variable, and
 * without {@code DISPLAY} or {@code WAYLAND_DISPLAY}, like a headless Minecraft server. It streams a local page, checks
 * the pixels of a frame, and disables and enables mcav in between.
 */
@Tag("cef")
class NoFlagsServerTest {

  // the first run on a machine downloads the CEF natives, about 150 MB
  private static final long TIMEOUT_MINUTES = 15L;

  @Test
  void aHeadlessServerWithoutJvmOptionsStreamsAPageAndSurvivesADisableAndEnable() throws Exception {
    try (final TestPages pages = TestPages.start()) {
      final String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
      final Path java = Path.of(System.getProperty("java.home"), "bin", executable);
      final List<String> command = List.of(
        java.toString(),
        "-Djava.awt.headless=true",
        NoFlagsServerMain.class.getName(),
        pages.uri("/main").toString()
      );
      final ProcessBuilder builder = new ProcessBuilder(command);
      final Map<String, String> environment = builder.environment();
      environment.remove("DISPLAY");
      environment.remove("WAYLAND_DISPLAY");
      environment.remove("JAVA_TOOL_OPTIONS");
      environment.remove("JDK_JAVA_OPTIONS");
      environment.put("CLASSPATH", System.getProperty("java.class.path"));
      builder.redirectErrorStream(true);
      final Process process = builder.start();
      final ByteArrayOutputStream output = new ByteArrayOutputStream();
      final Thread drain = new Thread(() -> copy(process.getInputStream(), output), "no-flags-output");
      drain.start();
      final boolean exited = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
      if (!exited) {
        process.destroyForcibly();
      }
      drain.join(TimeUnit.SECONDS.toMillis(10));
      final String text = output.toString(StandardCharsets.UTF_8);
      System.out.println(text);
      assertTrue(exited, "the server JVM finished in time");
      assertEquals(0, process.exitValue(), text);
      assertTrue(text.contains(NoFlagsServerMain.PASSED), text);
      assertTrue(text.contains("round 2: disabled, no browser process left"), text);
    }
  }

  private static void copy(final InputStream input, final ByteArrayOutputStream output) {
    try (input) {
      input.transferTo(output);
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
