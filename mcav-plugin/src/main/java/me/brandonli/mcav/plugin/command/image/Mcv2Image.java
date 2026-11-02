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

// the image is sent again twice a second: a viewer who starts watching needs a keyframe
final class Mcv2Image implements DisplayableImage {

  static final long PERIOD_TICKS = 10;

  private static final long KEYFRAME_FEEDS = (4 * 20) / PERIOD_TICKS;

  private final Plugin plugin;

  private final Mcv2Output output;

  // the timer's runs may overlap: an asynchronous run starts on time even when the one before has not ended
  private final AtomicLong feeds = new AtomicLong();

  private @Nullable BukkitTask task;

  Mcv2Image(final Plugin plugin, final Mcv2Output output) {
    Preconditions.checkNotNull(plugin, "Plugin must not be null!");
    Preconditions.checkNotNull(output, "Output must not be null!");

    this.plugin = plugin;
    this.output = output;
  }

  // main thread only; the caller keeps ownership of the image
  @Override
  public void displayImage(final ImageBuffer image) {
    Preconditions.checkNotNull(image, "Image must not be null!");

    final int width = image.getWidth();
    final int height = image.getHeight();
    final int[] pixels = image.copyPixels();
    this.output.start();
    this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(this.plugin, () -> this.feed(pixels, width, height), 0, PERIOD_TICKS);
  }

  // a copy each time, since the encoder may resize the frame in place
  private void feed(final int[] pixels, final int width, final int height) {
    if (this.feeds.incrementAndGet() % KEYFRAME_FEEDS == 0) {
      this.output.getResult().getChannel().requestKeyframe();
    }
    try (final ImageBuffer frame = ImageBuffer.buffer(pixels.clone(), width, height)) {
      this.output.applyFilter(frame, OriginalVideoMetadata.of(width, height));
    }
  }

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
