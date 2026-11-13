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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

final class JukeboxListenerTest {

  private static final String IMAGE_FILE_NAME = "alpine.iso";
  private static final String ISO_FOLDER_NAME = "iso";
  private static final String SANITIZED_IMAGE_FILE_NAME = "my_disk_.iso";

  private static final String DISC_NAME = "[alpine.iso]";

  private static final String VM_CREATE_COMMAND_PREFIX = "mcav vm create Steve 640x640 30 5x5 0 FILTER_LITE X86_64 NONE -cdrom \"";
  private static final String VM_CREATE_COMMAND_SUFFIX = "\" -m 2048M";

  @TempDir
  private Path folder;

  private MCAVSandbox sandbox;
  private JukeboxListener listener;
  private Path isoFolder;
  private Player player;

  @BeforeEach
  void createListener() {
    TestServer.reset();
    this.sandbox = mock(MCAVSandbox.class);
    when(this.sandbox.getDataPath()).thenReturn(this.folder);
    this.listener = new JukeboxListener(this.sandbox);
    this.isoFolder = this.folder.resolve(ISO_FOLDER_NAME);
    this.player = mock(Player.class);
    when(this.player.getName()).thenReturn("Steve");
  }

  @Test
  void shouldCreateTheIsoFolder() {
    final boolean exists = Files.isDirectory(this.isoFolder);
    assertTrue(exists);
  }

  @Test
  void shouldRegisterItselfWhenStarted() {
    this.listener.start();
    final PluginManager pluginManager = TestServer.getPluginManager();
    verify(pluginManager).registerEvents(this.listener, this.sandbox);
  }

  @Test
  void shouldUnregisterItselfWhenShutDown() {
    try (final MockedStatic<HandlerList> handlers = Mockito.mockStatic(HandlerList.class)) {
      this.listener.shutdown();
      handlers.verify(() -> HandlerList.unregisterAll(this.listener));
    }
  }

  @Test
  void shouldBootTheMachineOfTheImageNamedLikeTheDisc() throws IOException {
    final Path image = this.createImage(IMAGE_FILE_NAME);
    final PlayerInteractEvent event = this.interaction(Action.RIGHT_CLICK_BLOCK, Material.JUKEBOX, Material.MUSIC_DISC_CAT, DISC_NAME);
    this.listener.onJukeboxInteract(event);
    verify(event).setCancelled(true);

    final Path absolute = image.toAbsolutePath();
    verify(this.player).performCommand(VM_CREATE_COMMAND_PREFIX + absolute + VM_CREATE_COMMAND_SUFFIX);
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

  @Test
  void shouldReplaceCharactersThatFileNamesCannotContain() throws IOException {
    final Path image = this.createImage(SANITIZED_IMAGE_FILE_NAME);
    final PlayerInteractEvent event = this.interaction(
      Action.RIGHT_CLICK_BLOCK,
      Material.JUKEBOX,
      Material.MUSIC_DISC_PIGSTEP,
      "[my:disk?.iso]"
    );
    this.listener.onJukeboxInteract(event);
    final Path absolute = image.toAbsolutePath();
    verify(this.player).performCommand(VM_CREATE_COMMAND_PREFIX + absolute + VM_CREATE_COMMAND_SUFFIX);
  }

  @Test
  void shouldLeaveAUseOfTheJukeboxThatAnotherPluginDeniedAlone() throws IOException {
    this.createImage(IMAGE_FILE_NAME);
    final PlayerInteractEvent event = this.interaction(Action.RIGHT_CLICK_BLOCK, Material.JUKEBOX, Material.MUSIC_DISC_CAT, DISC_NAME);
    when(event.useInteractedBlock()).thenReturn(Event.Result.DENY);
    this.assertIgnored(event);
  }

  private void assertIgnored(final PlayerInteractEvent event) {
    this.listener.onJukeboxInteract(event);
    verify(event, never()).setCancelled(anyBoolean());
    verify(this.player, never()).performCommand(anyString());
  }

  @Test
  void shouldDecideAfterThePluginsThatProtectRegions() throws Exception {
    this.createImage(IMAGE_FILE_NAME);
    final RegisteredListener protection = new RegisteredListener(
      new Listener() {},
      (listener, event) -> ((PlayerInteractEvent) event).setUseInteractedBlock(Event.Result.DENY),
      EventPriority.HIGH,
      this.sandbox,
      false
    );
    this.dispatch(this.discOnJukebox(), protection, this.registered());
    verify(this.player, never()).performCommand(anyString());

    this.dispatch(this.discOnJukebox(), this.registered());
    verify(this.player, times(1)).performCommand(anyString());
  }

  private PlayerInteractEvent discOnJukebox() {
    final Block jukebox = mock(Block.class);
    when(jukebox.getType()).thenReturn(Material.JUKEBOX);
    final ItemStack disc = mock(ItemStack.class);
    when(disc.getType()).thenReturn(Material.MUSIC_DISC_CAT);
    final Component name = Component.text(DISC_NAME);
    when(disc.displayName()).thenReturn(name);

    return new PlayerInteractEvent(this.player, Action.RIGHT_CLICK_BLOCK, disc, jukebox, BlockFace.UP);
  }

  private RegisteredListener registered() throws NoSuchMethodException {
    final EventHandler handler = JukeboxListener.class
      .getMethod("onJukeboxInteract", PlayerInteractEvent.class)
      .getAnnotation(EventHandler.class);
    return new RegisteredListener(
      this.listener,
      (listener, event) -> ((JukeboxListener) listener).onJukeboxInteract((PlayerInteractEvent) event),
      handler.priority(),
      this.sandbox,
      false
    );
  }

  private void dispatch(final Event event, final RegisteredListener... listeners) throws EventException {
    final HandlerList handlers = new HandlerList();
    for (final RegisteredListener listener : listeners) {
      handlers.register(listener);
    }
    for (final RegisteredListener listener : handlers.getRegisteredListeners()) {
      listener.callEvent(event);
    }
  }

  @Test
  void shouldIgnoreInteractionsWithoutAnItem() {
    final PlayerInteractEvent event = this.interaction(Action.RIGHT_CLICK_BLOCK, Material.JUKEBOX, Material.MUSIC_DISC_CAT, DISC_NAME);
    when(event.getItem()).thenReturn(null);
    this.assertIgnored(event);
  }

  @Test
  void shouldIgnoreLeftClicks() throws IOException {
    this.createImage(IMAGE_FILE_NAME);
    final PlayerInteractEvent event = this.interaction(Action.LEFT_CLICK_BLOCK, Material.JUKEBOX, Material.MUSIC_DISC_CAT, DISC_NAME);
    this.assertIgnored(event);
  }

  @Test
  void shouldIgnoreRightClicksWithoutABlock() throws IOException {
    this.createImage(IMAGE_FILE_NAME);
    final PlayerInteractEvent event = this.interaction(Action.RIGHT_CLICK_BLOCK, Material.JUKEBOX, Material.MUSIC_DISC_CAT, DISC_NAME);
    when(event.getClickedBlock()).thenReturn(null);
    this.assertIgnored(event);
  }

  @Test
  void shouldIgnoreBlocksOtherThanJukeboxes() throws IOException {
    this.createImage(IMAGE_FILE_NAME);
    final PlayerInteractEvent event = this.interaction(Action.RIGHT_CLICK_BLOCK, Material.NOTE_BLOCK, Material.MUSIC_DISC_CAT, DISC_NAME);
    this.assertIgnored(event);
  }

  @Test
  void shouldIgnoreItemsOtherThanMusicDiscs() throws IOException {
    this.createImage(IMAGE_FILE_NAME);
    final PlayerInteractEvent event = this.interaction(Action.RIGHT_CLICK_BLOCK, Material.JUKEBOX, Material.DISC_FRAGMENT_5, DISC_NAME);
    this.assertIgnored(event);
  }

  @Test
  void shouldIgnoreDiscsWithoutAnImage() {
    final PlayerInteractEvent event = this.interaction(Action.RIGHT_CLICK_BLOCK, Material.JUKEBOX, Material.MUSIC_DISC_CAT, "[Music Disc]");
    this.assertIgnored(event);
  }

  @Test
  void shouldIgnoreADiscWhoseNameIsNoValidFileName() throws IOException {
    this.createImage(IMAGE_FILE_NAME);
    final PlayerInteractEvent event = this.interaction(
      Action.RIGHT_CLICK_BLOCK,
      Material.JUKEBOX,
      Material.MUSIC_DISC_CAT,
      "[alpine\u0000.iso]"
    );
    this.assertIgnored(event);
  }

  @Test
  void shouldRefuseNullArguments() {
    assertThrows(NullPointerException.class, () -> new JukeboxListener(null));
    assertThrows(NullPointerException.class, () -> this.listener.onJukeboxInteract(null));
  }

  @Test
  void shouldRefuseANullPluginWithItsOwnMessage() {
    final NullPointerException exception = assertThrows(NullPointerException.class, () -> new JukeboxListener(null));

    final String message = exception.getMessage();
    assertEquals("Plugin must not be null!", message);
  }

  @ParameterizedTest
  @ValueSource(strings = { "[..]", "[.]", "[]" })
  void shouldIgnoreNamesThatPointAtFolders(final String name) {
    final PlayerInteractEvent event = this.interaction(Action.RIGHT_CLICK_BLOCK, Material.JUKEBOX, Material.MUSIC_DISC_CAT, name);
    this.assertIgnored(event);
  }
}
