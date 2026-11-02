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

import com.google.common.base.Preconditions;
import java.util.concurrent.atomic.AtomicLong;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.plugin.command.video.Mcv2Output;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A still image on an MCV2 screen. The encoder sends a picture only when it is given a frame, and a viewer who starts
 * watching needs a keyframe, so the image is given again twice a second, off the main thread: a frame that repeats the
 * one before costs little. A viewer is shown the screen with a keyframe, but a client that draws no frame while that
 * keyframe is the newest, for example because it compiles the pack's shaders as it first draws the screen, keeps an
 * empty picture until the next one, which the encoder makes on its own only every 120 frames, a minute at two frames a
 * second. So every eighth frame is asked to be a keyframe, and such a client gets the next within four seconds, as
 * with a video.
 */
final class Mcv2Image implements DisplayableImage {

  /** Twice a second. */
  static final long PERIOD_TICKS = 10;

  /** Every fourth second, at 20 ticks a second. */
  private static final long KEYFRAME_FEEDS = (4 * 20) / PERIOD_TICKS;

  private final Plugin plugin;

  private final Mcv2Output output;

  // the timer's runs may overlap: an asynchronous run starts on time even when the one before has not ended
  private final AtomicLong feeds = new AtomicLong();

  private @Nullable BukkitTask task;

  /**
   * Constructs the image.
   *
   * @param plugin the plugin that schedules the frames
   * @param output the screen, not started
   */
  Mcv2Image(final Plugin plugin, final Mcv2Output output) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null!");
    Preconditions.checkNotNull(output, "Output must not be null!");

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
    Preconditions.checkNotNull(image, "Image must not be null!");

    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] pixels = image.copyPixels();
    this.output.start();
    this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(this.plugin, () -> this.feed(pixels, width, height), 0, PERIOD_TICKS);
  }

  /** Gives the screen a copy of the image, which the encoder may resize in place, every eighth time as a keyframe. */
  private void feed(final int[] pixels, final int width, final int height) {
    if (this.feeds.incrementAndGet() % KEYFRAME_FEEDS == 0) {
      this.output.getResult().getChannel().requestKeyframe();
    }
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
