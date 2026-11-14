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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.BlockConfiguration;
import me.brandonli.mcav.bukkit.media.config.BlockConfigurationBuilder;
import me.brandonli.mcav.bukkit.media.config.ChatConfiguration;
import me.brandonli.mcav.bukkit.media.config.ChatConfigurationBuilder;
import me.brandonli.mcav.bukkit.media.config.EntityConfiguration;
import me.brandonli.mcav.bukkit.media.config.EntityConfigurationBuilder;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.config.MapConfigurationBuilder;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfiguration;
import me.brandonli.mcav.bukkit.media.config.ScoreboardConfigurationBuilder;
import me.brandonli.mcav.bukkit.media.lookup.BlockPaletteLookup;
import me.brandonli.mcav.bukkit.testing.FakeScoreboards;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.FakeWorld;
import me.brandonli.mcav.bukkit.testing.Images;
import me.brandonli.mcav.bukkit.testing.MapPackets;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.entity.Display;
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
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;

final class DisplayableImageTest {

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final int BROWN = (102 << 16) | (76 << 8) | 51;
  private static final String CLEARED_CHAT = "\n".repeat(99);

  private static final int MAP_RESOLUTION = 64;

  private static final int MAP_SIZE = 128;

  private static final String CHARACTER = "#";

  private static final int GRAY = 0xFF808080;
  private static final int GRAY_RGB = 0x808080;

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
    this.world.enter(this.viewer);
    this.algorithm = mock(DitherAlgorithm.class);
    when(this.algorithm.ditherIntoBytes(any(ImageBuffer.class))).thenAnswer(this::ditherToPattern);
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private byte[] ditherToPattern(final InvocationOnMock invocation) {
    final ImageBuffer image = invocation.getArgument(0);
    return MapPackets.pattern(image);
  }

  private MapConfiguration createMapConfiguration(final Sizing sizing) {
    final MapConfigurationBuilder builder = MapConfiguration.builder();
    builder.viewers(List.of(VIEWER));
    builder.map(2);
    builder.mapBlockWidth(1);
    builder.mapBlockHeight(1);
    builder.mapWidthResolution(MAP_RESOLUTION);
    builder.mapHeightResolution(MAP_RESOLUTION);
    builder.resize(sizing == Sizing.RESIZED);
    return builder.build();
  }

  private MapConfiguration createMapGridConfiguration(final int columns, final int rows) {
    final MapConfigurationBuilder builder = MapConfiguration.builder();
    builder.viewers(List.of(VIEWER));
    builder.map(2);
    builder.mapBlockWidth(columns);
    builder.mapBlockHeight(rows);
    builder.mapWidthResolution(MAP_RESOLUTION);
    builder.mapHeightResolution(MAP_RESOLUTION);
    builder.resize(false);
    return builder.build();
  }

  private List<ClientboundMapItemDataPacket> mapPackets(final int index) {
    final List<Packet<?>> packets = this.server.getSentPackets(VIEWER);
    final Packet<?> packet = packets.get(index);
    return MapPackets.unbundle(packet);
  }

  private ChatConfiguration createChatConfiguration() {
    final ChatConfigurationBuilder builder = ChatConfiguration.builder();
    builder.viewers(List.of(VIEWER));
    builder.character(CHARACTER);
    builder.chatWidth(2);
    builder.chatHeight(1);
    return builder.build();
  }

  private EntityConfiguration createEntityConfiguration() {
    final World configuredWorld = this.world.getWorld();
    final Location position = new Location(configuredWorld, 0, 64, 0);
    final EntityConfigurationBuilder builder = EntityConfiguration.builder();
    builder.viewers(List.of(VIEWER));
    builder.character(CHARACTER);
    builder.position(position);
    builder.entityWidth(1);
    builder.entityHeight(1);
    return builder.build();
  }

  private ScoreboardConfiguration createScoreboardConfiguration() {
    final ScoreboardConfigurationBuilder builder = ScoreboardConfiguration.builder();
    builder.viewers(List.of(VIEWER));
    builder.character(CHARACTER);
    builder.lines(1);
    builder.width(1);
    return builder.build();
  }

  private BlockConfiguration createBlockConfiguration() {
    final World configuredWorld = this.world.getWorld();
    final Location position = new Location(configuredWorld, 0, 64, 0);
    final BlockConfigurationBuilder builder = BlockConfiguration.builder();
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
    final net.minecraft.network.chat.Component content = chatPacket.content(); // fqn: Component is imported as net.kyori.adventure.text.Component
    return content.getString();
  }

  @Test
  void shouldCreateTheDisplayForEveryKindOfConfiguration() {
    final MapConfiguration mapConfiguration = this.createMapConfiguration(Sizing.ORIGINAL);
    final ChatConfiguration chatConfiguration = this.createChatConfiguration();
    final EntityConfiguration entityConfiguration = this.createEntityConfiguration();
    final ScoreboardConfiguration scoreboardConfiguration = this.createScoreboardConfiguration();
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
  void shouldRejectMissingConfigurations() {
    final MapConfiguration mapConfiguration = this.createMapConfiguration(Sizing.ORIGINAL);

    assertThrows(NullPointerException.class, () -> DisplayableImage.map(null, this.algorithm));
    assertThrows(NullPointerException.class, () -> DisplayableImage.map(mapConfiguration, null));
    assertThrows(NullPointerException.class, () -> DisplayableImage.chat(null));
    assertThrows(NullPointerException.class, () -> DisplayableImage.entity(null));
    assertThrows(NullPointerException.class, () -> DisplayableImage.scoreboard(null));
    assertThrows(NullPointerException.class, () -> DisplayableImage.block(null));
  }

  @Test
  void shouldKeepTheSizeOfMapImagesCenterThemAndClearThemAgain() {
    final MapConfiguration configuration = this.createMapConfiguration(Sizing.ORIGINAL);
    final DisplayableImage display = DisplayableImage.map(configuration, this.algorithm);
    final ImageBuffer image = Images.solid(32, 16, GRAY);

    display.displayImage(image);
    display.release();

    final List<ClientboundMapItemDataPacket> shown = this.mapPackets(0);
    final List<ClientboundMapItemDataPacket> cleared = this.mapPackets(1);
    final ClientboundMapItemDataPacket shownPacket = shown.getFirst();
    final ClientboundMapItemDataPacket clearedPacket = cleared.getFirst();
    final byte[] colors = MapPackets.pattern(32 * 16, 4);
    final byte[] transparent = new byte[MAP_SIZE * MAP_SIZE];
    final int width = image.getWidth();
    final int height = image.getHeight();
    assertEquals(32, width, "a 2:1 image is not stretched to the square resolution");
    assertEquals(16, height);

    MapPackets.assertMapPacket(shownPacket, 2, 48, 56, 32, 16, colors);
    MapPackets.assertMapPacket(clearedPacket, 2, 0, 0, MAP_SIZE, MAP_SIZE, transparent);
    assertThrows(NullPointerException.class, () -> display.displayImage(null));
  }

  @Test
  void shouldClearEveryMapOfTheGridOfAMapImage() {
    final MapConfiguration configuration = this.createMapGridConfiguration(2, 2);
    final DisplayableImage display = DisplayableImage.map(configuration, this.algorithm);

    display.release();

    final List<ClientboundMapItemDataPacket> cleared = this.mapPackets(0);
    final int clearedCount = cleared.size();
    final ClientboundMapItemDataPacket last = cleared.get(3);
    final byte[] transparent = new byte[MAP_SIZE * MAP_SIZE];

    assertEquals(4, clearedCount, "every map of the 2x2 grid is cleared");

    MapPackets.assertMapPacket(last, 5, 0, 0, MAP_SIZE, MAP_SIZE, transparent);
  }

  @Test
  void shouldCenterMapImagesOnTheWholeWallAndSplitThemAcrossConsecutiveMaps() {
    final MapConfiguration configuration = this.createMapGridConfiguration(2, 1);
    final DisplayableImage display = DisplayableImage.map(configuration, this.algorithm);
    final ImageBuffer image = Images.solid(32, 16, GRAY);

    display.displayImage(image);

    final List<ClientboundMapItemDataPacket> shown = this.mapPackets(0);
    final int shownCount = shown.size();
    final ClientboundMapItemDataPacket leftPacket = shown.getFirst();
    final ClientboundMapItemDataPacket rightPacket = shown.get(1);
    final byte[] source = MapPackets.pattern(32 * 16, 4);
    final byte[] leftColors = new byte[16 * 16];
    final byte[] rightColors = new byte[16 * 16];
    for (int row = 0; row < 16; row++) {
      System.arraycopy(source, row * 32, leftColors, row * 16, 16);
      System.arraycopy(source, row * 32 + 16, rightColors, row * 16, 16);
    }
    assertEquals(2, shownCount, "the image spans both maps of the wall");
    MapPackets.assertMapPacket(leftPacket, 2, 112, 56, 16, 16, leftColors);
    MapPackets.assertMapPacket(rightPacket, 3, 0, 56, 16, 16, rightColors);
  }

  @Test
  void shouldCropMapImagesLargerThanTheMapsEquallyOnEverySide() {
    final MapConfiguration configuration = this.createMapConfiguration(Sizing.ORIGINAL);
    final DisplayableImage display = DisplayableImage.map(configuration, this.algorithm);
    final ImageBuffer image = Images.solid(256, 130, GRAY);

    display.displayImage(image);

    final List<ClientboundMapItemDataPacket> shown = this.mapPackets(0);
    final ClientboundMapItemDataPacket shownPacket = shown.getFirst();
    final byte[] source = MapPackets.pattern(256 * 130, 4);
    final byte[] expected = new byte[MAP_SIZE * MAP_SIZE];
    for (int row = 0; row < MAP_SIZE; row++) {
      final int sourceIndex = (row + 1) * 256 + 64;
      final int targetIndex = row * MAP_SIZE;
      System.arraycopy(source, sourceIndex, expected, targetIndex, MAP_SIZE);
    }

    MapPackets.assertMapPacket(shownPacket, 2, 0, 0, MAP_SIZE, MAP_SIZE, expected);
  }

  @Test
  void shouldResizeMapImagesWhenTheConfigurationAsksForIt() {
    final MapConfiguration configuration = this.createMapConfiguration(Sizing.RESIZED);
    final DisplayableImage display = DisplayableImage.map(configuration, this.algorithm);
    final ImageBuffer image = Images.solid(MAP_SIZE, MAP_SIZE, GRAY);

    display.displayImage(image);

    final List<ClientboundMapItemDataPacket> shown = this.mapPackets(0);
    final ClientboundMapItemDataPacket shownPacket = shown.getFirst();
    final byte[] colors = MapPackets.pattern(MAP_RESOLUTION * MAP_RESOLUTION, 4);
    final int width = image.getWidth();
    assertEquals(MAP_RESOLUTION, width);

    MapPackets.assertMapPacket(shownPacket, 2, 32, 32, MAP_RESOLUTION, MAP_RESOLUTION, colors);
  }

  @Test
  void shouldResizeChatImagesInPlaceReplaceThePreviousChatImageAndClearTheChatAgain() {
    final ChatConfiguration configuration = this.createChatConfiguration();
    final DisplayableImage display = DisplayableImage.chat(configuration);
    final ImageBuffer image = Images.solid(4, 2, GRAY);

    display.displayImage(image);
    display.release();

    final String firstClear = this.chatMessage(0);
    final String shown = this.chatMessage(1);
    final String secondClear = this.chatMessage(2);
    final int width = image.getWidth();
    final int height = image.getHeight();
    assertEquals(CLEARED_CHAT, firstClear);
    assertEquals("##", shown);
    assertEquals(CLEARED_CHAT, secondClear);
    assertEquals(2, width);
    assertEquals(1, height);
    assertThrows(NullPointerException.class, () -> display.displayImage(null));
  }

  @Test
  void shouldResizeEntityImagesInPlaceSpawnADisplayAndRemoveItAgain() {
    final EntityConfiguration configuration = this.createEntityConfiguration();
    final DisplayableImage display = DisplayableImage.entity(configuration);
    final ImageBuffer image = Images.solid(4, 2, GRAY);

    display.displayImage(image);
    this.server.runTasks();
    display.release();

    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final CraftTextDisplay entity = displays.getFirst();
    final Display.TextDisplay handle = entity.getHandle();
    final ArgumentCaptor<net.minecraft.network.chat.Component> textCaptor = ArgumentCaptor.captor(); // fqn: Component is imported as net.kyori.adventure.text.Component
    verify(handle).setText(textCaptor.capture());

    final String text = textCaptor.getValue().getString();
    assertEquals(CHARACTER, text);

    final List<Integer> colors = new ArrayList<>();
    textCaptor.getValue().visit((style, characters) -> this.recordCharacterColors(style, characters, colors), Style.EMPTY);
    final List<Integer> expectedColors = List.of(GRAY_RGB);
    assertEquals(expectedColors, colors, "the submitted image's exact color reaches the display");
    verify(entity).remove();

    final int width = image.getWidth();
    final int height = image.getHeight();
    assertEquals(1, width);
    assertEquals(1, height);

    assertThrows(NullPointerException.class, () -> display.displayImage(null));
  }

  private Optional<Object> recordCharacterColors(final Style style, final String characters, final List<Integer> colors) {
    for (int index = 0; index < characters.length(); index++) {
      final int color = style.getColor() == null ? -1 : style.getColor().getValue();
      colors.add(color);
    }

    return Optional.empty();
  }

  @Test
  void shouldResizeScoreboardImagesInPlaceShowTheSidebarAndRestoreThePreviousScoreboard() {
    final ScoreboardManager manager = this.server.getScoreboardManager();
    final FakeScoreboards scoreboards = FakeScoreboards.install(manager);
    final Scoreboard previous = mock(CraftScoreboard.class);
    FakeScoreboards.trackScoreboard(this.viewer, previous);
    final ScoreboardConfiguration configuration = this.createScoreboardConfiguration();
    final DisplayableImage display = DisplayableImage.scoreboard(configuration);
    final ImageBuffer image = Images.solid(4, 2, GRAY);

    display.displayImage(image);
    this.server.runTasks();
    final Scoreboard shown = this.viewer.getScoreboard();
    display.release();

    final Scoreboard restored = this.viewer.getScoreboard();
    final Scoreboard board = scoreboards.getNewScoreboard();
    final List<Team> teams = scoreboards.getTeams();
    final Team firstTeam = teams.getFirst();
    assertSame(board, shown);
    assertSame(previous, restored);
    verify(firstTeam).suffix(Component.text(CHARACTER, TextColor.color(GRAY_RGB)));

    final int width = image.getWidth();
    final int height = image.getHeight();
    assertEquals(1, width);
    assertEquals(1, height);

    assertThrows(NullPointerException.class, () -> display.displayImage(null));
  }

  @Test
  void shouldResizeBlockImagesInPlaceShowBlocksAndRestoreTheWorldAgain() {
    final BlockConfiguration configuration = this.createBlockConfiguration();
    final DisplayableImage display = DisplayableImage.block(configuration);
    final ImageBuffer image = Images.solid(4, 2, 0xFF000000 | BROWN);

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

    final int width = image.getWidth();
    final int height = image.getHeight();
    assertEquals(1, width);
    assertEquals(1, height);

    assertThrows(NullPointerException.class, () -> display.displayImage(null));
  }

  private enum Sizing {
    RESIZED,
    ORIGINAL,
  }
}
