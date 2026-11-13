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
package me.brandonli.mcav.browser.testing;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

final class OpenFilesTest {

  private static final Path NULL_DEVICE = Path.of("/dev/null");

  private List<InputStream> leakedStreams;

  @BeforeEach
  void setUp() {
    this.leakedStreams = new ArrayList<>();
  }

  @AfterEach
  void closeLeakedStreams() throws IOException {
    for (final InputStream stream : this.leakedStreams) {
      stream.close();
    }
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void shouldFailForAnAttemptThatLeavesAFileOpen() {
    final AssertionError failure = assertThrows(AssertionError.class, () -> OpenFiles.assertLeavesNoneOpen("the leak", this::leakAFile));
    final String message = failure.getMessage();
    final boolean namesTheAttempt = message.startsWith("the leak left ");
    assertTrue(namesTheAttempt, message);
  }

  private void leakAFile() {
    try {
      final InputStream stream = Files.newInputStream(NULL_DEVICE);
      this.leakedStreams.add(stream);
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
