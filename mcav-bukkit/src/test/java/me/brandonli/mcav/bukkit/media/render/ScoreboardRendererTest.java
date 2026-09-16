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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.bukkit.testing.FakeScoreboards;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.utils.ChatUtils;
import me.brandonli.mcav.media.image.ImageBuffer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.scoreboard.CraftScoreboard;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests {@link ScoreboardRenderer}.
 */
final class ScoreboardRendererTest {

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID LATE_VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID OFFLINE = UUID.fromString("00000000-0000-0000-0000-000000000003");

  private FakeServer server;
  private FakeScoreboards scoreboards;
  private CraftPlayer viewer;
  private Scoreboard previousBoard;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.server.injectModule();
    final ScoreboardManager manager = this.server.getScoreboardManager();
    this.scoreboards = FakeScoreboards.install(manager);
    this.viewer = this.server.addPlayer(VIEWER);
    this.previousBoard = mock(CraftScoreboard.class, "previous scoreboard");
    FakeScoreboards.trackScoreboard(this.viewer, this.previousBoard);
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private static ScoreboardConfiguration createConfiguration(final int lines) {
    final ScoreboardConfiguration.Builder<?> builder = ScoreboardConfiguration.builder();
    builder.viewers(List.of(VIEWER, LATE_VIEWER, OFFLINE));
    builder.character("#");
    builder.lines(lines);
    builder.width(2);
    return builder.build();
  }

  private void assertLinesAreOrdered() {
    final List<Team> teams = this.scoreboards.getTeams();
    final int lines = teams.size();
    for (int row = 0; row < lines; row++) {
      final String entry = ChatUtils.getUniqueString(row);
      final Team team = teams.get(row);
      final Score score = this.scoreboards.getScore(entry);
      verify(team).addEntry(entry);
      verify(score).setScore(lines - row);
    }
  }

  @Test
  void createsAnOrderedSidebarWithHiddenNumbersAndShowsIt() {
    final ScoreboardConfiguration configuration = createConfiguration(3);
    final ScoreboardRenderer renderer = new ScoreboardRenderer(configuration);

    renderer.show();

    final Scoreboard board = this.scoreboards.getBoard();
    final Objective objective = this.scoreboards.getObjective();
    final ArgumentCaptor<String> objectiveName = ArgumentCaptor.forClass(String.class);
    final Component emptyTitle = Component.empty();
    verify(board).registerNewObjective(objectiveName.capture(), eq(Criteria.DUMMY), eq(emptyTitle));
    final String name = objectiveName.getValue();
    final boolean validName = name.matches("mcav_\\d+");
    final List<String> teamNames = this.scoreboards.getTeamNames();
    final List<String> expectedTeamNames = List.of(name + "_0", name + "_1", name + "_2");
    final Scoreboard current = this.viewer.getScoreboard();
    final NumberFormat hiddenNumbers = NumberFormat.blank();
    assertTrue(validName, name);
    assertEquals(expectedTeamNames, teamNames);
    assertSame(board, current);
    verify(objective).setDisplaySlot(DisplaySlot.SIDEBAR);
    verify(objective).numberFormat(hiddenNumbers);
    this.assertLinesAreOrdered();
  }

  @Test
  void usesDifferentNamesForEveryRenderer() {
    final ScoreboardConfiguration firstConfiguration = createConfiguration(1);
    final ScoreboardConfiguration secondConfiguration = createConfiguration(1);
    final ScoreboardRenderer first = new ScoreboardRenderer(firstConfiguration);
    final ScoreboardRenderer second = new ScoreboardRenderer(secondConfiguration);

    first.show();
    second.show();

    final Scoreboard board = this.scoreboards.getBoard();
    final ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
    verify(board, times(2)).registerNewObjective(names.capture(), any(Criteria.class), any(Component.class));
    final List<String> objectiveNames = names.getAllValues();
    final String firstName = objectiveNames.get(0);
    final String secondName = objectiveNames.get(1);
    assertNotEquals(firstName, secondName, "objective names are unique");
  }

  @Test
  void createsTheScoreboardOnlyOnce() {
    final ScoreboardConfiguration configuration = createConfiguration(1);
    final ScoreboardRenderer renderer = new ScoreboardRenderer(configuration);

    renderer.show();
    renderer.show();

    final ScoreboardManager manager = this.server.getScoreboardManager();
    verify(manager, times(1)).getNewScoreboard();
  }

  @Test
  void drawsEveryImageRowIntoTheSuffixOfItsTeam() {
    final ScoreboardConfiguration configuration = createConfiguration(2);
    final ScoreboardRenderer renderer = new ScoreboardRenderer(configuration);
    final int[] pixels = { 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF0000FF };
    final ImageBuffer image = ImageBuffer.buffer(pixels, 2, 2);

    renderer.show();
    renderer.render(image);
    this.server.runTasks();

    final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacySection();
    final String firstLine = ChatUtils.createRawLine(pixels, "#", 2, 0);
    final String secondLine = ChatUtils.createRawLine(pixels, "#", 2, 1);
    final Component expectedFirst = serializer.deserialize(firstLine);
    final Component expectedSecond = serializer.deserialize(secondLine);
    final List<Team> teams = this.scoreboards.getTeams();
    final Team firstTeam = teams.get(0);
    final Team secondTeam = teams.get(1);
    verify(firstTeam).suffix(expectedFirst);
    verify(secondTeam).suffix(expectedSecond);
  }

  @Test
  void ignoresFramesBeforeTheScoreboardExists() {
    final ScoreboardConfiguration configuration = createConfiguration(1);
    final ScoreboardRenderer renderer = new ScoreboardRenderer(configuration);
    final Component line = Component.text("line");
    final Component[] lines = { line };

    renderer.apply(lines);

    final Scoreboard board = this.scoreboards.getBoard();
    verify(board, never()).registerNewTeam(anyString());
  }

  @Test
  void stopsRenderingFramesWhenItIsHidden() {
    final ScoreboardConfiguration configuration = createConfiguration(1);
    final ScoreboardRenderer renderer = new ScoreboardRenderer(configuration);

    renderer.show();
    final int whileShown = this.server.runTasks();
    renderer.hide();
    final int afterHide = this.server.runTasks();

    assertEquals(1, whileShown, "a shown scoreboard applies its frames once per tick");
    assertEquals(0, afterHide, "hiding the scoreboard stops its render task");
  }

  @Test
  void restoresThePreviousScoreboardOfViewersThatStillSeeIt() {
    final ScoreboardConfiguration configuration = createConfiguration(2);
    final ScoreboardRenderer renderer = new ScoreboardRenderer(configuration);
    renderer.show();
    final Scoreboard board = this.scoreboards.getBoard();
    final CraftPlayer lateViewer = this.server.addPlayer(LATE_VIEWER);
    FakeScoreboards.trackScoreboard(lateViewer, board);

    renderer.hide();
    renderer.hide();

    final Scoreboard viewerBoard = this.viewer.getScoreboard();
    final Scoreboard lateBoard = lateViewer.getScoreboard();
    final Scoreboard mainBoard = this.scoreboards.getMainBoard();
    final Objective objective = this.scoreboards.getObjective();
    final List<Team> teams = this.scoreboards.getTeams();
    assertSame(this.previousBoard, viewerBoard);
    assertSame(mainBoard, lateBoard, "viewers that joined later get the main scoreboard");
    verify(objective, times(1)).unregister();
    for (final Team team : teams) {
      verify(team, times(1)).unregister();
    }
  }

  @Test
  void leavesViewersAloneThatSwitchedToAnotherScoreboard() {
    final ScoreboardConfiguration configuration = createConfiguration(1);
    final ScoreboardRenderer renderer = new ScoreboardRenderer(configuration);
    final Scoreboard otherBoard = mock(CraftScoreboard.class, "other plugin");

    renderer.show();
    this.viewer.setScoreboard(otherBoard);
    renderer.hide();

    final Scoreboard current = this.viewer.getScoreboard();
    assertSame(otherBoard, current);
  }

  @Test
  void rejectsMissingArguments() {
    final ScoreboardConfiguration configuration = createConfiguration(1);
    final ScoreboardRenderer renderer = new ScoreboardRenderer(configuration);

    assertThrows(NullPointerException.class, () -> new ScoreboardRenderer(null));
    assertThrows(NullPointerException.class, () -> renderer.render(null));
  }
}
