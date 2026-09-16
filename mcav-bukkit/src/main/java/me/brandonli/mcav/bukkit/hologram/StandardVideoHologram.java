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

import com.google.common.base.Preconditions;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import me.brandonli.mcav.bukkit.BukkitModule;
import me.brandonli.mcav.json.ytdlp.format.URLParseDump;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A hologram that shows the title, the uploader, the upload date, and a progress bar of a video. The progress bar
 * advances once per second until the duration of the video is reached.
 */
public class StandardVideoHologram extends VideoHologram {

  private static final DateTimeFormatter MONTH_DAY_FORMATTER = DateTimeFormatter.ofPattern("MMMM d");
  private static final DateTimeFormatter YEAR_TIME_FORMATTER = DateTimeFormatter.ofPattern(", yyyy h:mm a");
  private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

  private static final String PROGRESS_FILLED = "<green>■</green>";
  private static final String PROGRESS_EMPTY = "<gray>■</gray>";

  private static final String UNKNOWN_TITLE = "Unknown title";
  private static final String UNKNOWN_UPLOADER = "Unknown uploader";
  private static final String UNKNOWN_DATE = "Unknown date";

  private static final int PROGRESS_SEGMENTS = 20;
  private static final long TICKS_PER_SECOND = 20L;

  private Component metadataText;
  private int durationSeconds;
  private int currentSecond;
  private @Nullable BukkitTask task;

  StandardVideoHologram() {
    this.metadataText = Component.empty();
  }

  /**
   * Spawns a text display at the location that shows the title, the uploader, and the upload date of the video.
   * Missing metadata is replaced by placeholders, and MiniMessage tags in the metadata are shown literally. Calling
   * this method again removes the previous display and stops its progress bar first.
   *
   * @param location the location to spawn the hologram at, which must have a world
   * @param dump     the metadata of the video, as parsed by yt-dlp
   * @throws NullPointerException     if the location or the metadata is null
   * @throws IllegalArgumentException if the location has no world
   */
  @Override
  public void handleRequest(final Location location, final URLParseDump dump) {
    Preconditions.checkNotNull(location, "Location must not be null");
    Preconditions.checkNotNull(dump, "Video metadata must not be null");
    final World world = location.getWorld();
    Preconditions.checkArgument(world != null, "Location must have a world");

    this.kill();
    final Component metadata = createMetadataText(dump);
    final TextDisplay entity = world.spawn(location, TextDisplay.class, display -> configureDisplay(display, metadata));
    this.setDisplay(entity);
    this.metadataText = metadata;

    // yt-dlp can report a fraction of a second, which still has to be played, so the duration is rounded up
    final double wholeSeconds = Math.ceil(dump.duration);
    this.durationSeconds = Math.max(0, (int) wholeSeconds);
    this.currentSecond = 0;
  }

  private static void configureDisplay(final TextDisplay display, final Component text) {
    display.setPersistent(false);
    display.setAlignment(TextDisplay.TextAlignment.CENTER);
    display.setBillboard(Display.Billboard.VERTICAL);
    display.setVisibleByDefault(true);
    display.setSeeThrough(false);
    display.setBackgroundColor(Color.BLACK);
    display.text(text);
  }

  private static Component createMetadataText(final URLParseDump dump) {
    final String rawTitle = dump.title == null ? UNKNOWN_TITLE : dump.title;
    final String rawUploader = dump.uploader == null ? UNKNOWN_UPLOADER : dump.uploader;
    final String title = MINI_MESSAGE.escapeTags(rawTitle);
    final String uploader = MINI_MESSAGE.escapeTags(rawUploader);
    final String uploadDate = formatUploadDate(dump.timestamp);
    final String miniMessage = "<white>%s</white>\n<gray>%s (%s)</gray>".formatted(title, uploader, uploadDate);
    return MINI_MESSAGE.deserialize(miniMessage);
  }

  private static String formatUploadDate(final int timestamp) {
    if (timestamp <= 0) {
      return UNKNOWN_DATE;
    }
    final Instant instant = Instant.ofEpochSecond(timestamp);
    final ZoneId zone = ZoneId.systemDefault();
    final ZonedDateTime dateTime = instant.atZone(zone);
    final int day = dateTime.getDayOfMonth();
    final String ordinal = getOrdinalSuffix(day);
    final String monthDay = dateTime.format(MONTH_DAY_FORMATTER);
    final String yearTime = dateTime.format(YEAR_TIME_FORMATTER);
    return monthDay + ordinal + yearTime;
  }

  private static String getOrdinalSuffix(final int day) {
    if (day >= 11 && day <= 13) {
      return "th";
    }
    final int lastDigit = day % 10;
    return switch (lastDigit) {
      case 1 -> "st";
      case 2 -> "nd";
      case 3 -> "rd";
      default -> "th";
    };
  }

  /**
   * Starts the progress bar, which advances once per second until the duration of the video is reached or the
   * display is removed. Has no effect before {@link #handleRequest(Location, URLParseDump)} was called or while the
   * progress bar is already running.
   */
  @Override
  public void start() {
    final TextDisplay display = this.getDisplay();
    if (display == null || this.task != null) {
      return;
    }

    this.currentSecond = 0;
    final Plugin plugin = BukkitModule.getPlugin();
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    this.task = scheduler.runTaskTimer(plugin, this::advanceProgress, 0L, TICKS_PER_SECOND);
  }

  private void advanceProgress() {
    final TextDisplay display = this.getDisplay();
    final boolean finished = this.currentSecond > this.durationSeconds;
    if (display == null || !display.isValid() || finished) {
      this.cancelTask();
      return;
    }
    final Component text = this.createProgressText();
    display.text(text);
    this.currentSecond++;
  }

  private Component createProgressText() {
    final String progressBar = this.createProgressBar();
    final String currentTime = formatTime(this.currentSecond);
    final String totalTime = formatTime(this.durationSeconds);
    final String timerLine = "<gray>%s</gray> %s <gray>%s</gray>".formatted(currentTime, progressBar, totalTime);
    final Component timer = MINI_MESSAGE.deserialize(timerLine);

    final Component newline = Component.newline();
    final Component withNewline = this.metadataText.append(newline);
    return withNewline.append(timer);
  }

  private String createProgressBar() {
    final int filledSegments = this.countFilledSegments();
    final int emptySegments = PROGRESS_SEGMENTS - filledSegments;
    final String filled = PROGRESS_FILLED.repeat(filledSegments);
    final String empty = PROGRESS_EMPTY.repeat(emptySegments);
    return filled + empty;
  }

  private int countFilledSegments() {
    if (this.durationSeconds == 0) {
      return 0;
    }
    final double progress = (double) this.currentSecond / this.durationSeconds;
    final double filledSegments = Math.ceil(progress * PROGRESS_SEGMENTS);
    return (int) filledSegments;
  }

  private static String formatTime(final int seconds) {
    final int minutes = seconds / 60;
    final int remainingSeconds = seconds % 60;
    return "%02d:%02d".formatted(minutes, remainingSeconds);
  }

  private void cancelTask() {
    final BukkitTask current = this.task;
    if (current == null) {
      return;
    }
    current.cancel();
    this.task = null;
  }

  /**
   * Stops the progress bar and removes the display entity from the world. Calling this method on a hologram that
   * was never spawned has no effect.
   */
  @Override
  public void kill() {
    this.cancelTask();
    super.kill();
  }
}
