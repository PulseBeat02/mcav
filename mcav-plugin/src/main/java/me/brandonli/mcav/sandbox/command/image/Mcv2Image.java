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
package me.brandonli.mcav.sandbox.command.image;

import com.google.common.base.Preconditions;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.sandbox.command.video.Mcv2Output;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A still image on an MCV2 screen. The encoder sends a picture only when it is given a frame, and a viewer who starts
 * watching needs a keyframe, so the image is given again twice a second, off the main thread: a frame that repeats the
 * one before costs little, and a viewer whose pack just loaded sees the image within half a second.
 */
final class Mcv2Image implements DisplayableImage {

  /** Twice a second. */
  static final long PERIOD_TICKS = 10;

  private final Plugin plugin;

  private final Mcv2Output output;

  private @Nullable BukkitTask task;

  /**
   * Constructs the image.
   *
   * @param plugin the plugin that schedules the frames
   * @param output the screen, not started
   */
  Mcv2Image(final Plugin plugin, final Mcv2Output output) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null");
    Preconditions.checkNotNull(output, "Output must not be null");
    this.plugin = plugin;
    this.output = output;
  }

  /**
   * Starts the screen and gives it the image, again and again until {@link #release()}. Call on the main thread.
   *
   * @param image the image, which the caller keeps: the screen works on copies of it
   */
  @Override
  public void displayImage(final ImageBuffer image) {
    Preconditions.checkNotNull(image, "Image must not be null");
    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] pixels = image.copyPixels();
    this.output.start();
    this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(this.plugin, () -> this.feed(pixels, width, height), 0, PERIOD_TICKS);
  }

  /** Gives the screen a copy of the image, which the encoder may resize in place. */
  private void feed(final int[] pixels, final int width, final int height) {
    try (final ImageBuffer frame = ImageBuffer.buffer(pixels.clone(), width, height)) {
      this.output.applyFilter(frame, OriginalVideoMetadata.of(width, height));
    }
  }

  /**
   * Stops giving the image and releases the screen. Call on the main thread.
   */
  @Override
  public void release() {
    final BukkitTask running = this.task;
    if (running != null) {
      running.cancel();
      this.task = null;
    }
    this.output.release();
  }
}
