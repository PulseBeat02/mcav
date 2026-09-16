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

import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.capability.installer.vlc.ReleasePackageManager;
import me.brandonli.mcav.utils.os.Platform;

/**
 * Prints the VLC downloads the installer knows about.
 */
public final class VLCDownloadExample {

  static void main() {
    final Download[] downloads = ReleasePackageManager.readVLCDownloadsFromJsonResource("vlc.json");
    for (final Download download : downloads) {
      final Platform platform = download.getPlatform();
      final String url = download.getUrl();
      final String hash = download.getHash();
      System.out.println("Platform: " + platform);
      System.out.println("URL: " + url);
      System.out.println("Hash: " + hash);
      System.out.println();
    }
  }
}
