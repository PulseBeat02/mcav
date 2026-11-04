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
package me.brandonli.mcav.bukkit.hologram;

import java.util.Optional;
import me.brandonli.mcav.json.ytdlp.format.UrlParseDump;
import org.bukkit.Location;
import org.bukkit.entity.TextDisplay;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A floating text display that shows information about the video that is playing, such as its title, uploader,
 * and a progress bar.
 *
 * <p>A hologram goes through three steps: {@link #spawn(Location, UrlParseDump)} spawns the display with
 * the metadata of the video, {@link #start()} starts the progress bar, and {@link #kill()} removes the display
 * again. All methods must be called on the main thread.
 *
 * {@snippet :
 * final Hologram hologram = Hologram.basic();
 * hologram.spawn(location, dump);
 * hologram.start();
 * // when playback ends
 * hologram.kill();
 * }
 *
 * <p>The standard progress bar counts scheduler ticks independently of the media player. Seeking, pausing
 * or changing playback speed does not update its clock; recreate/restart it as appropriate for the application.
 *
 * @since 1.0.0
 */
public interface Hologram {
  /**
   * Creates a hologram that shows the title, the uploader, the upload date, and a progress bar of the video.
   *
   * @return a new hologram
   * @since 1.0.0
   */
  static Hologram basic() {
    return new StandardVideoHologram();
  }

  /**
   * Spawns the hologram at the location and fills it with the metadata of the video. Calling this method again
   * removes the previous display first.
   *
   * @param location the location to spawn the hologram at, which must have a world
   * @param dump     the metadata of the video, as parsed by yt-dlp
   * @throws NullPointerException     if the location or metadata is null
   * @throws IllegalArgumentException if the location has no world
   * @since 1.0.0
   */
  void spawn(final Location location, final UrlParseDump dump);

  /**
   * Starts the progress bar. Has no effect before {@link #spawn(Location, UrlParseDump)} was called.
   *
   * @throws IllegalStateException if a display is present but no plugin has been injected into BukkitModule
   * @since 1.0.0
   */
  void start();

  /**
   * Removes the hologram from the world and stops the progress bar. Calling this method on a hologram that was
   * never spawned has no effect.
   *
   * @since 1.0.0
   */
  void kill();

  /**
   * Returns the display entity of this hologram.
   *
   * @return the display entity, or empty if the hologram is not spawned
   * @since 1.0.0
   */
  Optional<TextDisplay> getDisplay();

  /**
   * Replaces the display entity of this hologram. The previous entity is not removed. The caller must remove any replaced or detached entity.
   * A non-null replacement becomes owned by this hologram and is removed by {@link #kill()}.
   *
   * @param display the display entity, or null to detach the hologram from its entity
   * @since 1.0.0
   */
  void setDisplay(final @Nullable TextDisplay display);
}
