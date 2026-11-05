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

// Points user.home at another directory until closed, so the library's cache folder, ~/.mcav/cache, stays out of the real home.
public final class TemporaryUserHome implements AutoCloseable {

  private static final String USER_HOME = "user.home";

  private final Path home;
  private final String previousHome;

  private TemporaryUserHome(final Path home, final String previousHome) {
    this.home = home;
    this.previousHome = previousHome;
  }

  public static TemporaryUserHome redirectTo(final Path home) {
    Preconditions.checkNotNull(home, "Home must not be null!");

    final String previousHome = System.getProperty(USER_HOME);
    final String rawHome = home.toString();
    System.setProperty(USER_HOME, rawHome);
    return new TemporaryUserHome(home, previousHome);
  }

  @Override
  public void close() {
    System.setProperty(USER_HOME, this.previousHome);
  }

  public Path getHome() {
    return this.home;
  }
}
