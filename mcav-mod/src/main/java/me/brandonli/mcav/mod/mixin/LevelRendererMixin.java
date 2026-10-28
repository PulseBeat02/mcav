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

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import me.brandonli.mcav.mod.Mcv2Shaders;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// runs after Iris wrote its final image at the end of LevelRenderer.render; the fog colour is a JOML vector, which
// Mixin passes as an Object
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {

  @Inject(method = "render", at = @At("RETURN"))
  void mcav$rendered(
    final GraphicsResourceAllocator allocator,
    final boolean renderBlockOutline,
    final CameraRenderState camera,
    final GpuBufferSlice fog,
    @Coerce final Object fogColor,
    final boolean renderSky,
    final boolean renderHud,
    final CallbackInfo callback
  ) {
    Mcv2Shaders.rendered(allocator, camera);
  }
}
