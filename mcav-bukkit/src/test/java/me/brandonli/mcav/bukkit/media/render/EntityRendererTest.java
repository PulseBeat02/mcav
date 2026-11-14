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
import static org.mockito.Mockito.doReturn;
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
import me.brandonli.mcav.bukkit.media.config.EntityConfigurationBuilder;
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

final class EntityRendererTest {

  private static final int POSITION_HEIGHT = 70;

  private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID OFFLINE = UUID.fromString("00000000-0000-0000-0000-000000000002");

  private static final String FRAME_TEXT = "frame";
  private static final String STILL_TEXT = "still";

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
    this.position = new Location(configuredWorld, 1, POSITION_HEIGHT, 3);
  }

  @AfterEach
  void stopServer() {
    this.server.close();
  }

  private EntityConfiguration createConfiguration(final Location configuredPosition) {
    final EntityConfigurationBuilder builder = EntityConfiguration.builder();
    builder.viewers(this.viewers);
    builder.character("#");
    builder.position(configuredPosition);
    builder.entityWidth(2);
    builder.entityHeight(1);
    return builder.build();
  }

  @Test
  void shouldSpawnAHiddenDisplayAndShowItToOnlineViewers() {
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
  void shouldDoNothingOnATickBeforeTheDisplayWasSpawned() {
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
  void shouldShowTheDisplayToAViewerWhoComesOnlineAfterTheSpawn() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Component text = Component.literal(FRAME_TEXT);
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
  void shouldForgetAnOfflineVisibilityGrantAndShowTheEntityToTheRejoinedSession() {
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
  void shouldGrantVisibilityToTheReplacementEntityAfterAViewerReturns() {
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
  void shouldSpawnTheDisplayOnlyOnce() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);

    renderer.show();
    renderer.show();

    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final int displayCount = displays.size();
    assertEquals(1, displayCount);
  }

  @Test
  void shouldSetTheTextOfTheServerEntityOncePerTick() {
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
    text.visit((style, characters) -> this.recordCharacterColors(style, characters, colors), Style.EMPTY);
    final List<Integer> expectedColors = List.of(0x123456, 0xABCDEF);
    assertEquals(expectedColors, colors, "each rendered pixel retains its exact RGB");
    assertEquals(2, width);
    assertEquals(1, height);
  }

  private Optional<Object> recordCharacterColors(final Style style, final String characters, final List<Integer> colors) {
    for (int index = 0; index < characters.length(); index++) {
      final int color = style.getColor() == null ? -1 : style.getColor().getValue();
      colors.add(color);
    }

    return Optional.empty();
  }

  @Test
  void shouldSkipFramesBeforeTheDisplayIsShown() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Component text = Component.literal(FRAME_TEXT);

    renderer.apply(text);

    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final boolean nothingSpawned = displays.isEmpty();
    assertTrue(nothingSpawned);
  }

  @Test
  void shouldRespawnTheDisplayOnTheNextFrameOnceItsChunkIsLoadedAgain() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Component text = Component.literal(FRAME_TEXT);
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
  void shouldGiveNoTextToADisplayThatComesBackBeforeTheFirstFrame() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    renderer.show();
    final CraftTextDisplay discarded = this.world.getSpawnedDisplays().getFirst();
    when(discarded.isValid()).thenReturn(false);
    when(this.world.getWorld().isChunkLoaded(0, 0)).thenReturn(true);
    renderer.onTick();

    final List<CraftTextDisplay> spawned = this.world.getSpawnedDisplays();
    final int spawnedCount = spawned.size();
    assertEquals(2, spawnedCount, "the display comes back");

    final net.minecraft.world.entity.Display.TextDisplay handle = spawned.get(1).getHandle(); // fqn: Display is imported as org.bukkit.entity.Display
    verify(handle, never()).setText(any());

    renderer.hide();
  }

  @Test
  void shouldUseTheNewestSubmittedTextWhenADisplayReturnsToALoadedChunk() {
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
    final int displayCountWhileUnloaded = this.world.getSpawnedDisplays().size();
    assertEquals(1, displayCountWhileUnloaded);

    when(configuredWorld.isChunkLoaded(0, 0)).thenReturn(true);
    renderer.onTick();
    final CraftTextDisplay replacement = this.world.getSpawnedDisplays().get(1);
    final net.minecraft.world.entity.Display.TextDisplay handle = replacement.getHandle(); // fqn: Display is imported as org.bukkit.entity.Display
    verify(handle).setText(whileUnloaded);
    verify(handle, never()).setText(beforeUnload);

    renderer.hide();
  }

  @Test
  void shouldBringAStillImageBackWithItsTextOnceItsChunkIsLoadedAgain() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Component text = Component.literal(STILL_TEXT);
    renderer.show();
    renderer.apply(text);
    final CraftTextDisplay discarded = this.world.getSpawnedDisplays().getFirst();
    final World configuredWorld = this.world.getWorld();
    when(discarded.isValid()).thenReturn(false);
    renderer.onTick();
    final int displayCountWhileUnloaded = this.world.getSpawnedDisplays().size();
    assertEquals(1, displayCountWhileUnloaded, "not while the chunk is unloaded");

    when(configuredWorld.isChunkLoaded(0, 0)).thenReturn(true);
    renderer.onTick();
    renderer.onTick();

    final List<CraftTextDisplay> spawned = this.world.getSpawnedDisplays();
    final int spawnedCount = spawned.size();
    assertEquals(2, spawnedCount, "the display comes back once");

    final CraftTextDisplay respawned = spawned.get(1);
    final net.minecraft.world.entity.Display.TextDisplay handle = respawned.getHandle(); // fqn: Display is imported as org.bukkit.entity.Display
    verify(handle).setText(text);
    verify(this.viewer).showEntity(this.server.getPlugin(), respawned);

    renderer.hide();
    when(respawned.isValid()).thenReturn(false);
    renderer.onTick();
    final int displayCountAfterHide = this.world.getSpawnedDisplays().size();
    assertEquals(2, displayCountAfterHide);
  }

  @Test
  void shouldShowTheDisplayAgainToAViewerWhoseGrantPaperTookBack() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Plugin plugin = this.server.getPlugin();
    renderer.show();
    renderer.apply(Component.literal(STILL_TEXT));
    final CraftTextDisplay discarded = this.world.getSpawnedDisplays().getFirst();
    when(discarded.isValid()).thenReturn(false);
    when(this.world.getWorld().isChunkLoaded(0, 0)).thenReturn(true);
    renderer.onTick();
    final CraftTextDisplay respawned = this.world.getSpawnedDisplays().get(1);
    final boolean visible = this.viewer.canSee(respawned);
    assertTrue(visible);

    this.server.endTracking(respawned);
    renderer.onTick();
    final boolean visibleAgain = this.viewer.canSee(respawned);
    assertTrue(visibleAgain, "the viewer is shown the display again");
    verify(this.viewer, times(2)).showEntity(plugin, respawned);

    renderer.onTick();
    verify(this.viewer, times(2)).showEntity(plugin, respawned);

    renderer.hide();
  }

  @Test
  void shouldShowAReplacementDisplayOnItsFirstTickToAViewerWhoCannotSeeIt() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Plugin plugin = this.server.getPlugin();
    try {
      renderer.show();
      final List<CraftTextDisplay> spawnedBefore = this.world.getSpawnedDisplays();
      final CraftTextDisplay discarded = spawnedBefore.getFirst();
      final World configuredWorld = this.world.getWorld();
      when(discarded.isValid()).thenReturn(false);
      when(configuredWorld.isChunkLoaded(0, 0)).thenReturn(true);
      doReturn(false).when(this.viewer).canSee(any(Entity.class));

      renderer.onTick();

      final List<CraftTextDisplay> spawnedAfter = this.world.getSpawnedDisplays();
      final CraftTextDisplay replacement = spawnedAfter.get(1);
      verify(this.viewer, times(2)).showEntity(plugin, replacement);
    } finally {
      renderer.hide();
    }
  }

  @Test
  void shouldWaitForTheChunkToLoadBeforeRespawningTheDisplay() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Component text = Component.literal(FRAME_TEXT);
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
  void shouldAskAboutTheChunkThatHoldsANegativeBlockPosition() {
    final World configuredWorld = this.world.getWorld();
    final Location negative = new Location(configuredWorld, -17, POSITION_HEIGHT, 35);
    final EntityConfiguration configuration = this.createConfiguration(negative);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Component text = Component.literal(FRAME_TEXT);
    renderer.show();
    final List<CraftTextDisplay> spawnedBefore = this.world.getSpawnedDisplays();
    final CraftTextDisplay discarded = spawnedBefore.getFirst();
    when(discarded.isValid()).thenReturn(false);

    renderer.apply(text);

    verify(configuredWorld).isChunkLoaded(-2, 2);
  }

  @Test
  void shouldStopRespawningOnceThePositionHasNoWorld() {
    final Location movable = this.position.clone();
    final EntityConfiguration configuration = this.createConfiguration(movable);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final Component text = Component.literal(FRAME_TEXT);
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
  void shouldRemoveTheDisplayWhenHidden() {
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
  void shouldNeedAPositionInAWorld() {
    final Location movable = this.position.clone();
    final EntityConfiguration configuration = this.createConfiguration(movable);
    movable.setWorld(null);
    final EntityRenderer renderer = new EntityRenderer(configuration);

    assertThrows(IllegalStateException.class, renderer::show);
  }

  @Test
  void shouldHideTheDisplayFromRemovedViewersAndShowItAgainOnReaddition() {
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
  void shouldForgetWhoSawTheRemovedDisplayWhenShowingANewOne() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    renderer.show();
    this.viewers.remove(VIEWER);
    renderer.hide();
    renderer.show();
    renderer.onTick();

    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final int displayCount = displays.size();
    final Plugin plugin = this.server.getPlugin();
    assertEquals(2, displayCount);
    verify(this.viewer, never()).hideEntity(eq(plugin), any(Entity.class));
  }

  @Test
  void shouldNotLetAQueuedShowSpawnAnEntityAfterAMainThreadHide() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    this.server.setPrimaryThread(false);
    renderer.show();
    this.server.setPrimaryThread(true);
    renderer.hide();
    this.server.runTasks();
    final List<CraftTextDisplay> displays = this.world.getSpawnedDisplays();
    final int displayCount = displays.size();
    assertEquals(0, displayCount);

    final int tasks = this.server.getScheduledTaskCount();
    assertEquals(0, tasks);
  }

  @Test
  void shouldNotLetAQueuedHideRemoveAnEntityRequestedAgainOnTheMainThread() {
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
  void shouldRejectMissingArguments() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);

    assertThrows(NullPointerException.class, () -> new EntityRenderer(null));
    assertThrows(NullPointerException.class, () -> renderer.render(null));
  }

  @Test
  void shouldRejectAReleasedImage() {
    final EntityConfiguration configuration = this.createConfiguration(this.position);
    final EntityRenderer renderer = new EntityRenderer(configuration);
    final ImageBuffer image = ImageBuffer.buffer(new int[2], 2, 1);
    image.release();

    assertThrows(IllegalStateException.class, () -> renderer.render(image));
  }
}
