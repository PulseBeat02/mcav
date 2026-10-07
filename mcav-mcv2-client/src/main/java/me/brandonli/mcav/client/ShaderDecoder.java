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

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Decodes MCV2 under an Iris shader pack. Iris draws the world with the shader pack's programs and writes the pack's
 * final image over the game's main target after the level is rendered, so the MCV2 pack's text shaders never put the
 * transport strip where its post chain reads it, and whatever the chain drew is painted over. With a shader pack in use,
 * this builds the strip from the frame's MCV2 maps once the shader pack's final image is done, writes it into the main
 * target and runs the pack's chain over it. Nothing else of the frame changes, and a frame without MCV2 maps is left as
 * Iris drew it. Used on the render thread only.
 */
final class ShaderDecoder {

  /** How often the pack's layout is read again: a new pack comes with a resource reload, at most every few seconds. */
  private static final long LAYOUT_NANOS = 1_000_000_000L;

  /** The corner of a map as the game draws it, in the map's own space: its top left, just in front of the frame. */
  static final float MAP_DEPTH = -0.01F;

  /** The hooks the game calls, one bit each: the decoder works only once the game has called every one of them. */
  private static final int EXTRACTED = 1;

  private static final int POSED = 2;

  private static final int PROJECTED = 4;

  private static final int RENDERED = 8;

  private static final int ALL_HOOKS = EXTRACTED | POSED | PROJECTED | RENDERED;

  private static Optional<ShaderDecoder> current = Optional.empty();

  private final boolean testedIris;

  private final BooleanSupplier shaderPackInUse;

  private final BooleanSupplier shadowPass;

  private final Supplier<Optional<String>> config;

  private final LongSupplier clock;

  private final GameMatrices matrices;

  private final StripOutput output;

  private final ShaderFrame frame = new ShaderFrame();

  private Optional<PackLayout> layout = Optional.empty();

  private long layoutRead;

  private boolean layoutKnown;

  private int hooks;

  private boolean levelProjection;

  /**
   * Constructs the decoder.
   *
   * @param testedIris      whether the Iris installed is the version this decoder was proven with: another version may
   *                        draw in another order, so this stays off for it
   * @param shaderPackInUse whether Iris draws a shader pack now
   * @param shadowPass      whether Iris is drawing its shadow pass, which draws the maps again from the sun's view
   * @param config          the text of the pack's generated layout, empty while no MCV2 pack is loaded
   * @param clock           nanoseconds
   * @param matrices        reads the game's matrices
   * @param output          the game's main target and the pack's chain
   */
  ShaderDecoder(
    final boolean testedIris,
    final BooleanSupplier shaderPackInUse,
    final BooleanSupplier shadowPass,
    final Supplier<Optional<String>> config,
    final LongSupplier clock,
    final GameMatrices matrices,
    final StripOutput output
  ) {
    this.testedIris = testedIris;
    this.shaderPackInUse = shaderPackInUse;
    this.shadowPass = shadowPass;
    this.config = config;
    this.clock = clock;
    this.matrices = matrices;
    this.output = output;
  }

  /** The decoder the mixins report to, from the moment the mod starts. */
  static Optional<ShaderDecoder> current() {
    return current;
  }

  /** Makes a decoder, or none, the one the mixins report to. */
  static void install(final Optional<ShaderDecoder> decoder) {
    current = decoder;
  }

  /**
   * Whether MCV2 decodes under a shader pack in this client: Iris is the tested version and the game has called every
   * hook of the decoder, which it does only where its mixins applied. The map hooks run once the player sees a map,
   * such as the dithered maps a screen shows a player whose shaders keep MCV2 from decoding.
   */
  boolean decodesUnderShaders() {
    return this.testedIris && this.hooks == ALL_HOOKS;
  }

  /** A map's state was extracted; see {@link ShaderFrame#extracted}. */
  void extracted(final Object texture, final byte[] colours) {
    this.hooks |= EXTRACTED;
    this.frame.extracted(texture, colours);
  }

  /**
   * A map was posed. Iris's shadow pass poses the maps again, from the sun's view, which is not where the screens are.
   *
   * @param texture the map's texture
   * @param pose    the pose the map is drawn with: its top left corner is at the origin, just in front of the frame
   */
  void posed(final Object texture, final PoseStack.Pose pose) {
    this.hooks |= POSED;
    // a decoder of another Iris never decodes, and only the tested one is asked about its shadow pass
    if (!this.testedIris || this.shadowPass.getAsBoolean()) {
      return;
    }
    final float[] corner = TransportStrip.transform(this.matrices.pose(pose), 0, 0, MAP_DEPTH, 1);
    this.frame.posed(texture, corner[0], corner[1], corner[2]);
  }

  /** The game is about to set the projection it renders the level with: the next one {@link #projected} is that one. */
  void projecting() {
    this.levelProjection = true;
  }

  /**
   * The game set a projection. Only the one it renders the level with is the frame's: Iris sets its shadow pass's and
   * its hands' the same way. With shaders on, Iris draws the view bobbing through the camera's view rotation, which
   * {@link #rendered} reads, instead of through this projection.
   *
   * @param projection the projection, a JOML 4x4 float matrix
   */
  void projected(final @Nullable Object projection) {
    if (!this.levelProjection) {
      return;
    }
    this.levelProjection = false;
    this.hooks |= PROJECTED;
    this.frame.projected(GameMatrices.floats(projection));
  }

  /**
   * The level is rendered, and with a shader pack, Iris's final image is in the main target.
   *
   * @param allocator the frame's pool of render targets
   * @param camera    the frame's camera
   */
  void rendered(final GraphicsResourceAllocator allocator, final CameraRenderState camera) {
    this.hooks |= RENDERED;
    try {
      final float @Nullable [] projection = this.frame.projection();
      if (!this.testedIris || projection == null || this.frame.isEmpty() || !this.shaderPackInUse.getAsBoolean()) {
        return;
      }
      final Optional<PackLayout> pack = this.layout();
      if (pack.isEmpty()) {
        return;
      }
      final int width = this.output.width();
      // a window too narrow for a descriptor row, or too low for the strip, has no room for MCV2, as without shaders
      if (width < TransportStrip.DESCRIPTOR_PIXELS || pack.get().stripRows(width) > this.output.height()) {
        return;
      }
      final float[] viewRotation = this.matrices.viewRotation(camera);
      final byte[] strip = TransportStrip.build(pack.get(), width, this.frame.pages(), this.frame.anchors(), viewRotation, projection);
      this.output.decode(strip, pack.get().stripRows(width), allocator);
    } finally {
      this.frame.finish();
    }
  }

  private Optional<PackLayout> layout() {
    final long now = this.clock.getAsLong();
    if (!this.layoutKnown || now - this.layoutRead >= LAYOUT_NANOS) {
      this.layout = this.config.get().flatMap(PackLayout::parse);
      this.layoutRead = now;
      this.layoutKnown = true;
    }
    return this.layout;
  }
}
