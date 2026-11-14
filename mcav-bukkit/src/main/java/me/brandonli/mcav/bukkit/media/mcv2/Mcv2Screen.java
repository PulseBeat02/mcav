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
package me.brandonli.mcav.bukkit.media.mcv2;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import me.brandonli.mcav.bukkit.BukkitModule;
import me.brandonli.mcav.bukkit.media.map.MapLayout;
import me.brandonli.mcav.bukkit.media.map.MapPacketFactory;
import me.brandonli.mcav.bukkit.media.map.MapTilePatch;
import me.brandonli.mcav.bukkit.media.mcv2.transport.MapAlphabet;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * What the MCV2 shader needs in the world, per player: anchors in the wall's maps, and hidden glowing frames behind the
 * wall that hold the page maps.
 *
 * <p>Behind every map of the wall hangs one item frame, facing away from the wall, holding page map
 * {@code (row * columns + column) % pageSlots}, cycling over the complete wall in row order. The frames
 * are real entities that nobody sees until {@link #show(Player)} shows them to a player with the pack; they glow,
 * because the client only runs the pack's post chain while a glowing entity is drawn, and the player is sent a team
 * that gives them the configured outline color, which the pack filters out. The frames are not saved with the world,
 * so the screen keeps their chunks loaded until it is removed: a chunk that unloads, because every player left, would
 * take the frames with it, and a player who comes back would find nothing to show.
 *
 * <p>An anchor is the first row of a wall map: a signature, the map's column and row, the wall's size, the direction of
 * the maps' right edge and the screen's stream id, which tells the pack which of its screens the wall is. The server
 * sends every wall map as it stores it to players who start tracking the frame, which erases the anchors, so they are
 * sent again with every keyframe.
 *
 * <p>The visible wall and its map items remain caller-owned. This object owns only its hidden page entities
 * and chunk holds. Use {@link #build()}, {@link #show(Player)}, {@link #hide(Player)} and {@link #remove()} on the
 * main thread; do not manipulate or remove entities obtained from {@link #getFrames()} independently. Removal releases
 * chunk holds but does not restore wall map pixels.
 *
 * @since 1.0.0
 */
public final class Mcv2Screen {

  /**
   * The prefix of the per-screen team names of the page frames.
   *
   * @since 1.0.0
   */
  public static final String TEAM_PREFIX = "mcav_mcv2";

  private static final String PLAYER_NULL_MESSAGE = "Player must not be null!";

  private static final int[] SIGNATURE = { 21, 3, 58, 44, 9, 37, 60, 17 };

  private static final int CHUNK_SHIFT = 4;

  private static final int BLOCKS_BEHIND = 2;

  private static final int ROW_SYMBOL = 1;
  private static final int COLUMNS_SYMBOL = 2;
  private static final int ROWS_SYMBOL = 3;
  private static final int FACING_SYMBOL = 4;
  private static final int STREAM_LOW_SYMBOL = 5;
  private static final int STREAM_HIGH_SYMBOL = 6;
  private static final int CHECKSUM_SYMBOL = 7;

  private static final int ANCHOR_LEFT = 0;
  private static final int ANCHOR_TOP = 0;
  private static final int ANCHOR_ROWS = 1;

  // Bukkit holds one plugin ticket per chunk, so overlapping screens must share ownership.
  private static final Map<HeldChunk, Integer> HELD_CHUNKS = new HashMap<>();

  private static final AtomicInteger NEXT_TEAM_ID = new AtomicInteger();

  private final String teamName;

  private final Mcv2Configuration configuration;

  private final List<ItemFrame> frames;

  private final Set<HeldChunk> chunks;

  /**
   * Creates a screen.
   *
   * @param configuration the configuration of the screen
   * @throws NullPointerException if {@code configuration} is null
   * @since 1.0.0
   */
  public Mcv2Screen(final Mcv2Configuration configuration) {
    Preconditions.checkNotNull(configuration, "Configuration must not be null!");

    final int teamId = NEXT_TEAM_ID.getAndIncrement();
    final String teamIdText = Integer.toUnsignedString(teamId, Character.MAX_RADIX);
    this.configuration = configuration;
    this.teamName = "%s%s".formatted(TEAM_PREFIX, teamIdText);
    this.frames = new ArrayList<>();
    this.chunks = new LinkedHashSet<>();
  }

  static int slot(final int column, final int row, final int columns, final int slots) {
    return (row * columns + column) % slots;
  }

  /**
   * Spawns the hidden page frames behind the wall, and keeps their chunks loaded until {@link #remove()}. Call on the
   * main thread.
   *
   * @throws IllegalStateException if the frames were already spawned or a required plugin has not been injected
   * @throws NullPointerException  if the origin no longer resolves to a world
   * @since 1.0.0
   */
  public void build() {
    Preconditions.checkState(this.frames.isEmpty(), "The page frames already exist!");

    final Location origin = this.configuration.getOrigin();
    final World world = Preconditions.checkNotNull(origin.getWorld(), "The origin must be in a world!");
    final BlockFace right = this.configuration.getRight();
    final BlockFace back = this.configuration.getFacing().getOppositeFace();
    for (int row = 0; row < this.configuration.getRows(); row++) {
      for (int column = 0; column < this.configuration.getColumns(); column++) {
        final Location behind = origin
          .clone()
          .add(right.getModX() * column + back.getModX() * BLOCKS_BEHIND, -row, right.getModZ() * column + back.getModZ() * BLOCKS_BEHIND);
        final int slot = slot(column, row, this.configuration.getColumns(), this.configuration.getPageSlots());
        final ItemStack page = pageItem(this.configuration.getPageMap() + slot);
        this.holdChunkAt(world, behind);
        final ItemFrame frame = world.spawn(behind, ItemFrame.class, spawned -> this.preparePageFrame(spawned, back, page));
        this.frames.add(frame);
      }
    }
  }

  private void holdChunkAt(final World world, final Location location) {
    final HeldChunk chunk = new HeldChunk(world, location.getBlockX() >> CHUNK_SHIFT, location.getBlockZ() >> CHUNK_SHIFT);
    if (this.chunks.add(chunk)) {
      this.hold(chunk);
    }
  }

  private void preparePageFrame(final ItemFrame frame, final BlockFace back, final ItemStack page) {
    frame.setVisibleByDefault(false);
    frame.setPersistent(false);
    frame.setFacingDirection(back, true);
    frame.setItem(page, false);
    frame.setGlowing(true);
    frame.setInvulnerable(true);
    frame.setFixed(true);
    frame.setSilent(true);
  }

  static ItemStack pageItem(final int mapId) {
    final net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(Items.FILLED_MAP); // fqn: Minecraft's ItemStack beside the imported Bukkit one
    stack.set(DataComponents.MAP_ID, new MapId(mapId));
    return CraftItemStack.asBukkitCopy(stack);
  }

  /**
   * Shows the screen to a player with the pack: the page frames, their team and the anchors. Call on the main thread.
   *
   * @param player the player
   * @throws IllegalStateException if no plugin has been injected
   * @throws NullPointerException  if {@code player} is null
   * @since 1.0.0
   */
  public void show(final Player player) {
    Preconditions.checkNotNull(player, PLAYER_NULL_MESSAGE);

    final Plugin plugin = BukkitModule.getPlugin();
    for (final ItemFrame frame : this.frames) {
      player.showEntity(plugin, frame);
    }

    final List<UUID> viewers = List.of(player.getUniqueId());
    PacketUtils.sendPackets(viewers, ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(this.createTeam(), true));
    MapPacketFactory.send(viewers, this.getAnchors());
  }

  /**
   * Hides the page frames from a player again and removes their team. Call on the main thread.
   *
   * @param player the player
   * @throws IllegalStateException if no plugin has been injected
   * @throws NullPointerException  if {@code player} is null
   * @since 1.0.0
   */
  public void hide(final Player player) {
    Preconditions.checkNotNull(player, PLAYER_NULL_MESSAGE);

    final Plugin plugin = BukkitModule.getPlugin();
    for (final ItemFrame frame : this.frames) {
      player.hideEntity(plugin, frame);
    }

    PacketUtils.sendPackets(List.of(player.getUniqueId()), ClientboundSetPlayerTeamPacket.createRemovePacket(this.createTeam()));
  }

  /**
   * Removes the page frames, and lets their chunks unload. Call on the main thread.
   *
   * @since 1.0.0
   */
  public void remove() {
    this.frames.forEach(Entity::remove);
    this.frames.clear();
    this.chunks.forEach(this::release);
    this.chunks.clear();
  }

  private void hold(final HeldChunk chunk) {
    final int holders = HELD_CHUNKS.getOrDefault(chunk, 0) + 1;
    HELD_CHUNKS.put(chunk, holders);

    if (holders == 1) {
      chunk.world().addPluginChunkTicket(chunk.chunkX(), chunk.chunkZ(), BukkitModule.getPlugin());
    }
  }

  private void release(final HeldChunk chunk) {
    final Integer holders = HELD_CHUNKS.computeIfPresent(chunk, (_, count) -> count == 1 ? null : count - 1);
    if (holders == null) {
      chunk.world().removePluginChunkTicket(chunk.chunkX(), chunk.chunkZ(), BukkitModule.getPlugin());
    }
  }

  PlayerTeam createTeam() {
    final PlayerTeam team = new PlayerTeam(new Scoreboard(), this.teamName);
    final String name = NamedTextColor.NAMES.keyOrThrow(this.configuration.getOutlineColor());
    team.setColor(Optional.of(TeamColor.valueOf(name.toUpperCase(Locale.ROOT))));
    final Collection<String> members = team.getPlayers();
    for (final ItemFrame frame : this.frames) {
      members.add(frame.getUniqueId().toString());
    }

    return team;
  }

  /**
   * Returns the page frames.
   *
   * @return an unmodifiable list snapshot in wall order, containing live screen-owned entity references
   * @since 1.0.0
   */
  public List<ItemFrame> getFrames() {
    return List.copyOf(this.frames);
  }

  /**
   * Returns the anchor row of every wall map.
   *
   * @return one patch per map, row 0 only
   * @since 1.0.0
   */
  public List<MapTilePatch> getAnchors() {
    final int columns = this.configuration.getColumns();
    final int rows = this.configuration.getRows();
    final int facing = this.configuration.getFacingCode();
    final int streamLow = (int) (this.configuration.getStreamId() % MapAlphabet.SIZE);
    final int streamHigh = (int) (this.configuration.getStreamId() / MapAlphabet.SIZE);
    final List<MapTilePatch> patches = new ArrayList<>(columns * rows);
    for (int row = 0; row < rows; row++) {
      for (int column = 0; column < columns; column++) {
        final byte[] symbols = this.signedSymbols();
        final int symbolOffset = SIGNATURE.length;
        symbols[symbolOffset] = (byte) column;
        symbols[symbolOffset + ROW_SYMBOL] = (byte) row;
        symbols[symbolOffset + COLUMNS_SYMBOL] = (byte) columns;
        symbols[symbolOffset + ROWS_SYMBOL] = (byte) rows;
        symbols[symbolOffset + FACING_SYMBOL] = (byte) facing;
        symbols[symbolOffset + STREAM_LOW_SYMBOL] = (byte) streamLow;
        symbols[symbolOffset + STREAM_HIGH_SYMBOL] = (byte) streamHigh;

        final int checksum = (column + row + columns + rows + facing + streamLow + streamHigh) % MapAlphabet.SIZE;
        symbols[symbolOffset + CHECKSUM_SYMBOL] = (byte) checksum;
        final int mapId = this.configuration.getMap() + row * columns + column;
        patches.add(MapTilePatch.of(mapId, ANCHOR_LEFT, ANCHOR_TOP, MapLayout.MAP_SIZE, ANCHOR_ROWS, MapAlphabet.toMapColors(symbols)));
      }
    }

    return patches;
  }

  private byte[] signedSymbols() {
    final byte[] symbols = new byte[MapLayout.MAP_SIZE];
    for (int index = 0; index < SIGNATURE.length; index++) {
      symbols[index] = (byte) SIGNATURE[index];
    }

    return symbols;
  }

  private record HeldChunk(World world, int chunkX, int chunkZ) {}
}
