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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Executes injected scripts against observable browser API fixtures in the build's pinned JavaScript engine. */
final class ScriptAssertions {

  private ScriptAssertions() {}

  static void execute(final String resource, final String script) throws IOException, InterruptedException {
    final String fixture;
    try (final InputStream input = Objects.requireNonNull(ScriptAssertions.class.getResourceAsStream(resource))) {
      fixture = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    final String program = fixture.replace("SCRIPT_UNDER_TEST", DevToolsInput.quote(script));
    final String node = Objects.requireNonNull(System.getProperty("mcav.testNode"), "The script-testing convention supplies Node");
    final Path output = Files.createTempFile("mcav-script-", ".log");
    final Process process = new ProcessBuilder(node, "--input-type=module", "-")
      .redirectErrorStream(true)
      .redirectOutput(output.toFile())
      .start();
    try {
      try (final OutputStream input = process.getOutputStream()) {
        input.write(program.getBytes(StandardCharsets.UTF_8));
      }
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the script fixture finishes within its deadline");
      assertEquals(0, process.exitValue(), Files.readString(output));
    } finally {
      process.destroyForcibly();
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the script process is reaped");
      Files.deleteIfExists(output);
    }
  }
}
