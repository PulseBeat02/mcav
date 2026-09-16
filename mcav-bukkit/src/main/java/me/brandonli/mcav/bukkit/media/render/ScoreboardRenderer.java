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
package me.brandonli.mcav.bukkit.media.render;

import com.google.common.base.Preconditions;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.bukkit.utils.ChatUtils;
import me.brandonli.mcav.bukkit.utils.MainThreadRenderer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Renders images onto the sidebar scoreboard.
 *
 * <p>Every row of the image becomes one line of the sidebar. A line is drawn into the suffix of a team, because
 * team suffixes support any color per character, while the score entries themselves are invisible, unique color
 * code strings. The score numbers are hidden.
 *
 * <p>All scoreboard changes happen on the main thread, while frames may be submitted from any thread. The
 * scoreboard each viewer had before is restored when the renderer is hidden.
 */
public final class ScoreboardRenderer extends MainThreadRenderer<Component[]> {

  private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();
  private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();

  private final ScoreboardConfiguration configuration;
  private final Map<UUID, Scoreboard> previousScoreboards;
  private final Team[] teams;
  private final int id;

  private @Nullable Scoreboard scoreboard;

  /**
   * Constructs a new scoreboard renderer. Nothing is shown until {@link #show()} is called.
   *
   * @param configuration the configuration describing the viewers and the size of the scoreboard image
   * @throws NullPointerException if the configuration is null
   */
  public ScoreboardRenderer(final ScoreboardConfiguration configuration) {
    Preconditions.checkNotNull(configuration, "Configuration must not be null");
    final int lines = configuration.getLines();
    this.configuration = configuration;
    this.previousScoreboards = new HashMap<>();
    this.teams = new Team[lines];
    this.id = INSTANCE_COUNTER.incrementAndGet();
  }

  /**
   * Creates the scoreboard and shows it to every online viewer. May be called from any thread.
   */
  public void show() {
    MainThreadRenderer.runOnMainThread(this::createScoreboard);
    this.startRendering();
  }

  /**
   * Converts the image into scoreboard lines and schedules them for display. May be called from any thread.
   *
   * @param image the image to render, which is resized to the scoreboard dimensions in place
   * @throws NullPointerException if the image is null
   */
  public void render(final ImageBuffer image) {
    Preconditions.checkNotNull(image, "Image must not be null");
    final int width = this.configuration.getWidth();
    final int lines = this.configuration.getLines();
    final ResizeFilter resize = new ResizeFilter(width, lines);
    resize.applyFilter(image);

    final int[] pixels = image.getPixels();
    final String character = this.configuration.getCharacter();
    final Component[] components = new Component[lines];
    for (int row = 0; row < lines; row++) {
      final String line = ChatUtils.createRawLine(pixels, character, width, row);
      components[row] = LEGACY_SERIALIZER.deserialize(line);
    }
    this.submit(components);
  }

  /**
   * Removes the scoreboard and restores the previous scoreboard of every viewer. May be called from any thread,
   * but call it on the main thread during shutdown, because a disabled plugin cannot schedule the restore anymore.
   */
  public void hide() {
    this.stopRendering();
    MainThreadRenderer.runOnMainThread(this::removeScoreboard);
  }

  private void createScoreboard() {
    if (this.scoreboard != null) {
      return;
    }

    final ScoreboardManager manager = Bukkit.getScoreboardManager();
    final Scoreboard board = manager.getNewScoreboard();
    final Objective objective = this.registerObjective(board);
    this.registerTeams(board, objective);
    this.showToViewers(board);
    this.scoreboard = board;
  }

  private Objective registerObjective(final Scoreboard board) {
    final String objectiveName = "mcav_%d".formatted(this.id);
    final Component title = Component.empty();
    final Objective objective = board.registerNewObjective(objectiveName, Criteria.DUMMY, title);

    final NumberFormat hiddenNumbers = NumberFormat.blank();
    objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    objective.numberFormat(hiddenNumbers);
    return objective;
  }

  /**
   * Registers one team per line. The text of a line is the suffix of its team, and the score of its unique,
   * invisible entry keeps the lines in order.
   */
  private void registerTeams(final Scoreboard board, final Objective objective) {
    final int lines = this.teams.length;
    for (int row = 0; row < lines; row++) {
      final String teamName = "mcav_%d_%d".formatted(this.id, row);
      final Team team = board.registerNewTeam(teamName);
      final String entry = ChatUtils.getUniqueString(row);
      team.addEntry(entry);

      final Score score = objective.getScore(entry);
      score.setScore(lines - row);
      this.teams[row] = team;
    }
  }

  private void showToViewers(final Scoreboard board) {
    final Collection<UUID> viewers = this.configuration.getViewers();
    for (final UUID viewer : viewers) {
      final Player player = Bukkit.getPlayer(viewer);
      if (player == null) {
        continue;
      }
      final Scoreboard previous = player.getScoreboard();
      this.previousScoreboards.put(viewer, previous);
      player.setScoreboard(board);
    }
  }

  /**
   * Draws every line of the frame into the suffix of its team.
   *
   * @param lines the lines of the frame, from top to bottom
   */
  @Override
  protected void apply(final Component[] lines) {
    if (this.scoreboard == null) {
      return;
    }

    for (int row = 0; row < lines.length; row++) {
      final Team team = this.teams[row];
      final Component line = lines[row];
      team.suffix(line);
    }
  }

  private void removeScoreboard() {
    final Scoreboard board = this.scoreboard;
    if (board == null) {
      return;
    }

    this.restoreViewers(board);
    unregisterAll(board);
    this.previousScoreboards.clear();
    this.scoreboard = null;
  }

  /**
   * Gives every viewer who still sees the scoreboard back the scoreboard they had before.
   */
  private void restoreViewers(final Scoreboard board) {
    final ScoreboardManager manager = Bukkit.getScoreboardManager();
    final Scoreboard mainScoreboard = manager.getMainScoreboard();
    final Collection<UUID> viewers = this.configuration.getViewers();
    for (final UUID viewer : viewers) {
      final Player player = Bukkit.getPlayer(viewer);
      if (player == null) {
        continue;
      }
      final Scoreboard current = player.getScoreboard();
      // scoreboards do not override equals, so this asks whether the player sees this very scoreboard
      final boolean seesThisBoard = board.equals(current);
      if (seesThisBoard) {
        final Scoreboard previous = this.previousScoreboards.getOrDefault(viewer, mainScoreboard);
        player.setScoreboard(previous);
      }
    }
  }

  private static void unregisterAll(final Scoreboard board) {
    final Set<Objective> objectives = board.getObjectives();
    for (final Objective objective : objectives) {
      objective.unregister();
    }

    final Set<Team> registeredTeams = board.getTeams();
    for (final Team team : registeredTeams) {
      team.unregister();
    }
  }
}
