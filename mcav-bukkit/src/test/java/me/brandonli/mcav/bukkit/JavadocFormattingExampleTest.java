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
package me.brandonli.mcav.bukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import me.brandonli.mcav.bukkit.media.image.DisplayableImage;
import me.brandonli.mcav.bukkit.media.result.Characters;
import me.brandonli.mcav.bukkit.testing.FakeScoreboards;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.scoreboard.CraftScoreboard;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JavadocFormattingExampleTest {

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private static final int PICTURE_WIDTH = 16;
  private static final int PICTURE_HEIGHT = 15;

  private static final int EVEN_ROW_COLOR = 0x123456;
  private static final int ODD_ROW_COLOR = 0xABCDEF;

  private static final String PICTURE_FILE_NAME = "picture.png";

  private Path writePicture(final Path directory) throws IOException {
    final BufferedImage picture = new BufferedImage(PICTURE_WIDTH, PICTURE_HEIGHT, BufferedImage.TYPE_INT_RGB);
    for (int row = 0; row < PICTURE_HEIGHT; row++) {
      for (int column = 0; column < PICTURE_WIDTH; column++) {
        picture.setRGB(column, row, row % 2 == 0 ? EVEN_ROW_COLOR : ODD_ROW_COLOR);
      }
    }

    final Path file = directory.resolve(PICTURE_FILE_NAME);
    final File output = file.toFile();
    ImageIO.write(picture, "png", output);
    return file;
  }

  @Test
  void shouldShowTheImageOnTheScoreboardAndRestoreThePreviousScoreboard(@TempDir final Path directory)
    throws IOException, ReflectiveOperationException {
    final Path file = this.writePicture(directory);
    try (final FakeServer server = FakeServer.start()) {
      final CraftPlayer viewer = server.addPlayer(VIEWER);
      server.injectModule();
      final ScoreboardManager manager = server.getScoreboardManager();
      final FakeScoreboards scoreboards = FakeScoreboards.install(manager);
      final Scoreboard previous = mock(CraftScoreboard.class);
      FakeScoreboards.trackScoreboard(viewer, previous);

      final Collection<UUID> viewers = List.of(VIEWER);
      final DisplayableImage display = JavadocFormattingExample.showImageOnScoreboard(viewers, file);
      try {
        server.runTasks();

        final Scoreboard board = scoreboards.getNewScoreboard();
        verify(viewer).setScoreboard(board);

        final List<Team> teams = scoreboards.getTeams();
        final int teamCount = teams.size();
        assertEquals(PICTURE_HEIGHT, teamCount);

        this.verifyStripes(teams);
      } finally {
        display.release();
      }

      final Scoreboard restored = viewer.getScoreboard();
      assertSame(previous, restored);
    }
  }

  private void verifyStripes(final List<Team> teams) {
    for (int row = 0; row < PICTURE_HEIGHT; row++) {
      final int color = row % 2 == 0 ? EVEN_ROW_COLOR : ODD_ROW_COLOR;
      verify(teams.get(row)).suffix(Component.text(Characters.BLACK_SQUARE.repeat(PICTURE_WIDTH), TextColor.color(color)));
    }
  }
}
