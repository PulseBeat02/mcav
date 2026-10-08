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

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * What the mod's mixins call: they live in a package of their own, as Mixin requires, so this is their way into the
 * decoder. Every call is on the render thread.
 */
public final class Mcv2Shaders {

  /** The constants the server generates into the MCV2 pack. */
  static final Identifier LAYOUT = Identifier.fromNamespaceAndPath("mcav", "shaders/include/mcv2_config.glsl");

  /** The vanilla post chain the MCV2 pack replaces with its decoder. */
  static final Identifier OUTLINE_CHAIN = Identifier.withDefaultNamespace("entity_outline");

  private Mcv2Shaders() {}

  /**
   * The game extracted a map's state, before it renders the level.
   *
   * @param state the map's render state, which names its texture
   * @param data  the map's data, its colours
   */
  public static void extracted(final MapRenderState state, final MapItemSavedData data) {
    final Identifier texture = state.texture;
    if (texture != null) {
      ShaderDecoder.current().ifPresent(decoder -> decoder.extracted(texture, data.colors));
    }
  }

  /**
   * The game posed a map, while it renders the level.
   *
   * @param state the map's render state
   * @param pose  the pose the map is drawn with: its top left corner is at the origin, just in front of the frame
   */
  public static void posed(final MapRenderState state, final PoseStack pose) {
    final Identifier texture = state.texture;
    if (texture != null) {
      ShaderDecoder.current().ifPresent(decoder -> decoder.posed(texture, pose.last()));
    }
  }

  /** The game is about to set the projection it renders the level with. */
  public static void projecting() {
    ShaderDecoder.current().ifPresent(ShaderDecoder::projecting);
  }

  /**
   * The game set a projection, the level's or another.
   *
   * @param projection the projection, a JOML 4x4 float matrix
   */
  public static void projected(final @Nullable Object projection) {
    ShaderDecoder.current().ifPresent(decoder -> decoder.projected(projection));
  }

  /**
   * The game rendered the level, and with a shader pack, Iris's final image is in the main target.
   *
   * @param allocator the frame's pool of render targets
   * @param camera    the frame's camera
   */
  public static void rendered(final GraphicsResourceAllocator allocator, final CameraRenderState camera) {
    ShaderDecoder.current().ifPresent(decoder -> decoder.rendered(allocator, camera));
  }

  /**
   * Starts the decoder of this client, the one the mixins report to.
   *
   * @param irisVersion the version of the Iris installed, empty without Iris
   * @return whether MCV2 decodes under a shader pack in this client now, false for good when the game's matrices cannot
   *     be read and there is no decoder
   */
  static BooleanSupplier start(final Optional<String> irisVersion) {
    final Optional<ShaderDecoder> decoder = decoder(irisVersion);
    ShaderDecoder.install(decoder);
    return () -> decoder.filter(ShaderDecoder::decodesUnderShaders).isPresent();
  }

  /**
   * The decoder of this client.
   *
   * @param irisVersion the version of the Iris installed, empty without Iris
   * @return the decoder, which decodes under shader packs only with the Iris version it was proven with, or empty when
   *     the game's matrices cannot be read
   */
  static Optional<ShaderDecoder> decoder(final Optional<String> irisVersion) {
    return GameMatrices.find().map(matrices -> decoder(irisVersion, matrices));
  }

  private static ShaderDecoder decoder(final Optional<String> irisVersion, final GameMatrices matrices) {
    final MinecraftStripOutput output = new MinecraftStripOutput(Mcv2Shaders::mainTarget, Mcv2Shaders::outlineChain, Mcv2Shaders::commands);
    final boolean tested = irisVersion.filter(IrisShaders::isTested).isPresent();
    return new ShaderDecoder(
      tested,
      Mcv2Shaders::shaderPackInUse,
      Mcv2Shaders::shadowPass,
      Mcv2Shaders::layout,
      System::nanoTime,
      matrices,
      output
    );
  }

  /** The game's main target, which Iris's final pass writes the shader pack's image into. */
  static RenderTarget mainTarget() {
    return Minecraft.getInstance().gameRenderer.mainRenderTarget();
  }

  /** The post chain the MCV2 pack replaces vanilla's outline chain with, or null while no pack gives one. */
  static @Nullable PostChain outlineChain() {
    return Minecraft.getInstance().getShaderManager().getPostChain(OUTLINE_CHAIN, LevelTargetBundle.OUTLINE_TARGETS);
  }

  /** A command encoder of the game's GPU device. */
  static CommandEncoder commands() {
    return RenderSystem.getDevice().createCommandEncoder();
  }

  static boolean shaderPackInUse() {
    return IrisApi.getInstance().isShaderPackInUse();
  }

  static boolean shadowPass() {
    return IrisApi.getInstance().isRenderingShadowPass();
  }

  /** The text of the MCV2 pack's generated layout, empty while no MCV2 pack is loaded. */
  static Optional<String> layout() {
    return Minecraft.getInstance().getResourceManager().getResource(LAYOUT).flatMap(Mcv2Shaders::read);
  }

  static Optional<String> read(final Resource resource) {
    try (InputStream in = resource.open()) {
      return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (final IOException unreadable) {
      return Optional.empty();
    }
  }
}
