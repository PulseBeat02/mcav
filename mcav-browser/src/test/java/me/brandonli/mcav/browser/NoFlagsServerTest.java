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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import me.brandonli.mcav.browser.testing.TestPages;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Runs NoFlagsServerMain like a headless server: only -Djava.awt.headless=true, CLASSPATH, no DISPLAY, WAYLAND_DISPLAY or X server
@Tag("cef")
class NoFlagsServerTest {

  private static final String WITH_X_FOLDER = "with-x";
  private static final String WINDOWS_JAVA_EXECUTABLE = "java.exe";
  private static final String JAVA_EXECUTABLE = "java";
  private static final String JAVA_BIN_FOLDER = "bin";
  private static final String PLAIN_FOLDER = "plain";
  private static final String X_SERVER_EXECUTABLE = "Xvfb";

  private static final String JAVA_HOME_PROPERTY = "java.home";
  private static final String OS_NAME_PROPERTY = "os.name";
  private static final String CLASS_PATH_PROPERTY = "java.class.path";

  private static final long TIMEOUT_MINUTES = 15L;

  private static final List<String> X_SERVERS = List.of("Xvfb", "Xorg", "X", "Xwayland");

  @Test
  void shouldStreamAPageOnAHeadlessServerWithoutJvmOptionsAndSurviveADisableAndEnable() throws Exception {
    try (final TestPages pages = TestPages.start()) {
      final String executable = System.getProperty(OS_NAME_PROPERTY).startsWith("Windows") ? WINDOWS_JAVA_EXECUTABLE : JAVA_EXECUTABLE;
      final Path java = Path.of(System.getProperty(JAVA_HOME_PROPERTY), JAVA_BIN_FOLDER, executable);
      final List<String> command = List.of(
        java.toString(),
        NoFlagsServerMain.HEADLESS_OPTION,
        NoFlagsServerMain.class.getName(),
        pages.uri(TestPages.MAIN_PATH).toString()
      );
      final ProcessBuilder builder = new ProcessBuilder(command);
      final Map<String, String> environment = builder.environment();
      environment.remove("DISPLAY");
      environment.remove("WAYLAND_DISPLAY");
      environment.remove("JAVA_TOOL_OPTIONS");
      environment.remove("JDK_JAVA_OPTIONS");
      environment.put("CLASSPATH", System.getProperty(CLASS_PATH_PROPERTY));
      final String path = this.withoutXServers(environment.getOrDefault("PATH", ""));
      environment.put("PATH", path);
      System.out.println("PATH of the server: " + path);
      builder.redirectErrorStream(true);
      final Process process = builder.start();
      final ByteArrayOutputStream output = new ByteArrayOutputStream();
      final Thread drain = new Thread(() -> this.copy(process.getInputStream(), output), "no-flags-output");
      drain.start();
      final boolean exited = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
      if (!exited) {
        process.destroyForcibly();
      }
      drain.join(TimeUnit.SECONDS.toMillis(10));
      final String text = output.toString(StandardCharsets.UTF_8);
      System.out.println(text);
      assertTrue(exited, "the server JVM finished in time");

      final int exitCode = process.exitValue();
      assertEquals(0, exitCode, text);

      final boolean reportsPassed = text.contains(NoFlagsServerMain.PASSED);
      assertTrue(reportsPassed, text);

      final boolean reportsTheSecondRound = text.contains("round 2: disabled, no browser process left");
      assertTrue(reportsTheSecondRound, text);
    }
  }

  @Test
  void shouldLeaveEveryFolderWithAnXServerOutOfThePathOfTheServer(@TempDir final Path folder) throws IOException {
    final Path withX = Files.createDirectories(folder.resolve(WITH_X_FOLDER));
    final Path plain = Files.createDirectories(folder.resolve(PLAIN_FOLDER));
    final Path server = Files.createFile(withX.resolve(X_SERVER_EXECUTABLE));
    final boolean madeExecutable = server.toFile().setExecutable(true);
    assertTrue(madeExecutable, "the fake X server is executable");

    final String path = String.join(File.pathSeparator, withX.toString(), "", plain.toString());
    final String filteredPath = this.withoutXServers(path);
    final String expectedFilteredPath = plain.toString();
    assertEquals(expectedFilteredPath, filteredPath);
  }

  private String withoutXServers(final String path) {
    return Arrays.stream(path.split(File.pathSeparator))
      .filter(entry -> !entry.isEmpty())
      .filter(entry -> X_SERVERS.stream().noneMatch(name -> Files.isExecutable(Path.of(entry, name))))
      .collect(Collectors.joining(File.pathSeparator));
  }

  private void copy(final InputStream input, final ByteArrayOutputStream output) {
    try (input) {
      input.transferTo(output);
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
