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
package me.brandonli.mcav.capability.installer.ytdlp;

import java.nio.file.Path;
import me.brandonli.mcav.capability.installer.AbstractInstaller;
import me.brandonli.mcav.capability.installer.Download;
import me.brandonli.mcav.utils.IOUtils;

/**
 * YT-DLP installer class.
 */
public final class YTDLPInstaller extends AbstractInstaller {

  private static final Download[] DOWNLOADS = IOUtils.readDownloadsFromJsonResource("yt-dlp.json");

  YTDLPInstaller(final Path folder) {
    super(folder, "yt-dlp", DOWNLOADS);
  }

  YTDLPInstaller() {
    super("yt-dlp", DOWNLOADS);
  }

  /**
   * Constructs a new YTDLPInstaller with the specified directory for the executable.
   *
   * @param executable directory
   * @return new YTDLPInstaller
   */
  public static YTDLPInstaller create(final Path executable) {
    return new YTDLPInstaller(executable);
  }

  /**
   * Constructs a new YTDLPInstaller with the default directory for the executable.
   *
   * <p>It is [user home directory]/static-emc
   *
   * @return new YTDLPInstaller
   */
  public static YTDLPInstaller create() {
    return new YTDLPInstaller();
  }
}
