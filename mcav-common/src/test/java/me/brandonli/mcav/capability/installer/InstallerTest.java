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
package me.brandonli.mcav.capability.installer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests the default methods of {@link Installer}.
 */
final class InstallerTest {

  @Test
  void findsNoInstallationUnlessTheInstallerKnowsHowToLookForOne() throws IOException {
    final Installer installer = new Installer() {
      @Override
      public Path download(final boolean executable) {
        throw new AssertionError("Looking for an installation must not download anything");
      }

      @Override
      public boolean isSupported() {
        throw new AssertionError("Looking for an installation must not ask whether a download exists");
      }

      @Override
      public Path getPath() {
        return Path.of("program");
      }
    };
    final Optional<Path> installation = installer.findInstallation();
    final boolean empty = installation.isEmpty();
    assertTrue(empty);
  }
}
