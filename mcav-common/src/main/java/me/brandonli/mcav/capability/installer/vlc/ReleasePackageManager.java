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

import com.google.common.base.Preconditions;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.utils.IOUtils;

/**
 * Resolves the VLC downloads for every platform: the releases listed in the {@code installers/vlc.json} resource, each
 * with the SHA-256 hash its download is checked against before anything of it runs.
 *
 * <p>Windows and macOS get VLC from videolan.org. VideoLAN publishes no Linux build, so x86-64 Linux gets the AppImage
 * built from Arch Linux's VLC package (<a href="https://github.com/ivan-hc/VLC-appimage">ivan-hc/VLC-appimage</a>),
 * pinned to one dated release whose hash was checked when it was pinned. The AppImage is run to extract it, so only that
 * reviewed build runs, never whatever the weekly tag of a third party's account points to later; a newer build needs a
 * new pin. 32-bit x86 Linux gets none: Java 25, which mcav needs, has no port for it.
 */
public final class ReleasePackageManager {

  private ReleasePackageManager() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Reads the VLC downloads from a JSON resource, without asking the network.
   *
   * @param resourcePath the name of the JSON resource, such as {@code vlc.json}
   * @return the downloads for every supported platform
   * @throws NullPointerException if {@code resourcePath} is null
   */
  public static Download[] readVLCDownloadsFromJsonResource(final String resourcePath) {
    Preconditions.checkNotNull(resourcePath, "Resource path must not be null");
    return IOUtils.readDownloadsFromJsonResource(resourcePath);
  }
}
