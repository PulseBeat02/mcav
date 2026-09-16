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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.papermc.paper.math.Position;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import me.brandonli.mcav.bukkit.media.config.BlockConfiguration;
import me.brandonli.mcav.bukkit.media.lookup.BlockPaletteLookup;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.FakeWorld;
import me.brandonli.mcav.media.image.ImageBuffer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests {@link BlockRenderer}.
 */
final class BlockRendererTest {

  static final int BROWN = (102 << 16) | (76 << 8) | 51;
  static final int MAGENTA = (149 << 16) | (88 << 8) | 108;

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID OFFLINE = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID LATE = UUID.fromString("00000000-0000-0000-0000-000000000003");

  private FakeServer server;
  private FakeWorld world;
  private CraftPlayer viewer;
  private List<UUID> viewers;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.viewer = this.server.addPlayer(VIEWER);
    this.server.injectModule();
    this.world = FakeWorld.create();
    final List<UUID> initialViewers = List.of(VIEWER, OFFLINE);
    this.viewers = new CopyOnWriteArrayList<>(initialViewers);
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private BlockConfiguration createConfiguration() {
    final World configuredWorld = this.world.getWorld();
    final Location position = new Location(configuredWorld, 10.7, 64.2, -5.3);
    final BlockConfiguration.Builder<?> builder = BlockConfiguration.builder();
    builder.viewers(this.viewers);
    builder.position(position);
    builder.blockWidth(3);
    builder.blockHeight(2);
    return builder.build();
  }

  static ImageBuffer solidImage(final int width, final int height, final int rgb) {
    final int[] pixels = new int[width * height];
    Arrays.fill(pixels, 0xFF000000 | rgb);
    return ImageBuffer.buffer(pixels, width, height);
  }

  private static ImageBuffer imageWithOneMagentaBlock() {
    final int[] pixels = new int[6];
    Arrays.fill(pixels, 0xFF000000 | BROWN);
    pixels[4] = 0xFF000000 | MAGENTA;
    return ImageBuffer.buffer(pixels, 3, 2);
  }

  private Map<Position, BlockData> expectAll(final BlockData block) {
    final Map<Position, BlockData> expected = new HashMap<>();
    for (int x = 9; x <= 11; x++) {
      for (int y = 64; y <= 65; y++) {
        final Position position = Position.block(x, y, -6);
        expected.put(position, block);
      }
    }
    return expected;
  }

  private Map<Position, BlockData> expectOriginal() {
    final Map<Position, BlockData> expected = new HashMap<>();
    for (int x = 9; x <= 11; x++) {
      for (int y = 64; y <= 65; y++) {
        final Position position = Position.block(x, y, -6);
        final BlockData original = this.world.getOriginalBlock(x, y, -6);
        expected.put(position, original);
      }
    }
    return expected;
  }

  private static List<Map<? extends Position, BlockData>> captureChanges(final CraftPlayer player, final int expectedCalls) {
    final ArgumentCaptor<Map<? extends Position, BlockData>> captor = ArgumentCaptor.captor();
    verify(player, times(expectedCalls)).sendMultiBlockChange(captor.capture());
    return captor.getAllValues();
  }

  private void runTicksUntilTheFullResendIsDue() {
    for (int tick = 1; tick < BlockRenderer.FULL_RESEND_INTERVAL_TICKS; tick++) {
      this.server.runTasks();
    }
  }

  @Test
  void showsFramesAsAWallCenteredOnThePositionAndGrowingUpward() {
    final BlockConfiguration configuration = this.createConfiguration();
    final BlockRenderer renderer = new BlockRenderer(configuration);
    final ImageBuffer image = solidImage(3, 2, BROWN);

    renderer.show();
    renderer.render(image);
    this.server.runTasks();

    final List<Map<? extends Position, BlockData>> changes = captureChanges(this.viewer, 1);
    final BlockData brown = BlockPaletteLookup.getBlockData(BROWN);
    final Map<Position, BlockData> expected = this.expectAll(brown);
    final Map<? extends Position, BlockData> sent = changes.getFirst();
    assertEquals(expected, sent);
  }

  @Test
  void placesTheFirstImageRowAtTheTop() {
    final BlockConfiguration configuration = this.createConfiguration();
    final BlockRenderer renderer = new BlockRenderer(configuration);
    final int[] pixels = { BROWN, BROWN, BROWN, MAGENTA, BROWN, BROWN };
    for (int index = 0; index < pixels.length; index++) {
      pixels[index] |= 0xFF000000;
    }
    final ImageBuffer image = ImageBuffer.buffer(pixels, 3, 2);

    renderer.show();
    renderer.render(image);
    this.server.runTasks();

    final List<Map<? extends Position, BlockData>> changes = captureChanges(this.viewer, 1);
    final Map<? extends Position, BlockData> sent = changes.getFirst();
    final Position bottomLeft = Position.block(9, 64, -6);
    final Position topLeft = Position.block(9, 65, -6);
    final BlockData bottomLeftBlock = sent.get(bottomLeft);
    final BlockData topLeftBlock = sent.get(topLeft);
    final BlockData magenta = BlockPaletteLookup.getBlockData(MAGENTA);
    final BlockData brown = BlockPaletteLookup.getBlockData(BROWN);
    assertEquals(magenta, bottomLeftBlock);
    assertEquals(brown, topLeftBlock);
  }

  @Test
  void dithersColorsThatNoBlockHasToThePaletteOfTheWall() {
    final BlockConfiguration configuration = this.createConfiguration();
    final BlockRenderer renderer = new BlockRenderer(configuration);
    final int unmappedColor = (1 << 16) | (2 << 8) | 3;
    final BlockData air = BlockPaletteLookup.getBlockData(unmappedColor);
    final Material airMaterial = air.getMaterial();
    assertEquals(Material.AIR, airMaterial, "the color of this test is not a color of the block palette");

    final ImageBuffer image = solidImage(3, 2, unmappedColor);
    renderer.show();
    renderer.render(image);
    this.server.runTasks();

    final List<Map<? extends Position, BlockData>> changes = captureChanges(this.viewer, 1);
    final Map<? extends Position, BlockData> sent = changes.getFirst();
    final Position bottomLeft = Position.block(9, 64, -6);
    final BlockData block = sent.get(bottomLeft);
    assertNotEquals(air, block, "a color no block has is dithered to the nearest color of the palette");
  }

  @Test
  void sendsOnlyTheBlocksThatChangedToViewersWhoAlreadySeeTheWall() {
    final BlockConfiguration configuration = this.createConfiguration();
    final BlockRenderer renderer = new BlockRenderer(configuration);
    final ImageBuffer first = solidImage(3, 2, BROWN);
    final ImageBuffer identical = solidImage(3, 2, BROWN);
    final ImageBuffer changed = imageWithOneMagentaBlock();

    renderer.show();
    renderer.render(first);
    this.server.runTasks();
    renderer.render(identical);
    this.server.runTasks();
    renderer.render(changed);
    this.server.runTasks();

    final List<Map<? extends Position, BlockData>> changes = captureChanges(this.viewer, 2);
    final Map<? extends Position, BlockData> delta = changes.get(1);
    final BlockData magenta = BlockPaletteLookup.getBlockData(MAGENTA);
    final Position changedPosition = Position.block(10, 64, -6);
    final Map<Position, BlockData> expectedDelta = Map.of(changedPosition, magenta);
    assertEquals(expectedDelta, delta, "an identical frame sends nothing, and a delta only its block");
  }

  @Test
  void sendsTheCompleteWallToViewersWhoStartWatchingDuringPlayback() {
    final BlockConfiguration configuration = this.createConfiguration();
    final BlockRenderer renderer = new BlockRenderer(configuration);
    final ImageBuffer first = solidImage(3, 2, BROWN);
    final ImageBuffer changed = imageWithOneMagentaBlock();

    renderer.show();
    renderer.render(first);
    this.server.runTasks();
    final CraftPlayer latePlayer = this.server.addPlayer(LATE);
    this.viewers.add(LATE);
    renderer.render(changed);
    this.server.runTasks();

    final List<Map<? extends Position, BlockData>> lateChanges = captureChanges(latePlayer, 1);
    final List<Map<? extends Position, BlockData>> viewerChanges = captureChanges(this.viewer, 2);
    final Map<? extends Position, BlockData> lateWall = lateChanges.getFirst();
    final Map<? extends Position, BlockData> viewerDelta = viewerChanges.get(1);
    final BlockData brown = BlockPaletteLookup.getBlockData(BROWN);
    final BlockData magenta = BlockPaletteLookup.getBlockData(MAGENTA);
    final Position changedPosition = Position.block(10, 64, -6);
    final Map<Position, BlockData> expectedWall = this.expectAll(brown);
    expectedWall.put(changedPosition, magenta);
    final Map<Position, BlockData> expectedDelta = Map.of(changedPosition, magenta);
    assertEquals(expectedWall, lateWall, "the new viewer sees the whole current picture, not only the delta");
    assertEquals(expectedDelta, viewerDelta);
  }

  @Test
  void sendsTheCompleteWallToEveryViewerOnceEveryInterval() {
    final BlockConfiguration configuration = this.createConfiguration();
    final BlockRenderer renderer = new BlockRenderer(configuration);
    final ImageBuffer image = solidImage(3, 2, BROWN);

    renderer.show();
    renderer.render(image);
    this.runTicksUntilTheFullResendIsDue();
    verify(this.viewer, times(1)).sendMultiBlockChange(anyMap());
    this.server.runTasks();

    final List<Map<? extends Position, BlockData>> changes = captureChanges(this.viewer, 2);
    final Map<? extends Position, BlockData> resent = changes.get(1);
    final BlockData brown = BlockPaletteLookup.getBlockData(BROWN);
    final Map<Position, BlockData> expected = this.expectAll(brown);
    assertEquals(20, BlockRenderer.FULL_RESEND_INTERVAL_TICKS);
    assertEquals(expected, resent, "a static picture is sent again, so reloaded chunks show the wall again");
  }

  @Test
  void sendsTheCompleteWallAgainToViewersWhoRejoin() {
    final BlockConfiguration configuration = this.createConfiguration();
    final BlockRenderer renderer = new BlockRenderer(configuration);
    final ImageBuffer first = solidImage(3, 2, BROWN);
    final ImageBuffer changed = imageWithOneMagentaBlock();

    renderer.show();
    renderer.render(first);
    this.server.runTasks();
    this.server.removePlayer(VIEWER);
    renderer.render(changed);
    this.server.runTasks();
    final CraftPlayer rejoined = this.server.addPlayer(VIEWER);
    this.server.runTasks();

    final List<Map<? extends Position, BlockData>> rejoinedChanges = captureChanges(rejoined, 1);
    final Map<? extends Position, BlockData> wall = rejoinedChanges.getFirst();
    final BlockData brown = BlockPaletteLookup.getBlockData(BROWN);
    final BlockData magenta = BlockPaletteLookup.getBlockData(MAGENTA);
    final Position changedPosition = Position.block(10, 64, -6);
    final Map<Position, BlockData> expectedWall = this.expectAll(brown);
    expectedWall.put(changedPosition, magenta);
    verify(this.viewer, times(1)).sendMultiBlockChange(anyMap());
    assertEquals(expectedWall, wall);
  }

  @Test
  void restoresTheOriginalBlocksForViewersWhoAreRemovedDuringPlayback() {
    final BlockConfiguration configuration = this.createConfiguration();
    final BlockRenderer renderer = new BlockRenderer(configuration);
    final ImageBuffer image = solidImage(3, 2, BROWN);
    final ImageBuffer changed = imageWithOneMagentaBlock();

    renderer.show();
    renderer.render(image);
    this.server.runTasks();
    this.viewers.remove(VIEWER);
    this.server.runTasks();
    renderer.render(changed);
    this.server.runTasks();

    final List<Map<? extends Position, BlockData>> changes = captureChanges(this.viewer, 2);
    final Map<? extends Position, BlockData> restored = changes.get(1);
    final Map<Position, BlockData> expected = this.expectOriginal();
    assertEquals(expected, restored, "the removed viewer sees the world again and receives no more frames");
  }

  @Test
  void waitsForTheFirstFrameBeforeSendingAnything() {
    final BlockConfiguration configuration = this.createConfiguration();
    final BlockRenderer renderer = new BlockRenderer(configuration);

    renderer.show();
    this.server.runTasks();

    verify(this.viewer, never()).sendMultiBlockChange(anyMap());
  }

  @Test
  void resizesImagesToTheWall() {
    final BlockConfiguration configuration = this.createConfiguration();
    final BlockRenderer renderer = new BlockRenderer(configuration);
    final ImageBuffer image = solidImage(30, 20, BROWN);

    renderer.render(image);

    final int width = image.getWidth();
    final int height = image.getHeight();
    assertEquals(3, width);
    assertEquals(2, height);
  }

  @Test
  void restoresTheOriginalBlocksWhenHidden() {
    final BlockConfiguration configuration = this.createConfiguration();
    final BlockRenderer renderer = new BlockRenderer(configuration);
    final ImageBuffer image = solidImage(3, 2, BROWN);

    renderer.show();
    renderer.render(image);
    this.server.runTasks();
    renderer.hide();
    renderer.hide();

    final int tasks = this.server.getScheduledTaskCount();
    final List<Map<? extends Position, BlockData>> changes = captureChanges(this.viewer, 2);
    final Map<? extends Position, BlockData> restored = changes.get(1);
    final Map<Position, BlockData> expected = this.expectOriginal();
    assertEquals(expected, restored);
    assertEquals(0, tasks, "rendering stopped");
  }

  @Test
  void startsOverWithEveryViewerWhenShownAgain() {
    final BlockConfiguration configuration = this.createConfiguration();
    final BlockRenderer renderer = new BlockRenderer(configuration);
    final ImageBuffer first = solidImage(3, 2, BROWN);
    final ImageBuffer second = solidImage(3, 2, BROWN);

    renderer.show();
    renderer.render(first);
    this.server.runTasks();
    renderer.hide();
    renderer.show();
    renderer.render(second);
    this.server.runTasks();

    final List<Map<? extends Position, BlockData>> changes = captureChanges(this.viewer, 3);
    final Map<? extends Position, BlockData> wall = changes.get(2);
    final BlockData brown = BlockPaletteLookup.getBlockData(BROWN);
    final Map<Position, BlockData> expected = this.expectAll(brown);
    assertEquals(expected, wall);
  }

  @Test
  void capturesTheOriginalBlocksOnlyOnce() {
    final BlockConfiguration configuration = this.createConfiguration();
    final BlockRenderer renderer = new BlockRenderer(configuration);

    renderer.show();
    renderer.show();

    final int tasks = this.server.getScheduledTaskCount();
    final World configuredWorld = this.world.getWorld();
    verify(configuredWorld, times(6)).getBlockData(anyInt(), anyInt(), anyInt());
    assertEquals(1, tasks);
  }

  @Test
  void capturesTheBlocksOnTheMainThreadWhenShownFromAnotherThread() {
    final BlockConfiguration configuration = this.createConfiguration();
    final BlockRenderer renderer = new BlockRenderer(configuration);
    final World configuredWorld = this.world.getWorld();

    this.server.setPrimaryThread(false);
    renderer.show();
    verify(configuredWorld, never()).getBlockData(anyInt(), anyInt(), anyInt());
    this.server.runTasks();

    verify(configuredWorld, times(6)).getBlockData(anyInt(), anyInt(), anyInt());
  }

  @Test
  void ignoresFramesAndTicksWhileNotShown() {
    final BlockConfiguration configuration = this.createConfiguration();
    final BlockRenderer renderer = new BlockRenderer(configuration);
    final BlockData brown = BlockPaletteLookup.getBlockData(BROWN);
    final BlockData[] blocks = { brown, brown, brown, brown, brown, brown };

    renderer.apply(blocks);
    renderer.onTick();
    renderer.hide();

    verify(this.viewer, never()).sendMultiBlockChange(anyMap());
  }

  @Test
  void needsAPositionInAWorld() {
    final BlockConfiguration configuration = this.createConfiguration();
    final Location position = configuration.getPosition();
    position.setWorld(null);
    final BlockRenderer renderer = new BlockRenderer(configuration);

    assertThrows(IllegalStateException.class, renderer::show);
  }

  @Test
  void rejectsMissingArguments() {
    final BlockConfiguration configuration = this.createConfiguration();
    final BlockRenderer renderer = new BlockRenderer(configuration);

    assertThrows(NullPointerException.class, () -> new BlockRenderer(null));
    assertThrows(NullPointerException.class, () -> renderer.render(null));
  }
}
