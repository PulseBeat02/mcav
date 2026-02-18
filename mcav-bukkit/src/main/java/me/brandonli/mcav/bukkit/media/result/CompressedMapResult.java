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
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

/**
 * A DitherResultStep implementation that generates patch updates for map items based on quadrant hashing and tile analysis.
 */
public class CompressedMapResult implements DitherResultStep {

  private static final int MAP_PX = 128;
  private static final int TILE = 16;
  private static final int TILES_PER_ROW = MAP_PX / TILE;
  private static final int TILE_COUNT = TILES_PER_ROW * TILES_PER_ROW;
  private static final int QUAD = 64;
  private static final int QUADS_PER_ROW = MAP_PX / QUAD;
  private static final int QUAD_COUNT = QUADS_PER_ROW * QUADS_PER_ROW;
  private static final int TILES_PER_QUAD = QUAD / TILE;

  private static final int DEFAULT_MAX_BYTES_PER_FRAME = 2 * 1024 * 1024;
  private static final int BUNDLE_CHUNK = 512;
  private static final int MIN_UPDATES_PER_FRAME = 4;

  private static final int STALENESS_FACTOR = 500;
  private static final int MAX_STALENESS_FRAMES = 5;
  private static final int CRITICAL_STALENESS_FRAMES = 3;

  private static final int MIN_CHANGE_THRESHOLD = 8;

  private static final float SCENE_CHANGE_THRESHOLD = 0.5f;
  private static final float MAJOR_CHANGE_FRACTION = 0.33f;
  private static final int SCENE_CHANGE_SPREAD_FRAMES = 2;
  private static final float SCENE_CHANGE_BUDGET_MULTIPLIER = 1.5f;

  private static final int MAX_POOL_SIZE = 128;

  private final MapConfiguration mapConfiguration;
  private final LongHashFunction xxh3;
  private final byte[] quadScratch;
  private final Map<Integer, MapState> mapStates;
  private final ArrayList<PatchUpdate> deferredUpdates;
  private final ArrayList<byte[]> patchPool;

  private final int maxBytesPerFrame;
  private int sceneChangeFramesRemaining;

  /**
   * Constructs a CompressedMapResult with the given MapConfiguration and a default max bytes per frame.
   * @param configuration the MapConfiguration defining the map layout and viewers
   */
  public CompressedMapResult(final MapConfiguration configuration) {
    this(configuration, DEFAULT_MAX_BYTES_PER_FRAME);
  }

  /**
   * Constructs a CompressedMapResult with the given MapConfiguration and max bytes per frame.
   * @param configuration the MapConfiguration defining the map layout and viewers
   * @param maxBytesPerFrame the maximum number of bytes to send per frame for map updates
   */
  public CompressedMapResult(final MapConfiguration configuration, final int maxBytesPerFrame) {
    this.mapConfiguration = configuration;
    this.maxBytesPerFrame = maxBytesPerFrame;
    this.xxh3 = LongHashFunction.xx3(0L);
    this.quadScratch = new byte[QUAD * QUAD * 3];
    this.mapStates = new ConcurrentHashMap<>();
    this.deferredUpdates = new ArrayList<>();
    this.patchPool = new ArrayList<>();
    this.sceneChangeFramesRemaining = 0;
  }

  @Override
  public void process(final ImageBuffer samples, final DitherAlgorithm algorithm) {
    final int vidWidth = this.mapConfiguration.getMapWidthResolution();
    final int vidHeight = this.mapConfiguration.getMapHeightResolution();

    final ResizeFilter filter = new ResizeFilter(vidWidth, vidHeight);
    filter.applyFilter(samples);

    final int[] rgbPixels = samples.getPixels();
    final int expectedLength = vidWidth * vidHeight;
    if (rgbPixels == null || rgbPixels.length != expectedLength) {
      this.sendFullFrame(samples, algorithm);
      return;
    }

    final FrameLayout layout = new FrameLayout(this.mapConfiguration, vidWidth, vidHeight);
    final QuadrantResult quadResult = this.hashAllQuadrants(rgbPixels, layout);

    final boolean hasDeferredWork = !this.deferredUpdates.isEmpty();
    if (!quadResult.anyDirty && !hasDeferredWork) {
      this.incrementAllStaleness(layout);
      return;
    }

    final byte[] dithered = algorithm.ditherIntoBytes(samples);
    final DirtyAnalysisResult analysisResult = this.analyzeAllMaps(dithered, layout, quadResult);
    final boolean isSceneChange = this.detectSceneChange(analysisResult);
    final ArrayList<PatchUpdate> allUpdates = this.collectAllUpdates(analysisResult.patches);
    if (allUpdates.isEmpty()) {
      this.decrementSceneChangeCounter();
      return;
    }

    final PriorityBuckets buckets = this.categorizePatchUpdates(allUpdates);
    final boolean inRecovery = isSceneChange || this.sceneChangeFramesRemaining > 0;
    final int budget = this.computeEffectiveBudget(inRecovery);
    final SendResult sendResult = this.sendAllTiers(buckets, budget, inRecovery);

    this.applyLastSentUpdates(sendResult.sent);
    this.updateSkippedStaleness(sendResult.sentMapIds, layout);
    this.dispatchPackets(sendResult.packets, layout.viewers);
    this.decrementSceneChangeCounter();
    this.trimPool();
  }

  @Override
  public void start() {}

  @Override
  public void release() {
    this.mapStates.clear();
    this.deferredUpdates.clear();
    this.patchPool.clear();
    this.sceneChangeFramesRemaining = 0;
    this.sendClearPackets();
  }

  private static final class FrameLayout {

    final int vidWidth;
    final int vidHeight;
    final int mapBlockWidth;
    final int mapBlockHeight;
    final int mapStartId;
    final Collection<UUID> viewers;
    final int xOff;
    final int yOff;
    final int xLoopMin;
    final int yLoopMin;
    final int xLoopMax;
    final int yLoopMax;
    final int mapCols;

    FrameLayout(final MapConfiguration config, final int vidWidth, final int vidHeight) {
      this.vidWidth = vidWidth;
      this.vidHeight = vidHeight;
      this.mapBlockWidth = config.getMapBlockWidth();
      this.mapBlockHeight = config.getMapBlockHeight();
      this.mapStartId = config.getMap();
      this.viewers = config.getViewers();

      final int pixW = this.mapBlockWidth << 7;
      final int pixH = this.mapBlockHeight << 7;
      this.xOff = (pixW - vidWidth) >> 1;
      this.yOff = (pixH - vidHeight) >> 1;

      final int negXOff = this.xOff + vidWidth;
      final int negYOff = this.yOff + vidHeight;
      this.xLoopMin = Math.max(0, this.xOff >> 7);
      this.yLoopMin = Math.max(0, this.yOff >> 7);
      this.xLoopMax = Math.min(this.mapBlockWidth, (int) Math.ceil(negXOff / 128.0));
      this.yLoopMax = Math.min(this.mapBlockHeight, (int) Math.ceil(negYOff / 128.0));
      this.mapCols = this.xLoopMax - this.xLoopMin;
    }

    int mapIdAt(final int mx, final int my) {
      return this.mapStartId + my * this.mapBlockWidth + mx;
    }

    int arrayIndex(final int mx, final int my) {
      return (my - this.yLoopMin) * this.mapCols + (mx - this.xLoopMin);
    }

    int totalMapSlots() {
      return this.mapCols * (this.yLoopMax - this.yLoopMin);
    }
  }

  private static final class MapState {

    final long[] quadrantHashes;
    final byte[] lastSentData;
    int framesSinceLastSend;
    int accumulatedChanges;
    boolean initialized;

    MapState() {
      this.quadrantHashes = new long[QUAD_COUNT];
      this.lastSentData = new byte[MAP_PX * MAP_PX];
      this.framesSinceLastSend = 0;
      this.accumulatedChanges = 0;
      this.initialized = false;
    }
  }

  private static final class TileDirtyInfo {

    boolean dirty;
    int changedPixels;
    int minX;
    int minY;
    int maxX;
    int maxY;
  }

  private static final class PatchUpdate {

    final int mapIdInt;
    final int x;
    final int y;
    final int w;
    final int h;
    final byte[] patchData;
    final int changedPixels;
    final int staleness;
    final int accumulated;

    PatchUpdate(
      final int mapIdInt,
      final int x,
      final int y,
      final int w,
      final int h,
      final byte[] patchData,
      final int changedPixels,
      final int staleness,
      final int accumulated
    ) {
      this.mapIdInt = mapIdInt;
      this.x = x;
      this.y = y;
      this.w = w;
      this.h = h;
      this.patchData = patchData;
      this.changedPixels = changedPixels;
      this.staleness = staleness;
      this.accumulated = accumulated;
    }

    int dataSize() {
      return this.w * this.h;
    }

    int priorityScore() {
      final int capped = Math.min(this.staleness, MAX_STALENESS_FRAMES);
      final int stalenessScore = capped * STALENESS_FACTOR;
      final int accScore = Math.min(this.accumulated, MAP_PX * MAP_PX);
      return this.changedPixels + stalenessScore + accScore;
    }
  }

  private static final class TileRect {

    final int tileX;
    final int tileY;
    final int tileCols;
    final int tileRows;

    TileRect(final int tileX, final int tileY, final int tileCols, final int tileRows) {
      this.tileX = tileX;
      this.tileY = tileY;
      this.tileCols = tileCols;
      this.tileRows = tileRows;
    }
  }

  private static final class QuadrantResult {

    final int[] masks;
    final boolean anyDirty;

    QuadrantResult(final int[] masks, final boolean anyDirty) {
      this.masks = masks;
      this.anyDirty = anyDirty;
    }
  }

  private static final class DirtyAnalysisResult {

    final ArrayList<PatchUpdate> patches;
    final int totalMaps;
    final int majorChangeMaps;

    DirtyAnalysisResult(final ArrayList<PatchUpdate> patches, final int totalMaps, final int majorChangeMaps) {
      this.patches = patches;
      this.totalMaps = totalMaps;
      this.majorChangeMaps = majorChangeMaps;
    }
  }

  private static final class PriorityBuckets {

    final ArrayList<PatchUpdate> critical;
    final ArrayList<PatchUpdate> high;
    final ArrayList<PatchUpdate> normal;
    final ArrayList<PatchUpdate> low;

    PriorityBuckets() {
      this.critical = new ArrayList<>();
      this.high = new ArrayList<>();
      this.normal = new ArrayList<>();
      this.low = new ArrayList<>();
    }
  }

  private static final class SendResult {

    final ArrayList<Packet<? super ClientGamePacketListener>> packets;
    final ArrayList<PatchUpdate> sent;
    final Set<Integer> sentMapIds;

    SendResult() {
      this.packets = new ArrayList<>(128);
      this.sent = new ArrayList<>();
      this.sentMapIds = new HashSet<>();
    }
  }

  private static final class BoundingBox {

    final int x;
    final int y;
    final int w;
    final int h;
    final int changedPixels;
    final boolean valid;

    BoundingBox(final int x, final int y, final int w, final int h, final int changedPixels) {
      this.x = x;
      this.y = y;
      this.w = w;
      this.h = h;
      this.changedPixels = changedPixels;
      this.valid = true;
    }

    BoundingBox() {
      this.x = 0;
      this.y = 0;
      this.w = 0;
      this.h = 0;
      this.changedPixels = 0;
      this.valid = false;
    }
  }

  private static final class TileAnalysisResult {

    final TileDirtyInfo[] tileInfos;
    final int changedPixels;

    TileAnalysisResult(final TileDirtyInfo[] tileInfos, final int changedPixels) {
      this.tileInfos = tileInfos;
      this.changedPixels = changedPixels;
    }
  }

  private QuadrantResult hashAllQuadrants(final int[] rgbPixels, final FrameLayout layout) {
    final int totalSlots = layout.totalMapSlots();
    final int[] masks = new int[totalSlots];
    boolean anyDirty = false;

    for (int my = layout.yLoopMin; my < layout.yLoopMax; my++) {
      for (int mx = layout.xLoopMin; mx < layout.xLoopMax; mx++) {
        final int mapIdInt = layout.mapIdAt(mx, my);
        final int arrIdx = layout.arrayIndex(mx, my);
        final MapState state = this.mapStates.computeIfAbsent(mapIdInt, k -> new MapState());

        if (!state.initialized) {
          masks[arrIdx] = 0xF;
          anyDirty = true;
          continue;
        }

        final int mapWallX = mx << 7;
        final int mapWallY = my << 7;
        final int mask = this.hashQuadrantsForMap(rgbPixels, layout, state, mapWallX, mapWallY);
        masks[arrIdx] = mask;

        if (mask != 0) {
          anyDirty = true;
        }
      }
    }

    return new QuadrantResult(masks, anyDirty);
  }

  private int hashQuadrantsForMap(
    final int[] pixels,
    final FrameLayout layout,
    final MapState state,
    final int mapWallX,
    final int mapWallY
  ) {
    int mask = 0;

    for (int qy = 0; qy < QUADS_PER_ROW; qy++) {
      for (int qx = 0; qx < QUADS_PER_ROW; qx++) {
        final int qIdx = qy * QUADS_PER_ROW + qx;
        final int regionX = mapWallX + qx * QUAD;
        final int regionY = mapWallY + qy * QUAD;
        final long hash =
          this.hashRgbRegion(pixels, layout.vidWidth, layout.vidHeight, regionX, regionY, layout.xOff, layout.yOff, QUAD, QUAD);

        if (hash != state.quadrantHashes[qIdx]) {
          state.quadrantHashes[qIdx] = hash;
          mask |= (1 << qIdx);
        }
      }
    }

    return mask;
  }

  private long hashRgbRegion(
    final int[] pixels,
    final int vidWidth,
    final int vidHeight,
    final int wallX,
    final int wallY,
    final int xOff,
    final int yOff,
    final int regionW,
    final int regionH
  ) {
    int p = 0;

    for (int yy = 0; yy < regionH; yy++) {
      final int videoY = wallY + yy - yOff;
      final boolean yIn = videoY >= 0 && videoY < vidHeight;

      for (int xx = 0; xx < regionW; xx++) {
        final int videoX = wallX + xx - xOff;
        int argb = 0;
        if (yIn && videoX >= 0 && videoX < vidWidth) {
          argb = pixels[videoY * vidWidth + videoX];
        }
        this.quadScratch[p++] = (byte) (argb >> 16);
        this.quadScratch[p++] = (byte) (argb >> 8);
        this.quadScratch[p++] = (byte) argb;
      }
    }

    return this.xxh3.hashBytes(this.quadScratch, 0, p);
  }

  private DirtyAnalysisResult analyzeAllMaps(final byte[] dithered, final FrameLayout layout, final QuadrantResult quadResult) {
    final ArrayList<PatchUpdate> patches = new ArrayList<>(64);
    int totalMaps = 0;
    int majorChangeMaps = 0;
    final int majorPixelCount = (int) (MAP_PX * MAP_PX * MAJOR_CHANGE_FRACTION);

    for (int my = layout.yLoopMin; my < layout.yLoopMax; my++) {
      for (int mx = layout.xLoopMin; mx < layout.xLoopMax; mx++) {
        totalMaps++;

        final int arrIdx = layout.arrayIndex(mx, my);
        final int qMask = quadResult.masks[arrIdx];
        if (qMask == 0) {
          continue;
        }

        final int mapIdInt = layout.mapIdAt(mx, my);
        final int mapWallX = mx << 7;
        final int mapWallY = my << 7;
        final MapState state = requireNonNull(this.mapStates.get(mapIdInt));

        final TileAnalysisResult tileResult = this.analyzeTilesForMap(dithered, layout, state, mapWallX, mapWallY, qMask);

        if (tileResult.changedPixels == 0) {
          continue;
        }

        if (tileResult.changedPixels >= majorPixelCount) {
          majorChangeMaps++;
        }

        final List<PatchUpdate> mapPatches =
          this.mergeAndBuildPatches(tileResult.tileInfos, dithered, layout, mapWallX, mapWallY, mapIdInt, state);
        patches.addAll(mapPatches);
      }
    }

    return new DirtyAnalysisResult(patches, totalMaps, majorChangeMaps);
  }

  private TileAnalysisResult analyzeTilesForMap(
    final byte[] dithered,
    final FrameLayout layout,
    final MapState state,
    final int mapWallX,
    final int mapWallY,
    final int qMask
  ) {
    final TileDirtyInfo[] tileInfos = new TileDirtyInfo[TILE_COUNT];
    int totalChanged = 0;

    for (int ty = 0; ty < TILES_PER_ROW; ty++) {
      final int qy = ty / TILES_PER_QUAD;

      for (int tx = 0; tx < TILES_PER_ROW; tx++) {
        final int qx = tx / TILES_PER_QUAD;
        final int qIdx = qy * QUADS_PER_ROW + qx;
        final boolean quadrantClean = (qMask & (1 << qIdx)) == 0;

        if (quadrantClean) {
          continue;
        }

        final TileDirtyInfo info = this.analyzeTile(dithered, layout, state, mapWallX, mapWallY, tx, ty);
        if (info == null || !info.dirty) {
          continue;
        }

        final int tileIdx = ty * TILES_PER_ROW + tx;
        tileInfos[tileIdx] = info;
        totalChanged += info.changedPixels;
      }
    }

    return new TileAnalysisResult(tileInfos, totalChanged);
  }

  private @Nullable TileDirtyInfo analyzeTile(
    final byte[] dithered,
    final FrameLayout layout,
    final MapState state,
    final int mapWallX,
    final int mapWallY,
    final int tileCol,
    final int tileRow
  ) {
    final int tileBaseX = tileCol * TILE;
    final int tileBaseY = tileRow * TILE;

    int changed = 0;
    int minX = TILE;
    int minY = TILE;
    int maxX = -1;
    int maxY = -1;

    for (int yy = 0; yy < TILE; yy++) {
      final int localY = tileBaseY + yy;
      final int wallY = mapWallY + localY;
      final int videoY = wallY - layout.yOff;
      final boolean yIn = videoY >= 0 && videoY < layout.vidHeight;

      for (int xx = 0; xx < TILE; xx++) {
        final int localX = tileBaseX + xx;
        final int wallX = mapWallX + localX;
        final int videoX = wallX - layout.xOff;

        final byte current = this.readDitheredPixel(dithered, layout, videoX, videoY, yIn);
        final int sentIndex = localY * MAP_PX + localX;
        final byte sent = state.lastSentData[sentIndex];

        if (current != sent) {
          changed++;
          if (xx < minX) {
            minX = xx;
          }
          if (xx > maxX) {
            maxX = xx;
          }
          if (yy < minY) {
            minY = yy;
          }
          if (yy > maxY) {
            maxY = yy;
          }
        }
      }
    }

    final boolean belowThreshold = changed < MIN_CHANGE_THRESHOLD;
    if (belowThreshold || maxX < 0) {
      return null;
    }

    final TileDirtyInfo info = new TileDirtyInfo();
    info.dirty = true;
    info.changedPixels = changed;
    info.minX = tileBaseX + minX;
    info.minY = tileBaseY + minY;
    info.maxX = tileBaseX + maxX;
    info.maxY = tileBaseY + maxY;
    return info;
  }

  private byte readDitheredPixel(final byte[] dithered, final FrameLayout layout, final int videoX, final int videoY, final boolean yIn) {
    if (yIn && videoX >= 0 && videoX < layout.vidWidth) {
      final int index = videoY * layout.vidWidth + videoX;
      return dithered[index];
    }
    return 0;
  }

  private List<PatchUpdate> mergeAndBuildPatches(
    final TileDirtyInfo[] tileInfos,
    final byte[] dithered,
    final FrameLayout layout,
    final int mapWallX,
    final int mapWallY,
    final int mapIdInt,
    final MapState state
  ) {
    final boolean[] dirty = buildDirtyGrid(tileInfos);
    final List<TileRect> mergedRects = greedyMerge(dirty);
    final List<PatchUpdate> patches = new ArrayList<>(mergedRects.size());

    for (final TileRect rect : mergedRects) {
      final BoundingBox box = computeUnionBoundingBox(tileInfos, rect);
      if (!box.valid) {
        continue;
      }

      final byte[] patchData = this.extractPatchBytes(dithered, layout, mapWallX, mapWallY, box.x, box.y, box.w, box.h);

      final PatchUpdate patch = new PatchUpdate(
        mapIdInt,
        box.x,
        box.y,
        box.w,
        box.h,
        patchData,
        box.changedPixels,
        state.framesSinceLastSend,
        state.accumulatedChanges
      );
      patches.add(patch);
    }

    return patches;
  }

  private static boolean[] buildDirtyGrid(final TileDirtyInfo[] tileInfos) {
    final boolean[] dirty = new boolean[TILE_COUNT];
    for (int i = 0; i < TILE_COUNT; i++) {
      final TileDirtyInfo info = tileInfos[i];
      dirty[i] = info != null && info.dirty;
    }
    return dirty;
  }

  private static List<TileRect> greedyMerge(final boolean[] dirty) {
    final List<TileRect> rects = new ArrayList<>(8);

    for (int ty = 0; ty < TILES_PER_ROW; ty++) {
      int tx = 0;

      while (tx < TILES_PER_ROW) {
        final int startIdx = ty * TILES_PER_ROW + tx;
        if (!dirty[startIdx]) {
          tx++;
          continue;
        }

        final int runStart = tx;
        while (tx < TILES_PER_ROW && dirty[ty * TILES_PER_ROW + tx]) {
          tx++;
        }
        final int runLen = tx - runStart;

        int height = 1;
        for (int ey = ty + 1; ey < TILES_PER_ROW; ey++) {
          final boolean canExtend = isRowRunDirty(dirty, ey, runStart, runLen);
          if (!canExtend) {
            break;
          }
          clearRowRun(dirty, ey, runStart, runLen);
          height++;
        }

        clearRowRun(dirty, ty, runStart, runLen);
        final TileRect rect = new TileRect(runStart, ty, runLen, height);
        rects.add(rect);
      }
    }

    return rects;
  }

  private static boolean isRowRunDirty(final boolean[] dirty, final int row, final int start, final int len) {
    for (int x = start; x < start + len; x++) {
      final int idx = row * TILES_PER_ROW + x;
      if (!dirty[idx]) {
        return false;
      }
    }
    return true;
  }

  private static void clearRowRun(final boolean[] dirty, final int row, final int start, final int len) {
    for (int x = start; x < start + len; x++) {
      final int idx = row * TILES_PER_ROW + x;
      dirty[idx] = false;
    }
  }

  private static BoundingBox computeUnionBoundingBox(final TileDirtyInfo[] tileInfos, final TileRect rect) {
    int unionMinX = Integer.MAX_VALUE;
    int unionMinY = Integer.MAX_VALUE;
    int unionMaxX = Integer.MIN_VALUE;
    int unionMaxY = Integer.MIN_VALUE;
    int totalChanged = 0;

    for (int ry = 0; ry < rect.tileRows; ry++) {
      for (int rx = 0; rx < rect.tileCols; rx++) {
        final int tileIdx = (rect.tileY + ry) * TILES_PER_ROW + (rect.tileX + rx);
        final TileDirtyInfo info = tileInfos[tileIdx];

        if (info == null || !info.dirty) {
          continue;
        }

        if (info.minX < unionMinX) {
          unionMinX = info.minX;
        }
        if (info.minY < unionMinY) {
          unionMinY = info.minY;
        }
        if (info.maxX > unionMaxX) {
          unionMaxX = info.maxX;
        }
        if (info.maxY > unionMaxY) {
          unionMaxY = info.maxY;
        }
        totalChanged += info.changedPixels;
      }
    }

    final boolean noChanges = unionMaxX < unionMinX;
    if (noChanges) {
      return new BoundingBox();
    }

    final int width = unionMaxX - unionMinX + 1;
    final int height = unionMaxY - unionMinY + 1;
    return new BoundingBox(unionMinX, unionMinY, width, height, totalChanged);
  }

  private byte[] extractPatchBytes(
    final byte[] dithered,
    final FrameLayout layout,
    final int mapWallX,
    final int mapWallY,
    final int patchX,
    final int patchY,
    final int patchW,
    final int patchH
  ) {
    final int size = patchW * patchH;
    final byte[] patch = this.acquireBuffer(size);
    int p = 0;

    for (int yy = 0; yy < patchH; yy++) {
      final int wallY = mapWallY + patchY + yy;
      final int videoY = wallY - layout.yOff;
      final boolean yIn = videoY >= 0 && videoY < layout.vidHeight;

      for (int xx = 0; xx < patchW; xx++) {
        final int wallX = mapWallX + patchX + xx;
        final int videoX = wallX - layout.xOff;
        patch[p++] = this.readDitheredPixel(dithered, layout, videoX, videoY, yIn);
      }
    }

    return patch;
  }

  private boolean detectSceneChange(final DirtyAnalysisResult result) {
    final boolean hasEnoughMaps = result.totalMaps > 0;
    final float fraction = (float) result.majorChangeMaps / result.totalMaps;
    final boolean isSceneChange = hasEnoughMaps && fraction >= SCENE_CHANGE_THRESHOLD;

    if (isSceneChange && this.sceneChangeFramesRemaining == 0) {
      this.sceneChangeFramesRemaining = SCENE_CHANGE_SPREAD_FRAMES;
    }

    return isSceneChange;
  }

  private ArrayList<PatchUpdate> collectAllUpdates(final ArrayList<PatchUpdate> frameUpdates) {
    if (!this.deferredUpdates.isEmpty()) {
      frameUpdates.addAll(this.deferredUpdates);
      this.deferredUpdates.clear();
    }
    return frameUpdates;
  }

  private PriorityBuckets categorizePatchUpdates(final ArrayList<PatchUpdate> updates) {
    final PriorityBuckets buckets = new PriorityBuckets();
    final int majorTileThreshold = (int) (TILE * TILE * MAJOR_CHANGE_FRACTION);
    final int lowChangeMax = MIN_CHANGE_THRESHOLD * 4;
    final int lowAccumulatedMax = MIN_CHANGE_THRESHOLD * 16;
    final int highAccumulatedThreshold = (MAP_PX * MAP_PX) / 2;

    for (final PatchUpdate update : updates) {
      if (update.staleness >= CRITICAL_STALENESS_FRAMES) {
        buckets.critical.add(update);
      } else if (update.changedPixels >= majorTileThreshold || update.accumulated >= highAccumulatedThreshold) {
        buckets.high.add(update);
      } else if (update.changedPixels < lowChangeMax && update.staleness == 0 && update.accumulated < lowAccumulatedMax) {
        buckets.low.add(update);
      } else {
        buckets.normal.add(update);
      }
    }

    buckets.critical.sort((a, b) -> Integer.compare(b.staleness, a.staleness));
    buckets.high.sort((a, b) -> Integer.compare(b.priorityScore(), a.priorityScore()));
    buckets.normal.sort((a, b) -> Integer.compare(b.priorityScore(), a.priorityScore()));

    return buckets;
  }

  private int computeEffectiveBudget(final boolean inRecovery) {
    if (inRecovery) {
      return (int) (this.maxBytesPerFrame * SCENE_CHANGE_BUDGET_MULTIPLIER);
    }
    return this.maxBytesPerFrame;
  }

  private SendResult sendAllTiers(final PriorityBuckets buckets, final int budget, final boolean inRecovery) {
    final SendResult result = new SendResult();
    final Collection<MapDecoration> emptyDecorations = List.of();
    final int criticalCap = this.maxBytesPerFrame * 2;
    int totalBytes = 0;

    totalBytes = this.sendTier(buckets.critical, result, emptyDecorations, totalBytes, criticalCap);
    totalBytes = this.sendTier(buckets.high, result, emptyDecorations, totalBytes, budget);
    totalBytes = this.sendTier(buckets.normal, result, emptyDecorations, totalBytes, budget);

    if (inRecovery) {
      this.deferAll(buckets.low);
    } else {
      final int lowBudget = (int) (budget * 0.9);
      this.sendTier(buckets.low, result, emptyDecorations, totalBytes, lowBudget);
    }

    return result;
  }

  private int sendTier(
    final List<PatchUpdate> tier,
    final SendResult result,
    final Collection<MapDecoration> emptyDecorations,
    int totalBytes,
    final int maxBytes
  ) {
    for (final PatchUpdate update : tier) {
      final int updateSize = update.dataSize();
      final boolean overBudget = totalBytes + updateSize > maxBytes;
      final boolean aboveMinimum = result.packets.size() >= MIN_UPDATES_PER_FRAME;

      if (overBudget && aboveMinimum) {
        this.deferUpdate(update);
        continue;
      }

      final MapId mapId = new MapId(update.mapIdInt);
      final MapItemSavedData.MapPatch mapPatch = new MapItemSavedData.MapPatch(update.x, update.y, update.w, update.h, update.patchData);
      final ClientboundMapItemDataPacket packet = new ClientboundMapItemDataPacket(mapId, (byte) 0, false, emptyDecorations, mapPatch);

      result.packets.add(packet);
      result.sent.add(update);
      result.sentMapIds.add(update.mapIdInt);
      totalBytes += updateSize;
    }

    return totalBytes;
  }

  private void deferAll(final List<PatchUpdate> updates) {
    for (final PatchUpdate update : updates) {
      this.deferUpdate(update);
    }
  }

  private void deferUpdate(final PatchUpdate update) {
    final MapState state = this.mapStates.get(update.mapIdInt);
    if (state != null) {
      state.framesSinceLastSend++;
      state.accumulatedChanges += update.changedPixels;
    }

    final PatchUpdate escalated = new PatchUpdate(
      update.mapIdInt,
      update.x,
      update.y,
      update.w,
      update.h,
      update.patchData,
      update.changedPixels,
      update.staleness + 1,
      update.accumulated + update.changedPixels
    );
    this.deferredUpdates.add(escalated);
  }

  private void applyLastSentUpdates(final List<PatchUpdate> sentUpdates) {
    for (final PatchUpdate sent : sentUpdates) {
      final MapState state = this.mapStates.get(sent.mapIdInt);
      if (state == null) {
        continue;
      }

      int srcOff = 0;
      for (int yy = 0; yy < sent.h; yy++) {
        final int dstOff = (sent.y + yy) * MAP_PX + sent.x;
        System.arraycopy(sent.patchData, srcOff, state.lastSentData, dstOff, sent.w);
        srcOff += sent.w;
      }

      state.framesSinceLastSend = 0;
      state.accumulatedChanges = 0;
      state.initialized = true;
    }
  }

  private void updateSkippedStaleness(final Set<Integer> sentMapIds, final FrameLayout layout) {
    for (int my = layout.yLoopMin; my < layout.yLoopMax; my++) {
      for (int mx = layout.xLoopMin; mx < layout.xLoopMax; mx++) {
        final int mapIdInt = layout.mapIdAt(mx, my);
        final boolean wasSent = sentMapIds.contains(mapIdInt);
        if (wasSent) {
          continue;
        }

        final MapState state = this.mapStates.get(mapIdInt);
        if (state != null && state.initialized) {
          state.framesSinceLastSend++;
        }
      }
    }
  }

  private void incrementAllStaleness(final FrameLayout layout) {
    for (int my = layout.yLoopMin; my < layout.yLoopMax; my++) {
      for (int mx = layout.xLoopMin; mx < layout.xLoopMax; mx++) {
        final int mapIdInt = layout.mapIdAt(mx, my);
        final MapState state = this.mapStates.get(mapIdInt);
        if (state != null && state.initialized) {
          state.framesSinceLastSend++;
        }
      }
    }
  }

  private void dispatchPackets(final List<Packet<? super ClientGamePacketListener>> packets, final Collection<UUID> viewers) {
    if (packets.isEmpty()) {
      return;
    }

    for (int i = 0; i < packets.size(); i += BUNDLE_CHUNK) {
      final int end = Math.min(i + BUNDLE_CHUNK, packets.size());
      final List<Packet<? super ClientGamePacketListener>> chunk = packets.subList(i, end);
      final ClientboundBundlePacket bundle = new ClientboundBundlePacket(chunk);
      PacketUtils.sendPackets(viewers, bundle);
    }
  }

  private void decrementSceneChangeCounter() {
    if (this.sceneChangeFramesRemaining > 0) {
      this.sceneChangeFramesRemaining--;
    }
  }

  private byte[] acquireBuffer(final int minSize) {
    for (int i = 0; i < this.patchPool.size(); i++) {
      final byte[] buf = this.patchPool.get(i);
      if (buf.length >= minSize) {
        final int lastIdx = this.patchPool.size() - 1;
        this.patchPool.set(i, this.patchPool.get(lastIdx));
        this.patchPool.remove(lastIdx);
        return buf;
      }
    }
    return new byte[minSize];
  }

  private void trimPool() {
    while (this.patchPool.size() > MAX_POOL_SIZE) {
      final int lastIdx = this.patchPool.size() - 1;
      this.patchPool.remove(lastIdx);
    }
  }

  private void sendFullFrame(final ImageBuffer samples, final DitherAlgorithm algorithm) {
    final byte[] dithered = algorithm.ditherIntoBytes(samples);

    final int vidWidth = this.mapConfiguration.getMapWidthResolution();
    final int vidHeight = this.mapConfiguration.getMapHeightResolution();
    final int mapBlockWidth = this.mapConfiguration.getMapBlockWidth();
    final int mapBlockHeight = this.mapConfiguration.getMapBlockHeight();
    final int mapStartId = this.mapConfiguration.getMap();
    final Collection<UUID> viewers = this.mapConfiguration.getViewers();

    final int pixW = mapBlockWidth << 7;
    final int pixH = mapBlockHeight << 7;
    final int xOff = (pixW - vidWidth) >> 1;
    final int yOff = (pixH - vidHeight) >> 1;

    final Collection<MapDecoration> emptyDecorations = List.of();
    final ArrayList<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();

    for (int my = 0; my < mapBlockHeight; my++) {
      for (int mx = 0; mx < mapBlockWidth; mx++) {
        final int mapIdInt = mapStartId + my * mapBlockWidth + mx;
        final int mapWallX = mx << 7;
        final int mapWallY = my << 7;

        final byte[] mapData = this.extractFullMapData(dithered, vidWidth, vidHeight, mapWallX, mapWallY, xOff, yOff);
        this.updateMapStateFullFrame(mapIdInt, mapData);

        final MapItemSavedData.MapPatch patch = new MapItemSavedData.MapPatch(0, 0, MAP_PX, MAP_PX, mapData);
        final MapId id = new MapId(mapIdInt);
        final ClientboundMapItemDataPacket packet = new ClientboundMapItemDataPacket(id, (byte) 0, false, emptyDecorations, patch);
        packets.add(packet);
      }
    }

    this.dispatchPackets(packets, viewers);
  }

  private byte[] extractFullMapData(
    final byte[] dithered,
    final int vidWidth,
    final int vidHeight,
    final int mapWallX,
    final int mapWallY,
    final int xOff,
    final int yOff
  ) {
    final byte[] mapData = new byte[MAP_PX * MAP_PX];

    for (int ly = 0; ly < MAP_PX; ly++) {
      final int videoY = mapWallY + ly - yOff;
      final boolean yIn = videoY >= 0 && videoY < vidHeight;
      if (!yIn) {
        continue;
      }

      for (int lx = 0; lx < MAP_PX; lx++) {
        final int videoX = mapWallX + lx - xOff;
        final boolean xIn = videoX >= 0 && videoX < vidWidth;
        if (xIn) {
          final int srcIdx = videoY * vidWidth + videoX;
          final int dstIdx = ly * MAP_PX + lx;
          mapData[dstIdx] = dithered[srcIdx];
        }
      }
    }

    return mapData;
  }

  private void updateMapStateFullFrame(final int mapIdInt, final byte[] mapData) {
    final MapState state = this.mapStates.computeIfAbsent(mapIdInt, k -> new MapState());
    System.arraycopy(mapData, 0, state.lastSentData, 0, MAP_PX * MAP_PX);
    state.initialized = true;
    state.framesSinceLastSend = 0;
    state.accumulatedChanges = 0;
  }

  private void sendClearPackets() {
    final int start = this.mapConfiguration.getMap();
    final int mapWidth = this.mapConfiguration.getMapBlockWidth();
    final int mapHeight = this.mapConfiguration.getMapBlockHeight();
    final int totalMaps = mapWidth * mapHeight;
    final int end = start + totalMaps;
    final Collection<UUID> viewers = this.mapConfiguration.getViewers();
    final Collection<MapDecoration> emptyDecorations = List.of();

    final byte[] clearData = new byte[MAP_PX * MAP_PX];
    final MapItemSavedData.MapPatch clearPatch = new MapItemSavedData.MapPatch(0, 0, MAP_PX, MAP_PX, clearData);
    final ClientboundMapItemDataPacket[] emptyPackets = new ClientboundMapItemDataPacket[totalMaps];

    for (int i = start; i < end; i++) {
      final MapId id = new MapId(i);
      final int idx = i - start;
      emptyPackets[idx] = new ClientboundMapItemDataPacket(id, (byte) 0, false, emptyDecorations, clearPatch);
    }

    PacketUtils.sendPackets(viewers, emptyPackets);
  }
}
