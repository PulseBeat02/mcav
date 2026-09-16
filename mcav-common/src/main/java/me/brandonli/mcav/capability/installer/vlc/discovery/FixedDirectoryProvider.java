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
package me.brandonli.mcav.capability.installer.vlc.discovery;

import java.nio.file.Path;
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider;

/**
 * A directory provider that names exactly one directory, used to load the libraries of a VLC installation whose
 * location is already known.
 */
final class FixedDirectoryProvider implements DiscoveryDirectoryProvider {

  private final String directory;

  /**
   * Constructs a new provider.
   *
   * @param directory the directory to provide
   */
  FixedDirectoryProvider(final Path directory) {
    final Path absolute = directory.toAbsolutePath();
    this.directory = absolute.toString();
  }

  /**
   * Gets the priority of the provider, which is the highest possible because the directory was chosen explicitly.
   *
   * @return {@link Integer#MAX_VALUE}
   */
  @Override
  public int priority() {
    return Integer.MAX_VALUE;
  }

  /**
   * Gets the provided directory.
   *
   * @return an array holding the absolute path of the directory
   */
  @Override
  public String[] directories() {
    return new String[] { this.directory };
  }

  /**
   * Checks whether the provider can be used, which is always the case.
   *
   * @return true
   */
  @Override
  public boolean supported() {
    return true;
  }
}
