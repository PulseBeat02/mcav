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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import me.brandonli.mcav.bukkit.media.config.EntityConfiguration;
import me.brandonli.mcav.bukkit.testing.FakeServer;
import me.brandonli.mcav.bukkit.testing.FakeWorld;
import me.brandonli.mcav.media.image.ImageBuffer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.entity.CraftTextDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
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
  private List<UUID> viewers;
  private FakeWorld world;
  private CraftPlayer viewer;
  private Location position;

  @BeforeEach
  void startServer() throws ReflectiveOperationException {
    this.server = FakeServer.start();
    this.viewers = new CopyOnWriteArrayList<>(List.of(VIEWER, OFFLINE));
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
    builder.viewers(this.viewers);
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
    verify(this.viewer, never()).showEntity(eq(plugin), any(Entity.class));
  }

  @Test
  void showsTheDisplayToAViewerWhoComesOnlineAfterTheSpawn() {
    // the display is spawned with setVisibleByDefault(false), so a player sees it only after an explicit
    // showEntity, and that is per session: a viewer who was offline at spawn, or who relogged, is shown it again
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Component text = Component.literal("frame");
    renderer.show();
    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final CraftTextDisplay display = displays.getFirst();
    final Plugin plugin = this.server.getPlugin();

    final Player latecomer = this.server.addPlayer(OFFLINE);
    verify(latecomer, never()).showEntity(eq(plugin), any(Entity.class));

    renderer.apply(text);
    renderer.onTick();

    verify(latecomer).showEntity(plugin, display);
    verify(this.viewer, times(1)).showEntity(plugin, display);
  }

  @Test
  void forgetsAnOfflineVisibilityGrantAndShowsTheEntityToTheRejoinedSession() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    renderer.show();
    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final CraftTextDisplay display = displays.getFirst();
    final Plugin plugin = this.server.getPlugin();
    this.server.removePlayer(VIEWER);
    renderer.onTick();
    renderer.onTick();
    verify(this.viewer, never()).hideEntity(plugin, display);

    final CraftPlayer rejoined = this.server.addPlayer(VIEWER);
    renderer.onTick();
    renderer.onTick();
    verify(rejoined, times(1)).showEntity(plugin, display);
    final List<CraftTextDisplay> afterRejoin = this.world.getSpawnedDisplays();
    final int count = afterRejoin.size();
    assertEquals(1, count, "a new player session needs a visibility grant, not a replacement entity");
    renderer.hide();
    verify(display, times(1)).remove();
  }

  @Test
  void grantsVisibilityToTheReplacementEntityAfterAViewerReturns() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    renderer.show();
    renderer.hide();
    this.viewers.clear();
    renderer.show();
    this.viewers.add(VIEWER);
    renderer.onTick();
    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final CraftTextDisplay replacement = displays.getLast();
    final Plugin plugin = this.server.getPlugin();
    verify(this.viewer).showEntity(plugin, replacement);
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
    final int[] sourcePixels = new int[20 * 10];
    for (int row = 0; row < 10; row++) {
      for (int column = 0; column < 20; column++) {
        sourcePixels[row * 20 + column] = column < 10 ? 0xFF123456 : 0xFFABCDEF;
      }
    }
    final ImageBuffer image = ImageBuffer.buffer(sourcePixels, 20, 10);

    renderer.show();
    renderer.render(image);
    this.server.runTasks();
    this.server.runTasks();

    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final CraftTextDisplay display = displays.getFirst();
    final net.minecraft.world.entity.Display.TextDisplay handle = display.getHandle(); // fqn: Display is imported as org.bukkit.entity.Display
    final ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
    verify(handle, times(1)).setText(captor.capture());
    final Component text = captor.getValue();
    final String plain = text.getString();
    final int width = image.getWidth();
    final int height = image.getHeight();
    assertEquals("##", plain);
    final List<Integer> colors = new ArrayList<>();
    text.visit((style, characters) -> {
      for (int index = 0; index < characters.length(); index++) {
        colors.add(style.getColor() == null ? -1 : style.getColor().getValue());
      }
      return Optional.empty();
    }, Style.EMPTY);
    assertEquals(List.of(0x123456, 0xABCDEF), colors, "each rendered pixel retains its exact RGB");
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
    final net.minecraft.world.entity.Display.TextDisplay discardedHandle = discarded.getHandle(); // fqn: Display is imported as org.bukkit.entity.Display
    final World configuredWorld = this.world.getWorld();
    when(discarded.isValid()).thenReturn(false);
    when(configuredWorld.isChunkLoaded(0, 0)).thenReturn(true);

    renderer.apply(text);
    renderer.apply(text);

    final List<CraftTextDisplay> spawnedAfter = this.world.getSpawnedDisplays();
    final int spawnCount = spawnedAfter.size();
    final CraftTextDisplay respawned = spawnedAfter.get(1);
    final net.minecraft.world.entity.Display.TextDisplay respawnedHandle = respawned.getHandle(); // fqn: Display is imported as org.bukkit.entity.Display
    final Plugin plugin = this.server.getPlugin();
    assertEquals(2, spawnCount, "the display is respawned once and then reused");
    verify(respawnedHandle, times(2)).setText(text);
    verify(discardedHandle, never()).setText(any(Component.class));
    verify(this.viewer).showEntity(plugin, respawned);
  }

  @Test
  void aDisplayThatComesBackBeforeTheFirstFrameIsGivenNoText() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    renderer.show();
    final CraftTextDisplay discarded = this.world.getSpawnedDisplays().getFirst();
    when(discarded.isValid()).thenReturn(false);
    when(this.world.getWorld().isChunkLoaded(0, 0)).thenReturn(true);
    renderer.onTick();

    final List<CraftTextDisplay> spawned = this.world.getSpawnedDisplays();
    assertEquals(2, spawned.size(), "the display comes back");
    final net.minecraft.world.entity.Display.TextDisplay handle = spawned.get(1).getHandle(); // fqn: Display is imported as org.bukkit.entity.Display
    verify(handle, never()).setText(any());
    renderer.hide();
  }

  @Test
  void aDisplayReturningToALoadedChunkUsesTheNewestSubmittedText() {
    final EntityRenderer renderer = new EntityRenderer(this.createConfiguration(this.position));
    final Component beforeUnload = Component.literal("before");
    final Component whileUnloaded = Component.literal("latest");
    renderer.show();
    renderer.apply(beforeUnload);
    final CraftTextDisplay discarded = this.world.getSpawnedDisplays().getFirst();
    final World configuredWorld = this.world.getWorld();
    when(discarded.isValid()).thenReturn(false);
    when(configuredWorld.isChunkLoaded(0, 0)).thenReturn(false);
    renderer.apply(whileUnloaded);
    renderer.onTick();
    assertEquals(1, this.world.getSpawnedDisplays().size());
    when(configuredWorld.isChunkLoaded(0, 0)).thenReturn(true);
    renderer.onTick();
    final CraftTextDisplay replacement = this.world.getSpawnedDisplays().get(1);
    final net.minecraft.world.entity.Display.TextDisplay handle = replacement.getHandle(); // fqn: Display is imported as org.bukkit.entity.Display
    verify(handle).setText(whileUnloaded);
    verify(handle, never()).setText(beforeUnload);
    renderer.hide();
  }

  @Test
  void aStillImageComesBackWithItsTextOnceItsChunkIsLoadedAgain() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Component text = Component.literal("still");
    renderer.show();
    renderer.apply(text);
    final CraftTextDisplay discarded = this.world.getSpawnedDisplays().getFirst();
    final World configuredWorld = this.world.getWorld();
    // the chunk unloads with every viewer gone, and no frame follows: a still image
    when(discarded.isValid()).thenReturn(false);
    renderer.onTick();
    assertEquals(1, this.world.getSpawnedDisplays().size(), "not while the chunk is unloaded");
    when(configuredWorld.isChunkLoaded(0, 0)).thenReturn(true);
    renderer.onTick();
    renderer.onTick();

    final List<CraftTextDisplay> spawned = this.world.getSpawnedDisplays();
    assertEquals(2, spawned.size(), "the display comes back once");
    final CraftTextDisplay respawned = spawned.get(1);
    final net.minecraft.world.entity.Display.TextDisplay handle = respawned.getHandle(); // fqn: Display is imported as org.bukkit.entity.Display
    verify(handle).setText(text);
    verify(this.viewer).showEntity(this.server.getPlugin(), respawned);
    // once hidden, nothing comes back
    renderer.hide();
    when(respawned.isValid()).thenReturn(false);
    renderer.onTick();
    assertEquals(2, this.world.getSpawnedDisplays().size());
  }

  @Test
  void aViewerWhoseGrantPaperTookBackIsShownTheDisplayAgain() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Plugin plugin = this.server.getPlugin();
    renderer.show();
    renderer.apply(Component.literal("still"));
    final CraftTextDisplay discarded = this.world.getSpawnedDisplays().getFirst();
    when(discarded.isValid()).thenReturn(false);
    when(this.world.getWorld().isChunkLoaded(0, 0)).thenReturn(true);
    renderer.onTick();
    final CraftTextDisplay respawned = this.world.getSpawnedDisplays().get(1);
    assertTrue(this.viewer.canSee(respawned));
    // respawned the moment its chunk loaded again, the display stops being tracked before the viewer saw it, and
    // Paper takes the viewer's grant back
    this.server.endTracking(respawned);
    renderer.onTick();
    assertTrue(this.viewer.canSee(respawned), "the viewer is shown the display again");
    verify(this.viewer, times(2)).showEntity(plugin, respawned);
    renderer.onTick();
    verify(this.viewer, times(2)).showEntity(plugin, respawned);
    renderer.hide();
  }

  @Test
  void waitsForTheChunkToLoadBeforeRespawningTheDisplay() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Component text = Component.literal("frame");
    renderer.show();
    final List<CraftTextDisplay> spawnedBefore = this.world.getSpawnedDisplays();
    final CraftTextDisplay discarded = spawnedBefore.getFirst();
    final net.minecraft.world.entity.Display.TextDisplay handle = discarded.getHandle(); // fqn: Display is imported as org.bukkit.entity.Display
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
  void asksAboutTheChunkThatHoldsANegativeBlockPosition() {
    // the shared fixture sits at x = 1, z = 3, whose chunk is (0, 0) under every plausible arithmetic, so it cannot
    // tell a floor from a truncation. A negative x can: -17 >> 4 is -2, while -17 / 16 truncates toward zero and
    // gives -1, which is the chunk next door.
    final World configuredWorld = this.world.getWorld();
    final Location negative = new Location(configuredWorld, -17, 70, 35);
    final EntityConfiguration configuration = this.createConfiguration(negative);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Component text = Component.literal("frame");
    renderer.show();
    final List<CraftTextDisplay> spawnedBefore = this.world.getSpawnedDisplays();
    final CraftTextDisplay discarded = spawnedBefore.getFirst();
    when(discarded.isValid()).thenReturn(false);

    renderer.apply(text);

    verify(configuredWorld).isChunkLoaded(-2, 2);
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
    final net.minecraft.world.entity.Display.TextDisplay handle = display.getHandle(); // fqn: Display is imported as org.bukkit.entity.Display
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
  void hidesTheDisplayFromRemovedViewersAndShowsItAgainOnReaddition() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    renderer.show();
    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final CraftTextDisplay display = displays.getFirst();
    final Plugin plugin = this.server.getPlugin();
    this.viewers.remove(VIEWER);
    renderer.onTick();
    renderer.onTick();
    verify(this.viewer, times(1)).hideEntity(plugin, display);
    this.viewers.add(VIEWER);
    renderer.onTick();
    renderer.onTick();
    verify(this.viewer, times(2)).showEntity(plugin, display);
  }

  @Test
  void queuedShowCannotSpawnAnEntityAfterMainThreadHide() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    this.server.setPrimaryThread(false);
    renderer.show();
    this.server.setPrimaryThread(true);
    renderer.hide();
    this.server.runTasks();
    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    assertEquals(0, displays.size());
    final int tasks = this.server.getScheduledTaskCount();
    assertEquals(0, tasks);
  }

  @Test
  void queuedHideCannotRemoveAnEntityRequestedAgainOnMain() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    renderer.show();
    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final CraftTextDisplay display = displays.getFirst();
    this.server.setPrimaryThread(false);
    renderer.hide();
    this.server.setPrimaryThread(true);
    renderer.show();
    this.server.runTasks();
    verify(display, never()).remove();
    final Component text = Component.literal("still rendering");
    renderer.apply(text);
    final net.minecraft.world.entity.Display.TextDisplay handle = display.getHandle(); // fqn: Display is imported as org.bukkit.entity.Display
    verify(handle).setText(text);
    final int tasks = this.server.getScheduledTaskCount();
    assertEquals(1, tasks);
  }

  @Test
  void rejectsMissingArguments() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);

    assertThrows(NullPointerException.class, () -> new EntityRenderer(null));
    assertThrows(NullPointerException.class, () -> renderer.render(null));
  }
}
