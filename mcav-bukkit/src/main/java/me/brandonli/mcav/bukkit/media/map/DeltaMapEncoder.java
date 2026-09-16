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

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Encodes a stream of dithered frames into the smallest set of map patches that keeps clients up to date.
 *
 * <h2>How it works</h2>
 *
 * <p>The encoder remembers exactly which colors the clients currently display on every map. For every frame it:
 *
 * <ol>
 *   <li>compares the new frame against that remembered state in tiles of 16x16 pixels, using a vectorized array
 *       comparison to skip unchanged pixels quickly,</li>
 *   <li>merges the changed tiles of each map into as few rectangles as possible,</li>
 *   <li>chooses whichever is smaller: sending the rectangles separately, or sending one bounding rectangle that
 *       contains all of them, and</li>
 *   <li>sends the maps with the most urgent changes first until the byte budget of the frame is used up.</li>
 * </ol>
 *
 * <p>Maps that did not change cost nothing. Maps that do not fit into the budget are simply not sent. Their
 * remembered state is left untouched, so the next frame is compared against what the clients really display,
 * and maps that have been waiting are sent before all others. This spreads a scene cut over a few frames
 * instead of flooding the connection, and it never leaves a map permanently out of date.
 *
 * <p>Tiles where only a handful of pixels changed are usually caused by dithering noise rather than real
 * motion. Those tiles are held back for a few frames, because the noise often disappears on its own, and are
 * sent eventually so the picture never drifts away from the source.
 *
 * <p>The encoder is not thread-safe; callers must synchronize access themselves.
 */
public final class DeltaMapEncoder {

  /**
   * The default maximum number of bytes sent per frame, 128 KiB. At 30 frames per second this is at most about
   * 3.8 MB/s per viewer, while a full 5x5 map wall would need about 12 MB/s without delta encoding.
   *
   * <p>Earlier versions used a default of 2 MiB per frame. The lower default keeps the connections of the viewers
   * responsive, but a scene cut on a large wall now takes a few more frames to appear completely. Pass a larger
   * budget to the constructor of {@code CompressedMapResult} to get the old behavior back.
   */
  public static final int DEFAULT_MAX_BYTES_PER_FRAME = 128 * 1024;

  private static final int MAP_SIZE = MapLayout.MAP_SIZE;
  private static final int MAP_PIXELS = MAP_SIZE * MAP_SIZE;
  private static final int TILE_SIZE = 16;
  private static final int TILES_PER_ROW = MAP_SIZE / TILE_SIZE;
  private static final int TILE_COUNT = TILES_PER_ROW * TILES_PER_ROW;
  private static final int NOISE_THRESHOLD = 4;
  private static final int MAX_NOISE_DEFERRAL = 6;
  private static final long WAITING_PRIORITY = 1L << 32;
  private static final long INITIAL_PRIORITY = Long.MAX_VALUE;

  private final MapLayout layout;
  private final int maxBytesPerFrame;
  private final byte[][] clientState;
  private final boolean[] synced;
  private final int[] waitingFrames;
  private final byte[][] deferredNoiseFrames;
  private final int[] changedPixelsPerTile;

  /**
   * Constructs a new encoder for the specified layout.
   *
   * @param layout           the layout of the image on the maps
   * @param maxBytesPerFrame the maximum number of bytes to send per frame; the most urgent map is always sent,
   *                         even if it alone exceeds the budget
   * @throws IllegalArgumentException if the budget is not positive
   */
  public DeltaMapEncoder(final MapLayout layout, final int maxBytesPerFrame) {
    Preconditions.checkNotNull(layout, "Layout must not be null");
    Preconditions.checkArgument(maxBytesPerFrame > 0, "Byte budget must be positive");

    final int mapCount = layout.getMapCount();
    this.layout = layout;
    this.maxBytesPerFrame = maxBytesPerFrame;
    this.clientState = new byte[mapCount][MAP_PIXELS];
    this.synced = new boolean[mapCount];
    this.waitingFrames = new int[mapCount];
    this.deferredNoiseFrames = new byte[mapCount][TILE_COUNT];
    this.changedPixelsPerTile = new int[TILE_COUNT];
  }

  /**
   * Gets the layout this encoder was created for.
   *
   * @return the layout
   */
  public MapLayout getLayout() {
    return this.layout;
  }

  /**
   * Encodes the next frame into the patches that should be sent to the clients. The returned patches are
   * considered delivered, so every client must receive all of them for the following frames to be correct.
   *
   * @param image the frame as map palette indices, laid out row by row, matching the image size of the layout
   * @return the patches to send, or an empty list if nothing visible changed
   * @throws IllegalArgumentException if the image size does not match the layout
   */
  public List<MapTilePatch> encode(final byte[] image) {
    Preconditions.checkNotNull(image, "Image must not be null");
    final int imageWidth = this.layout.getImageWidth();
    final int imageHeight = this.layout.getImageHeight();
    final int expectedLength = imageWidth * imageHeight;
    Preconditions.checkArgument(image.length == expectedLength, "Image size does not match layout");

    final List<MapCandidate> candidates = this.collectCandidates(image);
    if (candidates.isEmpty()) {
      return List.of();
    }

    final Comparator<MapCandidate> byPriority = Comparator.comparingLong(MapCandidate::getPriority);
    final Comparator<MapCandidate> mostUrgentFirst = byPriority.reversed();
    candidates.sort(mostUrgentFirst);
    return this.acceptWithinBudget(candidates);
  }

  private List<MapCandidate> collectCandidates(final byte[] image) {
    final int mapCount = this.layout.getMapCount();
    final List<MapCandidate> candidates = new ArrayList<>();
    for (int map = 0; map < mapCount; map++) {
      final MapRegion region = this.layout.getRegion(map);
      if (region.isEmpty()) {
        continue;
      }

      final MapCandidate candidate;
      if (this.synced[map]) {
        candidate = this.createDeltaCandidate(image, map, region);
      } else {
        candidate = this.createFullCandidate(image, map, region);
      }
      if (candidate != null) {
        candidates.add(candidate);
      }
    }
    return candidates;
  }

  private List<MapTilePatch> acceptWithinBudget(final List<MapCandidate> candidates) {
    final List<MapTilePatch> accepted = new ArrayList<>();
    int usedBytes = 0;
    for (final MapCandidate candidate : candidates) {
      final int map = candidate.getMap();
      final int candidateSize = candidate.getSize();
      final boolean fitsBudget = usedBytes + candidateSize <= this.maxBytesPerFrame;
      final boolean isFirst = accepted.isEmpty();
      if (!fitsBudget && !isFirst) {
        this.waitingFrames[map]++;
        continue;
      }

      usedBytes += candidateSize;
      final List<MapTilePatch> patches = candidate.getPatches();
      for (final MapTilePatch patch : patches) {
        this.applyToClientState(map, patch);
        accepted.add(patch);
      }
      this.synced[map] = true;
      this.waitingFrames[map] = 0;
    }
    return accepted;
  }

  /**
   * Creates patches that describe the complete picture the clients currently display. Send these to viewers
   * that start watching while the stream is running, so they can follow the deltas of the following frames.
   *
   * @return one patch for every map that has been sent at least once
   */
  public List<MapTilePatch> snapshot() {
    final int mapCount = this.layout.getMapCount();
    final List<MapTilePatch> patches = new ArrayList<>();
    for (int map = 0; map < mapCount; map++) {
      // only maps the image covers are ever sent, so a synced map never has an empty region
      if (!this.synced[map]) {
        continue;
      }
      final MapRegion region = this.layout.getRegion(map);
      final MapTilePatch patch = this.copyClientState(map, region);
      patches.add(patch);
    }
    return patches;
  }

  private MapTilePatch copyClientState(final int map, final MapRegion region) {
    final int localX = region.getLocalX();
    final int localY = region.getLocalY();
    final int width = region.getWidth();
    final int height = region.getHeight();
    final byte[] state = this.clientState[map];
    final byte[] colors = new byte[width * height];
    for (int row = 0; row < height; row++) {
      final int sourceIndex = (localY + row) * MAP_SIZE + localX;
      final int targetIndex = row * width;
      System.arraycopy(state, sourceIndex, colors, targetIndex, width);
    }
    final int mapId = this.layout.getMapId(map);
    return new MapTilePatch(mapId, localX, localY, width, height, colors);
  }

  /**
   * Forgets what the clients display, so the next frame is sent completely.
   */
  public void reset() {
    Arrays.fill(this.synced, false);
    Arrays.fill(this.waitingFrames, 0);
    for (final byte[] frames : this.deferredNoiseFrames) {
      Arrays.fill(frames, (byte) 0);
    }
  }

  private MapCandidate createFullCandidate(final byte[] image, final int map, final MapRegion region) {
    final int x = region.getLocalX();
    final int y = region.getLocalY();
    final int width = region.getWidth();
    final int height = region.getHeight();
    final MapTilePatch patch = this.layout.extractPatch(image, map, x, y, width, height);
    final List<MapTilePatch> patches = List.of(patch);
    final int size = patch.getEncodedSize();
    return new MapCandidate(map, patches, size, INITIAL_PRIORITY);
  }

  private @Nullable MapCandidate createDeltaCandidate(final byte[] image, final int map, final MapRegion region) {
    final long dirtyTiles = this.findDirtyTiles(image, map, region);
    final long tilesToSend = this.holdBackNoise(map, dirtyTiles);
    if (tilesToSend == 0) {
      return null;
    }

    final int changedPixels = this.countChangedPixels(tilesToSend);
    final List<PatchRectangle> rectangles = chooseRectangles(tilesToSend, region);
    final List<MapTilePatch> patches = this.extractPatches(image, map, rectangles);
    final int size = getEncodedSize(rectangles);

    final long waitingBonus = this.waitingFrames[map] * WAITING_PRIORITY;
    final long priority = waitingBonus + changedPixels;
    return new MapCandidate(map, patches, size, priority);
  }

  /**
   * Removes the dirty tiles that only changed a few pixels from the tiles to send, unless they have been held back
   * for too long already, and updates how long every tile has been held back.
   */
  private long holdBackNoise(final int map, final long dirtyTiles) {
    final byte[] noiseFrames = this.deferredNoiseFrames[map];
    long tilesToSend = 0;
    for (int tile = 0; tile < TILE_COUNT; tile++) {
      final long tileBit = 1L << tile;
      final boolean dirty = (dirtyTiles & tileBit) != 0;
      final boolean deferred = dirty && this.shouldDefer(noiseFrames, tile);
      if (deferred) {
        noiseFrames[tile]++;
        continue;
      }
      noiseFrames[tile] = 0;
      if (dirty) {
        tilesToSend |= tileBit;
      }
    }
    return tilesToSend;
  }

  private boolean shouldDefer(final byte[] noiseFrames, final int tile) {
    final int tileChanges = this.changedPixelsPerTile[tile];
    final boolean looksLikeNoise = tileChanges < NOISE_THRESHOLD;
    final boolean mayDeferLonger = noiseFrames[tile] < MAX_NOISE_DEFERRAL;
    return looksLikeNoise && mayDeferLonger;
  }

  private int countChangedPixels(final long tiles) {
    int changedPixels = 0;
    for (int tile = 0; tile < TILE_COUNT; tile++) {
      final long tileBit = 1L << tile;
      if ((tiles & tileBit) != 0) {
        changedPixels += this.changedPixelsPerTile[tile];
      }
    }
    return changedPixels;
  }

  /**
   * Chooses whichever is smaller: the merged rectangles of the tiles, or one bounding rectangle around all of them.
   */
  private static List<PatchRectangle> chooseRectangles(final long tiles, final MapRegion region) {
    final List<PatchRectangle> rectangles = mergeTiles(tiles, region);
    final PatchRectangle bounds = PatchRectangle.boundingBox(rectangles);
    final int boundsSize = bounds.getEncodedSize();
    final int rectanglesSize = getEncodedSize(rectangles);
    if (boundsSize <= rectanglesSize) {
      return List.of(bounds);
    }
    return rectangles;
  }

  private static int getEncodedSize(final List<PatchRectangle> rectangles) {
    int size = 0;
    for (final PatchRectangle rectangle : rectangles) {
      size += rectangle.getEncodedSize();
    }
    return size;
  }

  private List<MapTilePatch> extractPatches(final byte[] image, final int map, final List<PatchRectangle> rectangles) {
    final List<MapTilePatch> patches = new ArrayList<>(rectangles.size());
    for (final PatchRectangle rectangle : rectangles) {
      final int x = rectangle.getX();
      final int y = rectangle.getY();
      final int width = rectangle.getWidth();
      final int height = rectangle.getHeight();
      final MapTilePatch patch = this.layout.extractPatch(image, map, x, y, width, height);
      patches.add(patch);
    }
    return patches;
  }

  /**
   * Compares the region of the image against what the clients display and records how many pixels changed in
   * every tile.
   *
   * @return a bit mask with the bit of every tile that changed
   */
  private long findDirtyTiles(final byte[] image, final int map, final MapRegion region) {
    Arrays.fill(this.changedPixelsPerTile, 0);
    final byte[] state = this.clientState[map];
    final int height = region.getHeight();
    long dirtyTiles = 0;
    for (int row = 0; row < height; row++) {
      dirtyTiles |= this.findDirtyTilesInRow(image, state, region, row);
    }
    return dirtyTiles;
  }

  private long findDirtyTilesInRow(final byte[] image, final byte[] state, final MapRegion region, final int row) {
    final int localX = region.getLocalX();
    final int width = region.getWidth();
    final int mapY = region.getLocalY() + row;
    final int imageRowStart = this.getImageRowStart(region, row);
    final int stateRowStart = mapY * MAP_SIZE + localX;
    final int tileRowStart = (mapY / TILE_SIZE) * TILES_PER_ROW;

    long dirtyTiles = 0;
    int column = 0;
    while (column < width) {
      final int firstChange = findNextChange(image, imageRowStart, state, stateRowStart, column, width);
      if (firstChange < 0) {
        break;
      }

      final int tileColumn = (localX + firstChange) / TILE_SIZE;
      final int tileEnd = Math.min(width, (tileColumn + 1) * TILE_SIZE - localX);
      final int tile = tileRowStart + tileColumn;
      final int changes = countChanges(image, imageRowStart, state, stateRowStart, firstChange, tileEnd);
      this.changedPixelsPerTile[tile] += changes;
      dirtyTiles |= 1L << tile;
      column = tileEnd;
    }
    return dirtyTiles;
  }

  /**
   * Finds the first column of the row, starting at a column, where the image differs from what the clients display.
   *
   * @return the column of the first change, or -1 if the rest of the row is unchanged
   */
  private static int findNextChange(
    final byte[] image,
    final int imageRowStart,
    final byte[] state,
    final int stateRowStart,
    final int fromColumn,
    final int width
  ) {
    final int imageFrom = imageRowStart + fromColumn;
    final int imageTo = imageRowStart + width;
    final int stateFrom = stateRowStart + fromColumn;
    final int stateTo = stateRowStart + width;
    final int mismatch = Arrays.mismatch(image, imageFrom, imageTo, state, stateFrom, stateTo);
    if (mismatch < 0) {
      return -1;
    }
    return fromColumn + mismatch;
  }

  private int getImageRowStart(final MapRegion region, final int row) {
    final int imageWidth = this.layout.getImageWidth();
    final int sourceX = region.getSourceX();
    final int sourceY = region.getSourceY();
    return (sourceY + row) * imageWidth + sourceX;
  }

  private static int countChanges(
    final byte[] image,
    final int imageRowStart,
    final byte[] state,
    final int stateRowStart,
    final int fromColumn,
    final int toColumn
  ) {
    int changes = 0;
    for (int column = fromColumn; column < toColumn; column++) {
      final byte newColor = image[imageRowStart + column];
      final byte oldColor = state[stateRowStart + column];
      if (newColor != oldColor) {
        changes++;
      }
    }
    return changes;
  }

  private static List<PatchRectangle> mergeTiles(final long tiles, final MapRegion region) {
    final boolean[] pending = toPendingTiles(tiles);
    final List<PatchRectangle> rectangles = new ArrayList<>();
    for (int tileY = 0; tileY < TILES_PER_ROW; tileY++) {
      for (int tileX = 0; tileX < TILES_PER_ROW; tileX++) {
        final int tile = tileY * TILES_PER_ROW + tileX;
        if (!pending[tile]) {
          continue;
        }
        final PatchRectangle rectangle = takeRun(pending, tileX, tileY, region);
        rectangles.add(rectangle);
      }
    }
    return rectangles;
  }

  private static boolean[] toPendingTiles(final long tiles) {
    final boolean[] pending = new boolean[TILE_COUNT];
    for (int tile = 0; tile < TILE_COUNT; tile++) {
      final long tileBit = 1L << tile;
      pending[tile] = (tiles & tileBit) != 0;
    }
    return pending;
  }

  /**
   * Grows a rectangle of pending tiles from a tile, first to the right and then downwards, marks its tiles as
   * handled, and clips it to the region.
   *
   * @return the rectangle in map pixels
   */
  private static PatchRectangle takeRun(final boolean[] pending, final int tileX, final int tileY, final MapRegion region) {
    final int runWidth = measureRunWidth(pending, tileX, tileY);
    final int runHeight = measureRunHeight(pending, tileX, tileY, runWidth);
    clearRun(pending, tileX, tileY, runWidth, runHeight);

    final int x = tileX * TILE_SIZE;
    final int y = tileY * TILE_SIZE;
    final int width = runWidth * TILE_SIZE;
    final int height = runHeight * TILE_SIZE;
    return PatchRectangle.clip(x, y, width, height, region);
  }

  private static int measureRunWidth(final boolean[] pending, final int tileX, final int tileY) {
    int runWidth = 1;
    while (tileX + runWidth < TILES_PER_ROW) {
      final int next = tileY * TILES_PER_ROW + tileX + runWidth;
      if (!pending[next]) {
        break;
      }
      runWidth++;
    }
    return runWidth;
  }

  private static int measureRunHeight(final boolean[] pending, final int tileX, final int tileY, final int runWidth) {
    int runHeight = 1;
    while (tileY + runHeight < TILES_PER_ROW) {
      final boolean rowPending = isRowPending(pending, tileX, tileY + runHeight, runWidth);
      if (!rowPending) {
        break;
      }
      runHeight++;
    }
    return runHeight;
  }

  private static boolean isRowPending(final boolean[] pending, final int tileX, final int tileY, final int runWidth) {
    for (int x = tileX; x < tileX + runWidth; x++) {
      final int tile = tileY * TILES_PER_ROW + x;
      if (!pending[tile]) {
        return false;
      }
    }
    return true;
  }

  private static void clearRun(final boolean[] pending, final int tileX, final int tileY, final int runWidth, final int runHeight) {
    for (int y = tileY; y < tileY + runHeight; y++) {
      for (int x = tileX; x < tileX + runWidth; x++) {
        final int tile = y * TILES_PER_ROW + x;
        pending[tile] = false;
      }
    }
  }

  private void applyToClientState(final int map, final MapTilePatch patch) {
    final byte[] state = this.clientState[map];
    final byte[] colors = patch.getColors();
    final int x = patch.getX();
    final int y = patch.getY();
    final int width = patch.getWidth();
    final int height = patch.getHeight();
    for (int row = 0; row < height; row++) {
      final int sourceIndex = row * width;
      final int targetIndex = (y + row) * MAP_SIZE + x;
      System.arraycopy(colors, sourceIndex, state, targetIndex, width);
    }
  }

  /**
   * A rectangle inside a map that should be sent as one patch.
   */
  private static final class PatchRectangle {

    private final int x;
    private final int y;
    private final int width;
    private final int height;

    PatchRectangle(final int x, final int y, final int width, final int height) {
      this.x = x;
      this.y = y;
      this.width = width;
      this.height = height;
    }

    /**
     * Clips a rectangle of tiles to the region. The rectangle always overlaps the region, because it only consists
     * of dirty tiles, and a tile only becomes dirty when a pixel inside the region changed.
     */
    static PatchRectangle clip(final int x, final int y, final int width, final int height, final MapRegion region) {
      final int regionX = region.getLocalX();
      final int regionY = region.getLocalY();
      final int regionRight = regionX + region.getWidth();
      final int regionBottom = regionY + region.getHeight();

      final int left = Math.max(x, regionX);
      final int top = Math.max(y, regionY);
      final int right = Math.min(x + width, regionRight);
      final int bottom = Math.min(y + height, regionBottom);
      return new PatchRectangle(left, top, right - left, bottom - top);
    }

    static PatchRectangle boundingBox(final List<PatchRectangle> rectangles) {
      int left = Integer.MAX_VALUE;
      int top = Integer.MAX_VALUE;
      int right = Integer.MIN_VALUE;
      int bottom = Integer.MIN_VALUE;
      for (final PatchRectangle rectangle : rectangles) {
        left = Math.min(left, rectangle.x);
        top = Math.min(top, rectangle.y);
        right = Math.max(right, rectangle.x + rectangle.width);
        bottom = Math.max(bottom, rectangle.y + rectangle.height);
      }
      return new PatchRectangle(left, top, right - left, bottom - top);
    }

    int getX() {
      return this.x;
    }

    int getY() {
      return this.y;
    }

    int getWidth() {
      return this.width;
    }

    int getHeight() {
      return this.height;
    }

    int getEncodedSize() {
      return this.width * this.height + MapLayout.PATCH_OVERHEAD;
    }
  }

  /**
   * The patches that would bring one map up to date, together with their cost and urgency.
   */
  private static final class MapCandidate {

    private final int map;
    private final List<MapTilePatch> patches;
    private final int size;
    private final long priority;

    MapCandidate(final int map, final List<MapTilePatch> patches, final int size, final long priority) {
      this.map = map;
      this.patches = patches;
      this.size = size;
      this.priority = priority;
    }

    int getMap() {
      return this.map;
    }

    List<MapTilePatch> getPatches() {
      return this.patches;
    }

    int getSize() {
      return this.size;
    }

    long getPriority() {
      return this.priority;
    }
  }
}
