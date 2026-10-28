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

import static java.util.Objects.requireNonNull;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Supplier;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import org.checkerframework.checker.nullness.qual.Nullable;

// the pack's chain replaces vanilla's entity_outline chain, which also draws glowing entities' outlines from the
// outline target and writes into it: it runs on an empty one, copied each frame from a target nothing draws into
final class MinecraftStripOutput implements StripOutput {

  private static final String EMPTY_OUTLINE = "MCV2 empty outline";

  private static final String TRANSPARENT = "MCV2 transparent";

  private static final String NO_COLOUR = "Render target must have a colour texture!";

  private static final int BASE_MIP_LEVEL = 0;

  private static final int FIRST_LAYER = 0;

  private static final int LEFT_COLUMN = 0;
  private static final int BOTTOM_ROW = 0;

  private final Supplier<RenderTarget> main;

  private final Supplier<@Nullable PostChain> chain;

  private final Supplier<CommandEncoder> encoder;

  private @Nullable OutlineTargets outlineTargets;

  private ByteBuffer pixels = ByteBuffer.allocateDirect(0);

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
    final RenderTarget target = this.main.get();
    return target.width;
  }

  @Override
  public int height() {
    final RenderTarget target = this.main.get();
    return target.height;
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
    commands.writeToTexture(
      colour(target),
      this.pixels,
      BASE_MIP_LEVEL,
      FIRST_LAYER,
      LEFT_COLUMN,
      target.height - rows,
      target.width,
      rows
    );
    final TextureTarget empty = this.emptyOutline(target, commands);
    final FrameGraphBuilder frame = new FrameGraphBuilder();
    final LevelTargetBundle targets = new LevelTargetBundle();
    targets.main = frame.importExternal(LevelTargetBundle.MAIN_TARGET_ID.toString(), target);
    targets.entityOutline = frame.importExternal(LevelTargetBundle.ENTITY_OUTLINE_TARGET_ID.toString(), empty);
    outline.addToFrame(frame, target.width, target.height, targets);
    frame.execute(allocator);
    return true;
  }

  // copied over again each frame, since the chain wrote into the last frame's
  private TextureTarget emptyOutline(final RenderTarget target, final CommandEncoder commands) {
    final OutlineTargets targets = this.sizeOutlineTargets(target, commands);
    final TextureTarget empty = targets.empty();
    final TextureTarget transparent = targets.transparent();
    empty.copyColorFrom(transparent);
    return empty;
  }

  private OutlineTargets sizeOutlineTargets(final RenderTarget target, final CommandEncoder commands) {
    final @Nullable OutlineTargets current = this.outlineTargets;
    if (current != null && this.hasSize(current, target)) {
      return current;
    }

    if (current != null) {
      this.destroyOutlineTargets(current);
    }

    final OutlineTargets replacement = this.createOutlineTargets(target, commands);
    this.outlineTargets = replacement;
    return replacement;
  }

  private boolean hasSize(final OutlineTargets targets, final RenderTarget target) {
    final TextureTarget empty = targets.empty();
    return empty.width == target.width && empty.height == target.height;
  }

  private void destroyOutlineTargets(final OutlineTargets targets) {
    final TextureTarget empty = targets.empty();
    final TextureTarget transparent = targets.transparent();
    empty.destroyBuffers();
    transparent.destroyBuffers();
  }

  private OutlineTargets createOutlineTargets(final RenderTarget target, final CommandEncoder commands) {
    final TextureTarget transparent = new TextureTarget(TRANSPARENT, target.width, target.height, GpuFormat.RGBA8_UNORM, null);
    // a new texture's pixels are undefined; a new direct buffer's bytes are zero
    final ByteBuffer zeros = ByteBuffer.allocateDirect(target.width * target.height * TransportStrip.CHANNELS);
    commands.writeToTexture(colour(transparent), zeros, BASE_MIP_LEVEL, FIRST_LAYER, LEFT_COLUMN, BOTTOM_ROW, target.width, target.height);
    final TextureTarget empty = new TextureTarget(EMPTY_OUTLINE, target.width, target.height, GpuFormat.RGBA8_UNORM, null);
    return new OutlineTargets(empty, transparent);
  }

  // a target has its colour texture from creation until it is destroyed
  private static GpuTexture colour(final RenderTarget target) {
    return requireNonNull(target.getColorTexture(), NO_COLOUR);
  }

  // the chain runs on empty, copied from transparent; both the size of the screen
  private record OutlineTargets(TextureTarget empty, TextureTarget transparent) {}
}
