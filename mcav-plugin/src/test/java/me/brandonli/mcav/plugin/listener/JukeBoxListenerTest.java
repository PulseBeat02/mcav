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
package me.brandonli.mcav.plugin.listener;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.testing.TestServer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Tests {@link JukeBoxListener}.
 */
final class JukeBoxListenerTest {

  @TempDir
  private Path folder;

  private MCAVSandbox sandbox;
  private JukeBoxListener listener;
  private Path isoFolder;
  private Player player;

  @BeforeEach
  void createListener() {
    TestServer.reset();
    this.sandbox = mock(MCAVSandbox.class);
    when(this.sandbox.getDataPath()).thenReturn(this.folder);
    this.listener = new JukeBoxListener(this.sandbox);
    this.isoFolder = this.folder.resolve("iso");
    this.player = mock(Player.class);
    when(this.player.getName()).thenReturn("Steve");
  }

  private PlayerInteractEvent interaction(final Action action, final Material blockType, final Material itemType, final String itemName) {
    final PlayerInteractEvent event = mock(PlayerInteractEvent.class);
    final Block block = mock(Block.class);
    when(block.getType()).thenReturn(blockType);
    final ItemStack item = mock(ItemStack.class);
    when(item.getType()).thenReturn(itemType);
    final Component name = Component.text(itemName);
    when(item.displayName()).thenReturn(name);
    when(event.getAction()).thenReturn(action);
    when(event.getClickedBlock()).thenReturn(block);
    when(event.getItem()).thenReturn(item);
    when(event.getPlayer()).thenReturn(this.player);
    return event;
  }

  private Path createImage(final String name) throws IOException {
    final Path image = this.isoFolder.resolve(name);
    Files.writeString(image, "iso");
    return image;
  }

  private void assertIgnored(final PlayerInteractEvent event) {
    this.listener.onJukeboxInteract(event);
    verify(event, never()).setCancelled(anyBoolean());
    verify(this.player, never()).performCommand(anyString());
  }

  @Test
  void createsTheIsoFolder() {
    final boolean exists = Files.isDirectory(this.isoFolder);
    assertTrue(exists);
  }

  @Test
  void registersItselfWhenStarted() {
    this.listener.start();
    final PluginManager pluginManager = TestServer.pluginManager();
    verify(pluginManager).registerEvents(this.listener, this.sandbox);
  }

  @Test
  void unregistersItselfWhenShutDown() {
    try (final MockedStatic<HandlerList> handlers = Mockito.mockStatic(HandlerList.class)) {
      this.listener.shutdown();
      handlers.verify(() -> HandlerList.unregisterAll(this.listener));
    }
  }

  @Test
  void bootsTheMachineOfTheImageNamedLikeTheDisc() throws IOException {
    final Path image = this.createImage("alpine.iso");
    final PlayerInteractEvent event = this.interaction(Action.RIGHT_CLICK_BLOCK, Material.JUKEBOX, Material.MUSIC_DISC_CAT, "[alpine.iso]");
    this.listener.onJukeboxInteract(event);
    verify(event).setCancelled(true);
    final Path absolute = image.toAbsolutePath();
    verify(this.player).performCommand(
      "mcav vm create Steve 640x640 30 5x5 0 FILTER_LITE X86_64 NONE -cdrom \"" + absolute + "\" -m 2048M"
    );
  }

  @Test
  void replacesCharactersThatFileNamesCannotContain() throws IOException {
    final Path image = this.createImage("my_disk_.iso");
    final PlayerInteractEvent event = this.interaction(
      Action.RIGHT_CLICK_BLOCK,
      Material.JUKEBOX,
      Material.MUSIC_DISC_PIGSTEP,
      "[my:disk?.iso]"
    );
    this.listener.onJukeboxInteract(event);
    final Path absolute = image.toAbsolutePath();
    verify(this.player).performCommand(
      "mcav vm create Steve 640x640 30 5x5 0 FILTER_LITE X86_64 NONE -cdrom \"" + absolute + "\" -m 2048M"
    );
  }

  @Test
  void leavesAUseOfTheJukeboxThatAnotherPluginDeniedAlone() throws IOException {
    this.createImage("alpine.iso");
    final PlayerInteractEvent event = this.interaction(Action.RIGHT_CLICK_BLOCK, Material.JUKEBOX, Material.MUSIC_DISC_CAT, "[alpine.iso]");
    when(event.useInteractedBlock()).thenReturn(Event.Result.DENY);
    this.assertIgnored(event);
  }

  /** A real interaction with a disc named after the image, which listeners can change as they do on a server. */
  private PlayerInteractEvent discOnJukebox() {
    final Block jukebox = mock(Block.class);
    when(jukebox.getType()).thenReturn(Material.JUKEBOX);
    final ItemStack disc = mock(ItemStack.class);
    when(disc.getType()).thenReturn(Material.MUSIC_DISC_CAT);
    final Component name = Component.text("[alpine.iso]");
    when(disc.displayName()).thenReturn(name);
    return new PlayerInteractEvent(this.player, Action.RIGHT_CLICK_BLOCK, disc, jukebox, BlockFace.UP);
  }

  /** The listener as the plugin manager registers it: at the priority its handler's annotation names. */
  private RegisteredListener registered() throws NoSuchMethodException {
    final EventHandler handler = JukeBoxListener.class
      .getMethod("onJukeboxInteract", PlayerInteractEvent.class)
      .getAnnotation(EventHandler.class);
    return new RegisteredListener(
      this.listener,
      (listener, event) -> ((JukeBoxListener) listener).onJukeboxInteract((PlayerInteractEvent) event),
      handler.priority(),
      this.sandbox,
      false
    );
  }

  /** Calls the listeners of an event as the server does, in the order of their priorities. */
  private static void dispatch(final Event event, final RegisteredListener... listeners) throws EventException {
    final HandlerList handlers = new HandlerList();
    for (final RegisteredListener listener : listeners) {
      handlers.register(listener);
    }
    for (final RegisteredListener listener : handlers.getRegisteredListeners()) {
      listener.callEvent(event);
    }
  }

  @Test
  void decidesAfterThePluginsThatProtectRegions() throws Exception {
    this.createImage("alpine.iso");
    final RegisteredListener protection = new RegisteredListener(
      new Listener() {},
      (listener, event) -> ((PlayerInteractEvent) event).setUseInteractedBlock(Event.Result.DENY),
      EventPriority.HIGH,
      this.sandbox,
      false
    );
    dispatch(this.discOnJukebox(), protection, this.registered());
    verify(this.player, never()).performCommand(anyString());
    dispatch(this.discOnJukebox(), this.registered());
    verify(this.player, times(1)).performCommand(anyString());
  }

  @Test
  void ignoresInteractionsWithoutAnItem() {
    final PlayerInteractEvent event = this.interaction(Action.RIGHT_CLICK_BLOCK, Material.JUKEBOX, Material.MUSIC_DISC_CAT, "[alpine.iso]");
    when(event.getItem()).thenReturn(null);
    this.assertIgnored(event);
  }

  @Test
  void ignoresLeftClicks() throws IOException {
    this.createImage("alpine.iso");
    final PlayerInteractEvent event = this.interaction(Action.LEFT_CLICK_BLOCK, Material.JUKEBOX, Material.MUSIC_DISC_CAT, "[alpine.iso]");
    this.assertIgnored(event);
  }

  @Test
  void ignoresRightClicksWithoutABlock() throws IOException {
    this.createImage("alpine.iso");
    final PlayerInteractEvent event = this.interaction(Action.RIGHT_CLICK_BLOCK, Material.JUKEBOX, Material.MUSIC_DISC_CAT, "[alpine.iso]");
    when(event.getClickedBlock()).thenReturn(null);
    this.assertIgnored(event);
  }

  @Test
  void ignoresBlocksOtherThanJukeboxes() throws IOException {
    this.createImage("alpine.iso");
    final PlayerInteractEvent event = this.interaction(
      Action.RIGHT_CLICK_BLOCK,
      Material.NOTE_BLOCK,
      Material.MUSIC_DISC_CAT,
      "[alpine.iso]"
    );
    this.assertIgnored(event);
  }

  @Test
  void ignoresItemsOtherThanMusicDiscs() throws IOException {
    this.createImage("alpine.iso");
    final PlayerInteractEvent event = this.interaction(
      Action.RIGHT_CLICK_BLOCK,
      Material.JUKEBOX,
      Material.DISC_FRAGMENT_5,
      "[alpine.iso]"
    );
    this.assertIgnored(event);
  }

  @Test
  void ignoresDiscsWithoutAnImage() {
    final PlayerInteractEvent event = this.interaction(Action.RIGHT_CLICK_BLOCK, Material.JUKEBOX, Material.MUSIC_DISC_CAT, "[Music Disc]");
    this.assertIgnored(event);
  }

  @Test
  void refusesNullArguments() {
    assertThrows(NullPointerException.class, () -> new JukeBoxListener(null));
    assertThrows(NullPointerException.class, () -> this.listener.onJukeboxInteract(null));
  }

  @ParameterizedTest
  @ValueSource(strings = { "[..]", "[.]", "[]" })
  void ignoresNamesThatPointAtFolders(final String name) {
    final PlayerInteractEvent event = this.interaction(Action.RIGHT_CLICK_BLOCK, Material.JUKEBOX, Material.MUSIC_DISC_CAT, name);
    this.assertIgnored(event);
  }
}
