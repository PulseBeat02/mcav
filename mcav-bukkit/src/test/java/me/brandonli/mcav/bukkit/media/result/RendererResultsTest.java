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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.papermc.paper.math.Position;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.BlockConfiguration;
import me.brandonli.mcav.bukkit.media.config.EntityConfiguration;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.bukkit.media.lookup.BlockPaletteLookup;
import me.brandonli.mcav.bukkit.testing.FakeScoreboards;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.FakeWorld;
import me.brandonli.mcav.bukkit.testing.Images;
import me.brandonli.mcav.bukkit.testing.UtilityClassAssertions;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.entity.CraftTextDisplay;
import org.bukkit.craftbukkit.scoreboard.CraftScoreboard;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link BlockResult}, {@link EntityResult}, and {@link ScoreboardResult}, the video filters that hand every
 * frame to a renderer.
 */
final class RendererResultsTest {

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final int BROWN = (102 << 16) | (76 << 8) | 51;

  private FakeServer server;
  private FakeWorld world;
  private CraftPlayer viewer;
  private OriginalVideoMetadata metadata;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.viewer = this.server.addPlayer(VIEWER);
    this.server.injectModule();
    this.world = FakeWorld.create();
    this.metadata = mock(OriginalVideoMetadata.class);
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private Location createPosition() {
    final World configuredWorld = this.world.getWorld();
    return new Location(configuredWorld, 0, 64, 0);
  }

  @Test
  void blockResultsShowFramesAsBlocksAndRestoreTheWorld() {
    final BlockConfiguration.Builder<?> builder = BlockConfiguration.builder();
    final Location position = this.createPosition();
    builder.viewers(List.of(VIEWER));
    builder.position(position);
    builder.blockWidth(1);
    builder.blockHeight(1);
    final BlockConfiguration configuration = builder.build();
    final BlockResult result = new BlockResult(configuration);
    result.start();
    final ImageBuffer image = Images.solid(1, 1, 0xFF000000 | BROWN);
    final boolean handled = result.applyFilter(image, this.metadata);
    this.server.runTasks();
    result.release();
    final Position block = Position.block(0, 64, 0);
    final BlockData brown = BlockPaletteLookup.getBlockData(BROWN);
    final BlockData original = this.world.getOriginalBlock(0, 64, 0);
    final Map<Position, BlockData> shown = Map.of(block, brown);
    final Map<Position, BlockData> restored = Map.of(block, original);

    assertTrue(handled);
    verify(this.viewer).sendMultiBlockChange(shown);
    verify(this.viewer).sendMultiBlockChange(restored);
    assertThrows(NullPointerException.class, () -> new BlockResult(null));

    this.assertRejectsMissingFrames(result);
  }

  private void assertRejectsMissingFrames(final FunctionalVideoFilter result) {
    assertThrows(NullPointerException.class, () -> result.applyFilter(null, this.metadata));

    try (final ImageBuffer image = Images.solid(1, 1, 0)) {
      assertThrows(NullPointerException.class, () -> result.applyFilter(image, null));
    }
  }

  @Test
  void entityResultsShowFramesInATextDisplayAndRemoveIt() {
    final EntityConfiguration.Builder<?> builder = EntityConfiguration.builder();
    final Location position = this.createPosition();
    builder.viewers(List.of(VIEWER));
    builder.character("#");
    builder.position(position);
    builder.entityWidth(1);
    builder.entityHeight(1);
    final EntityConfiguration configuration = builder.build();
    final EntityResult result = new EntityResult(configuration);
    result.start();
    final ImageBuffer image = Images.solid(1, 1, 0xFF000000);
    final boolean handled = result.applyFilter(image, this.metadata);
    this.server.runTasks();
    result.release();
    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final CraftTextDisplay display = displays.getFirst();
    final net.minecraft.world.entity.Display.TextDisplay handle = display.getHandle();
    final Plugin plugin = this.server.getPlugin();

    assertTrue(handled);
    verify(this.viewer).showEntity(plugin, display);
    verify(handle).setText(any(net.minecraft.network.chat.Component.class));
    verify(display).remove();
    assertThrows(NullPointerException.class, () -> new EntityResult(null));

    this.assertRejectsMissingFrames(result);
  }

  @Test
  void scoreboardResultsShowFramesOnTheSidebarAndRestoreThePreviousScoreboard() {
    final ScoreboardManager manager = this.server.getScoreboardManager();
    final FakeScoreboards scoreboards = FakeScoreboards.install(manager);
    final Scoreboard previous = mock(CraftScoreboard.class);
    FakeScoreboards.trackScoreboard(this.viewer, previous);
    final ScoreboardConfiguration.Builder<?> builder = ScoreboardConfiguration.builder();
    builder.viewers(List.of(VIEWER));
    builder.character("#");
    builder.lines(1);
    builder.width(1);
    final ScoreboardConfiguration configuration = builder.build();
    final ScoreboardResult result = new ScoreboardResult(configuration);
    result.start();
    final Scoreboard shown = this.viewer.getScoreboard();
    final ImageBuffer image = Images.solid(1, 1, 0xFF000000);
    final boolean handled = result.applyFilter(image, this.metadata);
    this.server.runTasks();
    result.release();
    final Scoreboard restored = this.viewer.getScoreboard();
    final Scoreboard board = scoreboards.getBoard();
    final List<Team> teams = scoreboards.getTeams();
    final Team team = teams.getFirst();

    assertTrue(handled);
    assertSame(board, shown);
    assertSame(previous, restored);
    verify(team).suffix(any(Component.class));
    assertThrows(NullPointerException.class, () -> new ScoreboardResult(null));

    this.assertRejectsMissingFrames(result);
  }

  @Test
  void offersCharactersThatWorkAsPixels() {
    final List<String> characters = List.of(
      Characters.FULL_CHARACTER,
      Characters.WHITE_SQUARE,
      Characters.BLACK_SQUARE,
      Characters.WHITE_CIRCLE,
      Characters.BLACK_CIRCLE,
      Characters.SMALL_WHITE_SQUARE,
      Characters.SMALL_BLACK_SQUARE
    );

    assertEquals(List.of("█", "□", "■", "○", "●", "▫", "▪"), characters);
    UtilityClassAssertions.assertNotInstantiable(Characters.class);
  }
}
