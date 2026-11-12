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
package me.brandonli.mcav.plugin.command.video;

import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2.Settings;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Configuration;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Decoder;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Pacer;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Pack;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2PackServer;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Result;
import me.brandonli.mcav.bukkit.media.mcv2.Mcv2Viewers;
import me.brandonli.mcav.bukkit.media.result.CompressedMapResult;
import me.brandonli.mcav.bukkit.resourcepack.provider.PackHosting;
import me.brandonli.mcav.media.player.pipeline.filter.video.FunctionalVideoFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import me.brandonli.mcav.plugin.command.MapDisplaySettings;
import me.brandonli.mcav.plugin.command.video.VideoMcv2Command.Mcv2Settings;
import me.brandonli.mcav.plugin.locale.Message;
import me.brandonli.mcav.plugin.utils.DitheringArgument;
import me.brandonli.mcav.utils.immutable.Pair;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
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
 *
 * <p>This class is not thread-safe: use it on the server main thread, except {@code open} and {@code output}, which may
 * be called from any thread.
 *
 * @since 1.0.0
 */
public final class Mcv2Support {

  /**
   * The system property that makes the pack also draw the first slot's decoded picture one to one in the top-left
   * corner, with the state of its pages and its decoder next to it, for testing the pack in-game.
   *
   * @since 1.0.0
   */
  public static final String DEBUG_VIEW_PROPERTY = "mcav.mcv2.debug_view";

  /**
   * The system property naming a folder that the frames every MCV2 screen started next sends are recorded into, one
   * stream file per screen, for a measurement that decodes them with the reference decoder; unset for none.
   *
   * @since 1.0.0
   */
  public static final String RECORD_PROPERTY = "mcav.sandbox.mcv2.record";

  private static final String NO_LIMIT = "none";

  private static final int NO_UNSENT_LIMIT = 0;

  // for measurements; unset keeps MCAV's default
  static final String PAGE_SLOTS_PROPERTY = "mcav.sandbox.mcv2.page_slots";
  static final String BACKLOG_PROPERTY = "mcav.sandbox.mcv2.backlog_limit";

  private static final String PACK_FOLDER = "pack";

  private static final int[][] SMALLER_FRACTIONS = { { 2, 3 }, { 1, 2 } };

  private static final int MINIMUM_WIDTH = 128;
  private static final int MINIMUM_HEIGHT = 72;

  private static final int EVEN_MASK = ~1;

  private static final int DEFAULT_PAGE_SLOTS = 0;

  private static final String PLACE_SEPARATOR = ", ";

  private final Mcv2PackServer packServer;

  /**
   * Creates the support, which serves nothing until {@link #start()}.
   *
   * @param dataFolder the plugin's data folder; the pack is written in {@code mcv2/pack} inside it
   * @param hosting    the function that hosts a written pack
   * @since 1.0.0
   */
  public Mcv2Support(final Path dataFolder, final Function<Path, PackHosting> hosting) {
    final Path streamFolder = dataFolder.resolve(Mcv2PlayCommand.STREAM_FOLDER);
    final Path packFolder = streamFolder.resolve(PACK_FOLDER);
    final boolean debug = Boolean.getBoolean(DEBUG_VIEW_PROPERTY);
    final Mcv2Pack.DebugView debugView = debug ? Mcv2Pack.DebugView.SHOWN : Mcv2Pack.DebugView.HIDDEN;
    final Mcv2PackServer packServer = new Mcv2PackServer(
      packFolder,
      hosting,
      debugView,
      Mcv2Support::tellOffered,
      Mcv2Support::tellRefused
    );
    this(packServer);
  }

  Mcv2Support(final Mcv2PackServer packServer) {
    Preconditions.checkNotNull(packServer, "Pack server must not be null!");

    this.packServer = packServer;
  }

  /**
   * Starts listening to the players for the pack. Call on the main thread.
   *
   * @since 1.0.0
   */
  public void start() {
    this.packServer.start();
  }

  /**
   * Stops serving the pack; the players keep what they loaded.
   *
   * @since 1.0.0
   */
  public void shutdown() {
    this.packServer.shutdown();
  }

  static void tellOffered(final Player player) {
    final Component message = Message.MCV2_PACK.build();
    player.sendMessage(message);
  }

  static void tellRefused(final Player player) {
    final Component message = Message.MCV2_REFUSED.build();
    player.sendMessage(message);
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
   * @param settings   the encoder settings
   * @param viewers    the players who watch
   * @return the configuration, or empty if the screen is too large for MCV2, or no frame or more than one frame
   *         holds the map
   * @since 1.0.0
   */
  public Optional<Mcv2Configuration> configure(
    final CommandSender sender,
    final Pair<Integer, Integer> blocks,
    final Pair<Integer, Integer> resolution,
    final int mapId,
    final Settings settings,
    final Collection<UUID> viewers
  ) {
    // the library refuses such a screen with an exception, which no command expects
    if (!this.isWithinMcv2Limits(blocks, resolution)) {
      final Component message = Message.MCV2_SIZE_ERROR.build();
      sender.sendMessage(message);
      return Optional.empty();
    }

    final List<World> worlds = Bukkit.getWorlds();
    final List<ItemFrame> frames = findFrames(worlds, mapId);
    if (frames.isEmpty()) {
      final Component message = Message.MCV2_SCREEN_ERROR.build(mapId);
      sender.sendMessage(message);
      return Optional.empty();
    }

    if (frames.size() > 1) {
      final String reason = this.describeDuplicates(mapId, frames);
      final Component message = Message.MCV2_SCREEN_DUPLICATE.build(reason);
      sender.sendMessage(message);
      return Optional.empty();
    }

    final ItemFrame frame = frames.getFirst();
    final int pageSlots = Integer.getInteger(PAGE_SLOTS_PROPERTY, DEFAULT_PAGE_SLOTS);
    final Location location = frame.getLocation();
    final BlockFace facing = frame.getFacing();
    final int columns = blocks.getFirst();
    final int rows = blocks.getSecond();
    final int videoWidth = resolution.getFirst();
    final int videoHeight = resolution.getSecond();
    final Mcv2Configuration configuration = Mcv2Configuration.builder()
      .viewers(viewers)
      .origin(location)
      .facing(facing)
      .map(mapId)
      .columns(columns)
      .rows(rows)
      .video(videoWidth, videoHeight)
      .settings(settings)
      .pageSlots(pageSlots)
      .backlogLimit(this.readBacklogLimit())
      .unsentLimit(this.readUnsentLimit())
      .build();
    return Optional.of(configuration);
  }

  private String describeDuplicates(final int mapId, final List<ItemFrame> frames) {
    final int count = frames.size();
    final List<String> places = frames.stream().map(this::describePlace).toList();
    final String joined = String.join(PLACE_SEPARATOR, places);
    return "map %s hangs in %s item frames, at %s. MCV2 plays on one wall per map, so remove the other walls or build each screen with its own map id!".formatted(
      mapId,
      count,
      joined
    );
  }

  private String describePlace(final ItemFrame frame) {
    final World world = frame.getWorld();
    final String worldName = world.getName();
    final Location location = frame.getLocation();
    final int frameX = location.getBlockX();
    final int frameY = location.getBlockY();
    final int frameZ = location.getBlockZ();
    return "%s %s %s %s".formatted(worldName, frameX, frameY, frameZ);
  }

  long readBacklogLimit() {
    final @Nullable String value = System.getProperty(BACKLOG_PROPERTY);
    if (value == null) {
      return Mcv2Configuration.DEFAULT_BACKLOG_LIMIT;
    }

    if (this.isBacklogUnlimited(value)) {
      return Long.MAX_VALUE;
    }

    return Long.parseLong(value);
  }

  private boolean isBacklogUnlimited(final @Nullable String value) {
    return NO_LIMIT.equals(value);
  }

  // none turns backpressure off, for measurements
  int readUnsentLimit() {
    final @Nullable String value = System.getProperty(BACKLOG_PROPERTY);
    return this.isBacklogUnlimited(value) ? NO_UNSENT_LIMIT : Mcv2Configuration.DEFAULT_UNSENT_LIMIT;
  }

  private boolean isWithinMcv2Limits(final Pair<Integer, Integer> blocks, final Pair<Integer, Integer> resolution) {
    final int columns = blocks.getFirst();
    final int rows = blocks.getSecond();
    final boolean mapsFit = columns <= Mcv2Configuration.MAXIMUM_SIDE && rows <= Mcv2Configuration.MAXIMUM_SIDE;
    final int videoWidth = resolution.getFirst();
    final int videoHeight = resolution.getSecond();
    final boolean pixelsFit = videoWidth <= Mcv2Decoder.MAXIMUM_DIMENSION && videoHeight <= Mcv2Decoder.MAXIMUM_DIMENSION;
    return mapsFit && pixelsFit;
  }

  /**
   * Gives a screen a slot of the pack, or tells the sender that every slot plays a screen. May be called from any
   * thread.
   *
   * @param sender        who started the screen
   * @param configuration the screen
   * @return the lease of the slot, or empty if none is free
   * @since 1.0.0
   */
  public Optional<Mcv2PackServer.Lease> open(final CommandSender sender, final Mcv2Configuration configuration) {
    try {
      final Mcv2PackServer.Lease lease = this.packServer.open(configuration);
      return Optional.of(lease);
    } catch (final IllegalStateException exception) {
      final Component message = Message.MCV2_FULL.build();
      sender.sendMessage(message);
      return Optional.empty();
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
   * @return the output, not started, or empty if no slot of the pack is free
   * @since 1.0.0
   */
  public Optional<Mcv2Output> output(final CommandSender sender, final Mcv2Configuration configuration, final DitheringArgument dithering) {
    final Optional<Mcv2PackServer.Lease> foundLease = this.open(sender, configuration);
    final Mcv2PackServer.@Nullable Lease lease = foundLease.orElse(null);
    if (lease == null) {
      return Optional.empty();
    }

    final Mcv2Configuration slotted = lease.getConfiguration();
    final Mcv2Viewers viewers = this.getViewers();
    final DitherAlgorithm algorithm = dithering.createAlgorithm();
    final Mcv2Result result = new Mcv2Result(slotted, viewers, algorithm);
    result.setPacingListener(change -> this.tellPacing(sender, change));
    final int videoWidth = slotted.getVideoWidth();
    final int videoHeight = slotted.getVideoHeight();
    final List<int[]> sizes = this.computeSmallerSizes(videoWidth, videoHeight);
    result.setSmallerSizes(sizes, lease);

    final @Nullable String recordFolder = System.getProperty(RECORD_PROPERTY);
    if (recordFolder != null) {
      final long streamId = slotted.getStreamId();
      final long startMilliseconds = System.currentTimeMillis();
      final String name = "screen-%s-%s.mcs".formatted(streamId, startMilliseconds);
      final Path folder = Path.of(recordFolder);
      final Path recordFile = folder.resolve(name);
      final FrameRecorder recorder = new FrameRecorder(recordFile);
      result.setFrameListener(recorder);
    }

    final Mcv2Output output = new Mcv2Output(result, lease);
    return Optional.of(output);
  }

  private void tellPacing(final CommandSender sender, final Mcv2Pacer.Change change) {
    final String description = change.describe();
    final Component message = Message.MCV2_PACING.build(description);
    sender.sendMessage(message);
  }

  // when every pack slot plays a screen, falls back to the dithered maps of the same wall
  FunctionalVideoFilter createMcv2Filter(final Mcv2Settings settings) {
    final Mcv2Configuration configuration = settings.configuration();
    final CommandSender sender = settings.sender();
    final DitheringArgument dithering = settings.dithering();
    final Optional<Mcv2Output> foundOutput = this.output(sender, configuration, dithering);
    final @Nullable Mcv2Output output = foundOutput.orElse(null);
    if (output != null) {
      return output;
    }

    final int columns = configuration.getColumns();
    final int rows = configuration.getRows();
    final Pair<Integer, Integer> blocks = Pair.pair(columns, rows);
    final int videoWidth = configuration.getVideoWidth();
    final int videoHeight = configuration.getVideoHeight();
    final Pair<Integer, Integer> resolution = Pair.pair(videoWidth, videoHeight);
    final int mapId = configuration.getMap();
    final Collection<UUID> viewers = configuration.getViewers();
    final MapConfiguration mapConfiguration = MapDisplaySettings.createConfiguration(blocks, resolution, mapId, viewers);
    final DitherAlgorithm algorithm = dithering.createAlgorithm();
    final CompressedMapResult result = new CompressedMapResult(mapConfiguration);
    return DitherFilter.dither(algorithm, result);
  }

  List<int[]> computeSmallerSizes(final int width, final int height) {
    final List<int[]> sizes = new ArrayList<>();
    for (final int[] fraction : SMALLER_FRACTIONS) {
      final int smallerWidth = this.scaleToEven(width, fraction);
      final int smallerHeight = this.scaleToEven(height, fraction);
      if (smallerWidth >= MINIMUM_WIDTH && smallerHeight >= MINIMUM_HEIGHT) {
        sizes.add(new int[] { smallerWidth, smallerHeight });
      }
    }

    return sizes;
  }

  private int scaleToEven(final int side, final int[] fraction) {
    return ((side * fraction[0]) / fraction[1]) & EVEN_MASK;
  }

  /**
   * Finds every frame holding the top-left map of a screen built with {@code /mcav screen}. Only the frames of
   * loaded chunks are found. Call on the main thread.
   *
   * @param worlds the worlds to search
   * @param mapId  the id of the top-left map
   * @return the frames in the order of the worlds and of their entities, or an empty list if no frame holds that map
   * @since 1.0.0
   */
  public static List<ItemFrame> findFrames(final Collection<World> worlds, final int mapId) {
    final List<ItemFrame> found = new ArrayList<>();
    for (final World world : worlds) {
      final List<ItemFrame> frames = findFramesInWorld(world, mapId);
      found.addAll(frames);
    }

    return found;
  }

  private static List<ItemFrame> findFramesInWorld(final World world, final int mapId) {
    final Collection<ItemFrame> frames = world.getEntitiesByClass(ItemFrame.class);
    return frames
      .stream()
      .filter(frame -> holdsMap(frame.getItem(), mapId))
      .toList();
  }

  static boolean holdsMap(final ItemStack item, final int mapId) {
    if (item.getType() != Material.FILLED_MAP) {
      return false;
    }

    final ItemMeta metadata = item.getItemMeta();
    return metadata instanceof final MapMeta mapMetadata && mapMetadata.hasMapId() && mapMetadata.getMapId() == mapId;
  }

  /**
   * Returns who loaded the pack.
   *
   * @return the viewers every screen shares
   * @since 1.0.0
   */
  public Mcv2Viewers getViewers() {
    return this.packServer.getViewers();
  }
}
