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

import static java.util.Objects.requireNonNull;

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

/**
 * Represents a basic video hologram that displays metadata and a progress bar.
 */
public class StandardVideoHologram extends VideoHologram {

  private static final DateTimeFormatter DATE_FORMATTER_FIRST = DateTimeFormatter.ofPattern("MMMM d");
  private static final DateTimeFormatter DATE_FORMATTER_SECOND = DateTimeFormatter.ofPattern(", yyyy h:mm a");

  private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

  private static final String METADATA_LINES =
    """
    <white>%%TITLE%%</white>
    <gray>%%UPLOADER%% (%%UPLOAD_DATE%%)</gray>
    """;

  private Component originalText;
  private int durationSeconds;
  private int currentSecond;
  private int taskId;

  StandardVideoHologram() {
    this.taskId = -1;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void handleRequest(final Location location, final URLParseDump dump) {
    final World world = requireNonNull(location.getWorld());
    final String title = dump.title;
    final String uploader = dump.uploader;
    final String uploadDate = this.getFormattedDate(dump.timestamp);
    final String minimessage = METADATA_LINES.replace("%%TITLE%%", title)
      .replace("%%UPLOADER%%", uploader)
      .replace("%%UPLOAD_DATE%%", uploadDate);
    final Component component = MINI_MESSAGE.deserialize(minimessage);
    final TextDisplay entity = world.spawn(location, TextDisplay.class, display -> {
      display.setAlignment(TextDisplay.TextAlignment.CENTER);
      display.setBillboard(Display.Billboard.VERTICAL);
      display.setVisibleByDefault(true);
      display.setSeeThrough(false);
      display.setBackgroundColor(Color.BLACK);
      display.text(component);
    });
    this.setDisplay(entity);
    this.durationSeconds = dump.duration;
  }

  private String getFormattedDate(final int timestamp) {
    final Instant instant = Instant.ofEpochSecond(timestamp);
    final ZonedDateTime dateTime = instant.atZone(ZoneId.systemDefault());
    final int day = dateTime.getDayOfMonth();
    final String ordinal;
    if (day >= 11 && day <= 13) {
      ordinal = "th";
    } else {
      ordinal = switch (day % 10) {
        case 1 -> "st";
        case 2 -> "nd";
        case 3 -> "rd";
        default -> "th";
      };
    }
    return dateTime.format(DATE_FORMATTER_FIRST) + ordinal + dateTime.format(DATE_FORMATTER_SECOND);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    final TextDisplay display = this.getDisplay();
    if (display == null) {
      return;
    }

    this.originalText = display.text();
    this.currentSecond = 0;

    final Plugin plugin = BukkitModule.getPlugin();
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    this.taskId = scheduler.scheduleSyncRepeatingTask(plugin, () -> this.updateTask(scheduler, display), 0L, 20L);
  }

  private void updateTask(final BukkitScheduler scheduler, final TextDisplay display) {
    if (this.currentSecond >= this.durationSeconds) {
      if (this.taskId != -1) {
        scheduler.cancelTask(this.taskId);
        this.taskId = -1;
      }
      return;
    }
    this.updateTimerDisplay(display);
    this.currentSecond++;
  }

  private void updateTimerDisplay(final TextDisplay display) {
    final StringBuilder progressBar = new StringBuilder();
    final int totalSquares = 20;
    final int greenSquares = (int) Math.ceil(((double) this.currentSecond / this.durationSeconds) * totalSquares);
    for (int i = 0; i < totalSquares; i++) {
      if (i < greenSquares) {
        progressBar.append("<green>■</green>");
      } else {
        progressBar.append("<gray>■</gray>");
      }
    }

    final String currentTime = this.formatTime(this.currentSecond);
    final String totalTime = this.formatTime(this.durationSeconds);
    final String timerLine = String.format("<gray>%s</gray> %s <gray>%s</gray>", currentTime, progressBar, totalTime);
    final Component newText = Component.empty()
      .append(this.originalText)
      .append(Component.newline())
      .append(MINI_MESSAGE.deserialize(timerLine));

    display.text(newText);
  }

  private String formatTime(final int seconds) {
    final int minutes = seconds / 60;
    final int remainingSeconds = seconds % 60;
    return String.format("%02d:%02d", minutes, remainingSeconds);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void kill() {
    if (this.taskId != -1) {
      final BukkitScheduler scheduler = Bukkit.getScheduler();
      scheduler.cancelTask(this.taskId);
      this.taskId = -1;
    }
    super.kill();
  }
}
