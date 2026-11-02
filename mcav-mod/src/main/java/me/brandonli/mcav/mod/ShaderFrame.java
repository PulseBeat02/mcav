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
package me.brandonli.mcav.mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;

// the game extracts every map's colors before it renders the level, and poses the maps, which gives an anchor's
// corner, while it renders
final class ShaderFrame {

  private final List<byte[]> pages;
  private final Map<Object, byte[]> anchorColors;
  private final List<StripAnchor> anchors;

  private float @Nullable [] projection;

  ShaderFrame() {
    this.pages = new ArrayList<>();
    this.anchorColors = new HashMap<>();
    this.anchors = new ArrayList<>();
  }

  // the render thread never changes the colors before the frame ends
  void extracted(final Object texture, final byte[] colors) {
    if (Mcv2Maps.isPage(colors)) {
      this.pages.add(colors);
      this.anchorColors.remove(texture);
    } else if (Mcv2Maps.isAnchor(colors)) {
      this.anchorColors.put(texture, colors);
    } else {
      this.anchorColors.remove(texture);
    }
  }

  // the corner is relative to the camera
  void posed(final Object texture, final float cornerX, final float cornerY, final float cornerZ) {
    final byte[] colors = this.anchorColors.get(texture);
    if (colors != null) {
      final StripAnchor anchor = StripAnchor.from(colors, cornerX, cornerY, cornerZ);
      this.anchors.add(anchor);
    }
  }

  // column-major
  void projected(final float[] projection) {
    this.projection = projection;
  }

  float @Nullable [] getProjection() {
    return this.projection;
  }

  List<byte[]> getPages() {
    return this.pages;
  }

  List<StripAnchor> getAnchors() {
    return this.anchors;
  }

  boolean isEmpty() {
    return this.pages.isEmpty() && this.anchors.isEmpty();
  }

  // the anchors stay known until a map changes
  void finish() {
    this.pages.clear();
    this.anchors.clear();
    this.projection = null;
  }
}
