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
package me.brandonli.mcav.loader;

import java.nio.file.Path;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.mockito.Mockito;
import org.slf4j.Logger;

public final class LoaderPreparationProbe {

  private LoaderPreparationProbe() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  public static DependencyLoader vlc(final Logger logger, final Runnable prepare) {
    final DependencyLoader loader = Mockito.spy(new DependencyLoader(logger));
    Mockito.doAnswer(_ -> installControlledVlc(loader, prepare))
      .when(loader)
      .installVlc();
    Mockito.doNothing().when(loader).installYtdlp();
    return loader;
  }

  private static @Nullable Object installControlledVlc(final DependencyLoader loader, final Runnable prepare) {
    loader.installVlc(() -> startControlledVlc(prepare));

    return null;
  }

  private static Optional<Path> startControlledVlc(final Runnable prepare) {
    prepare.run();

    return Optional.empty();
  }
}
