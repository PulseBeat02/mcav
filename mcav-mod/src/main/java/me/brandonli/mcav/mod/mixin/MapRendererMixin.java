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

import com.mojang.blaze3d.vertex.PoseStack;
import me.brandonli.mcav.mod.Mcv2Shaders;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the decoder about every map the game extracts and poses; how anything is drawn stays as it was. */
@Mixin(MapRenderer.class)
abstract class MapRendererMixin {

  @Inject(method = "extractRenderState", at = @At("TAIL"))
  void mcav$extracted(final MapId id, final MapItemSavedData data, final MapRenderState state, final CallbackInfo info) {
    Mcv2Shaders.extracted(state, data);
  }

  @Inject(method = "render", at = @At("HEAD"))
  void mcav$posed(
    final MapRenderState state,
    final PoseStack pose,
    final SubmitNodeCollector collector,
    final boolean inFrame,
    final int light,
    final CallbackInfo info
  ) {
    Mcv2Shaders.posed(state, pose);
  }
}
