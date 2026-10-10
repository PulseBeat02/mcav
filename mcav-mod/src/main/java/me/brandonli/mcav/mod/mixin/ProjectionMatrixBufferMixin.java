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

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import me.brandonli.mcav.mod.Mcv2Shaders;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands the decoder every projection set through this overload of {@code getBuffer}: the level's, which
 * {@code GameRenderer.renderLevel} builds from the camera's ({@link GameRendererMixin} marks it), and Iris's shadow and
 * hand projections. The matrix is a JOML one, which the mod never names: Mixin passes it as an {@link Object}.
 */
@Mixin(ProjectionMatrixBuffer.class)
abstract class ProjectionMatrixBufferMixin {

  @Inject(method = "getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;", at = @At("HEAD"))
  void mcav$projected(@Coerce final Object projection, final CallbackInfoReturnable<GpuBufferSlice> callback) {
    Mcv2Shaders.projected(projection);
  }
}
