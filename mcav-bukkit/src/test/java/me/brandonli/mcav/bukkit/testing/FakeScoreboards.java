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

// CraftScoreboard mocks with Guava immutable sets, because CraftBukkit casts to both
public final class FakeScoreboards {

  private final Scoreboard newScoreboard;
  private final Scoreboard mainScoreboard;
  private final Objective objective;
  private final Map<String, Team> teams;
  private final Map<String, Score> scores;

  private FakeScoreboards(final ScoreboardManager manager) {
    this.newScoreboard = mock(CraftScoreboard.class, "new scoreboard");
    this.mainScoreboard = mock(CraftScoreboard.class, "main scoreboard");
    this.objective = mock(Objective.class);
    this.teams = new LinkedHashMap<>();
    this.scores = new LinkedHashMap<>();

    when(manager.getNewScoreboard()).thenReturn(this.newScoreboard);
    when(manager.getMainScoreboard()).thenReturn(this.mainScoreboard);
    when(this.newScoreboard.registerNewObjective(anyString(), any(Criteria.class), any(Component.class))).thenReturn(this.objective);
    when(this.objective.getScore(anyString())).thenAnswer(invocation -> {
      final String entry = invocation.getArgument(0);
      return this.scores.computeIfAbsent(entry, name -> mock(Score.class, "score " + name));
    });
    when(this.newScoreboard.registerNewTeam(anyString())).thenAnswer(invocation -> {
      final String name = invocation.getArgument(0);
      final Team team = mock(Team.class, name);
      this.teams.put(name, team);
      return team;
    });
    when(this.newScoreboard.getObjectives()).thenAnswer(_ -> ImmutableSet.of(this.objective));
    when(this.newScoreboard.getTeams()).thenAnswer(_ -> {
      final Collection<Team> registeredTeams = this.teams.values();
      return ImmutableSet.copyOf(registeredTeams);
    });
  }

  public static FakeScoreboards install(final ScoreboardManager manager) {
    return new FakeScoreboards(manager);
  }

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

  public List<Team> getTeams() {
    final Collection<Team> registeredTeams = this.teams.values();
    return List.copyOf(registeredTeams);
  }

  public List<String> getTeamNames() {
    final Set<String> teamNames = this.teams.keySet();
    return List.copyOf(teamNames);
  }

  public Score getScore(final String entry) {
    return this.scores.get(entry);
  }

  public Scoreboard getNewScoreboard() {
    return this.newScoreboard;
  }

  public Scoreboard getMainScoreboard() {
    return this.mainScoreboard;
  }

  public Objective getObjective() {
    return this.objective;
  }
}
