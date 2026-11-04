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
package me.brandonli.mcav;

import java.util.Optional;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.capability.installer.vlc.ReleasePackageManager;
import me.brandonli.mcav.utils.os.Platform;

public final class VlcDownloadExample {

  private static final String VLC_DOWNLOADS_RESOURCE = "mcav/common/installers/vlc.json";

  private VlcDownloadExample() {
    final String message = "Utility class cannot be instantiated!";
    throw new UnsupportedOperationException(message);
  }

  static void main() {
    final Download[] downloads = ReleasePackageManager.readVlcDownloadsFromJsonResource(VLC_DOWNLOADS_RESOURCE);
    for (final Download download : downloads) {
      final Platform platform = download.getPlatform();
      final String url = download.getUrl();
      final Optional<String> hash = download.getHash();
      final String hashText = hash.orElse("none");
      System.out.println("Platform: %s".formatted(platform));
      System.out.println("URL: %s".formatted(url));
      System.out.println("Hash: %s".formatted(hashText));
      System.out.println();
    }
  }
}
