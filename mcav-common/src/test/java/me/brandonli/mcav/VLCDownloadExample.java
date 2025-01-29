/*
 * This file is part of mcav, a media playback library for Minecraft
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

public final class VLCDownloadExample {

  public static void main(final String[] args) {
    final Download[] downloads = ReleasePackageManager.readVLCDownloadsFromJsonResource("vlc.json");
    for (final Download download : downloads) {
      System.out.println("Platform: " + getPlatformString(download.getPlatform()));
      System.out.println("URL: " + download.getUrl());
      System.out.println("Hash: " + download.getHash());
      System.out.println();
    }
  }

  private static String getPlatformString(final Platform platform) {
    return (
      platform.getOS().name().toLowerCase() + "-" + platform.getArch().name().toLowerCase() + "-" + platform.getBits().name().toLowerCase()
    );
  }
}
