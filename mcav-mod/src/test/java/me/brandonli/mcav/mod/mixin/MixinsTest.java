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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import me.brandonli.mcav.mod.Mcv2Shaders;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every handler hands what the game gave it to the decoder's way in, and does nothing else. */
final class MixinsTest {

  @Test
  void theMapRendererHandsOnTheMapsItExtractsAndPoses() {
    final MapRenderState state = new MapRenderState();
    final PoseStack pose = new PoseStack();
    try (final MockedStatic<Mcv2Shaders> shaders = mockStatic(Mcv2Shaders.class)) {
      final MapRendererMixin mixin = new MapRendererMixin() {};
      mixin.mcav$extracted(null, null, state, new CallbackInfo("extractRenderState", false));
      shaders.verify(() -> Mcv2Shaders.extracted(state, null));
      mixin.mcav$posed(state, pose, mock(SubmitNodeCollector.class), true, 15, new CallbackInfo("render", false));
      shaders.verify(() -> Mcv2Shaders.posed(state, pose));
    }
  }

  @Test
  void theGameRendererSaysTheNextProjectionIsTheLevels() {
    try (final MockedStatic<Mcv2Shaders> shaders = mockStatic(Mcv2Shaders.class)) {
      new GameRendererMixin() {}.mcav$projecting(new CallbackInfo("renderLevel", false));
      shaders.verify(Mcv2Shaders::projecting);
    }
  }

  @Test
  void theProjectionBufferHandsOnEveryProjection() {
    final Object projection = new Object();
    try (final MockedStatic<Mcv2Shaders> shaders = mockStatic(Mcv2Shaders.class)) {
      new ProjectionMatrixBufferMixin() {}.mcav$projected(projection, new CallbackInfoReturnable<>("getBuffer", false));
      shaders.verify(() -> Mcv2Shaders.projected(projection));
    }
  }

  @Test
  void theLevelRendererHandsOnTheRenderedLevel() {
    final GraphicsResourceAllocator allocator = mock(GraphicsResourceAllocator.class);
    final CameraRenderState camera = new CameraRenderState();
    try (final MockedStatic<Mcv2Shaders> shaders = mockStatic(Mcv2Shaders.class)) {
      new LevelRendererMixin() {}.mcav$rendered(
        allocator,
        true,
        camera,
        mock(GpuBufferSlice.class),
        new Object(),
        true,
        false,
        new CallbackInfo("render", false)
      );
      shaders.verify(() -> Mcv2Shaders.rendered(allocator, camera));
    }
  }
}
