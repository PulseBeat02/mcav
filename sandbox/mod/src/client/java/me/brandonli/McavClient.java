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
package me.brandonli;

import me.brandonli.block.ModBlockEntities;
import me.brandonli.command.ClientTestCommand;
import me.brandonli.renderer.TelevisionScreenBlockEntityRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;

public class McavClient implements ClientModInitializer {

  @Override
  public void onInitializeClient() {
    this.registerRenderers();
    this.registerClientCommands();
  }

  private void registerRenderers() {
    BlockEntityRendererFactories.register(ModBlockEntities.TELEVISION_SCREEN_BLOCK_ENTITY, TelevisionScreenBlockEntityRenderer::new);
  }

  private void registerClientCommands() {
    ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
      ClientTestCommand.register(dispatcher);
    });
  }
}
