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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import me.brandonli.mcav.plugin.MCAVSandbox;
import me.brandonli.mcav.plugin.utils.DiskImages;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Tag;
import org.mockito.invocation.InvocationOnMock;

@Tag("fuzz")
final class JukeboxListenerFuzzTest {

  private static final String DATA_FOLDER_PREFIX = "mcav-jukebox-fuzz";

  private static final String IMAGE_FILE_NAME = "alpine.iso";
  private static final String SANITIZED_IMAGE_FILE_NAME = "my_disk_.iso";
  private static final String DIRECTORY_IMAGE_FILE_NAME = "directory.iso";
  private static final String OUTSIDE_IMAGE_FILE_NAME = "outside.iso";

  private static final String PLAYER_NAME = "Steve";

  private static final String VM_CREATE_COMMAND_PREFIX = "mcav vm create Steve 640x640 30 5x5 0 FILTER_LITE X86_64 NONE -cdrom \"";
  private static final String VM_CREATE_COMMAND_SUFFIX = "\" -m 2048M";

  private static final List<String> COMMANDS = new ArrayList<>();
  private static final AtomicReference<Component> DISC_NAME = new AtomicReference<>(Component.empty());

  private static final Path DATA_FOLDER = createDataFolder();
  private static final Path ISO_FOLDER = DiskImages.folderOf(DATA_FOLDER);

  private static final Player PLAYER = createPlayer();
  private static final ItemStack DISC = createDisc();
  private static final Block JUKEBOX = createJukebox();
  private static final JukeboxListener LISTENER = createListener();

  @FuzzTest(maxDuration = "30s")
  void shouldBootOnlyAnImageInsideTheIsoFolder(final FuzzedDataProvider data) throws IOException {
    final String discName = data.consumeRemainingAsString();
    final Component name = Component.text(discName);
    DISC_NAME.set(name);
    COMMANDS.clear();
    final PlayerInteractEvent event = new PlayerInteractEvent(PLAYER, Action.RIGHT_CLICK_BLOCK, DISC, JUKEBOX, BlockFace.UP);
    LISTENER.onJukeboxInteract(event);

    final boolean booted = !COMMANDS.isEmpty();
    final Event.Result jukeboxUse = event.useInteractedBlock();
    final boolean cancelled = jukeboxUse == Event.Result.DENY;
    assertEquals(booted, cancelled, discName);
    if (!booted) {
      return;
    }

    final int commandCount = COMMANDS.size();
    assertEquals(1, commandCount, discName);

    final String command = COMMANDS.getFirst();
    final boolean framed = command.startsWith(VM_CREATE_COMMAND_PREFIX) && command.endsWith(VM_CREATE_COMMAND_SUFFIX);
    assertTrue(framed, command);

    final int imageEnd = command.length() - VM_CREATE_COMMAND_SUFFIX.length();
    final String imageText = command.substring(VM_CREATE_COMMAND_PREFIX.length(), imageEnd);
    final Path image = Path.of(imageText);
    final Path realImage = image.toRealPath();
    final Path realFolder = ISO_FOLDER.toRealPath();
    final boolean inside = realImage.startsWith(realFolder) && !realImage.equals(realFolder);
    assertTrue(inside, command);

    final boolean file = Files.isRegularFile(realImage);
    assertTrue(file, command);
  }

  private static Path createDataFolder() {
    try {
      final Path dataFolder = Files.createTempDirectory(DATA_FOLDER_PREFIX);
      final Path isoFolder = DiskImages.folderOf(dataFolder);
      final Path directoryImage = isoFolder.resolve(DIRECTORY_IMAGE_FILE_NAME);
      final List<Path> folders = List.of(dataFolder, isoFolder, directoryImage);
      final List<Path> files = List.of(
        isoFolder.resolve(IMAGE_FILE_NAME),
        isoFolder.resolve(SANITIZED_IMAGE_FILE_NAME),
        dataFolder.resolve(OUTSIDE_IMAGE_FILE_NAME)
      );
      createFoldersAndFiles(folders, files);
      return dataFolder;
    } catch (final IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  // deleteOnExit deletes in reverse order, so each folder is registered before what it holds
  private static void createFoldersAndFiles(final List<Path> folders, final List<Path> files) throws IOException {
    for (final Path folder : folders) {
      Files.createDirectories(folder);
      final File created = folder.toFile();
      created.deleteOnExit();
    }

    for (final Path file : files) {
      Files.createFile(file);
      final File created = file.toFile();
      created.deleteOnExit();
    }
  }

  // the mocks live as long as the fuzzer runs, so they keep no record of their calls
  private static Player createPlayer() {
    final Player player = mock(Player.class, withSettings().stubOnly());
    when(player.getName()).thenReturn(PLAYER_NAME);
    when(player.performCommand(anyString())).thenAnswer(JukeboxListenerFuzzTest::recordCommand);
    return player;
  }

  private static boolean recordCommand(final InvocationOnMock invocation) {
    final String command = invocation.getArgument(0);
    COMMANDS.add(command);

    return true;
  }

  private static ItemStack createDisc() {
    final ItemStack disc = mock(ItemStack.class, withSettings().stubOnly());
    when(disc.getType()).thenReturn(Material.MUSIC_DISC_CAT);
    when(disc.displayName()).thenAnswer(_ -> DISC_NAME.get());
    return disc;
  }

  private static Block createJukebox() {
    final Block jukebox = mock(Block.class, withSettings().stubOnly());
    when(jukebox.getType()).thenReturn(Material.JUKEBOX);
    return jukebox;
  }

  private static JukeboxListener createListener() {
    final MCAVSandbox plugin = mock(MCAVSandbox.class, withSettings().stubOnly());
    when(plugin.getDataPath()).thenReturn(DATA_FOLDER);
    return new JukeboxListener(plugin);
  }
}
