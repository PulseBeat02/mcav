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
package me.brandonli.mcav.bukkit.media.map;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Converts map patches into map data packets and sends them to players.
 *
 * <p>Up to 4096 patches from one call are sent in one bundle, the protocol limit. The client handles the packets
 * of a bundle together, so the call's patches are applied in order within one client tick. Larger calls are split
 * into consecutive bundles; atomicity across those bundles is not guaranteed. A {@link DeltaMapEncoder} keeps to its
 * byte budget by skipping whole frames, not by sending parts of them, but it may hold back tiles where only a
 * handful of pixels changed until a later frame. Every method may be called from any thread.
 *
 * @since 1.0.0
 */
public final class MapPacketFactory {

  private static final String VIEWERS_NULL_MESSAGE = "Viewers must not be null!";
  private static final String PATCHES_NULL_MESSAGE = "Patches must not be null!";

  private static final int MAXIMUM_PACKETS_PER_BUNDLE = BundlerInfo.BUNDLE_SIZE_LIMIT;

  private static final byte DEFAULT_SCALE = 0;

  private static final int MAP_ORIGIN = 0;

  private MapPacketFactory() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated!");
  }

  /**
   * Sends the patches to the viewers in their supplied order. Viewers that are offline are skipped. Calls with
   * at most 4096 patches use a single bundle; larger calls have no guarantee of atomic application across bundles.
   *
   * @param viewers the UUIDs of the players to send the patches to
   * @param patches the patches to send
   * @throws NullPointerException if {@code viewers} or {@code patches} is null
   * @since 1.0.0
   */
  public static void send(final Collection<UUID> viewers, final List<MapTilePatch> patches) {
    Preconditions.checkNotNull(viewers, VIEWERS_NULL_MESSAGE);
    Preconditions.checkNotNull(patches, PATCHES_NULL_MESSAGE);

    if (patches.isEmpty() || viewers.isEmpty()) {
      return;
    }

    final List<Bundle> bundles = createBundles(patches);
    for (final Bundle bundle : bundles) {
      final ClientboundBundlePacket packet = bundle.getPacket();
      PacketUtils.sendPackets(viewers, packet);
    }
  }

  /**
   * Converts patches into the bundles {@link #send} sends, without sending them, for a caller that sends them to
   * each viewer itself: up to 4096 patches to a bundle, in order.
   *
   * @param patches the patches
   * @return the bundles, none for no patches
   * @throws NullPointerException if {@code patches} is null
   * @since 1.0.0
   */
  public static List<Bundle> createBundles(final List<MapTilePatch> patches) {
    Preconditions.checkNotNull(patches, PATCHES_NULL_MESSAGE);

    final List<Bundle> bundles = new ArrayList<>();
    for (int from = 0; from < patches.size(); from += MAXIMUM_PACKETS_PER_BUNDLE) {
      final int to = Math.min(from + MAXIMUM_PACKETS_PER_BUNDLE, patches.size());
      final List<MapTilePatch> bundlePatches = patches.subList(from, to);
      final Bundle bundle = createBundle(bundlePatches);
      bundles.add(bundle);
    }

    return bundles;
  }

  private static Bundle createBundle(final List<MapTilePatch> bundlePatches) {
    final List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>(bundlePatches.size());
    long bytes = 0;
    for (final MapTilePatch patch : bundlePatches) {
      final ClientboundMapItemDataPacket packet = createPacket(patch);
      packets.add(packet);
      bytes += patch.getEncodedSize();
    }

    final ClientboundBundlePacket bundlePacket = new ClientboundBundlePacket(packets);
    return Bundle.of(bundlePacket, bytes);
  }

  /**
   * Clears maps by filling them completely with the transparent color.
   *
   * <p>This synchronous operation has no byte budget or tick pacing. Each map contributes 16384 color bytes
   * per viewer before network compression, in bundles of up to 4096 maps; compressed, a transparent map is about 40
   * bytes. The calling thread only queues the packets: the connection threads compress them. The delta encoder's
   * playback budget does not apply to clearing.
   *
   * @param viewers    the UUIDs of the players to clear the maps for
   * @param startMapId the id of the first map
   * @param count      the number of maps with consecutive ids to clear
   * @throws IllegalArgumentException if the first map id or count is negative, or the last id overflows an integer
   * @throws NullPointerException     if {@code viewers} is null
   * @since 1.0.0
   */
  public static void clear(final Collection<UUID> viewers, final int startMapId, final int count) {
    Preconditions.checkNotNull(viewers, VIEWERS_NULL_MESSAGE);
    Preconditions.checkArgument(startMapId >= 0, "Map id %s must not be negative!", startMapId);
    Preconditions.checkArgument(count >= 0, "Map count %s must not be negative!", count);

    final long lastMapId = (long) startMapId + count - 1;
    Preconditions.checkArgument(lastMapId <= Integer.MAX_VALUE, "Map ids %s to %s exceed the largest map id!", startMapId, lastMapId);

    final int size = MapLayout.MAP_SIZE;
    final byte[] transparent = new byte[size * size];
    final List<MapTilePatch> patches = new ArrayList<>(count);
    for (int offset = 0; offset < count; offset++) {
      final int mapId = startMapId + offset;
      final MapTilePatch patch = MapTilePatch.of(mapId, MAP_ORIGIN, MAP_ORIGIN, size, size, transparent);
      patches.add(patch);
    }

    send(viewers, patches);
  }

  private static ClientboundMapItemDataPacket createPacket(final MapTilePatch patch) {
    final int mapId = patch.getMapId();
    final int left = patch.getX();
    final int top = patch.getY();
    final int width = patch.getWidth();
    final int height = patch.getHeight();
    final byte[] colors = patch.getColors();

    final MapId id = new MapId(mapId);
    final MapItemSavedData.MapPatch mapPatch = new MapItemSavedData.MapPatch(left, top, width, height, colors);
    final Optional<List<MapDecoration>> decorations = Optional.empty();
    final Optional<MapItemSavedData.MapPatch> colorPatch = Optional.of(mapPatch);
    final boolean locked = false;
    return new ClientboundMapItemDataPacket(id, DEFAULT_SCALE, locked, decorations, colorPatch);
  }

  /**
   * A bundle of map data packets, and their estimated size in bytes before network compression.
   *
   * <p>The bundle shares the packet rather than copying it, and does not check the size. This class is immutable and
   * thread-safe.
   *
   * @since 1.0.0
   */
  public static final class Bundle {

    private final ClientboundBundlePacket packet;
    private final long bytes;

    private Bundle(final ClientboundBundlePacket packet, final long bytes) {
      this.packet = packet;
      this.bytes = bytes;
    }

    /**
     * Returns a bundle of the given packet and size.
     *
     * @param packet the bundle
     * @param bytes  the sum of its patches' {@link MapTilePatch#getEncodedSize()}
     * @return the bundle
     * @throws NullPointerException if {@code packet} is null
     * @since 1.0.0
     */
    public static Bundle of(final ClientboundBundlePacket packet, final long bytes) {
      Preconditions.checkNotNull(packet, "Packet must not be null!");

      return new Bundle(packet, bytes);
    }

    /**
     * Sends the bundle to one player and runs a callback once its write completed or failed, or at once when the
     * player is not connected, since nothing reaches a connection then. May be called from any thread.
     *
     * <p>Write completion is not a client rendering acknowledgment. The callback must be safe to run on a
     * connection thread and should not block; it receives no success/failure flag.
     *
     * @param viewer  the UUID of the player
     * @param callback the callback to run once, on the player's connection thread or on this one
     * @throws NullPointerException if the viewer or callback is null
     * @since 1.0.0
     */
    public void send(final UUID viewer, final Runnable callback) {
      Preconditions.checkNotNull(callback, "Callback must not be null!");

      final boolean sent = PacketUtils.sendPacket(viewer, this.packet, _ -> callback.run());
      if (!sent) {
        callback.run();
      }
    }

    /**
     * Returns the bundle packet.
     *
     * @return the bundle
     * @since 1.0.0
     */
    public ClientboundBundlePacket getPacket() {
      return this.packet;
    }

    /**
     * Returns the estimated size of the bundle in bytes.
     *
     * @return the sum of its patches' {@link MapTilePatch#getEncodedSize()}
     * @since 1.0.0
     */
    public long getBytes() {
      return this.bytes;
    }

    /**
     * Returns whether {@code other} is a bundle with the same parts.
     *
     * @param other the object to compare with
     * @return whether every part matches
     * @since 1.0.0
     */
    @Override
    public boolean equals(final @Nullable Object other) {
      if (this == other) {
        return true;
      }

      if (!(other instanceof final Bundle bundle)) {
        return false;
      }

      return this.packet.equals(bundle.packet) && this.bytes == bundle.bytes;
    }

    /**
     * Returns a hash code built from every part.
     *
     * @return the hash code
     * @since 1.0.0
     */
    @Override
    public int hashCode() {
      return Objects.hash(this.packet, this.bytes);
    }

    /**
     * Returns a debugging representation with every part.
     *
     * @return the representation
     * @since 1.0.0
     */
    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("packet", this.packet).add("bytes", this.bytes).toString();
    }
  }
}
