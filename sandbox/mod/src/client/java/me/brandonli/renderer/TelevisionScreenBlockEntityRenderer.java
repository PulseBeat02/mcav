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
package me.brandonli.renderer;

import me.brandonli.block.TelevisionScreenBlockEntity;
import me.brandonli.util.GlobalTextureState;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.render.block.entity.state.BlockEntityRenderState;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;

public class TelevisionScreenBlockEntityRenderer implements BlockEntityRenderer<TelevisionScreenBlockEntity, BlockEntityRenderState> {

  public TelevisionScreenBlockEntityRenderer(final BlockEntityRendererFactory.Context context) {}

  @Override
  public BlockEntityRenderState createRenderState() {
    return new BlockEntityRenderState();
  }

  @Override
  public void render(
    final BlockEntityRenderState state,
    final MatrixStack matrices,
    final OrderedRenderCommandQueue queue,
    final CameraRenderState cameraState
  ) {
    if (!GlobalTextureState.isStreaming() || GlobalTextureState.getCurrentGlTextureId() < 0) {
      return;
    }
  }
}
