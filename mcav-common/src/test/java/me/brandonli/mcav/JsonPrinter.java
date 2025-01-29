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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.utils.os.Arch;
import me.brandonli.mcav.utils.os.Bits;
import me.brandonli.mcav.utils.os.OS;

public final class JsonPrinter {

  public static void main(final String[] args) {
    final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    final Download[] DOWNLOADS = {
      new Download(
        OS.WINDOWS,
        Arch.X86,
        Bits.BITS_64,
        "https://get.videolan.org/vlc/3.0.21/win64/vlc-3.0.21-win64.zip",
        "a0b7ec02b50adf6417eed014fb8df50af39690505a4225b85b3dc2ed17d14843"
      ),
      new Download(
        OS.WINDOWS,
        Arch.X86,
        Bits.BITS_32,
        "https://get.videolan.org/vlc/3.0.21/win32/vlc-3.0.21-win32.zip",
        "8ce67c8244ea9156533090ad6ed1b6b940c915f0bcfb5f7d17129d7fa5f768c7"
      ),
      new Download(
        OS.MAC,
        Arch.ARM,
        Bits.BITS_64,
        "https://get.videolan.org/vlc/3.0.21/macosx/vlc-3.0.21-arm64.dmg",
        "15dd65bf6489da9ec6a67f5585c74c40a58993acff41a82958a916dd74178044"
      ),
      new Download(
        OS.MAC,
        Arch.X86,
        Bits.BITS_64,
        "https://get.videolan.org/vlc/3.0.21/macosx/vlc-3.0.21-intel64.dmg",
        "d431fd051c3dc7af02bd313c6d05d90cf604b70ed3ec5bba6fd4c49ef3e638d9"
      ),
      new Download(
        OS.LINUX,
        Arch.X86,
        Bits.BITS_64,
        "https://github.com/ivan-hc/VLC-appimage/releases/download/continuous-with-plugins/VLC-media-player_3.0.21-17-jre8-with-plugins-archimage4.3-x86_64.AppImage",
        "d10e455a8811fa93a2e7f33c30f97b53719b6f06edd35e7c86e6b3b3eaee506c"
      ),
      new Download(
        OS.LINUX,
        Arch.X86,
        Bits.BITS_32,
        "https://github.com/ivan-hc/32-bit-AppImage-packages-database/releases/download/vlc/VLC-media-player_3.0.21-0+deb12u1-i386.AppImage",
        "09072a7bbe96973b92dacca0f4c314fdff41eb5df03cefe29c44a546073120ee"
      ),
    };
    System.out.println(gson.toJson(DOWNLOADS));
  }
}
