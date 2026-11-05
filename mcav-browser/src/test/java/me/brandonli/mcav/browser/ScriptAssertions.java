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

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

final class ScriptAssertions {

  private static final String TEMPORARY_SCRIPT_PREFIX = "mcav-script-";

  private static final String OUTPUT_LOG_SUFFIX = ".log";

  private static final String SCRIPTS_DIRECTORY = "/scripts/";

  private static final String SCRIPT_PLACEHOLDER = "SCRIPT_UNDER_TEST";

  private static final String NODE_PROPERTY = "mcav.test_node";

  private static final long SCRIPT_TIMEOUT_SECONDS = 120L;
  private static final long REAP_TIMEOUT_SECONDS = 30L;

  private ScriptAssertions() {}

  static void execute(final String resource, final String script) throws IOException, InterruptedException {
    final String fixture;
    try (final InputStream input = requireNonNull(ScriptAssertions.class.getResourceAsStream(SCRIPTS_DIRECTORY + resource))) {
      fixture = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    final String program = fixture.replace(SCRIPT_PLACEHOLDER, DevToolsInput.quote(script));
    final String node = requireNonNull(System.getProperty(NODE_PROPERTY), "Node must be supplied by the script-testing convention!");
    final Path output = Files.createTempFile(TEMPORARY_SCRIPT_PREFIX, OUTPUT_LOG_SUFFIX);
    final Process process = new ProcessBuilder(node, "--input-type=module", "-")
      .redirectErrorStream(true)
      .redirectOutput(output.toFile())
      .start();
    try {
      try (final OutputStream input = process.getOutputStream()) {
        input.write(program.getBytes(StandardCharsets.UTF_8));
      }
      final boolean finished = process.waitFor(SCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(finished, "the script fixture finishes within its deadline");

      final int exitValue = process.exitValue();
      assertEquals(0, exitValue, Files.readString(output));
    } finally {
      process.destroyForcibly();
      final boolean reaped = process.waitFor(REAP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(reaped, "the script process is reaped");

      Files.deleteIfExists(output);
    }
  }
}
