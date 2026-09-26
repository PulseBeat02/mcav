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
package me.brandonli.mcav.sandbox.command.video;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Pack;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Viewers;
import me.brandonli.mcav.bukkit.resourcepack.provider.PackHosting;
import me.brandonli.mcav.sandbox.locale.Message;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * What the MCV2 commands share: finding the wall of a map screen, building the screen's resource pack, serving it on
 * the Minecraft port, and asking the viewers' clients to load it.
 *
 * <p>One pack is served at a time; a new screen replaces it. Players who decline it, or whose client cannot load it,
 * are told so and keep the dithered maps.
 */
public final class Mcv2Support {

  /**
   * The system property that makes the pack also draw the decoded picture one to one in the top-left corner, with the
   * state of the pages and the decoder next to it, for testing the pack in-game.
   */
  public static final String DEBUG_VIEW = "mcav.mcv2.debugView";

  private final Path folder;
  private @Nullable Served served;
  private @Nullable UUID lastId;

  /**
   * The pack being served.
   *
   * @param hosting where the players download it
   * @param viewers who loaded it
   * @param id      its id, derived from its hash
   * @param hash    its SHA-1
   */
  private record Served(PackHosting hosting, Mcv2Viewers viewers, UUID id, String hash) {}

  /**
   * Constructs the support.
   *
   * @param folder the plugin's data folder, where the pack is written
   */
  public Mcv2Support(final Path folder) {
    Preconditions.checkNotNull(folder, "Folder must not be null");
    this.folder = folder;
  }

  /**
   * Finds the frame holding the top-left map of a screen built with {@code /mcav screen}.
   *
   * @param worlds the worlds to search
   * @param mapId  the id of the top-left map
   * @return the frame, or null if no frame holds that map
   */
  public static @Nullable ItemFrame findFrame(final Collection<World> worlds, final int mapId) {
    for (final World world : worlds) {
      for (final ItemFrame frame : world.getEntitiesByClass(ItemFrame.class)) {
        if (holdsMap(frame.getItem(), mapId)) {
          return frame;
        }
      }
    }
    return null;
  }

  static boolean holdsMap(final ItemStack item, final int mapId) {
    if (item.getType() != Material.FILLED_MAP) {
      return false;
    }
    final ItemMeta meta = item.getItemMeta();
    return meta instanceof final MapMeta map && map.hasMapId() && map.getMapId() == mapId;
  }

  /**
   * Writes the screen's pack, serves it, and asks the players' clients to load it. A pack is known by its content: its
   * id is derived from its hash, so the same screen offered again is the pack already served, and it is only sent to
   * the players who have not loaded it yet - a client that loads a pack again reloads all its resources, which takes
   * seconds, and would stack another copy. A different pack replaces the one served before, which the players are
   * asked to remove. Call on the main thread.
   *
   * @param configuration the screen
   * @param players       the players to ask
   * @return who loaded the pack, listening to the players' answers until {@link #close()}
   */
  public Mcv2Viewers offer(final Mcv2Configuration configuration, final Collection<? extends Player> players) {
    Preconditions.checkNotNull(configuration, "Configuration must not be null");
    Preconditions.checkNotNull(players, "Players must not be null");
    final Path directory = this.folder.resolve("mcv2");
    final Path next = directory.resolve("mcav-mcv2-next.zip");
    Mcv2Pack.write(configuration, Boolean.getBoolean(DEBUG_VIEW), next);
    final String sha1 = hash(next, "SHA-1");
    final Served current = this.served;
    final Served pack;
    if (current != null && current.hash().equals(sha1)) {
      deleteQuietly(next);
      pack = current;
    } else {
      this.close();
      final UUID packId = UUID.nameUUIDFromBytes(("mcav-mcv2:" + sha1).getBytes(StandardCharsets.UTF_8));
      final UUID previous = this.lastId;
      if (previous != null && !previous.equals(packId)) {
        for (final Player player : players) {
          player.removeResourcePacks(previous);
        }
      }
      final Path zip = directory.resolve("mcav-mcv2.zip");
      move(next, zip);
      final PackHosting server = PackHosting.injector(zip);
      server.start();
      final Mcv2Viewers tracker = new Mcv2Viewers(packId, player -> player.sendMessage(Message.MCV2_REFUSED.build()));
      tracker.register();
      pack = new Served(server, tracker, packId, sha1);
      this.served = pack;
      this.lastId = packId;
    }
    final ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(pack.id(), URI.create(pack.hosting().getRawUrl()), sha1);
    final ResourcePackRequest request = ResourcePackRequest.resourcePackRequest().packs(info).required(false).replace(false).build();
    for (final Player player : players) {
      final UUID uuid = player.getUniqueId();
      if (pack.viewers().isLoaded(uuid)) {
        continue;
      }
      pack.viewers().requested(uuid);
      player.sendMessage(Message.MCV2_PACK.build());
      player.sendResourcePacks(request);
    }
    return pack.viewers();
  }

  /**
   * Stops serving the pack and listening to the players' answers; the next offer serves its pack anew.
   */
  public void close() {
    final Served pack = this.served;
    if (pack != null) {
      pack.viewers().unregister();
      pack.hosting().shutdown();
      this.served = null;
    }
  }

  /**
   * Moves the pack just written into the place it is served from.
   *
   * @param source the pack just written
   * @param target where it is served from
   */
  static void move(final Path source, final Path target) {
    try {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (final IOException exception) {
      throw new UncheckedIOException("Cannot move the MCV2 pack into place", exception);
    }
  }

  /**
   * Deletes a pack that is not needed because the same pack is already served.
   *
   * @param file the pack
   */
  static void deleteQuietly(final Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (final IOException exception) {
      // a leftover file is overwritten by the next offer
    }
  }

  /**
   * The hash of a file; the client checks the pack it downloads against its SHA-1.
   *
   * @param file      the file
   * @param algorithm the digest algorithm
   * @return the hash as lowercase hexadecimal
   */
  static String hash(final Path file, final String algorithm) {
    try (final InputStream input = Files.newInputStream(file)) {
      final MessageDigest digest = MessageDigest.getInstance(algorithm);
      final byte[] buffer = new byte[8192];
      for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
        digest.update(buffer, 0, read);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (final IOException exception) {
      throw new UncheckedIOException("Cannot hash the MCV2 pack", exception);
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException(algorithm + " is not available", exception);
    }
  }
}
