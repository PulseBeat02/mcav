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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import me.brandonli.mcav.bukkit.BukkitModule;
import me.brandonli.mcav.bukkit.media.map.MapLayout;
import me.brandonli.mcav.bukkit.media.map.MapPacketFactory;
import me.brandonli.mcav.bukkit.media.map.MapTilePatch;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import me.brandonli.mcav.media.mcv2.transport.MapAlphabet;
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
 * {@code (column + row) % pageSlots}, so any run of page-slot many maps of a row has every page behind it. The frames
 * are real entities that nobody sees until {@link #show(Player)} shows them to a player with the pack; they glow,
 * because the client only runs the pack's post chain while a glowing entity is drawn, and the player is sent a team
 * that gives them the configured outline colour, which the pack filters out. The frames are not saved with the world.
 *
 * <p>An anchor is the first row of a wall map: a signature, the map's column and row, the wall's size and the
 * direction of the maps' right edge. The server sends every wall map as it stores it to players who start tracking the
 * frame, which erases the anchors, so they are sent again with every keyframe.
 */
public final class Mcv2Screen {

  /** The name of the team of the page frames. */
  public static final String TEAM = "mcav_mcv2";

  private static final int[] SIGNATURE = { 21, 3, 58, 44, 9, 37, 60, 17 };

  private final Mcv2Configuration configuration;
  private final List<ItemFrame> frames;

  /**
   * Constructs a new screen.
   *
   * @param configuration the screen
   */
  public Mcv2Screen(final Mcv2Configuration configuration) {
    Preconditions.checkNotNull(configuration, "Configuration must not be null");
    this.configuration = configuration;
    this.frames = new ArrayList<>();
  }

  /**
   * The page slot behind a map of the wall.
   *
   * @param column the map's column
   * @param row    the map's row
   * @param slots  the page slots
   * @return the slot
   */
  static int slot(final int column, final int row, final int slots) {
    return (column + row) % slots;
  }

  /**
   * Spawns the hidden page frames behind the wall. Call on the main thread.
   *
   * @throws IllegalStateException if the frames were already spawned
   */
  public void build() {
    Preconditions.checkState(this.frames.isEmpty(), "The page frames already exist");
    final Location origin = this.configuration.getOrigin();
    final World world = Preconditions.checkNotNull(origin.getWorld(), "The origin must be in a world");
    final BlockFace right = this.configuration.getRight();
    final BlockFace back = this.configuration.getFacing().getOppositeFace();
    for (int row = 0; row < this.configuration.getRows(); row++) {
      for (int column = 0; column < this.configuration.getColumns(); column++) {
        // two blocks behind the frame's block: past the wall block the frame hangs on
        final Location behind = origin
          .clone()
          .add(right.getModX() * column + back.getModX() * 2, -row, right.getModZ() * column + back.getModZ() * 2);
        final ItemStack page = pageItem(this.configuration.getPageMap() + slot(column, row, this.configuration.getPageSlots()));
        final ItemFrame frame = world.spawn(behind, ItemFrame.class, spawned -> {
          spawned.setVisibleByDefault(false);
          spawned.setPersistent(false);
          spawned.setFacingDirection(back, true);
          spawned.setItem(page, false);
          spawned.setGlowing(true);
          spawned.setInvulnerable(true);
          spawned.setFixed(true);
          spawned.setSilent(true);
        });
        this.frames.add(frame);
      }
    }
  }

  /** A filled map item naming a map id, which need not exist on the server. */
  static ItemStack pageItem(final int mapId) {
    final net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(Items.FILLED_MAP);
    stack.set(DataComponents.MAP_ID, new MapId(mapId));
    return CraftItemStack.asBukkitCopy(stack);
  }

  /**
   * Gets the page frames.
   *
   * @return the frames, in wall order
   */
  public List<ItemFrame> getFrames() {
    return List.copyOf(this.frames);
  }

  /**
   * Shows the screen to a player with the pack: the page frames, their team and the anchors. Call on the main thread.
   *
   * @param player the player
   */
  public void show(final Player player) {
    Preconditions.checkNotNull(player, "Player must not be null");
    final Plugin plugin = BukkitModule.getPlugin();
    for (final ItemFrame frame : this.frames) {
      player.showEntity(plugin, frame);
    }
    final List<UUID> viewer = List.of(player.getUniqueId());
    PacketUtils.sendPackets(viewer, ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(this.team(), true));
    MapPacketFactory.send(viewer, this.anchors());
  }

  /**
   * Hides the page frames from a player again and removes their team. Call on the main thread.
   *
   * @param player the player
   */
  public void hide(final Player player) {
    Preconditions.checkNotNull(player, "Player must not be null");
    final Plugin plugin = BukkitModule.getPlugin();
    for (final ItemFrame frame : this.frames) {
      player.hideEntity(plugin, frame);
    }
    PacketUtils.sendPackets(List.of(player.getUniqueId()), ClientboundSetPlayerTeamPacket.createRemovePacket(this.team()));
  }

  /**
   * Removes the page frames. Call on the main thread.
   */
  public void remove() {
    this.frames.forEach(Entity::remove);
    this.frames.clear();
  }

  /** The team of the page frames, with the configured colour. */
  PlayerTeam team() {
    final PlayerTeam team = new PlayerTeam(new Scoreboard(), TEAM);
    final String name = NamedTextColor.NAMES.keyOrThrow(this.configuration.getOutlineColor());
    team.setColor(Optional.of(TeamColor.valueOf(name.toUpperCase(Locale.ROOT))));
    final Collection<String> members = team.getPlayers();
    for (final ItemFrame frame : this.frames) {
      members.add(frame.getUniqueId().toString());
    }
    return team;
  }

  /**
   * The anchor row of every wall map.
   *
   * @return one patch per map, row 0 only
   */
  public List<MapTilePatch> anchors() {
    final int columns = this.configuration.getColumns();
    final int rows = this.configuration.getRows();
    final int facing = this.configuration.getFacingCode();
    final List<MapTilePatch> patches = new ArrayList<>(columns * rows);
    for (int row = 0; row < rows; row++) {
      for (int column = 0; column < columns; column++) {
        final byte[] symbols = new byte[MapLayout.MAP_SIZE];
        for (int i = 0; i < SIGNATURE.length; i++) {
          symbols[i] = (byte) SIGNATURE[i];
        }
        symbols[8] = (byte) column;
        symbols[9] = (byte) row;
        symbols[10] = (byte) columns;
        symbols[11] = (byte) rows;
        symbols[12] = (byte) facing;
        symbols[13] = (byte) ((column + row + columns + rows + facing) & 63);
        final int mapId = this.configuration.getMap() + row * columns + column;
        patches.add(new MapTilePatch(mapId, 0, 0, MapLayout.MAP_SIZE, 1, MapAlphabet.toMapColors(symbols)));
      }
    }
    return patches;
  }
}
