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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import javax.imageio.ImageIO;
import me.brandonli.mcav.bukkit.testing.FakeScoreboards;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.scoreboard.CraftScoreboard;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link JavaDocFormattingExample}, so the example from the documentation keeps working.
 */
final class JavaDocFormattingExampleTest {

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private static Path writePicture(final Path directory) throws IOException {
    final BufferedImage picture = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
    picture.setRGB(0, 0, 0xFF0000);
    final Path file = directory.resolve("picture.png");
    final File output = file.toFile();
    ImageIO.write(picture, "png", output);
    return file;
  }

  @Test
  void showsTheImageOnTheScoreboardAndRestoresThePreviousScoreboard(@TempDir final Path directory)
    throws IOException, ReflectiveOperationException {
    final Path file = writePicture(directory);
    try (final FakeServer server = FakeServer.start()) {
      final CraftPlayer viewer = server.addPlayer(VIEWER);
      server.injectModule();
      final ScoreboardManager manager = server.getScoreboardManager();
      final FakeScoreboards scoreboards = FakeScoreboards.install(manager);
      final Scoreboard previous = mock(CraftScoreboard.class);
      FakeScoreboards.trackScoreboard(viewer, previous);

      JavaDocFormattingExample.showScoreboardImage(VIEWER, file);

      final Scoreboard board = scoreboards.getBoard();
      final Scoreboard restored = viewer.getScoreboard();
      verify(viewer).setScoreboard(board);
      assertSame(previous, restored);
    }
  }
}
