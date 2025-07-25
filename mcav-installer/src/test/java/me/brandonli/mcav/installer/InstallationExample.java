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

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;

public final class InstallationExample {

  public static void main(final String[] args) {
    final Path downloaded = Path.of("dependencies");
    final Artifact download = Artifact.COMMON;
    final Class<InstallationExample> clazz = InstallationExample.class;
    final ClassLoader classLoader = requireNonNull(clazz.getClassLoader());
    @SuppressWarnings("deprecation")
    final MCAVInstaller installer = MCAVInstaller.injector(downloaded, classLoader);
    installer.loadMCAVDependencies(download);
  }
}
