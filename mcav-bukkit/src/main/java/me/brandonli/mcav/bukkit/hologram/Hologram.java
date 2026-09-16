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

import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import org.bukkit.Location;
import org.bukkit.entity.TextDisplay;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A floating text display that shows information about the video that is playing, such as its title, uploader,
 * and a progress bar.
 *
 * <p>A hologram goes through three steps: {@link #handleRequest(Location, URLParseDump)} spawns the display with
 * the metadata of the video, {@link #start()} starts the progress bar, and {@link #kill()} removes the display
 * again. All methods must be called on the main thread.
 *
 * <pre><code>
 *   final Hologram hologram = Hologram.basic();
 *   hologram.handleRequest(location, dump);
 *   hologram.start();
 *   // when playback ends
 *   hologram.kill();
 * </code></pre>
 */
public interface Hologram {
  /**
   * Creates a hologram that shows the title, the uploader, the upload date, and a progress bar of the video.
   *
   * @return a new hologram
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
   */
  void handleRequest(final Location location, final URLParseDump dump);

  /**
   * Starts the progress bar. Has no effect before {@link #handleRequest(Location, URLParseDump)} was called.
   */
  void start();

  /**
   * Removes the hologram from the world and stops the progress bar. Calling this method on a hologram that was
   * never spawned has no effect.
   */
  void kill();

  /**
   * Gets the display entity of this hologram.
   *
   * @return the display entity, or null if the hologram is not spawned
   */
  @Nullable TextDisplay getDisplay();

  /**
   * Replaces the display entity of this hologram. The previous entity is not removed.
   *
   * @param display the display entity, or null to detach the hologram from its entity
   */
  void setDisplay(@Nullable TextDisplay display);
}
