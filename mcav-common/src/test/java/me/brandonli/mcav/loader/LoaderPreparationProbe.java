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

import java.util.Optional;
import org.mockito.Mockito;
import org.slf4j.Logger;

/** Supplies a real loader with deterministic installation actions for lifecycle tests in the parent package. */
public final class LoaderPreparationProbe {

  private LoaderPreparationProbe() {}

  /**
   * Creates a loader whose VLC installation runs the action and whose yt-dlp step needs no external program.
   * @param logger receives the real preparation messages
   * @param prepare the controlled VLC preparation
   * @return the real loader with the external entry points replaced
   */
  public static DependencyLoader vlc(final Logger logger, final Runnable prepare) {
    final DependencyLoader loader = Mockito.spy(new DependencyLoader(logger));
    Mockito.doAnswer(_ -> {
      loader.installVLC(() -> {
        prepare.run();
        return Optional.empty();
      });
      return null;
    })
      .when(loader)
      .installVLC();
    Mockito.doNothing().when(loader).installYTDLP();
    return loader;
  }
}
