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
package me.brandonli.mcav.capability.installer.vlc;

import java.nio.file.Path;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;

// Remembers the outcome of loading libvlc. libvlc can only be loaded once per JVM, so the first successful load decides
// the result of every later one. A failed load is not remembered, so it can be retried, for example once the network is
// back.
final class VlcLoadState {

  private final Object lock;

  private boolean loaded;
  private @Nullable Path libraryDirectory;

  VlcLoadState() {
    this.lock = new Object();
  }

  // Runs the loader unless an earlier call succeeded, in which case the earlier result is returned. Concurrent calls
  // are serialized, so the loader never runs twice at the same time.
  Optional<Path> loadOnce(final Loader loader) {
    synchronized (this.lock) {
      if (!this.loaded) {
        final Optional<Path> result = loader.load();
        this.libraryDirectory = result.orElse(null);
        this.loaded = true;
      }

      return Optional.ofNullable(this.libraryDirectory);
    }
  }

  @FunctionalInterface
  interface Loader {
    Optional<Path> load();
  }
}
