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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Format;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2PackServer;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Result;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Viewers;
import me.brandonli.mcav.bukkit.media.mcv2.encode.EncoderSettings;
import me.brandonli.mcav.bukkit.resourcepack.provider.PackHosting;
import me.brandonli.mcav.sandbox.locale.Message;
import me.brandonli.mcav.sandbox.utils.DitheringArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * What every MCV2 screen of the plugin shares: the one resource pack that decodes them all, served by an
 * {@link Mcv2PackServer}, and finding the wall of a screen.
 *
 * <p>A screen asks for a slot of the pack when it starts and gives it back when it is released. Players who decline
 * the pack, or whose client cannot load it, are told so and keep the dithered maps.
 */
public final class Mcv2Support {

  /**
   * The system property that makes the pack also draw the first slot's decoded picture one to one in the top-left
   * corner, with the state of its pages and its decoder next to it, for testing the pack in-game.
   */
  public static final String DEBUG_VIEW = "mcav.mcv2.debugView";

  /**
   * The system property naming a folder that the frames every MCV2 screen started next sends are recorded into, one
   * stream file per screen, for a measurement that decodes them with the reference decoder; unset for none.
   */
  public static final String RECORD_PROPERTY = "mcav.sandbox.mcv2.record";

  /** The folder of the pack, inside the plugin's {@code mcv2} folder of streams. */
  private static final String PACK_FOLDER = "pack";

  /** The smaller sizes offered, as fractions of the screen's: two thirds, then a half. */
  private static final int[][] SMALLER_FRACTIONS = { { 2, 3 }, { 1, 2 } };

  /** The smallest size offered: 128x72. */
  private static final int MIN_WIDTH = 128;

  private static final int MIN_HEIGHT = 72;

  private final Mcv2PackServer packs;

  /**
   * Constructs the support, which serves nothing until {@link #start()}.
   *
   * @param dataFolder the plugin's data folder; the pack is written in {@code mcv2/pack} inside it
   * @param hosting    hosts a written pack
   */
  public Mcv2Support(final Path dataFolder, final Function<Path, PackHosting> hosting) {
    this(
      new Mcv2PackServer(
        dataFolder.resolve(Mcv2PlayCommand.STREAMS).resolve(PACK_FOLDER),
        hosting,
        Boolean.getBoolean(DEBUG_VIEW),
        Mcv2Support::tellOffered,
        Mcv2Support::tellRefused
      )
    );
  }

  Mcv2Support(final Mcv2PackServer packs) {
    Preconditions.checkNotNull(packs, "Pack server must not be null");
    this.packs = packs;
  }

  /** Tells a viewer why their client is about to ask them to load a pack. */
  static void tellOffered(final Player player) {
    player.sendMessage(Message.MCV2_PACK.build());
  }

  /** Tells a viewer whose client declined the pack, or could not load it, why they see the dithered maps. */
  static void tellRefused(final Player player) {
    player.sendMessage(Message.MCV2_REFUSED.build());
  }

  /**
   * Starts listening to the players for the pack. Call on the main thread.
   */
  public void start() {
    this.packs.start();
  }

  /**
   * Stops serving the pack; the players keep what they loaded.
   */
  public void shutdown() {
    this.packs.shutdown();
  }

  /**
   * Gets who loaded the pack.
   *
   * @return the viewers every screen shares
   */
  public Mcv2Viewers getViewers() {
    return this.packs.getViewers();
  }

  /**
   * Creates the configuration of an MCV2 screen on the wall that holds a map, or tells the sender there is none, or
   * that the screen is larger than MCV2 plays: the plugin's walls go up to 64 maps a side and 8192 pixels, MCV2's
   * to 63 maps and 4096 pixels. Call on the main thread, which may look at the worlds' entities.
   *
   * @param sender     who ran the command
   * @param blocks     the size of the wall in maps
   * @param resolution the encoded resolution
   * @param mapId      the id of the top-left map
   * @param settings   the encoder profile
   * @param viewers    the players who watch
   * @return the configuration, or null if the screen is too large for MCV2 or no frame holds the map
   */
  public @Nullable Mcv2Configuration configure(
    final CommandSender sender,
    final Pair<Integer, Integer> blocks,
    final Pair<Integer, Integer> resolution,
    final int mapId,
    final EncoderSettings settings,
    final Collection<UUID> viewers
  ) {
    // the library refuses such a screen with an exception, which no command expects
    if (!fitsMcv2(blocks, resolution)) {
      sender.sendMessage(Message.MCV2_SIZE_ERROR.build());
      return null;
    }
    final ItemFrame frame = findFrame(Bukkit.getWorlds(), mapId);
    if (frame == null) {
      sender.sendMessage(Message.MCV2_SCREEN_ERROR.build(mapId));
      return null;
    }
    return Mcv2Configuration.builder()
      .viewers(viewers)
      .origin(frame.getLocation())
      .facing(frame.getFacing())
      .map(mapId)
      .columns(blocks.getFirst())
      .rows(blocks.getSecond())
      .video(resolution.getFirst(), resolution.getSecond())
      .settings(settings)
      .pageSlots(Integer.getInteger(VideoMcv2Command.PAGE_SLOTS_PROPERTY, 0))
      .backlogLimit(VideoMcv2Command.backlogLimit())
      .unsentLimit(VideoMcv2Command.unsentLimit())
      .build();
  }

  private static boolean fitsMcv2(final Pair<Integer, Integer> blocks, final Pair<Integer, Integer> resolution) {
    final boolean maps = blocks.getFirst() <= Mcv2Configuration.MAX_SIDE && blocks.getSecond() <= Mcv2Configuration.MAX_SIDE;
    final boolean pixels = resolution.getFirst() <= Mcv2Format.MAX_DIMENSION && resolution.getSecond() <= Mcv2Format.MAX_DIMENSION;
    return maps && pixels;
  }

  /**
   * Gives a screen a slot of the pack, or tells the sender that every slot plays a screen. May be called from any
   * thread.
   *
   * @param sender        who started the screen
   * @param configuration the screen
   * @return the lease of the slot, or null if none is free
   */
  public Mcv2PackServer.@Nullable Lease open(final CommandSender sender, final Mcv2Configuration configuration) {
    try {
      return this.packs.open(configuration);
    } catch (final IllegalStateException full) {
      sender.sendMessage(Message.MCV2_FULL.build());
      return null;
    }
  }

  /**
   * Creates the output of an MCV2 screen: its result, which encodes the frames for the viewers with the pack and
   * dithers them for the others, in a slot of the pack. The sender learns when the screen steps down to what its
   * encoder budget sustains, and back up. May be called from any thread.
   *
   * @param sender        who started the screen
   * @param configuration the screen
   * @param dithering     how the frames are dithered for the viewers without the pack
   * @return the output, not started, or null if no slot of the pack is free
   */
  public @Nullable Mcv2Output output(final CommandSender sender, final Mcv2Configuration configuration, final DitheringArgument dithering) {
    final Mcv2PackServer.Lease lease = this.open(sender, configuration);
    if (lease == null) {
      return null;
    }
    final Mcv2Configuration slotted = lease.getConfiguration();
    final Mcv2Result result = new Mcv2Result(slotted, this.getViewers(), dithering.createAlgorithm());
    result.setPacingListener(change -> sender.sendMessage(Message.MCV2_PACING.build(change.describe())));
    // a smaller video before the dithered maps, in a slot of the pack of its own size
    result.setSmallerSizes(smallerSizes(slotted.getVideoWidth(), slotted.getVideoHeight()), lease);
    final String record = System.getProperty(RECORD_PROPERTY);
    if (record != null) {
      final String name = String.format(
        Locale.getDefault(Locale.Category.FORMAT),
        "screen-%d-%d.mcs",
        slotted.getStreamId(),
        System.currentTimeMillis()
      );
      result.setFrameListener(new FrameRecorder(Path.of(record).resolve(name)));
    }
    return new Mcv2Output(result, lease);
  }

  /**
   * The video sizes a screen may step down to when its encoder budget cannot sustain its own: two thirds and half of it,
   * even, while at least 128 by 72 pixels.
   *
   * @param width  the screen's video width
   * @param height the screen's video height
   * @return the smaller sizes, largest first
   */
  static List<int[]> smallerSizes(final int width, final int height) {
    final List<int[]> sizes = new ArrayList<>();
    for (final int[] fraction : SMALLER_FRACTIONS) {
      final int smallerWidth = ((width * fraction[0]) / fraction[1]) & ~1;
      final int smallerHeight = ((height * fraction[0]) / fraction[1]) & ~1;
      if (smallerWidth >= MIN_WIDTH && smallerHeight >= MIN_HEIGHT) {
        sizes.add(new int[] { smallerWidth, smallerHeight });
      }
    }
    return sizes;
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
}
