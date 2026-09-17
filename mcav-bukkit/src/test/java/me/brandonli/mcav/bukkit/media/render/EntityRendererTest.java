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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.EntityConfiguration;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.FakeWorld;
import me.brandonli.mcav.media.image.ImageBuffer;
import net.minecraft.network.chat.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.entity.CraftTextDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests {@link EntityRenderer}.
 */
final class EntityRendererTest {

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID OFFLINE = UUID.fromString("00000000-0000-0000-0000-000000000002");

  private FakeServer server;
  private FakeWorld world;
  private CraftPlayer viewer;
  private Location position;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.viewer = this.server.addPlayer(VIEWER);
    this.server.injectModule();
    this.world = FakeWorld.create();
    final World configuredWorld = this.world.getWorld();
    this.position = new Location(configuredWorld, 1, 70, 3);
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private EntityConfiguration createConfiguration(final Location configuredPosition) {
    final EntityConfiguration.Builder<?> builder = EntityConfiguration.builder();
    builder.viewers(List.of(VIEWER, OFFLINE));
    builder.character("#");
    builder.position(configuredPosition);
    builder.entityWidth(2);
    builder.entityHeight(1);
    return builder.build();
  }

  @Test
  void spawnsAHiddenDisplayAndShowsItToOnlineViewers() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);

    renderer.show();

    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final CraftTextDisplay display = displays.getFirst();
    final List<Location> locations = this.world.getSpawnLocations();
    final Location spawnLocation = locations.getFirst();
    final Plugin plugin = this.server.getPlugin();
    assertEquals(this.position, spawnLocation);
    assertNotSame(this.position, spawnLocation, "the configured position is not handed out");
    verify(display).setPersistent(false);
    verify(display).setInvulnerable(true);
    verify(display).setSeeThrough(false);
    verify(display).setShadowed(false);
    verify(display).setVisibleByDefault(false);
    verify(display).setAlignment(TextDisplay.TextAlignment.CENTER);
    verify(display).setBillboard(Display.Billboard.VERTICAL);
    verify(display).setBackgroundColor(Color.BLACK);
    verify(display).setLineWidth(Integer.MAX_VALUE);
    verify(this.viewer).showEntity(plugin, display);
  }

  @Test
  void doesNothingOnATickBeforeTheDisplayWasSpawned() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Plugin plugin = this.server.getPlugin();

    assertDoesNotThrow(renderer::onTick);

    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final boolean nothingSpawned = displays.isEmpty();
    assertTrue(nothingSpawned, "a tick before show() must not spawn anything");
    verify(this.viewer, never()).showEntity(eq(plugin), any(org.bukkit.entity.Entity.class));
  }

  @Test
  void showsTheDisplayToAViewerWhoComesOnlineAfterTheSpawn() {
    // the display is spawned with setVisibleByDefault(false), so a player sees it only after an explicit
    // showEntity, and that is per session: a viewer who was offline at spawn, or who relogged, saw nothing at all
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Component text = Component.literal("frame");
    renderer.show();
    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final CraftTextDisplay display = displays.getFirst();
    final Plugin plugin = this.server.getPlugin();

    final Player latecomer = this.server.addPlayer(OFFLINE);
    verify(latecomer, never()).showEntity(eq(plugin), any(org.bukkit.entity.Entity.class));

    renderer.apply(text);
    renderer.onTick();

    verify(latecomer).showEntity(plugin, display);
    verify(this.viewer, times(1)).showEntity(plugin, display);
  }

  @Test
  void spawnsTheDisplayOnlyOnce() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);

    renderer.show();
    renderer.show();

    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final int displayCount = displays.size();
    assertEquals(1, displayCount);
  }

  @Test
  void setsTheTextOfTheServerEntityOncePerTick() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final ImageBuffer image = BlockRendererTest.solidImage(20, 10, 0x123456);

    renderer.show();
    renderer.render(image);
    this.server.runTasks();
    this.server.runTasks();

    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final CraftTextDisplay display = displays.getFirst();
    final net.minecraft.world.entity.Display.TextDisplay handle = display.getHandle();
    final ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
    verify(handle, times(1)).setText(captor.capture());
    final Component text = captor.getValue();
    final String plain = text.getString();
    final int width = image.getWidth();
    final int height = image.getHeight();
    assertEquals("##", plain);
    assertEquals(2, width);
    assertEquals(1, height);
  }

  @Test
  void skipsFramesBeforeTheDisplayIsShown() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Component text = Component.literal("frame");

    renderer.apply(text);

    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final boolean nothingSpawned = displays.isEmpty();
    assertTrue(nothingSpawned);
  }

  @Test
  void respawnsTheDisplayOnTheNextFrameOnceItsChunkIsLoadedAgain() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Component text = Component.literal("frame");
    renderer.show();
    final List<CraftTextDisplay> spawnedBefore = this.world.getSpawnedDisplays();
    final CraftTextDisplay discarded = spawnedBefore.getFirst();
    final net.minecraft.world.entity.Display.TextDisplay discardedHandle = discarded.getHandle();
    final World configuredWorld = this.world.getWorld();
    when(discarded.isValid()).thenReturn(false);
    when(configuredWorld.isChunkLoaded(0, 0)).thenReturn(true);

    renderer.apply(text);
    renderer.apply(text);

    final List<CraftTextDisplay> spawnedAfter = this.world.getSpawnedDisplays();
    final int spawnCount = spawnedAfter.size();
    final CraftTextDisplay respawned = spawnedAfter.get(1);
    final net.minecraft.world.entity.Display.TextDisplay respawnedHandle = respawned.getHandle();
    final Plugin plugin = this.server.getPlugin();
    assertEquals(2, spawnCount, "the display is respawned once and then reused");
    verify(respawnedHandle, times(2)).setText(text);
    verify(discardedHandle, never()).setText(any(Component.class));
    verify(this.viewer).showEntity(plugin, respawned);
  }

  @Test
  void waitsForTheChunkToLoadBeforeRespawningTheDisplay() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Component text = Component.literal("frame");
    renderer.show();
    final List<CraftTextDisplay> spawnedBefore = this.world.getSpawnedDisplays();
    final CraftTextDisplay discarded = spawnedBefore.getFirst();
    final net.minecraft.world.entity.Display.TextDisplay handle = discarded.getHandle();
    when(discarded.isValid()).thenReturn(false);

    renderer.apply(text);

    final List<CraftTextDisplay> spawnedAfter = this.world.getSpawnedDisplays();
    final int spawnCount = spawnedAfter.size();
    final World configuredWorld = this.world.getWorld();
    assertEquals(1, spawnCount, "spawning would load the chunk the server just unloaded");
    verify(configuredWorld).isChunkLoaded(0, 0);
    verify(handle, never()).setText(any(Component.class));
  }

  @Test
  void stopsRespawningOnceThePositionHasNoWorld() {
    final Location movable = this.position.clone();
    final EntityConfiguration configuration = this.createConfiguration(movable);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Component text = Component.literal("frame");
    renderer.show();
    final List<CraftTextDisplay> spawnedBefore = this.world.getSpawnedDisplays();
    final CraftTextDisplay discarded = spawnedBefore.getFirst();
    when(discarded.isValid()).thenReturn(false);
    movable.setWorld(null);

    renderer.apply(text);

    final List<CraftTextDisplay> spawnedAfter = this.world.getSpawnedDisplays();
    final int spawnCount = spawnedAfter.size();
    assertEquals(1, spawnCount);
  }

  @Test
  void removesTheDisplayWhenHidden() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Component lateText = Component.literal("late");

    renderer.show();
    renderer.hide();
    renderer.hide();
    renderer.apply(lateText);

    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final CraftTextDisplay display = displays.getFirst();
    final net.minecraft.world.entity.Display.TextDisplay handle = display.getHandle();
    final int tasks = this.server.getScheduledTaskCount();
    verify(display, times(1)).remove();
    verify(handle, never()).setText(any(Component.class));
    assertEquals(0, tasks);
  }

  @Test
  void needsAPositionInAWorld() {
    final Location movable = this.position.clone();
    final EntityConfiguration configuration = this.createConfiguration(movable);
    movable.setWorld(null);
    final EntityRenderer renderer = new EntityRenderer(configuration);

    assertThrows(IllegalStateException.class, renderer::show);
  }

  @Test
  void rejectsMissingArguments() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);

    assertThrows(NullPointerException.class, () -> new EntityRenderer(null));
    assertThrows(NullPointerException.class, () -> renderer.render(null));
  }
}
