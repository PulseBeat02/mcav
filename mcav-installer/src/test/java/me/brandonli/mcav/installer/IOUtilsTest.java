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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import me.brandonli.mcav.installer.testing.UtilityClassAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link IOUtils}.
 */
final class IOUtilsTest {

  @TempDir
  private Path directory;

  @Test
  void getsTheFileNameOfAPath() {
    final Path file = this.directory.resolve("library-1.0.jar");
    final String name = IOUtils.getFileName(file);
    assertEquals("library-1.0.jar", name);
  }

  @Test
  void rejectsPathsWithoutAFileName() {
    final Path root = this.directory.getRoot();
    final InstallationException error = assertThrows(InstallationException.class, () -> IOUtils.getFileName(root));
    final String message = error.getMessage();
    assertEquals("Path " + root + " has no file name", message);
  }

  @Test
  void cannotBeInstantiated() {
    UtilityClassAssertions.assertNotInstantiable(IOUtils.class);
  }
}
