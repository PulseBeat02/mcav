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
package me.brandonli.mcav.plugin.command.image;

/**
 * The thread on which {@link ImageManager#releaseImage(ReleaseThread)} frees the image that is shown.
 *
 * <p>This enum is immutable and thread-safe.
 *
 * @since 1.0.0
 */
public enum ReleaseThread {
  /**
   * Frees the image at once on the calling thread, which must be the server main thread.
   *
   * @since 1.0.0
   */
  CALLING_THREAD,

  /**
   * Schedules the release of the display on the server main thread, so any thread may ask for it.
   *
   * @since 1.0.0
   */
  MAIN_THREAD,
}
