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
package me.brandonli.mcav.bukkit.media.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.papermc.paper.math.Position;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.BlockConfiguration;
import me.brandonli.mcav.bukkit.media.config.ChatConfiguration;
import me.brandonli.mcav.bukkit.media.config.EntityConfiguration;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.bukkit.media.lookup.BlockPaletteLookup;
import me.brandonli.mcav.bukkit.testing.FakeScoreboards;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.FakeWorld;
import me.brandonli.mcav.bukkit.testing.Images;
import me.brandonli.mcav.bukkit.testing.MapPackets;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import net.kyori.adventure.text.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.entity.CraftTextDisplay;
import org.bukkit.craftbukkit.scoreboard.CraftScoreboard;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

/**
 * Tests {@link DisplayableImage} and its displays {@link MapImage}, {@link ChatImage}, {@link EntityImage},
 * {@link ScoreboardImage}, and {@link BlockImage}.
 */
final class DisplayableImageTest {

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final int BROWN = (102 << 16) | (76 << 8) | 51;
  private static final String CLEARED_CHAT = "\n".repeat(99);

  private FakeServer server;
  private FakeWorld world;
  private CraftPlayer viewer;
  private DitherAlgorithm algorithm;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.viewer = this.server.addPlayer(VIEWER);
    this.server.injectModule();
    this.world = FakeWorld.create();
    this.algorithm = mock(DitherAlgorithm.class);
    when(this.algorithm.ditherIntoBytes(any(ImageBuffer.class))).thenAnswer(DisplayableImageTest::ditherToPattern);
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private static byte[] ditherToPattern(final InvocationOnMock invocation) {
    final ImageBuffer image = invocation.getArgument(0);
    return MapPackets.pattern(image);
  }

  private static MapConfiguration createMapConfiguration(final boolean resize) {
    final MapConfiguration.Builder<?> builder = MapConfiguration.builder();
    builder.viewers(List.of(VIEWER));
    builder.map(2);
    builder.mapBlockWidth(1);
    builder.mapBlockHeight(1);
    builder.mapWidthResolution(64);
    builder.mapHeightResolution(64);
    configureResize(builder, resize);
    return builder.build();
  }

  private static void configureResize(final MapConfiguration.Builder<?> builder, final boolean resize) {
    builder.resize(resize);
  }

  /**
   * Creates a configuration of a grid of maps, whose first map has the id 2.
   *
   * @param columns the number of maps per row
   * @param rows    the number of rows of maps
   * @return the configuration
   */
  private static MapConfiguration createMapGridConfiguration(final int columns, final int rows) {
    final MapConfiguration.Builder<?> builder = MapConfiguration.builder();
    builder.viewers(List.of(VIEWER));
    builder.map(2);
    builder.mapBlockWidth(columns);
    builder.mapBlockHeight(rows);
    builder.mapWidthResolution(64);
    builder.mapHeightResolution(64);
    configureResize(builder, false);
    return builder.build();
  }

  private List<ClientboundMapItemDataPacket> mapPackets(final int index) {
    final List<Packet<?>> packets = this.server.getSentPackets(VIEWER);
    final Packet<?> packet = packets.get(index);
    return MapPackets.unbundle(packet);
  }

  private static ChatConfiguration createChatConfiguration() {
    final ChatConfiguration.Builder<?> builder = ChatConfiguration.builder();
    builder.viewers(List.of(VIEWER));
    builder.character("#");
    builder.chatWidth(2);
    builder.chatHeight(1);
    return builder.build();
  }

  private EntityConfiguration createEntityConfiguration() {
    final World configuredWorld = this.world.getWorld();
    final Location position = new Location(configuredWorld, 0, 64, 0);
    final EntityConfiguration.Builder<?> builder = EntityConfiguration.builder();
    builder.viewers(List.of(VIEWER));
    builder.character("#");
    builder.position(position);
    builder.entityWidth(1);
    builder.entityHeight(1);
    return builder.build();
  }

  private static ScoreboardConfiguration createScoreboardConfiguration() {
    final ScoreboardConfiguration.Builder<?> builder = ScoreboardConfiguration.builder();
    builder.viewers(List.of(VIEWER));
    builder.character("#");
    builder.lines(1);
    builder.width(1);
    return builder.build();
  }

  private BlockConfiguration createBlockConfiguration() {
    final World configuredWorld = this.world.getWorld();
    final Location position = new Location(configuredWorld, 0, 64, 0);
    final BlockConfiguration.Builder<?> builder = BlockConfiguration.builder();
    builder.viewers(List.of(VIEWER));
    builder.position(position);
    builder.blockWidth(1);
    builder.blockHeight(1);
    return builder.build();
  }

  private String chatMessage(final int index) {
    final List<Packet<?>> packets = this.server.getSentPackets(VIEWER);
    final Packet<?> packet = packets.get(index);
    final ClientboundSystemChatPacket chatPacket = assertInstanceOf(ClientboundSystemChatPacket.class, packet);
    final net.minecraft.network.chat.Component content = chatPacket.content();
    return content.getString();
  }

  @Test
  void createsTheDisplayForEveryKindOfConfiguration() {
    final MapConfiguration mapConfiguration = createMapConfiguration(false);
    final ChatConfiguration chatConfiguration = createChatConfiguration();
    final EntityConfiguration entityConfiguration = this.createEntityConfiguration();
    final ScoreboardConfiguration scoreboardConfiguration = createScoreboardConfiguration();
    final BlockConfiguration blockConfiguration = this.createBlockConfiguration();

    final DisplayableImage map = DisplayableImage.map(mapConfiguration, this.algorithm);
    final DisplayableImage chat = DisplayableImage.chat(chatConfiguration);
    final DisplayableImage entity = DisplayableImage.entity(entityConfiguration);
    final DisplayableImage scoreboard = DisplayableImage.scoreboard(scoreboardConfiguration);
    final DisplayableImage block = DisplayableImage.block(blockConfiguration);

    assertInstanceOf(MapImage.class, map);
    assertInstanceOf(ChatImage.class, chat);
    assertInstanceOf(EntityImage.class, entity);
    assertInstanceOf(ScoreboardImage.class, scoreboard);
    assertInstanceOf(BlockImage.class, block);
  }

  @Test
  void rejectsMissingConfigurations() {
    final MapConfiguration mapConfiguration = createMapConfiguration(false);

    assertThrows(NullPointerException.class, () -> DisplayableImage.map(null, this.algorithm));
    assertThrows(NullPointerException.class, () -> DisplayableImage.map(mapConfiguration, null));
    assertThrows(NullPointerException.class, () -> DisplayableImage.chat(null));
    assertThrows(NullPointerException.class, () -> DisplayableImage.entity(null));
    assertThrows(NullPointerException.class, () -> DisplayableImage.scoreboard(null));
    assertThrows(NullPointerException.class, () -> DisplayableImage.block(null));
  }

  @Test
  void mapImagesKeepTheirSizeAreCenteredAndClearedAgain() {
    final MapConfiguration configuration = createMapConfiguration(false);
    final DisplayableImage display = DisplayableImage.map(configuration, this.algorithm);
    final ImageBuffer image = Images.solid(32, 16, 0xFF808080);

    display.displayImage(image);
    display.release();

    final List<ClientboundMapItemDataPacket> shown = this.mapPackets(0);
    final List<ClientboundMapItemDataPacket> cleared = this.mapPackets(1);
    final ClientboundMapItemDataPacket shownPacket = shown.getFirst();
    final ClientboundMapItemDataPacket clearedPacket = cleared.getFirst();
    final byte[] colors = MapPackets.pattern(32 * 16, 4);
    final byte[] transparent = new byte[128 * 128];
    final int width = image.getWidth();
    final int height = image.getHeight();
    assertEquals(32, width, "a 2:1 image is not stretched to the square resolution");
    assertEquals(16, height);
    MapPackets.assertMapPacket(shownPacket, 2, 48, 56, 32, 16, colors);
    MapPackets.assertMapPacket(clearedPacket, 2, 0, 0, 128, 128, transparent);
    assertThrows(NullPointerException.class, () -> display.displayImage(null));
  }

  @Test
  void mapImagesClearEveryMapOfTheirGrid() {
    final MapConfiguration configuration = createMapGridConfiguration(2, 2);
    final DisplayableImage display = DisplayableImage.map(configuration, this.algorithm);

    display.release();

    final List<ClientboundMapItemDataPacket> cleared = this.mapPackets(0);
    final int clearedCount = cleared.size();
    final ClientboundMapItemDataPacket last = cleared.get(3);
    final byte[] transparent = new byte[128 * 128];

    assertEquals(4, clearedCount, "every map of the 2x2 grid is cleared");
    MapPackets.assertMapPacket(last, 5, 0, 0, 128, 128, transparent);
  }

  @Test
  void mapImagesLargerThanTheMapsAreCroppedEquallyOnEverySide() {
    final MapConfiguration configuration = createMapConfiguration(false);
    final DisplayableImage display = DisplayableImage.map(configuration, this.algorithm);
    final ImageBuffer image = Images.solid(256, 130, 0xFF808080);

    display.displayImage(image);

    final List<ClientboundMapItemDataPacket> shown = this.mapPackets(0);
    final ClientboundMapItemDataPacket shownPacket = shown.getFirst();
    final byte[] source = MapPackets.pattern(256 * 130, 4);
    final byte[] expected = new byte[128 * 128];
    for (int row = 0; row < 128; row++) {
      final int sourceIndex = (row + 1) * 256 + 64;
      final int targetIndex = row * 128;
      System.arraycopy(source, sourceIndex, expected, targetIndex, 128);
    }
    MapPackets.assertMapPacket(shownPacket, 2, 0, 0, 128, 128, expected);
  }

  @Test
  void mapImagesAreResizedWhenTheConfigurationAsksForIt() {
    final MapConfiguration configuration = createMapConfiguration(true);
    final DisplayableImage display = DisplayableImage.map(configuration, this.algorithm);
    final ImageBuffer image = Images.solid(128, 128, 0xFF808080);

    display.displayImage(image);

    final List<ClientboundMapItemDataPacket> shown = this.mapPackets(0);
    final ClientboundMapItemDataPacket shownPacket = shown.getFirst();
    final byte[] colors = MapPackets.pattern(64 * 64, 4);
    final int width = image.getWidth();
    assertEquals(64, width);
    MapPackets.assertMapPacket(shownPacket, 2, 32, 32, 64, 64, colors);
  }

  @Test
  void chatImagesReplaceThePreviousImageAndClearTheChatAgain() {
    final ChatConfiguration configuration = createChatConfiguration();
    final DisplayableImage display = DisplayableImage.chat(configuration);
    final ImageBuffer image = Images.solid(4, 2, 0xFF808080);

    display.displayImage(image);
    display.release();

    final String firstClear = this.chatMessage(0);
    final String shown = this.chatMessage(1);
    final String secondClear = this.chatMessage(2);
    final int width = image.getWidth();
    assertEquals(CLEARED_CHAT, firstClear);
    assertEquals("##", shown);
    assertEquals(CLEARED_CHAT, secondClear);
    assertEquals(2, width);
    assertThrows(NullPointerException.class, () -> display.displayImage(null));
  }

  @Test
  void entityImagesSpawnADisplayAndRemoveItAgain() {
    final EntityConfiguration configuration = this.createEntityConfiguration();
    final DisplayableImage display = DisplayableImage.entity(configuration);
    final ImageBuffer image = Images.solid(1, 1, 0xFF808080);

    display.displayImage(image);
    this.server.runTasks();
    display.release();

    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final CraftTextDisplay entity = displays.getFirst();
    final net.minecraft.world.entity.Display.TextDisplay handle = entity.getHandle();
    verify(handle).setText(any(net.minecraft.network.chat.Component.class));
    verify(entity).remove();
    assertThrows(NullPointerException.class, () -> display.displayImage(null));
  }

  @Test
  void scoreboardImagesShowTheSidebarAndRestoreThePreviousScoreboard() {
    final ScoreboardManager manager = this.server.getScoreboardManager();
    final FakeScoreboards scoreboards = FakeScoreboards.install(manager);
    final Scoreboard previous = mock(CraftScoreboard.class);
    FakeScoreboards.trackScoreboard(this.viewer, previous);
    final ScoreboardConfiguration configuration = createScoreboardConfiguration();
    final DisplayableImage display = DisplayableImage.scoreboard(configuration);
    final ImageBuffer image = Images.solid(1, 1, 0xFF808080);

    display.displayImage(image);
    this.server.runTasks();
    final Scoreboard shown = this.viewer.getScoreboard();
    display.release();

    final Scoreboard restored = this.viewer.getScoreboard();
    final Scoreboard board = scoreboards.getBoard();
    final List<Team> teams = scoreboards.getTeams();
    final Team firstTeam = teams.getFirst();
    assertSame(board, shown);
    assertSame(previous, restored);
    verify(firstTeam).suffix(any(Component.class));
    assertThrows(NullPointerException.class, () -> display.displayImage(null));
  }

  @Test
  void blockImagesShowBlocksAndRestoreTheWorldAgain() {
    final BlockConfiguration configuration = this.createBlockConfiguration();
    final DisplayableImage display = DisplayableImage.block(configuration);
    final ImageBuffer image = Images.solid(1, 1, 0xFF000000 | BROWN);

    display.displayImage(image);
    this.server.runTasks();
    display.release();

    final Position position = Position.block(0, 64, 0);
    final BlockData brown = BlockPaletteLookup.getBlockData(BROWN);
    final BlockData original = this.world.getOriginalBlock(0, 64, 0);
    final Map<Position, BlockData> shownBlocks = Map.of(position, brown);
    final Map<Position, BlockData> restoredBlocks = Map.of(position, original);
    verify(this.viewer).sendMultiBlockChange(shownBlocks);
    verify(this.viewer).sendMultiBlockChange(restoredBlocks);
    assertThrows(NullPointerException.class, () -> display.displayImage(null));
  }
}
