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

// the game extracts every map's colours before it renders the level, and poses the maps, which gives an anchor's
// corner, while it renders
final class ShaderFrame {

  private final List<byte[]> pages = new ArrayList<>();

  private final Map<Object, byte[]> anchorColours = new HashMap<>();

  private final List<StripAnchor> anchors = new ArrayList<>();

  private float @Nullable [] projection;

  // the render thread never changes the colours before the frame ends
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

  // the corner is relative to the camera
  void posed(final Object texture, final float cornerX, final float cornerY, final float cornerZ) {
    final byte[] colours = this.anchorColours.get(texture);
    if (colours != null) {
      this.anchors.add(StripAnchor.of(colours, cornerX, cornerY, cornerZ));
    }
  }

  // column-major
  void projected(final float[] projection) {
    this.projection = projection;
  }

  float @Nullable [] projection() {
    return this.projection;
  }

  List<byte[]> pages() {
    return this.pages;
  }

  List<StripAnchor> anchors() {
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
