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
package me.brandonli.mcav.mod.mixin;

import me.brandonli.mcav.mod.Mcv2Shaders;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the decoder that the projection {@code renderLevel} is about to hand {@code ProjectionMatrixBuffer} is the
 * level's, so the shadow and hand projections Iris sets the same way later in the frame are not taken for it.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {

  @Inject(
    method = "renderLevel",
    at = @At(
      value = "INVOKE",
      target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
    )
  )
  void mcav$projecting(final CallbackInfo callback) {
    Mcv2Shaders.projecting();
  }
}
