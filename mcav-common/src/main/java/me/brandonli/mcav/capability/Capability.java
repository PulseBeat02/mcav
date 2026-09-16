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
package me.brandonli.mcav.capability;

/**
 * An optional feature of the library that depends on an external program or on native libraries that not every
 * system can load. Check availability with {@link me.brandonli.mcav.MCAVApi#hasCapability(Capability)} before using
 * the feature, or wait for it with {@link me.brandonli.mcav.MCAVApi#whenCapabilityReady(Capability)}.
 *
 * <p>{@link #FFMPEG} and {@link #FACE_DETECTION} are decided while {@link me.brandonli.mcav.MCAVApi#install(Class[])}
 * runs. {@link #VLC} and {@link #YT_DLP} are external programs that may have to be downloaded first, so they are
 * prepared in the background after {@code install} returned.
 */
public enum Capability {
  /**
   * Playback through the VLC media player, which supports the widest range of formats and streams. Prepared in the
   * background, because VLC may have to be downloaded first.
   */
  VLC("VLC"),

  /**
   * Playback through FFmpeg, which is bundled with the library and always available.
   */
  FFMPEG("FFmpeg"),

  /**
   * Resolving stream URLs of websites such as YouTube through yt-dlp. Prepared in the background, because yt-dlp may
   * have to be downloaded first.
   */
  YT_DLP("yt-dlp"),

  /**
   * Face detection with the object detection module of OpenCV. Its native libraries link OpenCV's GUI module, which
   * needs GTK 2 on Linux, so the feature is missing on many headless servers.
   */
  FACE_DETECTION("Face detection");

  private final String displayName;

  Capability(final String displayName) {
    this.displayName = displayName;
  }

  /**
   * Gets the name of the capability as it is written in log and error messages, such as {@code yt-dlp}.
   *
   * @return the display name
   */
  public String getDisplayName() {
    return this.displayName;
  }
}
