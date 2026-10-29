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

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.checkerframework.checker.nullness.qual.Nullable;

// Iris writes the shader pack's final image over the main target, so the MCV2 pack's text shaders never put the
// strip where its chain reads it: this builds the strip from the frame's maps after Iris, writes it and runs the chain
final class ShaderDecoder {

  // a new pack comes with a resource reload, at most every few seconds
  private static final long LAYOUT_NANOSECONDS = 1_000_000_000L;

  // a map's top left corner in its own space, just in front of the frame
  private static final float MAP_CORNER_X = 0F;
  private static final float MAP_CORNER_Y = 0F;
  private static final float MAP_DEPTH = -0.01F;

  // one bit per hook; the decoder works only once the game has called every one
  private static final int EXTRACTED = 1;
  private static final int POSED = 2;
  private static final int PROJECTED = 4;
  private static final int RENDERED = 8;

  private static final int ALL_HOOKS = EXTRACTED | POSED | PROJECTED | RENDERED;

  private static @Nullable ShaderDecoder current;

  private final boolean testedIris;

  private final BooleanSupplier shaderPackInUse;

  private final BooleanSupplier shadowPass;

  private final Supplier<Optional<String>> packConstants;

  private final LongSupplier clock;

  private final GameMatrices matrices;

  private final StripOutput output;

  private final ShaderFrame frame = new ShaderFrame();

  private @Nullable PackLayout layout;

  private long layoutRead;

  private boolean layoutKnown;

  private int hooks;

  private boolean levelProjection;

  // another Iris version may draw in another order, so the decoder stays off for it; the shadow pass draws the maps
  // again from the sun's view
  ShaderDecoder(
    final boolean testedIris,
    final BooleanSupplier shaderPackInUse,
    final BooleanSupplier shadowPass,
    final Supplier<Optional<String>> packConstants,
    final LongSupplier clock,
    final GameMatrices matrices,
    final StripOutput output
  ) {
    this.testedIris = testedIris;
    this.shaderPackInUse = shaderPackInUse;
    this.shadowPass = shadowPass;
    this.packConstants = packConstants;
    this.clock = clock;
    this.matrices = matrices;
    this.output = output;
  }

  static Optional<ShaderDecoder> current() {
    return Optional.ofNullable(current);
  }

  static void install(final ShaderDecoder decoder) {
    current = decoder;
  }

  static void uninstall() {
    current = null;
  }

  // the game calls every hook only where the mixins applied, and the map hooks only once the player sees a map
  boolean decodesUnderShaders() {
    return this.testedIris && this.hooks == ALL_HOOKS;
  }

  void extracted(final Object texture, final byte[] colours) {
    this.hooks |= EXTRACTED;
    this.frame.extracted(texture, colours);
  }

  // Iris's shadow pass poses the maps again from the sun's view, which is not where the screens are
  void posed(final Object texture, final PoseStack.Pose pose) {
    this.hooks |= POSED;

    if (!this.testedIris || this.shadowPass.getAsBoolean()) {
      return;
    }

    final float[] corner = TransportStrip.transform(
      this.matrices.pose(pose),
      MAP_CORNER_X,
      MAP_CORNER_Y,
      MAP_DEPTH,
      TransportStrip.POINT_W
    );
    this.frame.posed(texture, corner[0], corner[1], corner[2]);
  }

  // the next projection is the level's
  void projecting() {
    this.levelProjection = true;
  }

  // only the level's projection is the frame's: Iris sets its shadow pass's and its hands' the same way; with shaders,
  // Iris draws the view bobbing through the camera's view rotation instead
  void projected(final @Nullable Object projection) {
    if (!this.levelProjection) {
      return;
    }

    this.levelProjection = false;
    this.hooks |= PROJECTED;
    this.frame.projected(GameMatrices.floats(projection));
  }

  void rendered(final GraphicsResourceAllocator allocator, final CameraRenderState camera) {
    this.hooks |= RENDERED;
    try {
      final float @Nullable [] projection = this.frame.projection();
      if (!this.testedIris || projection == null || this.frame.isEmpty() || !this.shaderPackInUse.getAsBoolean()) {
        return;
      }
      final @Nullable PackLayout pack = this.layout();
      if (pack == null) {
        return;
      }
      final int width = this.output.width();
      if (width < TransportStrip.DESCRIPTOR_PIXELS || pack.stripRows(width) >= this.output.height()) {
        return;
      }
      final float[] viewRotation = this.matrices.viewRotation(camera);
      final byte[] strip = TransportStrip.build(pack, width, this.frame.pages(), this.frame.anchors(), viewRotation, projection);
      this.output.decode(strip, pack.stripRows(width), allocator);
    } finally {
      this.frame.finish();
    }
  }

  private @Nullable PackLayout layout() {
    final long now = this.clock.getAsLong();
    if (!this.layoutKnown || now - this.layoutRead >= LAYOUT_NANOSECONDS) {
      final Optional<String> constants = this.packConstants.get();
      final Optional<PackLayout> parsed = constants.flatMap(PackLayout::parse);
      this.layout = parsed.orElse(null);
      this.layoutRead = now;
      this.layoutKnown = true;
    }
    return this.layout;
  }
}
