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

import me.brandonli.mcav.capability.Capability;

/**
 * Represents the main API interface for handling media playback capabilities,
 * installation, and resource management in the MCAV library.
 */
public interface MCAVApi {
  /**
   * Checks whether the specified capability is supported and enabled.
   *
   * @param capability the capability to check
   * @return true if the capability is supported and enabled, false otherwise
   */
  boolean hasCapability(Capability capability);

  /**
   * Installs the necessary components and dependencies required for the media playback library.
   * This method initiates parallel tasks to handle the installation of various media-related tools
   * such as VLC, YTDLP, WebDriver, and additional miscellaneous resources.
   * <p>
   * The installation process includes:
   * - Downloading and configuring VLC for media playback capabilities.
   * - Installing and configuring YTDLP for handling YouTube media sources.
   * - Initializing WebDriver for browser-based playback or interactions.
   * - Loading additional dependencies, such as FFmpeg and OpenCV, into the environment.
   * <p>
   * After this method is executed, the library's capabilities can be verified using the
   * {@code hasCapability} method with specific {@code Capability} values.
   */
  void install();

  /**
   * Releases resources and performs cleanup operations as required by the library.
   * <p>
   * This method ensures that all components and services initialized by the API,
   * such as media player factories or any background processes, are properly
   * shut down. It is essential to invoke this method when the library's functionalities
   * are no longer needed to prevent resource leaks.
   */
  void release();
}
