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
package me.brandonli.mcav.testing;

import com.google.common.base.Preconditions;
import java.nio.file.Path;

/**
 * Points the {@code user.home} system property at another directory until it is closed, so code that uses the cache
 * folder of the library, {@code ~/.mcav/cache}, works inside a test directory instead of the real home directory.
 *
 * <pre><code>
 *   try (final TemporaryUserHome redirected = TemporaryUserHome.redirectTo(tempDirectory)) {
 *     final Path home = redirected.getHome();
 *     final Path cache = IOUtils.getCachedFolder();
 *   }
 * </code></pre>
 */
public final class TemporaryUserHome implements AutoCloseable {

  private static final String USER_HOME = "user.home";

  private final Path home;
  private final String previousHome;

  private TemporaryUserHome(final Path home, final String previousHome) {
    this.home = home;
    this.previousHome = previousHome;
  }

  /**
   * Points {@code user.home} at a directory.
   *
   * @param home the directory that becomes the home directory
   * @return a handle that restores the previous home directory when closed
   */
  public static TemporaryUserHome redirectTo(final Path home) {
    Preconditions.checkNotNull(home, "Home must not be null");
    final String previousHome = System.getProperty(USER_HOME);
    final String rawHome = home.toString();
    System.setProperty(USER_HOME, rawHome);
    return new TemporaryUserHome(home, previousHome);
  }

  /**
   * Gets the directory {@code user.home} points at while this handle is open.
   *
   * @return the temporary home directory
   */
  public Path getHome() {
    return this.home;
  }

  /**
   * Restores the previous home directory.
   */
  @Override
  public void close() {
    System.setProperty(USER_HOME, this.previousHome);
  }
}
