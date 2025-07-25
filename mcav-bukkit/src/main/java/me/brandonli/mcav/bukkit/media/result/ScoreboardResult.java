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
package me.brandonli.mcav.bukkit.media.result;

import static java.util.Objects.requireNonNull;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.Collection;
import java.util.UUID;
import me.brandonli.mcav.bukkit.BukkitModule;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.bukkit.utils.ChatUtils;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scoreboard.*;

/**
 * Represents a frame displaying frames on a scoreboard.
 */
public class ScoreboardResult implements FunctionalVideoFilter {

  private final ScoreboardConfiguration configuration;
  private final Team[] teamLines;

  /**
   * Constructs a new instance of the {@code ScoreboardResult} class using the provided
   * {@code ScoreboardConfiguration}.
   *
   * @param configuration the configuration object that defines the properties of the
   *                      scoreboard, including viewers, character, lines, and width.
   */
  public ScoreboardResult(final ScoreboardConfiguration configuration) {
    this.configuration = configuration;
    this.teamLines = new Team[configuration.getLines()];
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void start() {
    final BukkitScheduler scheduler = Bukkit.getScheduler();
    final Plugin plugin = BukkitModule.getPlugin();
    scheduler.runTask(plugin, this::start0);
  }

  private void start0() {
    final int lines = this.configuration.getLines();
    final Collection<UUID> viewers = this.configuration.getViewers();
    final ScoreboardManager scoreboardManager = requireNonNull(Bukkit.getScoreboardManager());
    final Scoreboard scoreboard = scoreboardManager.getNewScoreboard();
    final UUID randomUUID = UUID.randomUUID();
    final String objectiveName = randomUUID.toString();
    @SuppressWarnings("deprecation")
    final Objective objective = scoreboard.registerNewObjective(objectiveName, "dummy", "");
    objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    objective.numberFormat(NumberFormat.blank());
    for (int i = 0; i < lines; i++) {
      final UUID random = UUID.randomUUID();
      final String name = random.toString();
      final Team team = scoreboard.registerNewTeam(name);
      final String value = ChatUtils.getUniqueString(i);
      team.addEntry(value);
      final Score score = objective.getScore(value);
      score.setScore(lines - i - 1);
      this.teamLines[i] = team;
    }
    for (final UUID viewer : viewers) {
      final Player player = Bukkit.getPlayer(viewer);
      if (player == null) {
        continue;
      }
      player.setScoreboard(scoreboard);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    for (final Team team : this.teamLines) {
      if (team == null) {
        continue;
      }
      team.unregister();
    }
    final ScoreboardManager scoreboardManager = requireNonNull(Bukkit.getScoreboardManager());
    final Collection<UUID> viewers = this.configuration.getViewers();
    for (final UUID viewer : viewers) {
      final Player player = Bukkit.getPlayer(viewer);
      if (player == null) {
        continue;
      }
      final Scoreboard fresh = scoreboardManager.getNewScoreboard();
      player.setScoreboard(fresh);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @SuppressWarnings("deprecation")
  public void applyFilter(final ImageBuffer data, final OriginalVideoMetadata metadata) {
    final String character = this.configuration.getCharacter();
    final int width = this.configuration.getWidth();
    final int lines = this.configuration.getLines();
    final ResizeFilter resize = new ResizeFilter(width, lines);
    resize.applyFilter(data, metadata);
    final int[] resizedData = data.getPixels();
    for (int i = 0; i < lines; i++) {
      final Team team = this.teamLines[i];
      final String suffix = ChatUtils.createRawLine(resizedData, character, width, i);
      team.setSuffix(suffix);
    }
  }
}
