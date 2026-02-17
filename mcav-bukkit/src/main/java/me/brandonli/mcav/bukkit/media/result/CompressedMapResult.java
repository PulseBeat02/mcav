package me.brandonli.mcav.bukkit.media.result;

import me.brandonli.mcav.bukkit.media.config.MapConfiguration;
import me.brandonli.mcav.bukkit.utils.PacketUtils;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.pipeline.filter.video.ResizeFilter;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.DitherResultStep;
import me.brandonli.mcav.media.player.pipeline.filter.video.dither.algorithm.DitherAlgorithm;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.openhft.hashing.LongHashFunction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CompressedMapResult implements DitherResultStep {

  private final MapConfiguration mapConfiguration;

  // XXH3 64-bit
  private final LongHashFunction xxh3 = LongHashFunction.xx3(0L);

  // mapId -> last RGB tile hashes (pre-dither). 16x16 tiles => 64 per map.
  private final Map<Integer, long[]> lastRgbTileHashes = new ConcurrentHashMap<>();

  // mapId -> dirty tile bitmask for THIS frame (64 tiles -> 64 bits)
  private final Map<Integer, Long> dirtyTileMask = new ConcurrentHashMap<>();

  private static final int TILE = 16;
  private static final int TILES_PER_ROW = 128 / TILE; // 8
  private static final int TILE_COUNT = TILES_PER_ROW * TILES_PER_ROW; // 64

  // Scratch buffer: store RGB bytes for one tile (16*16*3 = 768 bytes)
  // (3 bytes/pixel is enough; hashing ARGB 4 bytes is wasted work)
  private final byte[] tileRgbScratch = new byte[TILE * TILE * 3];

  public CompressedMapResult(final MapConfiguration configuration) {
    this.mapConfiguration = configuration;
  }

  @Override
  public void process(final ImageBuffer samples, final DitherAlgorithm algorithm) {
    final int vidWidth = this.mapConfiguration.getMapWidthResolution();
    final int vidHeight = this.mapConfiguration.getMapHeightResolution();

    final ResizeFilter filter = new ResizeFilter(vidWidth, vidHeight);
    filter.applyFilter(samples);

    // Pre-dither RGB pixels
    final int[] rgbPixels = samples.getPixels();
    if (rgbPixels == null || rgbPixels.length != vidWidth * vidHeight) {
      this.sendFullFrame(samples, algorithm);
      return;
    }

    final int mapBlockWidth = this.mapConfiguration.getMapBlockWidth();
    final int mapBlockHeight = this.mapConfiguration.getMapBlockHeight();
    final int mapStartId = this.mapConfiguration.getMap();
    final Collection<UUID> viewers = this.mapConfiguration.getViewers();

    final int pixW = mapBlockWidth << 7;
    final int pixH = mapBlockHeight << 7;

    final int xOff = (pixW - vidWidth) >> 1;
    final int yOff = (pixH - vidHeight) >> 1;

    final int negXOff = xOff + vidWidth;
    final int negYOff = yOff + vidHeight;

    final int xLoopMin = Math.max(0, xOff >> 7);
    final int yLoopMin = Math.max(0, yOff >> 7);
    final int xLoopMax = Math.min(mapBlockWidth, (int) Math.ceil(negXOff / 128.0));
    final int yLoopMax = Math.min(mapBlockHeight, (int) Math.ceil(negYOff / 128.0));

    // Clear per-frame dirty state
    this.dirtyTileMask.clear();

    boolean anyDirty = false;

    // 1) Decide dirty tiles using RGB hashes (pre-dither)
    for (int my = yLoopMin; my < yLoopMax; my++) {
      final int relY = my << 7;

      for (int mx = xLoopMin; mx < xLoopMax; mx++) {
        final int relX = mx << 7;

        final int mapIndex = my * mapBlockWidth + mx;
        final int mapIdInt = mapStartId + mapIndex;

        final long[] prevHashes = this.lastRgbTileHashes.computeIfAbsent(mapIdInt, k -> new long[TILE_COUNT]);

        long mask = 0L;

        for (int ty = 0; ty < TILES_PER_ROW; ty++) {
          final int baseMapY = ty * TILE;

          for (int tx = 0; tx < TILES_PER_ROW; tx++) {
            final int baseMapX = tx * TILE;
            final int tileIdx = ty * TILES_PER_ROW + tx;

            // Fill scratch with RGB bytes for this tile
            int p = 0;
            for (int yy = 0; yy < TILE; yy++) {
              final int wallY = relY + baseMapY + yy;
              final int videoY = wallY - yOff;

              final int rowBase = (videoY >= 0 && videoY < vidHeight) ? (videoY * vidWidth) : -1;

              for (int xx = 0; xx < TILE; xx++) {
                final int wallX = relX + baseMapX + xx;
                final int videoX = wallX - xOff;

                int argb = 0;
                if (rowBase != -1 && videoX >= 0 && videoX < vidWidth) {
                  argb = rgbPixels[rowBase + videoX];
                }

                // store RGB (not ARGB)
                this.tileRgbScratch[p++] = (byte) ((argb >> 16) & 0xFF);
                this.tileRgbScratch[p++] = (byte) ((argb >> 8) & 0xFF);
                this.tileRgbScratch[p++] = (byte) (argb & 0xFF);
              }
            }

            final long h = this.xxh3.hashBytes(this.tileRgbScratch, 0, p);

            if (h != prevHashes[tileIdx]) {
              // mark dirty
              mask |= (1L << tileIdx);
              prevHashes[tileIdx] = h; // update in place (no new array)
            }
          }
        }

        if (mask != 0L) {
          this.dirtyTileMask.put(mapIdInt, mask);
          anyDirty = true;
        }
      }
    }

    if (!anyDirty) {
      return;
    }

    // 2) Dither ONCE to get final palette indices for sending
    final byte[] idx = algorithm.ditherIntoBytes(samples);

    // 3) Build packets only for dirty tiles
    final Collection<MapDecoration> empty = new ArrayList<>();
    final ArrayList<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>(256);

    for (int my = yLoopMin; my < yLoopMax; my++) {
      final int relY = my << 7;

      for (int mx = xLoopMin; mx < xLoopMax; mx++) {
        final int relX = mx << 7;

        final int mapIndex = my * mapBlockWidth + mx;
        final int mapIdInt = mapStartId + mapIndex;

        final Long maskObj = this.dirtyTileMask.get(mapIdInt);
        if (maskObj == null) {
          continue;
        }

        final long mask = maskObj;
        final MapId id = new MapId(mapIdInt);

        // For each dirty tile
        long m = mask;
        while (m != 0L) {
          final int tileIdx = Long.numberOfTrailingZeros(m);
          m &= (m - 1); // clear lowest set bit

          final int ty = tileIdx / TILES_PER_ROW;
          final int tx = tileIdx - (ty * TILES_PER_ROW);

          final int baseX = tx * TILE;
          final int baseY = ty * TILE;

          // Build patch bytes (256 bytes). Still allocates; next step would be pooling or rectangle merge.
          final byte[] patch = new byte[TILE * TILE];
          int p = 0;

          for (int yy = 0; yy < TILE; yy++) {
            final int wallY = relY + baseY + yy;
            final int videoY = wallY - yOff;
            final int rowBase = (videoY >= 0 && videoY < vidHeight) ? (videoY * vidWidth) : -1;

            for (int xx = 0; xx < TILE; xx++) {
              final int wallX = relX + baseX + xx;
              final int videoX = wallX - xOff;

              if (rowBase != -1 && videoX >= 0 && videoX < vidWidth) {
                patch[p++] = idx[rowBase + videoX];
              } else {
                patch[p++] = 0;
              }
            }
          }

          final MapItemSavedData.MapPatch mapPatch = new MapItemSavedData.MapPatch(baseX, baseY, TILE, TILE, patch);
          packets.add(new ClientboundMapItemDataPacket(id, (byte) 0, false, empty, mapPatch));
        }
      }
    }

    if (packets.isEmpty()) {
      return;
    }

    // Bundle in chunks to avoid "too many packets in a bundle"
    final int CHUNK = 256;
    for (int i = 0; i < packets.size(); i += CHUNK) {
      final int end = Math.min(i + CHUNK, packets.size());
      PacketUtils.sendPackets(viewers, new ClientboundBundlePacket(packets.subList(i, end)));
    }
  }

  private void sendFullFrame(final ImageBuffer samples, final DitherAlgorithm algorithm) {
    // You should keep your original full-frame sender here
    algorithm.ditherIntoBytes(samples);
  }

  @Override public void start() {}

  @Override
  public void release() {
    this.lastRgbTileHashes.clear();
    this.dirtyTileMask.clear();

    final int start = this.mapConfiguration.getMap();
    final int mapWidth = this.mapConfiguration.getMapBlockWidth();
    final int mapHeight = this.mapConfiguration.getMapBlockHeight();
    final int end = start + (mapWidth * mapHeight);

    final Collection<UUID> viewers = this.mapConfiguration.getViewers();
    final Collection<MapDecoration> empty = new ArrayList<>();

    final ClientboundMapItemDataPacket[] emptyPackets = new ClientboundMapItemDataPacket[end - start];
    final MapItemSavedData.MapPatch mapPatch = new MapItemSavedData.MapPatch(0, 0, 128, 128, new byte[128 * 128]);

    for (int i = start; i < end; i++) {
      final MapId id = new MapId(i);
      emptyPackets[i - start] = new ClientboundMapItemDataPacket(id, (byte) 0, false, empty, mapPatch);
    }

    PacketUtils.sendPackets(viewers, emptyPackets);
  }
}
