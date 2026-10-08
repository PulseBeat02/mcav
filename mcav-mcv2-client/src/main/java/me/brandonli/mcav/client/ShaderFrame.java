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
package me.brandonli.mcav.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The MCV2 maps of the frame being rendered. The game extracts every map's state before it renders the level, which is
 * when a page's colours and an anchor's are seen, and poses the maps while it renders, which is when an anchor's corner
 * is known. Used on the render thread only.
 */
final class ShaderFrame {

  private final List<byte[]> pages = new ArrayList<>();

  private final Map<Object, byte[]> anchorColours = new HashMap<>();

  private final List<StripAnchor> anchors = new ArrayList<>();

  private float @Nullable [] projection;

  /**
   * Notes a map whose state the game extracted.
   *
   * @param texture the map's texture, which names it while it is posed
   * @param colours the map's colours, read before the frame ends and never changed by the render thread meanwhile
   */
  void extracted(final Object texture, final byte[] colours) {
    if (Mcv2Maps.isPage(colours)) {
      this.pages.add(colours);
      this.anchorColours.remove(texture);
    } else if (Mcv2Maps.isAnchor(colours)) {
      this.anchorColours.put(texture, colours);
    } else {
      this.anchorColours.remove(texture);
    }
  }

  /**
   * Notes where the game drew a map.
   *
   * @param texture the map's texture
   * @param cornerX the map's top left corner relative to the camera, x
   * @param cornerY y
   * @param cornerZ z
   */
  void posed(final Object texture, final float cornerX, final float cornerY, final float cornerZ) {
    final byte[] colours = this.anchorColours.get(texture);
    if (colours != null) {
      this.anchors.add(StripAnchor.of(colours, cornerX, cornerY, cornerZ));
    }
  }

  /**
   * Notes the projection the game renders the level with.
   *
   * @param projection a column-major 4x4 matrix
   */
  void projected(final float[] projection) {
    this.projection = projection;
  }

  /** The projection of this frame, or null before the game set one. */
  float @Nullable [] projection() {
    return this.projection;
  }

  /** The page maps seen this frame. */
  List<byte[]> pages() {
    return this.pages;
  }

  /** The anchor maps drawn this frame. */
  List<StripAnchor> anchors() {
    return this.anchors;
  }

  /** Whether this frame drew any MCV2 map. */
  boolean isEmpty() {
    return this.pages.isEmpty() && this.anchors.isEmpty();
  }

  /** Starts the next frame: its maps and projection are used up; the anchors stay known until a map changes. */
  void finish() {
    this.pages.clear();
    this.anchors.clear();
    this.projection = null;
  }
}
