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

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The game's main target and the MCV2 pack's post chain, which is the vanilla {@code entity_outline} chain the pack
 * replaces. The chain also draws the outlines of glowing entities from the outline target, and writes into it; it runs
 * here on an empty one, copied each frame from a target nothing draws into, so it draws MCV2's screens and nothing else.
 */
final class MinecraftStripOutput implements StripOutput {

  private static final String EMPTY_OUTLINE = "MCV2 empty outline";

  private static final String TRANSPARENT = "MCV2 transparent";

  private static final String NO_COLOUR = "A render target without a colour texture";

  private final Supplier<RenderTarget> main;

  private final Supplier<@Nullable PostChain> chain;

  private final Supplier<CommandEncoder> encoder;

  private @Nullable OutlineTargets outlineTargets;

  /** The strip's pixels as the GPU takes them, kept from frame to frame: a frame's strip is the size of the last one. */
  private ByteBuffer pixels = ByteBuffer.allocateDirect(0);

  /**
   * Constructs the output.
   *
   * @param main    the game's main target
   * @param chain   the pack's outline chain, or null while it has none
   * @param encoder a command encoder of the game's GPU device
   */
  MinecraftStripOutput(
    final Supplier<RenderTarget> main,
    final Supplier<@Nullable PostChain> chain,
    final Supplier<CommandEncoder> encoder
  ) {
    this.main = main;
    this.chain = chain;
    this.encoder = encoder;
  }

  @Override
  public int width() {
    return this.main.get().width;
  }

  @Override
  public int height() {
    return this.main.get().height;
  }

  @Override
  public boolean decode(final byte[] strip, final int rows, final GraphicsResourceAllocator allocator) {
    final PostChain outline = this.chain.get();
    if (outline == null) {
      return false;
    }
    final RenderTarget target = this.main.get();
    final int rowBytes = target.width * TransportStrip.CHANNELS;
    if (this.pixels.capacity() < rows * rowBytes) {
      this.pixels = ByteBuffer.allocateDirect(rows * rowBytes).order(ByteOrder.nativeOrder());
    }
    this.pixels.clear();
    // texture rows count from the bottom of the screen, the strip's from the top
    for (int row = rows - 1; row >= 0; row--) {
      this.pixels.put(strip, row * rowBytes, rowBytes);
    }
    this.pixels.flip();
    final CommandEncoder commands = this.encoder.get();
    commands.writeToTexture(colour(target), this.pixels, 0, 0, 0, target.height - rows, target.width, rows);
    final TextureTarget empty = this.emptyOutline(target, commands);
    final FrameGraphBuilder frame = new FrameGraphBuilder();
    final LevelTargetBundle targets = new LevelTargetBundle();
    targets.main = frame.importExternal(LevelTargetBundle.MAIN_TARGET_ID.toString(), target);
    targets.entityOutline = frame.importExternal(LevelTargetBundle.ENTITY_OUTLINE_TARGET_ID.toString(), empty);
    outline.addToFrame(frame, target.width, target.height, targets);
    frame.execute(allocator);
    return true;
  }

  /** The outline target of this frame, transparent: the chain wrote into the last frame's, so it is copied over again. */
  private TextureTarget emptyOutline(final RenderTarget target, final CommandEncoder commands) {
    OutlineTargets targets = this.outlineTargets;
    if (targets == null || targets.empty().width != target.width || targets.empty().height != target.height) {
      if (targets != null) {
        targets.empty().destroyBuffers();
        targets.transparent().destroyBuffers();
      }
      final TextureTarget transparent = new TextureTarget(TRANSPARENT, target.width, target.height, GpuFormat.RGBA8_UNORM, null);
      // a new texture's pixels are undefined; a new direct buffer's bytes are zero
      final ByteBuffer zeros = ByteBuffer.allocateDirect(target.width * target.height * TransportStrip.CHANNELS);
      commands.writeToTexture(colour(transparent), zeros, 0, 0, 0, 0, target.width, target.height);
      targets = new OutlineTargets(new TextureTarget(EMPTY_OUTLINE, target.width, target.height, GpuFormat.RGBA8_UNORM, null), transparent);
      this.outlineTargets = targets;
    }
    targets.empty().copyColorFrom(targets.transparent());
    return targets.empty();
  }

  /** A target's colour texture, which a target has from the moment it is made until it is destroyed. */
  private static GpuTexture colour(final RenderTarget target) {
    return Objects.requireNonNull(target.getColorTexture(), NO_COLOUR);
  }

  /** The outline target the chain runs on, and the transparent one it is copied from, both the size of the screen. */
  private record OutlineTargets(TextureTarget empty, TextureTarget transparent) {}
}
