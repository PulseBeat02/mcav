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
package me.brandonli.mcav.bukkit.testing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.craftbukkit.scoreboard.CraftScoreboard;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

/**
 * Mocked scoreboards for tests. The manager hands out one new scoreboard, which records the objective, the teams,
 * and the scores registered on it, and players remember the scoreboard they were given.
 *
 * <p>The scoreboards are {@code CraftScoreboard} mocks returning Guava immutable sets, because CraftBukkit casts to
 * both.
 */
public final class FakeScoreboards {

  private final Scoreboard board;
  private final Scoreboard mainBoard;
  private final Objective objective;
  private final Map<String, Team> teams;
  private final Map<String, Score> scores;

  private FakeScoreboards(final ScoreboardManager manager) {
    this.board = mock(CraftScoreboard.class, "new scoreboard");
    this.mainBoard = mock(CraftScoreboard.class, "main scoreboard");
    this.objective = mock(Objective.class);
    this.teams = new LinkedHashMap<>();
    this.scores = new LinkedHashMap<>();

    when(manager.getNewScoreboard()).thenReturn(this.board);
    when(manager.getMainScoreboard()).thenReturn(this.mainBoard);
    when(this.board.registerNewObjective(anyString(), any(Criteria.class), any(Component.class))).thenReturn(this.objective);
    when(this.objective.getScore(anyString())).thenAnswer(invocation -> {
      final String entry = invocation.getArgument(0);
      return this.scores.computeIfAbsent(entry, name -> mock(Score.class, "score " + name));
    });
    when(this.board.registerNewTeam(anyString())).thenAnswer(invocation -> {
      final String name = invocation.getArgument(0);
      final Team team = mock(Team.class, name);
      this.teams.put(name, team);
      return team;
    });
    when(this.board.getObjectives()).thenAnswer(_ -> ImmutableSet.of(this.objective));
    when(this.board.getTeams()).thenAnswer(_ -> {
      final Collection<Team> registeredTeams = this.teams.values();
      return ImmutableSet.copyOf(registeredTeams);
    });
  }

  /**
   * Installs the scoreboards on the manager.
   *
   * @param manager the scoreboard manager mock
   * @return the scoreboards
   */
  public static FakeScoreboards install(final ScoreboardManager manager) {
    return new FakeScoreboards(manager);
  }

  /**
   * Makes the player remember the scoreboard it is given, starting with the specified one.
   *
   * @param player  the player mock
   * @param initial the scoreboard the player sees at first
   */
  public static void trackScoreboard(final Player player, final Scoreboard initial) {
    final AtomicReference<Scoreboard> current = new AtomicReference<>(initial);
    when(player.getScoreboard()).thenAnswer(_ -> current.get());
    doAnswer(invocation -> {
      final Scoreboard scoreboard = invocation.getArgument(0);
      current.set(scoreboard);
      return null;
    })
      .when(player)
      .setScoreboard(any(Scoreboard.class));
  }

  /**
   * Gets the scoreboard handed out by {@link ScoreboardManager#getNewScoreboard()}.
   *
   * @return the new scoreboard
   */
  public Scoreboard getBoard() {
    return this.board;
  }

  /**
   * Gets the main scoreboard of the server.
   *
   * @return the main scoreboard
   */
  public Scoreboard getMainBoard() {
    return this.mainBoard;
  }

  /**
   * Gets the objective registered on the new scoreboard.
   *
   * @return the objective
   */
  public Objective getObjective() {
    return this.objective;
  }

  /**
   * Gets the teams registered on the new scoreboard, in registration order.
   *
   * @return the teams
   */
  public List<Team> getTeams() {
    final Collection<Team> registeredTeams = this.teams.values();
    return List.copyOf(registeredTeams);
  }

  /**
   * Gets the names of the teams registered on the new scoreboard, in registration order.
   *
   * @return the team names
   */
  public List<String> getTeamNames() {
    final Set<String> teamNames = this.teams.keySet();
    return List.copyOf(teamNames);
  }

  /**
   * Gets the score of an entry of the objective.
   *
   * @param entry the entry
   * @return the score mock
   */
  public Score getScore(final String entry) {
    return this.scores.get(entry);
  }
}
