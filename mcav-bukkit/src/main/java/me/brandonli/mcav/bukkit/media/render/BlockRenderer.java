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

import com.google.common.base.Preconditions;
import io.papermc.paper.math.Position;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import me.brandonli.mcav.bukkit.media.config.BlockConfiguration;
import me.brandonli.mcav.bukkit.media.lookup.BlockPaletteLookup;
import me.brandonli.mcav.bukkit.utils.MainThreadRenderer;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.error.FilterLiteDither;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Renders images as a wall of colored blocks using fake block changes, so the world itself is never modified.
 *
 * <p>Every pixel becomes one block. The wall stands upright, is centered horizontally on the configured position,
 * and grows upward from it. Images are dithered to the colors of the block palette, see
 * {@link BlockPaletteLookup}.
 *
 * <p>Viewers who already see the wall only receive the blocks that changed since the previous frame, in a single
 * multi block change. Fake blocks only live on the client and disappear whenever the client reloads a chunk, for
 * example after walking out of view distance and back, or after clicking one of them. So the renderer tracks its
 * viewers like this:
 *
 * <ul>
 *   <li>Viewers who start watching, because they joined the server or were added to the viewers, receive the
 *       complete wall during the next server tick.</li>
 *   <li>Every 20 ticks, which is once per second on a server running at full speed, every viewer receives the
 *       complete wall again, even while the picture does not change.</li>
 *   <li>Viewers who are removed from the viewers while they are online receive the original blocks again.</li>
 * </ul>
 *
 * <p>The original blocks are captured when the renderer is shown and sent again when it is hidden, which makes the
 * wall disappear for the viewers. The block state of this renderer is only accessed on the main thread.
 *
 * <p>Call {@link #show()} before submitting frames and {@link #hide()} when finished. Conversion reads and
 * may resize the caller-owned image synchronously; the scheduled task retains only converted frame data. At most
 * the latest submitted frame is applied per tick, so intermediate frames may be dropped. Do not concurrently
 * write to the same input buffer. Hiding clears pending work; the renderer can subsequently be shown again.
 *
 * @since 1.0.0
 */
public final class BlockRenderer extends MainThreadRenderer<BlockData[]> {

  static final int FULL_RESEND_INTERVAL_TICKS = 20;

  private static final int CENTERING_DIVISOR = 2;

  private final BlockConfiguration configuration;
  private final Set<UUID> activeViewers;

  private Position @Nullable [] positions;
  // the world of the wall, by id: a block change carries no world, so a viewer elsewhere would get the wall there
  private @Nullable UUID worldId;
  private BlockData @Nullable [] originalBlocks;
  private BlockData @Nullable [] displayedBlocks;
  private int ticksSinceFullResend;

  /**
   * Creates a new block renderer. Nothing is shown until {@link #show()} is called.
   *
   * @param configuration the configuration describing the viewers, position, and size of the block wall
   * @throws NullPointerException if the configuration is null
   * @since 1.0.0
   */
  public BlockRenderer(final BlockConfiguration configuration) {
    Preconditions.checkNotNull(configuration, "Configuration must not be null!");

    this.configuration = configuration;
    this.activeViewers = new HashSet<>();
  }

  /**
   * Remembers the blocks currently at the location of the wall, so they can be restored later, and starts
   * rendering frames. May be called from any thread.
   *
   * @throws IllegalStateException if no plugin was injected, or if initialization runs on the calling thread
   *                               and the configured position no longer has a world
   * @since 1.0.0
   */
  public void show() {
    this.showDisplay(this::captureBlocks);
  }

  /**
   * Converts the image into blocks and schedules them for display. May be called from any thread.
   *
   * @param image the image to render, which is resized to the wall dimensions in place
   * @throws NullPointerException  if the image is null
   * @throws IllegalStateException if the image has been released
   * @since 1.0.0
   */
  public void render(final ImageBuffer image) {
    Preconditions.checkNotNull(image, "Image must not be null!");

    final int width = this.configuration.getBlockWidth();
    final int height = this.configuration.getBlockHeight();
    final ResizeFilter resize = new ResizeFilter(width, height);
    resize.applyFilter(image);

    final int[] sourcePixels = image.getPixels();
    final int[] pixels = sourcePixels.clone();
    final FilterLiteDither dither = BlockPaletteLookup.getDither();
    dither.dither(pixels, width);

    final BlockData[] blocks = new BlockData[pixels.length];
    for (int index = 0; index < pixels.length; index++) {
      final int color = pixels[index];
      blocks[index] = BlockPaletteLookup.getBlockData(color);
    }

    this.submit(blocks);
  }

  /**
   * Stops rendering frames and shows the original blocks to the viewers again. May be called from any thread, but
   * call it on the main thread during shutdown, because a disabled plugin cannot schedule the restore anymore.
   *
   * @since 1.0.0
   */
  public void hide() {
    this.hideDisplay(this::restoreBlocks);
  }

  private void captureBlocks() {
    if (this.positions != null) {
      return;
    }

    final Location origin = this.configuration.getPosition();
    final World world = origin.getWorld();
    if (world == null) {
      final String message = "Block position has no world!";
      throw new IllegalStateException(message);
    }

    final int blockCount = this.getBlockCount();
    final Position[] capturedPositions = new Position[blockCount];
    final BlockData[] capturedBlocks = new BlockData[blockCount];
    final int originX = origin.getBlockX();
    final int originY = origin.getBlockY();
    final int originZ = origin.getBlockZ();
    for (int index = 0; index < blockCount; index++) {
      final int blockX = this.getBlockX(originX, index);
      final int blockY = this.getBlockY(originY, index);
      capturedPositions[index] = Position.block(blockX, blockY, originZ);
      capturedBlocks[index] = world.getBlockData(blockX, blockY, originZ);
    }

    this.positions = capturedPositions;
    this.worldId = world.getUID();
    this.originalBlocks = capturedBlocks;
    this.displayedBlocks = null;
  }

  private boolean isInWallWorld(final Player player) {
    return player.getWorld().getUID().equals(this.worldId);
  }

  private int getBlockCount() {
    final int width = this.configuration.getBlockWidth();
    final int height = this.configuration.getBlockHeight();
    return width * height;
  }

  private int getBlockX(final int originX, final int index) {
    final int width = this.configuration.getBlockWidth();
    final int leftX = originX - width / CENTERING_DIVISOR;
    final int column = index % width;
    return leftX + column;
  }

  private int getBlockY(final int originY, final int index) {
    final int width = this.configuration.getBlockWidth();
    final int height = this.configuration.getBlockHeight();
    final int row = index / width;
    return originY + (height - 1 - row);
  }

  /**
   * Applies a frame by sending the blocks that changed since the previous frame to the viewers who already see the
   * wall, in one multi block change. This method is always called on the main thread, at most once per server tick.
   *
   * <p>Nothing is sent while the wall is not shown or when no block changed. New viewers get the whole wall from
   * {@link #onTick()}, which runs next.
   *
   * @param blocks the blocks of the frame, one for each wall position
   * @since 1.0.0
   */
  @Override
  protected void apply(final BlockData[] blocks) {
    final Position[] currentPositions = this.positions;
    if (currentPositions == null) {
      return;
    }

    final BlockData[] previousBlocks = this.displayedBlocks;
    final Map<Position, BlockData> changes = this.collectChanges(currentPositions, previousBlocks, blocks);
    this.displayedBlocks = blocks;

    if (changes.isEmpty()) {
      return;
    }

    this.sendToActiveViewers(changes);
  }

  private Map<Position, BlockData> collectChanges(
    final Position[] positions,
    final BlockData @Nullable [] previousBlocks,
    final BlockData[] blocks
  ) {
    final Map<Position, BlockData> changes = new HashMap<>();
    for (int index = 0; index < blocks.length; index++) {
      final BlockData block = blocks[index];
      final boolean changed = previousBlocks == null || !previousBlocks[index].equals(block);
      if (changed) {
        final Position position = positions[index];
        changes.put(position, block);
      }
    }

    return changes;
  }

  private void sendToActiveViewers(final Map<Position, BlockData> changes) {
    final Collection<UUID> viewers = this.configuration.getViewers();
    for (final UUID viewer : viewers) {
      final boolean watching = this.activeViewers.contains(viewer);
      final Player player = Bukkit.getPlayer(viewer);
      if (watching && player != null && this.isInWallWorld(player)) {
        player.sendMultiBlockChange(changes);
      }
    }
  }

  /**
   * Sends the complete wall to viewers who started watching, and to every viewer every 20 ticks, then sends the
   * original blocks back to viewers who were removed while online. This method runs on the main thread once per
   * server tick while the renderer is running, right after the pending frame, if there was one, was applied.
   *
   * <p>Does nothing while the wall is not shown or before its first frame was applied.
   *
   * @since 1.0.0
   */
  @Override
  protected void onTick() {
    final Position[] currentPositions = this.positions;
    final BlockData[] blocks = this.displayedBlocks;
    if (currentPositions == null || blocks == null) {
      return;
    }

    final WallRecipients recipients = this.advanceResendCounter();
    final Set<UUID> watchingViewers = new HashSet<>();
    final List<Player> playersWithoutWall = this.updateActiveViewers(watchingViewers, recipients);
    if (!playersWithoutWall.isEmpty()) {
      final Map<Position, BlockData> wall = this.createBlockMap(currentPositions, blocks);
      for (final Player player : playersWithoutWall) {
        player.sendMultiBlockChange(wall);
      }
    }

    this.restoreRemovedViewers(currentPositions, watchingViewers);
  }

  private WallRecipients advanceResendCounter() {
    this.ticksSinceFullResend++;
    if (this.ticksSinceFullResend < FULL_RESEND_INTERVAL_TICKS) {
      return WallRecipients.NEW_VIEWERS;
    }

    this.ticksSinceFullResend = 0;

    return WallRecipients.EVERY_VIEWER;
  }

  private List<Player> updateActiveViewers(final Set<UUID> watchingViewers, final WallRecipients recipients) {
    final List<Player> playersWithoutWall = new ArrayList<>();
    final Collection<UUID> viewers = this.configuration.getViewers();
    for (final UUID viewer : viewers) {
      final Player player = Bukkit.getPlayer(viewer);
      if (player == null || !this.isInWallWorld(player)) {
        continue;
      }
      watchingViewers.add(viewer);
      final boolean startedWatching = this.activeViewers.add(viewer);
      if (startedWatching || recipients == WallRecipients.EVERY_VIEWER) {
        playersWithoutWall.add(player);
      }
    }

    return playersWithoutWall;
  }

  private void restoreRemovedViewers(final Position[] currentPositions, final Set<UUID> watchingViewers) {
    final List<UUID> removedViewers = new ArrayList<>(this.activeViewers);
    removedViewers.removeAll(watchingViewers);

    if (removedViewers.isEmpty()) {
      return;
    }

    this.activeViewers.retainAll(watchingViewers);
    final Map<Position, BlockData> restored = this.createOriginalBlockMap(currentPositions);
    this.sendToPlayersInWallWorld(removedViewers, restored);
  }

  private void restoreBlocks() {
    final Position[] currentPositions = this.positions;
    if (currentPositions == null) {
      return;
    }

    final Map<Position, BlockData> restored = this.createOriginalBlockMap(currentPositions);
    final Collection<UUID> configured = this.configuration.getViewers();
    final Set<UUID> viewers = new HashSet<>(configured);
    // A viewer can leave the configuration immediately before hide, without another render tick.
    viewers.addAll(this.activeViewers);
    this.sendToPlayersInWallWorld(viewers, restored);

    this.positions = null;
    this.worldId = null;
    this.originalBlocks = null;
    this.displayedBlocks = null;
    this.activeViewers.clear();
    this.ticksSinceFullResend = 0;
  }

  private Map<Position, BlockData> createOriginalBlockMap(final Position[] currentPositions) {
    final BlockData[] original = Objects.requireNonNull(this.originalBlocks, "Original blocks were not captured!");
    return this.createBlockMap(currentPositions, original);
  }

  // a player in another world has nothing of the wall to restore; their client dropped this world's blocks when they left
  private void sendToPlayersInWallWorld(final Collection<UUID> players, final Map<Position, BlockData> blocks) {
    for (final UUID uuid : players) {
      final Player player = Bukkit.getPlayer(uuid);
      if (player != null && this.isInWallWorld(player)) {
        player.sendMultiBlockChange(blocks);
      }
    }
  }

  private Map<Position, BlockData> createBlockMap(final Position[] positions, final BlockData[] blocks) {
    final Map<Position, BlockData> map = new HashMap<>();
    for (int index = 0; index < positions.length; index++) {
      final Position position = positions[index];
      final BlockData block = blocks[index];
      map.put(position, block);
    }

    return map;
  }

  private enum WallRecipients {
    NEW_VIEWERS,
    EVERY_VIEWER,
  }
}
